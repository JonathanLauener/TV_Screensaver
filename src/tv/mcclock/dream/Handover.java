package tv.mcclock.dream;

/**
 * Lets the screensaver hand over to the home screen without a visible cut (main thread only).
 *
 * HOME during the screensaver: the system wakes the dream and starts the home screen under it.
 * The home screen claims the hand-over, shows the same picture (clock centred, same video
 * frame), and once that's on screen it asks the dream to go. Then it slides into its own layout.
 */
final class Handover {

    private static ClockDream dream;   // the screensaver on screen now, if any
    private static boolean claimed;

    static void dreamStarted(ClockDream d) {
        dream = d;
        claimed = false;
    }

    static void dreamGone(ClockDream d) {
        if (dream != d) return;
        dream = null;
        claimed = false;
    }

    /** The home screen is coming up. True if a screensaver is showing and will wait for it. */
    static boolean claimForHome() {
        if (dream == null) return false;
        claimed = true;
        return true;
    }

    static boolean claimed() {
        return claimed;
    }

    /** The home screen looks exactly like the screensaver now: the screensaver can go. */
    static void homeReady() {
        if (dream != null) dream.finish();
    }

    private Handover() {
    }
}
