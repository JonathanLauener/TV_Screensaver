package tv.mcclock.dream;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tv.mcclock.dream.background.Background;
import tv.mcclock.dream.background.Backgrounds;

/**
 * The home screen: the screensaver's background and clock, with the clock lifted and the apps
 * below.
 *
 * While it's in front it is also the screensaver: after the TV's screen saver delay without a
 * key press, the apps slide down off the screen and the clock down to the middle, which looks
 * exactly like {@link ClockDream}. Any key brings the home screen back. (It keeps the screen
 * on meanwhile, so the system's own screensaver doesn't start on top.)
 *
 * Below the apps, Quelle and Einstellungen open menus over it that look like Minecraft's
 * server list ({@link McMenu}): the TV's inputs, and shortcuts into the TV's settings.
 *
 * The app list, banners and HDMI inputs follow tv-detox's home screen (MIT, ~/tv-detox).
 */
public final class HomeActivity extends Activity implements Background.Host, Handover.Home,
        McMenu.Host {

    private static final int HOME = 0, AMBIENT = 1;
    // Layout in pixels at 1080p, scaled to the real screen by px().
    private static final int CLOCK_LIFT = 250;   // the clock's centre moves up by this at home
    private static final int PANEL_TOP = 500;    // top of the first row of banners
    private static final int TILE_W = 256, TILE_H = 144, TILE_GAP = 32, MARGIN = 96;
    private static final int TILE_ROW_GAP = 16;  // below a row's caption space
    private static final int ROW_GAP = 24, BUTTON_GAP = 24, SHADE_TOP = 340;
    private static final int GROW_ROOM = 16;     // above the first row, for a focused banner
    private static final long MOVE_MS = 750;
    private static final long MENU_MS = 150;     // the menus fade in and out this fast
    private static final long HANDOVER_MAX_MS = 3000;
    /**
     * Android 9 stops us under the screensaver; once stopped our window can't get its video
     * surface any more, so if the picture hasn't arrived by this long after, it never will.
     */
    private static final long STOPPED_GRACE_MS = 1000;
    private static final int NO_KEY = -1;
    private static final String APPS_ASSET = "home_apps.txt";
    private static final String PLAY_STORE = "com.android.vending";
    // Live TV apps an HDMI button can open, tried in order (from tv-detox).
    private static final String[][] LIVE_TV = {{"com.android.tv", "com.android.tv.MainActivity"},
            {"com.droidlogic.android.tv", "com.android.tv.TvActivity"}};
    private static final String INPUT_PLAYER = "com.google.android.tv.inputplayer";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable goAmbient = () -> setMode(AMBIENT, true);
    private final Runnable finishHandover = this::finishHandover;
    private final Runnable allowSleep = this::releaseScreen;
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // An update sends REMOVED then ADDED: rebuild once, on ADDED.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return;
            }
            String pkg = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
            if (PLAY_STORE.equals(pkg) || appList().contains(pkg)) buildTiles();
        }
    };

    private float scale;
    private PowerManager.WakeLock screenLock;
    private Background background;
    private Music music;
    private View cover, shade;
    private PixelClockView clock;
    private LinearLayout panel, tiles, buttonsRow;
    private McMenu menu;
    private InputsMenu inputsMenu;
    private SettingsMenu settingsMenu;
    private Object menuOpener;  // the tag of the button that opened the menu
    private Locale locale;      // the language the labels are in
    private int mode = HOME;
    // Taking over from the screensaver: claimed it, took the background, and it's on screen.
    private boolean handingOver, tookOver, readySent;
    private boolean stopped;
    private int swallowKey = NO_KEY;
    private long lastInputMs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scale = getResources().getDisplayMetrics().heightPixels / 1080f;
        locale = getResources().getConfiguration().getLocales().get(0);
        // Not FLAG_KEEP_SCREEN_ON: its wake lock counts as user activity when it's let go, which
        // would put off the TV's own sleep timer by another screensaver delay.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "McClock:home");
        screenLock.setReferenceCounted(false);
        int match = ViewGroup.LayoutParams.MATCH_PARENT, wrap = ViewGroup.LayoutParams.WRAP_CONTENT;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        background = Backgrounds.get(this);
        root.addView(background.createView(this, this), match, match);
        music = Music.get(this);

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
        panelLp.topMargin = px(PANEL_TOP) - px(GROW_ROOM);
        root.addView(panel, panelLp);

        // The banners, in two rows (see buildTiles).
        tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.VERTICAL);
        tiles.setClipChildren(false);
        tiles.setClipToPadding(false);
        tiles.setPadding(0, px(GROW_ROOM), 0, 0);
        panel.addView(tiles, new LinearLayout.LayoutParams(match, wrap));

        buttonsRow = new LinearLayout(this);
        buttonsRow.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(match, wrap);
        buttonsLp.topMargin = px(ROW_GAP);
        panel.addView(buttonsRow, buttonsLp);

        menu = new McMenu(this, this);
        menu.setVisibility(View.GONE);
        root.addView(menu, match, match);
        inputsMenu = new InputsMenu(this, this::openInput);
        settingsMenu = new SettingsMenu(this, this::openPage);

        setContentView(root);
        buildTiles();
        setMode(HOME, false);

        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packagesChanged, filter);
    }

    /**
     * We handle a language change ourselves (configChanges: locale, and layoutDirection, which
     * Android changes along with it), so the video goes on; the labels need the new language.
     */
    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        Locale now = getResources().getConfiguration().getLocales().get(0);
        if (now.equals(locale)) return;
        locale = now;
        buildTiles();
        McMenu.Content open = menu.content();
        if (open != null) {
            menu.show(open);
            menu.focusFirst();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        handler.removeCallbacksAndMessages(null);
        menu.clear();
        releaseScreen();
        Handover.release(this);
        Handover.homeUnderDream(false);
        background.hide(this);
        music.release(this, false);
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        Handover.homeUnderDream(false);
        music.hold(this);  // carries on from the screensaver's, if it was playing
        // Android 9 stops activities under the screensaver and starts them again once it's
        // gone: in the middle of a hand-over, just carry on.
        if (handingOver) return;
        if (Handover.claimForHome(this)) {
            beginHandover();
        } else {
            // Black until the background is on screen again.
            Anim.cancelAll(cover);
            cover.setAlpha(1f);
            cover.setVisibility(View.VISIBLE);
            setMode(HOME, false);
            background.show(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastInputMs = SystemClock.uptimeMillis();
        keepScreenOn();
        if (mode == HOME) restartIdle();
        // Back from an app that never covered us (a dialog, a crash message): music again.
        music.hold(this);
        // Back from something that didn't hide us (the TV's settings are a side panel): the
        // background was suspended for it.
        if (!handingOver) background.resume();
        // Back from a settings page or an input: what the menu shows may have changed.
        if (menuOpen()) menu.refresh();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(goAmbient);
        handler.removeCallbacks(allowSleep);
        releaseScreen();
        super.onPause();
    }

    @Override
    protected void onStop() {
        stopped = true;
        // Asleep under the screensaver (Android 9 does that): a key in it brings us back.
        Handover.homeUnderDream(Handover.dreamShowing() && !isFinishing());
        // Under a screensaver we're taking over from we still want the picture; otherwise let
        // the background go until we're back.
        if (!handingOver) {
            background.hide(this);
            music.release(this, false);
        } else if (tookOver && !readySent) {
            handler.removeCallbacks(finishHandover);
            handler.postDelayed(finishHandover, STOPPED_GRACE_MS);
        }
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (handingOver) return;
        if (Handover.claimForHome(this)) {
            beginHandover();  // HOME in a screensaver over us, and we weren't stopped
        } else if (mode == AMBIENT) {
            setMode(HOME, true);  // HOME in idle mode
        } else if (menuOpen()) {
            // HOME in a menu: back to the apps. No fade if we're coming back from black.
            closeMenu(cover.getVisibility() != View.VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (menuOpen()) closeMenu(true);
        // Otherwise there's nothing to go back to.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The screensaver has gone and we're on top: slide into the home screen.
        if (hasFocus && handingOver && readySent && !Handover.dreamShowing()) finishHandover();
    }

    // ---- Taking over from the screensaver ----

    /**
     * Coming up under the screensaver (HOME was pressed): look exactly like it, then take its
     * background once it has been woken (takeOver), unless it goes first (dreamGone).
     */
    private void beginHandover() {
        Log.i(ClockDream.TAG, "home: waiting to take over from the screensaver");
        handingOver = true;
        tookOver = readySent = false;
        // No cover: the screensaver hides us until we show the same picture.
        Anim.cancelAll(cover);
        cover.setVisibility(View.GONE);
        setMode(AMBIENT, false);
        if (Handover.dreamWoken()) takeOver();
    }

    @Override
    public void takeOver() {
        if (!handingOver || tookOver) return;
        tookOver = true;
        Log.i(ClockDream.TAG, "home: taking the background");
        handler.removeCallbacks(finishHandover);
        handler.postDelayed(finishHandover, stopped ? STOPPED_GRACE_MS : HANDOVER_MAX_MS);
        background.show(this);
    }

    @Override
    public void dreamGone() {
        if (!handingOver) return;
        Log.i(ClockDream.TAG, "home: screensaver gone" + (readySent ? "" : " before we were ready"));
        if (!tookOver && stopped) {
            // Asleep and never took the picture: start afresh when we're back (onStart).
            handingOver = false;
            handler.removeCallbacks(finishHandover);
            Handover.release(this);
            music.release(this, false);  // nobody sees us (the TV may be going to sleep)
            return;
        }
        if (!tookOver) {
            // It went without handing over (a key): show the background, from black.
            tookOver = true;
            cover.setAlpha(1f);
            cover.setVisibility(View.VISIBLE);
            background.show(this);
        } else if (readySent && hasWindowFocus()) {
            finishHandover();
        }
    }

    /** Done taking over (or giving up): make sure the screensaver goes, and slide home. */
    private void finishHandover() {
        handler.removeCallbacks(finishHandover);
        if (!handingOver) return;
        Log.i(ClockDream.TAG, "home: hand-over done" + (readySent ? "" : " (timed out)"));
        handingOver = false;
        Handover.homeReady();
        Handover.release(this);
        if (stopped) {
            background.hide(this);  // no decoder for a window nobody sees; onStart shows it again
            music.release(this, false);  // nor music; onStart holds it again
        } else if (!tookOver) {
            background.show(this);
        }
        setMode(HOME, !stopped);
    }

    // ---- Keys and the idle mode ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        lastInputMs = SystemClock.uptimeMillis();
        keepScreenOn();
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

    /**
     * Idle mode only when the TV would start a screensaver and this app's is the one picked.
     * Meanwhile we keep the screen on ourselves, so the system's doesn't start over us.
     */
    private boolean ambientEnabled() {
        ContentResolver cr = getContentResolver();
        if (Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 0) return false;
        if (Settings.Secure.getInt(cr, "screensaver_activate_on_sleep", 1) == 0) return false;
        String picked = Settings.Secure.getString(cr, "screensaver_components");
        return picked != null && picked.startsWith(getPackageName() + "/");
    }

    /**
     * Holds the screen on while we're in front, so the system screensaver doesn't start over
     * the home screen's own idle mode. In idle mode, lets go once the TV's "put to sleep"
     * time (if set) has passed since the last key, so it goes to sleep as it would after its
     * own screensaver.
     */
    private void keepScreenOn() {
        handler.removeCallbacks(allowSleep);
        if (!ambientEnabled()) {
            releaseScreen();
            return;
        }
        screenLock.acquire();
        int sleepMs = Settings.Secure.getInt(getContentResolver(), "sleep_timeout", -1);
        if (sleepMs > 0) handler.postAtTime(allowSleep, lastInputMs + sleepMs);
    }

    private void releaseScreen() {
        if (screenLock.isHeld()) screenLock.release();
    }

    private long idleTimeoutMs() {
        int ms = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                300_000);
        return Math.max(10_000, ms);
    }

    private void setMode(int newMode, boolean animate) {
        mode = newMode;
        boolean home = newMode == HOME;
        // The idle mode looks exactly like the screensaver: no menu. Its button keeps the focus.
        boolean closing = !home && menuOpen();
        if (closing) menu.clear();
        arrange(animate);
        if (closing) focusMenuButton();
        if (home) {
            restartIdle();
        } else {
            handler.removeCallbacks(goAmbient);
        }
    }

    /**
     * Puts everything where the mode and the menu want it. At home the clock is lifted with the
     * apps below, unless a menu covers them (they fade out under it); in idle mode only the
     * clock shows, in the middle.
     */
    private void arrange(boolean animate) {
        boolean home = mode == HOME, inMenu = home && menuOpen();
        float clockY = home ? -px(CLOCK_LIFT) : 0;
        float panelY = home ? 0 : getResources().getDisplayMetrics().heightPixels - px(PANEL_TOP)
                + px(40);
        float shadeAlpha = home ? 1 : 0;
        float shown = inMenu ? 0 : 1;  // the clock and the apps
        Anim.cancelAll(clock);
        Anim.cancelAll(panel);
        Anim.cancelAll(shade);
        Anim.cancelAll(menu);
        // What's hidden can't take the focus: the apps under a menu, a menu on its way out.
        panel.setDescendantFocusability(inMenu ? ViewGroup.FOCUS_BLOCK_DESCENDANTS
                : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        menu.setDescendantFocusability(inMenu ? ViewGroup.FOCUS_AFTER_DESCENDANTS
                : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (inMenu) menu.setVisibility(View.VISIBLE);
        if (!home && panel.getAlpha() < 1f) {
            // Hidden by a menu: off the screen at once, and visible again there.
            panel.setTranslationY(panelY);
            panel.setAlpha(1f);
        }
        if (animate) {
            PathInterpolator ease = new PathInterpolator(0.3f, 0f, 0.15f, 1f);
            // Going home the clock leads and the apps follow; going idle the apps leave first.
            Anim.of(clock, View.TRANSLATION_Y, clockY, MOVE_MS).ease(ease).delay(home ? 0 : 120)
                    .start();
            Anim.of(panel, View.TRANSLATION_Y, panelY, MOVE_MS).ease(ease).delay(home ? 90 : 0)
                    .withLayer().start();
            Anim.of(shade, View.ALPHA, shadeAlpha, MOVE_MS).start();
            // From a menu into idle mode the clock fades in on its way down.
            Anim.of(clock, View.ALPHA, shown, home ? MENU_MS : MOVE_MS / 2).start();
            Anim.of(panel, View.ALPHA, shown, MENU_MS).start();
            Anim.of(menu, View.ALPHA, inMenu ? 1 : 0, MENU_MS)
                    .then(inMenu ? null : () -> menu.setVisibility(View.GONE)).start();
        } else {
            clock.setTranslationY(clockY);
            panel.setTranslationY(panelY);
            shade.setAlpha(shadeAlpha);
            clock.setAlpha(shown);
            panel.setAlpha(shown);
            menu.setAlpha(inMenu ? 1 : 0);
            menu.setVisibility(inMenu ? View.VISIBLE : View.GONE);
        }
    }

    // ---- The menus ----

    private boolean menuOpen() {
        return menu.content() != null;
    }

    /** Opens a menu over the apps, from the button with this tag (the focus goes back there). */
    private void openMenu(McMenu.Content content, Object opener) {
        if (mode != HOME || handingOver || menuOpen()) return;
        menuOpener = opener;
        menu.show(content);
        arrange(true);
        menu.focusFirst();
    }

    @Override
    public void closeMenu() {
        closeMenu(true);
    }

    private void closeMenu(boolean animate) {
        if (!menuOpen()) return;
        menu.clear();
        arrange(animate);
        focusMenuButton();
    }

    /** Back on the button that opened the menu (found again, in case the buttons were rebuilt). */
    private void focusMenuButton() {
        View opener = menuOpener == null ? null : panel.findViewWithTag(menuOpener);
        if (opener != null) opener.requestFocus();
    }

    /**
     * The TV's settings pages are side panels over us and need no video decoder: the background
     * keeps going behind them. A fresh task each time: they all share one, and an old one would
     * come back on the page it was left on, with BACK going through the old pages.
     */
    @Override
    public void openPage(Intent page) {
        try {
            startActivity(new Intent(page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    // ---- Background ----

    /** Asleep under the screensaver, our view keeps its surface so we can take the picture. */
    @Override
    public boolean keepSurfaceWhileStopped() {
        return Handover.dreamShowing();
    }

    @Override
    public void onBackgroundShown() {
        if (handingOver && Handover.claimed()) {
            // Same picture as the screensaver now. Let a couple of frames reach the screen,
            // then tell it to go; we slide on once we have the focus (onWindowFocusChanged).
            cover.postOnAnimation(() -> cover.postOnAnimation(() -> {
                if (!handingOver) return;
                Log.i(ClockDream.TAG, "home: same picture as the screensaver, it can go");
                readySent = true;
                // The short grace was for a picture that might never come; it's here now.
                handler.removeCallbacks(finishHandover);
                handler.postDelayed(finishHandover, HANDOVER_MAX_MS);
                Handover.homeReady();  // it goes, then we get dreamGone()
            }));
            return;
        }
        if (handingOver) {
            readySent = true;  // the screensaver has gone already
            if (hasWindowFocus()) finishHandover();
        }
        if (cover.getVisibility() != View.VISIBLE || cover.getAlpha() < 1f) return;
        Anim.of(cover, View.ALPHA, 0f, 400).then(() -> cover.setVisibility(View.GONE)).start();
    }

    // ---- Apps and buttons ----

    private void buildTiles() {
        // Keep the focus where it was (by package or label) across a rebuild.
        View focused = panel.findFocus();
        Object focusedTag = focused == null ? null : focused.getTag();
        tiles.removeAllViews();
        buttonsRow.removeAllViews();
        PackageManager pm = getPackageManager();
        List<String> pkgs = new ArrayList<>();
        List<Intent> launches = new ArrayList<>();
        for (String pkg : appList()) {
            Intent launch = launchIntent(pm, pkg);
            if (launch == null) continue;
            pkgs.add(pkg);
            launches.add(launch);
        }
        // Two rows, the first one a banner longer for an odd count. Banners shrink if a row
        // would be wider than the screen.
        int perRow = Math.max(1, (pkgs.size() + 1) / 2);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int gap = px(TILE_GAP);
        int w = Math.min(px(TILE_W), (screenW - 2 * px(MARGIN) - (perRow - 1) * gap) / perRow);
        int h = Math.round(w * (float) TILE_H / TILE_W);
        View first = null;
        LinearLayout row = null;
        for (int i = 0; i < pkgs.size(); i++) {
            if (i % perRow == 0) row = addTileRow(i == 0 ? 0 : px(TILE_ROW_GAP));
            String pkg = pkgs.get(i);
            Intent launch = launches.get(i);
            AppTile tile = new AppTile(this, banner(pm, pkg, launch, w, h), label(pm, pkg), w, h,
                    scale);
            tile.setOnClickListener(v -> start(launch));
            tile.setTag(pkg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row.getChildCount() > 0) lp.leftMargin = gap;
            row.addView(tile, lp);
            if (first == null) first = tile;
        }

        int bp = Math.max(1, Math.round(3 * scale));
        Intent store = launchIntent(pm, PLAY_STORE);
        if (store != null) addButton("store", "Play Store", bp, v -> start(store));
        addButton("inputs", getString(R.string.inputs), bp, v -> openMenu(inputsMenu, v.getTag()));
        addButton("settings", getString(R.string.settings), bp,
                v -> openMenu(settingsMenu, v.getTag()));
        for (int r = 0; r < tiles.getChildCount(); r++) stopAtEnds((ViewGroup) tiles.getChildAt(r));
        stopAtEnds(buttonsRow);

        if (menuOpen()) return;  // the focus is in the menu; closing it finds its button again
        View again = focusedTag == null ? null : panel.findViewWithTag(focusedTag);
        if (again != null) {
            again.requestFocus();
        } else if (first != null) {
            first.requestFocus();
        } else if (buttonsRow.getChildCount() > 0) {
            buttonsRow.getChildAt(0).requestFocus();
        }
    }

    /**
     * Left from the first and right from the last of a row go nowhere. Android would otherwise
     * jump diagonally into the nearest row (from the last button up onto a banner).
     */
    private static void stopAtEnds(ViewGroup row) {
        int n = row.getChildCount();
        if (n == 0) return;
        View first = row.getChildAt(0), last = row.getChildAt(n - 1);
        if (first.getId() == View.NO_ID) first.setId(View.generateViewId());
        if (last.getId() == View.NO_ID) last.setId(View.generateViewId());
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());
    }

    private LinearLayout addTileRow(int topMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        tiles.addView(row, lp);
        return row;
    }

    /** The key tags it, so the focus finds it again after a rebuild (even in another language). */
    private void addButton(String key, String label, int bp, View.OnClickListener click) {
        McButton b = new McButton(this, label, bp);
        b.setOnClickListener(click);
        b.setTag("button:" + key);
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

    /**
     * Starts something on top, after freeing what the background holds (such as a video
     * decoder) and fading out the music.
     */
    private void start(Intent intent) {
        background.suspend();
        music.release(this, true);
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            background.resume();
            music.hold(this);
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
        background.suspend();
        music.release(this, true);
        for (Intent t : tries) {
            try {
                startActivity(t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
            }
        }
        background.resume();
        music.hold(this);
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
