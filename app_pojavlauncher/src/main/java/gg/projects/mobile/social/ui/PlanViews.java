package gg.projects.mobile.social.ui;

import android.content.Context;

import java.util.Map;
import java.util.TimeZone;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.PlanTimes;
import gg.projects.mobile.social.SocialModel.Plan;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.AppDates;

/**
 * M21 (TEAM5): how plans and join requests say their times: "Bugün 22:00", "Yarın 22:00", "Cumartesi
 * 22:00" (the app's language, 24 hours: AppDates), "12 dk önce", and who answered a plan what.
 */
public final class PlanViews {
    private PlanViews() {}

    /** "Bugün 22:00", "Yarın 22:00", "Cumartesi 22:00"; a date for anything further. */
    public static String when(Context context, long at, long nowSeconds) {
        return when(context, at, nowSeconds, false);
    }

    /** {@code inline}: inside a sentence ("Ayşe bugün 22:00 için plan yaptı"). */
    public static String when(Context context, long at, long nowSeconds, boolean inline) {
        String time = CrewViews.time(context, at);
        int days = PlanTimes.daysFrom(nowSeconds, at, TimeZone.getDefault());
        String day;
        if (days == 0) {
            day = context.getString(inline ? R.string.projects_plans_today_inline : R.string.projects_plans_today);
        } else if (days == 1) {
            day = context.getString(inline ? R.string.projects_plans_tomorrow_inline : R.string.projects_plans_tomorrow);
        } else if (days > 1 && days < PlanTimes.DAYS) {
            day = AppDates.weekday(context, at * 1000L);
        } else {
            day = AppDates.longDate(context, at * 1000L);
        }
        return context.getString(R.string.projects_plans_when, day, time);
    }

    /** The plan sheet's day: "Bugün", "Yarın", then the weekday's name ("Cumartesi", "Saturday"). */
    public static String dayLabel(Context context, PlanTimes.Day day) {
        if (day.offset == 0) return context.getString(R.string.projects_plans_today);
        if (day.offset == 1) return context.getString(R.string.projects_plans_tomorrow);
        // Noon of that day: a clock change at night does not move it to the day before.
        return AppDates.weekday(context, (day.start + 12 * 3600) * 1000L);
    }

    /** "az önce", "12 dk önce", "3 sa önce", "2 gün önce". */
    public static String ago(Context context, long sentAt, long nowSeconds) {
        long seconds = Math.max(0, nowSeconds - sentAt);
        if (sentAt <= 0 || seconds < 60) return context.getString(R.string.projects_time_just_now);
        if (seconds < 3600) {
            int minutes = (int) (seconds / 60);
            return context.getResources().getQuantityString(R.plurals.projects_time_minutes_ago, minutes, minutes);
        }
        if (seconds < 86_400) {
            int hours = (int) (seconds / 3600);
            return context.getResources().getQuantityString(R.plurals.projects_time_hours_ago, hours, hours);
        }
        int days = (int) (seconds / 86_400);
        return context.getResources().getQuantityString(R.plurals.projects_time_days_ago, days, days);
    }

    /** "Geliyor: Ece, Kaan  Belki: Deniz  Gelemiyor: Can" (the call card's tally, for plans; {@link #appendTally}). */
    public static CharSequence tally(Context context, SocialState state, Plan plan) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        String[] answers = {Plan.YES, Plan.MAYBE, Plan.NO};
        int[] labels = {R.string.projects_plans_said_yes, R.string.projects_plans_said_maybe, R.string.projects_plans_said_no};
        for (int i = 0; i < answers.length; i++) {
            StringBuilder names = new StringBuilder();
            for (Map.Entry<String, String> e : plan.answers.entrySet()) {
                if (!answers[i].equals(e.getValue())) continue;
                Profile p = state.profile(e.getKey());
                String name = e.getKey().equals(state.myId()) ? context.getString(R.string.projects_crew_you)
                        : p == null ? context.getString(R.string.projects_crew_someone) : p.name();
                if (names.length() > 0) names.append(", ");
                names.append(name);
            }
            if (names.length() == 0) continue;
            appendTally(context, out, context.getString(labels[i]), names);
        }
        return out;
    }

    /**
     * One answer's group of a tally ("Geliyor: Ece, Kaan"): apart from the group before it by a gap, its label quieter
     * than the names (no middle dot in UI text, the lead 29 Oct 2026).
     */
    static void appendTally(Context context, android.text.SpannableStringBuilder out, String label, CharSequence names) {
        if (out.length() > 0) out.append(gg.projects.mobile.ui.Apart.GAP);
        gg.projects.mobile.ui.Apart.tone(context, out, label + ": ", R.color.projects_ash_400);
        out.append(names);
    }

    /** "Ayşe planladı" / "Sen planladın". */
    public static String by(Context context, SocialState state, Plan plan) {
        if (plan.by.equals(state.myId())) return context.getString(R.string.projects_plans_by_you);
        Profile maker = state.profile(plan.by);
        return context.getString(R.string.projects_plans_by, maker == null ? context.getString(R.string.projects_crew_someone) : maker.name());
    }
}
