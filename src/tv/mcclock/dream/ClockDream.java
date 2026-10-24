package tv.mcclock.dream;

import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.IOException;

/**
 * Screensaver: the bundled video on a seamless loop, with the time in the Minecraft font
 * centred on top.
 */
public final class ClockDream extends DreamService
        implements SurfaceHolder.Callback, LoopingVideo.Listener {

    static final String TAG = "McClock";
    private static final String VIDEO_ASSET = "screensaver.webm";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private View cover;
    private Surface surface;
    private int restarts;          // MediaCodec loop restarts after errors this session
    private LoopingVideo player;   // gapless MediaCodec loop
    private MediaPlayer fallback;  // only used if the MediaCodec loop fails

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);  // any key press wakes the TV
        setFullscreen(true);
        setScreenBright(true);

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
    public void onDreamingStopped() {
        stopVideo();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopVideo();
        super.onDetachedFromWindow();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        startVideo();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopVideo();
        surface = null;
    }

    private void startVideo() {
        if (surface == null || player != null || fallback != null) return;
        try {
            float refreshRate = getWindowManager().getDefaultDisplay().getRefreshRate();
            player = new LoopingVideo(getAssets().openFd(VIDEO_ASSET), surface, refreshRate, this);
            player.start();
        } catch (IOException e) {
            Log.e(TAG, "cannot open " + VIDEO_ASSET, e);
            player = null;
            startFallback();
        }
    }

    private void stopVideo() {
        if (player != null) {
            player.stop();
            player = null;
        }
        if (fallback != null) {
            fallback.release();
            fallback = null;
        }
    }

    @Override
    public void onFirstFrame(LoopingVideo source) {
        if (source == player) reveal();
    }

    @Override
    public void onVideoError(LoopingVideo source, Exception error) {
        if (source != player) return;
        player.stop();
        player = null;
        if (restarts++ < 1) {
            // Decoders occasionally fail once (e.g. the TV reclaimed it); try a fresh one.
            Log.w(TAG, "MediaCodec loop failed, restarting it", error);
            startVideo();
        } else {
            Log.w(TAG, "MediaCodec loop failed again, falling back to MediaPlayer", error);
            startFallback();
        }
    }

    private void startFallback() {
        if (surface == null || fallback != null) return;
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor afd = getAssets().openFd(VIDEO_ASSET)) {
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.setSurface(surface);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) reveal();
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "MediaPlayer error " + what + "/" + extra);
                return true;
            });
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.prepareAsync();
            fallback = mp;
            Log.i(TAG, "playing with MediaPlayer fallback");
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "MediaPlayer fallback failed", e);
            mp.release();
        }
    }

    private void reveal() {
        if (cover == null || cover.getVisibility() != View.VISIBLE || cover.getAlpha() < 1f) return;
        cover.animate().alpha(0f).setStartDelay(50).setDuration(600)
                .withEndAction(() -> cover.setVisibility(View.GONE));
    }
}
