package com.lrrecomp.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Test;

/** CTRL -> GPU compatibility (testing) and Stretch to fill (GpuCompat.java). */
public class GpuCompatTest {
    private static GpuCompat.Choices choices(boolean shortcuts, boolean shaders, boolean gpl, boolean dyn,
                                             String mode, boolean noUbwc, boolean stretch) {
        return new GpuCompat.Choices(shortcuts, shaders, gpl, dyn, mode, noUbwc, stretch);
    }

    private static final GpuCompat.Choices NONE = choices(false, false, false, false, "", false, false);

    @Test
    public void defaultsChangeNothing() {
        assertTrue(GpuCompat.cvars(NONE).isEmpty());
        assertEquals("", GpuCompat.turnipDebug(NONE, false, false));
        assertEquals("none", NONE.summary());
    }

    @Test
    public void deviceFlagsAloneAsBefore() {
        // Adreno 650 with Turnip: syncdraw; Adreno 710-722: sysmem (GpuDriverStore).
        assertEquals("syncdraw", GpuCompat.turnipDebug(NONE, true, false));
        assertEquals("sysmem", GpuCompat.turnipDebug(NONE, false, true));
    }

    @Test
    public void turnipChoicesMergeWithTheDevicesFlags() {
        GpuCompat.Choices gmem = choices(false, false, false, false, "gmem", true, false);
        assertEquals("syncdraw,gmem,noubwc", GpuCompat.turnipDebug(gmem, true, false));
        // The player's mode replaces the device's sysmem.
        assertEquals("gmem,noubwc", GpuCompat.turnipDebug(gmem, false, true));
        GpuCompat.Choices noUbwc = choices(false, false, false, false, "", true, false);
        assertEquals("sysmem,noubwc", GpuCompat.turnipDebug(noUbwc, false, true));
        assertEquals("tu_noubwc", noUbwc.summary());
    }

    @Test
    public void unknownModeIsAutomatic() {
        GpuCompat.Choices odd = choices(false, false, false, false, "bogus", false, false);
        assertEquals("", GpuCompat.turnipDebug(odd, false, false));
        assertEquals("none", odd.summary());
    }

    @Test
    public void eachOptionTurnsItsCvarsOff() {
        Map<String, String> shortcuts = GpuCompat.cvars(choices(true, false, false, false, "", false, false));
        assertEquals("false", shortcuts.get("direct_host_resolve"));
        assertEquals("false", shortcuts.get("vulkan_resolve_feed_textures"));
        assertFalse(shortcuts.containsKey("vulkan_pipeline_libraries"));
        Map<String, String> gpl = GpuCompat.cvars(choices(false, false, true, false, "", false, false));
        assertEquals("false", gpl.get("vulkan_pipeline_libraries"));
        assertEquals("false", gpl.get("vulkan_precompile_vertex_libraries"));
        assertEquals("false", GpuCompat.cvars(choices(false, false, false, true, "", false, false))
            .get("vulkan_dynamic_rendering"));
        assertEquals("false", GpuCompat.cvars(choices(false, true, false, false, "", false, false))
            .get("spirv_structured_forward_jumps"));
        assertEquals("false", GpuCompat.cvars(choices(false, false, false, false, "", false, true))
            .get("present_letterbox"));
        assertEquals("REX_PRESENT_LETTERBOX", GpuCompat.environmentName("present_letterbox"));
        assertEquals("shortcuts_off,shader_paths_off,pipeline_libraries_off,dynamic_rendering_off,tu=sysmem,"
            + "tu_noubwc,stretch", choices(true, true, true, true, "sysmem", true, true).summary());
    }

    /** Every cvar the options set must exist in the SDK fork: a misspelt REX_ variable is silently ignored. */
    @Test
    public void everyCvarExistsInTheSdk() throws Exception {
        Path sdk = Paths.get(System.getProperty("user.dir")).resolve("../../sdk/rexglue-sdk/src").normalize();
        if (!Files.isDirectory(sdk)) sdk = Paths.get("C:/lr/sdk/rexglue-sdk/src");
        org.junit.Assume.assumeTrue("SDK fork sources not present", Files.isDirectory(sdk));
        StringBuilder sources = new StringBuilder();
        try (Stream<Path> files = Files.walk(sdk)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".cpp"))::iterator) {
                sources.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            }
        }
        String all = sources.toString();
        for (String cvar : GpuCompat.cvars(choices(true, true, true, true, "", false, true)).keySet()) {
            assertTrue("cvar " + cvar + " not defined in the SDK", all.contains("REXCVAR_DEFINE_BOOL(" + cvar + ","));
        }
    }
}
