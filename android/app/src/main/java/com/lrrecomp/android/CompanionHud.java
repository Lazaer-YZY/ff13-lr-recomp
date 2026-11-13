package com.lrrecomp.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * The game's own HUD pieces for the bottom screen, decoded from the player's game files at APK
 * build time (tools/companion_hud.py: gui/resident/system.x360.xgr, upscaled 4x with Real-ESRGAN
 * when installed) into assets/hud/, or on the device (CompanionAssets: Lanczos 4x) into
 * <external files>/companion/hud/. The pieces are grey in the game, which tints them when drawing;
 * so does this class. Rectangles are given in the original atlas pixels and scaled by hud.json.
 */
final class CompanionHud {
    private static final String TAG = "lrrecomp";

    // Sub-rectangles in the original atlases (tools: out/dual battle_mock.py).
    static final int[] PLATE = { 0, 71, 124, 99 };       // ws_pnl01: name / ability plate frame
    static final int[] TRUSS = { 0, 0, 232, 46 };        // ws_pnl01: bronze truss (divider)
    static final int[] GAUGE = { 1, 5, 629, 75 };        // gp_parts01: gauge housing
    static final int[] DIAL = { 8, 8, 120, 119 };        // btl_cmd_en: command dial
    static final Map<String, int[]> BUTTONS = new HashMap<>();
    static final Map<String, int[]> ELEMENTS = new HashMap<>();
    static {
        BUTTONS.put("B", new int[] { 0, 0, 24, 24 });
        BUTTONS.put("A", new int[] { 25, 0, 49, 24 });
        BUTTONS.put("Y", new int[] { 0, 25, 24, 49 });
        BUTTONS.put("X", new int[] { 25, 25, 49, 49 });
        BUTTONS.put("LB", new int[] { 50, 0, 86, 24 });
        BUTTONS.put("RB", new int[] { 89, 0, 125, 24 });
        ELEMENTS.put("fire", new int[] { 61, 110, 76, 127 });
        ELEMENTS.put("thunder", new int[] { 77, 110, 92, 127 });
        ELEMENTS.put("ice", new int[] { 94, 110, 110, 127 });
        ELEMENTS.put("wind", new int[] { 111, 110, 127, 127 });
    }

    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private final Map<String, Integer> scales = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    final boolean ok;

    /** The generation of the on-device assets this was loaded from (CompanionAssets). */
    private final int generation = CompanionAssets.generation();

    /** h, or a fresh load when h has no pieces and the on-device build published new ones since. */
    static CompanionHud fresh(CompanionHud h, Context context) {
        return h != null && (h.ok || h.generation == CompanionAssets.generation()) ? h : new CompanionHud(context);
    }

    CompanionHud(Context context) {
        boolean loaded = false;
        try (InputStream in = CompanionAssets.open(context, "hud", "hud.json")) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            JSONObject o = new JSONObject(new String(buf.toByteArray(), "UTF-8"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String name = it.next();
                try (InputStream png = CompanionAssets.open(context, "hud", name + ".png")) {
                    Bitmap bm = BitmapFactory.decodeStream(png);
                    if (bm != null) {
                        bitmaps.put(name, bm);
                        scales.put(name, o.getInt(name));
                    }
                }
            }
            loaded = bitmaps.containsKey("ws_pnl01") && bitmaps.containsKey("gp_parts01")
                && bitmaps.containsKey("button_set01");
        } catch (Exception e) {
            Log.w(TAG, "companion: no HUD pieces yet (APK without them, built on the device from the game files)", e);
        }
        ok = loaded;
    }

    Bitmap bitmap(String atlas) { return bitmaps.get(atlas); }

    // ---- The game's own fonts: btlnum (metallic battle digits) and btl_number01 (squared battle
    // font, digits and capitals). Glyph rectangles in original atlas pixels (tools: glyph segmentation).
    static final Map<Character, int[]> NUM = new HashMap<>();
    static final Map<Character, int[]> FONT = new HashMap<>();
    static {
        int[][] numRow1 = { {4, 53}, {67, 97}, {116, 165}, {172, 221}, {228, 277}, {283, 333}, {339, 389}, {396, 445}, {451, 500} };
        for (int i = 0; i < 9; i++) NUM.put((char) ('0' + i), new int[] { numRow1[i][0], 2, numRow1[i][1], 42 });
        NUM.put('9', new int[] { 4, 47, 52, 86 });
        NUM.put('/', new int[] { 62, 47, 106, 86 });
        NUM.put('%', new int[] { 116, 47, 165, 86 });
        String r1 = "023456789AB", r2 = "CDEFGHIJKLMN", r3 = "OPQRSTUVWXY";
        int[][] x1 = { {1, 44}, {67, 109}, {110, 152}, {153, 195}, {196, 238}, {239, 282}, {283, 325}, {326, 368}, {370, 412}, {413, 463}, {464, 507} };
        int[][] x2 = { {1, 44}, {45, 88}, {89, 131}, {132, 175}, {176, 218}, {219, 262}, {263, 275}, {276, 318}, {319, 361}, {362, 404}, {405, 450}, {451, 493} };
        int[][] x3 = { {1, 44}, {45, 88}, {89, 132}, {133, 175}, {176, 218}, {219, 261}, {262, 305}, {306, 349}, {350, 394}, {395, 437}, {438, 481} };
        for (int i = 0; i < r1.length(); i++) FONT.put(r1.charAt(i), new int[] { x1[i][0], 1, x1[i][1], 31 });
        for (int i = 0; i < r2.length(); i++) FONT.put(r2.charAt(i), new int[] { x2[i][0], 32, x2[i][1], 62 });
        for (int i = 0; i < r3.length(); i++) FONT.put(r3.charAt(i), new int[] { x3[i][0], 63, x3[i][1], 93 });
        String r4 = "Z%!?+-x/.':;";
        int[][] x4 = { {1, 43}, {44, 87}, {88, 100}, {101, 143}, {144, 179}, {180, 207}, {210, 238}, {241, 272}, {273, 284}, {285, 297}, {298, 310}, {311, 322} };
        for (int i = 0; i < r4.length(); i++) FONT.put(r4.charAt(i), new int[] { x4[i][0], 94, x4[i][1], 124 });
        FONT.put('1', new int[] { 476, 94, 497, 124 });
    }

    /** Width of v drawn with a game font at cap height h (spaces = 0.45 h). */
    float textWidth(boolean digits, String v, float h) {
        Map<Character, int[]> g = digits ? NUM : FONT;
        float w = 0;
        for (int i = 0; i < v.length(); i++) {
            int[] r = g.get(Character.toUpperCase(v.charAt(i)));
            if (r == null) { w += h * 0.45f; continue; }
            w += h * (r[2] - r[0]) / (float) (r[3] - r[1]) + h * (digits ? 0.04f : 0.08f);
        }
        return w;
    }

    /**
     * Draws v in the game's own font: digits = btlnum (the HUD's big numbers), else btl_number01
     * (labels). (x, y) = left/centre/right per align (-1/0/1) and the vertical centre; h = cap height;
     * rgb tints the white glyphs as the HUD does (0xFFFFFF = untinted). False when the atlas is missing.
     */
    boolean text(Canvas c, boolean digits, String v, float x, float y, float h, int rgb, int align) {
        String atlas = digits ? "btlnum" : "btl_number01";
        if (!bitmaps.containsKey(atlas)) return false;
        Map<Character, int[]> g = digits ? NUM : FONT;
        float w = textWidth(digits, v, h);
        float cx = align < 0 ? x : align > 0 ? x - w : x - w / 2f;
        for (int i = 0; i < v.length(); i++) {
            int[] r = g.get(Character.toUpperCase(v.charAt(i)));
            if (r == null) { cx += h * 0.45f; continue; }
            float gw = h * (r[2] - r[0]) / (float) (r[3] - r[1]);
            draw(c, atlas, r, cx, y - h / 2f, gw, h, rgb, 255);
            cx += gw + h * (digits ? 0.04f : 0.08f);
        }
        return true;
    }

    /** Minimap icons per marker type: atlas name and rectangle (filled from the minimap's own icons). */
    static final Map<String, String> MARKER_ATLAS = new HashMap<>();
    static final Map<String, int[]> MARKER_RECT = new HashMap<>();
    /** Icons the game tints (the quest "!" is white in the atlas, cyan on the minimap). */
    static final Map<String, Integer> MARKER_TINT = new HashMap<>();
    static {
        // docs/dual_screen_map_re.md "Markers": types matched against the minimap on the Thor.
        MARKER_ATLAS.put("quest", "naviicon");        MARKER_RECT.put("quest", new int[] { 14, 0, 32, 18 });
        MARKER_TINT.put("quest", 0x5FE8F0);
        MARKER_ATLAS.put("mainquest", "naviicon");    MARKER_RECT.put("mainquest", new int[] { 14, 0, 32, 18 });
        MARKER_TINT.put("mainquest", 0xFFC83C);
        MARKER_ATLAS.put("shop", "weiss_navi_icon");  MARKER_RECT.put("shop", new int[] { 23, 1, 43, 25 });
        MARKER_ATLAS.put("inn", "weiss_navi_icon");   MARKER_RECT.put("inn", new int[] { 27, 27, 51, 46 });
        MARKER_ATLAS.put("door", "weiss_navi_icon");  MARKER_RECT.put("door", new int[] { 1, 27, 25, 47 });
        MARKER_ATLAS.put("warp", "weiss_navi_icon");  MARKER_RECT.put("warp", new int[] { 1, 1, 21, 25 });
    }

    /** Draws the game's icon for a marker type; false when there is none (the caller draws a badge). */
    boolean drawMarker(Canvas c, String type, float x, float y, float size) {
        String atlas = MARKER_ATLAS.get(type);
        int[] r = MARKER_RECT.get(type);
        if (atlas == null || r == null || !bitmaps.containsKey(atlas)) return false;
        float h = size * (r[3] - r[1]) / (float) (r[2] - r[0]);
        Integer tint = MARKER_TINT.get(type);
        if (tint != null) draw(c, atlas, r, x, y, size, h, tint, 255);
        else drawPlain(c, atlas, r, x, y, size, h, 255);
        return true;
    }

    private boolean source(String atlas, int[] r) {
        Bitmap b = bitmaps.get(atlas);
        if (b == null || r == null) return false;
        int k = scales.get(atlas);
        src.set(r[0] * k, r[1] * k, r[2] * k, r[3] * k);
        return true;
    }

    /** Multiplies the grey piece by rgb (the game's tint); alpha 0-255. */
    private void tint(int rgb, int alpha) {
        paint.setColorFilter(new LightingColorFilter(rgb & 0xFFFFFF, 0));
        paint.setAlpha(alpha);
    }

    void draw(Canvas c, String atlas, int[] r, float x, float y, float w, float h, int rgb, int alpha) {
        if (!source(atlas, r)) return;
        tint(rgb, alpha);
        dst.set(x, y, x + w, y + h);
        c.drawBitmap(bitmaps.get(atlas), src, dst, paint);
    }

    /** Untinted (the controller buttons and icons keep their colours). */
    void drawPlain(Canvas c, String atlas, int[] r, float x, float y, float w, float h, int alpha) {
        if (!source(atlas, r)) return;
        paint.setColorFilter(null);
        paint.setAlpha(alpha);
        dst.set(x, y, x + w, y + h);
        c.drawBitmap(bitmaps.get(atlas), src, dst, paint);
    }

    /** Stretches a piece horizontally, keeping `edge` original pixels at both ends (3-slice). */
    void slice3(Canvas c, String atlas, int[] r, int edge, float x, float y, float w, float h, int rgb, int alpha) {
        if (!source(atlas, r)) return;
        Bitmap b = bitmaps.get(atlas);
        int k = scales.get(atlas);
        tint(rgb, alpha);
        float cap = edge * h / (r[3] - r[1]);
        int e = edge * k;
        Rect s = new Rect(src);
        s.right = src.left + e;
        dst.set(x, y, x + cap, y + h);
        c.drawBitmap(b, s, dst, paint);
        s.left = src.left + e;
        s.right = src.right - e;
        dst.set(x + cap, y, x + w - cap, y + h);
        c.drawBitmap(b, s, dst, paint);
        s.left = src.right - e;
        s.right = src.right;
        dst.set(x + w - cap, y, x + w, y + h);
        c.drawBitmap(b, s, dst, paint);
    }
}
