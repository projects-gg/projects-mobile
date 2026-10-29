package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.CrewLook;
import gg.projects.mobile.ui.AppDates;
import gg.projects.mobile.ui.Press;

/** Small view pieces the friends and crew screens share. */
public final class CrewViews {
    private CrewViews() {}

    /** {@code [GKR]} beside a crew's name: the crew colour at 24 % under bone text (the desktop's TeamTag). */
    public static void tintTag(TextView tag, @Nullable String color) {
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(4 * tag.getResources().getDisplayMetrics().density);
        chip.setColor(CrewLook.tagTint(color));
        tag.setBackground(chip);
    }

    /**
     * A full-width action row in a card or sheet: a 24 dp icon and its label, 52 dp tall, pressing
     * like the app's rows. {@code tint} colours both (flare for an action that ends something).
     */
    public static TextView actionRow(Context context, @DrawableRes int icon, CharSequence text, @ColorRes int tint,
                                     View.OnClickListener listener) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView row = new TextView(context);
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.setTextColor(ContextCompat.getColor(context, tint));
        try {
            row.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(Math.round(52 * dp));
        row.setPaddingRelative(Math.round(10 * dp), 0, Math.round(10 * dp), 0);
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        Drawable glyph = ContextCompat.getDrawable(context, icon);
        if (glyph != null) {
            glyph = glyph.mutate();
            glyph.setTint(ContextCompat.getColor(context, tint));
            int size = Math.round(22 * dp);
            glyph.setBounds(0, 0, size, size);
            row.setCompoundDrawablesRelative(glyph, null, null, null);
            row.setCompoundDrawablePadding(Math.round(14 * dp));
        }
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(listener);
        Press.attach(row, Press.Style.ROW);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** A hairline between groups of actions. */
    public static View divider(Context context) {
        View line = new View(context);
        float dp = context.getResources().getDisplayMetrics().density;
        line.setBackgroundColor(ContextCompat.getColor(context, R.color.projects_obsidian_700));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(dp)));
        params.topMargin = Math.round(6 * dp);
        params.bottomMargin = Math.round(6 * dp);
        line.setLayoutParams(params);
        return line;
    }

    /** A {@code YYYY-MM-DD} day in the app's language ("12 Eylül 2026", "12 September 2026"), or null. */
    @Nullable
    public static String day(Context context, @Nullable String isoDay) {
        Long at = AppDates.isoDay(isoDay);
        return at == null ? null : AppDates.longDate(AppDates.turkish(context), at, AppDates.UTC);
    }

    /** 0.4.5: {@link #day} for "… beri" / "since …" ("12 Eylül 2026'dan", "12 September 2026"), or null. */
    @Nullable
    public static String sinceDay(Context context, @Nullable String isoDay) {
        Long at = AppDates.isoDay(isoDay);
        return at == null ? null : AppDates.sinceDate(AppDates.turkish(context), at, AppDates.UTC);
    }

    /** A clock time, on 24 hours in both languages ("21:30"; MOB-POLISH: not the phone's 12-hour setting). */
    public static String time(Context context, long seconds) {
        return AppDates.time(seconds * 1000L);
    }

    /** "Bugün 21:30" / "Yarın 08:00", or the date for later days ("24 Ekim 2026 21:30", "24 October 2026 21:30"). */
    public static String moment(Context context, long seconds, long nowSeconds) {
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.setTimeInMillis(nowSeconds * 1000L);
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        long today = day.getTimeInMillis();
        day.add(java.util.Calendar.DAY_OF_MONTH, 1);
        long tomorrow = day.getTimeInMillis();
        day.add(java.util.Calendar.DAY_OF_MONTH, 1);
        long after = day.getTimeInMillis();
        long at = seconds * 1000L;
        String time = time(context, seconds);
        if (at >= today && at < tomorrow) return context.getString(R.string.projects_crew_today_at, time);
        if (at >= tomorrow && at < after) return context.getString(R.string.projects_crew_tomorrow_at, time);
        return AppDates.longDate(context, at) + " " + time;
    }

    /**
     * "Discord'dan mesaj at" in a card or sheet: {@link #actionRow} with Discord's own symbol in Blurple at its own
     * proportions (a Discord-branded action, design.md §6 Friends), the label as every row's.
     */
    public static TextView discordRow(Context context, CharSequence text, View.OnClickListener listener) {
        TextView row = actionRow(context, R.drawable.projects_ic_discord_glyph, text, R.color.projects_bone_100, listener);
        // As wide as the other rows' 22 dp icons, so the labels line up.
        Drawable mark = SocialChips.discordMark(context, 22f / SocialChips.DISCORD_RATIO);
        if (mark != null) row.setCompoundDrawablesRelative(mark, null, null, null);
        return row;
    }
}
