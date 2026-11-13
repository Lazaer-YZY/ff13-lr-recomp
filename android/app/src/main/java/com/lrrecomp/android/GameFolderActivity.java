// Adapted from Lost Odyssey Recomp (GPL-3.0).
package com.lrrecomp.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Game folder page: where the game data is read from. The app folder in
 * {@code Android/data} needs no permission but phone file managers cannot
 * write into it, so a folder anywhere on the internal storage or an SD card
 * can be picked instead. The native runtime opens files by path, so a picked
 * folder needs the "All files access" permission (storage permission before
 * Android 11).
 *
 * It opens instead of the game when no {@code default.xex} is found in the
 * game folder, and from the CTRL dialog while playing; changing the folder
 * then restarts. "Import from ISO" extracts the game from the player's disc
 * image on the device ({@link IsoImport}); DLC import is not supported.
 */
public final class GameFolderActivity extends Activity {
    static final String EXTRA_FROM_GAME = "from_game";
    private static final int REQUEST_PICK_FOLDER = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 2;
    private static final int REQUEST_PICK_ISO = 3;

    private boolean fromGame;
    private File originalCustom;
    /** What to continue with once the storage permission is granted. */
    private enum Pending { NONE, PICK }
    private Pending afterPermission = Pending.NONE;
    private LinearLayout page;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::updateImport;
    private ProgressBar importBar;
    private TextView importBytes;
    private TextView importFile;
    private Button importCancel;
    private AlertDialog readingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PlayerLogs.installCrashHandler(this);
        super.onCreate(savedInstanceState);
        fromGame = getIntent().getBooleanExtra(EXTRA_FROM_GAME, false);
        originalCustom = GameStorage.customRoot(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(18, 24, 30));
        int padding = dp(20);
        page.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 24, 30));
        scroll.setFillViewport(true);
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        // Android 15 draws apps edge to edge: keep the page clear of the status bar.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the system permission page.
        if (afterPermission != Pending.NONE && canReadSharedStorage()) {
            Pending pending = afterPermission;
            afterPermission = Pending.NONE;
            if (pending == Pending.PICK) pickFolder();
        }
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        if (readingDialog != null) readingDialog.dismiss();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        IsoImport job = IsoImport.current();
        if (job != null && !job.finished()) {
            Toast.makeText(this, "Cancel the import first", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.WHITE);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView dim(String value) {
        TextView view = text(value, 13f, false);
        view.setTextColor(Color.argb(255, 190, 200, 210));
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private void render() {
        page.removeAllViews();
        page.addView(text("Game folder", 24f, true));
        if (renderImport()) return;

        File gameDir = GameStorage.gameDir(this);
        boolean found = GameStorage.hasDefaultXex(gameDir);
        TextView status = text(found ? "Game found: " + gameDir
            : "No game found. Put the extracted game (default.xex and weiss_data/ directly "
                + "inside the folder) in one of the places below.", 15f, true);
        status.setTextColor(found ? Color.rgb(140, 220, 140) : Color.rgb(255, 196, 80));
        page.addView(status);

        page.addView(text("App folder", 18f, true));
        StringBuilder folders = new StringBuilder();
        for (File volume : GameStorage.volumes(this))
            folders.append(new File(volume, "game").getAbsolutePath()).append('\n');
        page.addView(dim(folders.toString().trim()));
        page.addView(dim("No permission needed. Copy the game here from a PC over USB; "
            + "file managers on the device usually cannot write into Android/data. "
            + "Uninstalling the app deletes this folder."));

        page.addView(text("Custom folder", 18f, true));
        File custom = GameStorage.customRoot(this);
        if (custom == null) {
            page.addView(dim("Not set."));
        } else {
            page.addView(dim(custom.getAbsolutePath()));
            if (!canReadSharedStorage()) {
                TextView warning = dim("All files access is off, so the game cannot read this folder.");
                warning.setTextColor(Color.rgb(255, 196, 80));
                page.addView(warning);
            } else if (!GameStorage.hasDefaultXex(custom)) {
                TextView warning = dim("default.xex is missing in this folder.");
                warning.setTextColor(Color.rgb(255, 196, 80));
                page.addView(warning);
            }
        }
        page.addView(dim("Any folder on the internal storage or an SD card, for example one "
            + "filled with a file manager. Pick the folder that holds default.xex. "
            + "Needs the \"All files access\" permission."));

        if (!fromGame) {
            page.addView(text("Import from ISO", 18f, true));
            page.addView(dim("Pick your Xbox 360 disc image (.iso) and the app extracts the game "
                + "(about 7 GB) into the custom folder if one is set, else into the app folder. "
                + "The ISO can be deleted afterwards."));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, dp(8));
        actions.addView(button("Choose folder…", v -> chooseFolder()));
        // Not while playing: the running game has the current game files open.
        if (!fromGame) actions.addView(button("Import from ISO…", v -> pickIso()));
        if (custom != null) {
            actions.addView(button("Use app folder", v -> {
                GameStorage.setCustomRoot(this, null);
                render();
            }));
        }
        page.addView(actions);

        page.addView(primaryButton(found));
    }

    private Button primaryButton(boolean found) {
        boolean changed = !Objects.equals(originalCustom, GameStorage.customRoot(this));
        if (!fromGame) {
            Button start = button("Start game", v -> {
                startActivity(new Intent(this, RuntimeActivity.class));
                finish();
            });
            start.setEnabled(found);
            return start;
        }
        if (!changed) return button("Back to game", v -> finish());
        Button apply = button("Apply and restart", v -> new AlertDialog.Builder(this)
            .setTitle("Restart the game?")
            .setMessage("The game folder is chosen when the game starts, so the game restarts now. "
                + "Progress since the last save is lost.")
            .setPositiveButton("Restart", (dialog, which) -> {
                Intent intent = new Intent(this, RuntimeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                // The pending activity outlives the process; Android starts a new one for it.
                Runtime.getRuntime().exit(0);
            })
            .setNegativeButton("Cancel", null)
            .show());
        // Restarting without game data would only reach the missing-game error.
        apply.setEnabled(found);
        return apply;
    }

    // Permission and picker ---------------------------------------------------

    private boolean canReadSharedStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void chooseFolder() {
        if (canReadSharedStorage()) { pickFolder(); return; }
        askForStorage(Pending.PICK);
    }

    private void askForStorage(Pending next) {
        new AlertDialog.Builder(this)
            .setTitle("Allow file access")
            .setMessage("The game reads its files directly, so folders outside "
                + "the app folder need the \"All files access\" permission. Android opens the "
                + "setting now; turn it on for Lightning Returns Recomp and come back.")
            .setPositiveButton("Open setting", (dialog, which) -> requestStorage(next))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void requestStorage(Pending next) {
        afterPermission = next;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQUEST_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // onResume follows and continues to the picker when the permission was granted.
        if (requestCode == REQUEST_STORAGE_PERMISSION && !canReadSharedStorage()) afterPermission = Pending.NONE;
    }

    private void pickFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK_FOLDER);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No folder picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_ISO) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) readIso(data.getData());
            return;
        }
        if (requestCode != REQUEST_PICK_FOLDER || resultCode != RESULT_OK || data == null
                || data.getData() == null) return;
        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(data.getData());
        } catch (RuntimeException e) {
            documentId = null;
        }
        File picked = GameStorage.pathForTreeDocumentId(documentId, Environment.getExternalStorageDirectory());
        File root = GameStorage.normalizeGameRoot(picked);
        if (root == null) {
            new AlertDialog.Builder(this).setTitle("No game in this folder")
                .setMessage(picked == null
                    ? "Pick a folder on the internal storage or an SD card."
                    : "Expected default.xex in " + picked.getAbsolutePath()
                        + ". Pick the folder that holds default.xex and weiss_data.")
                .setPositiveButton("OK", null).show();
            return;
        }
        GameStorage.setCustomRoot(this, root);
        render();
    }

    // ISO import --------------------------------------------------------------

    private void pickIso() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // .iso has no reliable mime type.
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_ISO);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    private void message(String title, String body) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK", null).show();
    }

    /** Opens and lists the picked disc image off the UI thread, then asks to import. */
    private void readIso(Uri uri) {
        readingDialog = new AlertDialog.Builder(this).setTitle("Reading disc image…")
            .setMessage("Checking the files on the disc.").setCancelable(false).show();
        new Thread(() -> {
            IsoImport.Disc disc = null;
            String error = null;
            try {
                disc = IsoImport.open(getApplicationContext(), uri);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            IsoImport.Disc opened = disc;
            String failure = error;
            runOnUiThread(() -> {
                if (readingDialog != null) readingDialog.dismiss();
                readingDialog = null;
                if (isDestroyed()) {
                    if (opened != null) opened.close();
                    return;
                }
                if (opened == null) message("Cannot import this file", failure);
                else checkTitle(opened);
            });
        }, "ISO check").start();
    }

    private void checkTitle(IsoImport.Disc disc) {
        if (disc.titleId == IsoImport.TITLE_ID) { checkTarget(disc); return; }
        String id = disc.titleId < 0 ? "unknown" : String.format("%08X", disc.titleId);
        new AlertDialog.Builder(this).setTitle("Not Lightning Returns?")
            .setMessage("This disc's title ID is " + id + "; Lightning Returns is 5351081B. "
                + "The game will not run from another disc.")
            .setPositiveButton("Import anyway", (dialog, which) -> checkTarget(disc))
            .setNegativeButton("Cancel", (dialog, which) -> disc.close())
            .setOnCancelListener(dialog -> disc.close())
            .show();
    }

    /**
     * The custom folder when one is set and readable; otherwise the first app
     * folder (internal first) with room for the game.
     */
    private void checkTarget(IsoImport.Disc disc) {
        long need = disc.totalBytes + IsoImport.SPACE_MARGIN;
        File custom = GameStorage.customRoot(this);
        List<File> candidates = new ArrayList<>();
        if (custom != null && canReadSharedStorage()) candidates.add(custom);
        else for (File volume : GameStorage.volumes(this)) candidates.add(new File(volume, "game"));
        File target = null;
        StringBuilder space = new StringBuilder();
        for (File candidate : candidates) {
            File existing = candidate;
            while (existing != null && !existing.exists()) existing = existing.getParentFile();
            long free = existing != null ? existing.getUsableSpace() : 0;
            space.append(candidate.getAbsolutePath()).append(": ")
                .append(IsoImport.gigabytes(free)).append(" free\n");
            if (target == null && free >= need) target = candidate;
        }
        if (target == null) {
            disc.close();
            message("Not enough space", "The game needs " + IsoImport.gigabytes(need) + ".\n\n"
                + space.toString().trim() + "\n\nFree some space or pick a custom folder on a larger storage.");
            return;
        }
        String[] existing = target.list();
        boolean hasFiles = false;
        if (existing != null) for (String name : existing) if (!name.equals(GameStorage.README)) hasFiles = true;
        File chosen = target;
        new AlertDialog.Builder(this).setTitle("Import the game?")
            .setMessage(disc.fileCount + " files, " + IsoImport.gigabytes(disc.totalBytes) + ", into\n"
                + target.getAbsolutePath()
                + (hasFiles ? "\n\nFiles with the same names already in this folder are replaced." : "")
                + "\n\nThis takes a few minutes; keep the device charged. You can leave the app: a notification shows the progress.")
            .setPositiveButton("Import", (dialog, which) -> {
                IsoImport.start(disc, chosen);
                IsoImportService.start(this);  // keeps the copy alive if the user leaves the app
                render();
            })
            .setNegativeButton("Cancel", (dialog, which) -> disc.close())
            .setOnCancelListener(dialog -> disc.close())
            .show();
    }

    /** The import's progress page while one runs; returns false when there is none to show. */
    private boolean renderImport() {
        IsoImport job = IsoImport.current();
        if (job == null) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return false;
        }
        if (job.finished()) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            IsoImport.clearFinished();
            if (job.state == IsoImport.State.DONE) {
                message("Game imported", job.message == null ? "The game is ready."
                    : "The game is in place, but: " + job.message);
            } else if (job.state == IsoImport.State.FAILED) {
                message("Import failed", job.message + "\n\nNothing was left in the game folder.");
            }
            return false;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        page.addView(text("Importing the game", 18f, true));
        page.addView(dim("Into " + job.target.getAbsolutePath()
            + ". Keep the app open; the screen stays on until it is done."));
        importBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importBar.setMax(1000);
        page.addView(importBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        importBytes = text("", 15f, false);
        page.addView(importBytes);
        importFile = dim("");
        page.addView(importFile);
        importCancel = button("Cancel import", v -> new AlertDialog.Builder(this)
            .setTitle("Cancel the import?")
            .setMessage("The files extracted so far are deleted.")
            .setPositiveButton("Cancel import", (dialog, which) -> { job.cancel(); updateImport(); })
            .setNegativeButton("Continue", null)
            .show());
        page.addView(importCancel);
        updateImport();
        return true;
    }

    private void updateImport() {
        handler.removeCallbacks(tick);
        IsoImport job = IsoImport.current();
        if (job == null || job.finished() || importBar == null || importBar.getParent() == null) {
            render();
            return;
        }
        long total = Math.max(1, job.totalBytes);
        importBar.setProgress((int) (job.doneBytes * 1000 / total));
        if (job.state == IsoImport.State.CLEANING) {
            importBytes.setText("Deleting the extracted files…");
            importFile.setText("");
            importCancel.setEnabled(false);
        } else {
            importBytes.setText(IsoImport.gigabytes(job.doneBytes) + " of " + IsoImport.gigabytes(job.totalBytes)
                + String.format(" (%d%%)", job.doneBytes * 100 / total));
            importFile.setText(job.currentFile);
        }
        handler.postDelayed(tick, 250);
    }
}
