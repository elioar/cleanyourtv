package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal home's content rows between the dock and the app grid: the Stremio row (Continue watching, then
 * trending) and the news row. Each row is a label line over a track of cards that slides so the focused card
 * stays in view; the group labels ride along with their first card. Keys: Left and Right move along the row,
 * Up and Down leave it (the listener decides where to), OK opens the card.
 */
final class ContentRows extends LinearLayout {
    static final int KIND_STREMIO = 0, KIND_NEWS = 1, KIND_TV = 2;

    interface Listener {
        void onCardFocused(int kind, Object item, int index);

        void onCardClicked(int kind, Object item);

        /** Up (true) or Down (false) from the row at this position (0 = first shown row). */
        void onRowExit(int rowPosition, boolean up);
    }

    private final class Row {
        final int kind;
        final LinearLayout track;
        final List<View> cards = new ArrayList<>();
        final List<Object> items = new ArrayList<>();
        int focused;
        View view;

        Row(int kind, LinearLayout track) {
            this.kind = kind;
            this.track = track;
        }
    }

    private final float density;
    private final ImageLoader images;
    private final Listener listener;
    private final boolean animations;
    private final Typeface regular, medium;
    private final List<Row> rows = new ArrayList<>();
    private List<HomeFeeds.Movie> cw = new ArrayList<>(), trending = new ArrayList<>();
    private List<HomeFeeds.News> news = new ArrayList<>();
    private String newsLabel = "";
    private List<NowOnTv.Channel> tv = new ArrayList<>();
    private boolean stremioOn, newsOn, tvOn;

    ContentRows(Context ctx, ImageLoader images, Listener listener, boolean animations) {
        super(ctx);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.images = images;
        this.listener = listener;
        this.animations = animations;
        regular = ctx.getResources().getFont(R.font.outfit_regular);
        medium = ctx.getResources().getFont(R.font.outfit_medium);
        // Cards never take focus on their own (Android's first-focus search, a tile rebinding): only focusRow().
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    /** Rebuild waiting because a card had focus when new data came. */
    private boolean dirty;

    /** Focus has gone back to the tiles: cards stop taking focus, and a waiting rebuild runs. */
    void release() {
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        if (dirty) {
            dirty = false;
            rebuild();
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    void setEnabledRows(boolean stremio, boolean tvRow, boolean newsRow) {
        stremioOn = stremio;
        tvOn = tvRow;
        newsOn = newsRow;
        rebuildOrWait();
    }

    /** Now on TV: one card per channel with something on now in the guide. */
    void setNowOnTv(List<NowOnTv.Channel> channels) {
        tv = channels;
        rebuildOrWait();
    }

    void setStremio(List<HomeFeeds.Movie> continueWatching, List<HomeFeeds.Movie> top) {
        cw = continueWatching;
        trending = top;
        rebuildOrWait();
    }

    void setNews(List<HomeFeeds.News> items, String label) {
        news = items;
        newsLabel = label;
        rebuildOrWait();
    }

    /** New data never moves the cards from under the owner: it waits until focus leaves the rows. */
    private void rebuildOrWait() {
        if (focusedRow() >= 0) {
            dirty = true;
            return;
        }
        rebuild();
    }

    int rowCount() {
        return rows.size();
    }

    int kindAt(int position) {
        return rows.get(position).kind;
    }

    View rowView(int position) {
        return rows.get(position).view;
    }

    /** Which shown row holds focus, or -1. */
    int focusedRow() {
        View f = findFocus();
        for (int i = 0; f != null && i < rows.size(); i++) {
            if (rows.get(i).cards.contains(f)) return i;
        }
        return -1;
    }

    /** Focuses the row at this position on the card it last had. */
    boolean focusRow(int position) {
        if (position < 0 || position >= rows.size()) return false;
        Row r = rows.get(position);
        if (r.cards.isEmpty()) return false;
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        return r.cards.get(Math.min(r.focused, r.cards.size() - 1)).requestFocus();
    }

    /** Back to each row's first card (Home pressed). */
    void resetPositions() {
        for (Row r : rows) {
            r.focused = 0;
            r.track.setTranslationX(0f);
        }
    }

    /** Runs only while no card has focus (rebuildOrWait); each row keeps the card it last had. */
    private void rebuild() {
        int[] kept = new int[3];
        for (Row r : rows) kept[r.kind] = r.focused;
        removeAllViews();
        rows.clear();
        if (stremioOn && (!cw.isEmpty() || !trending.isEmpty())) addStremioRow(kept[KIND_STREMIO]);
        if (tvOn && !tv.isEmpty()) addTvRow(kept[KIND_TV]);
        if (newsOn && !news.isEmpty()) addNewsRow(kept[KIND_NEWS]);
    }

    // ---- rows -------------------------------------------------------------------------------------

    private Row newRow(int kind) {
        LinearLayout section = new LinearLayout(getContext());
        section.setOrientation(VERTICAL);
        section.setClipChildren(false);
        section.setClipToPadding(false);
        LinearLayout track = new LinearLayout(getContext());
        track.setOrientation(HORIZONTAL);
        track.setClipChildren(false);
        track.setClipToPadding(false);
        section.addView(track, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(24);
        addView(section, lp);
        Row r = new Row(kind, track);
        r.view = section;
        rows.add(r);
        return r;
    }

    private void addStremioRow(int keep) {
        Row r = newRow(KIND_STREMIO);
        for (int i = 0; i < cw.size(); i++) {
            HomeFeeds.Movie m = cw.get(i);
            boolean wide = i == 0;
            addCard(r, m, i == 0 ? getContext().getString(R.string.row_continue) : null,
                    movieCard(m, wide), wide ? 202 : 96);
        }
        for (int i = 0; i < trending.size(); i++) {
            HomeFeeds.Movie m = trending.get(i);
            addCard(r, m, i == 0 ? getContext().getString(R.string.row_trending) : null, movieCard(m, false), 96);
        }
        r.focused = Math.min(keep, r.cards.size() - 1);
    }

    private void addNewsRow(int keep) {
        Row r = newRow(KIND_NEWS);
        for (int i = 0; i < news.size(); i++) {
            HomeFeeds.News n = news.get(i);
            addCard(r, n, i == 0 ? newsLabel : null, newsCard(n), 173);
        }
        r.focused = Math.min(keep, r.cards.size() - 1);
    }

    private void addTvRow(int keep) {
        Row r = newRow(KIND_TV);
        long now = System.currentTimeMillis();
        for (int i = 0; i < tv.size(); i++) {
            addCard(r, tv.get(i), i == 0 ? getContext().getString(R.string.row_now_on_tv) : null, tvCard(tv.get(i), now), 208);
        }
        r.focused = Math.min(keep, r.cards.size() - 1);
    }

    /** One column: the group's label (or an empty line of the same height), then the card. */
    private void addCard(Row r, Object item, String label, FrameLayout card, int widthDp) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        TextView l = new TextView(getContext());
        l.setText(label == null ? "" : label.toUpperCase());
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        l.setLetterSpacing(0.2f);
        l.setTypeface(medium);
        l.setTextColor(0x80FFFFFF);
        l.setSingleLine(true);
        // A label is as wide as it needs: it runs on over the following cards, like the design preview.
        LayoutParams llp = new LayoutParams(dp(360), dp(16));
        llp.bottomMargin = dp(8);
        column.addView(l, llp);
        column.addView(card);
        LayoutParams clp = new LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!r.cards.isEmpty()) clp.setMarginStart(dp(14));
        // The label may be wider than its card; let it draw past the column instead of widening it.
        column.setClipChildren(false);
        r.track.addView(column, clp);
        final int index = r.cards.size();
        r.cards.add(card);
        r.items.add(item);
        card.setOnClickListener(v -> listener.onCardClicked(r.kind, item));
        card.setOnFocusChangeListener((v, focused) -> {
            float s = focused && animations ? 1.06f : 1f;
            v.animate().cancel();
            if (animations) v.animate().scaleX(s).scaleY(s).setDuration(200).start();
            else {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
            if (focused) {
                r.focused = index;
                slideTo(r, index);
                listener.onCardFocused(r.kind, item, index);
            }
        });
        card.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = rows.indexOf(r);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (index > 0) r.cards.get(index - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (index < r.cards.size() - 1) r.cards.get(index + 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    listener.onRowExit(pos, true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    listener.onRowExit(pos, false);
                    return true;
                default:
                    return false;
            }
        });
        // Labels sit over the first column of each group; let them overflow to the right.
        l.setMaxLines(1);
        l.setEllipsize(null);
        l.setHorizontallyScrolling(true);
    }

    /** Keeps two cards visible before the focused one, as the design preview does. */
    private void slideTo(Row r, int index) {
        float x = 0f;
        if (index > 2) {
            View first = (View) r.cards.get(0).getParent();
            View anchor = (View) r.cards.get(index - 2).getParent();
            x = -(anchor.getLeft() - first.getLeft());
        }
        if (r.track.getTranslationX() == x) return;
        r.track.animate().cancel();
        if (animations) r.track.animate().translationX(x).setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
        else r.track.setTranslationX(x);
    }

    // ---- cards ------------------------------------------------------------------------------------

    private FrameLayout baseCard(int wDp, int hDp, float radiusDp) {
        FrameLayout card = new FrameLayout(getContext());
        card.setLayoutParams(new LayoutParams(dp(wDp), dp(hDp)));
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF15161C);
        bg.setCornerRadius(dp(radiusDp));
        card.setBackground(bg);
        // Focus ring drawn over the artwork.
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(Math.max(2, dp(1.5f)), 0xD9FFFFFF);
        ring.setCornerRadius(dp(radiusDp));
        StateListDrawable fg = new StateListDrawable();
        fg.addState(new int[]{android.R.attr.state_focused}, ring);
        card.setForeground(fg);
        // No outline clip: the pictures and the shade carry their own rounded corners (ImageLoader.intoRounded),
        // because clipping every card on every frame of a row's slide was too much for the TV's GPU.
        return card;
    }

    private ImageView art(FrameLayout card) {
        ImageView img = new ImageView(getContext());
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return img;
    }

    private View shade(FrameLayout card, int fromAlpha) {
        View s = new View(getContext());
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{fromAlpha << 24, 0x00000000});
        g.setCornerRadius(dp(8));
        s.setBackground(g);
        card.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return s;
    }

    private TextView text(FrameLayout card, String value, float sp, Typeface tf, int gravity, int maxLines) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(Color.WHITE);
        t.setMaxLines(maxLines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        card.addView(t, lp);
        return t;
    }

    private FrameLayout movieCard(HomeFeeds.Movie m, boolean wide) {
        FrameLayout card = baseCard(wide ? 202 : 96, 144, 8);
        ImageView img = art(card);
        if (wide) {
            // Continue watching's first card: the backdrop, its title, time left and progress.
            images.intoRounded(m.background.replace("/medium/", "/small/"), img, dp(202), dp(144), dp(8));
            shade(card, 0xD9);
            TextView t = text(card, m.name, 15f, medium, Gravity.BOTTOM, 2);
            ((FrameLayout.LayoutParams) t.getLayoutParams()).bottomMargin = dp(16);
        } else {
            images.intoRounded(m.poster, img, dp(96), dp(144), dp(8));
            if (m.progress >= 0) shade(card, 0x99);
        }
        if (m.progress >= 0) {
            FrameLayout bar = new FrameLayout(getContext());
            GradientDrawable track = new GradientDrawable();
            track.setColor(0x4DFFFFFF);
            track.setCornerRadius(dp(2));
            bar.setBackground(track);
            View fill = new View(getContext());
            GradientDrawable f = new GradientDrawable();
            f.setColor(Color.WHITE);
            f.setCornerRadius(dp(2));
            fill.setBackground(f);
            bar.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM);
            blp.setMargins(dp(7), 0, dp(7), dp(6));
            card.addView(bar, blp);
            float p = Math.max(0.02f, Math.min(1f, m.progress));
            bar.post(() -> {
                fill.getLayoutParams().width = Math.round(bar.getWidth() * p);
                fill.requestLayout();
            });
            if (wide && m.minutesLeft > 0) {
                TextView left = new TextView(getContext());
                left.setText(getContext().getString(R.string.minutes_left, m.minutesLeft));
                left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
                left.setTypeface(regular);
                left.setTextColor(Color.WHITE);
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(0x99000000);
                pill.setCornerRadius(dp(99));
                left.setBackground(pill);
                left.setPadding(dp(6), dp(1), dp(6), dp(2));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
                lp.setMargins(0, dp(6), dp(6), 0);
                card.addView(left, lp);
            }
        }
        card.setContentDescription(m.name);
        return card;
    }

    /**
     * A channel card, text only (no pictures to load): number and name with a red LIVE tag, the programme, its
     * times, how far into it we are, and what is next.
     */
    private FrameLayout tvCard(NowOnTv.Channel ch, long now) {
        FrameLayout card = baseCard(208, 112, 8);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1C2232, 0xFF0D0F15});
        g.setCornerRadius(dp(8));
        card.setBackground(g);
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        boolean guide = ch.title != null;
        TextView name = line(guide ? (ch.number.isEmpty() ? "" : ch.number + "  ") + ch.name : ch.number, 10f, medium, 0xCCFFFFFF);
        top.addView(name, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView live = line("LIVE", 8f, medium, Color.WHITE);
        live.setLetterSpacing(0.1f);
        GradientDrawable red = new GradientDrawable();
        red.setColor(0xFFD93025);
        red.setCornerRadius(dp(3));
        live.setBackground(red);
        live.setPadding(dp(5), dp(1), dp(5), dp(1));
        top.addView(live);
        col.addView(top);

        if (!guide) {
            // No guide for this channel: its name, large, is the card.
            TextView big = line(ch.name, 17f, medium, Color.WHITE);
            LayoutParams glp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.topMargin = dp(14);
            col.addView(big, glp);
            card.setContentDescription(ch.number + " " + ch.name);
            return card;
        }
        TextView title = line(ch.title, 13f, medium, Color.WHITE);
        LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        col.addView(title, tlp);
        col.addView(line(NowOnTv.times(getContext(), ch.start, ch.end), 9f, regular, 0x99FFFFFF));

        FrameLayout bar = new FrameLayout(getContext());
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x33FFFFFF);
        track.setCornerRadius(dp(2));
        bar.setBackground(track);
        View fill = new View(getContext());
        GradientDrawable f = new GradientDrawable();
        f.setColor(0xFFD93025);
        f.setCornerRadius(dp(2));
        fill.setBackground(f);
        bar.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        blp.topMargin = dp(6);
        col.addView(bar, blp);
        float p = Math.max(0.02f, ch.progress(now));
        bar.post(() -> {
            fill.getLayoutParams().width = Math.round(bar.getWidth() * p);
            fill.requestLayout();
        });

        if (ch.nextTitle != null) {
            TextView next = line(getContext().getString(R.string.tv_next, ch.nextTitle), 9f, regular, 0x99FFFFFF);
            LayoutParams nlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(5);
            col.addView(next, nlp);
        }
        card.setContentDescription(ch.name + ", " + ch.title);
        return card;
    }

    private TextView line(String value, float sp, Typeface tf, int color) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private FrameLayout newsCard(HomeFeeds.News n) {
        FrameLayout card = baseCard(173, 96, 8);
        String thumb = n.thumbnail();
        if (thumb != null) {
            ImageView img = art(card);
            images.intoRounded(thumb, img, dp(173), dp(96), dp(8));
            shade(card, 0xE6);
        } else {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1B2433, 0xFF0E1016});
            g.setCornerRadius(dp(8));
            card.setBackground(g);
        }
        // Top line: where it is from and when; a red LIVE tag for a live stream.
        TextView top = text(card, n.live ? "LIVE" : (n.source + " · " + HomeFeeds.ago(getContext(), n.published)).toUpperCase(),
                8f, medium, Gravity.TOP, 1);
        top.setLetterSpacing(0.1f);
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) top.getLayoutParams();
        tlp.topMargin = dp(7);
        if (n.live) {
            GradientDrawable red = new GradientDrawable();
            red.setColor(0xFFD93025);
            red.setCornerRadius(dp(3));
            top.setBackground(red);
            top.setPadding(dp(5), dp(1), dp(5), dp(1));
            tlp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        } else {
            top.setTextColor(0xC0FFFFFF);
        }
        TextView h = text(card, n.title, 11f, medium, Gravity.BOTTOM, 3);
        ((FrameLayout.LayoutParams) h.getLayoutParams()).bottomMargin = dp(7);
        h.setLineSpacing(0, 1.05f);
        card.setContentDescription(n.title);
        return card;
    }
}
