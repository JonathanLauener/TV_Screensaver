package tv.mcclock.dream;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * A Minecraft menu button: grey stone face with a light top-left and dark bottom-right edge,
 * a black outline, and the label in the pixel font. Focused, the outline turns white and the
 * label yellow, like hovering a button in the game. Sizes are in button pixels (bp): the game's
 * buttons are 20 bp tall.
 */
final class McButton extends View {

    private static final int HEIGHT_BP = 20, PAD_BP = 10, MIN_WIDTH_BP = 64;
    private static final int FACE = 0xFF6F6F6F, LIGHT = 0xFFA5A5A5, DARK = 0xFF484848;
    private static final int OUTLINE = 0xFF000000, OUTLINE_FOCUSED = 0xFFFFFFFF;
    private static final int TEXT = 0xFFE0E0E0, TEXT_FOCUSED = 0xFFFFFFA0;

    private final String label;
    private final int bp;  // screen pixels per button pixel
    private final Paint paint = new Paint();

    McButton(Context context, String label, int bp) {
        super(context);
        this.label = label;
        this.bp = bp;
        setFocusable(true);
        setClickable(true);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = Math.max(MIN_WIDTH_BP, PixelText.width(label) + 2 * PAD_BP);
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
        boolean focused = isFocused();
        fill(canvas, 0, 0, w, h, focused ? OUTLINE_FOCUSED : OUTLINE);
        fill(canvas, bp, bp, w - bp, h - bp, FACE);
        fill(canvas, bp, bp, w - bp, 2 * bp, LIGHT);          // top edge
        fill(canvas, bp, bp, 2 * bp, h - bp, LIGHT);          // left edge
        fill(canvas, bp, h - 3 * bp, w - bp, h - bp, DARK);   // bottom edge, 2 bp
        fill(canvas, w - 2 * bp, bp, w - bp, h - bp, DARK);   // right edge
        int x = (w - PixelText.width(label) * bp) / 2;
        int baseline = (h - 2 * bp + PixelText.CAP * bp) / 2 + bp;
        PixelText.drawShadowed(canvas, paint, label, x, baseline, bp,
                focused ? TEXT_FOCUSED : TEXT);
    }

    private void fill(Canvas canvas, int l, int t, int r, int b, int color) {
        paint.setColor(color);
        canvas.drawRect(l, t, r, b, paint);
    }
}
