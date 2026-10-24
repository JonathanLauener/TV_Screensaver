package tv.mcclock.dream;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The home screen: the screensaver's video and clock, with the clock lifted and the apps below.
 *
 * While it's in front it is also the screensaver: after the TV's screen saver delay without a
 * key press, the apps slide down off the screen and the clock down to the middle, which looks
 * exactly like {@link ClockDream}. Any key brings the home screen back. (It keeps the screen
 * on meanwhile, so the system's own screensaver doesn't start on top.)
 *
 * The app list, banners and HDMI inputs follow tv-detox's home screen (MIT, ~/tv-detox).
 */
public final class HomeActivity extends Activity
        implements SurfaceHolder.Callback, VideoEngine.Client {

    private static final int HOME = 0, AMBIENT = 1;
    // Layout in pixels at 1080p, scaled to the real screen by px().
    private static final int CLOCK_LIFT = 230;   // the clock's centre moves up by this at home
    private static final int PANEL_TOP = 560;
    private static final int TILE_W = 256, TILE_H = 144, TILE_GAP = 32, MARGIN = 96;
    private static final int ROW_GAP = 8, BUTTON_GAP = 24, SHADE_TOP = 380;
    private static final long MOVE_MS = 750;
    private static final long HANDOVER_MAX_MS = 3000;
    private static final int NO_KEY = -1;
    private static final String APPS_ASSET = "home_apps.txt";
    private static final String PLAY_STORE = "com.android.vending";
    private static final int MAX_INPUTS = 4;
    // Live TV apps an HDMI button can open, tried in order (from tv-detox).
    private static final String[][] LIVE_TV = {{"com.android.tv", "com.android.tv.MainActivity"},
            {"com.droidlogic.android.tv", "com.android.tv.TvActivity"}};
    private static final String INPUT_PLAYER = "com.google.android.tv.inputplayer";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable goAmbient = () -> setMode(AMBIENT, true);
    private final Runnable finishHandover = this::finishHandover;
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            buildTiles();
        }
    };

    private float scale;
    private View cover, shade;
    private PixelClockView clock;
    private LinearLayout panel, tilesRow, buttonsRow;
    private int mode = HOME;
    private boolean handingOver, readySent;
    private int swallowKey = NO_KEY;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scale = getResources().getDisplayMetrics().heightPixels / 1080f;
        int match = ViewGroup.LayoutParams.MATCH_PARENT, wrap = ViewGroup.LayoutParams.WRAP_CONTENT;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        SurfaceView video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, match, match);

        // Darkens the bottom a little behind the apps, so names and buttons stay readable.
        shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x99000000}));
        FrameLayout.LayoutParams shadeLp = new FrameLayout.LayoutParams(match, match);
        shadeLp.topMargin = px(SHADE_TOP);
        root.addView(shade, shadeLp);

        cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        root.addView(cover, match, match);

        clock = new PixelClockView(this);
        root.addView(clock, match, match);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(match, wrap);
        panelLp.topMargin = px(PANEL_TOP) - px(16);
        root.addView(panel, panelLp);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setFillViewport(true);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        tilesRow = new LinearLayout(this);
        tilesRow.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        tilesRow.setClipChildren(false);
        tilesRow.setClipToPadding(false);
        tilesRow.setPadding(px(MARGIN), px(16), px(MARGIN), 0);  // room for a focused tile to grow
        scroller.addView(tilesRow, new HorizontalScrollView.LayoutParams(match, wrap));
        panel.addView(scroller, new LinearLayout.LayoutParams(match, wrap));

        buttonsRow = new LinearLayout(this);
        buttonsRow.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(match, wrap);
        buttonsLp.topMargin = px(ROW_GAP);
        panel.addView(buttonsRow, buttonsLp);

        setContentView(root);
        buildTiles();
        setMode(HOME, false);

        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packagesChanged, filter);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Coming up under the screensaver (HOME was pressed): start from its exact picture.
        handingOver = Handover.claimForHome();
        readySent = false;
        // The video starts again on a new surface: black until its first frame.
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
        if (handingOver) {
            setMode(AMBIENT, false);
            handler.postDelayed(finishHandover, HANDOVER_MAX_MS);
        } else {
            setMode(HOME, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ambientEnabled()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (mode == HOME) restartIdle();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(goAmbient);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (mode == AMBIENT && !handingOver) setMode(HOME, true);  // HOME in idle mode
    }

    @Override
    public void onBackPressed() {
        // Nothing to go back to.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The screensaver has gone and we're on top: slide into the home screen.
        if (hasFocus && handingOver && readySent) finishHandover();
    }

    private void finishHandover() {
        handler.removeCallbacks(finishHandover);
        if (!handingOver) return;
        handingOver = false;
        cover.setVisibility(View.GONE);
        setMode(HOME, true);
    }

    // ---- Keys and the idle mode ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (mode == HOME) restartIdle();
            return super.dispatchKeyEvent(event);  // volume works without leaving idle mode
        }
        if (code == swallowKey) {  // the rest of the press that woke us up
            if (event.getAction() == KeyEvent.ACTION_UP) swallowKey = NO_KEY;
            return true;
        }
        if (mode == AMBIENT || handingOver) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                swallowKey = code;
                if (handingOver) {
                    finishHandover();
                } else {
                    setMode(HOME, true);
                }
            }
            return true;
        }
        restartIdle();
        return super.dispatchKeyEvent(event);
    }

    private void restartIdle() {
        handler.removeCallbacks(goAmbient);
        if (ambientEnabled()) handler.postDelayed(goAmbient, idleTimeoutMs());
    }

    /** Idle mode only when this app's screensaver is the one picked in the TV's settings. */
    private boolean ambientEnabled() {
        ContentResolver cr = getContentResolver();
        if (Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 0) return false;
        String picked = Settings.Secure.getString(cr, "screensaver_components");
        return picked != null && picked.startsWith(getPackageName() + "/");
    }

    private long idleTimeoutMs() {
        int ms = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                300_000);
        return Math.max(10_000, ms);
    }

    private void setMode(int newMode, boolean animate) {
        mode = newMode;
        boolean home = newMode == HOME;
        float clockY = home ? -px(CLOCK_LIFT) : 0;
        float panelY = home ? 0 : getResources().getDisplayMetrics().heightPixels - px(PANEL_TOP)
                + px(40);
        float shadeAlpha = home ? 1 : 0;
        clock.animate().cancel();
        panel.animate().cancel();
        shade.animate().cancel();
        if (animate) {
            PathInterpolator ease = new PathInterpolator(0.3f, 0f, 0.15f, 1f);
            // Going home the clock leads and the apps follow; going idle the apps leave first.
            clock.animate().translationY(clockY).setInterpolator(ease).setDuration(MOVE_MS)
                    .setStartDelay(home ? 0 : 120);
            panel.animate().translationY(panelY).setInterpolator(ease).setDuration(MOVE_MS)
                    .setStartDelay(home ? 90 : 0).withLayer();
            shade.animate().alpha(shadeAlpha).setDuration(MOVE_MS).setStartDelay(0);
        } else {
            clock.setTranslationY(clockY);
            panel.setTranslationY(panelY);
            shade.setAlpha(shadeAlpha);
        }
        if (home) {
            restartIdle();
        } else {
            handler.removeCallbacks(goAmbient);
        }
    }

    // ---- Video ----

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        VideoEngine.get(this).attach(this, holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        VideoEngine.get(this).detach(this);
    }

    @Override
    public void onFirstFrame() {
        if (handingOver) {
            // Same picture as the screensaver now. Let a couple of frames reach the screen,
            // then tell it to go; we slide on once we have the focus (onWindowFocusChanged).
            cover.setVisibility(View.GONE);
            cover.postOnAnimation(() -> cover.postOnAnimation(() -> {
                readySent = true;
                Handover.homeReady();
            }));
            return;
        }
        if (cover.getVisibility() != View.VISIBLE || cover.getAlpha() < 1f) return;
        cover.animate().alpha(0f).setDuration(400)
                .withEndAction(() -> cover.setVisibility(View.GONE));
    }

    // ---- Apps and buttons ----

    private void buildTiles() {
        tilesRow.removeAllViews();
        buttonsRow.removeAllViews();
        PackageManager pm = getPackageManager();
        int w = px(TILE_W), h = px(TILE_H);
        View first = null;
        for (String pkg : appList()) {
            Intent launch = launchIntent(pm, pkg);
            if (launch == null) continue;
            AppTile tile = new AppTile(this, banner(pm, pkg, launch, w, h), label(pm, pkg), w, h,
                    scale);
            tile.setOnClickListener(v -> start(launch));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (tilesRow.getChildCount() > 0) lp.leftMargin = px(TILE_GAP);
            tilesRow.addView(tile, lp);
            if (first == null) first = tile;
        }

        int bp = Math.max(1, Math.round(3 * scale));
        Intent store = launchIntent(pm, PLAY_STORE);
        if (store != null) addButton("Play Store", bp, v -> start(store));
        for (TvInputInfo input : hdmiInputs()) {
            String id = input.getId();
            addButton(inputLabel(input), bp, v -> openInput(id));
        }
        String settings = "de".equals(Locale.getDefault().getLanguage()) ? "Einstellungen" : "Settings";
        addButton(settings, bp, v -> start(new Intent(Settings.ACTION_SETTINGS)));

        if (first != null) first.requestFocus();
    }

    private void addButton(String label, int bp, View.OnClickListener click) {
        McButton b = new McButton(this, label, bp);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (buttonsRow.getChildCount() > 0) lp.leftMargin = px(BUTTON_GAP);
        buttonsRow.addView(b, lp);
    }

    /** assets/home_apps.txt, in order: one package per line, # for comments. */
    private List<String> appList() {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(APPS_ASSET)))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && !out.contains(line)) out.add(line);
            }
        } catch (IOException ignored) {
        }
        out.remove(getPackageName());
        return out;
    }

    /** Starts something on top, after freeing the video decoder for it. */
    private void start(Intent intent) {
        VideoEngine.get(this).release();
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            VideoEngine.get(this).resume();
        }
    }

    // Opens an HDMI input: the stock Live TV app, then Droidlogic's, then whatever the TV picks
    // (tv-detox's order; on Sony the last one opens Sony's TV app).
    private void openInput(String id) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        List<Intent> tries = new ArrayList<>();
        ResolveInfo def = getPackageManager().resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && INPUT_PLAYER.equals(def.activityInfo.packageName)) {
            tries.add(new Intent(view).setClassName(INPUT_PLAYER, def.activityInfo.name));
        }
        for (String[] live : LIVE_TV) {
            Intent t = new Intent(view).setClassName(live[0], live[1]);
            if (t.resolveActivity(getPackageManager()) != null) tries.add(t);
        }
        tries.add(view);
        VideoEngine.get(this).release();
        for (Intent t : tries) {
            try {
                startActivity(t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
            }
        }
        VideoEngine.get(this).resume();
    }

    /** The TV's own HDMI inputs (not devices behind them), in order, skipping hidden ones. */
    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo in : tim.getTvInputList()) {
            if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null && !in.isHidden(this)) {
                out.add(in);
            }
        }
        Collections.sort(out, (a, b) -> a.getId().compareTo(b.getId()));
        return out.size() > MAX_INPUTS ? out.subList(0, MAX_INPUTS) : out;
    }

    /** The name set in Settings > Inputs (like "Bedroom"), else the TV's own (like "HDMI 1"). */
    private String inputLabel(TvInputInfo in) {
        CharSequence custom = in.loadCustomLabel(this);
        if (custom != null && custom.length() > 0) return custom.toString();
        CharSequence label = in.loadLabel(this);
        return label != null && label.length() > 0 ? label.toString() : "HDMI";
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /**
     * The app's TV banner drawn once into a tile-sized bitmap (centre-cropped; some apps ship
     * huge banners Android 9 refuses to draw). Apps without one get their icon on a dark tile.
     */
    private Bitmap banner(PackageManager pm, String pkg, Intent launch, int w, int h) {
        Drawable d = null;
        boolean isBanner = true;
        try {
            d = pm.getActivityBanner(launch);
            if (d == null) d = pm.getApplicationBanner(pkg);
            if (d == null) {
                d = pm.getApplicationIcon(pkg);
                isBanner = false;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (d == null) return null;
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            if (isBanner) {
                int iw = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : w;
                int ih = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : h;
                float s = Math.max((float) w / iw, (float) h / ih);
                int dw = Math.round(iw * s), dh = Math.round(ih * s);
                d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            } else {
                c.drawColor(0xFF2B2B2B);
                int size = h * 3 / 5;
                d.setBounds((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2);
            }
            d.draw(c);
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private int px(int at1080p) {
        return Math.round(at1080p * scale);
    }
}
