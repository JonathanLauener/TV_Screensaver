package tv.mcclock.dream.background;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;

/**
 * Picks the background the app was built with: build.sh bundles one file as
 * assets/background.&lt;ext&gt;, and its type decides what draws it.
 */
public final class Backgrounds {

    static final String TAG = "McClock";
    private static final String NAME = "background.";
    private static final String[] VIDEO = {"webm", "mp4", "mkv"};
    private static final String[] IMAGE = {"png", "jpg", "jpeg", "webp"};

    private static Background instance;

    /** The process's one background (main thread only). */
    public static Background get(Context context) {
        if (instance == null) instance = create(context.getApplicationContext());
        return instance;
    }

    private static Background create(Context app) {
        String asset = findAsset(app);
        String ext = asset == null ? "" : asset.substring(NAME.length()).toLowerCase(Locale.ROOT);
        if (contains(VIDEO, ext)) return new VideoBackground(app, asset);
        if (contains(IMAGE, ext)) return new ImageBackground(app, asset);
        Log.e(TAG, asset == null ? "no background bundled" : "unknown background " + asset);
        return new ImageBackground(app, null);  // black
    }

    private static String findAsset(Context app) {
        try {
            String[] names = app.getAssets().list("");
            if (names != null) {
                for (String n : names) if (n.startsWith(NAME)) return n;
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot list assets", e);
        }
        return null;
    }

    private static boolean contains(String[] list, String s) {
        for (String x : list) if (x.equals(s)) return true;
        return false;
    }

    private Backgrounds() {
    }
}
