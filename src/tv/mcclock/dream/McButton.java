package tv.mcclock.dream;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;

/**
 * A Minecraft menu button as in Java Edition 1.20.5 and later: grey stone face with a light
 * top-left and dark bottom-right edge, a black outline, and a white label in the pixel font.
 * Focused, it looks hovered: white outline and a slightly lighter face. Disabled, it goes dark
 * and flat with a grey label. Sizes are in button pixels (bp): the game's buttons are 20 bp tall.
 */
final class McButton extends View {

    private static final int HEIGHT_BP = 20, PAD_BP = 10, MIN_WIDTH_BP = 64, LABEL_TOP_BP = 6;
    // The game's button sprites: normal, then hovered (focused here).
    private static final int[] OUTLINE = {0xFF000000, 0xFFFFFFFF};
    private static final int[] FACE = {0xFF6F6F6F, 0xFF757575};
    private static final int[] LIGHT = {0xFFAAAAAA, 0xFFAFAFAF};
    private static final int[] SHADE = {0xFF565656, 0xFF5C5C5C};
    private static final int[] CORNER_TOP = {0xFF737373, 0xFF797979};  // bottom left, where
    private static final int[] CORNER_LOW = {0xFF6D6D6D, 0xFF737373};  // light meets shade
    private static final int TEXT = 0xFFFFFFFF;
    private static final int FACE_DISABLED = 0xFF2C2C2C, TEXT_DISABLED = 0xFFA0A0A0;

    private final String label;
    private final int bp;  // screen pixels per button pixel
    private final int widthBp;  // 0: as wide as the label needs
    private final Paint paint = new Paint();

    McButton(Context context, String label, int bp) {
        this(context, label, bp, 0);
    }

    /** A button widthBp wide, like the game's rows of equal buttons. */
    McButton(Context context, String label, int bp, int widthBp) {
        super(context);
        this.label = label;
        this.bp = bp;
        this.widthBp = widthBp;
        setFocusable(true);
        setClickable(true);
        // Android 8+ would wash a focused view with a translucent highlight: we draw our own.
        if (Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
    }

    /** Disabled buttons can't take the focus, as in the game they can't be hovered. */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setFocusable(enabled);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = widthBp > 0 ? widthBp : Math.max(MIN_WIDTH_BP, PixelText.width(label) + 2 * PAD_BP);
        setMeasuredDimension(w * bp, HEIGHT_BP * bp);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int text;
        if (!isEnabled()) {
            fill(canvas, 0, 0, w, h, OUTLINE[0]);
            fill(canvas, bp, bp, w - bp, h - bp, FACE_DISABLED);
            text = TEXT_DISABLED;
        } else {
            int s = isFocused() ? 1 : 0;
            fill(canvas, 0, 0, w, h, OUTLINE[s]);
            fill(canvas, bp, bp, w - bp, h - bp, FACE[s]);
            fill(canvas, bp, bp, w - 2 * bp, 2 * bp, LIGHT[s]);          // top edge
            fill(canvas, bp, bp, 2 * bp, h - 3 * bp, LIGHT[s]);          // left edge
            fill(canvas, 2 * bp, h - 3 * bp, w - bp, h - bp, SHADE[s]);  // bottom edge, 2 bp
            fill(canvas, w - 2 * bp, 2 * bp, w - bp, h - bp, SHADE[s]);  // right edge
            fill(canvas, bp, h - 3 * bp, 2 * bp, h - 2 * bp, CORNER_TOP[s]);
            fill(canvas, bp, h - 2 * bp, 2 * bp, h - bp, CORNER_LOW[s]);
            text = TEXT;
        }
        // On the button-pixel grid, where the game puts it: centred (its text widths include
        // the pixel after the last glyph), caps from y = 6.
        int x = (w / bp / 2 - (PixelText.width(label) + 1) / 2) * bp;
        int baseline = (LABEL_TOP_BP + PixelText.CAP) * bp;
        PixelText.drawShadowed(canvas, paint, label, x, baseline, bp, text);
    }

    private void fill(Canvas canvas, int l, int t, int r, int b, int color) {
        paint.setColor(color);
        canvas.drawRect(l, t, r, b, paint);
    }
}
