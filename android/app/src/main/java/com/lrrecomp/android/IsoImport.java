package com.lrrecomp.android;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * Extracts the game from a user's Xbox 360 disc image (picked through the
 * system file picker) into a game folder, as {@code tools/xdvdfs.py extract}
 * does on a PC. The files go to a sibling folder ({@code game.import}) first
 * and are moved into the game folder only when every file is complete, with
 * {@code default.xex} last: a cancelled or failed import never leaves a folder
 * the launcher would take for a game.
 *
 * The running import is process-wide ({@link #current}), so the game folder
 * page re-attaches to it when it is opened again.
 */
final class IsoImport {
    static final long TITLE_ID = 0x5351081BL;
    /** sha256 of default.xex of the tested USA disc (as in tools/quickstart.sh). */
    static final String TESTED_XEX_SHA256 = "52d3c11378fa041e086dabfd79994e7eaa63e47f28e5c98878c71573823d5afb";
    /** Free space kept beyond the game's size. */
    static final long SPACE_MARGIN = 256L << 20;

    /** A picked disc image, opened and listed, before the import starts. */
    static final class Disc implements AutoCloseable {
        final ParcelFileDescriptor descriptor;
        final FileInputStream stream;
        final XdvdfsReader reader;
        final List<XdvdfsReader.Entry> entries;
        final long totalBytes;
        final int fileCount;
        /** -1 when default.xex has no readable title ID. */
        final long titleId;

        private Disc(ParcelFileDescriptor descriptor, FileInputStream stream, XdvdfsReader reader,
                List<XdvdfsReader.Entry> entries, long titleId) {
            this.descriptor = descriptor;
            this.stream = stream;
            this.reader = reader;
            this.entries = entries;
            this.titleId = titleId;
            long total = 0;
            int files = 0;
            for (XdvdfsReader.Entry entry : entries) if (!entry.isDir) { total += entry.size; ++files; }
            this.totalBytes = total;
            this.fileCount = files;
        }

        @Override
        public void close() {
            try { stream.close(); } catch (IOException ignored) {}
            try { descriptor.close(); } catch (IOException ignored) {}
        }
    }

    /** Opens and lists a disc image; slow on SD cards, so not on the UI thread. */
    static Disc open(Context context, Uri uri) throws IOException {
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("The file could not be opened.");
        FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
        try {
            FileChannel channel = stream.getChannel();
            try {
                channel.size();
                channel.position(0);
            } catch (IOException e) {
                throw new IOException("This file cannot be read directly. Copy the ISO to the "
                    + "device storage or SD card and pick it there.");
            }
            XdvdfsReader reader;
            try {
                reader = new XdvdfsReader(channel);
            } catch (IOException e) {
                throw new IOException("This is not an Xbox 360 disc image (ISO).");
            }
            // The console's own system update ($SystemUpdate, ~150 MB) is never read by the game.
            List<XdvdfsReader.Entry> entries = new java.util.ArrayList<>(reader.entries());
            entries.removeIf(e -> e.path.startsWith("$SystemUpdate"));
            XdvdfsReader.Entry xex = XdvdfsReader.find(entries, "default.xex");
            if (xex == null || xex.isDir)
                throw new IOException("This disc has no default.xex, so it is not a game disc.");
            long titleId = XdvdfsReader.xexTitleId(reader.head(xex, 64 << 10));
            return new Disc(descriptor, stream, reader, entries, titleId);
        } catch (IOException | RuntimeException e) {
            try { stream.close(); } catch (IOException ignored) {}
            descriptor.close();
            throw e;
        }
    }

    // Running import ------------------------------------------------------------

    enum State { RUNNING, CLEANING, DONE, FAILED, CANCELLED }

    private static volatile IsoImport current;

    final File target;
    final long totalBytes;
    volatile State state = State.RUNNING;
    volatile long doneBytes;
    volatile String currentFile = "";
    /** Failure text, or the default.xex sha256 warning after success (null when it matches). */
    volatile String message;
    private volatile boolean cancelRequested;
    private final Disc disc;

    private IsoImport(Disc disc, File target) {
        this.disc = disc;
        this.target = target;
        this.totalBytes = disc.totalBytes;
    }

    static IsoImport current() {
        return current;
    }

    /** Forgets a finished import once its result was shown. */
    static void clearFinished() {
        IsoImport job = current;
        if (job != null && job.finished()) current = null;
    }

    boolean finished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    void cancel() {
        cancelRequested = true;
    }

    /** Starts extracting {@code disc} into {@code target}; the import owns and closes the disc. */
    static IsoImport start(Disc disc, File target) {
        IsoImport job = new IsoImport(disc, target);
        current = job;
        Thread thread = new Thread(job::run, "ISO import");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
        return job;
    }

    static File stagingDir(File target) {
        return new File(target.getParentFile(), target.getName() + ".import");
    }

    private void run() {
        File staging = stagingDir(target);
        try {
            deleteRecursively(staging);
            if (staging.exists()) throw new IOException("Cannot clear " + staging);
            if (!staging.mkdirs()) throw new IOException("Cannot create " + staging);
            ByteBuffer buffer = ByteBuffer.allocateDirect(8 << 20);
            for (XdvdfsReader.Entry entry : disc.entries) {
                if (cancelRequested) break;
                File destination = new File(staging, entry.path);
                if (entry.isDir) {
                    if (!destination.isDirectory() && !destination.mkdirs())
                        throw new IOException("Cannot create " + destination);
                    continue;
                }
                currentFile = entry.path;
                File parent = destination.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Cannot create " + parent);
                boolean complete;
                try (FileOutputStream output = new FileOutputStream(destination)) {
                    complete = disc.reader.copy(entry, output.getChannel(), buffer, count -> {
                        doneBytes += count;
                        return !cancelRequested;
                    });
                }
                if (!complete) break;
            }
            if (cancelRequested) {
                finishCleaning(staging, State.CANCELLED, null);
                return;
            }
            currentFile = "";
            String sha = sha256(new File(staging, xexName(staging)));
            moveIntoPlace(staging, target);
            message = TESTED_XEX_SHA256.equals(sha) ? null
                : "default.xex is not the tested USA release (sha256 " + sha + "). Other releases "
                    + "need their own build; the game will likely fail or crash.";
            state = State.DONE;
        } catch (IOException | RuntimeException e) {
            String text = e.getMessage() != null ? e.getMessage() : e.toString();
            if (text.contains("ENOSPC") || text.contains("EACCES") || text.contains("EROFS"))
                text += "\nCheck the free space and that the folder is writable.";
            finishCleaning(staging, State.FAILED, text);
        } finally {
            disc.close();
        }
    }

    private void finishCleaning(File staging, State end, String text) {
        state = State.CLEANING;
        deleteRecursively(staging);
        message = text;
        state = end;
    }

    /** The staged default.xex's actual name (the disc may spell it in another case). */
    private static String xexName(File staging) {
        String[] names = staging.list();
        if (names != null) for (String name : names) if (name.equalsIgnoreCase("default.xex")) return name;
        return "default.xex";
    }

    /**
     * Moves the staged files into the game folder, replacing same-named ones
     * (an earlier partial copy); {@code default.xex} goes last so the folder
     * only looks like a game once everything else is in place.
     */
    static void moveIntoPlace(File staging, File target) throws IOException {
        if (!target.exists()) {
            if (staging.renameTo(target)) return;
            if (!target.mkdirs()) throw new IOException("Cannot create " + target);
        }
        File[] children = staging.listFiles();
        if (children == null) throw new IOException("Cannot list " + staging);
        File xex = null;
        for (File child : children) {
            if (child.getName().equalsIgnoreCase("default.xex")) { xex = child; continue; }
            moveReplacing(child, new File(target, child.getName()));
        }
        if (xex != null) moveReplacing(xex, new File(target, "default.xex"));
        deleteRecursively(staging);
    }

    private static void moveReplacing(File from, File to) throws IOException {
        deleteRecursively(to);
        if (!from.renameTo(to)) throw new IOException("Cannot move " + from + " to " + to);
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buffer = new byte[1 << 20];
        try (InputStream input = new FileInputStream(file)) {
            for (int n; (n = input.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    static String gigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1e9);
    }
}
