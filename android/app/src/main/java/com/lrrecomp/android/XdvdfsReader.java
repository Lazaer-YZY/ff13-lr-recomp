package com.lrrecomp.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reader for the XDVDFS game partition of an Xbox 360 disc image, a port of
 * {@code tools/xdvdfs.py}: the volume descriptor sits at sector 32 of the game
 * partition, whose start depends on how the image was dumped; each directory
 * is a binary tree of entries listed in order (left, self, right) and walked
 * depth first. All reads are positional on the channel, so one reader can be
 * used from any thread.
 */
final class XdvdfsReader {
    static final int SECTOR = 2048;
    private static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    /** Game partition offsets: XGD2 / XGD3 redump images, XGD1, plain extracted partition. */
    static final long[] BASES = { 0x2080000L, 0xFD90000L, 0x18300000L, 0L };
    private static final int MAX_DEPTH = 64;

    /** One file or directory; {@code path} uses '/' separators, no leading slash. */
    static final class Entry {
        final String path;
        final long sector;
        final long size;
        final boolean isDir;

        Entry(String path, long sector, long size, boolean isDir) {
            this.path = path;
            this.sector = sector;
            this.size = size;
            this.isDir = isDir;
        }
    }

    interface Progress {
        /** Called after each chunk; returning false cancels the copy. */
        boolean onBytes(long count);
    }

    private final FileChannel channel;
    private static final long SYNC_BYTES = 64L << 20;
    private long unsynced;
    final long base;
    final long rootSector;
    final long rootSize;

    XdvdfsReader(FileChannel channel) throws IOException {
        this.channel = channel;
        this.base = findBase(channel);
        ByteBuffer root = read(base + 32L * SECTOR + 20, 8);
        if (root.remaining() < 8) throw new IOException("Truncated volume descriptor");
        rootSector = root.getInt() & 0xFFFFFFFFL;
        rootSize = root.getInt() & 0xFFFFFFFFL;
    }

    /** The game partition offset, or an IOException when the file is no Xbox disc image. */
    static long findBase(FileChannel channel) throws IOException {
        byte[] probe = new byte[MAGIC.length];
        for (long base : BASES) {
            ByteBuffer buffer = ByteBuffer.wrap(probe);
            long position = base + 32L * SECTOR;
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, position + buffer.position());
                if (n < 0) break;
            }
            if (!buffer.hasRemaining() && Arrays.equals(probe, MAGIC)) return base;
        }
        throw new IOException("No XDVDFS volume descriptor found");
    }

    /** Up to {@code size} bytes at an absolute file offset (less at the end of the file). */
    private ByteBuffer read(long offset, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, offset + buffer.position());
            if (n < 0) break;
        }
        buffer.flip();
        return buffer;
    }

    /** Every entry, in the order of {@code xdvdfs.py list}. */
    List<Entry> entries() throws IOException {
        List<Entry> out = new ArrayList<>();
        walkDir(rootSector, rootSize, "", out, 0);
        return out;
    }

    private void walkDir(long sector, long size, String prefix, List<Entry> out, int depth) throws IOException {
        if (size == 0) return;
        if (depth > MAX_DEPTH) throw new IOException("Directory tree too deep at " + prefix);
        if (size > 64L << 20) throw new IOException("Directory too large at " + prefix);
        ByteBuffer data = read(base + sector * SECTOR, (int) size);
        List<Entry> list = new ArrayList<>();
        visit(data, 0, prefix, new HashSet<>(), list);
        for (Entry entry : list) {
            out.add(entry);
            if (entry.isDir) walkDir(entry.sector, entry.size, entry.path + "/", out, depth + 1);
        }
    }

    private static void visit(ByteBuffer data, int offset, String prefix, Set<Integer> seen, List<Entry> list)
            throws IOException {
        if (seen.contains(offset) || offset + 14 > data.limit()) return;
        seen.add(offset);
        int left = data.getShort(offset) & 0xFFFF;
        int right = data.getShort(offset + 2) & 0xFFFF;
        if (left == 0xFFFF && right == 0xFFFF) return;
        long sector = data.getInt(offset + 4) & 0xFFFFFFFFL;
        long size = data.getInt(offset + 8) & 0xFFFFFFFFL;
        int attributes = data.get(offset + 12) & 0xFF;
        int nameLength = data.get(offset + 13) & 0xFF;
        int nameEnd = Math.min(offset + 14 + nameLength, data.limit());
        byte[] raw = new byte[nameEnd - offset - 14];
        for (int i = 0; i < raw.length; ++i) raw[i] = data.get(offset + 14 + i);
        String name = new String(raw, StandardCharsets.US_ASCII);
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("Bad file name in disc image: " + prefix + name);
        if (left != 0) visit(data, left * 4, prefix, seen, list);
        list.add(new Entry(prefix + name, sector, size, (attributes & 0x10) != 0));
        if (right != 0) visit(data, right * 4, prefix, seen, list);
    }

    /** First entry whose path matches ignoring case, or null. */
    static Entry find(List<Entry> entries, String path) {
        for (Entry entry : entries) if (entry.path.equalsIgnoreCase(path)) return entry;
        return null;
    }

    /** The first {@code max} bytes of a file entry. */
    byte[] head(Entry entry, int max) throws IOException {
        ByteBuffer buffer = read(base + entry.sector * SECTOR, (int) Math.min(entry.size, max));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * Copies a file entry to {@code output} in large chunks; returns false when
     * {@code progress} cancelled it. A short image throws.
     */
    boolean copy(Entry entry, FileChannel output, ByteBuffer buffer, Progress progress) throws IOException {
        long position = base + entry.sector * SECTOR;
        long left = entry.size;
        while (left > 0) {
            buffer.clear();
            if (left < buffer.capacity()) buffer.limit((int) left);
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, position + buffer.position());
                if (n < 0) throw new IOException("Disc image ends inside " + entry.path);
            }
            buffer.flip();
            int count = buffer.remaining();
            while (buffer.hasRemaining()) output.write(buffer);
            position += count;
            left -= count;
            // Flush every 64 MB: unflushed writes pile up in memory far faster than storage
            // takes them, and on the Thor the low-memory killer then killed the app (in the
            // foreground) a few GB into the copy.
            unsynced += count;
            if (unsynced >= SYNC_BYTES) {
                output.force(false);
                unsynced = 0;
            }
            if (progress != null && !progress.onBytes(count)) return false;
        }
        return true;
    }

    /**
     * Title ID from an XEX2 header (execution info, optional header 0x00040006),
     * or -1 when it is not there. Lightning Returns is 0x5351081B.
     */
    static long xexTitleId(byte[] header) {
        ByteBuffer data = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (header.length < 0x18 || data.getInt(0) != 0x58455832) return -1; // "XEX2"
        long count = data.getInt(0x14) & 0xFFFFFFFFL;
        for (long i = 0; i < count; ++i) {
            int at = (int) (0x18 + i * 8);
            if (at + 8 > header.length) return -1;
            if (data.getInt(at) == 0x00040006) {
                long info = data.getInt(at + 4) & 0xFFFFFFFFL;
                if (info + 16 > header.length) return -1;
                return data.getInt((int) info + 12) & 0xFFFFFFFFL;
            }
        }
        return -1;
    }
}
