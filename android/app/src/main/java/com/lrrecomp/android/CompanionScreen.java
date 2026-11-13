package com.lrrecomp.android;

import android.app.Activity;
import android.app.Presentation;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * The bottom screen of dual-screen devices (AYN Thor: "Screen-2", 1240x1080, touch): an Android
 * Presentation on the first presentation display. Shown while the game is in the foreground and
 * the CTRL dialog's "Second screen" setting is on (default on). Drawing happens off the game's
 * cores and GPU (CompanionSurface); touching a schema presses LB/RB on controller 1.
 *
 * The window never takes focus (FLAG_NOT_FOCUSABLE): with one global focus, a touch on the
 * second display would otherwise move key focus there and the game would stop seeing the
 * controller.
 */
final class CompanionScreen implements DisplayManager.DisplayListener {
    private static final String TAG = "lrrecomp";
    static final String PREF = "companion";

    private final Activity activity;
    private final DisplayManager displays;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String demo;
    private Presentation presentation;
    private CompanionSurface surface;
    private boolean resumed;
    private final Boolean forced;

    CompanionScreen(Activity activity) {
        this.activity = activity;
        this.displays = (DisplayManager) activity.getSystemService(Activity.DISPLAY_SERVICE);
        String extra = null;
        if ((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            extra = activity.getIntent().getStringExtra("LR_COMPANION_DEMO");
        }
        demo = extra;
        // Benches (debug builds): --es LR_COMPANION 0|1 overrides the CTRL dialog setting for an A/B.
        String force = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            ? activity.getIntent().getStringExtra("LR_COMPANION") : null;
        forced = force == null ? null : !"0".equals(force);
        // Benches: --es LR_COMPANION_MAP area|follow sets the remembered map mode.
        String mapMode = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            ? activity.getIntent().getStringExtra("LR_COMPANION_MAP") : null;
        if ("area".equals(mapMode) || "follow".equals(mapMode)) {
            RuntimeActivity.renderPrefs(activity).edit().putString(CompanionView.PREF_MAP_MODE, mapMode).apply();
        }
    }

    /** True when the device has a second screen the companion can use. */
    static boolean available(Activity activity) {
        DisplayManager dm = (DisplayManager) activity.getSystemService(Activity.DISPLAY_SERVICE);
        return dm != null && target(dm) != null;
    }

    /** The setting; on by default (it only does something on dual-screen devices). */
    static boolean enabled(SharedPreferences render) {
        return render.getBoolean(PREF, true);
    }

    void onResume() {
        resumed = true;
        if (displays != null) displays.registerDisplayListener(this, handler);
        update();
    }

    void onPause() {
        resumed = false;
        if (displays != null) displays.unregisterDisplayListener(this);
        // Leaving the game gives the bottom screen back (ES-DE's companion, the launcher).
        dismiss();
    }

    /** Applies the CTRL dialog's setting at once. */
    void update() {
        boolean want = resumed && (forced != null ? forced : enabled(RuntimeActivity.renderPrefs(activity)));
        Display display = want && displays != null ? target(displays) : null;
        if (display == null) {
            dismiss();
            return;
        }
        if (presentation != null && presentation.getDisplay().getDisplayId() == display.getDisplayId()) return;
        dismiss();
        show(display);
    }

    private static Display target(DisplayManager dm) {
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.getState() != Display.STATE_OFF) return d;
        }
        return null;
    }

    private void show(Display display) {
        try {
            surface = new CompanionSurface(activity, demo);
            presentation = new Screen(activity, display, surface);
            presentation.show();
            surface.start();
            CompanionAssets.ensure(activity);  // maps + HUD pieces from the game files, once (background)
            Log.i(TAG, "companion: showing on display " + display.getDisplayId() + " (" + display.getName() + ")");
        } catch (WindowManager.InvalidDisplayException | IllegalStateException e) {
            Log.w(TAG, "companion: cannot show on display " + display.getDisplayId(), e);
            if (surface != null) surface.stop();
            presentation = null;
            surface = null;
        }
    }

    private void dismiss() {
        if (surface != null) surface.stop();
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
        presentation = null;
        surface = null;
    }

    @Override public void onDisplayAdded(int displayId) { update(); }
    @Override public void onDisplayRemoved(int displayId) { update(); }
    @Override public void onDisplayChanged(int displayId) { update(); }

    /** The Presentation window: full screen, no system bars, never focused. */
    private static final class Screen extends Presentation {
        private final View content;

        Screen(Activity owner, Display display, View content) {
            super(owner, display);
            this.content = content;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Window window = getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN);
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
            setContentView(content);
            // The bottom screen never changes faster than ~10 times a second: its 60 Hz mode is enough.
            Display d = getDisplay();
            if (window != null && d != null) {
                Display.Mode cur = d.getMode();
                for (Display.Mode m : d.getSupportedModes()) {
                    if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
                            && Math.abs(m.getRefreshRate() - 60f) < 1f) {
                        WindowManager.LayoutParams lp = window.getAttributes();
                        lp.preferredDisplayModeId = m.getModeId();
                        window.setAttributes(lp);
                        break;
                    }
                }
            }
        }
    }
}
