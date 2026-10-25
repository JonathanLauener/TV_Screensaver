package tv.mcclock.dream.background;

import android.content.Context;
import android.view.View;

/**
 * What's drawn behind the clock and the apps. The screensaver and the home screen only talk to
 * this interface, so the background can be swapped (another video, a still image, a different
 * renderer) without touching the clock, the home screen or the transitions. Which one runs is
 * picked at build time ({@link Backgrounds}).
 *
 * There is one background per process, shared by every window (main thread only). Each window
 * (a host) has its own view of it, and the host that last called {@link #show} gets the picture.
 * When it moves to another host it carries on where it was (the video keeps the same decoder
 * and frame). That is how the screensaver hands over to the home screen without a cut: it hands
 * over the background.
 */
public interface Background {

    /** A window showing the background. */
    interface Host {
        /**
         * The background is on screen in this host's view. Answers every {@link #show}, once
         * the view can show it (a video: once the view's surface exists and a frame is on it).
         * Also called after the background restarted (after {@link #resume}, or recovering from
         * an error). Until then the view may be blank, so the host can keep it covered.
         */
        void onBackgroundShown();
    }

    /** A full-screen view for one host's window, meant to go at the back of its layout. */
    View createView(Context context, Host host);

    /**
     * This host wants the picture from now on: it moves here from whichever host had it. Call it
     * whenever the host (re)appears and has covered its view. If the host's view isn't ready
     * yet, the picture stays where it is until it is.
     */
    void show(Host host);

    /**
     * The host doesn't want the picture any more (its window is going, or went to the back):
     * stop drawing into its view now. The picture goes back to the host that asked for it
     * before, if any. A host that is stopped but still wants the picture (an activity under the
     * screensaver, about to take over) just doesn't call this.
     */
    void hide(Host host);

    /**
     * Frees what another app may need (the hardware video decoder) before starting it. The
     * views stay; {@link #resume} or the next {@link #show} starts it again.
     */
    void suspend();

    void resume();
}
