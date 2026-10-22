package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal home's own wallpaper (launcher settings): the ambient glow (nothing behind the tiles), one photo, or a
 * slideshow. A chosen photo is copied once, downscaled to the screen, into the app's own storage, so it survives
 * the USB drive being unplugged and is never decoded at full size on Home. A slideshow keeps only a list of paths
 * (slides.txt); WallpaperPlayer decodes one picture at a time, as it does for a theme's collection.
 */
final class HomeWallpaper {
    static final String MODE_KEY = "home_wallpaper_mode";
    static final String MODE_GLOW = "glow", MODE_PHOTO = "photo", MODE_SLIDESHOW = "slideshow";
    static final String DIM_KEY = "home_wallpaper_dim";
    static final String INTERVAL_KEY = "home_slideshow_minutes";
    static final String SOURCE_KEY = "home_slideshow_source";
    static final String PHOTO_NAME_KEY = "home_photo_name";
    static final String SOURCE_PHOTOS = "photos";
    static final int[] DIMS = {30, 50, 70};
    static final int[] INTERVALS = {1, 5, 30, 60};
    /** Longest edge a copied photo keeps: the screen's width, no more. */
    private static final int MAX_EDGE = 1920;
    /** Photos a slideshow walks through, newest first. */
    private static final int MAX_SLIDES = 60;

    private HomeWallpaper() {
    }

    static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "home_wallpaper");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static File photoFile(Context ctx) {
        return new File(dir(ctx), "photo.jpg");
    }

    /** The chosen photo as a one-item list, or empty when none was chosen yet. */
    static List<File> photo(Context ctx) {
        List<File> out = new ArrayList<>();
        File f = photoFile(ctx);
        if (f.isFile() && f.length() > 0) out.add(f);
        return out;
    }

    /** The slideshow's pictures that still exist (a USB drive may be gone), or empty. */
    static List<File> slides(Context ctx) {
        List<File> out = new ArrayList<>();
        File list = new File(dir(ctx), "slides.txt");
        if (!list.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(list))) {
            String line;
            while ((line = r.readLine()) != null) {
                File f = new File(line.trim());
                if (!line.trim().isEmpty() && f.isFile()) out.add(f);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    static int dim(SharedPreferences prefs) {
        int d = prefs.getInt(DIM_KEY, 50);
        return d >= 0 && d <= 90 ? d : 50;
    }

    static int intervalMinutes(SharedPreferences prefs) {
        int m = prefs.getInt(INTERVAL_KEY, 5);
        return m >= 1 && m <= 1440 ? m : 5;
    }

    /** One picture found on the TV or a USB drive. */
    static final class Picture {
        final String path;
        final String name;

        Picture(String path, String name) {
            this.path = path;
            this.name = name;
        }
    }

    /**
     * Pictures Android has indexed, newest first: the TV's own storage and any mounted USB drive. Needs
     * READ_EXTERNAL_STORAGE; without it the query returns nothing and the caller says so.
     */
    static List<Picture> listPictures(Context ctx, int limit) {
        List<Picture> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        String[] cols = {MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.WIDTH};
        List<android.net.Uri> volumes = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            for (String v : MediaStore.getExternalVolumeNames(ctx)) volumes.add(MediaStore.Images.Media.getContentUri(v));
        } else {
            volumes.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        }
        for (android.net.Uri uri : volumes) {
            try (Cursor c = cr.query(uri, cols, null, null, MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
                if (c == null) continue;
                while (c.moveToNext() && out.size() < limit) {
                    String path = c.getString(0);
                    if (path == null || !new File(path).isFile()) continue;
                    // Skip icons and thumbnails: anything narrower than 640 px would be a blur across the screen.
                    if (!c.isNull(2) && c.getInt(2) > 0 && c.getInt(2) < 640) continue;
                    String name = c.getString(1);
                    out.add(new Picture(path, name != null ? name : new File(path).getName()));
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // no permission, or a volume that went away
            }
        }
        return out;
    }

    /** Copies one picture, downscaled to cover the screen, as the wallpaper photo. Runs off the main thread. */
    static boolean importPhoto(Context ctx, String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return false;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src;
        try {
            src = BitmapFactory.decodeFile(path, o);
        } catch (OutOfMemoryError e) {
            return false;
        }
        if (src == null) return false;
        Bitmap out = src;
        int edge = Math.max(src.getWidth(), src.getHeight());
        if (edge > MAX_EDGE) {
            float k = MAX_EDGE / (float) edge;
            out = Bitmap.createBitmap(Math.round(src.getWidth() * k), Math.round(src.getHeight() * k), Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, null, new Rect(0, 0, out.getWidth(), out.getHeight()),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
        }
        File tmp = new File(dir(ctx), "photo.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            out.compress(Bitmap.CompressFormat.JPEG, 88, fos);
        } catch (IOException e) {
            return false;
        } finally {
            out.recycle();
        }
        return tmp.renameTo(photoFile(ctx));
    }

    /** Writes the slideshow list from the TV's and USB drive's pictures; false when there are none. */
    static boolean buildPhotoSlides(Context ctx) {
        List<Picture> pics = listPictures(ctx, MAX_SLIDES);
        if (pics.isEmpty()) return false;
        try (FileWriter w = new FileWriter(new File(dir(ctx), "slides.txt"))) {
            for (Picture p : pics) w.write(p.path + "\n");
        } catch (IOException e) {
            return false;
        }
        return true;
    }
}
