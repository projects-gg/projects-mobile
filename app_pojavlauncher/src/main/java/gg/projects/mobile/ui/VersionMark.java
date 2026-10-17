package gg.projects.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.content.DefaultVersion;
import gg.projects.mobile.join.DefaultProfiles;

/**
 * The "Önerilen" mark of Minecraft version lists (the desktop's version badge): the recommended
 * version, the one the default and new profiles start on ({@link DefaultProfiles#recommended()}),
 * carries the mod screen's gold "Yeni" badge after its id. Upstream's rows are single TextViews
 * (list items, spinner items), so the badge is a span of the row's own text: the layouts stay as
 * upstream ships them and screen readers say "26.1.2 Önerilen".
 */
public final class VersionMark {
    private VersionMark() {}

    /** {@code versionId}, followed by the badge when it is the recommended version. */
    @MainThread
    public static CharSequence label(Context context, String versionId) {
        if (!DefaultVersion.isRecommended(versionId, DefaultProfiles.recommended())) return versionId;
        SpannableStringBuilder text = new SpannableStringBuilder(versionId).append(' ');
        int start = text.length();
        text.append(context.getString(R.string.projects_version_recommended));
        text.setSpan(new Badge(context), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /** Puts {@link #label} on a list or spinner row whose text is {@code versionId}; returns the row. */
    @MainThread
    public static View mark(View row, String versionId) {
        if (row instanceof TextView) ((TextView) row).setText(label(row.getContext(), versionId));
        return row;
    }

    /**
     * The "Yeni" badge of the mod screen ({@code item_projects_mod.xml}) drawn inside a line of text:
     * {@code projects_badge_gold} behind Figtree semibold 11.5sp in gold 300, 6dp side padding, 8dp
     * after the version, centred on the version like the badge beside a mod's title.
     */
    private static final class Badge extends ReplacementSpan {
        private final Drawable background;
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final int padH;
        private final int padV;
        private final int gap;

        Badge(Context context) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            background = ContextCompat.getDrawable(context, R.drawable.projects_badge_gold);
            label.setColor(ContextCompat.getColor(context, R.color.projects_gold_300));
            label.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
            label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, metrics));
            padH = Math.round(6 * metrics.density);
            padV = Math.round(1 * metrics.density);
            gap = Math.round(8 * metrics.density);
        }

        /** Space before the badge; the text's own space counts towards the 8dp. */
        private int marginStart(Paint paint) {
            return Math.max(0, gap - Math.round(paint.measureText(" ")));
        }

        private int width(CharSequence text, int start, int end) {
            return Math.round(label.measureText(text, start, end)) + 2 * padH;
        }

        private int height() {
            Paint.FontMetricsInt fm = label.getFontMetricsInt();
            return fm.bottom - fm.top + 2 * padV;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            if (fm != null) {
                // The line keeps the version's height; it grows only if the badge would not fit.
                paint.getFontMetricsInt(fm);
                int extra = height() - (fm.descent - fm.ascent);
                if (extra > 0) {
                    fm.ascent -= extra / 2;
                    fm.descent += extra - extra / 2;
                    fm.top = Math.min(fm.top, fm.ascent);
                    fm.bottom = Math.max(fm.bottom, fm.descent);
                }
            }
            return marginStart(paint) + width(text, start, end);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, @NonNull Paint paint) {
            Paint.FontMetricsInt line = paint.getFontMetricsInt();
            int height = height();
            int left = Math.round(x) + marginStart(paint);
            // Centred between the version's ascent and descent: on its digits.
            int badgeTop = y + (line.ascent + line.descent) / 2 - height / 2;
            if (background != null) {
                background.setBounds(left, badgeTop, left + width(text, start, end), badgeTop + height);
                background.draw(canvas);
            }
            canvas.drawText(text, start, end, left + padH, badgeTop + padV - label.getFontMetricsInt().top, label);
        }
    }
}
