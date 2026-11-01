package tv.mcclock.dream;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;

/**
 * The menu sounds, in place of the TV's system clicks and ticks: Minecraft's button click
 * (assets/sounds/click.ogg, which fetch-sounds.sh downloads and build.sh bundles). The game has
 * no sound for moving through a menu, but on a remote you want to hear that a press did
 * something, as the system's tick told you, so a move plays the same sample quieter.
 *
 * One per process (main thread only). The pool is never released, so the click of a press that
 * starts an app still sounds. Without the file it logs that once and stays silent. Like Android's
 * own sounds these follow the TV's "Systemtöne" setting.
 */
final class Sounds {

    private static final String ASSET = "sounds/click.ogg";
    private static final float CLICK_VOLUME = 0.25f;  // the volume the game plays its UI sounds at
    private static final float MOVE_VOLUME = 0.12f;
    private static final int MAX_STREAMS = 4;         // key repeat overlaps them

    private static Sounds instance;

    /** The process's menu sounds (main thread only). The first call loads the sample. */
    static Sounds get(Context context) {
        if (instance == null) instance = new Sounds(context.getApplicationContext());
        return instance;
    }

    private final ContentResolver resolver;
    private final SoundPool pool;
    private int sample;
    private boolean loaded;  // until then, silence

    private Sounds(Context app) {
        resolver = app.getContentResolver();
        // Not USAGE_MEDIA: Music skips a track while isMusicActive() is true, so a click on the
        // media stream could put the music off by 30 s. For the same reason no audio focus: the
        // music shouldn't duck or stop for a click.
        pool = new SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener((p, id, status) -> {
            loaded = status == 0;
            if (!loaded) Log.w(ClockDream.TAG, "sounds: cannot load " + ASSET + ": " + status);
        });
        try (AssetFileDescriptor fd = app.getAssets().openFd(ASSET)) {
            sample = pool.load(fd, 1);
        } catch (IOException | RuntimeException e) {
            Log.w(ClockDream.TAG, "sounds: no " + ASSET + " (run fetch-sounds.sh and rebuild)", e);
        }
    }

    /** A button or entry was pressed. */
    void click() {
        play(CLICK_VOLUME);
    }

    /** The focus or the selection moved. */
    void move() {
        play(MOVE_VOLUME);
    }

    private void play(float volume) {
        if (!loaded) return;
        // Read each time, as Android's own sounds do: the setting can change while we run.
        if (Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) == 0) return;
        pool.play(sample, volume, volume, 1, 0, 1f);
    }
}
