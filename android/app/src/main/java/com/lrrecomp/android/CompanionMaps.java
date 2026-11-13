package com.lrrecomp.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The game's area maps ("navimaps"), built from the player's game files at APK build time
 * (tools/lr_navimap.py via build_android.sh stage) into assets/navimap/, or on the device
 * (CompanionAssets, same files under <external files>/companion/navimap/): navimaps.json (id, world
 * origin X/Z, size in pixels, 2 px per world unit) and navimap_NN.png (land and edges on
 * transparency). The game streams a map in 512 px tiles; each tile's world origin (hooked load,
 * "navi=" in the native state) lies inside exactly one map, which is the one to show.
 */
final class CompanionMaps {
    private static final String TAG = "lrrecomp";

    static final class NaviMap {
        int id;
        float originX, originZ;
        int width, height;
        float pxPerUnit = 2f;
        /** Original map pixels per 2x tile (navimap_NN_2x/<row>_<col>.png, Real-ESRGAN), 0 = none. */
        int tile2x;

        boolean contains(float x, float z) {
            float u = (x - originX) * pxPerUnit, v = (z - originZ) * pxPerUnit;
            return u >= 0 && v >= 0 && u < width && v < height;
        }
    }

    private final Context context;
    private NaviMap[] maps;
    private int mapsGeneration;
    private int bitmapId = -1;
    private Bitmap bitmap;
    private int bitmapSample = 1;

    CompanionMaps(Context context) {
        this.context = context;
    }

    /** The map that contains a streamed tile's origin, preferring the large region maps (1-4). */
    /** The map with this navimap id when it holds the point (the streamed tile's own map). */
    NaviMap byId(int id, float x, float z) {
        for (NaviMap m : list()) if (m.id == id && (Float.isNaN(x) || m.contains(x, z))) return m;
        return null;
    }

    NaviMap find(float x, float z) {
        NaviMap best = null;
        for (NaviMap m : list()) {
            if (!m.contains(x, z)) continue;
            if (best == null || (long) m.width * m.height > (long) best.width * best.height) best = m;
        }
        return best;
    }

    /** Region names of the four big maps (1 = Luxerion seen on the Thor; 2-4 from their shapes). */
    static String regionName(int id) {
        switch (id) {
            case 1: return "Luxerion";
            case 2: return "The Wildlands";
            case 3: return "The Dead Dunes";
            case 4: return "Yusnaan";
            default: return "";
        }
    }

    /** The map's picture, decoded at most once per map, downsampled to about maxSide pixels. */
    Bitmap bitmap(NaviMap m, int maxSide) {
        if (m.id == bitmapId) return bitmap;
        bitmap = null;
        bitmapId = m.id;
        String name = String.format(java.util.Locale.ROOT, "navimap_%02d.png", m.id);
        try (InputStream in = CompanionAssets.open(context, "navimap", name)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            int sample = 1;
            while (Math.max(m.width, m.height) / (sample * 2) >= maxSide) sample *= 2;
            o.inSampleSize = sample;
            bitmapSample = sample;
            bitmap = BitmapFactory.decodeStream(in, null, o);
        } catch (IOException | OutOfMemoryError e) {
            Log.w(TAG, "companion: cannot load " + name, e);
        }
        return bitmap;
    }

    int sample() { return bitmapSample; }

    /** 2x tiles near Lightning, decoded on demand (drawing thread); missing = empty or not built. */
    private final java.util.LinkedHashMap<String, Bitmap> tiles = new java.util.LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> eldest) {
            return size() > 40;
        }
    };

    Bitmap tile(NaviMap m, int row, int col) {
        if (m.tile2x <= 0 || row < 0 || col < 0) return null;
        String key = m.id + "/" + row + "_" + col;
        if (tiles.containsKey(key)) return tiles.get(key);
        Bitmap b = null;
        String name = String.format(java.util.Locale.ROOT, "navimap_%02d_2x/%d_%d.png", m.id, row, col);
        try (InputStream in = CompanionAssets.open(context, "navimap", name)) {
            b = BitmapFactory.decodeStream(in);
        } catch (IOException | OutOfMemoryError ignored) {
            // empty tile (not bundled) or no 2x tiles
        }
        tiles.put(key, b);
        return b;
    }

    private NaviMap[] list() {
        // None yet: retried when the on-device build (CompanionAssets) publishes new files.
        if (maps != null && (maps.length > 0 || mapsGeneration == CompanionAssets.generation())) return maps;
        maps = new NaviMap[0];
        mapsGeneration = CompanionAssets.generation();
        try (InputStream in = CompanionAssets.open(context, "navimap", "navimaps.json")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            JSONArray a = new JSONArray(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            NaviMap[] out = new NaviMap[a.length()];
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                NaviMap m = new NaviMap();
                m.id = o.getInt("id");
                JSONArray origin = o.getJSONArray("origin");
                m.originX = (float) origin.getDouble(0);
                m.originZ = (float) origin.getDouble(1);
                m.width = o.getInt("width_px");
                m.height = o.getInt("height_px");
                m.pxPerUnit = (float) o.optDouble("px_per_unit", 2.0);
                m.tile2x = o.optInt("tile_2x", 0);
                out[i] = m;
            }
            maps = out;
        } catch (Exception e) {
            Log.w(TAG, "companion: no area maps yet (APK without them, built on the device from the game files)", e);
        }
        return maps;
    }
}
