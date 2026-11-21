package com.lrrecomp.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Assume;
import org.junit.Test;

/**
 * The GPU driver page on Adreno recommends the bundled Turnip for every model (public issue #4:
 * the page used to show Eden's per-model pick, an untested build).
 */
public class GpuDriverCatalogTest {
    @Test
    public void adrenoModelNumbers() {
        assertEquals(650, GpuDriverCatalog.adrenoModel("Adreno (TM) 650"));
        assertEquals(750, GpuDriverCatalog.adrenoModel("Adreno750v2"));
        assertEquals(0, GpuDriverCatalog.adrenoModel("Mali-G715"));
        assertEquals(0, GpuDriverCatalog.adrenoModel(null));
    }

    @Test
    public void introductionRecommendsTheBundledDriverOnEveryModel() {
        String[] models = {"Adreno (TM) 618", "Adreno (TM) 650", "Adreno (TM) 660", "Adreno (TM) 710",
                           "Adreno (TM) 740", "Adreno (TM) 830", null};
        for (String model : models) {
            String text = GpuDriverCatalog.adrenoIntroduction(model);
            assertTrue(text, text.startsWith("GPU: " + (model != null ? model : "unknown") + "\n"));
            assertTrue(text, text.contains("Recommended: " + GpuDriverStore.BUNDLED_TURNIP_NAME + " (bundled)"));
            assertFalse(text, text.contains("Eden recommends"));
            assertFalse(text, text.contains("Mr. Purple"));
            assertFalse(text, text.contains("KIMCHI"));
        }
    }

    /** The name on the page is the one the installed list shows (the asset's meta.json "name"). */
    @Test
    public void bundledNameMatchesThePackage() throws IOException {
        File zip = new File("src/main/assets/" + GpuDriverStore.BUNDLED_TURNIP_ASSET);
        Assume.assumeTrue("bundled driver not fetched (tools/build_android.sh fetches it)", zip.isFile());
        String meta = null;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.getName().endsWith(GpuDriverStore.META_FILE)) {
                    meta = read(in);
                    break;
                }
            }
        }
        assertTrue("no meta.json in " + zip, meta != null);
        Matcher name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(meta);
        assertTrue(meta, name.find());
        assertEquals(name.group(1), GpuDriverStore.BUNDLED_TURNIP_NAME);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
