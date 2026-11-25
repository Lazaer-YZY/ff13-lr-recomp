package com.lrrecomp.android;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Save backups (CTRL -> Game -> Save backups; off until the player picks a folder). Ported from the DS2 project's
 * SaveBackups.java (its owner's request there, 2026-11-21: "keep save in original but also let user select default
 * backup folder that doesn't get deleted when game is uninstalled etc. Should keep some snapshots ideally"); for LR
 * the owner, 2026-11-24: "Yes. Work on s10".
 *
 * LR keeps its save slots (ff13-NN.dat; the autosave is ff13-50.dat) in user/<xuid>/5351081B/00000001 of the app
 * folder, their headers in 5351081B/Headers/00000001 (the native side writes them; a profile has LR saves when its
 * 5351081B/00000001 exists). Uninstalling the app deletes that folder, and the manifest's allowBackup is off, so a
 * lost phone or a reinstall lost the playthrough. The saves stay where they are; this only copies them, Java-side,
 * into a folder the player picks with the system folder picker (ACTION_OPEN_DOCUMENT_TREE, as Azahar and Dolphin
 * pick their user folders). The app keeps a persisted permission for that one folder, so no storage permission is
 * needed: picking the folder is the consent. Android drops the permission on uninstall, so after a reinstall the
 * player picks the same folder again, and the page then offers its newest backup.
 *
 * Backing up: a low-priority thread looks at the saves once a minute while the game runs, and once more when the
 * game goes to the background: their signature (every file's path, size and time) differs from the last backup's,
 * it then stays the same for 10 s (a save writes the slot and its header in steps), and the last backup is at least
 * 10 minutes old. A backup is a folder "LR save <yyyy-MM-dd HH-mm-ss>" holding <xuid>/5351081B/... as in user/,
 * every slot at once (~550 KB each); its "LR backup.txt" is written last, so a copy that was cut short (the app
 * closed, or the game saved during the copy) is never offered for restore and is removed by a later clean-up.
 * Folders beside 5351081B (the development scripts' 5351081B.player while a bench save is in use) are never copied.
 *
 * Kept: the newest 10, and the newest of each of the 7 most recent days with backups (at most 17), within 64 MB in
 * all: the newest 3 whatever their size, then one per day, then the other recent ones (BUDGET_BYTES, KEEP_MIN):
 * 5 slots keep all 17 (47 MB), 10 slots (5.5 MB a backup) 12, 30 slots 4; only past
 * ~40 slots do the newest 3 go over 64 MB. Only folders named like a backup are ever deleted.
 *
 * Restoring: the backup is copied into save_restore_pending/ of the app folder, and the next start moves it into
 * user/ before the game runs (applyPendingRestore, first thing in RuntimeActivity.onCreate), never under a running
 * game. A restore replaces all slots together; the saves it replaces are moved to user_before_restore/<time>/ (the
 * newest 3 are kept), not deleted. The files beside the profiles in user/ (steam_saves_imported.txt and the game's
 * other notes) are not part of a backup and stay as they are.
 */
final class SaveBackups {
    private static final String TAG = "lrrecomp";
    // Its own preferences, not CTRL's render_settings: those go into every log export, and the signature is long.
    private static final String PREFERENCES = "save_backups";
    private static final String FOLDER = "folder";
    private static final String SIGNATURE = "signature";
    private static final String LAST_BACKUP = "last_backup_ms";

    static final String TITLE_ID = "5351081B";
    /** A profile's saved games (content type 1); its presence means the profile has LR saves. */
    private static final String SAVE_GAMES = "00000001";
    /**
     * Downloadable content (type 2). The runtime installs it under profile 0000000000000000, which has no save and is
     * never copied (ContentManager::ResolvePackagePath); skipped anyway should a profile ever hold its 1.2 GB.
     */
    private static final String MARKETPLACE = "00000002";

    static final String PREFIX = "LR save ";
    private static final Pattern NAME = Pattern.compile("LR save (\\d{4}-\\d{2}-\\d{2}) \\d{2}-\\d{2}-\\d{2}");
    private static final String STAMP = "yyyy-MM-dd HH-mm-ss";
    /** Written last into a backup: without it the backup is incomplete. */
    private static final String MARKER = "LR backup.txt";
    private static final Pattern MARKER_FILES = Pattern.compile("(?m)^files=(\\d+)$");
    private static final Pattern MARKER_BYTES = Pattern.compile("(?m)^bytes=(\\d+)$");
    /**
     * The marker's "time=<ms> <profile>/5351081B/..." lines: each copied file's and folder's modification time. LR's
     * load screen shows a slot's "Date saved" from its files' time (S10, 2026-11-24: a copied slot showed the time of
     * the copy), and a copy through the folder provider cannot keep it, so a restore puts the recorded times back.
     */
    private static final Pattern MARKER_TIME = Pattern.compile("(?m)^time=(\\d+) (.+)$");

    static final String PENDING = "save_restore_pending";
    private static final String PENDING_STAGING = PENDING + ".tmp";
    private static final String PENDING_NAME = "restore.txt";
    /** The backup's time lines, applied once the saves are in place (applyPendingRestore). */
    private static final String PENDING_TIMES = "times.txt";
    static final String BEFORE_RESTORE = "user_before_restore";

    static final long CHECK_MS = 60_000;
    static final long STILL_MS = 10_000;
    static final long MIN_INTERVAL_MS = 10 * 60_000;
    static final int KEEP_NEWEST = 10;
    static final int KEEP_DAYS = 7;
    static final int KEEP_BEFORE_RESTORE = 3;
    /**
     * The backups' total size (the markers' bytes=) stays within this; the oldest go first. Every backup is a full
     * copy of every slot, so without it a player with many slots would keep 17 x (slots x ~550 KB): 30 slots = 280 MB
     * (the owner, 2026-11-24: "Ensure backup save folder doesnt grow disproportionally").
     */
    static final long BUDGET_BYTES = 64L << 20;
    /** Kept whatever their size (a player with very many slots still has a few to go back to). */
    static final int KEEP_MIN = 3;
    /** An incomplete backup older than this was cut short (a copy takes seconds) and is removed. */
    private static final long ABANDONED_MS = 60 * 60_000;

    /** One copy at a time, in either direction (the worker, "Back up now", a restore). */
    private static final Object COPY_LOCK = new Object();
    /** The newest backup time used (COPY_LOCK): two backups within a second get different names. */
    private static long lastStamp;

    private SaveBackups() {}

    // Paths ------------------------------------------------------------------------------------------------------

    /** The app files folder (as RuntimeActivity passes it to the runtime: the logs' parent). */
    static File appRoot(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    static File userRoot(Context context) {
        return new File(appRoot(context), "user");
    }

    /** Every profile's 5351081B folder that holds LR saves, by profile. */
    static List<File> saveFolders(File userRoot) {
        List<File> result = new ArrayList<>();
        File[] profiles = userRoot.listFiles();
        if (profiles == null) return result;
        Arrays.sort(profiles);
        for (File profile : profiles) {
            File save = new File(profile, TITLE_ID);
            if (new File(save, SAVE_GAMES).isDirectory()) result.add(save);
        }
        return result;
    }

    static boolean hasSave(Context context) {
        return !saveFolders(userRoot(context)).isEmpty();
    }

    /** Every save file's path (from user/), size and time, sorted: it changes whenever the game saves. */
    static String signature(File userRoot) {
        List<String> entries = new ArrayList<>();
        for (File save : saveFolders(userRoot)) {
            addEntries(save, save.getParentFile().getName() + "/" + TITLE_ID, true, entries);
        }
        Collections.sort(entries);
        StringBuilder signature = new StringBuilder();
        for (String entry : entries) signature.append(entry).append('\n');
        return signature.toString();
    }

    private static void addEntries(File folder, String path, boolean top, List<String> entries) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (top && file.getName().equals(MARKETPLACE)) continue;
            String relative = path + "/" + file.getName();
            if (file.isDirectory()) addEntries(file, relative, false, entries);
            else if (file.isFile()) entries.add(relative + ' ' + file.length() + ' ' + file.lastModified());
        }
    }

    // Setting ----------------------------------------------------------------------------------------------------

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** The chosen folder (a document tree), or null when backups are off. */
    static Uri folderSetting(Context context) {
        String value = prefs(context).getString(FOLDER, null);
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    /** Whether the app still holds its read and write permission for the folder. */
    static boolean hasAccess(Context context, Uri tree) {
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(tree) && permission.isReadPermission() && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    /** The folder backups go to, or null when they are off or the folder can no longer be opened. */
    static Uri folder(Context context) {
        Uri tree = folderSetting(context);
        return tree != null && hasAccess(context, tree) ? tree : null;
    }

    /**
     * Sets the folder (null: off) and keeps its permission across restarts; the previous folder's permission is given
     * back. The last backup's signature and time belonged to the previous folder: the new one gets a backup soon.
     */
    static void setFolder(Context context, Uri tree) {
        ContentResolver resolver = context.getContentResolver();
        Uri previous = folderSetting(context);
        int access = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (tree != null) resolver.takePersistableUriPermission(tree, access);
        if (previous != null && !previous.equals(tree)) {
            try {
                resolver.releasePersistableUriPermission(previous, access);
            } catch (SecurityException ignored) {
                // Already gone.
            }
        }
        prefs(context).edit().putString(FOLDER, tree != null ? tree.toString() : null)
            .remove(SIGNATURE).remove(LAST_BACKUP).commit();
        Log.i(TAG, "save backups: " + (tree != null ? "folder " + tree : "off"));
    }

    /** "Internal storage/Documents/LR saves" for the usual folders, else the folder's name. */
    static String describe(Context context, Uri tree) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (RuntimeException e) {
            id = null;
        }
        if (id != null && "com.android.externalstorage.documents".equals(tree.getAuthority())) {
            int colon = id.indexOf(':');
            if (colon > 0) {
                String volume = id.substring(0, colon);
                String path = id.substring(colon + 1);
                String base = volume.equalsIgnoreCase("primary") ? context.getString(R.string.backup_internal_storage)
                                                                 : context.getString(R.string.backup_sd_card, volume);
                return path.isEmpty() ? base : base + "/" + path;
            }
        }
        try {
            String name = displayName(context.getContentResolver(), rootDocument(tree));
            if (name != null) return name;
        } catch (RuntimeException ignored) {
            // The address below.
        }
        return tree.toString();
    }

    // Storage Access Framework (DocumentsContract; androidx.documentfile is not a dependency) ---------------------

    private static final class Entry {
        final Uri uri;
        final String name;
        final boolean folder;

        Entry(Uri uri, String name, boolean folder) {
            this.uri = uri;
            this.name = name;
            this.folder = folder;
        }
    }

    private static Uri rootDocument(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    private static List<Entry> children(ContentResolver resolver, Uri tree, Uri folder) throws IOException {
        List<Entry> result = new ArrayList<>();
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getDocumentId(folder));
            try (Cursor cursor = resolver.query(children, new String[] {Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) throw new IOException("the folder could not be read");
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    if (id == null || name == null) continue;
                    result.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name,
                        Document.MIME_TYPE_DIR.equals(cursor.getString(2))));
                }
            }
        } catch (RuntimeException e) {
            throw new IOException(String.valueOf(e.getMessage()), e);
        }
        return result;
    }

    private static String displayName(ContentResolver resolver, Uri document) {
        try (Cursor cursor = resolver.query(document, new String[] {Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static Uri create(ContentResolver resolver, Uri parent, String mimeType, String name) throws IOException {
        Uri uri;
        try {
            uri = DocumentsContract.createDocument(resolver, parent, mimeType, name);
        } catch (RuntimeException e) {
            throw new IOException(String.valueOf(e.getMessage()), e);
        }
        if (uri == null) throw new IOException("could not create " + name);
        return uri;
    }

    /** Deletes a document; a folder whose provider only deletes empty folders is emptied first. */
    private static void delete(ContentResolver resolver, Uri tree, Uri document) {
        try {
            if (DocumentsContract.deleteDocument(resolver, document)) return;
        } catch (IOException | RuntimeException ignored) {
            // Below.
        }
        try {
            for (Entry entry : children(resolver, tree, document)) delete(resolver, tree, entry.uri);
            DocumentsContract.deleteDocument(resolver, document);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "save backups: could not delete " + document + ": " + e);
        }
    }

    // Backing up -------------------------------------------------------------------------------------------------

    /** The save changed while it was copied; that copy was deleted. */
    static final class SaveChanged extends IOException {
        SaveChanged() {
            super("the game saved during the copy");
        }
    }

    private static SimpleDateFormat stampFormat() {
        return new SimpleDateFormat(STAMP, Locale.US);
    }

    /**
     * Copies the saves into a new backup folder now and cleans up old backups. Returns the backup's name, or null when
     * the game has no save yet; throws SaveChanged when the game saved during the copy (nothing is left behind).
     */
    static String backUp(Context context) throws IOException {
        synchronized (COPY_LOCK) {
            Uri tree = folder(context);
            if (tree == null) throw new IOException(context.getString(R.string.backup_no_folder));
            File user = userRoot(context);
            String before = signature(user);
            List<File> saves = saveFolders(user);
            if (saves.isEmpty()) return null;
            ContentResolver resolver = context.getContentResolver();
            long now = Math.max(System.currentTimeMillis(), lastStamp + 1000);
            lastStamp = now;
            String name = PREFIX + stampFormat().format(new Date(now));
            Uri backup = create(resolver, rootDocument(tree), Document.MIME_TYPE_DIR, name);
            long[] totals = new long[2];  // files, bytes
            StringBuilder times = new StringBuilder();
            try {
                // A provider renames a folder whose name is taken ("... (1)"); such a folder would never be listed.
                String created = displayName(resolver, backup);
                if (created != null && !created.equals(name)) throw new IOException(name + " already exists");
                byte[] buffer = new byte[1 << 16];
                for (File save : saves) {
                    String path = save.getParentFile().getName() + "/" + TITLE_ID;
                    addTime(times, save, path);
                    Uri profile = create(resolver, backup, Document.MIME_TYPE_DIR, save.getParentFile().getName());
                    copyToTree(resolver, save, create(resolver, profile, Document.MIME_TYPE_DIR, TITLE_ID), true,
                        buffer, totals, path, times);
                }
                if (!before.equals(signature(user))) throw new SaveChanged();
                writeMarker(context, resolver, backup, now, totals, times);
            } catch (IOException | RuntimeException e) {
                delete(resolver, tree, backup);
                throw e;
            }
            prefs(context).edit().putString(SIGNATURE, before).putLong(LAST_BACKUP, now).apply();
            Log.i(TAG, "save backups: " + name + " (" + totals[0] + " files, " + totals[1] + " bytes)");
            prune(context, resolver, tree);
            return name;
        }
    }

    private static void copyToTree(ContentResolver resolver, File from, Uri to, boolean top, byte[] buffer,
                                   long[] totals, String path, StringBuilder times) throws IOException {
        File[] files = from.listFiles();
        if (files == null) throw new IOException("cannot read " + from);
        Arrays.sort(files);
        for (File file : files) {
            if (top && file.getName().equals(MARKETPLACE)) continue;
            String relative = path + "/" + file.getName();
            addTime(times, file, relative);
            if (file.isDirectory()) {
                copyToTree(resolver, file, create(resolver, to, Document.MIME_TYPE_DIR, file.getName()), false,
                    buffer, totals, relative, times);
            } else if (file.isFile()) {
                // application/octet-stream: providers keep the name as it is (no extension added).
                Uri target = create(resolver, to, "application/octet-stream", file.getName());
                try (InputStream input = new FileInputStream(file);
                     OutputStream output = resolver.openOutputStream(target, "w")) {
                    if (output == null) throw new IOException("cannot write " + file.getName());
                    for (int n; (n = input.read(buffer)) > 0; ) {
                        output.write(buffer, 0, n);
                        totals[1] += n;
                    }
                }
                ++totals[0];
            }
        }
    }

    /** A "time=<ms> <path>" line for the marker (a file or folder of the copy, path from the profile). */
    private static void addTime(StringBuilder times, File file, String path) {
        long time = file.lastModified();
        if (time > 0) times.append("time=").append(time).append(' ').append(path).append('\n');
    }

    /**
     * Sets the recorded modification times (the marker's time lines) on the files and folders under {@code base}
     * that exist, for the paths starting with {@code prefix} (a profile's "<xuid>/"); a line whose path leaves
     * {@code base} is skipped. Returns how many were set.
     */
    static int applyTimes(File base, String text, String prefix) {
        if (text == null) return 0;
        int set = 0;
        Matcher matcher = MARKER_TIME.matcher(text);
        while (matcher.find()) {
            String path = matcher.group(2);
            if (!path.startsWith(prefix)) continue;
            boolean valid = true;
            for (String part : path.split("/", -1)) valid &= validName(part);
            if (!valid) continue;
            File file = new File(base, path);
            try {
                if (file.exists() && file.setLastModified(Long.parseLong(matcher.group(1)))) ++set;
            } catch (RuntimeException ignored) {
                // A time out of range: the copy's own time stays.
            }
        }
        return set;
    }

    private static void writeMarker(Context context, ContentResolver resolver, Uri backup, long when, long[] totals,
                                    CharSequence times) throws IOException {
        Uri marker = create(resolver, backup, "text/plain", MARKER);
        String text = "Lightning Returns: Final Fantasy XIII (Android port): save backup of "
            + DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(new Date(when)) + ".\n"
            + "Restore it in the game: CTRL, Save backups. This file marks the backup as complete.\n"
            + "files=" + totals[0] + "\nbytes=" + totals[1] + "\n" + times;
        try (OutputStream output = resolver.openOutputStream(marker, "w")) {
            if (output == null) throw new IOException("cannot write " + MARKER);
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    // Listing and clean-up ---------------------------------------------------------------------------------------

    /** A backup folder in the chosen folder. */
    static final class Backup {
        final Uri tree;
        final Uri uri;
        final String name;
        final long time;
        final boolean complete;
        /** Its marker file (complete backups only). */
        final Uri marker;

        Backup(Uri tree, Uri uri, String name, long time, Uri marker) {
            this.tree = tree;
            this.uri = uri;
            this.name = name;
            this.time = time;
            this.complete = marker != null;
            this.marker = marker;
        }

        String label() {
            return SaveBackups.label(time);
        }
    }

    /** A backup's date and time for the page, in the device's format. */
    static String label(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time));
    }

    /** The backups in the folder, newest first; incomplete ones only with includeIncomplete. */
    static List<Backup> list(Context context, Uri tree, boolean includeIncomplete) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        List<Backup> result = new ArrayList<>();
        Uri root;
        try {
            root = rootDocument(tree);
        } catch (RuntimeException e) {
            throw new IOException(String.valueOf(e.getMessage()), e);
        }
        for (Entry entry : children(resolver, tree, root)) {
            if (!entry.folder || !NAME.matcher(entry.name).matches()) continue;
            long time;
            try {
                Date parsed = stampFormat().parse(entry.name.substring(PREFIX.length()));
                if (parsed == null) continue;
                time = parsed.getTime();
            } catch (ParseException e) {
                continue;
            }
            Uri marker = null;
            try {
                for (Entry child : children(resolver, tree, entry.uri)) {
                    if (!child.folder && MARKER.equals(child.name)) {
                        marker = child.uri;
                        break;
                    }
                }
            } catch (IOException ignored) {
                // Unreadable: treated as incomplete.
            }
            if (marker != null || includeIncomplete) result.add(new Backup(tree, entry.uri, entry.name, time, marker));
        }
        // The names' stamps sort by time.
        result.sort((a, b) -> b.name.compareTo(a.name));
        return result;
    }

    /**
     * The complete backups to keep, from their names newest first: the newest KEEP_NEWEST, and the newest of each of
     * the KEEP_DAYS most recent days that have backups (a spread back in time for a player who plays now and then).
     */
    static Set<String> retained(List<String> newestFirst) {
        Set<String> keep = new HashSet<>();
        Set<String> days = new HashSet<>();
        for (int i = 0; i < newestFirst.size(); ++i) {
            String name = newestFirst.get(i);
            if (i < KEEP_NEWEST) keep.add(name);
            String day = name.substring(PREFIX.length(), PREFIX.length() + 10);
            if (days.size() < KEEP_DAYS && days.add(day)) keep.add(name);
        }
        return keep;
    }

    /**
     * retained(), within BUDGET_BYTES. The retained backups are taken in this order while their total stays within
     * the budget: the newest KEEP_MIN (kept whatever their size), then the newest of each day (newest day first),
     * then the rest of the newest KEEP_NEWEST. So a tight budget gives up minutes-apart copies before the days back
     * in time (DS2's 7 MB backups: 9 fit, the newest 3 and 6 days). A size below 0 is unknown (a marker without
     * bytes=) and counts as the largest known size. Sizes follow {@code newestFirst}.
     */
    static Set<String> retained(List<String> newestFirst, List<Long> sizes) {
        int count = newestFirst.size();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < Math.min(KEEP_MIN, count); ++i) order.add(i);
        Set<String> days = new HashSet<>();
        for (int i = 0; i < count; ++i) {
            String name = newestFirst.get(i);
            String day = name.substring(PREFIX.length(), PREFIX.length() + 10);
            if (days.size() < KEEP_DAYS && days.add(day) && !order.contains(i)) order.add(i);
        }
        for (int i = 0; i < Math.min(KEEP_NEWEST, count); ++i) if (!order.contains(i)) order.add(i);
        long largest = 0;
        for (long size : sizes) largest = Math.max(largest, size);
        Set<String> keep = new HashSet<>();
        long total = 0;
        for (int i : order) {
            long size = sizes.get(i) >= 0 ? sizes.get(i) : largest;
            // The rest come later in the order: once one does not fit, none of them is kept.
            if (keep.size() >= KEEP_MIN && total + size > BUDGET_BYTES) break;
            keep.add(newestFirst.get(i));
            total += size;
        }
        return keep;
    }

    /** The byte count a marker records, or -1. */
    static long markerBytes(String marker) {
        if (marker == null) return -1;
        Matcher matcher = MARKER_BYTES.matcher(marker);
        try {
            return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Deletes the backups beyond retained(), and incomplete ones that were cut short (COPY_LOCK held). */
    private static void prune(Context context, ContentResolver resolver, Uri tree) {
        try {
            List<Backup> all = list(context, tree, true);
            List<String> complete = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            for (Backup backup : all) {
                if (!backup.complete) continue;
                complete.add(backup.name);
                sizes.add(markerBytes(readMarker(resolver, backup.marker)));
            }
            Set<String> keep = retained(complete, sizes);
            long now = System.currentTimeMillis();
            for (Backup backup : all) {
                boolean remove = backup.complete ? !keep.contains(backup.name) : now - backup.time > ABANDONED_MS;
                if (!remove) continue;
                delete(resolver, tree, backup.uri);
                Log.i(TAG, "save backups: removed " + backup.name + (backup.complete ? "" : " (incomplete)"));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "save backups: clean-up failed: " + e);
        }
    }

    // Restoring --------------------------------------------------------------------------------------------------

    static boolean hasPendingRestore(Context context) {
        return new File(appRoot(context), PENDING).isDirectory();
    }

    static void cancelPendingRestore(Context context) {
        synchronized (COPY_LOCK) {
            deleteRecursively(new File(appRoot(context), PENDING_STAGING));
            deleteRecursively(new File(appRoot(context), PENDING));
        }
        Log.i(TAG, "save backups: restore cancelled");
    }

    /**
     * Copies a backup into save_restore_pending/ of the app folder, which the next start puts in place
     * (applyPendingRestore). The copy goes to a staging folder first and is renamed when complete, so a copy cut
     * short is never restored.
     */
    static void prepareRestore(Context context, Backup backup) throws IOException {
        synchronized (COPY_LOCK) {
            ContentResolver resolver = context.getContentResolver();
            File root = appRoot(context);
            File staging = new File(root, PENDING_STAGING);
            deleteRecursively(staging);
            try {
                if (!staging.mkdirs()) throw new IOException("cannot create " + staging);
                long[] totals = new long[2];
                String marker = null;
                byte[] buffer = new byte[1 << 16];
                for (Entry entry : children(resolver, backup.tree, backup.uri)) {
                    if (entry.folder && validName(entry.name)) {
                        copyFromTree(resolver, backup.tree, entry.uri, new File(staging, entry.name), buffer, totals);
                    } else if (!entry.folder && MARKER.equals(entry.name)) {
                        marker = readMarker(resolver, entry.uri);
                    }
                }
                if (saveFolders(staging).isEmpty()) throw new IOException(context.getString(R.string.backup_empty));
                long expected = markerFiles(marker);
                if (expected >= 0 && expected != totals[0]) {
                    throw new IOException("incomplete (" + totals[0] + " of " + expected + " files)");
                }
                writeText(new File(staging, PENDING_NAME), backup.name);
                if (marker != null) writeText(new File(staging, PENDING_TIMES), marker);
                File pending = new File(root, PENDING);
                deleteRecursively(pending);
                if (!staging.renameTo(pending)) throw new IOException("cannot move " + staging);
                Log.i(TAG, "save backups: " + backup.name + " (" + totals[0] + " files) waits for the next start");
            } catch (IOException | RuntimeException e) {
                deleteRecursively(staging);
                throw e;
            }
        }
    }

    private static boolean validName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0;
    }

    private static void copyFromTree(ContentResolver resolver, Uri tree, Uri from, File to, byte[] buffer,
                                     long[] totals) throws IOException {
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
        for (Entry entry : children(resolver, tree, from)) {
            if (!validName(entry.name)) continue;
            File target = new File(to, entry.name);
            if (entry.folder) {
                copyFromTree(resolver, tree, entry.uri, target, buffer, totals);
                continue;
            }
            try (InputStream input = resolver.openInputStream(entry.uri);
                 OutputStream output = new FileOutputStream(target)) {
                if (input == null) throw new IOException("cannot read " + entry.name);
                for (int n; (n = input.read(buffer)) > 0; ) {
                    output.write(buffer, 0, n);
                    totals[1] += n;
                }
            }
            ++totals[0];
        }
    }

    /** The backup's marker text (at most 1 MB: with every slot's time lines it is a few KB), or null. */
    private static String readMarker(ContentResolver resolver, Uri marker) {
        try (InputStream input = resolver.openInputStream(marker)) {
            if (input == null) return null;
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = input.read(buffer)) > 0 && text.size() < (1 << 20); ) text.write(buffer, 0, n);
            return new String(text.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The file count a marker records, or -1. */
    static long markerFiles(String marker) {
        if (marker == null) return -1;
        Matcher matcher = MARKER_FILES.matcher(marker);
        try {
            return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Puts a restored backup in place (RuntimeActivity.onCreate, before the game starts): each profile's current
     * 5351081B folder is moved to user_before_restore/<time>/ first, then the backup's is moved into user/. Renames
     * within the app folder, so this is quick. Returns a message for the player, or null when nothing was waiting.
     */
    static String applyPendingRestore(Context context) {
        File root = appRoot(context);
        // A restore copy cut short (the app closed while copying): never applied.
        deleteRecursively(new File(root, PENDING_STAGING));
        File pending = new File(root, PENDING);
        if (!pending.isDirectory()) return null;
        String name = readText(new File(pending, PENDING_NAME));
        String times = readText(new File(pending, PENDING_TIMES));
        File aside = new File(new File(root, BEFORE_RESTORE),
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
        File user = userRoot(context);
        int restored = 0;
        int failed = 0;
        for (File save : saveFolders(pending)) {
            String profile = save.getParentFile().getName();
            File current = new File(new File(user, profile), TITLE_ID);
            File previous = new File(new File(aside, profile), TITLE_ID);
            if (current.exists()) {
                //noinspection ResultOfMethodCallIgnored
                previous.getParentFile().mkdirs();
                if (!current.renameTo(previous)) {
                    Log.e(TAG, "save restore: could not move " + current + " aside; profile " + profile + " unchanged");
                    ++failed;
                    continue;
                }
            }
            //noinspection ResultOfMethodCallIgnored
            current.getParentFile().mkdirs();
            if (!save.renameTo(current)) {
                Log.e(TAG, "save restore: could not move " + save + " to " + current);
                if (previous.exists() && !previous.renameTo(current)) {
                    Log.e(TAG, "save restore: the previous save stays in " + previous);
                }
                ++failed;
                continue;
            }
            // Downloadable content is not part of a backup; should it have been in this folder, it stays.
            File content = new File(previous, MARKETPLACE);
            if (content.exists() && !content.renameTo(new File(current, MARKETPLACE))) {
                Log.w(TAG, "save restore: " + content + " stays aside");
            }
            ++restored;
            // The saves' own times, once they are in place (the copies have the time of the copy; the load screen
            // shows a slot's file time as its "Date saved").
            int timed = applyTimes(user, times, profile + "/");
            Log.i(TAG, "save restore: profile " + profile + " from " + name + " (" + timed + " file and folder times)"
                + (previous.exists() ? "; the previous save is in " + previous : ""));
        }
        deleteRecursively(pending);
        keepNewest(new File(root, BEFORE_RESTORE), KEEP_BEFORE_RESTORE);
        if (restored == 0 || failed > 0) {
            Log.e(TAG, "save restore of " + name + ": " + restored + " profiles restored, " + failed + " failed");
            return context.getString(R.string.backup_restore_start_failed);
        }
        // The restored save is a backup already.
        prefs(context).edit().putString(SIGNATURE, signature(user)).apply();
        String when = String.valueOf(name);
        try {
            Date parsed = name != null && name.startsWith(PREFIX)
                ? stampFormat().parse(name.substring(PREFIX.length())) : null;
            if (parsed != null) when = label(parsed.getTime());
        } catch (ParseException ignored) {
            // The backup's folder name instead.
        }
        return context.getString(R.string.backup_restored, when);
    }

    /** Keeps the newest {@code keep} folders in {@code parent} (names are times, so they sort by age). */
    private static void keepNewest(File parent, int keep) {
        File[] folders = parent.listFiles(File::isDirectory);
        if (folders == null || folders.length <= keep) return;
        Arrays.sort(folders, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = keep; i < folders.length; ++i) {
            deleteRecursively(folders[i]);
            Log.i(TAG, "save restore: removed " + folders[i]);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File file) {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = input.read(buffer)) > 0; ) text.write(buffer, 0, n);
            return new String(text.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    // The background thread --------------------------------------------------------------------------------------

    /** Backs the saves up while the game runs (RuntimeActivity: started when a folder is set, stopped on destroy). */
    static final class Worker {
        private final Context context;
        private final Object signal = new Object();
        private boolean stopped;
        private boolean poked;

        Worker(Context context) {
            this.context = context.getApplicationContext();
            Thread thread = new Thread(this::run, "LR save backups");
            thread.setDaemon(true);
            thread.start();
        }

        /** One check now, on the thread (the game going to the background, a new folder). */
        void checkSoon() {
            synchronized (signal) {
                poked = true;
                signal.notifyAll();
            }
        }

        void shutdown() {
            synchronized (signal) {
                stopped = true;
                signal.notifyAll();
            }
        }

        /** Waits {@code ms} (or until poked, with wakeOnPoke); false once stopped. */
        private boolean pause(long ms, boolean wakeOnPoke) {
            long end = SystemClock.elapsedRealtime() + ms;
            synchronized (signal) {
                while (!stopped) {
                    if (wakeOnPoke && poked) {
                        poked = false;
                        break;
                    }
                    long left = end - SystemClock.elapsedRealtime();
                    if (left <= 0) break;
                    try {
                        signal.wait(left);
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
                return !stopped;
            }
        }

        private void run() {
            // Below the game's threads: a check is a directory listing, a backup a few MB once in a while.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (pause(CHECK_MS, true)) {
                try {
                    check();
                } catch (RuntimeException e) {
                    Log.w(TAG, "save backups: check failed: " + e);
                }
            }
        }

        private void check() {
            if (folder(context) == null) return;
            File user = userRoot(context);
            String signature = signature(user);
            SharedPreferences prefs = prefs(context);
            if (signature.isEmpty() || signature.equals(prefs.getString(SIGNATURE, ""))) return;
            long last = prefs.getLong(LAST_BACKUP, 0);
            long now = System.currentTimeMillis();
            if (now >= last && now - last < MIN_INTERVAL_MS) return;
            // The game writes its save in several steps: only a save that stays still is copied.
            if (!pause(STILL_MS, false) || !signature.equals(signature(user))) return;
            try {
                backUp(context);
            } catch (SaveChanged e) {
                Log.i(TAG, "save backups: the game saved during the copy; again at the next check");
            } catch (IOException e) {
                Log.w(TAG, "save backups: backup failed: " + e);
            }
        }
    }

    // CTRL page --------------------------------------------------------------------------------------------------

    /**
     * CTRL -> Save backups: the folder (or Off), Choose folder / Turn off / Back up now, and the backups with Restore.
     * Without a save (a fresh install) and with backups in the folder, "Restore your latest backup" comes first;
     * offerNewest (a folder just picked) asks once, at the first listing, when the folder already held backups.
     */
    static final class Page {
        private final RuntimeActivity activity;
        private final boolean offerNewest;
        /** When the folder was picked: only backups made before it are offered (not the ones made since, of this save). */
        private final long pickedAt = System.currentTimeMillis();
        private final LinearLayout content;
        private final AlertDialog dialog;
        private boolean offered;
        private int generation;

        Page(RuntimeActivity activity, boolean offerNewest) {
            this.activity = activity;
            this.offerNewest = offerNewest;
            content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(20), dp(8), dp(20), dp(8));
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(content);
            dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.backup_title))
                .setView(scroll)
                .setNegativeButton(activity.getString(R.string.backup_close), null)
                .create();
        }

        void show() {
            dialog.show();
            render();
        }

        private int dp(float value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }

        private TextView text(LinearLayout parent, String value, boolean bold) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, bold ? 16 : 14);
            if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setPadding(0, dp(8), 0, dp(2));
            parent.addView(view);
            return view;
        }

        private TextView hint(LinearLayout parent, String value) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            view.setAlpha(0.7f);
            view.setPadding(0, dp(2), 0, dp(4));
            parent.addView(view);
            return view;
        }

        private Button button(LinearLayout parent, int label, View.OnClickListener listener) {
            Button button = new Button(activity);
            button.setText(activity.getString(label));
            button.setAllCaps(false);
            button.setOnClickListener(listener);
            parent.addView(button);
            return button;
        }

        private LinearLayout row() {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            content.addView(row);
            return row;
        }

        private void toast(String message) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }

        /** Fills the page: the parts that need the folder are read on a thread. */
        private void render() {
            int current = ++generation;
            content.removeAllViews();
            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.VERTICAL);
            content.addView(top);
            hint(content, activity.getString(R.string.backup_explain));
            if (hasPendingRestore(activity)) {
                TextView pending = text(content, activity.getString(R.string.backup_pending), true);
                pending.setTextColor(Color.rgb(255, 196, 80));
                LinearLayout buttons = row();
                button(buttons, R.string.backup_restart_now, v -> activity.restartGame());
                button(buttons, R.string.backup_pending_cancel, v -> {
                    cancelPendingRestore(activity);
                    render();
                });
            }
            Uri tree = folderSetting(activity);
            if (tree == null) {
                text(content, activity.getString(R.string.backup_folder, activity.getString(R.string.backup_off)), true);
                hint(content, activity.getString(R.string.backup_pick_hint));
                button(row(), R.string.backup_choose, v -> choose());
                return;
            }
            TextView folderLine = text(content, activity.getString(R.string.backup_folder, "…"), true);
            TextView access = hint(content, activity.getString(R.string.backup_folder_lost));
            access.setVisibility(View.GONE);
            access.setTextColor(Color.rgb(255, 196, 80));
            access.setAlpha(1f);
            LinearLayout buttons = row();
            button(buttons, R.string.backup_choose, v -> choose());
            button(buttons, R.string.backup_turn_off, v -> {
                setFolder(activity, null);
                activity.stopSaveBackups();
                toast(activity.getString(R.string.backup_turned_off));
                render();
            });
            Button now = button(buttons, R.string.backup_now, null);
            now.setEnabled(false);
            text(content, activity.getString(R.string.backup_list), true);
            LinearLayout list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            content.addView(list);
            TextView status = hint(list, activity.getString(R.string.backup_reading));
            now.setOnClickListener(v -> backUpNow(now, status));

            new Thread(() -> {
                String label = describe(activity, tree);
                boolean readable = hasAccess(activity, tree);
                List<Backup> backups = null;
                String error = null;
                if (readable) {
                    try {
                        backups = list(activity, tree, false);
                    } catch (IOException | RuntimeException e) {
                        error = String.valueOf(e.getMessage());
                    }
                }
                boolean save = hasSave(activity);
                List<Backup> found = backups;
                String failure = error;
                activity.runOnUiThread(() -> {
                    if (current != generation || !dialog.isShowing()) return;
                    folderLine.setText(activity.getString(R.string.backup_folder, label));
                    access.setVisibility(readable ? View.GONE : View.VISIBLE);
                    now.setEnabled(readable && save);
                    fill(top, list, status, found, failure, save);
                });
            }, "LR save backups page").start();
        }

        private void fill(LinearLayout top, LinearLayout list, TextView status, List<Backup> backups, String error,
                          boolean save) {
            if (backups == null) {
                status.setText(error != null ? activity.getString(R.string.backup_failed, error) : "");
                return;
            }
            // The offer is decided once, at the first listing: a later listing (after "Back up now", or the worker's
            // first backup into the new folder) holds this save's own copies.
            Backup offer = null;
            if (offerNewest && !offered) {
                offered = true;
                for (Backup backup : backups) {
                    // Names have whole seconds: 2 s of margin.
                    if (backup.time + 2000 < pickedAt) {
                        offer = backup;
                        break;
                    }
                }
            }
            if (backups.isEmpty()) {
                status.setText(activity.getString(R.string.backup_none));
                return;
            }
            list.removeView(status);
            Backup newest = backups.get(0);
            if (!save) {
                // A fresh install (or a lost save): the way back comes first.
                TextView noSave = text(top, activity.getString(R.string.backup_no_save), true);
                noSave.setTextColor(Color.rgb(255, 196, 80));
                Button restore = new Button(activity);
                restore.setText(activity.getString(R.string.backup_restore_latest, newest.label()));
                restore.setAllCaps(false);
                restore.setTypeface(Typeface.DEFAULT_BOLD);
                restore.setOnClickListener(v -> restore(newest));
                top.addView(restore);
            }
            for (Backup backup : backups) {
                LinearLayout line = new LinearLayout(activity);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView when = new TextView(activity);
                when.setText(backup.label());
                when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                line.addView(when, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                button(line, R.string.backup_restore, v -> restore(backup));
                list.addView(line);
            }
            if (offer != null) {
                Backup candidate = offer;
                new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.backup_found_title))
                    .setMessage(activity.getString(save ? R.string.backup_found_message_has_save
                                                        : R.string.backup_found_message, candidate.label()))
                    .setPositiveButton(activity.getString(R.string.backup_restore), (d, which) -> restore(candidate))
                    .setNegativeButton(activity.getString(R.string.backup_not_now), null)
                    .show();
            }
        }

        private void choose() {
            dialog.dismiss();
            activity.chooseSaveBackupFolder();
        }

        private void backUpNow(Button now, TextView status) {
            now.setEnabled(false);
            now.setText(activity.getString(R.string.backup_working));
            new Thread(() -> {
                String message;
                try {
                    String name = backUp(activity);
                    message = name == null ? activity.getString(R.string.backup_nothing)
                                           : activity.getString(R.string.backup_done);
                } catch (SaveChanged e) {
                    message = activity.getString(R.string.backup_raced);
                } catch (IOException | RuntimeException e) {
                    message = activity.getString(R.string.backup_failed, String.valueOf(e.getMessage()));
                }
                String shown = message;
                activity.runOnUiThread(() -> {
                    toast(shown);
                    if (dialog.isShowing()) render();
                });
            }, "LR save backup").start();
        }

        /** Copies the backup into the app folder, then asks to restart (the restore happens at the start). */
        private void restore(Backup backup) {
            AlertDialog progress = new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.backup_restoring))
                .setCancelable(false)
                .show();
            new Thread(() -> {
                String error = null;
                try {
                    prepareRestore(activity, backup);
                } catch (IOException | RuntimeException e) {
                    error = String.valueOf(e.getMessage());
                }
                String failure = error;
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (failure != null) {
                        new AlertDialog.Builder(activity)
                            .setMessage(activity.getString(R.string.backup_restore_failed, failure))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                        return;
                    }
                    if (dialog.isShowing()) render();
                    new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.backup_restore_title))
                        .setMessage(activity.getString(R.string.backup_restore_message, backup.label()))
                        .setCancelable(false)
                        .setPositiveButton(activity.getString(R.string.backup_restart_now),
                            (d, which) -> activity.restartGame())
                        .setNeutralButton(activity.getString(R.string.restart_later),
                            (d, which) -> toast(activity.getString(R.string.backup_restore_later)))
                        .setNegativeButton(activity.getString(R.string.touch_cancel), (d, which) -> {
                            cancelPendingRestore(activity);
                            if (dialog.isShowing()) render();
                        })
                        .show();
                });
            }, "LR save restore").start();
        }
    }
}
