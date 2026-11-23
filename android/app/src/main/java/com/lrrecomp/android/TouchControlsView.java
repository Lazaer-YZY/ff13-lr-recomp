// Adapted from Lost Odyssey Recomp (GPL-3.0).
package com.lrrecomp.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/** Local touch controls layered over SDL's surface; physical pads stay in SDL. */
final class TouchControlsView extends View {
    private static final long CONTROLLER_POLL_MS = 1000;
    private static final int UP = 0x0001, DOWN = 0x0002, LEFT = 0x0004, RIGHT = 0x0008;
    private static final int START = 0x0010, BACK = 0x0020;
    private static final int L3 = 0x0040, R3 = 0x0080;
    private static final int LB = 0x0100, RB = 0x0200;
    private static final int A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000;
    private static final String[] NAMES = {
        "LEFT STICK", "RIGHT STICK", "D-PAD", "A", "B", "X", "Y",
        "L3", "R3", "LB", "LT", "BACK", "START", "RT", "RB"
    };

    private static final class Pointer {
        final int element;
        float x, y;

        Pointer(int element, float x, float y) {
            this.element = element;
            this.x = x;
            this.y = y;
        }
    }

    private final RuntimeActivity activity;
    private final SharedPreferences preferences;
    private final InputManager inputManager;
    private final TouchControllerVisibility visibility;
    private final Handler controllerHandler = new Handler(Looper.getMainLooper());
    private final Runnable controllerPoll = new Runnable() {
        @Override public void run() {
            if (!hostResumed || !isAttachedToWindow()) return;
            refreshControllers();
            controllerHandler.postDelayed(this, CONTROLLER_POLL_MS);
        }
    };
    private final InputManager.InputDeviceListener deviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int deviceId) { refreshControllers(); }
        @Override public void onInputDeviceRemoved(int deviceId) { refreshControllers(); }
        @Override public void onInputDeviceChanged(int deviceId) { refreshControllers(); }
    };
    private final TouchCtrlHandle ctrl = new TouchCtrlHandle();
    private final Runnable ctrlWake = this::invalidate;
    private long ctrlWakeAt = -1;
    private boolean settingsDialogOpen, settingsPointerRestores;
    private final SparseArray<Pointer> pointers = new SparseArray<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TouchControlLayout layout, draft;
    private CheckBox settingsToggle;
    private TextView controllerHint;
    private boolean settingsChoiceEdited, syncingSettingsToggle;
    private boolean editing, customLayout, hostResumed, deviceListenerRegistered;
    private int settingsPointer = -1, dragPointer = -1, toolbarPointer = -1;
    private int selected = -1, toolbarPressed = -1;
    private float dragOffsetX, dragOffsetY, baseUnit;
    private int shownButtons, shownLt, shownRt, shownLx, shownLy, shownRx, shownRy;

    TouchControlsView(RuntimeActivity activity) {
        super(activity);
        this.activity = activity;
        preferences = activity.getSharedPreferences("touch_controls", Context.MODE_PRIVATE);
        visibility = new TouchControllerVisibility(preferences.getBoolean("enabled", true));
        inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        customLayout = preferences.getInt("layout_version", 0) == 1;
        long now = SystemClock.uptimeMillis();
        ctrl.reset(now);
        ctrl.setEnabled(preferences.getBoolean("ctrl_auto_hide", true), now);
        ctrl.setHideAll(preferences.getBoolean("hide_all", false), now);
        ctrl.setHideAfterStart(preferences.getBoolean("ctrl_hide_after_start", false), now);
        setClickable(true);
        setContentDescription("Touch controller settings");
    }

    private void refreshControllers() {
        int controllers = 0;
        if (inputManager != null) {
            for (int deviceId : inputManager.getInputDeviceIds()) {
                InputDevice device = inputManager.getInputDevice(deviceId);
                if (device == null || device.isVirtual()) continue;
                int sources = device.getSources();
                if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                    ++controllers;
                }
            }
        }
        if (RuntimeActivity.nativeHasConnectedController()) controllers = Math.max(controllers, 1);
        if (!visibility.setControllerCount(controllers)) return;
        clearTouches();
        if (settingsToggle != null && !settingsChoiceEdited) {
            syncingSettingsToggle = true;
            settingsToggle.setChecked(visibility.visible());
            syncingSettingsToggle = false;
        }
        if (controllerHint != null)
            controllerHint.setVisibility(visibility.controllerPresent() ? VISIBLE : GONE);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startControllerWatch();
    }

    private void startControllerWatch() {
        if (!hostResumed || !isAttachedToWindow()) return;
        if (inputManager != null && !deviceListenerRegistered) {
            inputManager.registerInputDeviceListener(deviceListener, controllerHandler);
            deviceListenerRegistered = true;
        }
        refreshControllers();
        controllerHandler.removeCallbacks(controllerPoll);
        controllerHandler.postDelayed(controllerPoll, CONTROLLER_POLL_MS);
    }

    private void stopControllerWatch() {
        controllerHandler.removeCallbacks(controllerPoll);
        if (inputManager != null && deviceListenerRegistered) {
            inputManager.unregisterInputDeviceListener(deviceListener);
            deviceListenerRegistered = false;
        }
    }

    void onHostResume() {
        hostResumed = true;
        // Back from a CTRL page or another app: show CTRL again, retract later.
        ctrl.reset(SystemClock.uptimeMillis());
        invalidate();
        startControllerWatch();
    }

    void onHostPause() {
        hostResumed = false;
        stopControllerWatch();
        cancelCtrlWake();
        clearTouches();
    }

    /** System bars and Android Back restore only CTRL, never the hidden gamepad. */
    void revealCtrl() {
        ctrl.activity(SystemClock.uptimeMillis());
        invalidate();
    }

    /** CTRL removed by "Removed 30 s after the game starts": Android Back opens Settings instead. */
    boolean ctrlGone() { return ctrl.gone(SystemClock.uptimeMillis()); }

    /** Android Back while CTRL is gone: the Settings dialog, unless it or the layout editor is already open. */
    void openSettingsFromBack() {
        if (settingsDialogOpen || editing) return;
        showSettings();
    }

    private boolean controlsVisible() {
        return !ctrl.hideAll() && visibility.visible();
    }

    boolean hidesAllControls() { return ctrl.hideAll(); }

    private static float bounded(float value, float minimum, float maximum,
                                 float fallback) {
        return Float.isFinite(value) ? Math.max(minimum, Math.min(maximum, value))
            : fallback;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;
        baseUnit = Math.min(width * .0715f, height * .1625f);
        float size = bounded(preferences.getFloat("size", 1f),
                             TouchControlLayout.MIN_SIZE,
                             TouchControlLayout.MAX_SIZE, 1f);
        float opacity = bounded(preferences.getFloat("opacity", 1f),
                                TouchControlLayout.MIN_OPACITY,
                                TouchControlLayout.MAX_OPACITY, 1f);
        TouchControlLayout fresh = TouchControlLayout.defaults(
            width, height, baseUnit * size);
        fresh.size = size;
        fresh.opacity = opacity;
        if (customLayout) {
            for (int id = 0; id < TouchControlLayout.COUNT; ++id) {
                float storedX = preferences.getFloat("x_" + id, fresh.x[id]);
                float storedY = preferences.getFloat("y_" + id, fresh.y[id]);
                if (Float.isFinite(storedX)) fresh.x[id] = storedX;
                if (Float.isFinite(storedY)) fresh.y[id] = storedY;
                fresh.visible[id] = preferences.getBoolean("visible_" + id, true);
            }
            float ctrlX = preferences.getFloat("ctrl_x", fresh.ctrlX);
            float ctrlY = preferences.getFloat("ctrl_y", fresh.ctrlY);
            if (Float.isFinite(ctrlX)) fresh.ctrlX = ctrlX;
            if (Float.isFinite(ctrlY)) fresh.ctrlY = ctrlY;
        }
        layout = fresh;
        layout.clampAll(width, height, baseUnit * layout.size, 0f);
        if (draft != null) draft.clampAll(width, height, baseUnit * draft.size,
                                          editorReservedBottom());
        clearTouches();
    }

    private float editorReservedBottom() { return getHeight() * .15f; }
    private TouchControlLayout activeLayout() { return editing ? draft : layout; }
    private float unit() { return baseUnit * activeLayout().size; }
    private float px(int id) { return activeLayout().pixelX(id, getWidth()); }
    private float py(int id) { return activeLayout().pixelY(id, getHeight()); }
    private float settingX() { return activeLayout().ctrlX * getWidth(); }
    private float settingY() { return activeLayout().ctrlY * getHeight(); }
    private float settingRadius() { return unit() * .45f; }
    private boolean onCtrl(float x, float y) {
        return near(x, y, settingX(), settingY(), unit() * TouchControlLayout.CTRL_HIT_RADIUS);
    }

    /** Lays out CTRL for the current slide progress (see TouchCtrlHandle). */
    private float layoutCtrl(long now) {
        float progress = ctrl.progress(now);
        // The retracted tab sits at the physical screen edge: the system bars are
        // hidden in the game, so the layout's status-bar strip is not reserved here.
        ctrl.layout(settingX(), settingY(), settingRadius(), getWidth(), getHeight(),
                    48f * getResources().getDisplayMetrics().density, 0f, progress);
        return progress;
    }

    private void syncCtrlPin() {
        ctrl.setPinned(editing || settingsDialogOpen || settingsPointer != -1,
                       SystemClock.uptimeMillis());
        invalidate();
    }

    private void cancelCtrlWake() {
        controllerHandler.removeCallbacks(ctrlWake);
        ctrlWakeAt = -1;
    }

    private void scheduleCtrlWake(long now) {
        long wait = ctrl.nextWakeMs(now);
        if (wait == 0) {
            postInvalidateOnAnimation();
        } else if (wait > 0 && hostResumed) {
            long at = now + wait;
            if (at == ctrlWakeAt) return;
            controllerHandler.removeCallbacks(ctrlWake);
            controllerHandler.postAtTime(ctrlWake, at);
            ctrlWakeAt = at;
        }
    }

    private static boolean near(float x, float y, float cx, float cy, float radius) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    private boolean hit(int id, float x, float y) {
        float radius = TouchControlLayout.hitRadius(id) * unit();
        return near(x, y, px(id), py(id), radius);
    }

    private int elementAt(float x, float y, boolean includeHidden) {
        int best = -1;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (int id = 0; id < TouchControlLayout.COUNT; ++id) {
            if (!includeHidden && !activeLayout().visible[id]) continue;
            float radius = TouchControlLayout.hitRadius(id) * unit();
            float dx = x - px(id), dy = y - py(id);
            float distance = (dx * dx + dy * dy) / (radius * radius);
            if (distance <= 1f && distance < bestDistance) {
                best = id;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int stick(float value) {
        if (Math.abs(value) < .12f) return 0;
        return Math.round(Math.max(-1f, Math.min(1f, value)) * 32767f);
    }

    private static int buttonMask(int element) {
        switch (element) {
            case TouchControlLayout.A: return A;
            case TouchControlLayout.B: return B;
            case TouchControlLayout.X: return X;
            case TouchControlLayout.Y: return Y;
            case TouchControlLayout.L3: return L3;
            case TouchControlLayout.R3: return R3;
            case TouchControlLayout.LB: return LB;
            case TouchControlLayout.RB: return RB;
            case TouchControlLayout.BACK: return BACK;
            case TouchControlLayout.START: return START;
            default: return 0;
        }
    }

    private void publish() {
        int buttons = 0, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0;
        boolean leftAssigned = false, rightAssigned = false;
        if (controlsVisible() && !editing && layout != null && baseUnit > 0f) {
            float unit = unit();
            for (int i = 0; i < pointers.size(); ++i) {
                Pointer pointer = pointers.valueAt(i);
                int id = pointer.element;
                if (!layout.visible[id]) continue;
                if (id == TouchControlLayout.LEFT_STICK && !leftAssigned) {
                    float dx = (pointer.x - px(id)) / unit;
                    float dy = (py(id) - pointer.y) / unit;
                    float length = (float) Math.hypot(dx, dy);
                    if (length > 1f) { dx /= length; dy /= length; }
                    lx = stick(dx); ly = stick(dy); leftAssigned = true;
                } else if (id == TouchControlLayout.RIGHT_STICK && !rightAssigned) {
                    float dx = (pointer.x - px(id)) / unit;
                    float dy = (py(id) - pointer.y) / unit;
                    float length = (float) Math.hypot(dx, dy);
                    if (length > 1f) { dx /= length; dy /= length; }
                    rx = stick(dx); ry = stick(dy); rightAssigned = true;
                } else if (id == TouchControlLayout.DPAD) {
                    float dx = (pointer.x - px(id)) / unit;
                    float dy = (pointer.y - py(id)) / unit;
                    if (dx < -.28f) buttons |= LEFT;
                    if (dx > .28f) buttons |= RIGHT;
                    if (dy < -.28f) buttons |= UP;
                    if (dy > .28f) buttons |= DOWN;
                } else if (hit(id, pointer.x, pointer.y)) {
                    if (id == TouchControlLayout.LT) lt = 255;
                    else if (id == TouchControlLayout.RT) rt = 255;
                    else buttons |= buttonMask(id);
                }
            }
        }
        shownButtons = buttons; shownLt = lt; shownRt = rt;
        shownLx = lx; shownLy = ly; shownRx = rx; shownRy = ry;
        RuntimeActivity.nativeSetTouchInput(buttons, lt, rt, lx, ly, rx, ry);
        invalidate();
    }

    void clearTouches() {
        pointers.clear();
        boolean heldCtrl = settingsPointer != -1;
        settingsPointer = dragPointer = toolbarPointer = -1;
        settingsPointerRestores = false;
        if (heldCtrl) syncCtrlPin();
        toolbarPressed = -1;
        publish();
    }

    private void setControlsEnabled(boolean value) {
        clearTouches();
        boolean previous = visibility.enabledPreference();
        visibility.applyShow(value);
        if (visibility.enabledPreference() != previous)
            preferences.edit().putBoolean("enabled", visibility.enabledPreference()).apply();
        invalidate();
    }

    private void saveLayout() {
        SharedPreferences.Editor editor = preferences.edit().putInt("layout_version", 1);
        for (int id = 0; id < TouchControlLayout.COUNT; ++id) {
            editor.putFloat("x_" + id, layout.x[id]);
            editor.putFloat("y_" + id, layout.y[id]);
            editor.putBoolean("visible_" + id, layout.visible[id]);
        }
        editor.putFloat("ctrl_x", layout.ctrlX).putFloat("ctrl_y", layout.ctrlY);
        editor.apply();
        customLayout = true;
    }

    private void applySettings(boolean show, int sizePercent, int opacityPercent,
                               boolean ctrlAutoHide, boolean hideAll, boolean ctrlHideAfterStart) {
        clearTouches();
        ctrl.setEnabled(ctrlAutoHide, SystemClock.uptimeMillis());
        ctrl.setHideAll(hideAll, SystemClock.uptimeMillis());
        // Turned on now: the 30 s count from here (the game started long ago).
        ctrl.setHideAfterStart(ctrlHideAfterStart, SystemClock.uptimeMillis());
        activity.updateSystemBarsBehavior();
        float size = sizePercent / 100f;
        if (!customLayout) layout = TouchControlLayout.defaults(
            getWidth(), getHeight(), baseUnit * size);
        layout.size = size;
        layout.opacity = opacityPercent / 100f;
        layout.clampAll(getWidth(), getHeight(), baseUnit * layout.size, 0f);
        preferences.edit().putFloat("size", layout.size)
            .putFloat("opacity", layout.opacity)
            .putBoolean("ctrl_auto_hide", ctrlAutoHide)
            .putBoolean("hide_all", hideAll)
            .putBoolean("ctrl_hide_after_start", ctrlHideAfterStart).apply();
        setControlsEnabled(show);
    }

    /**
     * A radio group of choices added to {@code content} under a bold label (none if {@code label} is null); returns
     * the first button (its group holds the others, see {@link #radioChoice}).
     */
    private RadioButton radio(LinearLayout content, String label, int checked, String... texts) {
        if (label != null) header(content, label);
        RadioGroup group = new RadioGroup(content.getContext());
        RadioButton first = null;
        for (int i = 0; i < texts.length; ++i) {
            RadioButton button = new RadioButton(content.getContext());
            button.setId(View.generateViewId());
            button.setText(texts[i]);
            group.addView(button);
            if (i == checked) button.setChecked(true);
            if (first == null) first = button;
        }
        content.addView(group);
        return first;
    }

    /** A bold label over a group of choices inside a section of the settings dialog. */
    private void header(LinearLayout content, String text) {
        TextView view = new TextView(content.getContext());
        view.setText(text);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        float density = getResources().getDisplayMetrics().density;
        view.setPadding(0, Math.round(10f * density), 0, Math.round(2f * density));
        content.addView(view);
    }

    /**
     * A collapsible section of the settings dialog (as in the DS2 port's CTRL): a header row (closed: a right
     * triangle, open: a down one) and the body its options go into, closed at first. Opening one closes the others
     * and scrolls its header to the top.
     */
    private LinearLayout section(LinearLayout content, ScrollView scroll, java.util.List<TextView> headers,
                                 java.util.List<LinearLayout> bodies, String title) {
        Context context = content.getContext();
        float density = getResources().getDisplayMetrics().density;
        View divider = new View(context);
        divider.setBackgroundColor(Color.argb(60, 128, 128, 128));
        content.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                               Math.max(1, Math.round(density))));
        TextView header = new TextView(context);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        header.setPadding(0, Math.round(14f * density), 0, Math.round(14f * density));
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            header.setBackgroundResource(ripple.resourceId);
        }
        header.setTag(title);
        header.setText("▸  " + title);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Math.round(8f * density), 0, 0, Math.round(10f * density));
        body.setVisibility(GONE);
        content.addView(header);
        content.addView(body);
        headers.add(header);
        bodies.add(body);
        header.setOnClickListener(v -> {
            boolean open = body.getVisibility() != VISIBLE;
            for (int i = 0; i < bodies.size(); ++i) {
                bodies.get(i).setVisibility(GONE);
                headers.get(i).setText("▸  " + headers.get(i).getTag());
            }
            if (!open) return;
            body.setVisibility(VISIBLE);
            header.setText("▾  " + title);
            // Once the closed sections above have shrunk: the opened header at the top of the dialog.
            header.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        header.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        scroll.smoothScrollTo(0, header.getTop());
                    }
                });
        });
        return body;
    }

    /** A part of a section that opens and closes on its own (closed at first); returns its body. */
    private LinearLayout subsection(LinearLayout content, String title) {
        Context context = content.getContext();
        float density = getResources().getDisplayMetrics().density;
        TextView header = new TextView(context);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.setPadding(0, Math.round(12f * density), 0, Math.round(12f * density));
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            header.setBackgroundResource(ripple.resourceId);
        }
        header.setText("▸  " + title);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Math.round(8f * density), 0, 0, 0);
        body.setVisibility(GONE);
        content.addView(header);
        content.addView(body);
        header.setOnClickListener(v -> {
            boolean open = body.getVisibility() != VISIBLE;
            body.setVisibility(open ? VISIBLE : GONE);
            header.setText((open ? "▾  " : "▸  ") + title);
        });
        return body;
    }

    /** A short grey line under an option, indented to the option's text. */
    private TextView hint(LinearLayout content, String text) {
        TextView view = new TextView(content.getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setAlpha(0.7f);
        float density = getResources().getDisplayMetrics().density;
        view.setPadding(Math.round(32f * density), 0, 0, Math.round(4f * density));
        content.addView(view);
        return view;
    }

    /** A checkbox with an optional grey explanation under it. */
    private CheckBox option(LinearLayout content, String label, String help, boolean checked) {
        CheckBox box = new CheckBox(content.getContext());
        box.setText(label);
        box.setChecked(checked);
        content.addView(box);
        if (help != null) hint(content, help);
        return box;
    }

    /** A button opening another page, in the dialog's own text (not all caps). */
    private Button pageButton(LinearLayout content, String label) {
        Button button = new Button(content.getContext());
        button.setText(label);
        button.setAllCaps(false);
        content.addView(button);
        return button;
    }

    /** Index of the checked button in the group of {@code first}, 0 if none. */
    private static int radioChoice(RadioButton first) {
        RadioGroup group = (RadioGroup) first.getParent();
        for (int i = 0; i < group.getChildCount(); ++i) {
            if (((RadioButton) group.getChildAt(i)).isChecked()) return i;
        }
        return 0;
    }

    /** A dropdown of {@code texts} added to {@code content}, {@code selected} chosen. */
    private Spinner spinner(LinearLayout content, int selected, String... texts) {
        Spinner spinner = new Spinner(content.getContext());
        ArrayAdapter<String> adapter =
            new ArrayAdapter<>(content.getContext(), android.R.layout.simple_spinner_item, texts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selected, texts.length - 1)));
        content.addView(spinner);
        return spinner;
    }

    /** True on a screen wider than 16:9 (where "Stretch to fill wide screens" does something). */
    private boolean wideScreen() {
        int w = Math.max(getWidth(), getHeight()), h = Math.min(getWidth(), getHeight());
        return h > 0 && (float) w / h > 16f / 9f + 0.01f;
    }

    private void showSettings() {
        clearTouches();
        Context strings = activity;
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20f * density);
        content.setPadding(padding, 0, padding, 0);
        // The same dialog as the DS2 port's CTRL (the owner, 2026-11-23: "The two menus should look and feel very
        // similar"): collapsible sections, all closed when the dialog opens, one open at a time, the footnote on top,
        // Exit at the top right; the options differ where the games do.
        ScrollView scroll = new ScrollView(activity);
        java.util.List<TextView> sectionHeaders = new java.util.ArrayList<>();
        java.util.List<LinearLayout> sectionBodies = new java.util.ArrayList<>();
        TextView nextStart = hint(content, strings.getString(R.string.touch_next_start_note));
        nextStart.setPadding(0, Math.round(4f * density), 0, Math.round(8f * density));
        LinearLayout touchSec = section(content, scroll, sectionHeaders, sectionBodies,
            strings.getString(R.string.touch_section_controls));
        LinearLayout rateSec = section(content, scroll, sectionHeaders, sectionBodies,
            strings.getString(R.string.touch_section_performance));
        LinearLayout imageSec = section(content, scroll, sectionHeaders, sectionBodies,
            strings.getString(R.string.touch_section_image));
        LinearLayout screenSec = !CompanionScreen.available(activity) ? null : section(content, scroll,
            sectionHeaders, sectionBodies, strings.getString(R.string.touch_section_second_screen));
        LinearLayout gameSec = section(content, scroll, sectionHeaders, sectionBodies,
            strings.getString(R.string.touch_section_game));
        LinearLayout toolsSec = section(content, scroll, sectionHeaders, sectionBodies,
            strings.getString(R.string.touch_section_troubleshooting));
        SharedPreferences render = RuntimeActivity.renderPrefs(activity);

        // --- Controls: the on-screen controls, their layout editor, size and opacity, the CTRL button.
        header(touchSec, strings.getString(R.string.touch_group_on_screen));
        CheckBox toggle = new CheckBox(activity);
        toggle.setText(strings.getString(R.string.touch_show_controls));
        // The old "Hide all touch controls" also hid the gamepad: shown unticked so Apply keeps it hidden.
        toggle.setChecked(visibility.visible() && !ctrl.hideAll());
        settingsChoiceEdited = false;
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingSettingsToggle) settingsChoiceEdited = true;
        });
        touchSec.addView(toggle);
        TextView hint = hint(touchSec, strings.getString(R.string.touch_controller_hint));
        hint.setVisibility(visibility.controllerPresent() ? VISIBLE : GONE);
        settingsToggle = toggle;
        controllerHint = hint;
        // The layout editor here rather than as a dialog button, so it is clear it moves the touch controls.
        Button editLayoutButton = pageButton(touchSec, strings.getString(R.string.touch_edit_layout));
        TextView sizeLabel = new TextView(activity);
        SeekBar size = new SeekBar(activity);
        size.setMax(80);
        size.setProgress(Math.round(layout.size * 100f) - 60);
        sizeLabel.setText(strings.getString(R.string.touch_control_size, size.getProgress() + 60));
        size.setOnSeekBarChangeListener(seekListener(value ->
            sizeLabel.setText(strings.getString(R.string.touch_control_size, value + 60))));
        touchSec.addView(sizeLabel);
        touchSec.addView(size);
        TextView opacityLabel = new TextView(activity);
        SeekBar opacity = new SeekBar(activity);
        opacity.setMax(75);
        opacity.setProgress(Math.round(layout.opacity * 100f) - 25);
        opacityLabel.setText(strings.getString(R.string.touch_opacity, opacity.getProgress() + 25));
        opacity.setOnSeekBarChangeListener(seekListener(value ->
            opacityLabel.setText(strings.getString(R.string.touch_opacity, value + 25))));
        touchSec.addView(opacityLabel);
        touchSec.addView(opacity);
        // The CTRL button, one choice (TouchCtrlHandle): always shown, auto-hidden to a tab (the default), or removed
        // 30 s after the start with Android Back opening Settings (RuntimeActivity). The old "Hide all touch
        // controls" shows as removed.
        RadioButton ctrlChoice = radio(touchSec, strings.getString(R.string.touch_ctrl_button),
            ctrl.hideAfterStart() || ctrl.hideAll() ? 2 : ctrl.enabled() ? 1 : 0,
            strings.getString(R.string.touch_ctrl_always), strings.getString(R.string.touch_ctrl_tab),
            strings.getString(R.string.touch_ctrl_removed));

        // --- Frame rate and performance: 30 / 40 / 60 / 60 on the 120 Hz panel in one list, then the overlay.
        // 30 and 60 apply at once; 40 and the smoother 60 need the panel mode, set at the next start.
        String fps = render.getString("fps", RuntimeActivity.DEFAULT_FPS);
        boolean sixty = "60g".equals(fps) || "60".equals(fps);
        RadioButton fpsChoice = radio(rateSec, strings.getString(R.string.touch_frame_rate),
            "40".equals(fps) ? 1 : sixty ? (render.getBoolean("panel120", false) ? 3 : 2) : 0,
            strings.getString(R.string.touch_fps_30), strings.getString(R.string.touch_fps_40),
            strings.getString(R.string.touch_fps_60), strings.getString(R.string.touch_fps_60_smooth));
        hint(rateSec, strings.getString(R.string.touch_fps_help));
        CheckBox perfOverlay = option(rateSec, strings.getString(R.string.touch_perf_overlay),
            strings.getString(R.string.touch_perf_overlay_help), render.getBoolean("overlay", false));

        // --- Image: anti-aliasing at once; the upscaler and stretching from the next start.
        header(imageSec, strings.getString(R.string.touch_aa));
        Spinner aaChoice = spinner(imageSec, "fxaa".equals(render.getString("aa", "off")) ? 1 : 0,
            strings.getString(R.string.touch_aa_off), strings.getString(R.string.touch_aa_fxaa));
        final String[] upscales = { "bilinear", "fsr", "cas" };
        header(imageSec, strings.getString(R.string.touch_upscale));
        Spinner upscaleChoice = spinner(imageSec,
            java.util.Arrays.asList(upscales).indexOf(render.getString("upscale", "bilinear")),
            strings.getString(R.string.touch_upscale_bilinear), strings.getString(R.string.touch_upscale_fsr),
            strings.getString(R.string.touch_upscale_cas));
        // Public issue #6: the presenter's letterbox off, so the 16:9 picture fills a wider phone screen. Greyed out
        // and unticked on 16:9 screens, as DS2's Fill screen.
        CheckBox stretch = option(imageSec, strings.getString(R.string.touch_stretch),
            strings.getString(R.string.touch_stretch_help), render.getBoolean(GpuCompat.KEY_STRETCH, false));
        if (!wideScreen()) {
            stretch.setText(strings.getString(R.string.touch_stretch) + " " + strings.getString(R.string.touch_stretch_na));
            stretch.setChecked(false);
            stretch.setEnabled(false);
        }

        // --- Second screen (dual-screen devices only): schemata in battle, map in the field, fan art otherwise.
        CheckBox companion = screenSec == null ? null : option(screenSec, strings.getString(R.string.touch_companion),
            strings.getString(R.string.touch_companion_help), CompanionScreen.enabled(render));
        final CheckBox companionBox = companion;

        // --- Game: the game folder, then the cheats (public issue #1): one switch each, applied at once (Cheats.java).
        Button folderButton = pageButton(gameSec, strings.getString(R.string.touch_game_folder));
        header(gameSec, strings.getString(R.string.touch_section_cheats));
        int cheatBits = Cheats.saved(render);
        CheckBox[] cheatBoxes = new CheckBox[Cheats.ALL.length];
        for (int i = 0; i < Cheats.ALL.length; ++i)
            cheatBoxes[i] = option(gameSec, strings.getString(Cheats.ALL[i].label), null,
                (cheatBits & Cheats.ALL[i].bit) != 0);
        hint(gameSec, strings.getString(R.string.touch_cheats_help));

        // --- Troubleshooting: the GPU driver page, debug logging, the log zip, then Advanced.
        // Qualcomm and Mali devices: open the GPU driver page (download / switch Turnip or PanVK).
        Button driverButton = null;
        if (GpuDriverStore.supported()) {
            GpuDriverStore.Installed driver = GpuDriverStore.selectedDriver(activity);
            driverButton = pageButton(toolsSec, strings.getString(R.string.touch_gpu_driver,
                driver != null ? driver.metadata.name : strings.getString(R.string.touch_gpu_driver_system)));
        }
        // Diagnostics (the owner, 2026-11-11: off for regular play): read at start.
        CheckBox debugLog = option(toolsSec, strings.getString(R.string.touch_debug_log),
            strings.getString(R.string.touch_debug_log_help),
            render.getBoolean("debug_log", RuntimeActivity.DEFAULT_DEBUG_LOG));
        // A zip of lr.log through the system file picker, so a player can attach it to a bug report
        // from the phone (Android 11+ hides Android/data from file managers; public issue #4).
        Button logsButton = pageButton(toolsSec, strings.getString(R.string.touch_save_logs));
        // Advanced, closed until tapped: the exact shader math and the GPU compatibility (testing) switches, in every
        // build since the Adreno 6xx fix (v1.2.3), as in DS2.
        LinearLayout advanced = subsection(toolsSec, strings.getString(R.string.touch_advanced));
        // Xenos multiply emulation (0 x anything = 0) in the translated shaders: off by default since
        // 2026-11-11 (-12% GPU in Luxerion, no visible difference seen); the fallback if a scene glitches.
        CheckBox exactMath = option(advanced, strings.getString(R.string.touch_exact_shader_math),
            strings.getString(R.string.touch_exact_shader_math_help), render.getBoolean("exact_shader_math", false));
        // GPU compatibility (testing; public issue #4, Adreno 600 series): renderer paths to switch off
        // one at a time, read at the next start (GpuCompat).
        header(advanced, strings.getString(R.string.touch_section_compat));
        hint(advanced, strings.getString(R.string.touch_compat_help)).setPadding(0, 0, 0, Math.round(4f * density));
        CheckBox compatShortcuts = option(advanced, strings.getString(R.string.touch_compat_shortcuts),
            strings.getString(R.string.touch_compat_shortcuts_help), render.getBoolean(GpuCompat.KEY_SHORTCUTS, false));
        CheckBox compatShaders = option(advanced, strings.getString(R.string.touch_compat_shaders),
            strings.getString(R.string.touch_compat_shaders_help), render.getBoolean(GpuCompat.KEY_SHADERS, false));
        CheckBox compatGpl = option(advanced, strings.getString(R.string.touch_compat_gpl),
            strings.getString(R.string.touch_compat_gpl_help), render.getBoolean(GpuCompat.KEY_GPL, false));
        CheckBox compatDynamic = option(advanced, strings.getString(R.string.touch_compat_dynrender),
            strings.getString(R.string.touch_compat_dynrender_help),
            render.getBoolean(GpuCompat.KEY_DYNAMIC_RENDERING, false));
        // Turnip's own switches only where Turnip runs (Adreno); elsewhere the stored values stay as they are.
        boolean turnip = GpuDriverStore.vendor() == GpuDriverStore.Vendor.ADRENO;
        RadioButton turnipChoice = null;
        CheckBox noUbwc = null;
        if (turnip) {
            String turnipMode = render.getString(GpuCompat.KEY_TURNIP_MODE, GpuCompat.MODE_AUTO);
            turnipChoice = radio(advanced, strings.getString(R.string.touch_compat_tu_mode),
                GpuCompat.MODE_SYSMEM.equals(turnipMode) ? 1 : GpuCompat.MODE_GMEM.equals(turnipMode) ? 2 : 0,
                strings.getString(R.string.touch_compat_tu_auto), strings.getString(R.string.touch_compat_tu_sysmem),
                strings.getString(R.string.touch_compat_tu_gmem));
            noUbwc = option(advanced, strings.getString(R.string.touch_compat_tu_noubwc), null,
                render.getBoolean(GpuCompat.KEY_TURNIP_NO_UBWC, false));
        }
        final RadioButton turnipMode = turnipChoice;
        final CheckBox compatNoUbwc = noUbwc;

        scroll.addView(content);
        // The title row with Exit at its right, as in DS2.
        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleRow.setPadding(Math.round(24f * density), Math.round(18f * density), Math.round(12f * density), 0);
        TextView titleText = new TextView(activity);
        titleText.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle);
        titleText.setText(strings.getString(R.string.touch_settings_title));
        titleRow.addView(titleText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button exitButton = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        exitButton.setText(strings.getString(R.string.exit_game));
        exitButton.setAllCaps(false);
        exitButton.setTextColor(Color.rgb(255, 140, 120));
        titleRow.addView(exitButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                                                                   LinearLayout.LayoutParams.WRAP_CONTENT));
        Runnable applyTouch = () -> {
            int ctrlMode = radioChoice(ctrlChoice);
            applySettings(toggle.isChecked(), size.getProgress() + 60, opacity.getProgress() + 25,
                          ctrlMode != 0, false, ctrlMode == 2);
        };
        AlertDialog settingsDialog = new AlertDialog.Builder(activity)
            .setCustomTitle(titleRow)
            .setView(scroll)
            .setPositiveButton(strings.getString(R.string.touch_apply), (dialog, which) -> {
                applyTouch.run();
                SharedPreferences prefs = RuntimeActivity.renderPrefs(activity);
                final String nextStartBefore = RuntimeActivity.nextStartState(prefs);
                int rate = radioChoice(fpsChoice);
                prefs.edit().putBoolean("panel120", rate == 3)
                    .putBoolean("debug_log", debugLog.isChecked())
                    .putBoolean("exact_shader_math", exactMath.isChecked())
                    .putBoolean(GpuCompat.KEY_STRETCH, stretch.isChecked())
                    .putBoolean(GpuCompat.KEY_SHORTCUTS, compatShortcuts.isChecked())
                    .putBoolean(GpuCompat.KEY_SHADERS, compatShaders.isChecked())
                    .putBoolean(GpuCompat.KEY_GPL, compatGpl.isChecked())
                    .putBoolean(GpuCompat.KEY_DYNAMIC_RENDERING, compatDynamic.isChecked())
                    .putString(GpuCompat.KEY_TURNIP_MODE, turnipMode == null
                        ? render.getString(GpuCompat.KEY_TURNIP_MODE, GpuCompat.MODE_AUTO)
                        : radioChoice(turnipMode) == 1 ? GpuCompat.MODE_SYSMEM
                        : radioChoice(turnipMode) == 2 ? GpuCompat.MODE_GMEM : GpuCompat.MODE_AUTO)
                    .putBoolean(GpuCompat.KEY_TURNIP_NO_UBWC, compatNoUbwc == null
                        ? render.getBoolean(GpuCompat.KEY_TURNIP_NO_UBWC, false) : compatNoUbwc.isChecked())
                    .commit();  // on disk before a restart below
                activity.applyRenderSettings(rate == 1 ? "40" : rate >= 2 ? "60g" : "30",
                    aaChoice.getSelectedItemPosition() == 1 ? "fxaa" : "off",
                    upscales[upscaleChoice.getSelectedItemPosition()], perfOverlay.isChecked());
                if (companionBox != null) activity.applyCompanionSetting(companionBox.isChecked());
                int cheats = 0;
                for (int i = 0; i < cheatBoxes.length; ++i)
                    cheats = Cheats.with(cheats, Cheats.ALL[i].bit, cheatBoxes[i].isChecked());
                activity.applyCheats(cheats);
                if (!nextStartBefore.equals(RuntimeActivity.nextStartState(RuntimeActivity.renderPrefs(activity))))
                    activity.offerRestart();
            })
            .setNegativeButton(strings.getString(R.string.touch_cancel), null)
            .create();
        settingsDialog.setOnDismissListener(ignored -> {
            settingsToggle = null;
            controllerHint = null;
            settingsChoiceEdited = false;
            settingsDialogOpen = false;
            syncCtrlPin();
        });
        if (driverButton != null) {
            driverButton.setOnClickListener(v -> {
                settingsDialog.dismiss();
                activity.openGpuDriverPage();
            });
        }
        folderButton.setOnClickListener(v -> {
            settingsDialog.dismiss();
            activity.openGameFolderPage();
        });
        logsButton.setOnClickListener(v -> {
            settingsDialog.dismiss();
            activity.startLogExport();
        });
        // As the old dialog button did: the touch settings applied (so the editor shows them), then the editor.
        editLayoutButton.setOnClickListener(v -> {
            applyTouch.run();
            enterEditor();
            settingsDialog.dismiss();
        });
        exitButton.setOnClickListener(v -> {
            settingsDialog.dismiss();
            activity.confirmExit();
        });
        settingsDialogOpen = true;
        syncCtrlPin();
        settingsDialog.show();
    }

    private interface ProgressChanged { void update(int value); }

    private static SeekBar.OnSeekBarChangeListener seekListener(ProgressChanged listener) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                     boolean fromUser) {
                listener.update(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private void enterEditor() {
        clearTouches();
        draft = layout.copy();
        draft.clampAll(getWidth(), getHeight(), baseUnit * draft.size,
                       editorReservedBottom());
        editing = true;
        selected = -1;
        syncCtrlPin();
        publish();
    }

    private void leaveEditor(boolean save) {
        clearTouches();
        if (save) {
            layout = draft.copy();
            layout.clampAll(getWidth(), getHeight(), baseUnit * layout.size, 0f);
            saveLayout();
        }
        editing = false;
        draft = null;
        selected = -1;
        syncCtrlPin();
        publish();
    }

    private void resetDraft() {
        dragPointer = -1;
        float size = draft.size, opacity = draft.opacity;
        draft = TouchControlLayout.defaults(getWidth(), getHeight(), baseUnit * size);
        draft.size = size;
        draft.opacity = opacity;
        draft.clampAll(getWidth(), getHeight(), baseUnit * size, editorReservedBottom());
        selected = -1;
        invalidate();
    }

    private int toolbarAt(float x, float y) {
        if (Math.abs(y - getHeight() * .91f) > getHeight() * .052f) return -1;
        for (int i = 0; i < 4; ++i) {
            if (Math.abs(x - getWidth() * (.37f + i * .09f)) <= getWidth() * .043f)
                return i;
        }
        return -1;
    }

    private boolean onEditorTouch(MotionEvent event, int action, int index, int id) {
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = event.getX(index), y = event.getY(index);
            int toolbar = toolbarAt(x, y);
            if (toolbar >= 0) {
                if (toolbarPointer == -1) {
                    toolbarPointer = id;
                    toolbarPressed = toolbar;
                }
            } else if (dragPointer == -1) {
                // CTRL wins overlaps, as it does in play (#253).
                int element = onCtrl(x, y) ? TouchControlLayout.CTRL : elementAt(x, y, true);
                if (element == TouchControlLayout.CTRL) {
                    selected = element;
                    dragPointer = id;
                    dragOffsetX = settingX() - x;
                    dragOffsetY = settingY() - y;
                } else if (element >= 0) {
                    selected = element;
                    dragPointer = id;
                    dragOffsetX = px(element) - x;
                    dragOffsetY = py(element) - y;
                } else {
                    selected = -1;
                }
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (dragPointer != -1) {
                for (int i = 0; i < event.getPointerCount(); ++i) {
                    if (event.getPointerId(i) == dragPointer) {
                        if (selected == TouchControlLayout.CTRL)
                            draft.moveCtrl(event.getX(i) + dragOffsetX,
                                           event.getY(i) + dragOffsetY, getWidth(),
                                           getHeight(), unit(), editorReservedBottom());
                        else
                            draft.move(selected, event.getX(i) + dragOffsetX,
                                       event.getY(i) + dragOffsetY, getWidth(), getHeight(),
                                       unit(), editorReservedBottom());
                        invalidate();
                        break;
                    }
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            if (id == dragPointer) {
                dragPointer = -1;
                // CTRL dropped onto controls: move them clear so CTRL stays hittable.
                if (selected == TouchControlLayout.CTRL) {
                    draft.clampAll(getWidth(), getHeight(), unit(), editorReservedBottom());
                    invalidate();
                }
            }
            if (id == toolbarPointer) {
                int pressed = toolbarPressed;
                toolbarPointer = toolbarPressed = -1;
                if (pressed == toolbarAt(event.getX(index), event.getY(index))) {
                    if (pressed == 0) leaveEditor(true);
                    else if (pressed == 1) leaveEditor(false);
                    else if (pressed == 2) resetDraft();
                    else if (pressed == 3 && selected >= 0
                             && selected != TouchControlLayout.CTRL) {
                        dragPointer = -1;
                        draft.visible[selected] = !draft.visible[selected];
                        invalidate();
                    }
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            dragPointer = toolbarPointer = toolbarPressed = -1;
            return true;
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // A mouse click belongs to SDL's pointer path, not to the virtual pad.
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                && !event.isFromSource(InputDevice.SOURCE_STYLUS)) return false;
        if (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE) return false;
        if (layout == null) return false;
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int id = event.getPointerId(index);
        if (editing) return onEditorTouch(event, action, index, id);
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = event.getX(index), y = event.getY(index);
            long now = SystemClock.uptimeMillis();
            layoutCtrl(now);
            boolean onTab = ctrl.retracted() && ctrl.hitTab(x, y);
            if (ctrl.visible(now) && settingsPointer == -1 && (onTab
                    || (!ctrl.retracted() && onCtrl(x, y)))) {
                // A touch on the retracted tab only brings CTRL back.
                settingsPointer = id;
                settingsPointerRestores = onTab;
                ctrl.activity(now);
                syncCtrlPin();
                return true;
            }
            if (!controlsVisible()) return action == MotionEvent.ACTION_POINTER_DOWN;
            int element = elementAt(x, y, false);
            if (element < 0) return action == MotionEvent.ACTION_POINTER_DOWN;
            pointers.put(id, new Pointer(element, x, y));
            publish();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); ++i) {
                Pointer pointer = pointers.get(event.getPointerId(i));
                if (pointer != null) { pointer.x = event.getX(i); pointer.y = event.getY(i); }
            }
            publish();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            pointers.remove(id);
            if (id == settingsPointer) {
                boolean restoring = settingsPointerRestores;
                settingsPointer = -1;
                settingsPointerRestores = false;
                ctrl.activity(SystemClock.uptimeMillis());
                syncCtrlPin();
                if (!restoring && onCtrl(event.getX(index), event.getY(index))) {
                    performClick();
                    showSettings();
                    return true;
                }
            }
            publish();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            clearTouches();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopControllerWatch();
        cancelCtrlWake();
        clearTouches();
        super.onDetachedFromWindow();
    }

    private void color(int value, float opacity) {
        paint.setColor(Color.argb(Math.round(Color.alpha(value) * opacity),
                                  Color.red(value), Color.green(value), Color.blue(value)));
    }

    private void drawButton(Canvas canvas, float x, float y, float radius,
                            String label, boolean active, float opacity) {
        paint.setStyle(Paint.Style.FILL);
        color(active ? Color.argb(190, 70, 159, 214) : Color.argb(90, 0, 0, 0), opacity);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, unit() * .015f));
        color(Color.argb(210, 222, 239, 249), opacity);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(unit() * .26f);
        paint.setFakeBoldText(false);
        color(Color.WHITE, opacity);
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) * .5f, paint);
    }

    private void drawPill(Canvas canvas, float x, float y, String label,
                          boolean active, float opacity) {
        float halfWidth = unit() * .42f, halfHeight = unit() * .22f;
        paint.setStyle(Paint.Style.FILL);
        color(active ? Color.argb(190, 70, 159, 214) : Color.argb(90, 0, 0, 0), opacity);
        canvas.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth,
                             y + halfHeight, halfHeight, halfHeight, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, unit() * .015f));
        color(Color.argb(210, 222, 239, 249), opacity);
        canvas.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth,
                             y + halfHeight, halfHeight, halfHeight, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(unit() * .20f);
        paint.setFakeBoldText(true);
        color(Color.WHITE, opacity);
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) * .5f, paint);
    }

    private void drawStick(Canvas canvas, float x, float y, int axisX, int axisY,
                           float opacity) {
        float unit = unit();
        paint.setStyle(Paint.Style.FILL);
        color(Color.argb(72, 0, 0, 0), opacity);
        canvas.drawCircle(x, y, unit, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, unit * .015f));
        color(Color.argb(210, 222, 239, 249), opacity);
        canvas.drawCircle(x, y, unit, paint);
        color(Color.argb(92, 222, 239, 249), opacity);
        canvas.drawCircle(x, y, unit * .68f, paint);
        paint.setStyle(Paint.Style.FILL);
        color(axisX != 0 || axisY != 0
            ? Color.argb(220, 70, 159, 214) : Color.argb(180, 175, 185, 191), opacity);
        canvas.drawCircle(x + axisX / 32767f * unit * .55f,
                          y - axisY / 32767f * unit * .55f, unit * .40f, paint);
    }

    private void drawElement(Canvas canvas, int id) {
        TouchControlLayout controls = activeLayout();
        if (!editing && !controls.visible[id]) return;
        float opacity = editing ? (controls.visible[id] ? Math.max(.70f,
            controls.opacity) : .40f) : controls.opacity;
        float x = px(id), y = py(id), unit = unit();
        switch (id) {
            case TouchControlLayout.LEFT_STICK:
                drawStick(canvas, x, y, shownLx, shownLy, opacity); break;
            case TouchControlLayout.RIGHT_STICK:
                drawStick(canvas, x, y, shownRx, shownRy, opacity); break;
            case TouchControlLayout.DPAD:
                drawButton(canvas, x, y - unit * .855f, unit * .45f, "↑",
                           (shownButtons & UP) != 0, opacity);
                drawButton(canvas, x, y + unit * .855f, unit * .45f, "↓",
                           (shownButtons & DOWN) != 0, opacity);
                drawButton(canvas, x - unit * .855f, y, unit * .45f, "←",
                           (shownButtons & LEFT) != 0, opacity);
                drawButton(canvas, x + unit * .855f, y, unit * .45f, "→",
                           (shownButtons & RIGHT) != 0, opacity);
                break;
            case TouchControlLayout.BACK:
            case TouchControlLayout.START:
                drawPill(canvas, x, y, NAMES[id], (shownButtons & buttonMask(id)) != 0,
                         opacity);
                break;
            default:
                boolean active = id == TouchControlLayout.LT ? shownLt != 0
                    : id == TouchControlLayout.RT ? shownRt != 0
                    : (shownButtons & buttonMask(id)) != 0;
                drawButton(canvas, x, y, unit * .45f, NAMES[id], active, opacity);
                break;
        }
        if (editing && id == selected) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(3f, unit * .025f));
            color(Color.argb(230, 255, 195, 67), 1f);
            canvas.drawCircle(x, y, TouchControlLayout.hitRadius(id) * unit * 1.08f, paint);
        }
    }

    private void drawEditorToolbar(Canvas canvas) {
        boolean ctrlSelected = selected == TouchControlLayout.CTRL;
        String description = selected < 0 ? "Drag a control · tap to select"
            : ctrlSelected ? "CTRL · always shown"
            : NAMES[selected] + (draft.visible[selected] ? " · visible" : " · hidden");
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(18f, unit() * .16f));
        paint.setFakeBoldText(true);
        color(Color.argb(240, 255, 255, 255), 1f);
        canvas.drawText(description, getWidth() * .5f, getHeight() * .82f, paint);
        for (int i = 0; i < 4; ++i) {
            float x = getWidth() * (.37f + i * .09f);
            float y = getHeight() * .91f;
            float halfWidth = getWidth() * .043f;
            float halfHeight = getHeight() * .045f;
            paint.setStyle(Paint.Style.FILL);
            color(Color.argb(i == toolbarPressed ? 220 : 185, 22, 33, 43), 1f);
            canvas.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth,
                                 y + halfHeight, halfHeight * .45f,
                                 halfHeight * .45f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, unit() * .012f));
            color(Color.argb(220, 222, 239, 249), 1f);
            canvas.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth,
                                 y + halfHeight, halfHeight * .45f,
                                 halfHeight * .45f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(11f * getResources().getDisplayMetrics().density,
                Math.min(unit() * .18f, getWidth() * .019f)));
            paint.setFakeBoldText(true);
            color(Color.WHITE, i == 3 && (selected < 0 || ctrlSelected) ? .45f : 1f);
            String label = i == 0 ? "SAVE" : i == 1 ? "CANCEL" : i == 2 ? "RESET"
                : selected >= 0 && !ctrlSelected && !draft.visible[selected] ? "SHOW" : "HIDE";
            canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) * .5f, paint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (baseUnit <= 0f || activeLayout() == null) return;
        if (controlsVisible() || editing) {
            for (int id = 0; id < TouchControlLayout.COUNT; ++id) drawElement(canvas, id);
        }
        if (editing) {
            drawEditorCtrl(canvas);
            drawEditorToolbar(canvas);
        } else {
            drawCtrl(canvas);
        }
    }

    private void drawEditorCtrl(Canvas canvas) {
        float x = settingX(), y = settingY();
        drawButton(canvas, x, y, settingRadius(), "CTRL", false,
                   Math.max(.70f, draft.opacity));
        if (selected == TouchControlLayout.CTRL) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(3f, unit() * .025f));
            color(Color.argb(230, 255, 195, 67), 1f);
            canvas.drawCircle(x, y, TouchControlLayout.CTRL_HIT_RADIUS * unit() * 1.08f, paint);
        }
    }

    private void drawCtrl(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        if (!ctrl.visible(now)) return;
        float progress = layoutCtrl(now);
        float radius = settingRadius();
        // Follows the layout's opacity slider (#253), fainter as a retracted tab.
        float opacity = layout.opacity * ctrl.opacity;
        boolean active = settingsPointer != -1 && !settingsPointerRestores;
        // The circle slides past the edge; the screen (or, at the top, the
        // status-bar strip) clips what is beyond it.
        canvas.save();
        canvas.clipRect(0f, ctrl.clipTop, getWidth(), getHeight());
        paint.setStyle(Paint.Style.FILL);
        color(active ? Color.argb(190, 70, 159, 214) : Color.argb(90, 0, 0, 0), opacity);
        canvas.drawCircle(ctrl.drawX, ctrl.drawY, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, unit() * .015f));
        color(Color.argb(210, 222, 239, 249), opacity);
        canvas.drawCircle(ctrl.drawX, ctrl.drawY, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(unit() * .26f * ctrl.labelScale);
        paint.setFakeBoldText(progress > .5f);
        color(Color.WHITE, Math.min(1f, opacity * 1.5f));
        float baseline = ctrl.labelY - (paint.ascent() + paint.descent()) * .5f;
        if (ctrl.labelRotation != 0f) {
            canvas.save();
            canvas.rotate(ctrl.labelRotation, ctrl.labelX, ctrl.labelY);
            canvas.drawText("CTRL", ctrl.labelX, baseline, paint);
            canvas.restore();
        } else {
            canvas.drawText("CTRL", ctrl.labelX, baseline, paint);
        }
        canvas.restore();
        paint.setFakeBoldText(false);
        scheduleCtrlWake(now);
    }
}
