package com.lrrecomp.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.Choreographer;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * The bottom screen's drawing, kept away from the game: CompanionView renders in software
 * (lockCanvas, not the GPU the game is often bound on) on its own background thread, which pins
 * itself to the little cores (the game's threads run on the middle and prime cores). It redraws only
 * when what is shown changes (CompanionState.signature): nothing while Lightning stands still or a
 * cutscene plays, at most POLL_MS while she walks or a battle runs. The display hardware composes
 * the finished frame on the second panel.
 */
final class CompanionSurface extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "lrrecomp";
    private static final long POLL_MS = 100;
    private static final long POLL_IDLE_MS = 250;  // fan art (title, cutscenes, menus)

    private final CompanionView renderer;
    private final HandlerThread thread;
    private final Handler worker;
    private final String demo;
    private final long demoStart = System.currentTimeMillis();
    private final Object surfaceLock = new Object();
    private boolean hasSurface;  // under surfaceLock
    private boolean dirty = true;  // worker thread only
    private volatile boolean battle;
    private boolean running;
    // Follow map: frames at the display rate while it moves (worker thread only).
    private Choreographer choreographer;
    private boolean framing;
    private long lastChange;
    private final float[] pose = new float[4];
    private boolean pendingFrame;
    private long lastDraw;
    // Draw-time statistics for the log (every 10 s while drawing).
    private long statSince, statNanos, statMax;
    private int statFrames;
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running || !renderer.wantsAnimation()) {
                framing = false;
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (demo != null ? demoPose(now) : RuntimeActivity.nativeCompanionPose(pose)) {
                renderer.setPose(pose[0], pose[1], pose[2], pose[3]);
            }
            // Eased every display frame, drawn at most every 30 ms (~30 fps): smooth, and half the
            // little-core time of drawing every frame.
            if (renderer.animate(now)) {
                lastChange = now;
                pendingFrame = true;
            }
            if (pendingFrame && now - lastDraw >= 30) {
                pendingFrame = false;
                lastDraw = now;
                draw();
            }
            // Every display frame while anything moves; after 0.6 s of stillness, 20 checks a second.
            if (now - lastChange < 600) choreographer.postFrameCallback(this);
            else worker.postDelayed(() -> { if (running) choreographer.postFrameCallback(frame); }, 50);
        }
    };

    CompanionSurface(Context context, String demo) {
        super(context);
        this.demo = demo;
        renderer = new CompanionView(context);
        getHolder().setFormat(PixelFormat.OPAQUE);
        getHolder().addCallback(this);
        // Normal priority, pinned to the little cores (nativeCompanionPinLittleCores): Android's
        // background priority also put the thread in a power-limited group whose cores stayed at a low
        // clock (measured on the Thor: 63-134 ms per map frame, 75 ms for a plain picture copy).
        thread = new HandlerThread("LR companion", Process.THREAD_PRIORITY_DEFAULT);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> Log.i(TAG, "companion: drawing thread " + RuntimeActivity.nativeCompanionPinLittleCores()));
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            String line = demo != null ? demoLine() : RuntimeActivity.nativeCompanionState();
            CompanionState st = CompanionState.parse(line);
            battle = st.scene() == CompanionState.Scene.BATTLE;
            if (renderer.setState(st) || dirty) draw();
            if (renderer.wantsAnimation() && !framing) {
                if (choreographer == null) choreographer = Choreographer.getInstance();  // this looper's
                framing = true;
                lastChange = SystemClock.uptimeMillis();
                choreographer.postFrameCallback(frame);
            }
            worker.postDelayed(this, st.scene() == CompanionState.Scene.FANART ? POLL_IDLE_MS : POLL_MS);
        }
    };

    void start() {
        worker.post(() -> {
            running = true;
            worker.removeCallbacks(tick);
            worker.post(tick);
        });
    }

    /** Stops drawing and ends the thread (the Presentation is going away). */
    void stop() {
        worker.post(() -> running = false);
        thread.quitSafely();
    }

    // Drawn with Android's hardware canvas (GPU): measured on the Thor, software drawing on the little
    // cores took 60-135 ms per frame because the game's own unpinned threads keep those cores busy;
    // the bottom screen is a dozen textured quads, well under a millisecond of GPU per frame, and its
    // bitmaps (map tiles, HUD pieces, cached layers) upload once. Scene changes dip through dark
    // (FADE_MS), like the game's transitions. Worker thread only.
    private static final long FADE_MS = 320;
    private CompanionState.Scene lastScene;
    private long fadeStart;
    private final android.graphics.Paint fadePaint = new android.graphics.Paint();
    private final Runnable fadeStep = this::present;

    private void draw() {
        if (renderer.getWidth() <= 0 || renderer.getHeight() <= 0) return;
        CompanionState.Scene scene = renderer.scene();
        if (lastScene != null && scene != lastScene) fadeStart = SystemClock.uptimeMillis();
        lastScene = scene;
        present();
    }

    /** Renders the current picture into the surface (and the dark dip while a scene change fades in). */
    private void present() {
        long now = SystemClock.uptimeMillis();
        float t = fadeStart == 0 ? 1f : Math.min(1f, (now - fadeStart) / (float) FADE_MS);
        synchronized (surfaceLock) {
            if (!hasSurface) return;
            Canvas c = null;
            try {
                c = getHolder().getSurface().lockHardwareCanvas();
                long t0 = System.nanoTime();
                renderer.draw(c);
                if (t < 1f) {
                    float k = 1f - t * t * (3 - 2 * t);
                    fadePaint.setColor(android.graphics.Color.argb(Math.round(230 * k), 0, 0, 0));
                    c.drawRect(0, 0, renderer.getWidth(), renderer.getHeight(), fadePaint);
                }
                long dt = System.nanoTime() - t0;
                dirty = false;
                stat(dt, now);
            } catch (RuntimeException e) {
                Log.w(TAG, "companion: draw failed", e);
            } finally {
                if (c != null) {
                    try {
                        getHolder().getSurface().unlockCanvasAndPost(c);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        if (t < 1f) {
            worker.removeCallbacks(fadeStep);
            worker.postDelayed(fadeStep, 16);
        } else {
            fadeStart = 0;
        }
    }

    /** Recording time per frame (the GPU work follows asynchronously), logged every 10 s. */
    private void stat(long nanos, long nowMs) {
        statNanos += nanos;
        statMax = Math.max(statMax, nanos);
        statFrames++;
        if (statSince == 0) statSince = nowMs;
        if (nowMs - statSince >= 10000) {
            Log.i(TAG, String.format(java.util.Locale.US, "companion: %d frames in %.1f s, record avg %.1f ms, max %.1f ms (%s)",
                statFrames, (nowMs - statSince) / 1000f, statNanos / 1e6 / statFrames, statMax / 1e6, lastScene));
            statSince = nowMs;
            statNanos = statMax = 0;
            statFrames = 0;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (surfaceLock) {
            hasSurface = true;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        worker.post(() -> {
            renderer.layout(0, 0, width, height);
            dirty = true;
            draw();
        });
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Blocks until a draw in progress has posted: the surface must not be used after this returns.
        synchronized (surfaceLock) {
            hasSurface = false;
        }
    }

    // Pinch on the follow map zooms (remembered); a pinch never counts as a tap.
    private boolean pinched;
    private final android.view.ScaleGestureDetector pinch = new android.view.ScaleGestureDetector(getContext(),
        new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(android.view.ScaleGestureDetector d) {
                pinched = true;
                final float f = d.getScaleFactor();
                worker.post(() -> { if (renderer.zoom(f, false)) draw(); });
                return true;
            }

            @Override
            public void onScaleEnd(android.view.ScaleGestureDetector d) {
                worker.post(() -> renderer.zoom(1f, true));
            }
        });

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) pinched = false;
        if (e.getActionMasked() != MotionEvent.ACTION_UP || pinched) return true;
        if (battle) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        final float x = e.getX(), y = e.getY();
        worker.post(() -> {
            if (renderer.tap(x, y)) {
                dirty = true;
                draw();
            }
        });
        return true;
    }

    private boolean demoPose(long now) {
        if (!"map".equals(demo)) return false;
        double t = System.currentTimeMillis() - demoStart;
        pose[0] = (float) (-267.6 + 20 * Math.sin(t / 4000.0));
        pose[1] = 464.6f;
        pose[2] = (float) (t / 1500.0 % (2 * Math.PI));
        pose[3] = (float) (t / 2500.0 % (2 * Math.PI));
        return true;
    }

    private String demoLine() {
        long t = System.currentTimeMillis() - demoStart;
        switch (demo) {
            case "battle": return CompanionState.demoBattle(t);
            case "map": return "v=1;mode=1;navi=00010002:-315.030:267.431;worldx=" + (-267.6 + 20 * Math.sin(t / 4000.0))
                + ";worldz=464.6;heading=" + (t / 1500.0 % 6.283)
                + ";mk=mainquest:-180.0:520.0,quest:-300.0:430.0,shop:-250.0:470.0,inn:-262.0:452.0,warp:-330.0:500.0,save:-240.0:440.0"
                + ";quest=Reach the cathedral before 18:00;clock=21:07;day=1";
            default: return "v=1;mode=2";
        }
    }
}
