package tv.mcclock.dream;

import android.animation.TimeInterpolator;
import android.util.Property;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;

/**
 * Animates one property of a view, frame by frame on the display's clock (main thread only).
 *
 * Not ViewPropertyAnimator: that follows the system's animation speed setting, and on this TV
 * it's set to off (tv-detox does that), which would make every slide and fade jump straight
 * to its end. The slides between the home screen and its idle mode are the point here.
 *
 * Starting an animation on a property cancels the one already running on it.
 */
final class Anim implements Choreographer.FrameCallback {

    interface Step {
        void onStep(View view);
    }

    private static final ArrayList<Anim> running = new ArrayList<>();

    private final View view;
    private final Property<View, Float> property;
    private final float to;
    private float from;
    private long durationNs, delayNs;
    private TimeInterpolator ease = new LinearInterpolator();
    private Runnable end;
    private Step step;
    private boolean layer;
    private long startNs = -1;

    private Anim(View view, Property<View, Float> property, float to, long durationMs) {
        this.view = view;
        this.property = property;
        this.to = to;
        this.durationNs = durationMs * 1_000_000;
    }

    static Anim of(View view, Property<View, Float> property, float to, long durationMs) {
        return new Anim(view, property, to, durationMs);
    }

    Anim delay(long ms) {
        delayNs = ms * 1_000_000;
        return this;
    }

    Anim ease(TimeInterpolator interpolator) {
        ease = interpolator;
        return this;
    }

    /** Runs when it reaches the end (not when cancelled). */
    Anim then(Runnable action) {
        end = action;
        return this;
    }

    /** Runs after each frame's change. */
    Anim onStep(Step s) {
        step = s;
        return this;
    }

    /** Draws the view into a hardware layer meanwhile (cheaper to move). */
    Anim withLayer() {
        layer = true;
        return this;
    }

    Anim start() {
        cancel(view, property);
        from = property.get(view);
        running.add(this);
        if (layer) view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        Choreographer.getInstance().postFrameCallback(this);
        return this;
    }

    /** Stops the animation of this property, leaving it where it is. */
    static void cancel(View view, Property<View, Float> property) {
        for (int i = running.size() - 1; i >= 0; i--) {
            Anim a = running.get(i);
            if (a.view == view && a.property == property) a.stop();
        }
    }

    /** Stops every animation of this view. */
    static void cancelAll(View view) {
        for (int i = running.size() - 1; i >= 0; i--) {
            Anim a = running.get(i);
            if (a.view == view) a.stop();
        }
    }

    private void stop() {
        running.remove(this);
        Choreographer.getInstance().removeFrameCallback(this);
        if (layer) view.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (startNs < 0) startNs = frameTimeNanos;
        long t = frameTimeNanos - startNs - delayNs;
        float f = durationNs <= 0 ? 1f : Math.max(0f, Math.min(1f, (float) t / durationNs));
        property.set(view, from + (to - from) * ease.getInterpolation(f));
        if (step != null) step.onStep(view);
        if (f < 1f) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        stop();
        if (end != null) end.run();
    }
}
