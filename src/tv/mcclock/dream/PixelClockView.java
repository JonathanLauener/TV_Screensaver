package tv.mcclock.dream;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The time in Minecraft's font, centred, with Minecraft's own text shadow: the same text in
 * #3F3F3F (a quarter of white), one font pixel right and down.
 *
 * The glyphs are Minecraft's 5 x 7 digits and its 1-pixel colon ({@link PixelText}), drawn as
 * k x k squares, so every font pixel is exactly the same size.
 */
final class PixelClockView extends View {

    /** One font pixel is this many screen pixels on a 1080-pixel-tall screen. */
    private static final float FONT_PIXELS_AT_1080P = 18f;
    /**
     * Burn-in care for OLED panels: each minute the clock steps through these offsets, in half
     * font pixels (9 px at 1080p), so it never sits on exactly the same pixels for long.
     */
    private static final int[][] DRIFT = {
            {0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private final Paint paint = new Paint();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private final BroadcastReceiver timeChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            tick();
        }
    };

    private String text = "";
    private int fontPixel = 1;
    private int dx, dy;

    PixelClockView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(timeChanged, filter);
        tick();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().unregisterReceiver(timeChanged);
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        fontPixel = Math.max(1, Math.round(h * FONT_PIXELS_AT_1080P / 1080f));
        updateDrift(System.currentTimeMillis());
    }

    /** Refreshes the text and schedules itself for the start of the next minute. */
    private void tick() {
        long now = System.currentTimeMillis();
        String pattern = android.text.format.DateFormat.is24HourFormat(getContext())
                ? "HH:mm" : "h:mm";
        // Locale.US for Latin digits whatever the TV's language; the glyphs only cover 0-9.
        String next = new SimpleDateFormat(pattern, Locale.US).format(new Date(now));
        if (!next.equals(text)) {
            text = next;
            updateDrift(now);
            invalidate();
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 60_000 - now % 60_000 + 50);
    }

    private void updateDrift(long now) {
        int[] step = DRIFT[(int) ((now / 60_000) % DRIFT.length)];
        int half = Math.max(1, fontPixel / 2);
        dx = step[0] * half;
        dy = step[1] * half;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (text.isEmpty()) return;
        int k = fontPixel;
        int x = (getWidth() - PixelText.width(text) * k) / 2 + dx;
        int baseline = (getHeight() + PixelText.CAP * k) / 2 + dy;
        PixelText.drawShadowed(canvas, paint, text, x, baseline, k, Color.WHITE);
    }
}
