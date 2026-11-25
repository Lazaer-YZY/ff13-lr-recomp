package com.lrrecomp.android;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CTRL -> Troubleshooting -> "Report a problem on GitHub" (the owner, 2026-11-25, ported from DS2): saves the log zip
 * in Download and opens the public repository's issue form (.github/ISSUE_TEMPLATE/report.yml) with the version,
 * phone, GPU, driver and settings filled in through the form fields' ids. GitHub has no way for an app to attach a
 * file to an issue (neither the new-issue URL nor the REST API), so the player attaches the zip on the GitHub page.
 * LR has no frame captures, so the report zip is the Save logs zip (lr.log's last 32 MB, a few MB zipped).
 */
final class ProblemReport {
    static final String REPOSITORY = "https://github.com/Lazaer-YZY/ff13-lr-recomp";
    static final String TEMPLATE = "report.yml";
    static final String PACKAGE = "com.lrrecomp.android";
    /** The settings field's share of the URL: GitHub answers 414 for very long URLs. */
    static final int SETTINGS_MAX_CHARS = 1500;
    /** Bookkeeping keys of render_settings that say nothing about the game's settings. */
    private static final java.util.Set<String> SKIPPED_KEYS =
        new java.util.HashSet<>(java.util.Arrays.asList("debug_log_default_on", "fps40_trial"));

    private ProblemReport() {}

    /** The confirmation dialog; when this run has Debug logging off it offers to turn it on and restart first. */
    static void show(RuntimeActivity activity) {
        SharedPreferences render = RuntimeActivity.renderPrefs(activity);
        boolean debugLog = activity.debugLoggingThisRun();
        String message = activity.getString(R.string.report_message);
        if (!debugLog) message += "\n\n" + activity.getString(R.string.report_debug_off);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(R.string.report_title)
            .setMessage(message)
            .setPositiveButton(R.string.report_continue, (dialog, which) -> saveAndOpen(activity))
            .setNegativeButton(R.string.touch_cancel, null);
        if (!debugLog) {
            builder.setNeutralButton(R.string.report_debug_on, (dialog, which) -> {
                render.edit().putBoolean("debug_log", true).commit();  // on disk before a restart
                activity.offerRestart();
            });
        }
        builder.show();
    }

    /**
     * Android 10+: the zip goes straight to Download through MediaStore (no permission needed), then the issue form
     * opens. Older Android: the system file picker first (RuntimeActivity's log export), then the form.
     */
    static void saveAndOpen(RuntimeActivity activity) {
        if (Build.VERSION.SDK_INT < 29) {
            activity.startLogExport(true);
            return;
        }
        List<File> files = PlayerLogs.exportable(PlayerLogs.directory(activity));
        String device = activity.logDeviceText();
        String requested = PlayerLogs.exportName(new java.util.Date());
        new Thread(() -> {
            ContentResolver resolver = activity.getContentResolver();
            Uri uri = null;
            String saved;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, requested);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Download could not be opened");
                try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("the file could not be opened");
                    PlayerLogs.exportZip(files, device, output);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                saved = displayName(resolver, uri, requested);
            } catch (IOException | RuntimeException e) {
                if (uri != null) {
                    try {
                        resolver.delete(uri, null, null);
                    } catch (RuntimeException ignored) {
                        // A pending entry is cleaned up by the system.
                    }
                }
                String failed = activity.getString(R.string.logs_save_failed, String.valueOf(e.getMessage()));
                activity.runOnUiThread(() -> Toast.makeText(activity, failed, Toast.LENGTH_LONG).show());
                return;
            }
            final String name = saved;
            final String shown = activity.getString(R.string.report_saved, name);
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, shown, Toast.LENGTH_LONG).show();
                openIssue(activity, name);
            });
        }, "LR problem report").start();
    }

    /** The name MediaStore gave the file ("LR-FFXIII-logs-... (1).zip" when the name was taken). */
    private static String displayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor row = resolver.query(uri, new String[] {MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (row != null && row.moveToFirst() && row.getString(0) != null) return row.getString(0);
        } catch (RuntimeException ignored) {
            // The name is only for the messages.
        }
        return fallback;
    }

    /** Opens the issue form in the browser, its fields filled in; zipName goes into the logs field. */
    static void openIssue(RuntimeActivity activity, String zipName) {
        Uri uri = issueUri(activity, zipName);
        android.util.Log.i("lrrecomp", "problem report: opening the issue form (" + uri.toString().length()
            + " characters): " + uri);
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, activity.getString(R.string.report_no_browser, REPOSITORY + "/issues", zipName),
                Toast.LENGTH_LONG).show();
        }
    }

    static Uri issueUri(RuntimeActivity activity, String zipName) {
        String version;
        try {
            version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            version = "?";
        }
        if (!PACKAGE.equals(activity.getPackageName())) version += " (" + activity.getPackageName() + ")";
        String gpuModel = GpuDriverStore.gpuModel();
        GpuDriverStore.Installed driver = GpuDriverStore.selectedDriver(activity);
        String device = deviceLine(Build.MANUFACTURER, Build.MODEL, PlayerLogs.socModel(), Build.VERSION.RELEASE);
        String gpu = (gpuModel != null ? gpuModel : "unknown GPU") + ", "
            + (driver != null ? driver.metadata.name : GpuDriverStore.SYSTEM_DRIVER_NAME);
        SharedPreferences render = RuntimeActivity.renderPrefs(activity);
        // The settings that shape the picture, with their defaults (render_settings holds only what was changed).
        android.graphics.Point screen = new android.graphics.Point();
        activity.getWindowManager().getDefaultDisplay().getRealSize(screen);
        String head = "Screen: " + Math.max(screen.x, screen.y) + "x" + Math.min(screen.x, screen.y)
            + ", render height " + render.getString(RuntimeActivity.KEY_RENDER_HEIGHT,
                RuntimeActivity.DEFAULT_RENDER_HEIGHT)
            + ", wide screen " + (render.getBoolean(RuntimeActivity.KEY_WIDE, false) ? "on" : "off")
            + ", frame rate " + render.getString("fps", RuntimeActivity.DEFAULT_FPS)
            + "\nDebug logging this run: " + (activity.debugLoggingThisRun() ? "on" : "off")
            + "\nGPU compatibility: " + GpuCompat.Choices.from(render).summary()
            + "\nSustained performance mode: " + RuntimeActivity.sustainedState;
        String settings = settingsText(head, render.getAll(), SETTINGS_MAX_CHARS);
        return buildIssueUri(REPOSITORY, version, device, gpu, settings,
            activity.getString(R.string.report_attach, zipName) + "\n\n");
    }

    static Uri buildIssueUri(String repository, String version, String device, String gpu, String settings,
                             String logs) {
        return Uri.parse(repository + "/issues/new").buildUpon()
            .appendQueryParameter("template", TEMPLATE)
            .appendQueryParameter("version", version)
            .appendQueryParameter("device", device)
            .appendQueryParameter("gpu", gpu)
            .appendQueryParameter("settings", settings)
            .appendQueryParameter("logs", logs)
            .build();
    }

    /** "AYN Thor, SM8550, Android 13": "AYN Thor" rather than "AYN AYN Thor"; "samsung SM-G973F" stays as it is. */
    static String deviceLine(String manufacturer, String model, String soc, String release) {
        String name = model.toLowerCase(java.util.Locale.ROOT).startsWith(manufacturer.toLowerCase(java.util.Locale.ROOT))
            ? model : manufacturer + " " + model;
        return name + (soc != null && !soc.isEmpty() ? ", " + soc : "") + ", Android " + release;
    }

    /** head, then the settings by name (key=value lines), cut with "..." at maxChars. */
    static String settingsText(String head, Map<String, ?> prefs, int maxChars) {
        StringBuilder settings = new StringBuilder(head);
        for (Map.Entry<String, ?> entry : new TreeMap<>(prefs).entrySet()) {
            if (SKIPPED_KEYS.contains(entry.getKey())) continue;
            String line = "\n" + entry.getKey() + "=" + entry.getValue();
            if (settings.length() + line.length() > maxChars) {
                settings.append("\n...");
                break;
            }
            settings.append(line);
        }
        return settings.toString();
    }
}
