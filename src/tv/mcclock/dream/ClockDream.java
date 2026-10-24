package tv.mcclock.dream;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Screensaver: the bundled video on a seamless loop, with the time in the Minecraft font
 * centred on top. The home screen's idle mode looks exactly the same, so the two can hand over.
 */
public final class ClockDream extends DreamService
        implements SurfaceHolder.Callback, VideoEngine.Client {

    static final String TAG = "McClock";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    /** Woken by the system (HOME, most likely): how long to wait for the home screen to claim. */
    private static final long CLAIM_WAIT_MS = 500;
    /** ...and, once claimed, the longest to wait for it to be ready. */
    private static final long HANDOVER_MAX_MS = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
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

        SurfaceView video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, MATCH, MATCH);

        // Black until the first video frame is on screen, then fades away.
        cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        root.addView(cover, MATCH, MATCH);

        root.addView(new PixelClockView(this), MATCH, MATCH);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        Handover.dreamStarted(this);
    }

    @Override
    public void onDreamingStopped() {
        stopVideo();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopVideo();
        super.onDetachedFromWindow();
    }

    private void stopVideo() {
        handler.removeCallbacksAndMessages(null);
        VideoEngine.get(this).detach(this);
        Handover.dreamGone(this);
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
            finish();
            return;
        }
        // Woken by the system. For HOME it starts the home screen next; give it a moment to
        // claim the hand-over, then up to HANDOVER_MAX_MS to be ready (it calls finish()).
        handler.postDelayed(() -> {
            if (Handover.claimed()) {
                handler.postDelayed(this::finish, HANDOVER_MAX_MS);
            } else {
                finish();
            }
        }, CLAIM_WAIT_MS);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        VideoEngine.get(this).attach(this, holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        VideoEngine.get(this).detach(this);
    }

    @Override
    public void onFirstFrame() {
        if (cover == null || cover.getVisibility() != View.VISIBLE || cover.getAlpha() < 1f) return;
        cover.animate().alpha(0f).setStartDelay(50).setDuration(600)
                .withEndAction(() -> cover.setVisibility(View.GONE));
    }
}
