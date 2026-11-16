package com.lrrecomp.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * "Loading…" over the game's surface from the start until the game shows something: while the
 * runtime loads the shader storage at boot nothing is drawn (~40 s on a first start with the
 * bundled shader cache, ShaderSeed), and a black screen that long looks like a hang. It goes when
 * {@code RuntimeActivity.nativeBootProgress()} reports something on screen: the shader screen at
 * the title (the game's own progress from there), or the boot logos when nothing is compiled.
 */
final class BootOverlay extends FrameLayout {
    private static final long POLL_MS = 250, SLOW_HINT_MS = 8000, FADE_MS = 300;
    private static final int GOLD = 0xFFE9C46A;

    private final TextView title;
    private final TextView hint;
    private final long started = SystemClock.uptimeMillis();
    private volatile boolean firstStart;
    private boolean gone;

    BootOverlay(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setClickable(false);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(32);
        column.setPadding(dp(48), pad, dp(48), pad);

        ProgressBar spinner = new ProgressBar(context);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
        column.addView(spinner, new LinearLayout.LayoutParams(dp(56), dp(56)));

        title = new TextView(context);
        title.setText("Loading…");
        title.setTextColor(0xFFF0DC6E);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(18), 0, 0);
        column.addView(title, fullWidth());

        hint = new TextView(context);
        hint.setTextColor(0xFFB9B2A8);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, 0);
        hint.setVisibility(GONE);
        column.addView(hint, fullWidth());

        // Full width: wrap_content inside the SDL layout measured the column at the spinner's width.
        addView(column, new FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        postDelayed(this::poll, POLL_MS);
    }

    /** The bundled shader cache was just installed (ShaderSeed): say why this start is slow. */
    void setFirstStart() {
        firstStart = true;
    }

    private void poll() {
        if (gone) return;
        int progress;
        try {
            progress = RuntimeActivity.nativeBootProgress();
        } catch (UnsatisfiedLinkError e) {
            progress = 0;  // The library is not loaded yet.
        }
        if (progress >= 2) {
            gone = true;
            animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
                if (getParent() instanceof android.view.ViewGroup)
                    ((android.view.ViewGroup) getParent()).removeView(this);
            }).start();
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - started;
        if (firstStart && hint.getVisibility() != VISIBLE) {
            hint.setText("First start after installing or updating: loading the game's shaders. This "
                + "takes about a minute; later starts are quick.");
            hint.setVisibility(VISIBLE);
        } else if (!firstStart && elapsed > SLOW_HINT_MS && hint.getVisibility() != VISIBLE) {
            hint.setText("The first start after installing, an update or a GPU driver change takes longer.");
            hint.setVisibility(VISIBLE);
        }
        postDelayed(this::poll, POLL_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        gone = true;
        super.onDetachedFromWindow();
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
