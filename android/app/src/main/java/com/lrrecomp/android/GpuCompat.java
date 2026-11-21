package com.lrrecomp.android;

import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CTRL -> GPU compatibility (testing): renderer paths a player can switch off one at a time when the
 * picture is wrong or the GPU hangs (public issue #4: Adreno 650 with missing text, garbled textures and
 * VK_ERROR_DEVICE_LOST; the sister DS2 project's #8 / #11 on Adreno 650 / 660 show the same family). All
 * are off by default and read at the next start. The renderer settings go into the environment as
 * REX_&lt;CVAR&gt; (unknown names are ignored there, and a debug launch's "cvars" extra still outranks
 * them); the Turnip ones into TU_DEBUG together with the device's own flags.
 */
final class GpuCompat {
    static final String KEY_SHORTCUTS = "compat_shortcuts";
    static final String KEY_SHADERS = "compat_shaders";
    static final String KEY_GPL = "compat_gpl";
    static final String KEY_DYNAMIC_RENDERING = "compat_dynrender";
    static final String KEY_TURNIP_MODE = "compat_tu_mode";
    static final String KEY_TURNIP_NO_UBWC = "compat_tu_noubwc";
    /** "Stretch to fill wide screens" (Image section, public issue #6): the presenter's letterbox off. */
    static final String KEY_STRETCH = "stretch";

    static final String MODE_AUTO = "";
    static final String MODE_SYSMEM = "sysmem";
    static final String MODE_GMEM = "gmem";

    private GpuCompat() {}

    /** The player's choices, read once per start. */
    static final class Choices {
        final boolean shortcutsOff, shadersOff, gplOff, dynamicRenderingOff, turnipNoUbwc, stretch;
        final String turnipMode;

        Choices(boolean shortcutsOff, boolean shadersOff, boolean gplOff, boolean dynamicRenderingOff,
                String turnipMode, boolean turnipNoUbwc, boolean stretch) {
            this.shortcutsOff = shortcutsOff;
            this.shadersOff = shadersOff;
            this.gplOff = gplOff;
            this.dynamicRenderingOff = dynamicRenderingOff;
            this.turnipMode = MODE_SYSMEM.equals(turnipMode) || MODE_GMEM.equals(turnipMode) ? turnipMode : MODE_AUTO;
            this.turnipNoUbwc = turnipNoUbwc;
            this.stretch = stretch;
        }

        static Choices from(SharedPreferences prefs) {
            return new Choices(prefs.getBoolean(KEY_SHORTCUTS, false), prefs.getBoolean(KEY_SHADERS, false),
                prefs.getBoolean(KEY_GPL, false), prefs.getBoolean(KEY_DYNAMIC_RENDERING, false),
                prefs.getString(KEY_TURNIP_MODE, MODE_AUTO), prefs.getBoolean(KEY_TURNIP_NO_UBWC, false),
                prefs.getBoolean(KEY_STRETCH, false));
        }

        /** One line for the log zip's device summary, e.g. "shortcuts_off,tu=sysmem" ("none" if all default). */
        String summary() {
            StringBuilder s = new StringBuilder();
            if (shortcutsOff) append(s, "shortcuts_off");
            if (shadersOff) append(s, "shader_paths_off");
            if (gplOff) append(s, "pipeline_libraries_off");
            if (dynamicRenderingOff) append(s, "dynamic_rendering_off");
            if (!turnipMode.isEmpty()) append(s, "tu=" + turnipMode);
            if (turnipNoUbwc) append(s, "tu_noubwc");
            if (stretch) append(s, "stretch");
            return s.length() == 0 ? "none" : s.toString();
        }
    }

    /**
     * Cvar values (name -> value) for the chosen options; the caller sets each as REX_&lt;NAME&gt;.
     * Every cvar here exists in the SDK fork (tests/host checks the names against its sources).
     */
    static Map<String, String> cvars(Choices choices) {
        Map<String, String> cvars = new LinkedHashMap<>();
        if (choices.shortcutsOff) {
            // Render-target and resolve shortcuts this port added (upstream Xenia does the plain copies),
            // and the memexport barrier merging (one barrier per memexport draw again).
            cvars.put("direct_host_resolve", "false");
            cvars.put("vulkan_resolve_feed_textures", "false");
            cvars.put("vulkan_resolve_feed_lazy_dump", "false");
            cvars.put("gpu_skip_overwritten_color_transfers", "false");
            cvars.put("gpu_constant_transfers_as_clears", "false");
            cvars.put("vulkan_memexport_coalesce_barriers", "false");
        }
        if (choices.shadersOff) {
            // Shader translation changes this port added; the plain forms the driver's compiler sees elsewhere.
            // Changing any of these rebuilds the shaders once.
            cvars.put("spirv_texture_sign_specialization", "false");
            cvars.put("spirv_texture_sign_branch", "false");
            cvars.put("spirv_structured_forward_jumps", "false");
            cvars.put("vulkan_texture_fetch_implicit_lod", "false");
            cvars.put("vulkan_shared_memory_texel_buffer", "false");
        }
        if (choices.gplOff) {
            cvars.put("vulkan_pipeline_libraries", "false");
            cvars.put("vulkan_precompile_vertex_libraries", "false");
        }
        if (choices.dynamicRenderingOff) cvars.put("vulkan_dynamic_rendering", "false");
        if (choices.stretch) cvars.put("present_letterbox", "false");
        return cvars;
    }

    /** REX_&lt;CVAR&gt; environment variable name of a cvar. */
    static String environmentName(String cvar) {
        return "REX_" + cvar.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * TU_DEBUG for this start ("" = leave it unset): the device's own flags (syncdraw on Adreno 650,
     * sysmem on Adreno 710-722, see GpuDriverStore) plus the player's Turnip choices. A rendering mode
     * the player picked replaces the device's sysmem.
     */
    static String turnipDebug(Choices choices, boolean deviceSyncDraw, boolean deviceSysmem) {
        StringBuilder flags = new StringBuilder();
        if (deviceSyncDraw) append(flags, "syncdraw");
        if (!choices.turnipMode.isEmpty()) {
            append(flags, choices.turnipMode);
        } else if (deviceSysmem) {
            append(flags, MODE_SYSMEM);
        }
        if (choices.turnipNoUbwc) append(flags, "noubwc");
        return flags.toString();
    }

    private static void append(StringBuilder list, String item) {
        if (list.length() > 0) list.append(',');
        list.append(item);
    }
}
