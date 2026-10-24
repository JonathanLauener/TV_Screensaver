package tv.mcclock.dream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * An app's TV banner, square-cornered like a Minecraft slot. Focused, it grows a little, gets a
 * white frame, and shows the app's name underneath in the pixel font.
 */
final class AppTile extends View {

    private static final float FOCUS_SCALE = 1.08f;
    private static final int DIM = 0x40000000;

    private final Bitmap banner;
    private final String label;
    private final int bannerW, bannerH, frame, textPixel, gap;
    private final Paint paint = new Paint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();

    AppTile(Context context, Bitmap banner, String label, int bannerW, int bannerH, float scale) {
        super(context);
        this.banner = banner;
        this.label = label;
        this.bannerW = bannerW;
        this.bannerH = bannerH;
        frame = Math.max(2, Math.round(4 * scale));
        textPixel = Math.max(1, Math.round(3 * scale));
        gap = Math.round(18 * scale);
        setFocusable(true);
        setClickable(true);
        setPivotX(bannerW / 2f);
        setPivotY(bannerH / 2f);
    }

    /** Banner, gap, then room for one line of caption and its shadow. */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int captionH = gap + (PixelText.CAP + 3) * textPixel;
        setMeasuredDimension(bannerW, bannerH + captionH);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        float s = gained ? FOCUS_SCALE : 1f;
        animate().scaleX(s).scaleY(s).setDuration(150).setInterpolator(new DecelerateInterpolator());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        dst.set(0, 0, bannerW, bannerH);
        if (banner != null) {
            canvas.drawBitmap(banner, null, dst, bitmapPaint);
        } else {
            paint.setColor(0xFF3A3A3A);
            canvas.drawRect(dst, paint);
        }
        if (isFocused()) {
            paint.setColor(Color.WHITE);
            canvas.drawRect(0, 0, bannerW, frame, paint);
            canvas.drawRect(0, bannerH - frame, bannerW, bannerH, paint);
            canvas.drawRect(0, 0, frame, bannerH, paint);
            canvas.drawRect(bannerW - frame, 0, bannerW, bannerH, paint);
            // The name, centred under the banner. Unscale it so it stays on the pixel grid.
            canvas.save();
            canvas.scale(1 / getScaleX(), 1 / getScaleY(), bannerW / 2f, bannerH / 2f);
            int x = (bannerW - PixelText.width(label) * textPixel) / 2;
            int baseline = Math.round(bannerH + (bannerH * (getScaleY() - 1)) / 2) + gap
                    + PixelText.CAP * textPixel;
            PixelText.drawShadowed(canvas, paint, label, x, baseline, textPixel, Color.WHITE);
            canvas.restore();
        } else {
            paint.setColor(DIM);
            canvas.drawRect(dst, paint);
        }
    }
}
