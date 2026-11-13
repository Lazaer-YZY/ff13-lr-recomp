package com.lrrecomp.android;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The bottom screen's area maps and HUD pieces, built on the device from the player's own game
 * files (an APK built without the game files carries none). Port of tools/lr_navimap.py and
 * tools/companion_hud.py (which private builds still run at APK build time: when the APK has
 * assets/navimap or assets/hud, those win and nothing is built).
 *
 * Output: <external files>/companion/{navimap,hud}/ (same files and JSON as the Python tools) and
 * companion/.stamp (VERSION, game folder, default.xex size). Built once, in a low-priority
 * background thread started by the companion screen (CompanionScreen.show -> ensure); until it is
 * done the companion draws without maps / HUD pieces, and picks them up when generation() changes.
 *
 * Without Real-ESRGAN: the 2x map tiles upsample the game's land/edge field with Lanczos and
 * flatten it (map look C: the thresholds give clean flat geometry); HUD atlases are scaled 4x
 * (wmap 2x) with Lanczos on premultiplied alpha plus an unsharp mask. The 1x maps and the 1x HUD
 * pieces (atb_uv, mapbg) match the Python output (same decode, same Lanczos coefficients).
 */
final class CompanionAssets {
    private static final String TAG = "lrrecomp";
    /** Bump when the output changes (forces a rebuild on devices that have the old one). */
    static final String VERSION = "companion-assets-1";
    static final String STAMP = ".stamp";

    private static volatile int generation;
    private static boolean started;
    /** Tests: read the generated folder even when the APK has assets. */
    static volatile boolean preferGenerated;
    private static final Map<String, Boolean> apkHas = new HashMap<>();

    private CompanionAssets() {}

    /** Bumped whenever a new set of generated files is published. */
    static int generation() { return generation; }

    static File dir(Context context) {
        File ext = context.getExternalFilesDir(null);
        return new File(ext != null ? ext : context.getFilesDir(), "companion");
    }

    private static boolean apkHas(Context context, String folder) {
        synchronized (apkHas) {
            Boolean b = apkHas.get(folder);
            if (b == null) {
                String probe = folder + "/" + (folder.equals("hud") ? "hud.json" : "navimaps.json");
                try (InputStream in = context.getAssets().open(probe)) {
                    b = true;
                } catch (IOException e) {
                    b = false;
                }
                apkHas.put(folder, b);
            }
            return b;
        }
    }

    /** A companion file: from the APK when it bundles that folder, else from the generated one. */
    static InputStream open(Context context, String folder, String name) throws IOException {
        if (!preferGenerated && apkHas(context, folder)) return context.getAssets().open(folder + "/" + name);
        return new FileInputStream(new File(new File(dir(context), folder), name));
    }

    static String stampFor(File game) {
        return VERSION + "\n" + game.getAbsolutePath() + "\n" + new File(game, "default.xex").length() + "\n";
    }

    static String readText(File f) {
        try {
            return new String(LrArchive.readFile(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Starts the one-time build when the game folder is valid and the stamp is missing or old. */
    static synchronized void ensure(Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        if (apkHas(app, "navimap") && apkHas(app, "hud")) return;
        final File game = GameStorage.gameDir(app);
        if (!GameStorage.hasDefaultXex(game) || !new File(game, "weiss_data").isDirectory()) return;
        final File out = dir(app);
        final String stamp = stampFor(game);
        if (stamp.equals(readText(new File(out, STAMP)))) return;
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);
                try {
                    Log.i(TAG, "companion assets: thread " + RuntimeActivity.nativeCompanionPinLittleCores());
                } catch (Throwable ignored) {
                    // native library not loaded: stay where the scheduler puts us
                }
                Thread.sleep(15000);  // let the game boot read its files first
                long t0 = System.nanoTime();
                build(game, out, stamp, s -> Log.i(TAG, "companion assets: " + s));
                Log.i(TAG, String.format(Locale.ROOT, "companion assets: built in %.1f s", (System.nanoTime() - t0) / 1e9));
            } catch (Throwable e) {
                Log.w(TAG, "companion assets: build failed (the bottom screen stays without maps)", e);
            }
        }, "LR companion assets");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    // ---- build ---------------------------------------------------------------------------

    /** Builds hud/ then navimap/ under out (each published by a rename when complete), then the stamp. */
    static void build(File game, File out, String stamp, Consumer<String> log) throws IOException {
        File work = new File(out, ".work");
        deleteTree(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new IOException("cannot create " + work);
        new File(out, STAMP).delete();
        long t0 = System.nanoTime();
        LrArchive arch = LrArchive.system(game);
        log.accept(String.format(Locale.ROOT, "archive index %d entries, %.1f s", arch.index.size(), (System.nanoTime() - t0) / 1e9));
        t0 = System.nanoTime();
        buildHud(arch, new File(work, "hud"), log);
        publish(work, out, "hud");
        log.accept(String.format(Locale.ROOT, "hud %.1f s", (System.nanoTime() - t0) / 1e9));
        t0 = System.nanoTime();
        arch.addZone0100(game);
        buildNavimaps(arch, new File(work, "navimap"), log);
        publish(work, out, "navimap");
        log.accept(String.format(Locale.ROOT, "navimap %.1f s", (System.nanoTime() - t0) / 1e9));
        deleteTree(work);
        if (stamp != null) writeText(new File(out, STAMP), stamp);
    }

    private static void publish(File work, File out, String name) throws IOException {
        File dst = new File(out, name);
        deleteTree(dst);
        if (!new File(work, name).renameTo(dst)) throw new IOException("cannot publish " + dst);
        generation++;
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static void writeText(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- HUD (tools/companion_hud.py) ------------------------------------------------------

    static final String[] PIECES = { "ws_pnl01", "gp_parts01", "button_set01", "battle_parts01", "btl_cmd_en", "atb_uv",
        "syswin_bg01", "sys_clk", "sys_clk_waku", "libra",
        "nvmap_waku", "nvmap_waku2", "nvmap_sabi", "naviicon", "weiss_navi_icon", "nmapgrid",
        "btlnum", "btl_number01", "FF13_font" };
    /** Unsharp mask after the Lanczos upscale (Real-ESRGAN + a light unsharp mask in the Python tool). */
    static final float SHARPEN_SIGMA_PER_SCALE = 0.75f, SHARPEN_AMOUNT = 1.3f;
    /** Piece -> scale; atb_uv is a cloudy fill texture (tiled and blurred anyway): 1x. */
    private static int pieceScale(String p) { return p.equals("atb_uv") ? 1 : 4; }

    /** Decoded textures (RGBA) of every .xgr whose path contains sub, by entry name (later wins). */
    static Map<String, Object[]> decodeXgr(LrArchive arch, String sub) throws IOException {
        Map<String, Object[]> out = new HashMap<>();
        for (String path : new java.util.TreeSet<>(arch.index.keySet())) {
            if (!path.endsWith(".xgr") || !path.contains(sub)) continue;
            byte[] xgr = arch.read(path);
            String ipath = path.substring(0, path.length() - 4) + ".imgb";
            byte[] imgb = arch.has(ipath) ? arch.read(ipath) : new byte[0];
            for (GuiTex.Texture t : GuiTex.textures(xgr, imgb)) {
                byte[] px = GuiTex.decode(t.fmt, t.width, t.height, t.data, t.type != 0);
                if (px != null) out.put(t.name, new Object[] { px, t.width, t.height });
            }
        }
        return out;
    }

    static void buildHud(LrArchive arch, File dir, Consumer<String> log) throws IOException {
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        Map<String, Integer> scales = new TreeMap<>();
        Map<String, Object[]> sys = decodeXgr(arch, "gui/resident/system.x360");
        Map<String, Object[]> mapScreen = new HashMap<>();
        Object[] mapbg = decodeXgr(arch, "gui/resident/shop/shop03.").get("mapbg");
        Object[] wmap = decodeXgr(arch, "gui/resident/shop/shop02.").get("wmap");
        if (mapbg != null) mapScreen.put("mapbg", mapbg);
        if (wmap != null) mapScreen.put("wmap", wmap);
        Map<String, Integer> want = new LinkedHashMap<>();
        want.put("mapbg", 1);
        want.put("wmap", 2);
        for (String p : PIECES) want.put(p, pieceScale(p));
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            String name = e.getKey();
            Object[] t = mapScreen.containsKey(name) ? mapScreen.get(name) : sys.get(name);
            if (t == null) {
                log.accept(name + " not found in the game files");
                continue;
            }
            byte[] px = (byte[]) t[0];
            int w = (Integer) t[1], h = (Integer) t[2], k = e.getValue();
            File f = new File(dir, name + ".png");
            if (k == 1) ImageOps.writePng(f, px, w, h);
            else ImageOps.scaleRgbaToPng(px, w, h, w * k, h * k, f, SHARPEN_SIGMA_PER_SCALE * k, SHARPEN_AMOUNT);
            scales.put(name, k);
        }
        StringBuilder j = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Integer> e : scales.entrySet()) {
            j.append(" \"").append(e.getKey()).append("\": ").append(e.getValue()).append(++i < scales.size() ? ",\n" : "\n");
        }
        j.append("}");
        writeText(new File(dir, "hud.json"), j.toString());
        log.accept(scales.size() + " HUD pieces");
    }

    // ---- area maps (tools/lr_navimap.py) ---------------------------------------------------

    static final String SCENE = "scene/lay/scene00100/bin/";

    /** GENP tree: 'GENP', version byte, then tagged values. Keys are 32-bit name hashes (hex). */
    static Object genp(byte[] d) {
        int[] p = { 5 };
        return genpVal(d, p);
    }

    private static Object genpVal(byte[] d, int[] p) {
        int t = d[p[0]++] & 0xFF;
        if (t < 0x50) return t;
        if (t == 0x90) {
            int bits = GuiTex.be32(d, p[0]);
            p[0] += 4;
            return (double) Float.intBitsToFloat(bits);
        }
        if (t == 0x8a) {
            p[0] += 2;
            return GuiTex.be16(d, p[0] - 2);
        }
        if (t == 0x50 || t == 0x74 || t == 0x83 || t == 0x84 || t == 0xba || t == 0xdf) {
            p[0] += 1;
            return d[p[0] - 1] & 0xFF;
        }
        if (t >= 0xd0 && t < 0xdf) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i < (t & 15); i++) {
                if ((d[p[0]] & 0xFF) != 0x87) throw new IllegalStateException("GENP: key expected at " + p[0]);
                String k = String.format(Locale.ROOT, "%02x%02x%02x%02x", d[p[0] + 1], d[p[0] + 2], d[p[0] + 3], d[p[0] + 4]);
                p[0] += 5;
                m.put(k, genpVal(d, p));
            }
            return m;
        }
        if (t >= 0xc0 && t <= 0xcf) {
            List<Object> l = new ArrayList<>();
            for (int i = 0; i < (t & 15); i++) l.add(genpVal(d, p));
            return l;
        }
        if (t == 0xa7) {
            int n = d[p[0]++] & 0xFF;
            List<Object> l = new ArrayList<>();
            for (int i = 0; i < n; i++) l.add(genpVal(d, p));
            return l;
        }
        if (t == 0xb7) {
            int n = d[p[0]] & 15;
            p[0] += 1 + n;
            List<Object> l = new ArrayList<>();
            for (int i = p[0] - n; i < p[0]; i++) l.add(d[i] & 0xFF);
            return l;
        }
        throw new IllegalStateException(String.format(Locale.ROOT, "GENP: tag %#x at %#x", t, p[0] - 1));
    }

    static final class MapDef {
        int id, tile;
        double[] origin;
        /** Per row: first tile index, x positions (in 128 px). */
        final List<int[]> rows = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    static List<MapDef> navimaps(Object root) {
        List<MapDef> out = new ArrayList<>();
        for (Object o : (List<Object>) ((Map<String, Object>) root).get("e688ed50")) {
            Map<String, Object> r = (Map<String, Object>) o;
            MapDef m = new MapDef();
            m.id = ((Number) r.get("bf396750")).intValue();
            List<Object> org = (List<Object>) r.get("590acad0");
            m.origin = new double[org.size()];
            for (int i = 0; i < org.size(); i++) {
                m.origin[i] = new BigDecimal(((Number) org.get(i)).doubleValue()).setScale(3, RoundingMode.HALF_EVEN).doubleValue();
            }
            Object tile = r.get("f7c0246a");
            m.tile = tile instanceof Number ? ((Number) tile).intValue() : 0;
            for (Object ro : (List<Object>) r.get("0a8bf4a1")) {
                Map<String, Object> row = (Map<String, Object>) ro;
                List<Object> xs = (List<Object>) row.get("accec0b7");
                int[] a = new int[xs.size() + 1];
                a[0] = ((Number) row.get("8da7fd74")).intValue();
                for (int i = 0; i < xs.size(); i++) a[1 + i] = ((Number) xs.get(i)).intValue();
                m.rows.add(a);
            }
            out.add(m);
        }
        return out;
    }

    // Map look C (the owner, 2026-11-11): one flat land colour, thin flat outlines, soft only at the
    // boundaries. As a lookup on the 8-bit green field: land 60-90, lines 215-235 (smoothstep).
    private static final float[] FILL = { 28, 46, 52 }, LINE = { 150, 190, 196 };
    static final int[][] FLAT = flatLut(215, 235);
    /**
     * The 2x tiles: lines from 200 instead of 215. Real-ESRGAN (the Python tool) drew the outlines a
     * little thicker and rounder than a plain Lanczos upsample; the lower threshold matches its line
     * width best (mean difference to its tiles 0.68 vs 0.89 per channel, tuned on map 1).
     */
    static final int[][] FLAT_2X = flatLut(200, 220);

    private static int[][] flatLut(double lineLo, double lineHi) {
        int[][] lut = new int[256][4];
        for (int g = 0; g < 256; g++) {
            double land = smoothstep(60, 90, g), line = smoothstep(lineLo, lineHi, g);
            for (int c = 0; c < 3; c++) lut[g][c] = (int) (float) (FILL[c] + (LINE[c] - FILL[c]) * (float) line);
            lut[g][3] = (int) (land * 255);
        }
        return lut;
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    static final int TILE = 256;  // 2x tiles: TILE map pixels -> 2 * TILE pixels
    private static final int PAD = 16;

    private static boolean marker(byte[] rgb, int i) {
        return (rgb[i] & 0xFF) > 40 || (rgb[i + 2] & 0xFF) > 40;
    }

    private static int maxGreen(byte[] rgb, int w, int h, int x0, int x1, int y0, int y1) {
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(w, x1);
        y1 = Math.min(h, y1);
        int m = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0, i = (y * w + x0) * 3 + 1; x < x1; x++, i += 3) m = Math.max(m, rgb[i] & 0xFF);
        return m;
    }

    static void buildNavimaps(LrArchive arch, File dir, Consumer<String> log) throws IOException {
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        byte[] xwp = arch.read(SCENE + "scene00100.x360.xwp");
        byte[] geb = null;
        for (GuiTex.WpdEntry e : GuiTex.wpdEntries(xwp)) if (e.name.equals("navimap") && e.ext.equals("geb")) geb = e.body();
        if (geb == null) throw new IOException("navimap.geb not found");
        StringBuilder json = new StringBuilder("[");
        int count = 0;
        for (MapDef m : navimaps(genp(geb))) {
            int maxX = -1, tiles = 0;
            for (int[] row : m.rows) for (int j = 1; j < row.length; j++) { maxX = Math.max(maxX, row[j]); tiles++; }
            if (tiles == 0) continue;
            int w = maxX * 128 + 512, h = m.rows.size() * 512;
            byte[] rgb = new byte[w * h * 3];
            for (int ri = 0; ri < m.rows.size(); ri++) {
                int[] row = m.rows.get(ri);
                for (int j = 1; j < row.length; j++) {
                    String path = SCENE + String.format(Locale.ROOT, "scene00100_%02x_split_%02x.x360.imgb", m.id, row[0] + j - 1);
                    if (!arch.has(path)) continue;
                    byte[] px = GuiTex.decode(24, 512, 512, arch.read(path), true);
                    int x0 = row[j] * 128, y0 = ri * 512;
                    for (int y = 0; y < 512; y++)
                        for (int x = 0; x < 512; x++) {
                            int s = (y * 512 + x) * 4, d = ((y0 + y) * w + x0 + x) * 3;
                            rgb[d] = px[s];
                            rgb[d + 1] = px[s + 1];
                            rgb[d + 2] = px[s + 2];
                        }
                }
            }
            flat1x(rgb, w, h, new File(dir, String.format(Locale.ROOT, "navimap_%02d.png", m.id)));
            int n2 = tiles2x(rgb, w, h, new File(dir, String.format(Locale.ROOT, "navimap_%02d_2x", m.id)));
            json.append(count++ == 0 ? "\n" : ",\n")
                .append(" {\n  \"id\": ").append(m.id)
                .append(",\n  \"origin\": [\n   ").append(num(m.origin[0])).append(",\n   ").append(num(m.origin[1]))
                .append("\n  ],\n  \"width_px\": ").append(w).append(",\n  \"height_px\": ").append(h)
                .append(",\n  \"px_per_unit\": 2.0,\n  \"tiles\": ").append(tiles)
                .append(",\n  \"tile_2x\": ").append(TILE).append("\n }");
            log.accept(String.format(Locale.ROOT, "navimap %2d: %dx%d px, %d tiles, %d 2x tiles", m.id, w, h, tiles, n2));
        }
        json.append("\n]");
        writeText(new File(dir, "navimaps.json"), json.toString());
    }

    private static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.format(Locale.ROOT, "%.1f", v);
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** The overview map at 1x: the field supersampled 4x (Lanczos), made flat, averaged back. */
    static void flat1x(byte[] rgb, int w, int h, File out) throws IOException {
        final int ss = 4, B = 64;
        ImageOps.Coeffs cx = ImageOps.lanczos(w, w * ss), cy = ImageOps.lanczos(h, h * ss);
        float[][] pre = new float[256][4];
        for (int g = 0; g < 256; g++) {
            float a = FLAT[g][3] / 255f;
            for (int c = 0; c < 3; c++) pre[g][c] = FLAT[g][c] * a;
            pre[g][3] = FLAT[g][3];
        }
        byte[] strip = new byte[w * B * 4];
        try (ImageOps.Png png = new ImageOps.Png(out, w, h)) {
            for (int r0 = 0; r0 < h; r0 += B) {
                int r1 = Math.min(h, r0 + B);
                java.util.Arrays.fill(strip, (byte) 0);
                for (int c0 = 0; c0 < w; c0 += B) {
                    int c1 = Math.min(w, c0 + B);
                    if (maxGreen(rgb, w, h, c0 - 3, c1 + 3, r0 - 3, r1 + 3) <= 30) continue;  // stays transparent
                    int fw = (c1 - c0) * ss;
                    byte[] f = ImageOps.resample(rgb, 0, 0, w, h, 3, 1, 1, cx, cy, c0 * ss, c1 * ss, r0 * ss, r1 * ss);
                    for (int y = r0; y < r1; y++)
                        for (int x = c0; x < c1; x++) {
                            float pr = 0, pg = 0, pb = 0, pa = 0;
                            int base = (y - r0) * ss * fw + (x - c0) * ss;
                            for (int sy = 0; sy < ss; sy++)
                                for (int sx = 0; sx < ss; sx++) {
                                    float[] q = pre[f[base + sy * fw + sx] & 0xFF];
                                    pr += q[0];
                                    pg += q[1];
                                    pb += q[2];
                                    pa += q[3];
                                }
                            float n = ss * ss, alpha = pa / n;
                            int o = ((y - r0) * w + x) * 4;
                            if (alpha > 0) {
                                float k = Math.max(alpha / 255f, 1e-6f) * n;
                                strip[o] = (byte) (int) Math.min(255, pr / k);
                                strip[o + 1] = (byte) (int) Math.min(255, pg / k);
                                strip[o + 2] = (byte) (int) Math.min(255, pb / k);
                            }
                            strip[o + 3] = (byte) (int) alpha;
                        }
                }
                for (int y = r0; y < r1; y++)
                    for (int x = 0; x < w; x++) {
                        int i = (y * w + x) * 3;
                        if (!marker(rgb, i)) continue;
                        int o = ((y - r0) * w + x) * 4;
                        strip[o] = rgb[i];
                        strip[o + 1] = rgb[i + 1];
                        strip[o + 2] = rgb[i + 2];
                        strip[o + 3] = (byte) 255;
                    }
                for (int y = r0; y < r1; y++) png.row(strip, (y - r0) * w * 4);
            }
        }
    }

    /**
     * Follow mode shows the maps at ~2x: per TILE x TILE block with land, the field upsampled 2x
     * (Lanczos) and made flat, the coloured marks upsampled on top; navimap_NN_2x/<row>_<col>.png.
     */
    static int tiles2x(byte[] rgb, int w, int h, File dir) throws IOException {
        ImageOps.Coeffs cx = ImageOps.lanczos(w, 2 * w), cy = ImageOps.lanczos(h, 2 * h);
        int n = 0;
        int T2 = 2 * TILE;
        for (int r = 0; r < h; r += TILE)
            for (int c = 0; c < w; c += TILE) {
                if (maxGreen(rgb, w, h, c, c + TILE, r, r + TILE) <= 8) continue;
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                int ox0 = 2 * c, ox1 = Math.min(2 * w, 2 * c + T2), oy0 = 2 * r, oy1 = Math.min(2 * h, 2 * r + T2);
                int ow = ox1 - ox0, oh = oy1 - oy0;
                byte[] f = ImageOps.resample(rgb, 0, 0, w, h, 3, 1, 1, cx, cy, ox0, ox1, oy0, oy1);
                byte[] tile = new byte[T2 * T2 * 4];
                for (int y = 0; y < oh; y++)
                    for (int x = 0; x < ow; x++) {
                        int[] v = FLAT_2X[f[y * ow + x] & 0xFF];
                        int o = (y * T2 + x) * 4;
                        tile[o] = (byte) v[0];
                        tile[o + 1] = (byte) v[1];
                        tile[o + 2] = (byte) v[2];
                        tile[o + 3] = (byte) v[3];
                    }
                // The game's coloured marks (gates, crossings), Lanczos on premultiplied alpha, composited over.
                int x0 = Math.max(0, c - PAD), y0 = Math.max(0, r - PAD);
                int x1 = Math.min(w, c + TILE + PAD), y1 = Math.min(h, r + TILE + PAD);
                int mw = x1 - x0, mh = y1 - y0;
                byte[] marks = null;
                for (int y = y0; y < y1; y++)
                    for (int x = x0; x < x1; x++) {
                        int i = (y * w + x) * 3;
                        if (!marker(rgb, i)) continue;
                        if (marks == null) marks = new byte[mw * mh * 4];
                        int o = ((y - y0) * mw + x - x0) * 4;
                        marks[o] = rgb[i];
                        marks[o + 1] = rgb[i + 1];
                        marks[o + 2] = rgb[i + 2];
                        marks[o + 3] = (byte) 255;
                    }
                if (marks != null) {
                    byte[] m = ImageOps.resample(marks, x0, y0, mw, mh, 4, 0, 4, cx, cy, ox0, ox1, oy0, oy1);
                    ImageOps.unpremultiply(m);
                    for (int y = 0; y < oh; y++)
                        for (int x = 0; x < ow; x++) {
                            int s = (y * ow + x) * 4, o = (y * T2 + x) * 4;
                            float sa = (m[s + 3] & 0xFF) / 255f;
                            if (sa <= 0) continue;
                            float da = (tile[o + 3] & 0xFF) / 255f;
                            float oa = sa + da * (1 - sa);
                            for (int k = 0; k < 3; k++) {
                                float v = ((m[s + k] & 0xFF) * sa + (tile[o + k] & 0xFF) * da * (1 - sa)) / oa;
                                tile[o + k] = (byte) Math.min(255, Math.round(v));
                            }
                            tile[o + 3] = (byte) Math.min(255, Math.round(oa * 255));
                        }
                }
                ImageOps.writePng(new File(dir, (r / TILE) + "_" + (c / TILE) + ".png"), tile, T2, T2);
                n++;
            }
        return n;
    }
}
