package tv.mcclock.dream;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Draws text the way Minecraft does: pixel glyphs as k x k squares, each followed by one
 * pixel of space, and a 4-pixel space character. With {@link #drawShadowed} the text gets
 * Minecraft's shadow: the same text a quarter as bright, one pixel right and down.
 */
final class PixelText {

    static final int CAP = 7;          // rows above the baseline for capitals and digits
    private static final int SPACE = 3; // a space is 3 pixels plus the usual 1 after it

    /** Width in font pixels, without the space after the last glyph. */
    static int width(String text) {
        int w = -1;
        for (int i = 0; i < text.length(); i++) w += advance(text.charAt(i));
        return Math.max(0, w);
    }

    private static int advance(char c) {
        if (c == ' ') return SPACE + 1;
        Glyphs.Glyph g = glyph(c);
        return g.width + 1;
    }

    private static Glyphs.Glyph glyph(char c) {
        Glyphs.Glyph g = Glyphs.get(c);
        return g != null ? g : Glyphs.get('?');
    }

    /** Draws text with its left edge at x and its baseline at y, in the paint's colour. */
    static void draw(Canvas canvas, Paint paint, String text, int x, int y, int k) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ') {
                Glyphs.Glyph g = glyph(c);
                int top = y - g.top * k;
                for (int row = 0; row < g.rows.length; row++) {
                    String bits = g.rows[row];
                    for (int col = 0; col < bits.length(); col++) {
                        if (bits.charAt(col) != '#') continue;
                        int left = x + col * k, t = top + row * k;
                        canvas.drawRect(left, t, left + k, t + k, paint);
                    }
                }
            }
            x += advance(c) * k;
        }
    }

    /** Minecraft's text shadow colour for a text colour: a quarter of its brightness. */
    static int shadowOf(int color) {
        return (color & 0xFF000000) | ((color & 0xFCFCFC) >> 2);
    }

    static void drawShadowed(Canvas canvas, Paint paint, String text, int x, int y, int k,
            int color) {
        paint.setColor(shadowOf(color));
        draw(canvas, paint, text, x + k, y + k, k);
        paint.setColor(color);
        draw(canvas, paint, text, x, y, k);
    }

    private PixelText() {
    }
}
