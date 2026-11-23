// Adapted from Lost Odyssey Recomp (GPL-3.0).
package com.lrrecomp.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.io.File;
import org.libsdl.app.SDLActivity;

/** Game runtime. The user-supplied game folder holds default.xex + weiss_data/. */
public final class RuntimeActivity extends SDLActivity {
    /** Matches the SDL_SendAndroidMessage command in settings/menu.cpp. */
    private static final int COMMAND_OPEN_GAME_FOLDER = 0x8000;
    /** A start that stays in the foreground this long counts as reached for the driver choice. */
    private static final long BOOT_SETTLED_MS = 15000;
    /**
     * Presentation surface height cap (SDK cvar present_max_height, our lr-android branch): the game
     * renders at 1280x720, so presenting into the 1080p swapchain only costs GPU time and heat.
     * 0 = present at the display's size (A/B: flip to 0 and rebuild).
     */
    private static final int PRESENT_MAX_HEIGHT = 720;

    private TouchControlsView touchControls;
    /** The bottom screen of dual-screen devices (CompanionScreen). */
    private CompanionScreen companion;
    /** Why the renderer could not start with the selected driver; set from native code. */
    private volatile String graphicsFailure;
    private final Handler bootHandler = new Handler(Looper.getMainLooper());
    private volatile BootOverlay bootOverlay;
    private final Runnable bootSettled = () -> {
        GpuDriverStore.clearBootPending(this);
        PlayerLogs.publish(this);
    };

    static native void nativeSetTouchInput(int buttons, int leftTrigger, int rightTrigger,
                                           int leftX, int leftY, int rightX, int rightY);
    static native boolean nativeHasConnectedController();
    /** BootOverlay: 0 not started, 1 running but nothing shown, 2 something on screen, 3 fallback. */
    static native int nativeBootProgress();
    /** Live render options from the CTRL dialog: "fps" = 30 | 60g, "aa" = off | fxaa. */
    static native void nativeSetOption(String name, String value);
    /** Second screen: what the game shows now (src/lr_companion.inc), key=value pairs. */
    static native String nativeCompanionState();
    /** Second screen: presses XInput buttons on controller 1 for ms milliseconds. */
    static native void nativeCompanionPress(int buttons, int ms);
    /** Second screen: pins the calling thread to the little cores; returns what it did (for the log). */
    static native String nativeCompanionPinLittleCores();
    /** Second screen: {x, z, heading, camheading} of Lightning in the field; false = not in the field. */
    static native boolean nativeCompanionPose(float[] out);

    /** Display settings of the CTRL dialog (frame rate, anti-aliasing, upscaling). */
    static SharedPreferences renderPrefs(Context context) {
        return context.getSharedPreferences("render_settings", Context.MODE_PRIVATE);
    }

    /** Frame rate on a fresh install: 60 fps in gameplay for the public build, 30 for the owner's. */
    static final String DEFAULT_FPS = BuildConfig.PUBLIC_DEFAULTS ? "60g" : "30";
    /** Debug logging on a fresh install: off in the public build (~4% CPU), on in the owner's. */
    static final boolean DEFAULT_DEBUG_LOG = !BuildConfig.PUBLIC_DEFAULTS;

    /**
     * 40 fps needs the panel's 120 Hz mode (every third refresh; the runtime's vertical blank runs
     * at 120 Hz then, see LrFrameRateStartup). Requested for the window when "40" is saved. A
     * start that doesn't clear its trial flag within 45 s (crash, hang) puts the setting back to
     * 30 fps at the next start.
     */
    private void applyDisplayModeForFrameRate() {
        SharedPreferences render = renderPrefs(this);
        // Debug launches: --es LR_VBLANK120 1 runs 60 fps on the 120 Hz panel mode and vertical blank
        // (a late frame shows after 25 ms instead of 33; lr_hooks.cpp LrFrameRateStartup).
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && "1".equals(getIntent().getStringExtra("LR_VBLANK120"))) {
            requestDisplayMode120();
            return;
        }
        if (panel120Active(render)) {
            requestDisplayMode120();
            return;
        }
        if (!"40".equals(render.getString("fps", DEFAULT_FPS))) return;
        if (render.getBoolean("fps40_trial", false)) {
            render.edit().putString("fps", "30").putBoolean("fps40_trial", false).apply();
            Toast.makeText(this, R.string.touch_fps_40_fallback, Toast.LENGTH_LONG).show();
            return;
        }
        render.edit().putBoolean("fps40_trial", true).apply();
        bootHandler.postDelayed(() -> renderPrefs(this).edit().putBoolean("fps40_trial", false).apply(), 45000);
        requestDisplayMode120();
    }

    /** "Smoother 60 fps" is on and the frame rate setting is 60 (in gameplay or everywhere). */
    static boolean panel120Active(SharedPreferences render) {
        String fps = render.getString("fps", DEFAULT_FPS);
        return render.getBoolean("panel120", false) && ("60g".equals(fps) || "60".equals(fps));
    }

    /** Asks for the panel's 120 Hz mode at the current resolution (the Thor: 60 and 120 Hz). */
    private void requestDisplayMode120() {
        Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? getDisplay()
                                                                          : getWindowManager().getDefaultDisplay();
        if (display == null) return;
        Display.Mode current = display.getMode();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()
                    && Math.abs(mode.getRefreshRate() - 120f) < 1f) {
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.preferredDisplayModeId = mode.getModeId();
                getWindow().setAttributes(params);
                return;
            }
        }
    }

    /** Saves the display settings; frame rate and anti-aliasing change at once, the upscaler at the next start. */
    void applyRenderSettings(String fps, String aa, String upscale, boolean overlay) {
        renderPrefs(this).edit().putString("fps", fps).putString("aa", aa).putString("upscale", upscale)
            .putBoolean("overlay", overlay).commit();  // on disk before a restart (offerRestart)
        nativeSetOption("fps", fps);
        nativeSetOption("aa", aa);
        nativeSetOption("overlay", overlay ? "1" : "0");
    }

    /**
     * The CTRL settings read only when the game starts (marked * in the dialog): 40 fps and the smoother 60 (the
     * 120 Hz panel mode), the upscaler, stretching, debug logging, the exact shader math and the GPU compatibility
     * switches. The dialog offers a restart when they change, as DS2's does.
     */
    static String nextStartState(SharedPreferences render) {
        return "40".equals(render.getString("fps", DEFAULT_FPS)) + "|" + panel120Active(render)
            + "|" + render.getString("upscale", "bilinear")
            + "|" + render.getBoolean("debug_log", DEFAULT_DEBUG_LOG)
            + "|" + render.getBoolean("exact_shader_math", false)
            + "|" + GpuCompat.Choices.from(render).summary();
    }

    /** After CTRL changed a next-start setting: restart now (progress since the last save is lost) or later. */
    void offerRestart() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.restart_title))
            .setMessage(getString(R.string.restart_message))
            .setPositiveButton(getString(R.string.restart_now), (dialog, which) -> restartGame())
            .setNegativeButton(getString(R.string.restart_later), null)
            .show();
    }

    /** Restarts the game in a new process (CTRL's next-start settings). */
    void restartGame() {
        android.util.Log.i("lrrecomp", "restart from CTRL");
        if (touchControls != null) touchControls.clearTouches();
        GpuDriverStore.clearBootPending(this);  // a chosen restart is not a failed start
        // The pending activity outlives the process; Android starts a new one for it.
        Intent intent = new Intent(this, RuntimeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        Runtime.getRuntime().exit(0);
    }

    /**
     * CTRL's Exit button (as in DS2, the owner, 2026-11-23: "choosing to quit game"): asks first, then ends the game
     * like a restart does, without starting it again. The game has no exit of its own.
     */
    void confirmExit() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.exit_title))
            .setMessage(getString(R.string.exit_message))
            .setPositiveButton(getString(R.string.exit_confirm), (dialog, which) -> exitGame())
            .setNegativeButton(getString(R.string.touch_cancel), null)
            .show();
    }

    private void exitGame() {
        android.util.Log.i("lrrecomp", "exit from CTRL");
        if (touchControls != null) touchControls.clearTouches();
        GpuDriverStore.clearBootPending(this);  // a chosen exit is not a failed start, however soon it came
        finishAndRemoveTask();
        Runtime.getRuntime().exit(0);
    }

    /** CTRL dialog: saves the cheat switches and applies them at once (Cheats.java). */
    void applyCheats(int bits) {
        Cheats.save(renderPrefs(this), bits);
        nativeSetOption("cheats", Cheats.optionValue(bits));
    }

    // Sustained performance mode (on by default; debug extra LR_SUSTAINED=0 turns it off): asks the
    // power HAL for clocks the device can hold indefinitely instead of bursting to peak clocks and
    // then throttling, which on the Thor showed up as temperature spikes and the fan ramping up and
    // down. Only some devices support it; the result goes to logcat (tag lrrecomp).
    private void applySustainedPerformanceMode() {
        boolean want = true;
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            String extra = getIntent().getStringExtra("LR_SUSTAINED");
            if ("0".equals(extra)) want = false;
        }
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean supported = pm != null && pm.isSustainedPerformanceModeSupported();
        if (want && supported) getWindow().setSustainedPerformanceMode(true);
        android.util.Log.i("lrrecomp", "sustained performance mode: " + (supported ? (want ? "on" : "off (LR_SUSTAINED=0)") : "not supported on this device"));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PlayerLogs.installCrashHandler(this);
        super.onCreate(savedInstanceState);
        // Development launches on a test phone that locks between unattended runs (`--es LR_SHOW_WHEN_LOCKED 1`,
        // as the DS2 project does on its Adreno 640 test phone): the game shows over the lock screen like an
        // alarm app; it does not unlock the phone or show anything else.
        if (BuildConfig.DEBUG && android.os.Build.VERSION.SDK_INT >= 27 && getIntent() != null
                && "1".equals(getIntent().getStringExtra("LR_SHOW_WHEN_LOCKED"))) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        applyDisplayModeForFrameRate();
        hideSystemBars();
        applySustainedPerformanceMode();
        // SDLActivity.onCreate posts setWindowStyle(false), which shows the bars
        // again once this method returns; hide after that command has run, and
        // whenever the bars come back later (SDL window-style changes, a swipe).
        bootHandler.post(this::hideSystemBars);
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (view, insets) -> {
            if (insets.isVisible(WindowInsetsCompat.Type.statusBars())
                    || insets.isVisible(WindowInsetsCompat.Type.navigationBars())) {
                if (touchControls != null) touchControls.revealCtrl();
                scheduleHideSystemBars();
            }
            return insets;
        });
        GameStorage.prepare(this);
        GpuDriverStore.markBootPending(this);
        if (mLayout != null && !mBrokenLibraries) {
            // "Loading…" over the black screen at boot, under the touch controls (CTRL stays reachable).
            bootOverlay = new BootOverlay(this);
            mLayout.addView(bootOverlay, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT));
            touchControls = new TouchControlsView(this);
            mLayout.addView(touchControls, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT));
            nativeSetTouchInput(0, 0, 0, 0, 0, 0, 0);
            companion = new CompanionScreen(this);
        }
    }

    /** CTRL dialog: the second screen setting changed (applies at once). */
    void applyCompanionSetting(boolean on) {
        renderPrefs(this).edit().putBoolean(CompanionScreen.PREF, on).apply();
        if (companion != null) companion.update();
    }

    /**
     * Called by the native runtime before its main returns when no Vulkan
     * device the renderer can use was created (video.cpp).
     */
    @SuppressWarnings("unused")
    void reportGraphicsFailure(String reason) {
        graphicsFailure = reason;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && touchControls != null && touchControls.ctrlGone()) {
            // CTRL removed after the start (CTRL option): Back opens Settings, and the key goes nowhere else.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) touchControls.openSettingsFromBack();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP
                && !event.isCanceled() && touchControls != null) touchControls.revealCtrl();
        // Keep SDL's existing Back mapping/consumption, including gesture-generated keys.
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (touchControls != null && touchControls.ctrlGone()) {
            touchControls.openSettingsFromBack();  // the back gesture, which comes without a key event
            return;
        }
        if (touchControls != null) touchControls.revealCtrl();
        // Preserve SDL's fallback behavior on Android versions that call this directly.
        super.onBackPressed();
    }

    /** Opens the GPU driver page over the game (CTRL dialog). */
    void openGpuDriverPage() {
        Intent intent = new Intent(this, GpuDriverActivity.class);
        intent.putExtra(GpuDriverActivity.EXTRA_FROM_GAME, true);
        startActivity(intent);
    }

    @Override
    protected boolean onUnhandledMessage(int command, Object param) {
        if (command == COMMAND_OPEN_GAME_FOLDER) {
            openGameFolderPage();
            return true;
        }
        return super.onUnhandledMessage(command, param);
    }

    /** Opens the game folder page over the game (CTRL dialog or native settings). */
    void openGameFolderPage() {
        Intent intent = new Intent(this, GameFolderActivity.class);
        intent.putExtra(GameFolderActivity.EXTRA_FROM_GAME, true);
        startActivity(intent);
    }

    private static final int REQUEST_EXPORT_LOGS = 0x4c52;  // 'LR'

    /** CTRL dialog's "Save logs": the system file picker, then a zip of lr.log there. */
    void startLogExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, PlayerLogs.exportName(new java.util.Date()));
        try {
            startActivityForResult(intent, REQUEST_EXPORT_LOGS);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.logs_no_picker), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_EXPORT_LOGS) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) exportLogs(data.getData());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void exportLogs(android.net.Uri uri) {
        java.util.List<File> files = PlayerLogs.exportable(PlayerLogs.directory(this));
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            version = "?";
        }
        GpuDriverStore.Installed driver = GpuDriverStore.selectedDriver(this);
        String device = PlayerLogs.deviceDescription() + "\napp=" + version
            + "\ngpu=" + GpuDriverStore.gpuModel()
            + "\ndriver=" + (driver != null ? driver.metadata.name : GpuDriverStore.SYSTEM_DRIVER_NAME)
            + "\ndebug_logging=" + (renderPrefs(this).getBoolean("debug_log", false) ? "on" : "off")
            + "\ngpu_compat=" + GpuCompat.Choices.from(renderPrefs(this)).summary();
        new Thread(() -> {
            String message;
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new java.io.IOException("the file could not be opened");
                PlayerLogs.exportZip(files, device, output);
                String name = null;
                try (android.database.Cursor row = getContentResolver().query(uri,
                        new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (row != null && row.moveToFirst()) name = row.getString(0);
                } catch (RuntimeException ignored) {
                    // The name is only for the message.
                }
                message = getString(R.string.logs_saved, name != null ? name : "a zip");
            } catch (java.io.IOException | RuntimeException e) {
                message = getString(R.string.logs_save_failed, String.valueOf(e.getMessage()));
            }
            final String shown = message;
            runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
        }, "LR log export").start();
    }

    @Override
    protected void onPause() {
        if (touchControls != null) touchControls.onHostPause();
        if (companion != null) companion.onPause();
        // Leaving the game is when a player connects USB to fetch the log.
        PlayerLogs.publish(this);
        super.onPause();
    }

    /**
     * The game always fills the screen (#199): status and navigation bars stay
     * hidden (a swipe shows them briefly) and the picture extends under the
     * display cutout. SDL only does this when the native side switches to a
     * fullscreen window mode, and the default display mode never does.
     */
    private void hideSystemBars() {
        Window window = getWindow();
        if (window == null) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);
        if (Build.VERSION.SDK_INT >= 28 /* Android 9 (P) */) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            int cutoutMode = Build.VERSION.SDK_INT >= 30 /* Android 11 (R) */
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            if (attributes.layoutInDisplayCutoutMode != cutoutMode) {
                attributes.layoutInDisplayCutoutMode = cutoutMode;
                window.setAttributes(attributes);
            }
        }
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(window, window.getDecorView());
        updateSystemBarsBehavior();
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    void updateSystemBarsBehavior() {
        // Transient bars can stay "hidden" in app insets even while drawn. Use
        // normal swipe-revealed bars in hide-all mode so their visibility can
        // restore CTRL; the existing timer hides the bars again after 2.5 s.
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
            .setSystemBarsBehavior(touchControls != null && touchControls.hidesAllControls()
                ? WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                : WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    /** Bars shown by a swipe stay for a moment, like Android's own transient bars. */
    private static final long HIDE_BARS_AFTER_MS = 2500;
    private final Runnable hideSystemBarsRunnable = this::hideSystemBars;

    private void scheduleHideSystemBars() {
        bootHandler.removeCallbacks(hideSystemBarsRunnable);
        bootHandler.postDelayed(hideSystemBarsRunnable, HIDE_BARS_AFTER_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (touchControls != null) touchControls.onHostResume();
        if (companion != null) companion.onResume();
        bootHandler.removeCallbacks(bootSettled);
        bootHandler.postDelayed(bootSettled, BOOT_SETTLED_MS);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (!hasFocus && touchControls != null) touchControls.clearTouches();
        super.onWindowFocusChanged(hasFocus);
        // Dialogs and the pages opened over the game can bring the bars back.
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        if (touchControls != null) touchControls.clearTouches();
        bootHandler.removeCallbacks(bootSettled);
        bootHandler.removeCallbacks(hideSystemBarsRunnable);
        // A normal exit is not a failed start, however short it was.
        GpuDriverStore.clearBootPending(this);
        super.onDestroy();
        // The same driver would fail again on the next start, and the launcher
        // goes straight to the game once a driver was chosen: show the GPU
        // driver page with the reason instead (#185). Devices without custom
        // drivers get the reason in a dialog.
        if (isFinishing() && graphicsFailure != null) {
            Intent intent = new Intent(this, GpuDriverActivity.class);
            intent.putExtra(GpuDriverActivity.EXTRA_GRAPHICS_FAILURE, graphicsFailure);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            Runtime.getRuntime().exit(0);
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "c++_shared", "SDL3", "rexruntime", "rexgpu-xenos", "main" };
    }

    /**
     * Native argv (ReXGlue cvars use {@code --name=value}) plus the environment
     * the native side reads through getenv(). See android/README.md.
     */
    @Override
    protected String[] getArguments() {
        File game = GameStorage.gameDir(this);
        // Custom Vulkan driver (libadrenotools): the hook libraries sit in the
        // extracted native library directory, the chosen package in its own folder.
        nativeSetenv("LR_NATIVE_LIB_DIR", getApplicationInfo().nativeLibraryDir + "/");
        // ReXGlue (our lr-android branch) keeps <name>.toml and its default log folder in REX_ANDROID_APP_DIR;
        // without it they land in the read-only native library directory.
        nativeSetenv("REX_ANDROID_APP_DIR", PlayerLogs.directory(this).getParentFile().getAbsolutePath() + "/");
        File logDir = PlayerLogs.directory(this);
        // Logs go where a PC can copy them over USB; the device line names the phone.
        nativeSetenv("LR_LOG_DIR", logDir.getAbsolutePath());
        nativeSetenv("LR_ANDROID_DEVICE", PlayerLogs.deviceDescription());
        // Mesa drivers (Turnip) keep compiled pipelines on disk only with a cache
        // directory; Android gives them none. Other drivers ignore these.
        nativeSetenv("MESA_SHADER_CACHE_DIR", new File(getCacheDir(), "mesa_shader_cache").getAbsolutePath());
        nativeSetenv("MESA_SHADER_CACHE_MAX_SIZE", "256M");
        GpuDriverStore.Installed driver = GpuDriverStore.selectedDriver(this);
        // CTRL -> GPU compatibility (testing) and Stretch to fill: read at start (GpuCompat).
        GpuCompat.Choices compat = GpuCompat.Choices.from(renderPrefs(this));
        if (driver != null) {
            nativeSetenv("LR_CUSTOM_DRIVER_DIR", driver.directory.getAbsolutePath() + "/");
            nativeSetenv("LR_VK_CUSTOM_DRIVER", driver.metadata.libraryName);
            nativeSetenv("LR_VK_CUSTOM_DRIVER_MODE", GpuDriverStore.loadMode(this, driver));
            boolean syncDraw = GpuDriverStore.needsAdreno650SyncDraw(GpuDriverStore.gpuModel(), driver.metadata);
            if (syncDraw) nativeSetenv("LR_VK_ADRENO650_SYNC_DRAW", "1");
            // TU_DEBUG: the device's own flags and CTRL's Turnip choices in one list (the native side adds
            // syncdraw only while TU_DEBUG is unset). Turnip only: other drivers ignore it.
            String turnipFlags = driver.metadata.isPanVk() ? "" : GpuCompat.turnipDebug(compat, syncDraw,
                GpuDriverStore.needsTurnipSysmem(GpuDriverStore.gpuModel(), driver.metadata));
            if (!turnipFlags.isEmpty()) nativeSetenv("TU_DEBUG", turnipFlags);
            // PanVK refuses the Mali GPUs Mesa marks experimental without this.
            if (driver.metadata.isPanVk()) nativeSetenv("PAN_I_WANT_A_BROKEN_VULKAN_DRIVER", "1");
        }
        // Renderer settings this game needs on mobile Vulkan (from leftisthominid/lr-ffxiii-port's tuned config
        // and its Android entry point). Set as REX_<NAME> environment variables: unknown names are ignored,
        // whereas an unknown command-line flag aborts startup. Debug launches override them through the
        // "cvars" extra (command line outranks environment), e.g. --es cvars "--readback_memexport".
        //  - readback_memexport off: otherwise every memexport draw (title sequence effects) forces a full GPU
        //    round trip plus a copy back into guest memory on the GPU command thread (the title-sequence freeze);
        //  - vulkan_sparse_shared_memory off: sparse binding on mobile drivers drops GPU writes, which turns
        //    copied render targets black (seen on Turnip);
        //  - clear_memory_page_state / occlusion_query_enable off: the port's tested values.
        nativeSetenv("REX_READBACK_MEMEXPORT", "false");
        nativeSetenv("REX_VULKAN_SPARSE_SHARED_MEMORY", "false");
        nativeSetenv("REX_CLEAR_MEMORY_PAGE_STATE", "false");
        nativeSetenv("REX_OCCLUSION_QUERY_ENABLE", "false");
        // FIFO presentation only: with MAILBOX (the driver's default pick) presents never block, and the
        // repaint the ImGui overlays request every frame turned the SDL UI thread into an unthrottled
        // redraw loop (84% of a core at an idle title menu on the Thor, most of the heat).
        nativeSetenv("REX_VULKAN_ALLOW_PRESENT_MODE_MAILBOX", "false");
        nativeSetenv("REX_VULKAN_ALLOW_PRESENT_MODE_IMMEDIATE", "false");
        nativeSetenv("REX_VULKAN_ALLOW_PRESENT_MODE_FIFO_RELAXED", "false");
        // Memory that serves no purpose (from the DS2 port, its #8: a 6 GB phone): upload pages beyond 8 MB free per
        // pool once the GPU is done with them (a pool otherwise keeps its loading peak), and render targets that own
        // no EDRAM data and no draw bound for 30 s freed (leftovers of the title, menus and loading screens).
        nativeSetenv("REX_UPLOAD_BUFFER_MAX_FREE_MB", "8");
        nativeSetenv("REX_VULKAN_EVICT_IDLE_RENDER_TARGETS_S", "30");
        // Display settings from the CTRL dialog (renderPrefs): the hooks read LR_FPS / LR_AA at
        // start, the presenter's upscaler is the present_effect cvar (restart only).
        SharedPreferences render = renderPrefs(this);
        nativeSetenv("LR_FPS", render.getString("fps", DEFAULT_FPS));
        // "Smoother 60 fps" (CTRL dialog): 60 fps as 2 vertical blanks of the 120 Hz panel mode.
        nativeSetenv("LR_VBLANK120", panel120Active(render) ? "1" : "0");
        nativeSetenv("LR_AA", render.getString("aa", "off"));
        nativeSetenv("REX_PRESENT_EFFECT", render.getString("upscale", "bilinear"));
        nativeSetenv("LR_FRAME_OVERLAY", render.getBoolean("overlay", false) ? "1" : "0");
        // Cheats (CTRL dialog, off by default): the mask the hooks read at start (LR_CHEATS).
        // Tests and benches (debug builds): --es LR_CHEATS N for this start only, not saved.
        int cheats = Cheats.saved(render);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            int forced = Cheats.parseExtra(getIntent().getStringExtra("LR_CHEATS"));
            if (forced >= 0) cheats = forced;
        }
        nativeSetenv("LR_CHEATS", Cheats.optionValue(cheats));
        // "Debug logging" (CTRL dialog, off by default; the owner, 2026-11-11): the game's frame / wait /
        // CPU statistics and slow-frame reports (LR_DIAG), the command thread's per-draw timers and
        // slow-frame lines (vulkan_slow_frame_ms), the WAIT_REG_MEM statistics, and info-level log
        // lines (each one written and flushed at once by the thread that logs it). Off keeps
        // warnings and errors. Scripted launches turn it on with `--es LR_DIAG 1`.
        // ON by default until the project concludes (the owner, 2026-11-11: their sessions must carry the
        // statistics when a glitch shows up). Installs that saved "off" with the CTRL dialog's Apply
        // are switched on once (debug_log_default_on); the player's later choice sticks.
        // The public build (BuildConfig.PUBLIC_DEFAULTS) skips that and starts with logging off.
        if (!BuildConfig.PUBLIC_DEFAULTS && !render.getBoolean("debug_log_default_on", false)) {
            render.edit().putBoolean("debug_log", true).putBoolean("debug_log_default_on", true).apply();
        }
        boolean diag = render.getBoolean("debug_log", DEFAULT_DEBUG_LOG);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getStringExtra("LR_DIAG") != null) {
            diag = !"0".equals(getIntent().getStringExtra("LR_DIAG"));
        }
        nativeSetenv("LR_DIAG", diag ? "1" : "0");
        // "Exact Xbox shader math" (CTRL, off by default since 2026-11-11): the Xenos multiply rule
        // (0 x anything = 0, also infinity / NaN) in every translated shader. Off: host multiplies
        // with special-function infinities clamped (SDK patch 100); Luxerion plaza GPU 18.3 -> 16.0 ms,
        // warp sequence frames over 45 ms 1.4% -> 0.2%, no visible difference seen. Changing it
        // rebuilds the pixel shader libraries once (the shaders used in play first, ~16 s).
        nativeSetenv("REX_VULKAN_SHADER_XENOS_MULTIPLY",
                     render.getBoolean("exact_shader_math", false) ? "true" : "false");
        for (java.util.Map.Entry<String, String> cvar : GpuCompat.cvars(compat).entrySet()) {
            nativeSetenv(GpuCompat.environmentName(cvar.getKey()), cvar.getValue());
        }
        if (!diag) {
            nativeSetenv("REX_VULKAN_SLOW_FRAME_MS", "0");
            nativeSetenv("REX_GPU_LOG_WAIT_REG_MEM", "false");
            nativeSetenv("REX_LOG_LEVEL", "warn");
        }
        // Debug launches can select an isolated fixture and forward LR_* switches.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            String override = getIntent().getStringExtra("game_root");
            if (override != null && !override.isEmpty()) game = new File(override);
            // Every LR_* and REX_* extra is forwarded as an environment variable:
            // `am start --es LR_DEBUG_FOO 1`, `--es REX_WAIT_SPIN_US 50` (REX_<CVAR> sets a cvar).
            Bundle extras = getIntent().getExtras();
            if (extras != null) {
                for (String name : extras.keySet()) {
                    // LR_/REX_ for the game and runtime, TU_/MESA_/FD_/IR3_ for the Turnip driver.
                    if (!name.startsWith("LR_") && !name.startsWith("REX_") && !name.startsWith("TU_")
                            && !name.startsWith("MESA_") && !name.startsWith("FD_") && !name.startsWith("IR3_")) continue;
                    Object value = extras.get(name);
                    if (value != null) nativeSetenv(name, String.valueOf(value));
                }
            }
        }
        // ReXGlue's defaults for these are XDG paths under $HOME (= /data on Android), which the app
        // cannot write; the runtime aborted on create_directories("/data/.local"). Keep everything in
        // the app's external files dir.
        File appRoot = logDir.getParentFile() != null ? logDir.getParentFile() : getFilesDir();
        File userRoot = new File(appRoot, "user");
        File cacheRoot = new File(appRoot, "cache");
        File metadataRoot = new File(appRoot, "metadata");
        userRoot.mkdirs(); cacheRoot.mkdirs(); metadataRoot.mkdirs();
        // Public APKs carry a shader cache, so the first start precompiles at the title (ShaderSeed).
        if (ShaderSeed.install(this, cacheRoot) && bootOverlay != null) bootOverlay.setFirstStart();
        java.util.ArrayList<String> args = new java.util.ArrayList<>(java.util.Arrays.asList(
            "--game_data_root=" + game.getAbsolutePath(),
            "--user_data_root=" + userRoot.getAbsolutePath(),
            "--cache_root=" + cacheRoot.getAbsolutePath(),
            "--metadata_root=" + metadataRoot.getAbsolutePath(),
            "--log_file=" + new File(logDir, "lr.log").getAbsolutePath(),
            "--gpu_plugin=xenos",
            // The title screen floods the log with 'Texture fetch constant has "invalid" type' and draws
            // black art without this (Xenia's usual workaround for this engine).
            "--gpu_allow_invalid_fetch_constants",
            "--present_max_height=" + PRESENT_MAX_HEIGHT));
        // Debug launches can add runtime cvars: `am start ... --es cvars "--dump_shaders=/sdcard/... --log_level=debug"`.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            String extra = getIntent().getStringExtra("cvars");
            if (extra != null) {
                for (String token : extra.trim().split("\\s+")) {
                    if (token.startsWith("--")) args.add(token);
                }
            }
        }
        return args.toArray(new String[0]);
    }
}
