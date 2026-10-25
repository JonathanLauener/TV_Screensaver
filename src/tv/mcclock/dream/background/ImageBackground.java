package tv.mcclock.dream.background;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * A bundled still image, filling the screen (centre-cropped). Every window draws the same
 * decoded bitmap, so a hand-over between windows has nothing to carry over. No asset: black.
 */
final class ImageBackground implements Background {

    private static final String TAG = Backgrounds.TAG;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<StillView> views = new ArrayList<>();
    private Bitmap bitmap;
    private boolean loaded;

    ImageBackground(Context app, String asset) {
        if (asset == null) {
            loaded = true;
            return;
        }
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        int screenW = Math.max(dm.widthPixels, dm.heightPixels);
        int screenH = Math.min(dm.widthPixels, dm.heightPixels);
        // Decoding a 4K image takes a while: off the main thread, black until then.
        new Thread(() -> {
            Bitmap b = decode(app, asset, screenW, screenH);
            main.post(() -> {
                bitmap = b;
                loaded = true;
                for (StillView v : views) v.invalidate();
            });
        }, "McClock-image").start();
    }

    /**
     * Only the middle part with the screen's shape, at the smallest power-of-two reduction that
     * still covers the screen, so the bitmap is never much bigger than the screen.
     */
    private static Bitmap decode(Context app, String asset, int screenW, int screenH) {
        try (InputStream in = app.getAssets().open(asset)) {
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(in, false);
            try {
                int bw = decoder.getWidth(), bh = decoder.getHeight();
                Rect crop = new Rect();
                centreCrop(bw, bh, screenW, screenH, crop);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                while (crop.width() / (opts.inSampleSize * 2) >= screenW
                        && crop.height() / (opts.inSampleSize * 2) >= screenH) {
                    opts.inSampleSize *= 2;
                }
                Bitmap b = decoder.decodeRegion(crop, opts);
                if (b == null) Log.e(TAG, "cannot decode " + asset);
                return b;
            } finally {
                decoder.recycle();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.e(TAG, "cannot load " + asset, e);
            return null;
        }
    }

    /** The largest part of a bw x bh picture with the shape of w x h, from the middle. */
    private static void centreCrop(int bw, int bh, int w, int h, Rect out) {
        if ((long) bw * h > (long) bh * w) {
            int cw = (int) ((long) bh * w / h);
            out.set((bw - cw) / 2, 0, (bw + cw) / 2, bh);
        } else {
            int ch = (int) ((long) bw * h / w);
            out.set(0, (bh - ch) / 2, bw, (bh + ch) / 2);
        }
    }

    @Override
    public View createView(Context context, Host host) {
        StillView v = new StillView(context, host);
        views.add(v);
        return v;
    }

    @Override
    public void show(Host host) {
        for (StillView v : views) {
            if (v.host != host) continue;
            if (v.drawn) {
                // Still on screen from its last draw (a still picture doesn't change): no need
                // to wait for another one, which a stopped window wouldn't get.
                v.post(host::onBackgroundShown);
            } else {
                v.announce();
            }
        }
    }

    @Override
    public void hide(Host host) {
    }

    @Override
    public void suspend() {
    }

    @Override
    public void resume() {
    }

    private final class StillView extends View {
        final Host host;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect(), dst = new Rect();
        private boolean owed = true;  // tell the host once the picture is drawn
        boolean drawn;  // the picture has been drawn and the window not hidden since

        StillView(Context context, Host host) {
            super(context);
            this.host = host;
        }

        void announce() {
            owed = true;
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            views.remove(this);  // its window is gone for good
            drawn = false;
            super.onDetachedFromWindow();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) {
                announce();
            } else {
                drawn = false;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if (!loaded) return;
            Bitmap b = bitmap;
            drawn = true;
            if (b != null && getWidth() > 0 && getHeight() > 0) {
                centreCrop(b.getWidth(), b.getHeight(), getWidth(), getHeight(), src);
                dst.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(b, src, dst, paint);
            }
            if (owed) {
                Log.d(TAG, "picture drawn for " + host.getClass().getSimpleName());
                owed = false;
                post(host::onBackgroundShown);
            }
        }
    }
}
