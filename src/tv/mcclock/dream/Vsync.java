package tv.mcclock.dream;

import android.view.Choreographer;

/**
 * The display's refresh times, so each video frame can be aimed at one particular refresh.
 * start() and stop() run on the main thread; closest() is safe from any thread.
 */
final class Vsync implements Choreographer.FrameCallback {

    private static final long SAMPLE_EVERY_MS = 100;

    final long periodNs;
    private volatile long anchorNs;  // System.nanoTime() of a recent refresh, 0 until seen
    private Choreographer choreographer;

    Vsync(float refreshRate) {
        periodNs = (long) (1e9 / (refreshRate >= 10 ? refreshRate : 60));
    }

    void start() {
        choreographer = Choreographer.getInstance();
        choreographer.postFrameCallback(this);
    }

    void stop() {
        if (choreographer != null) choreographer.removeFrameCallback(this);
    }

    boolean known() {
        return anchorNs != 0;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (anchorNs == 0) {
            anchorNs = frameTimeNanos;
        } else {
            // Single timestamps jitter by a millisecond or two on some TVs, so only move the
            // refresh grid an eighth of the way towards each one.
            long predicted = closest(frameTimeNanos);
            anchorNs = predicted + (frameTimeNanos - predicted) / 8;
        }
        choreographer.postFrameCallbackDelayed(this, SAMPLE_EVERY_MS);
    }

    /** The refresh closest to timeNs, or timeNs itself until a refresh has been seen. */
    long closest(long timeNs) {
        long anchor = anchorNs;
        if (anchor == 0) return timeNs;
        return anchor + Math.floorDiv(timeNs - anchor + periodNs / 2, periodNs) * periodNs;
    }
}
