package tv.mcclock.dream;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import tv.mcclock.dream.background.Background;
import tv.mcclock.dream.background.Backgrounds;

/**
 * Screensaver: the background (see {@link Background}) with the time in the Minecraft font
 * centred on top. The home screen's idle mode looks exactly the same, so the two can hand over.
 */
public final class ClockDream extends DreamService implements Background.Host {

    static final String TAG = "McClock";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    /**
     * Woken by the system (HOME, most likely): how long to wait for the home screen to claim.
     * The home screen starts only once the app below has paused, which can take a while.
     */
    private static final long CLAIM_WAIT_MS = 2000;
    /** ...and, once claimed, the longest to wait for it to be ready. */
    private static final long HANDOVER_MAX_MS = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Background background;
    private View cover;
    private boolean wokenByInput;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);  // any key press wakes the TV
        setFullscreen(true);
        setScreenBright(true);
        // No fade-out: when the home screen takes over it's already showing the same picture.
        getWindow().setWindowAnimations(0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        background = Backgrounds.get(this);
        root.addView(background.createView(this, this), MATCH, MATCH);

        // Black until the background is on screen, then fades away.
        cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        root.addView(cover, MATCH, MATCH);

        root.addView(new PixelClockView(this), MATCH, MATCH);
        setContentView(root);
        background.show(this);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        Handover.dreamStarted(this);
        Music.get(this).hold(this);
    }

    @Override
    public void onDreamingStopped() {
        cleanUp();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        cleanUp();
        super.onDetachedFromWindow();
    }

    private void cleanUp() {
        handler.removeCallbacksAndMessages(null);
        if (background != null) background.hide(this);
        // Woken by a key: back to the app underneath, so the music goes now, unless that's the
        // home screen. Otherwise the home screen takes it over (HOME), or it fades out shortly
        // (the TV going to sleep).
        Music.get(this).release(this, wokenByInput && !Handover.homeUnderDream());
        Handover.dreamGone(this);
        // A home screen asleep under us kept its surface for a hand-over that isn't coming.
        if (background != null) background.recheckKeptSurfaces();
    }

    /** Ends the screensaver now. */
    void leave() {
        Handover.dreamFinishing(this);
        finish();
    }

    /**
     * The home screen shows the same picture underneath: hide our window first, so it shows
     * through, and only then go. Taking the window down while it's showing would flash black
     * for a moment (the video's black backdrop outlives the video by a frame or so).
     */
    void handOver() {
        Handover.dreamFinishing(this);
        View decor = getWindow().getDecorView();
        decor.setVisibility(View.INVISIBLE);
        decor.postOnAnimation(() -> decor.postOnAnimation(() -> decor.postOnAnimation(this::finish)));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        wokenByInput = true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        wokenByInput = true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onWakeUp() {
        // A key press in the screensaver: back to whatever was underneath, straight away.
        if (wokenByInput) {
            Log.i(TAG, "screensaver: woken by a key");
            leave();
            return;
        }
        Log.i(TAG, "screensaver: woken by the system" + (Handover.claimed() ? ", home waiting" : ""));
        // Woken by the system. For HOME it starts the home screen too; give it a moment to
        // claim the hand-over, then up to HANDOVER_MAX_MS to be ready (it calls leave()).
        Handover.dreamWoken(this);
        handler.postDelayed(() -> {
            if (Handover.claimed()) {
                handler.postDelayed(this::leave, HANDOVER_MAX_MS);
            } else {
                leave();
            }
        }, CLAIM_WAIT_MS);
    }

    @Override
    public void onBackgroundShown() {
        if (cover == null || cover.getVisibility() != View.VISIBLE || cover.getAlpha() < 1f) return;
        Anim.of(cover, View.ALPHA, 0f, 600).delay(50).then(() -> cover.setVisibility(View.GONE))
                .start();
    }
}
