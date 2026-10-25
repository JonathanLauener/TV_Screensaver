package tv.mcclock.dream.background;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.hardware.display.DisplayManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.io.IOException;
import java.util.ArrayList;

/**
 * A bundled video on a seamless loop ({@link LoopingVideo}), decoded straight into each host's
 * SurfaceView. There is one decoder for all hosts: when the picture moves to another host, the
 * decoder's output moves to that host's surface, so it carries on from the same frame.
 */
final class VideoBackground implements Background, LoopingVideo.Listener {

    private static final String TAG = Backgrounds.TAG;
    private static final long SWITCH_TIMEOUT_MS = 700;
    /** After this many MediaCodec failures in a row, use MediaPlayer's own looping instead. */
    private static final int MAX_FAILURES = 2;
    /** After a failure, wait this long before trying again (another app may hold the decoder). */
    private static final long RETRY_MS = 1000;
    /** After the MediaPlayer fallback failed too, wait this long before starting over. */
    private static final long FALLBACK_RETRY_MS = 5000;
    /** MediaPlayer doesn't say when the first frame reached a new surface; assume by then. */
    private static final long FALLBACK_SWITCH_MS = 150;

    private static final class Entry {
        final Host host;
        Surface surface;   // null while the host's SurfaceView has none
        boolean wanted;    // show() was called and hide() hasn't been since
        boolean owed;      // the host is owed an onBackgroundShown

        Entry(Host host) {
            this.host = host;
        }
    }

    private final Context app;
    private final String asset;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::update;
    /** Wanted hosts in the order they last called show(); the last one with a surface is shown. */
    private final ArrayList<Entry> entries = new ArrayList<>();
    private LoopingVideo player;
    private MediaPlayer fallback;
    private Surface fallbackSurface;
    private boolean fallbackPlaying;
    private int failures;

    VideoBackground(Context app, String asset) {
        this.app = app;
        this.asset = asset;
    }

    @Override
    public View createView(Context context, Host host) {
        entry(host);
        SurfaceView view = new KeptSurfaceView(context);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.d(TAG, "surface created for " + name(host));
                Entry e = entry(host);
                e.surface = holder.getSurface();
                if (e.wanted) e.owed = true;
                update();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            /** When this returns, the decoder has let go of the surface. */
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.d(TAG, "surface destroyed for " + name(host));
                Entry e = find(host);
                if (e == null) return;
                e.surface = null;
                if (!e.wanted) entries.remove(e);  // hidden, so the decoder had already left it
                update();
            }
        });
        return view;
    }

    @Override
    public void show(Host host) {
        Entry e = entry(host);
        entries.remove(e);
        entries.add(e);
        e.wanted = true;
        e.owed = true;
        update();
    }

    @Override
    public void hide(Host host) {
        Entry e = find(host);
        if (e == null) return;
        e.wanted = false;
        e.owed = false;
        if (e.surface == null) entries.remove(e);
        update();
    }

    @Override
    public void suspend() {
        main.removeCallbacksAndMessages(null);
        failures = 0;
        if (player != null) {
            player.stop();
            player = null;
        }
        if (fallback != null) {
            fallback.release();
            fallback = null;
            fallbackSurface = null;
            fallbackPlaying = false;
        }
    }

    @Override
    public void resume() {
        update();
    }

    /**
     * Keeps its surface when its activity is stopped while the window stays on screen. Android 9
     * does that to every activity under a screensaver ("sleeping"), and a home screen there has
     * to take the picture before the screensaver goes, so it's already on screen when it does.
     * A window that is really hidden still loses the surface, through its visibility. The host
     * calls hide() if it doesn't want the picture while stopped, so no decoder runs for nothing.
     */
    private static final class KeptSurfaceView extends SurfaceView {
        KeptSurfaceView(Context context) {
            super(context);
        }

        /** Overrides the hidden SurfaceView.windowStopped(boolean): ignore the stop. */
        @SuppressWarnings("unused")
        public void windowStopped(boolean stopped) {
        }
    }

    private Entry find(Host host) {
        for (Entry e : entries) if (e.host == host) return e;
        return null;
    }

    private Entry entry(Host host) {
        Entry e = find(host);
        if (e == null) {
            e = new Entry(host);
            entries.add(0, e);  // not wanted yet: at the bottom
        }
        return e;
    }

    /** The entry that should have the picture: the last wanted one whose view has a surface. */
    private Entry target() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.wanted && e.surface != null) return e;
        }
        return null;
    }

    /** Puts the picture on the target's surface, starting or stopping the video as needed. */
    private void update() {
        main.removeCallbacks(retry);
        Entry top = target();
        if (top == null) {
            suspend();
            return;
        }
        if (player != null && !player.isRunning()) {
            // Its decoder failed and the error is on its way; stopping it drops that message.
            failures++;
            Log.w(TAG, "MediaCodec loop failed (" + failures + ")");
            player.stop();
            player = null;
            main.postDelayed(retry, RETRY_MS);
            return;
        }
        if (player != null) {
            if (player.surface() == top.surface) {
                if (top.owed && player.hasShownFrame()) announce(top);
                return;
            }
            Log.d(TAG, "moving the video to " + name(top.host));
            if (player.setSurface(top.surface, SWITCH_TIMEOUT_MS)) {
                top.owed = true;  // announced with the first frame on the new surface
                return;
            }
            Log.w(TAG, "restarting the video on the new surface");
            player.stop();
            player = null;
        }
        top.owed = true;
        if (fallback != null) {
            if (fallbackSurface != top.surface) {
                fallback.setSurface(top.surface);
                fallbackSurface = top.surface;
            }
            if (fallbackPlaying) {
                Host host = top.host;
                main.postDelayed(() -> {
                    Entry e = target();
                    if (e != null && e.host == host) announce(e);
                }, FALLBACK_SWITCH_MS);
            }
            return;
        }
        if (failures >= MAX_FAILURES) {
            startFallback(top.surface);
            return;
        }
        try {
            player = new LoopingVideo(app.getAssets().openFd(asset), top.surface,
                    refreshRate(), this);
            player.start();
        } catch (IOException e) {
            Log.e(TAG, "cannot open " + asset, e);
            failures = MAX_FAILURES;
            startFallback(top.surface);
        }
    }

    private static String name(Host host) {
        return host.getClass().getSimpleName();
    }

    private void announce(Entry e) {
        if (!e.owed) return;
        e.owed = false;
        e.host.onBackgroundShown();
    }

    private float refreshRate() {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        return d == null ? 60f : d.getRefreshRate();
    }

    @Override
    public void onFirstFrame(LoopingVideo source) {
        if (source != player) return;
        failures = 0;
        Entry top = target();
        if (top != null && source.surface() == top.surface && source.hasShownFrame()) announce(top);
    }

    @Override
    public void onVideoError(LoopingVideo source, Exception error) {
        if (source != player) return;
        failures++;
        Log.w(TAG, "MediaCodec loop failed (" + failures + ")", error);
        player.stop();
        player = null;
        // The last frame stays on screen meanwhile.
        main.postDelayed(retry, RETRY_MS);
    }

    private void startFallback(Surface surface) {
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor afd = app.getAssets().openFd(asset)) {
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.setSurface(surface);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && p == fallback) {
                    fallbackPlaying = true;
                    Entry top = target();
                    if (top != null && top.surface == fallbackSurface) announce(top);
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "MediaPlayer error " + what + "/" + extra);
                if (p == fallback) {
                    // Start over (with MediaCodec again) in a while; the last frame stays.
                    fallback.release();
                    fallback = null;
                    fallbackSurface = null;
                    fallbackPlaying = false;
                    failures = 0;
                    main.postDelayed(retry, FALLBACK_RETRY_MS);
                }
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
