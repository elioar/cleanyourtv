package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Posters, thumbnails and backdrops for minimal home's rows: downloaded once into the disk cache, decoded off the
 * main thread at the size they are shown (RGB_565, about half the memory of full colour), and kept in a small
 * memory cache. A view that was reused for another card meanwhile is left alone.
 */
final class ImageLoader {
    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    ImageLoader(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Shows url in the view, decoded to about w×h pixels; the view keeps its placeholder until then. */
    void into(String url, ImageView view, int w, int h) {
        view.setTag(R.id.icon, url);
        if (url == null) {
            view.setImageDrawable(null);
            return;
        }
        String key = url + "@" + w;
        Bitmap hit = memory.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        load(url, w, h, bmp -> {
            if (bmp != null && url.equals(view.getTag(R.id.icon))) view.setImageBitmap(bmp);
        });
    }

    /**
     * Shows url cropped to exactly w×h with rounded corners drawn into the drawable. The card then needs no outline
     * clip: clipping thirty cards to rounded corners on every frame of a row's slide was most of its GPU cost.
     */
    void intoRounded(String url, ImageView view, int w, int h, float radius) {
        view.setTag(R.id.icon, url);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        if (url == null) {
            view.setImageDrawable(null);
            return;
        }
        String key = url + "@" + w + "x" + h;
        Bitmap hit = memory.get(key);
        if (hit != null) {
            view.setImageDrawable(rounded(view, hit, radius));
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bmp = null;
            File f = Net.cached(ctx, url);
            if (f != null) {
                Bitmap src = decode(f, w, h);
                if (src != null) {
                    bmp = cropTo(src, w, h);
                    if (bmp != src) src.recycle();
                }
            }
            if (bmp != null) memory.put(key, bmp);
            Bitmap result = bmp;
            main.post(() -> {
                if (result != null && url.equals(view.getTag(R.id.icon))) view.setImageDrawable(rounded(view, result, radius));
            });
        });
    }

    private static android.graphics.drawable.Drawable rounded(ImageView view, Bitmap bmp, float radius) {
        androidx.core.graphics.drawable.RoundedBitmapDrawable d =
                androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(view.getResources(), bmp);
        d.setCornerRadius(radius);
        d.setAntiAlias(true);
        return d;
    }

    /** Centre crop to exactly w×h. */
    private static Bitmap cropTo(Bitmap src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        new android.graphics.Canvas(out).drawBitmap(src, m, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Decodes url to about w×h pixels in the background; done gets the bitmap (or null) on the main thread. */
    void load(String url, int w, int h, Consumer<Bitmap> done) {
        String key = url + "@" + w;
        Bitmap hit = memory.get(key);
        if (hit != null) {
            done.accept(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap bmp = null;
            File f = Net.cached(ctx, url);
            if (f != null) bmp = decode(f, w, h);
            if (bmp != null) memory.put(key, bmp);
            Bitmap result = bmp;
            main.post(() -> done.accept(result));
        });
    }

    /** Decodes at the largest power-of-two step not below the target, then scales to cover w×h exactly once. */
    static Bitmap decode(File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            int sw = o.outWidth / sample;
            // One more step down to the target width while decoding, without a second full-size copy.
            if (sw > w * 1.15f) {
                o.inScaled = true;
                o.inDensity = sw;
                o.inTargetDensity = w;
            }
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    void trimMemory() {
        memory.evictAll();
    }

    void close() {
        pool.shutdownNow();
    }
}
