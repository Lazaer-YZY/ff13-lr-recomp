// Adapted from Lost Odyssey Recomp (GPL-3.0).
package com.lrrecomp.android;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runtime logs in {@code Android/data/<package>/files/logs/}, which a player
 * can copy to a PC over USB without root or adb.
 *
 * The game's log is {@code lr.log} (RuntimeActivity's --log_file; every start is
 * appended to it). A PC sees new files over USB (MTP) only after the media
 * scanner has indexed them, and MTP reports the size recorded at that scan, so
 * the folder is scanned again on every launch and when the game is left: the
 * files of a run that crashed are complete after that. Until 2026-11-20 the
 * scan skipped lr.log (the names below were Lost Odyssey's), so a PC showed it
 * stale or not at all (public issue #4).
 */
final class PlayerLogs {
    static final String DIRECTORY = "logs";
    static final String README = "README.txt";
    static final int JAVA_CRASHES_KEPT = 5;
    static final String README_TEXT =
        "LR: FFXIII logs. For a bug report, attach lr.log (every start is added to its end)\n"
        + "and any java-crash-*.txt. Open the app once after a crash so a PC sees the\n"
        + "complete files.\n";
    private static final Pattern LOG_FILE = Pattern.compile(
        "lr\\.log|runtime-\\d+\\.log|shader-\\d+\\.jsonl|native-stderr(\\.previous)?\\.log|java-crash-\\d+\\.txt|README\\.txt");
    private static final Pattern JAVA_CRASH = Pattern.compile("java-crash-(\\d+)\\.txt");
    private static final Pattern RUNTIME_LOG = Pattern.compile("runtime-(\\d+)\\.log");
    private static final Pattern EXPORTED = Pattern.compile(
        "lr\\.log|native-stderr(\\.previous)?\\.log|java-crash-\\d+\\.txt");
    /**
     * lr.log grows with every start; an export keeps its last 32 MB (whole lines; a zip of
     * that is a few MB, well under GitHub's 25 MB attachment limit).
     */
    static final long EXPORT_TAIL_BYTES = 32L << 20;
    /** Runtime logs in an export: this run and the two before it, for a crash in the last one. */
    static final int RUNTIME_LOGS_EXPORTED = 3;

    private PlayerLogs() {}

    static File directory(Context context) {
        File external = context.getExternalFilesDir(null);
        return new File(external != null ? external : context.getFilesDir(), DIRECTORY);
    }

    /** One line naming the phone, for the native log's LR_* switch list. */
    static String deviceDescription() {
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE;
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?";
        return Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ") android=" + Build.VERSION.RELEASE
            + " sdk=" + Build.VERSION.SDK_INT + " soc=" + soc + " abi=" + abi;
    }

    /** The log files in {@code directory}, by name; other files and folders are left alone. */
    static List<File> scannable(File directory) {
        File[] files = directory.listFiles();
        List<File> result = new ArrayList<>();
        if (files == null) return result;
        for (File file : files)
            if (file.isFile() && LOG_FILE.matcher(file.getName()).matches()) result.add(file);
        Collections.sort(result, (a, b) -> a.getName().compareTo(b.getName()));
        return result;
    }

    /** Java crash reports beyond the newest {@code keep}, oldest first. */
    static List<File> staleJavaCrashes(File directory, int keep) {
        File[] files = directory.listFiles();
        List<File> crashes = new ArrayList<>();
        if (files != null) for (File file : files)
            if (file.isFile() && JAVA_CRASH.matcher(file.getName()).matches()) crashes.add(file);
        Collections.sort(crashes, (a, b) -> Long.compare(timestamp(a), timestamp(b)));
        return crashes.size() > keep ? crashes.subList(0, crashes.size() - keep) : Collections.emptyList();
    }

    private static long timestamp(File crash) {
        Matcher matcher = JAVA_CRASH.matcher(crash.getName());
        try {
            return matcher.matches() ? Long.parseLong(matcher.group(1)) : 0;
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * The files a log export holds: the game's lr.log and the Java crash reports
     * (plus the Lost Odyssey runtime/stderr names, should they ever appear). Shader
     * logs stay out; they are large and rarely needed.
     */
    static List<File> exportable(File directory) {
        File[] files = directory.listFiles();
        List<File> runtime = new ArrayList<>();
        List<File> result = new ArrayList<>();
        if (files == null) return result;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (RUNTIME_LOG.matcher(file.getName()).matches()) runtime.add(file);
            else if (EXPORTED.matcher(file.getName()).matches()) result.add(file);
        }
        Collections.sort(runtime, (a, b) -> Long.compare(runtimeStamp(b), runtimeStamp(a)));
        result.addAll(runtime.subList(0, Math.min(RUNTIME_LOGS_EXPORTED, runtime.size())));
        Collections.sort(result, (a, b) -> a.getName().compareTo(b.getName()));
        return result;
    }

    private static long runtimeStamp(File log) {
        Matcher matcher = RUNTIME_LOG.matcher(log.getName());
        try {
            return matcher.matches() ? Long.parseLong(matcher.group(1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Writes {@code files} and a device.txt naming the phone and app to one ZIP.
     * The running game flushes every log line, so its current log is complete
     * up to the export.
     */
    static void exportZip(List<File> files, String device, OutputStream output) throws IOException {
        exportZip(files, device, output, EXPORT_TAIL_BYTES);
    }

    /** As above; a file longer than {@code tailBytes} is cut to its end, from a line start. */
    static void exportZip(List<File> files, String device, OutputStream output, long tailBytes) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(output);
        zip.putNextEntry(new ZipEntry("logs/device.txt"));
        zip.write((device + "\n").getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        byte[] buffer = new byte[1 << 16];
        for (File file : files) {
            zip.putNextEntry(new ZipEntry("logs/" + file.getName()));
            try (InputStream input = new FileInputStream(file)) {
                long skip = file.length() - tailBytes;
                if (skip > 0) {
                    for (long left = skip; left > 0; ) {
                        long n = input.skip(left);
                        if (n <= 0) break;
                        left -= n;
                    }
                    for (int c; (c = input.read()) >= 0 && c != '\n'; ) ++skip;  // to the next line
                    zip.write(("[first " + (skip + 1) + " bytes of " + file.getName() + " left out]\n")
                        .getBytes(StandardCharsets.UTF_8));
                }
                for (int n; (n = input.read(buffer)) > 0; ) zip.write(buffer, 0, n);
            }
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    /** The suggested file name of a log export, e.g. LR-FFXIII-logs-20261120-1530.zip. */
    static String exportName(java.util.Date when) {
        return "LR-FFXIII-logs-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(when)
            + ".zip";
    }

    /** Whether settings.ini turns on Settings -> System -> Debug log (info lines in the runtime log). */
    static boolean debugLogEnabled(Context context) {
        File settings = new File(new File(context.getFilesDir(), "config"), "settings.ini");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(settings), StandardCharsets.UTF_8))) {
            // The old debug_log key is ignored, as in settings/config.cpp.
            for (String line; (line = reader.readLine()) != null; )
                if (line.trim().equals("diagnostic_log=0")) return false;
        } catch (IOException ignored) {
            // No settings yet: the default is on.
        }
        return true;
    }

    /** Creates the folder and README, then shows every log file to a PC over USB. */
    static void publish(Context context) {
        File directory = directory(context);
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        File readme = new File(directory, README);
        // Rewritten when its text changed (older installs carry Lost Odyssey's file names).
        if (readme.length() != README_TEXT.getBytes(StandardCharsets.UTF_8).length) write(readme, README_TEXT);
        List<File> files = scannable(directory);
        if (files.isEmpty()) return;
        String[] paths = new String[files.size()];
        for (int i = 0; i < paths.length; ++i) paths[i] = files.get(i).getAbsolutePath();
        MediaScannerConnection.scanFile(context.getApplicationContext(), paths, null, null);
    }

    /** Writes uncaught Java exceptions to {@code java-crash-<ms>.txt}, then lets Android handle them. */
    static synchronized void installCrashHandler(Context context) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof CrashWriter) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashWriter(directory(context), previous));
    }

    static String crashReport(Thread thread, Throwable error, String device) {
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        writer.println("device: " + device);
        writer.println("thread: " + thread.getName());
        error.printStackTrace(writer);
        writer.flush();
        return text.toString();
    }

    private static final class CrashWriter implements Thread.UncaughtExceptionHandler {
        private final File directory;
        private final Thread.UncaughtExceptionHandler previous;

        CrashWriter(File directory, Thread.UncaughtExceptionHandler previous) {
            this.directory = directory;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            try {
                //noinspection ResultOfMethodCallIgnored
                directory.mkdirs();
                write(new File(directory, "java-crash-" + System.currentTimeMillis() + ".txt"),
                    crashReport(thread, error, deviceDescription()));
                for (File stale : staleJavaCrashes(directory, JAVA_CRASHES_KEPT))
                    //noinspection ResultOfMethodCallIgnored
                    stale.delete();
            } catch (Throwable ignored) {
                // The report is optional; Android's own handling must still run.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        }
    }

    private static void write(File file, String text) {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // A missing README or report must not stop the game.
        }
    }
}
