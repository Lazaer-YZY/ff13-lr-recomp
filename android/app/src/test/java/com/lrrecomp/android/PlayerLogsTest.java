package com.lrrecomp.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The log folder the app indexes for a PC over USB must include the game's lr.log
 * (public issue #4: it was skipped, so a PC showed it stale or not at all).
 */
public class PlayerLogsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void scanIncludesTheGameLog() throws IOException {
        File dir = folder.getRoot();
        for (String name : new String[] {"lr.log", "README.txt", "java-crash-123.txt", "notes.bin", "lr.log.bak"})
            Files.write(new File(dir, name).toPath(), new byte[] {1});
        assertTrue(new File(dir, "sub").mkdir());
        List<String> names = new ArrayList<>();
        for (File file : PlayerLogs.scannable(dir)) names.add(file.getName());
        assertEquals(Arrays.asList("README.txt", "java-crash-123.txt", "lr.log"), names);
    }

    @Test
    public void readmeNamesTheGameLog() {
        assertTrue(PlayerLogs.README_TEXT.contains("lr.log"));
    }

    /** CTRL's "Save logs" zip: device.txt, lr.log and the Java crashes, nothing else. */
    @Test
    public void exportHoldsTheGameLogAndCrashes() throws IOException {
        File dir = folder.getRoot();
        write(new File(dir, "lr.log"), "start 1\nstart 2\n");
        write(new File(dir, "java-crash-5.txt"), "boom\n");
        write(new File(dir, "README.txt"), "readme\n");
        write(new File(dir, "shader-1.jsonl"), "{}\n");
        List<String> names = new ArrayList<>();
        for (File file : PlayerLogs.exportable(dir)) names.add(file.getName());
        assertEquals(Arrays.asList("java-crash-5.txt", "lr.log"), names);

        Map<String, String> zip = unzip(PlayerLogs.exportable(dir), "device=x", 1L << 20);
        assertEquals(Arrays.asList("logs/device.txt", "logs/java-crash-5.txt", "logs/lr.log"), new ArrayList<>(zip.keySet()));
        assertEquals("device=x\n", zip.get("logs/device.txt"));
        assertEquals("start 1\nstart 2\n", zip.get("logs/lr.log"));
    }

    /** A long lr.log keeps its end, from a line start, with a note of what was left out. */
    @Test
    public void longLogKeepsItsEnd() throws IOException {
        File log = new File(folder.getRoot(), "lr.log");
        write(log, "aaaa\nbbbb\ncccc\ndddd\n");  // 20 bytes
        Map<String, String> zip = unzip(Arrays.asList(log), "d", 8);  // skip 12, cut to the next line
        assertEquals("[first 15 bytes of lr.log left out]\ndddd\n", zip.get("logs/lr.log"));
    }

    @Test
    public void exportNameIsDatedZip() {
        java.util.Calendar when = java.util.Calendar.getInstance();
        when.set(2026, java.util.Calendar.NOVEMBER, 20, 15, 30);
        assertEquals("LR-FFXIII-logs-20261120-1530.zip", PlayerLogs.exportName(when.getTime()));
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> unzip(List<File> files, String device, long tail) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PlayerLogs.exportZip(files, device, bytes, tail);
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                entries.put(entry.getName(), new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
