package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The hero over minimal home's content rows: the focused title's backdrop on the right, faded into black towards
 * the text, and its logo (or name), source, description and details on the left. Two backdrop views crossfade.
 * MainActivity calls show() only after focus has rested on a card for a moment, so scrolling along a row never
 * downloads or decodes a backdrop per card.
 */
final class MovieHero {
    private final Context ctx;
    private final float density;
    private final ImageLoader images;
    private final boolean animations, reducedMotion;
    final FrameLayout backdrop;
    private final ImageView[] art = new ImageView[2];
    private int front;
    final LinearLayout text;
    private final ImageView logo;
    private final TextView title, source, description, meta;
    private String shownKey;
    private boolean visible;

    MovieHero(Context ctx, ImageLoader images, boolean animations, boolean reducedMotion) {
        this.ctx = ctx;
        this.images = images;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        density = ctx.getResources().getDisplayMetrics().density;
        Typeface light = ctx.getResources().getFont(R.font.outfit_light);
        Typeface regular = ctx.getResources().getFont(R.font.outfit_regular);
        Typeface medium = ctx.getResources().getFont(R.font.outfit_medium);

        backdrop = new FrameLayout(ctx);
        backdrop.setAlpha(0f);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < 2; i++) {
            art[i] = new ImageView(ctx);
            art[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            art[i].setAlpha(0f);
            // One bitmap each: their crossfade can apply alpha while drawing, no offscreen pass needed.
            art[i].forceHasOverlappingRendering(false);
            backdrop.addView(art[i]);
        }
        // Black from the left (behind the text) and from the bottom (behind the rows), as in the design preview.

        text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setAlpha(0f);
        logo = new ImageView(ctx);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setMaxHeight(dp(84));
        logo.setMaxWidth(dp(340));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.bottomMargin = dp(12);
        text.addView(logo, llp);
        title = tv(light, 40f, Color.WHITE);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(dp(10), 0, dp(2), 0x99000000);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(10);
        text.addView(title, tlp);
        source = tv(medium, 12f, 0xE6FFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(6);
        text.addView(source, slp);
        description = tv(regular, 13f, 0xE0FFFFFF);
        description.setMaxLines(3);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(10);
        text.addView(description, dlp);
        meta = tv(regular, 12f, Color.WHITE);
        text.addView(meta);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Backdrop composer: one picture at a time, off the main thread. */
    private final java.util.concurrent.ExecutorService composer = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** The composed backdrop is half the screen's size, stretched: a smaller texture, and soft anyway. */
    private static final int BACK_W = 960, BACK_H = 540;

    /**
     * The whole backdrop in one opaque picture: black, the artwork in the right 72% and top 82% (as the design
     * preview places it), and the fades to black on the left and at the bottom painted in. The GPU then draws one
     * opaque picture per frame instead of the artwork plus two full-screen gradients over it, which was most of
     * what made the content rows stutter on this TV.
     */
    private static Bitmap compose(Bitmap art) {
        Bitmap out = Bitmap.createBitmap(BACK_W, BACK_H, Bitmap.Config.RGB_565);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        c.drawColor(0xFF000000);
        android.graphics.RectF area = new android.graphics.RectF(BACK_W * 0.28f, 0, BACK_W, BACK_H * 0.82f);
        float scale = Math.max(area.width() / art.getWidth(), area.height() / art.getHeight());
        float dw = art.getWidth() * scale, dh = art.getHeight() * scale;
        android.graphics.RectF dst = new android.graphics.RectF(area.centerX() - dw / 2, area.centerY() - dh / 2,
                area.centerX() + dw / 2, area.centerY() + dh / 2);
        c.save();
        c.clipRect(area);
        c.drawBitmap(art, null, dst, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        c.restore();
        android.graphics.Paint p = new android.graphics.Paint();
        p.setShader(new android.graphics.LinearGradient(0, 0, BACK_W, 0,
                new int[]{0xFF000000, 0xFF000000, 0xBF000000, 0x00000000}, new float[]{0f, 0.22f, 0.42f, 0.75f},
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, BACK_W, BACK_H, p);
        p.setShader(new android.graphics.LinearGradient(0, BACK_H, 0, 0,
                new int[]{0xFF000000, 0xFF000000, 0x99000000, 0x00000000}, new float[]{0f, 0.40f, 0.55f, 0.82f},
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, BACK_W, BACK_H, p);
        return out;
    }

    private TextView tv(Typeface tf, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** The composed backdrop already places the artwork; its views just fill the screen. */
    void layout(int screenW, int screenH) {
        for (ImageView a : art) {
            a.setScaleType(ImageView.ScaleType.FIT_XY);
            a.setLayoutParams(new FrameLayout.LayoutParams(screenW, screenH));
        }
    }

    boolean isVisible() {
        return visible;
    }

    void showMovie(HomeFeeds.Movie m) {
        String key = "m:" + m.id;
        if (key.equals(shownKey) && visible) return;
        shownKey = key;
        title.setText(m.name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f);
        title.setVisibility(View.VISIBLE);
        logo.setVisibility(View.GONE);
        logo.setImageDrawable(null);
        // The title's logo replaces the text name once it has loaded.
        images.load(m.logo, dp(340), dp(84), bmp -> {
            if (bmp == null || !key.equals(shownKey)) return;
            logo.setImageBitmap(bmp);
            logo.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE);
        });
        source.setText(m.progress >= 0 ? ctx.getString(R.string.hero_stremio_continue) : "Stremio");
        description.setText(m.description);
        SpannableStringBuilder line = new SpannableStringBuilder();
        if (!m.rating.isEmpty()) {
            int s = line.length();
            line.append(" IMDb ");
            line.setSpan(new BackgroundColorSpan(0xFFF5C518), s, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setSpan(new ForegroundColorSpan(Color.BLACK), s, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setSpan(new StyleSpan(Typeface.BOLD), s, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setSpan(new RelativeSizeSpan(0.8f), s, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.append("  ").append(m.rating);
        }
        dot(line, m.genres);
        dot(line, m.year);
        dot(line, m.runtime);
        if (m.minutesLeft > 0) dot(line, ctx.getString(R.string.minutes_left, m.minutesLeft));
        meta.setText(line);
        swapBackdrop(m.background, key);
        reveal();
    }

    void showNews(HomeFeeds.News n, String country, boolean youtube) {
        String key = "n:" + n.link;
        if (key.equals(shownKey) && visible) return;
        shownKey = key;
        logo.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        title.setText(n.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        source.setText(n.source + (n.published > 0 ? " · " + HomeFeeds.ago(ctx, n.published) : ""));
        description.setText(n.description == null ? "" : n.description.replace('\n', ' '));
        SpannableStringBuilder line = new SpannableStringBuilder(n.live ? ctx.getString(R.string.news_live) : "");
        dot(line, country);
        dot(line, ctx.getString(youtube ? R.string.news_youtube : R.string.news_web));
        meta.setText(line);
        swapBackdrop(n.videoId != null ? "https://i.ytimg.com/vi/" + n.videoId + "/maxresdefault.jpg" : n.image, key);
        reveal();
    }

    /** A Now on TV card: the programme, its channel, and "● LIVE · times · X min left · genre · Next: …". */
    void showChannel(NowOnTv.Channel ch) {
        long now = System.currentTimeMillis();
        String key = "t:" + ch.id + ":" + ch.start;
        if (key.equals(shownKey) && visible) return;
        shownKey = key;
        logo.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        if (ch.title == null) {
            // No guide for it: the channel itself.
            title.setText(ch.name);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f);
            source.setText(ctx.getString(R.string.tv_channel_number, ch.number));
            description.setText("");
            SpannableStringBuilder live = new SpannableStringBuilder("● LIVE");
            live.setSpan(new ForegroundColorSpan(0xFFFF5A4E), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            live.setSpan(new StyleSpan(Typeface.BOLD), 2, live.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            dot(live, ctx.getString(R.string.tv_antenna));
            meta.setText(live);
            swapBackdrop(null, key);
            reveal();
            return;
        }
        title.setText(ch.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
        source.setText((ch.number.isEmpty() ? "" : ch.number + "  ") + ch.name);
        description.setText(ch.description == null ? "" : ch.description.replace('\n', ' '));
        SpannableStringBuilder line = new SpannableStringBuilder();
        line.append("● LIVE");
        line.setSpan(new ForegroundColorSpan(0xFFFF5A4E), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new StyleSpan(Typeface.BOLD), 2, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        dot(line, NowOnTv.times(ctx, ch.start, ch.end));
        dot(line, ctx.getString(R.string.minutes_left, ch.minutesLeft(now)));
        dot(line, ch.genre);
        if (ch.nextTitle != null) dot(line, ctx.getString(R.string.tv_next_at, NowOnTv.time(ctx, ch.nextStart), ch.nextTitle));
        meta.setText(line);
        // The guide rarely has pictures; without one the backdrop stays black behind the text.
        swapBackdrop(ch.poster != null && !ch.poster.isEmpty() ? ch.poster : null, key);
        reveal();
    }

    private static void dot(SpannableStringBuilder line, String part) {
        if (part == null || part.isEmpty()) return;
        if (line.length() > 0) {
            int s = line.length();
            line.append("   ·   ");
            line.setSpan(new ForegroundColorSpan(0x80FFFFFF), s, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        line.append(part);
    }

    private void reveal() {
        visible = true;
        long d = !animations ? 0 : reducedMotion ? 120 : 420;
        text.animate().cancel();
        text.setAlpha(0f);
        text.setTranslationY(dp(8));
        if (d == 0) {
            text.setAlpha(1f);
            text.setTranslationY(0f);
        } else {
            text.animate().alpha(1f).translationY(0f).setDuration(d).withLayer().start();
        }
        backdrop.animate().cancel();
        if (d == 0) backdrop.setAlpha(1f);
        else backdrop.animate().alpha(1f).setDuration(d).start();
    }

    /** Loads about 1280 px of backdrop and crossfades it in; null clears to black. */
    private void swapBackdrop(String url, String key) {
        int back = 1 - front;
        if (url == null) {
            fade(art[front], 0f);
            return;
        }
        images.load(url, 1280, 720, bmp -> {
            if (!key.equals(shownKey)) return;
            if (bmp == null) {
                fade(art[front], 0f);
                return;
            }
            composer.execute(() -> {
                Bitmap composed;
                try {
                    composed = compose(bmp);
                } catch (OutOfMemoryError e) {
                    return;
                }
                main.post(() -> {
                    if (!key.equals(shownKey)) return;
                    art[back].setImageBitmap(composed);
                    fade(art[back], 1f);
                    fade(art[front], 0f);
                    front = back;
                });
            });
        });
    }

    /** Crossfade; a picture that has faded out is not drawn at all (alpha 0 is skipped), only one stays on. */
    private void fade(ImageView v, float to) {
        v.animate().cancel();
        if (!animations) v.setAlpha(to);
        else v.animate().alpha(to).setDuration(reducedMotion ? 120 : 350).start();
    }

    void hide() {
        if (!visible) return;
        visible = false;
        shownKey = null;
        long d = !animations ? 0 : reducedMotion ? 100 : 300;
        text.animate().cancel();
        backdrop.animate().cancel();
        if (d == 0) {
            text.setAlpha(0f);
            backdrop.setAlpha(0f);
        } else {
            text.animate().alpha(0f).setDuration(d).withLayer().start();
            backdrop.animate().alpha(0f).setDuration(d).start();
        }
    }

    void close() {
        composer.shutdownNow();
    }

    /** Lets go of the backdrop pictures (Home in the background). */
    void release() {
        for (ImageView a : art) {
            a.setImageDrawable(null);
            a.setAlpha(0f);
        }
        shownKey = null;
    }
}
