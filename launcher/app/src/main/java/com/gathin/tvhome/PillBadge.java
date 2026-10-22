package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * A status mark in the top-right corner of a round top-bar button, drawn as a layer of the button's background so
 * it moves, zooms and fades with it (not as the foreground: a single-line TextView keeps a horizontal scroll that
 * shifts its foreground, while the background is drawn without it): a red dot (the VPN is down) or a small blue capsule with a number (sleep-timer minutes).
 * Hidden while empty.
 */
final class PillBadge extends Drawable {
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean dot;
    private String label;

    PillBadge(float density, Typeface typeface) {
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2 * density);
        edge.setColor(0xFF000000);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(9 * density);
        text.setTextAlign(Paint.Align.CENTER);
        if (typeface != null) text.setTypeface(typeface);
    }

    /** The red dot, or nothing. */
    void setDot(boolean on) {
        if (dot == on && label == null) return;
        dot = on;
        label = null;
        fill.setColor(0xFFFF453A);
        invalidateSelf();
    }

    /** A blue capsule with this text, or nothing for null. */
    void setLabel(String value) {
        if (value == null ? label == null && !dot : value.equals(label)) return;
        label = value;
        dot = false;
        fill.setColor(0xFF7B8CFF);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (dot) {
            float r = 5.5f * density;
            c.drawCircle(b.right - r, b.top + r, r, fill);
            c.drawCircle(b.right - r, b.top + r, r - density, edge);
        } else if (label != null) {
            float h = 15 * density;
            float w = Math.max(18 * density, text.measureText(label) + 9 * density);
            rect.set(b.right - w + 2 * density, b.top - 2 * density, b.right + 2 * density, b.top - 2 * density + h);
            c.drawRoundRect(rect, h / 2, h / 2, fill);
            c.drawRoundRect(rect, h / 2, h / 2, edge);
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, text);
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
