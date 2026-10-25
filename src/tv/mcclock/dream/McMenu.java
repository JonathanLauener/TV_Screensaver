package tv.mcclock.dream;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A menu that looks like Minecraft's multiplayer server list (Java Edition 1.20.5 and later,
 * opened in a world): the world (our background) dimmed behind, the title at the top, a dark
 * see-through list between two separator lines, and two rows of buttons. Each entry is a
 * server-style row: a 32 x 32 icon, the name in white, up to two grey lines, and signal bars.
 *
 * Laid out as in 1.21.11, in GUI pixels at the game's own GUI scale for the screen (4 at 1080p):
 * a 33-pixel header, the list down to 60 above the bottom, rows 36 high and 305 wide, buttons 20
 * high. The game blurs the world behind; a TV can't blur its video plane, so the world is dimmed
 * more instead, as the game does under its in-game screens.
 * Up and down move the selection, OK opens it, down from the last entry goes to the buttons.
 */
final class McMenu extends FrameLayout {

    /** No signal bars; a red cross (can't connect); otherwise 0 to 5 bars. */
    static final int NO_SIGNAL = -2, UNREACHABLE = -1;

    /** One row of the list. */
    static final class Entry {
        final String key;    // stays the same across a refresh, to keep the selection
        final String icon;   // an Icons name
        final String name;
        final String line1, line2;  // grey, either may be null
        final int signal;
        final Runnable open;

        Entry(String key, String icon, String name, String line1, String line2, int signal,
                Runnable open) {
            this.key = key;
            this.icon = icon;
            this.name = name;
            this.line1 = line1;
            this.line2 = line2;
            this.signal = signal;
            this.open = open;
        }
    }

    /** What one menu shows. */
    interface Content {
        String title();

        /** Read afresh when the menu opens and on each refresh. */
        List<Entry> entries();

        /** Shown in the list when it's empty. */
        String emptyText();

        /** The first button: opens the selected entry. */
        String openLabel();

        /** The second button, and the page it opens (null: the button is disabled). */
        String extraLabel();

        Intent extra();

        /** The menu is showing this content now (start listening for changes)... */
        void opened(McMenu menu);

        /** ...and stopped showing it. */
        void closed();
    }

    /** The home screen's side. */
    interface Host {
        /** Zurück (or BACK). */
        void closeMenu();

        /** Opens a page of the TV's settings. */
        void openPage(Intent page);
    }

    // Minecraft's layout, in GUI pixels.
    private static final int GUI_W_MIN = 320, GUI_H_MIN = 240;  // the game's smallest GUI
    private static final int TITLE_Y = 12;  // in the middle of the header
    private static final int LIST_TOP = 33, LIST_BOTTOM_GAP = 60;
    private static final int ITEM_H = 36, ROW_W = 305, LIST_PAD = 2, CONTENT_PAD = 2;
    private static final int CONTENT_W = ROW_W - 2 * CONTENT_PAD;
    private static final int ICON = 32, TEXT_X = 35, NAME_Y = 1, LINE1_Y = 12, LINE_H = 9;
    private static final int LINE_W = CONTENT_W - 34;  // the game wraps descriptions to this
    private static final int PING_H = 8, PING_X = CONTENT_W - 15;
    private static final int SCROLLBAR_GAP = 8, SCROLLBAR_W = 6, THUMB_MIN = 32;
    private static final int BUTTONS_W = 308, BUTTON_H = 20, BUTTON_GAP = 4;
    private static final int BUTTON_W = (BUTTONS_W - BUTTON_GAP) / 2;

    // Minecraft's colours.
    private static final int DIM_TOP = 0xC0101010, DIM_BOTTOM = 0xD0101010;  // over a world
    private static final int LIST_BG = 0x70000000;
    private static final int SEPARATOR_LIGHT = 0x33FFFFFF, SEPARATOR_DARK = 0xBF000000;
    private static final int TEXT = 0xFFFFFFFF, GREY = 0xFF808080;
    private static final int SELECTED = 0xFFFFFFFF, SELECTED_UNFOCUSED = 0xFF808080;
    private static final int SELECTED_FILL = 0xFF000000;
    private static final int SCROLL_TRACK = 0xFF000000, THUMB = 0xFF808080, THUMB_LIGHT = 0xFFC0C0C0;
    private static final int BAR_ON = 0xFF00FF21, BAR_ON_SHADOW = 0xFF00870F;
    private static final int BAR_OFF = 0xFF5B5B5B, BAR_OFF_SHADOW = 0xFF383838;
    private static final int CROSS = 0xFFFF0000, CROSS_SHADOW = 0xFF820000;
    // Pointing at a server's icon in the game greys it and shows a "play" arrow; here that's
    // the selected entry while the list has the focus. Blocks of 2 x 2 GUI pixels.
    private static final int PLAY_WASH = 0xA0909090;
    private static final int PLAY_X = 16, PLAY_Y = 5;
    private static final String[] PLAY = {
            "K......",
            "KWK....",
            "KWWK...",
            "KWGWK..",
            "KWGGWK.",
            "KWGGGSK",
            "KGGGSK.",
            "KGGSK..",
            "KGSK...",
            "KSK....",
            "K......"};
    private static final int[] PLAY_COLORS = {'K', 0xFF000000, 'W', 0xFFFFFFFF, 'G', 0xFF8B8B8B,
            'S', 0xFF373737};

    private final Host host;
    private final int gp;  // screen pixels per GUI pixel
    private final Paint paint = new Paint();
    private final Paint dim = new Paint();  // the gradient behind everything
    private final EntryList list;
    private final McButton[] buttons = new McButton[4];
    private final LinearLayout buttonRows;
    private final HashMap<Content, String> lastOpened = new HashMap<>();
    private Content content;
    private List<Entry> entries = new ArrayList<>();
    private String title = "";
    private int guiW, guiH;

    McMenu(Context context, Host host) {
        super(context);
        this.host = host;
        setWillNotDraw(false);
        setClipChildren(false);
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        gp = guiScale(dm.widthPixels, dm.heightPixels);

        list = new EntryList(context);
        addView(list);

        buttonRows = new LinearLayout(context);
        buttonRows.setOrientation(LinearLayout.VERTICAL);
        addView(buttonRows, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(context);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) rowLp.topMargin = BUTTON_GAP * gp;
            buttonRows.addView(row, rowLp);
        }
    }

    /** The game's automatic GUI scale: the largest that leaves at least 320 x 240 GUI pixels. */
    private static int guiScale(int w, int h) {
        int s = 1;
        while (w / (s + 1) >= GUI_W_MIN && h / (s + 1) >= GUI_H_MIN) s++;
        return s;
    }

    /** Shows this content (the entries are read now), with the selection where it was. */
    void show(Content c) {
        if (content != null && content != c) content.closed();
        boolean fresh = content != c;
        content = c;
        title = c.title();
        setButtons(c);
        if (fresh) c.opened(this);
        list.setEntries(c.entries(), lastOpened.get(c), fresh);
        buttons[0].setEnabled(list.selected() != null);
        invalidate();
    }

    /** Stops showing the content (the caller hides the view). */
    void clear() {
        if (content != null) content.closed();
        content = null;
    }

    Content content() {
        return content;
    }

    /** Reads the entries again, keeping the selection on the same entry. */
    void refresh() {
        if (content == null) return;
        boolean hadFocus = list.hasFocus();
        Entry sel = list.selected();
        list.setEntries(content.entries(), sel == null ? null : sel.key, false);
        buttons[0].setEnabled(list.selected() != null);
        if (hadFocus && !list.isFocusable()) focusFirst();
    }

    /** The list, or the first button that can take the focus. */
    void focusFirst() {
        if (list.isFocusable()) {
            list.requestFocus();
            return;
        }
        for (McButton b : buttons) {
            if (b != null && b.isEnabled()) {
                b.requestFocus();
                return;
            }
        }
    }

    private void setButtons(Content c) {
        Intent extra = c.extra();
        String[] labels = {c.openLabel(), c.extraLabel(),
                getContext().getString(R.string.refresh), getContext().getString(R.string.back)};
        View.OnClickListener[] clicks = {v -> openSelected(), v -> {
            if (extra != null) host.openPage(extra);
        }, v -> refresh(), v -> host.closeMenu()};
        for (int i = 0; i < 4; i++) {
            LinearLayout row = (LinearLayout) buttonRows.getChildAt(i / 2);
            if (i % 2 == 0) row.removeAllViews();
            McButton b = new McButton(getContext(), labels[i], gp, BUTTON_W);
            b.setOnClickListener(clicks[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % 2 == 1) lp.leftMargin = BUTTON_GAP * gp;
            row.addView(b, lp);
            buttons[i] = b;
        }
        buttons[1].setEnabled(extra != null);
    }

    private void openSelected() {
        Entry e = list.selected();
        if (e == null || content == null) return;
        lastOpened.put(content, e.key);
        e.open.run();
    }

    // ---- Layout and drawing ----

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        guiW = w / gp;
        guiH = h / gp;
        int listH = Math.max(0, guiH - LIST_BOTTOM_GAP - LIST_TOP);
        LayoutParams listLp = (LayoutParams) list.getLayoutParams();
        listLp.width = w;
        listLp.height = listH * gp;
        listLp.topMargin = LIST_TOP * gp;
        // The two rows of buttons sit in the middle of the space below the list.
        int buttonsH = 2 * BUTTON_H + BUTTON_GAP;
        LayoutParams rowsLp = (LayoutParams) buttonRows.getLayoutParams();
        rowsLp.topMargin = (guiH - LIST_BOTTOM_GAP + (LIST_BOTTOM_GAP - buttonsH) / 2) * gp;
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        dim.setShader(new LinearGradient(0, 0, 0, h, DIM_TOP, DIM_BOTTOM, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        // The world behind, darkened like the game does under its in-game menus.
        canvas.drawRect(0, 0, w, h, dim);
        // The separators above and below the list: a light line over a dark one, and below
        // the list the other way round.
        int top = LIST_TOP * gp, bottom = (guiH - LIST_BOTTOM_GAP) * gp;
        fill(canvas, 0, top - 2 * gp, w, top - gp, SEPARATOR_LIGHT);
        fill(canvas, 0, top - gp, w, top, SEPARATOR_DARK);
        fill(canvas, 0, bottom, w, bottom + gp, SEPARATOR_DARK);
        fill(canvas, 0, bottom + gp, w, bottom + 2 * gp, SEPARATOR_LIGHT);
        // The title, centred on the GUI-pixel grid, with the usual text shadow.
        int x = centred(title, guiW);
        PixelText.drawShadowed(canvas, paint, title, x, (TITLE_Y + PixelText.CAP) * gp, gp, TEXT);
    }

    private void fill(Canvas canvas, int l, int t, int r, int b, int color) {
        paint.setColor(color);
        canvas.drawRect(l, t, r, b, paint);
    }

    /** Where text centred in a width of guiWidth GUI pixels starts, in screen pixels. */
    private int centred(String text, int guiWidth) {
        // The game's text widths include the pixel after the last glyph.
        return (guiWidth / 2 - (PixelText.width(text) + 1) / 2) * gp;
    }

    /** Text cut to fit maxWidth font pixels, ending in "..." if it had to be cut. */
    private static String fit(String text, int maxWidth) {
        if (text == null || PixelText.width(text) <= maxWidth) return text;
        String dots = "...";
        int end = text.length();
        while (end > 0 && PixelText.width(text.substring(0, end) + dots) > maxWidth) end--;
        return text.substring(0, end).trim() + dots;
    }

    /**
     * Minecraft's signal bars (10 x 8): five bars 2 to 6 high, each with a shadow one pixel right
     * and down, the first ones green. Can't connect: grey bars under a red cross.
     */
    private void drawSignal(Canvas canvas, int x, int y, int bars) {
        if (bars == NO_SIGNAL) return;
        for (int i = 0; i < 5; i++) {
            boolean on = bars != UNREACHABLE && i < bars;
            int col = x + 2 * i;
            dot(canvas, col, y + 5 - i, 1, 2 + i, on ? BAR_ON : BAR_OFF);
            dot(canvas, col + 1, y + 6 - i, 1, 2 + i, on ? BAR_ON_SHADOW : BAR_OFF_SHADOW);
        }
        if (bars != UNREACHABLE) return;
        // An X of 7 rows meeting in one pixel, each pixel with a dark one to its right.
        for (int i = 0; i < 7; i++) {
            dot(canvas, x + 2 + i, y + 1 + i, 1, 1, CROSS_SHADOW);
            dot(canvas, x + 8 - i, y + 1 + i, 1, 1, CROSS_SHADOW);
            dot(canvas, x + 1 + i, y + 1 + i, 1, 1, CROSS);
            dot(canvas, x + 7 - i, y + 1 + i, 1, 1, CROSS);
        }
    }

    /** A w x h block of GUI pixels at (x, y). */
    private void dot(Canvas canvas, int x, int y, int w, int h, int color) {
        fill(canvas, x * gp, y * gp, (x + w) * gp, (y + h) * gp, color);
    }

    // ---- The list ----

    /** The scrolling list of entries: one focusable view, the selection moves inside it. */
    private final class EntryList extends View {

        private final Rect clip = new Rect();
        private int selected = -1;
        private int scroll;  // GUI pixels

        EntryList(Context context) {
            super(context);
            setClickable(true);
            setOnClickListener(v -> openSelected());
            // Android 8+ would wash a focused view with a translucent highlight: we draw our own.
            if (Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
        }

        /**
         * New entries, selecting the one with this key. Otherwise the selection stays at the
         * same place, or for a menu just opened (fresh) it starts at the top.
         */
        void setEntries(List<Entry> list, String selectKey, boolean fresh) {
            entries = list;
            if (fresh) {
                selected = 0;
                scroll = 0;
            }
            int sel = entries.isEmpty() ? -1 : Math.max(0, Math.min(selected, entries.size() - 1));
            if (selectKey != null) {
                for (int i = 0; i < entries.size(); i++) {
                    if (selectKey.equals(entries.get(i).key)) sel = i;
                }
            }
            setFocusable(!entries.isEmpty());
            selected = sel;
            scroll = clampScroll(scroll);
            ensureVisible();
            invalidate();
        }

        Entry selected() {
            return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
        }

        private int listH() {
            return getHeight() / gp;
        }

        private int contentH() {
            return entries.size() * ITEM_H + 2 * LIST_PAD;
        }

        private int maxScroll() {
            return Math.max(0, contentH() - listH());
        }

        private int clampScroll(int s) {
            return Math.max(0, Math.min(maxScroll(), s));
        }

        /**
         * Scrolls so the selected row and one more on each side are in view (as the game did up
         * to 1.21.8), so moving through the list shows what's coming.
         */
        private void ensureVisible() {
            if (selected < 0 || getHeight() == 0) return;
            int rowTop = LIST_PAD + selected * ITEM_H - scroll;
            if (rowTop - ITEM_H < LIST_PAD) scroll -= LIST_PAD - (rowTop - ITEM_H);
            if (rowTop + 2 * ITEM_H > listH() - LIST_PAD) {
                scroll += rowTop + 2 * ITEM_H - (listH() - LIST_PAD);
            }
            scroll = clampScroll(scroll);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            scroll = clampScroll(scroll);
            ensureVisible();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && selected > 0) {
                select(selected - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && selected < entries.size() - 1) {
                select(selected + 1);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        private void select(int index) {
            selected = index;
            ensureVisible();
            invalidate();
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            fill(canvas, 0, 0, w, h, LIST_BG);
            int gw = w / gp;
            if (entries.isEmpty()) {
                String empty = content == null ? "" : content.emptyText();
                int x = centred(empty, gw);
                paint.setColor(TEXT);
                PixelText.draw(canvas, paint, empty, x, (listH() / 2 + PixelText.CAP / 2) * gp, gp);
                return;
            }
            canvas.save();
            clip.set(0, 0, w, h);
            canvas.clipRect(clip);
            int rowX = gw / 2 - ROW_W / 2;
            for (int i = 0; i < entries.size(); i++) {
                int rowY = LIST_PAD + i * ITEM_H - scroll;
                if (rowY + ITEM_H < 0 || rowY > listH()) continue;
                if (i == selected) {
                    // A 1-pixel frame, white while the list has the focus, filled black.
                    dot(canvas, rowX, rowY, ROW_W, ITEM_H, isFocused() ? SELECTED : SELECTED_UNFOCUSED);
                    dot(canvas, rowX + 1, rowY + 1, ROW_W - 2, ITEM_H - 2, SELECTED_FILL);
                }
                drawEntry(canvas, entries.get(i), rowX + CONTENT_PAD, rowY + CONTENT_PAD,
                        i == selected && isFocused());
            }
            canvas.restore();
            drawScrollbar(canvas, gw);
        }

        private void drawEntry(Canvas canvas, Entry e, int left, int top, boolean pointed) {
            Icons.draw(canvas, e.icon, left * gp, top * gp, ICON * gp);
            if (pointed) drawPlay(canvas, left, top);
            int nameW = (e.signal == NO_SIGNAL ? CONTENT_W : PING_X - 5) - TEXT_X;
            int x = (left + TEXT_X) * gp;
            paint.setColor(TEXT);
            PixelText.draw(canvas, paint, fit(e.name, nameW), x, (top + NAME_Y + PixelText.CAP) * gp,
                    gp);
            paint.setColor(GREY);
            if (e.line1 != null) {
                PixelText.draw(canvas, paint, fit(e.line1, LINE_W), x,
                        (top + LINE1_Y + PixelText.CAP) * gp, gp);
            }
            if (e.line2 != null) {
                PixelText.draw(canvas, paint, fit(e.line2, LINE_W), x,
                        (top + LINE1_Y + LINE_H + PixelText.CAP) * gp, gp);
            }
            drawSignal(canvas, left + PING_X, top, e.signal);
        }

        private void drawPlay(Canvas canvas, int left, int top) {
            dot(canvas, left, top, ICON, ICON, PLAY_WASH);
            for (int r = 0; r < PLAY.length; r++) {
                for (int c = 0; c < PLAY[r].length(); c++) {
                    char ch = PLAY[r].charAt(c);
                    for (int k = 0; k < PLAY_COLORS.length; k += 2) {
                        if (PLAY_COLORS[k] != ch) continue;
                        dot(canvas, left + PLAY_X + 2 * c, top + PLAY_Y + 2 * r, 2, 2,
                                PLAY_COLORS[k + 1]);
                    }
                }
            }
        }

        private void drawScrollbar(Canvas canvas, int gw) {
            int max = maxScroll();
            if (max <= 0) return;
            int listH = listH();
            int x = gw / 2 - ROW_W / 2 + ROW_W + SCROLLBAR_GAP;
            int thumb = Math.max(THUMB_MIN, Math.min(listH - 8, listH * listH / contentH()));
            int y = scroll * (listH - thumb) / max;
            fill(canvas, x * gp, 0, (x + SCROLLBAR_W) * gp, listH * gp, SCROLL_TRACK);
            fill(canvas, x * gp, y * gp, (x + SCROLLBAR_W) * gp, (y + thumb) * gp, THUMB);
            fill(canvas, x * gp, y * gp, (x + SCROLLBAR_W - 1) * gp, (y + thumb - 1) * gp,
                    THUMB_LIGHT);
        }
    }
}
