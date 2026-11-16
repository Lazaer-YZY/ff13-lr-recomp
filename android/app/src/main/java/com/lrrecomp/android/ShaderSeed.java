package com.lrrecomp.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Seeds the game's shader cache from the APK (assets/shader_seed, packed by
 * tools/build_release_apk.sh from a cache collected by playing through the game): the shaders
 * (5351081B.xsh), the shader + render state combinations (5351081B.fbo.vk.xpso) and the pixel
 * shader metadata (5351081B.psmeta). With them, the first start compiles every shader the game
 * uses at the title, behind the shader screen (LrPrecompileNotice), instead of on first sight: on
 * a fresh install the title drew garbled, menus came up as white boxes until their shaders were
 * built, and every new area stuttered. The driver's own cache (vk.pipelinecache) is never
 * bundled: it only fits one GPU and driver build.
 *
 * Copied before the game starts when the device's cache holds fewer shaders than the bundled one
 * (a fresh install, or one that ran only briefly), once per bundled seed (shader_seed.stamp); a
 * bigger cache from the player's own play is kept. Private builds bundle no seed.
 */
final class ShaderSeed {
    private static final String TAG = "LRShaderSeed";
    private static final String DIR = "shader_seed";
    private static final String XSH = "5351081B.xsh";
    /** The shaders last: an interrupted copy leaves the old, smaller .xsh, so it runs again. */
    private static final String[] FILES = { "5351081B.psmeta", "5351081B.fbo.vk.xpso", XSH };

    private ShaderSeed() {}

    /**
     * {@code cacheRoot} is the runtime's --cache_root; the cache is in shaders/shareable. True when
     * the seed was copied in: this start loads and compiles it (BootOverlay says so).
     */
    static boolean install(Context context, File cacheRoot) {
        AssetManager assets = context.getAssets();
        // assets/shader_seed/stamp.txt: "<sha256 prefix of the .xsh> <its size in bytes>".
        String stamp;
        try (InputStream in = assets.open(DIR + "/stamp.txt")) {
            stamp = new String(readAll(in), StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            return false;  // No seed in this build.
        }
        File dir = new File(cacheRoot, "shaders/shareable");
        File stampFile = new File(dir, "shader_seed.stamp");
        try {
            if (stampFile.isFile()
                    && stamp.equals(new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.US_ASCII).trim()))
                return false;
            String[] parts = stamp.split(" ");
            long seedSize = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            File xsh = new File(dir, XSH);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            final long had = xsh.length();
            boolean seeded = false;
            if (had < seedSize) {
                long start = System.nanoTime();
                for (String name : FILES) copy(assets, DIR + "/" + name, new File(dir, name));
                seeded = true;
                Log.i(TAG, "shader cache seeded from the APK (" + seedSize + " bytes of shaders, had "
                    + had + ") in " + (System.nanoTime() - start) / 1000000 + " ms");
            } else {
                Log.i(TAG, "shader cache kept: " + had + " bytes of shaders, seed " + seedSize);
            }
            try (OutputStream out = new FileOutputStream(stampFile)) {
                out.write((stamp + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            return seeded;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "shader cache not seeded", e);
            return false;
        }
    }

    /** Through a temporary file, so a cut-short copy never leaves a truncated cache file. */
    private static void copy(AssetManager assets, String asset, File target) throws IOException {
        File tmp = new File(target.getPath() + ".seed");
        try (InputStream in = assets.open(asset); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[1 << 20];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot replace " + target);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
