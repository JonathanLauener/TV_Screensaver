package tv.mcclock.dream;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The soundtrack. While the screensaver or the home screen shows, the tracks build.sh bundles
 * (assets/music/) play in random order the way the game's title-screen music does: none twice
 * before all have played, with a pause of 1 to 30 seconds between them.
 *
 * One per process, held by whichever screens want it (main thread only), so the screensaver's
 * hand-over to the home screen doesn't interrupt it. It keeps out of the way: a track only
 * starts when nothing else is playing and it gets the audio focus, it stops when another app
 * takes the focus, waits while something has it for a moment (voice search) and ducks under
 * short sounds. It keeps the focus between tracks, so it hears about those in the pauses too.
 */
final class Music implements AudioManager.OnAudioFocusChangeListener {

    private static final String DIR = "music";
    private static final long FIRST_MS = 3000;  // after a screen asks for it
    // The game's menu music waits 20 to 600 ticks (1 to 30 s) before the next track.
    private static final long GAP_MIN_MS = 1000, GAP_MAX_MS = 30_000;
    private static final long BUSY_RETRY_MS = 30_000;  // something else is playing: ask again
    /** Time for the next screen to take it over when one lets go (screensaver to home). */
    private static final long GRACE_MS = 2000;
    private static final long FADE_MS = 1000, FADE_QUICK_MS = 300, FADE_STEP_MS = 40;
    private static final float DUCKED = 0.2f;

    private static Music instance;

    /** The process's soundtrack (main thread only). */
    static Music get(Context context) {
        if (instance == null) instance = new Music(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    private final List<String> tracks;
    private final ArrayList<String> bag = new ArrayList<>();  // still to play this round
    private final Random random = new Random();
    private final ArrayList<Object> holders = new ArrayList<>();
    private final Runnable next = this::playNext;
    private final Runnable letGo = () -> stop(FADE_MS);
    private AudioFocusRequest focusRequest;
    private MediaPlayer player;
    private String last;
    private boolean scheduled;           // the next track is on its way
    private boolean prepared;            // the player can start
    private boolean focused, ducked;
    private boolean interrupted;         // another app has the audio for a moment
    private float level = 1f;            // the fade, 0 to 1
    private Runnable fade;

    private Music(Context app) {
        this.app = app;
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        String[] names = null;
        try {
            names = app.getAssets().list(DIR);
        } catch (IOException e) {
            Log.e(ClockDream.TAG, "music: cannot list assets", e);
        }
        tracks = names == null ? Collections.<String>emptyList() : Arrays.asList(names);
        Log.i(ClockDream.TAG, "music: " + tracks.size() + " tracks");
    }

    /** This screen is showing and wants the music (again). */
    void hold(Object owner) {
        if (!holders.contains(owner)) holders.add(owner);
        handler.removeCallbacks(letGo);
        if (player != null) {
            if (fade != null) fadeTo(1f, FADE_QUICK_MS, null);  // it was on its way out
        } else if (!scheduled) {
            schedule(FIRST_MS);
        }
    }

    /**
     * This screen doesn't want it any more. Once no screen does, it fades out: right away if an
     * app is about to start (leaving), otherwise after a moment, in case another screen of ours
     * takes over.
     */
    void release(Object owner, boolean leaving) {
        if (!holders.remove(owner) || !holders.isEmpty()) return;  // not a holder, or not the last
        handler.removeCallbacks(letGo);
        if (leaving) {
            stop(FADE_QUICK_MS);
        } else {
            handler.postDelayed(letGo, GRACE_MS);
        }
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(next);
        handler.postDelayed(next, delayMs);
        scheduled = true;
    }

    private long gap() {
        return GAP_MIN_MS + (long) (random.nextDouble() * (GAP_MAX_MS - GAP_MIN_MS));
    }

    /** The next track from the bag; a new round starts with any track but the last one. */
    private String pick() {
        if (bag.isEmpty()) {
            bag.addAll(tracks);
            Collections.shuffle(bag, random);
            if (bag.size() > 1 && bag.get(0).equals(last)) Collections.swap(bag, 0, bag.size() - 1);
        }
        last = bag.remove(0);
        return last;
    }

    private void playNext() {
        scheduled = false;
        if (holders.isEmpty() || tracks.isEmpty() || player != null || audio == null) return;
        if (interrupted) return;  // the focus coming back starts it
        if (audio.isMusicActive() || !requestFocus()) {
            schedule(BUSY_RETRY_MS);  // another app is playing: not over it
            return;
        }
        String track = pick();
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor fd = app.getAssets().openFd(DIR + "/" + track)) {
            mp.setAudioAttributes(attributes);
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
        } catch (IOException | RuntimeException e) {
            Log.e(ClockDream.TAG, "music: cannot open " + track, e);
            mp.release();
            abandonFocus();
            schedule(gap());
            return;
        }
        player = mp;
        prepared = false;
        level = 1f;
        applyVolume();
        mp.setOnPreparedListener(p -> {
            if (p != player) return;
            prepared = true;
            if (interrupted) return;  // it starts when the focus comes back
            p.start();
            Log.i(ClockDream.TAG, "music: playing " + track);
        });
        mp.setOnCompletionListener(this::trackOver);
        mp.setOnErrorListener((p, what, extra) -> {
            Log.w(ClockDream.TAG, "music: error " + what + "/" + extra + " in " + track);
            trackOver(p);
            return true;
        });
        mp.prepareAsync();
    }

    /** A track ended (or failed): a pause, then the next one. */
    private void trackOver(MediaPlayer p) {
        if (p != player) return;
        releasePlayer();
        if (holders.isEmpty()) {
            abandonFocus();
        } else {
            schedule(gap());
        }
    }

    /** Fades out and stops; no next track until a screen holds it again. */
    private void stop(long fadeMs) {
        handler.removeCallbacks(next);
        scheduled = false;
        if (player == null) {
            abandonFocus();
            return;
        }
        fadeTo(0f, fadeMs, () -> {
            releasePlayer();
            abandonFocus();
        });
    }

    private void releasePlayer() {
        cancelFade();
        if (player == null) return;
        MediaPlayer p = player;
        player = null;
        prepared = false;
        p.release();
    }

    // ---- Volume ----

    private void fadeTo(float target, long durationMs, Runnable done) {
        cancelFade();
        float from = level;
        long start = SystemClock.uptimeMillis();
        fade = new Runnable() {
            @Override
            public void run() {
                float t = Math.min(1f, (SystemClock.uptimeMillis() - start) / (float) durationMs);
                level = from + (target - from) * t;
                applyVolume();
                if (t < 1f) {
                    handler.postDelayed(this, FADE_STEP_MS);
                    return;
                }
                fade = null;
                if (done != null) done.run();
            }
        };
        fade.run();
    }

    private void cancelFade() {
        if (fade != null) handler.removeCallbacks(fade);
        fade = null;
    }

    private void applyVolume() {
        if (player == null) return;
        float v = level * (ducked ? DUCKED : 1f);
        player.setVolume(v, v);
    }

    // ---- Audio focus ----

    @SuppressWarnings("deprecation")  // the pre-26 calls, for API 24-25
    private boolean requestFocus() {
        if (focused) return true;
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attributes)
                        .setOnAudioFocusChangeListener(this, handler)
                        .build();
            }
            result = audio.requestAudioFocus(focusRequest);
        } else {
            result = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        focused = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focused;
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        ducked = interrupted = false;
        if (!focused) return;
        focused = false;
        if (Build.VERSION.SDK_INT >= 26) {
            audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (!focused) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                ducked = false;
                applyVolume();
                if (interrupted) {
                    interrupted = false;
                    if (player != null) {
                        if (prepared) player.start();
                    } else if (!holders.isEmpty() && !scheduled) {
                        schedule(FIRST_MS);
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                ducked = true;
                applyVolume();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                interrupted = true;
                if (player != null && prepared && player.isPlaying()) player.pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Another app plays now: stop, and try again later only if it's quiet then.
                Log.i(ClockDream.TAG, "music: another app took the audio");
                releasePlayer();
                abandonFocus();
                if (!holders.isEmpty()) schedule(BUSY_RETRY_MS);
                break;
            default:
                break;
        }
    }
}
