package com.lrrecomp.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * SaveBackups (CTRL -> Game -> Save backups): which saves a backup copies, which backups are kept, and how a restore
 * goes in at the next start. The copies through the folder picker's provider are checked on a device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SaveBackupsTest {
    private static final String XUID = "B13EBABEBABEBABE";

    private Context context;
    private File root;
    private File user;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        root = SaveBackups.appRoot(context);
        deleteRecursively(root);
        assertTrue(root.mkdirs());
        user = SaveBackups.userRoot(context);
    }

    // The saves ------------------------------------------------------------------------------------------------------

    @Test
    public void appRootIsTheFolderTheRuntimeGetsAsUserDataParent() {
        // RuntimeActivity passes <logs' parent>/user as --user_data_root.
        assertEquals(PlayerLogs.directory(context).getParentFile(), SaveBackups.appRoot(context));
    }

    @Test
    public void saveFoldersAreTheProfilesWithLrSaves() throws IOException {
        slot(user, XUID, "ff13-01.dat", "one");
        // A profile without saved games (only downloadable content), notes beside the profiles, and the development
        // scripts' player folder while a bench save is in use: none of them is a save folder.
        write(new File(user, "0000000000000000/5351081B/00000002/dlc/content.bin"), "dlc");
        write(new File(user, "resolution_ok.txt"), "1");
        write(new File(user, XUID + "/5351081B.player/00000001/ff13-07.dat/APP.DAT"), "player");
        List<File> folders = SaveBackups.saveFolders(user);
        assertEquals(1, folders.size());
        assertEquals(new File(user, XUID + "/5351081B"), folders.get(0));
        assertTrue(SaveBackups.hasSave(context));
    }

    @Test
    public void noSavesOnAFreshInstall() {
        assertTrue(SaveBackups.saveFolders(user).isEmpty());
        assertFalse(SaveBackups.hasSave(context));
        assertEquals("", SaveBackups.signature(user));
    }

    @Test
    public void signatureListsEverySlotAndHeaderButNotDownloadableContent() throws IOException {
        slot(user, XUID, "ff13-01.dat", "one");
        slot(user, XUID, "ff13-50.dat", "autosave");
        write(new File(user, XUID + "/5351081B/00000002/dlc/content.bin"), "dlc");
        String signature = SaveBackups.signature(user);
        assertTrue(signature, signature.contains(XUID + "/5351081B/00000001/ff13-01.dat/APP.DAT 3 "));
        assertTrue(signature, signature.contains(XUID + "/5351081B/00000001/ff13-50.dat/APP.DAT 8 "));
        assertTrue(signature, signature.contains(XUID + "/5351081B/Headers/00000001/ff13-50.dat.header "));
        assertFalse(signature, signature.contains("00000002"));
        assertEquals(4, signature.split("\n").length);
    }

    @Test
    public void signatureChangesWhenTheGameSaves() throws IOException {
        File app = slot(user, XUID, "ff13-01.dat", "one");
        String before = SaveBackups.signature(user);
        write(app, "one, later");
        assertFalse(before.equals(SaveBackups.signature(user)));
    }

    // Kept backups ---------------------------------------------------------------------------------------------------

    @Test
    public void retainedKeepsTheNewestTenAndOnePerDayForSevenDays() {
        // Newest first: 12 backups today, then 2 each on 8 earlier days.
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 12; ++i) names.add(String.format("LR save 2026-11-24 %02d-00-00", 23 - i));
        for (int day = 23; day >= 16; --day) {
            names.add(String.format("LR save 2026-11-%02d 20-00-00", day));
            names.add(String.format("LR save 2026-11-%02d 08-00-00", day));
        }
        Set<String> keep = SaveBackups.retained(names);
        for (int i = 0; i < 10; ++i) assertTrue(names.get(i), keep.contains(names.get(i)));
        assertFalse(keep.contains("LR save 2026-11-24 13-00-00"));
        assertFalse(keep.contains("LR save 2026-11-24 12-00-00"));
        // Today (in the newest ten) and the newest of each of the next six days.
        for (int day = 23; day >= 18; --day) {
            assertTrue(keep.contains(String.format("LR save 2026-11-%02d 20-00-00", day)));
            assertFalse(keep.contains(String.format("LR save 2026-11-%02d 08-00-00", day)));
        }
        assertFalse(keep.contains("LR save 2026-11-17 20-00-00"));
        assertEquals(10 + 6, keep.size());
    }

    /** 19 backups newest first (12 today, then one a day for 7 days); the count rules keep 16 (10 + 6 days). */
    private static List<String> manyBackups() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 12; ++i) names.add(String.format("LR save 2026-11-24 %02d-00-00", 23 - i));
        for (int day = 23; day >= 17; --day) names.add(String.format("LR save 2026-11-%02d 20-00-00", day));
        return names;
    }

    private static List<Long> sizes(int count, long each) {
        List<Long> sizes = new ArrayList<>();
        for (int i = 0; i < count; ++i) sizes.add(each);
        return sizes;
    }

    @Test
    public void smallBackupsKeepWhatTheCountRulesKeep() {
        List<String> names = manyBackups();
        long fiveSlots = 5 * 547_944L;
        assertEquals(SaveBackups.retained(names), SaveBackups.retained(names, sizes(names.size(), fiveSlots)));
        assertEquals(10 + 6, SaveBackups.retained(names, sizes(names.size(), fiveSlots)).size());
    }

    @Test
    public void theBudgetKeepsTheNewestThreeThenTheDaysThenTheRecentOnes() {
        List<String> names = manyBackups();
        long tenSlots = 10 * 547_944L;  // 5.5 MB: 12 fit in 64 MB
        Set<String> keep = SaveBackups.retained(names, sizes(names.size(), tenSlots));
        assertEquals(12, keep.size());
        assertTrue(keep.size() * tenSlots <= SaveBackups.BUDGET_BYTES);
        // The newest 3 (today), one for each of the 6 days before, then 3 more of today's.
        for (int i = 0; i < 6; ++i) assertTrue(names.get(i), keep.contains(names.get(i)));
        for (int day = 23; day >= 18; --day) assertTrue(keep.contains(String.format("LR save 2026-11-%02d 20-00-00", day)));
        for (int i = 6; i < 12; ++i) assertFalse(names.get(i), keep.contains(names.get(i)));
    }

    @Test
    public void aDs2SizedBudgetKeepsTheDaysBeforeTheMinutes() {
        // One 7.2 MB save (DS2's): 9 fit, the newest 3 and 6 days back.
        List<String> names = manyBackups();
        Set<String> keep = SaveBackups.retained(names, sizes(names.size(), 7_192_804L));
        assertEquals(9, keep.size());
        for (int i = 0; i < 3; ++i) assertTrue(keep.contains(names.get(i)));
        for (int day = 23; day >= 18; --day) assertTrue(keep.contains(String.format("LR save 2026-11-%02d 20-00-00", day)));
    }

    @Test
    public void theNewestThreeStayWhateverTheirSize() {
        List<String> names = manyBackups();
        Set<String> keep = SaveBackups.retained(names, sizes(names.size(), 40L << 20));
        assertEquals(SaveBackups.KEEP_MIN, keep.size());
        for (int i = 0; i < SaveBackups.KEEP_MIN; ++i) assertTrue(keep.contains(names.get(i)));
    }

    @Test
    public void anUnknownSizeCountsAsTheLargestKnown() {
        List<String> names = manyBackups();
        List<Long> sizes = sizes(names.size(), -1);
        sizes.set(0, 20L << 20);  // the newest says 20 MB, the others say nothing: 3 fit (60 MB)
        assertEquals(3, SaveBackups.retained(names, sizes).size());
        // No size known at all: the count rules alone.
        assertEquals(16, SaveBackups.retained(names, sizes(names.size(), -1)).size());
    }

    @Test
    public void markerByteCount() {
        assertEquals(5_479_440L, SaveBackups.markerBytes("x\nfiles=20\nbytes=5479440\ntime=1 a/b\n"));
        assertEquals(-1, SaveBackups.markerBytes("files=2\n"));
        assertEquals(-1, SaveBackups.markerBytes(null));
    }

    @Test
    public void retainedKeepsEverythingWhenThereAreFew() {
        List<String> names = Arrays.asList("LR save 2026-11-24 10-00-00", "LR save 2026-11-20 10-00-00");
        assertEquals(2, SaveBackups.retained(names).size());
    }

    // Restoring ------------------------------------------------------------------------------------------------------

    @Test
    public void nothingToRestoreWithoutAPendingBackup() throws IOException {
        // A restore copy cut short (the app closed while copying) is removed and never applied.
        write(new File(root, SaveBackups.PENDING + ".tmp/" + XUID + "/5351081B/00000001/ff13-01.dat/APP.DAT"), "x");
        slot(user, XUID, "ff13-01.dat", "current");
        assertNull(SaveBackups.applyPendingRestore(context));
        assertFalse(new File(root, SaveBackups.PENDING + ".tmp").exists());
        assertFalse(SaveBackups.hasPendingRestore(context));
        assertEquals("current", read(new File(user, XUID + "/5351081B/00000001/ff13-01.dat/APP.DAT")));
    }

    @Test
    public void restoreReplacesEverySlotAndKeepsTheOldSavesAside() throws IOException {
        slot(user, XUID, "ff13-01.dat", "current 1");
        slot(user, XUID, "ff13-03.dat", "current 3");
        write(new File(user, "steam_saves_imported.txt"), "imported");
        write(new File(user, XUID + "/5351081B.player/keep.txt"), "player");
        File pending = new File(root, SaveBackups.PENDING);
        slot(pending, XUID, "ff13-01.dat", "backup 1");
        slot(pending, XUID, "ff13-02.dat", "backup 2");
        write(new File(pending, "restore.txt"), "LR save 2026-11-24 10-30-00");
        assertTrue(SaveBackups.hasPendingRestore(context));

        String message = SaveBackups.applyPendingRestore(context);
        assertNotNull(message);
        assertTrue(message, message.startsWith("Saves restored from the backup of "));
        assertFalse(pending.exists());

        File saves = new File(user, XUID + "/5351081B/00000001");
        assertEquals("backup 1", read(new File(saves, "ff13-01.dat/APP.DAT")));
        assertEquals("backup 2", read(new File(saves, "ff13-02.dat/APP.DAT")));
        // All slots together: a slot the backup does not have is not left beside it.
        assertFalse(new File(saves, "ff13-03.dat").exists());
        assertEquals("backup 2", read(new File(user, XUID + "/5351081B/Headers/00000001/ff13-02.dat.header")));
        // Files beside the title folder stay.
        assertEquals("imported", read(new File(user, "steam_saves_imported.txt")));
        assertEquals("player", read(new File(user, XUID + "/5351081B.player/keep.txt")));

        File[] aside = new File(root, SaveBackups.BEFORE_RESTORE).listFiles();
        assertNotNull(aside);
        assertEquals(1, aside.length);
        File old = new File(aside[0], XUID + "/5351081B/00000001");
        assertEquals("current 1", read(new File(old, "ff13-01.dat/APP.DAT")));
        assertEquals("current 3", read(new File(old, "ff13-03.dat/APP.DAT")));
    }

    @Test
    public void restoreKeepsDownloadableContentInPlace() throws IOException {
        slot(user, XUID, "ff13-01.dat", "current");
        write(new File(user, XUID + "/5351081B/00000002/dlc/content.bin"), "dlc");
        File pending = new File(root, SaveBackups.PENDING);
        slot(pending, XUID, "ff13-01.dat", "backup");
        write(new File(pending, "restore.txt"), "LR save 2026-11-24 10-30-00");
        assertNotNull(SaveBackups.applyPendingRestore(context));
        assertEquals("dlc", read(new File(user, XUID + "/5351081B/00000002/dlc/content.bin")));
    }

    @Test
    public void restoreIntoAFreshInstall() throws IOException {
        File pending = new File(root, SaveBackups.PENDING);
        slot(pending, XUID, "ff13-05.dat", "backup");
        write(new File(pending, "restore.txt"), "LR save 2026-11-24 10-30-00");
        assertNotNull(SaveBackups.applyPendingRestore(context));
        assertEquals("backup", read(new File(user, XUID + "/5351081B/00000001/ff13-05.dat/APP.DAT")));
        // Nothing was replaced: nothing kept aside.
        assertFalse(new File(root, SaveBackups.BEFORE_RESTORE).exists());
    }

    @Test
    public void onlyTheNewestThreeReplacedSavesAreKept() throws IOException {
        File before = new File(root, SaveBackups.BEFORE_RESTORE);
        for (String old : new String[] {"20260101-000000", "20260102-000000", "20260103-000000", "20260104-000000"}) {
            write(new File(before, old + "/" + XUID + "/5351081B/00000001/ff13-01.dat/APP.DAT"), old);
        }
        slot(user, XUID, "ff13-01.dat", "current");
        File pending = new File(root, SaveBackups.PENDING);
        slot(pending, XUID, "ff13-01.dat", "backup");
        write(new File(pending, "restore.txt"), "LR save 2026-11-24 10-30-00");
        assertNotNull(SaveBackups.applyPendingRestore(context));
        String[] kept = before.list();
        assertNotNull(kept);
        Arrays.sort(kept);
        assertEquals(3, kept.length);
        assertEquals("20260103-000000", kept[0]);
        assertEquals("20260104-000000", kept[1]);
        assertFalse(kept[2].startsWith("2026010"));
    }

    @Test
    public void restorePutsBackTheSavesOwnTimes() throws IOException {
        // LR's load screen shows a slot's file time as "Date saved": the copies must not keep the time of the copy.
        long saved = 1762729800000L;  // 2025-11-09 23:10 UTC
        long folder = saved - 60_000;
        File pending = new File(root, SaveBackups.PENDING);
        slot(pending, XUID, "ff13-07.dat", "backup");
        write(new File(pending, "restore.txt"), "LR save 2026-11-24 10-30-00");
        String base = XUID + "/5351081B";
        write(new File(pending, "times.txt"), "Lightning Returns ...\nfiles=2\nbytes=12\n"
            + "time=" + folder + " " + base + "\n"
            + "time=" + folder + " " + base + "/00000001/ff13-07.dat\n"
            + "time=" + saved + " " + base + "/00000001/ff13-07.dat/APP.DAT\n"
            + "time=" + saved + " " + base + "/Headers/00000001/ff13-07.dat.header\n"
            // Another profile's line (not in this restore) and a path leaving the folder change nothing.
            + "time=" + saved + " 0000000000000000/5351081B/x\n"
            + "time=" + saved + " " + XUID + "/../outside\n");
        File outside = new File(user, "outside");
        write(outside, "x");
        long outsideTime = outside.lastModified();
        assertNotNull(SaveBackups.applyPendingRestore(context));
        File title = new File(user, base);
        assertEquals(saved, new File(title, "00000001/ff13-07.dat/APP.DAT").lastModified());
        assertEquals(saved, new File(title, "Headers/00000001/ff13-07.dat.header").lastModified());
        assertEquals(folder, new File(title, "00000001/ff13-07.dat").lastModified());
        assertEquals(folder, title.lastModified());
        assertEquals(outsideTime, outside.lastModified());
    }

    @Test
    public void applyTimesOnlyTouchesThePrefixAndExistingPaths() throws IOException {
        File app = slot(user, XUID, "ff13-01.dat", "one");
        File other = slot(user, "E000000000000001", "ff13-01.dat", "two");
        long otherTime = other.lastModified();
        String text = "time=1000000 " + XUID + "/5351081B/00000001/ff13-01.dat/APP.DAT\n"
            + "time=1000000 " + XUID + "/5351081B/00000001/ff13-99.dat/APP.DAT\n"
            + "time=1000000 E000000000000001/5351081B/00000001/ff13-01.dat/APP.DAT\n"
            + "time=1000000 " + XUID + "/./5351081B\n";
        assertEquals(1, SaveBackups.applyTimes(user, text, XUID + "/"));
        assertEquals(1000000L, app.lastModified());
        assertEquals(otherTime, other.lastModified());
        assertEquals(0, SaveBackups.applyTimes(user, null, XUID + "/"));
    }

    @Test
    public void markerFileCount() {
        assertEquals(25, SaveBackups.markerFiles("Lightning Returns ...\nfiles=25\nbytes=7\ntime=1 a/b\n"));
        assertEquals(-1, SaveBackups.markerFiles("no count"));
        assertEquals(-1, SaveBackups.markerFiles(null));
    }

    @Test
    public void cancelledRestoreLeavesTheSavesAlone() throws IOException {
        slot(user, XUID, "ff13-01.dat", "current");
        File pending = new File(root, SaveBackups.PENDING);
        slot(pending, XUID, "ff13-01.dat", "backup");
        SaveBackups.cancelPendingRestore(context);
        assertFalse(SaveBackups.hasPendingRestore(context));
        assertNull(SaveBackups.applyPendingRestore(context));
        assertEquals("current", read(new File(user, XUID + "/5351081B/00000001/ff13-01.dat/APP.DAT")));
    }

    // Helpers --------------------------------------------------------------------------------------------------------

    /** A save slot as the runtime writes it: the slot's APP.DAT and its header. Returns the APP.DAT. */
    private static File slot(File userRoot, String xuid, String name, String text) throws IOException {
        File app = new File(userRoot, xuid + "/5351081B/00000001/" + name + "/APP.DAT");
        write(app, text);
        write(new File(userRoot, xuid + "/5351081B/Headers/00000001/" + name + ".header"), text);
        return app;
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) assertTrue(parent.mkdirs());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
