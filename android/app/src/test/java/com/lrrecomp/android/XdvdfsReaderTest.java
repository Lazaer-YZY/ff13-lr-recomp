package com.lrrecomp.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/**
 * XdvdfsReader on a small synthetic image, and optionally on a real disc:
 * {@code LR_ISO=path/to.iso ./gradlew :app:testDebugUnitTest --tests '*XdvdfsReaderTest*'}
 * writes app/build/xdvdfs_list.txt in the format of {@code tools/xdvdfs.py list}.
 */
public class XdvdfsReaderTest {
    private static final int S = XdvdfsReader.SECTOR;

    private static int putEntry(ByteBuffer dir, int at, int left, int right, int sector, int size, boolean isDir,
            String name) {
        dir.putShort(at, (short) left).putShort(at + 2, (short) right).putInt(at + 4, sector).putInt(at + 8, size);
        dir.put(at + 12, (byte) (isDir ? 0x10 : 0x20)).put(at + 13, (byte) name.length());
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; ++i) dir.put(at + 14 + i, bytes[i]);
        return (at + 14 + bytes.length + 3) & ~3;
    }

    private static byte[] fakeXex(long titleId) {
        ByteBuffer xex = ByteBuffer.allocate(0x100).order(ByteOrder.BIG_ENDIAN);
        xex.putInt(0, 0x58455832).putInt(0x14, 2);
        xex.putInt(0x18, 0x00010001).putInt(0x1C, 0);
        xex.putInt(0x20, 0x00040006).putInt(0x24, 0x40);
        xex.putInt(0x40 + 12, (int) titleId);
        return xex.array();
    }

    @Test
    public void listsAndExtractsSyntheticImage() throws IOException {
        long base = XdvdfsReader.BASES[0];
        byte[] xex = fakeXex(IsoImport.TITLE_ID);
        byte[] inner = new byte[5 * S + 123];
        for (int i = 0; i < inner.length; ++i) inner[i] = (byte) (i * 31);
        File image = File.createTempFile("xdvdfs", ".iso");
        image.deleteOnExit();
        try (RandomAccessFile file = new RandomAccessFile(image, "rw")) {
            ByteBuffer volume = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
            volume.put("MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII)).putInt(40).putInt(S);
            file.seek(base + 32L * S);
            file.write(volume.array());
            // Root (sector 40): "b.txt" with "a" (dir) on the left and "default.xex" on the right.
            ByteBuffer root = ByteBuffer.allocate(S).order(ByteOrder.LITTLE_ENDIAN);
            java.util.Arrays.fill(root.array(), (byte) 0xFF);
            int next = putEntry(root, 0, 0, 0, 50, 3, false, "b.txt");
            int aAt = next;
            next = putEntry(root, aAt, 0, 0, 41, S, true, "a");
            int xexAt = next;
            putEntry(root, xexAt, 0, 0, 60, xex.length, false, "default.xex");
            root.putShort(0, (short) (aAt / 4)).putShort(2, (short) (xexAt / 4));
            file.seek(base + 40L * S);
            file.write(root.array());
            ByteBuffer dirA = ByteBuffer.allocate(S).order(ByteOrder.LITTLE_ENDIAN);
            java.util.Arrays.fill(dirA.array(), (byte) 0xFF);
            putEntry(dirA, 0, 0, 0, 70, inner.length, false, "inner.bin");
            file.seek(base + 41L * S);
            file.write(dirA.array());
            file.seek(base + 50L * S);
            file.write("abc".getBytes(StandardCharsets.US_ASCII));
            file.seek(base + 60L * S);
            file.write(xex);
            file.seek(base + 70L * S);
            file.write(inner);
        }
        try (RandomAccessFile file = new RandomAccessFile(image, "r")) {
            XdvdfsReader reader = new XdvdfsReader(file.getChannel());
            assertEquals(base, reader.base);
            List<XdvdfsReader.Entry> entries = reader.entries();
            StringBuilder listing = new StringBuilder();
            for (XdvdfsReader.Entry entry : entries)
                listing.append(entry.isDir ? "D " : "F ").append(entry.size).append(' ').append(entry.path).append('\n');
            assertEquals("D 2048 a\nF 10363 a/inner.bin\nF 3 b.txt\nF 256 default.xex\n", listing.toString());
            XdvdfsReader.Entry xexEntry = XdvdfsReader.find(entries, "DEFAULT.XEX");
            assertEquals(IsoImport.TITLE_ID, XdvdfsReader.xexTitleId(reader.head(xexEntry, 64 << 10)));

            File out = File.createTempFile("inner", ".bin");
            out.deleteOnExit();
            long[] counted = { 0 };
            try (FileOutputStream output = new FileOutputStream(out)) {
                assertTrue(reader.copy(XdvdfsReader.find(entries, "a/inner.bin"), output.getChannel(),
                    ByteBuffer.allocate(4096), count -> { counted[0] += count; return true; }));
            }
            assertEquals(inner.length, counted[0]);
            assertArrayEquals(inner, Files.readAllBytes(out.toPath()));
            try (FileOutputStream output = new FileOutputStream(out)) {
                assertFalse(reader.copy(XdvdfsReader.find(entries, "a/inner.bin"), output.getChannel(),
                    ByteBuffer.allocate(4096), count -> false));
            }
        }
    }

    @Test(expected = IOException.class)
    public void rejectsNonDiscFile() throws IOException {
        File image = File.createTempFile("notiso", ".bin");
        image.deleteOnExit();
        Files.write(image.toPath(), new byte[200 * S]);
        try (RandomAccessFile file = new RandomAccessFile(image, "r")) {
            new XdvdfsReader(file.getChannel());
        }
    }

    @Test
    public void listsRealDisc() throws IOException {
        String path = System.getenv("LR_ISO");
        Assume.assumeTrue(path != null && new File(path).isFile());
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            FileChannel channel = file.getChannel();
            XdvdfsReader reader = new XdvdfsReader(channel);
            List<XdvdfsReader.Entry> entries = reader.entries();
            try (Writer out = Files.newBufferedWriter(new File("build/xdvdfs_list.txt").toPath(),
                    StandardCharsets.UTF_8)) {
                for (XdvdfsReader.Entry entry : entries)
                    out.write((entry.isDir ? "D " : "F ") + String.format("%12d", entry.size) + " " + entry.path + "\n");
            }
            XdvdfsReader.Entry xex = XdvdfsReader.find(entries, "default.xex");
            assertEquals(IsoImport.TITLE_ID, XdvdfsReader.xexTitleId(reader.head(xex, 64 << 10)));
            File out = File.createTempFile("default", ".xex");
            out.deleteOnExit();
            try (FileOutputStream output = new FileOutputStream(out)) {
                assertTrue(reader.copy(xex, output.getChannel(), ByteBuffer.allocateDirect(8 << 20), null));
            }
            assertEquals(IsoImport.TESTED_XEX_SHA256, IsoImport.sha256(out));
        }
    }
}
