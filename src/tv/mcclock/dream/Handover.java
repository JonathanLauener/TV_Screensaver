package tv.mcclock.dream;

/**
 * Lets the screensaver hand over to the home screen without a visible cut (main thread only).
 *
 * HOME during the screensaver: the system wakes the dream and starts the home screen under it,
 * in either order. The home screen claims the hand-over and waits in the screensaver's layout.
 * Once the dream has been woken it takes the background (same frame), and once that's on screen
 * it asks the dream to go. Then it slides into its own layout.
 *
 * A home screen that merely restarts under a running screensaver (the app below closed) waits
 * too, but never takes the picture or ends the screensaver: only a wake does.
 */
final class Handover {

    /** The home screen's side. */
    interface Home {
        /** The dream has been woken: take the background now. */
        void takeOver();

        /** The dream went without handing over (a key, or it timed out). */
        void dreamGone();
    }

    private static ClockDream dream;   // the screensaver on screen now, if any
    private static boolean woken;      // the system woke it (HOME, most likely)
    private static boolean finishing;  // it has called finish(): too late to claim
    private static Home home;          // the home screen waiting to take over
    private static boolean homeUnder;  // the home screen is asleep under the screensaver

    static void dreamStarted(ClockDream d) {
        dream = d;
        woken = finishing = false;
    }

    /** Woken by the system rather than a key: HOME may be on its way. */
    static void dreamWoken(ClockDream d) {
        if (dream != d || finishing) return;
        woken = true;
        if (home != null) home.takeOver();
    }

    static void dreamFinishing(ClockDream d) {
        if (dream == d) finishing = true;
    }

    static void dreamGone(ClockDream d) {
        if (dream != d) return;
        dream = null;
        homeUnder = false;  // the TV may wake into another app, with the home still stopped
        Home h = home;
        home = null;
        if (h != null) h.dreamGone();
    }

    /**
     * The home screen is coming up. True if a screensaver is showing that it should wait for:
     * it takes over now if {@link #dreamWoken()}, else on {@link Home#takeOver}, unless it gets
     * {@link Home#dreamGone} first.
     */
    static boolean claimForHome(Home h) {
        if (dream == null || finishing) return false;
        home = h;
        return true;
    }

    static boolean dreamWoken() {
        return dream != null && woken;
    }

    /** The home screen has gone to the back: it no longer waits. */
    static void release(Home h) {
        if (home == h) home = null;
    }

    static boolean claimed() {
        return home != null;
    }

    /** The home screen looks exactly like the screensaver now: the screensaver can go. */
    static void homeReady() {
        if (dream != null && woken) dream.handOver();
    }

    static boolean dreamShowing() {
        return dream != null;
    }

    /** The home screen went to sleep under the screensaver (true) or is back (false). */
    static void homeUnderDream(boolean under) {
        homeUnder = under;
    }

    /** What a key in the screensaver goes back to: the home screen, rather than another app. */
    static boolean homeUnderDream() {
        return homeUnder;
    }

    private Handover() {
    }
}
