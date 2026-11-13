package com.lrrecomp.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Runs the on-device companion asset builder (CompanionAssets) against the real game files on the
 * PC (LR_GAME_ROOT, default C:/lrgame; skipped without them) into app/build/companion_assets_java/,
 * compares it with the Python-built APK assets (src/main/assets/{navimap,hud}, when staged), and
 * renders the CompanionPreviewTest scenes from the Java-built files (build/companion_preview/gen_*.png).
 *   ./gradlew :app:testDebugUnitTest --tests '*CompanionAssetsTest*'
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CompanionAssetsTest {
    private static File gameRoot() {
        String env = System.getenv("LR_GAME_ROOT");
        return new File(env != null && !env.isEmpty() ? env : "C:/lrgame");
    }

    private static List<String> files(File dir) {
        List<String> out = new ArrayList<>();
        walk(dir, "", out);
        return out;
    }

    private static void walk(File dir, String prefix, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String n = prefix + k.getName();
            if (k.getName().startsWith(".") || k.getName().startsWith("_sheet")) continue;
            if (k.isDirectory()) walk(k, n + "/", out);
            else out.add(n);
        }
    }

    private static String text(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** {mean abs difference over channels (premultiplied colour + alpha), fraction of alpha > 128 in a, in b}. */
    private static Bitmap read(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPremultiplied = false;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new AssertionError("cannot decode " + f);
        return b;
    }

    private static double[] diff(Bitmap a, Bitmap b) {
        double sum = 0;
        long covA = 0, covB = 0;
        int w = a.getWidth(), h = a.getHeight();
        int[] ra = new int[w], rb = new int[w];
        for (int y = 0; y < h; y++) {
            a.getPixels(ra, 0, w, 0, y, w, 1);
            b.getPixels(rb, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int p = ra[x], q = rb[x];
                int pa = p >>> 24, qa = q >>> 24;
                if (pa > 128) covA++;
                if (qa > 128) covB++;
                sum += Math.abs(pa - qa);
                for (int s = 0; s < 24; s += 8) sum += Math.abs(((p >> s) & 255) * pa / 255 - ((q >> s) & 255) * qa / 255);
            }
        }
        double n = (double) w * h;
        return new double[] { sum / (4 * n), covA / n, covB / n };
    }

    @Test
    public void buildFromGameFiles() throws Exception {
        File root = gameRoot();
        Assume.assumeTrue("no game files at " + root, new File(root, "weiss_data").isDirectory());
        File out = new File("build/companion_assets_java");
        CompanionAssets.deleteTree(out);
        out.mkdirs();
        long t0 = System.nanoTime();
        CompanionAssets.build(root, out, CompanionAssets.stampFor(root), s -> System.out.println("  " + s));
        System.out.printf(Locale.ROOT, "CompanionAssets.build: %.1f s total%n", (System.nanoTime() - t0) / 1e9);
        assertTrue(new File(out, "hud/hud.json").isFile());
        assertTrue(new File(out, "navimap/navimaps.json").isFile());
        assertTrue(new File(out, CompanionAssets.STAMP).isFile());

        File py = new File("src/main/assets");
        if (!new File(py, "navimap/navimaps.json").isFile() || !new File(py, "hud/hud.json").isFile()) {
            System.out.println("no Python-built assets in src/main/assets: comparison skipped");
        } else {
            compare(py, out);
        }
        renderScenes(out);
    }

    private static void compare(File py, File out) throws Exception {
        for (String folder : new String[] { "hud", "navimap" }) {
            TreeSet<String> a = new TreeSet<>(files(new File(py, folder))), b = new TreeSet<>(files(new File(out, folder)));
            TreeSet<String> onlyPy = new TreeSet<>(a), onlyJava = new TreeSet<>(b);
            onlyPy.removeAll(b);
            onlyJava.removeAll(a);
            System.out.printf(Locale.ROOT, "%s: %d files (Python %d); only Python: %s; only Java: %s%n",
                folder, b.size(), a.size(), onlyPy, onlyJava);
            assertEquals(folder + " file list", a, b);
        }
        // JSON numbers
        JSONObject hp = new JSONObject(text(new File(py, "hud/hud.json"))), hj = new JSONObject(text(new File(out, "hud/hud.json")));
        assertEquals(hp.length(), hj.length());
        for (java.util.Iterator<String> it = hp.keys(); it.hasNext(); ) {
            String k = it.next();
            assertEquals("hud.json " + k, hp.getInt(k), hj.getInt(k));
        }
        JSONArray np = new JSONArray(text(new File(py, "navimap/navimaps.json"))), nj = new JSONArray(text(new File(out, "navimap/navimaps.json")));
        assertEquals(np.length(), nj.length());
        for (int i = 0; i < np.length(); i++) {
            JSONObject a = np.getJSONObject(i), b = nj.getJSONObject(i);
            for (String k : new String[] { "id", "width_px", "height_px", "tiles", "tile_2x" }) assertEquals("navimaps.json " + i + " " + k, a.getInt(k), b.getInt(k));
            assertEquals(a.getDouble("px_per_unit"), b.getDouble("px_per_unit"), 0);
            for (int c = 0; c < 2; c++) assertEquals(a.getJSONArray("origin").getDouble(c), b.getJSONArray("origin").getDouble(c), 0);
        }
        // Images: equal sizes; the 1x pictures close in content, the upscaled ones close in coverage.
        double worstMap = 0, worstTile = 0, worstHudCov = 0, sumTile = 0;
        int nTile = 0;
        for (String folder : new String[] { "hud", "navimap" }) {
            for (String f : files(new File(py, folder))) {
                if (!f.endsWith(".png")) continue;
                Bitmap a = read(new File(py, folder + "/" + f)), b = read(new File(out, folder + "/" + f));
                assertEquals(f + " width", a.getWidth(), b.getWidth());
                assertEquals(f + " height", a.getHeight(), b.getHeight());
                double[] d = diff(a, b);
                if (folder.equals("navimap") && !f.contains("_2x/")) {
                    System.out.printf(Locale.ROOT, "  %s: mean diff %.2f, coverage %.4f / %.4f%n", f, d[0], d[1], d[2]);
                    worstMap = Math.max(worstMap, d[0]);
                    assertTrue(f + " differs (mean " + d[0] + ")", d[0] < 2.0);
                } else if (folder.equals("navimap")) {
                    worstTile = Math.max(worstTile, d[0]);
                    sumTile += d[0];
                    nTile++;
                    assertTrue(f + " coverage " + d[1] + " vs " + d[2], Math.abs(d[1] - d[2]) < 0.02);
                } else {
                    System.out.printf(Locale.ROOT, "  hud/%s: %dx%d mean diff %.2f, coverage %.4f / %.4f%n", f, b.getWidth(), b.getHeight(), d[0], d[1], d[2]);
                    worstHudCov = Math.max(worstHudCov, Math.abs(d[1] - d[2]));
                    a.recycle();
                    b.recycle();
                    if (f.equals("atb_uv.png") || f.equals("mapbg.png")) assertEquals(f + " (not upscaled) must be identical", 0.0, d[0], 0);
                    else assertTrue(f + " coverage " + d[1] + " vs " + d[2], Math.abs(d[1] - d[2]) < 0.02);
                }
            }
        }
        System.out.printf(Locale.ROOT, "1x maps: worst mean diff %.2f; 2x tiles: mean %.2f worst %.2f (%d); HUD worst coverage gap %.4f%n",
            worstMap, sumTile / Math.max(nTile, 1), worstTile, nTile, worstHudCov);
    }

    private static void renderScenes(File built) throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        File dst = CompanionAssets.dir(ctx);
        CompanionAssets.deleteTree(dst);
        copyTree(built, dst);
        CompanionAssets.preferGenerated = true;
        try {
            String markers = ";mk=quest:-260.8:461.2,shop:-278.0:444.0,warp:-240.0:412.0,door:-283.0:434.0,inn:-256.0:443.0"
                + ";quest=Talk to potential witnesses.;region=Luxerion;place=North Station Plaza";
            String field = "v=1;mode=1;navi=00010002:-315.030:267.431;worldx=-259.5;worldz=448.3;heading=0.6;camheading=-0.07"
                + ";clock=21:07;day=1" + markers;
            CompanionPreviewTest.render(ctx, "gen_battle", CompanionState.demoBattle(2400), null);
            RuntimeActivity.renderPrefs(ctx).edit().putString(CompanionView.PREF_MAP_MODE, "area").commit();
            CompanionPreviewTest.render(ctx, "gen_map_area", field, null);
            RuntimeActivity.renderPrefs(ctx).edit().putString(CompanionView.PREF_MAP_MODE, "follow").commit();
            CompanionPreviewTest.render(ctx, "gen_map_follow", field, new float[] { -259.5f, 448.3f, 0.6f, -0.07f });
            CompanionPreviewTest.render(ctx, "gen_world", "v=1;mode=1;clock=08:30;day=3", null);
        } finally {
            CompanionAssets.preferGenerated = false;
        }
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else {
            Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
