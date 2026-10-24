package tv.mcclock.dream;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.hardware.display.DisplayManager;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;

/**
 * The one video player of the app, shared by the screensaver and the home screen (main thread
 * only). Windows attach their video surface; the one attached last gets the picture. Moving the
 * picture to another window keeps the same decoder running, so the screensaver can hand the
 * video to the home screen mid-frame and nobody sees a jump.
 */
final class VideoEngine implements LoopingVideo.Listener {

    interface Client {
        /** The first frame is on this client's surface (after it attached, or after a restart). */
        void onFirstFrame();
    }

    private static final String TAG = ClockDream.TAG;
    private static final String VIDEO_ASSET = "screensaver.webm";
    private static final long SWITCH_TIMEOUT_MS = 700;
    /** After this many MediaCodec failures, use MediaPlayer's own looping instead. */
    private static final int MAX_FAILURES = 2;

    private static VideoEngine instance;

    static VideoEngine get(Context context) {
        if (instance == null) instance = new VideoEngine(context.getApplicationContext());
        return instance;
    }

    private static final class Entry {
        final Client client;
        final Surface surface;

        Entry(Client client, Surface surface) {
            this.client = client;
            this.surface = surface;
        }
    }

    private final Context app;
    private final ArrayList<Entry> clients = new ArrayList<>();  // the last one is shown
    private LoopingVideo player;
    private MediaPlayer fallback;
    private Surface fallbackSurface;
    private int failures;

    private VideoEngine(Context app) {
        this.app = app;
    }

    /** Shows the video on this surface from now on. */
    void attach(Client client, Surface surface) {
        remove(client);
        clients.add(new Entry(client, surface));
        show();
    }

    /** When this returns, nothing draws to the client's surface any more. */
    void detach(Client client) {
        Entry e = find(client);
        if (e == null) return;
        boolean wasShown = e == top();
        clients.remove(e);
        if (!wasShown) return;
        if (clients.isEmpty()) {
            release();
        } else {
            show();
        }
    }

    /**
     * Frees the decoder (before starting another app, which may need it). Clients stay attached;
     * the next {@link #attach} or {@link #resume} starts the video again.
     */
    void release() {
        if (player != null) {
            player.stop();
            player = null;
        }
        if (fallback != null) {
            fallback.release();
            fallback = null;
            fallbackSurface = null;
        }
    }

    void resume() {
        if (!clients.isEmpty()) show();
    }

    private Entry top() {
        return clients.isEmpty() ? null : clients.get(clients.size() - 1);
    }

    private Entry find(Client client) {
        for (Entry e : clients) if (e.client == client) return e;
        return null;
    }

    private void remove(Client client) {
        Entry e = find(client);
        if (e != null) clients.remove(e);
    }

    /** Sends the picture to the top client's surface, starting the video if needed. */
    private void show() {
        Entry top = top();
        if (top == null) return;
        if (player != null) {
            if (player.setSurface(top.surface, SWITCH_TIMEOUT_MS)) return;
            Log.w(TAG, "restarting the video on the new surface");
            player.stop();
            player = null;
        }
        if (fallback != null) {
            if (fallbackSurface != top.surface) {
                fallback.setSurface(top.surface);
                fallbackSurface = top.surface;
            }
            return;
        }
        if (failures >= MAX_FAILURES) {
            startFallback(top.surface);
            return;
        }
        try {
            player = new LoopingVideo(app.getAssets().openFd(VIDEO_ASSET), top.surface,
                    refreshRate(), this);
            player.start();
        } catch (IOException e) {
            Log.e(TAG, "cannot open " + VIDEO_ASSET, e);
            failures = MAX_FAILURES;
            startFallback(top.surface);
        }
    }

    private float refreshRate() {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        return d == null ? 60f : d.getRefreshRate();
    }

    @Override
    public void onFirstFrame(LoopingVideo source) {
        Entry top = top();
        if (source == player && top != null && source.surface() == top.surface) {
            top.client.onFirstFrame();
        }
    }

    @Override
    public void onVideoError(LoopingVideo source, Exception error) {
        if (source != player) return;
        failures++;
        Log.w(TAG, "MediaCodec loop failed (" + failures + ")", error);
        player.stop();
        player = null;
        show();
    }

    private void startFallback(Surface surface) {
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor afd = app.getAssets().openFd(VIDEO_ASSET)) {
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.setSurface(surface);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnInfoListener((p, what, extra) -> {
                Entry top = top();
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && p == fallback
                        && top != null) {
                    top.client.onFirstFrame();
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "MediaPlayer error " + what + "/" + extra);
                return true;
            });
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.prepareAsync();
            fallback = mp;
            fallbackSurface = surface;
            Log.i(TAG, "playing with MediaPlayer fallback");
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "MediaPlayer fallback failed", e);
            mp.release();
        }
    }
}
