package gg.projects.mobile.ui;

import android.content.Context;

import androidx.annotation.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import gg.projects.mobile.language.LanguageManager;

/**
 * MOB-POLISH: dates and times in the app's language. Turkish as the phone has always written them ("24 Ekim 2026"),
 * English in British English whatever the phone's region ("24 October 2026", never "October 24, 2026": design.md §7
 * Copy), and a clock time always on 24 hours ("21:30"), never the phone's 12-hour setting. The words around them
 * ("Bugün", "Yarın", "2 dk önce") stay with the callers. 0.4.5: the one formatter for every date the app writes,
 * always the launcher's form (day, the month's whole name, the year; no comma): the short "24 Eki 2026" went.
 *
 * <p>Every format has a pure form (the language and the time zone given) for the JVM tests; the {@link Context} forms
 * ask {@link LanguageManager#isTurkish} and use the phone's time zone.
 */
public final class AppDates {
    private AppDates() {}

    /** A calendar day written without a time zone ({@code YYYY-MM-DD}) is read, and written back, in UTC. */
    public static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static final Locale TURKISH = new Locale("tr", "TR");

    public static boolean turkish(Context context) {
        return LanguageManager.isTurkish(context);
    }

    /** The locale a date is written in: Turkish, or British English. */
    public static Locale locale(boolean turkish) {
        return turkish ? TURKISH : Locale.UK;
    }

    /** "24 Ekim 2026" / "24 October 2026". */
    public static String longDate(Context context, long millis) {
        return longDate(turkish(context), millis, TimeZone.getDefault());
    }

    public static String longDate(boolean turkish, long millis, TimeZone zone) {
        return format("d MMMM yyyy", turkish, millis, zone);
    }

    /**
     * 0.4.5: the long date for "… beri" / "since …": in Turkish with its ablative ending after the year as it is read
     * ("12 Eylül 2026'dan", "3 Ocak 2027'den"), in English the plain long date ("12 September 2026").
     */
    public static String sinceDate(boolean turkish, long millis, TimeZone zone) {
        if (!turkish) return longDate(false, millis, zone);
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(millis);
        return gg.projects.mobile.cloud.TrGrammar.ablativeDate(calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.YEAR));
    }

    /** "24 Ekim" / "24 October". */
    public static String dayMonth(Context context, long millis) {
        return dayMonth(turkish(context), millis, TimeZone.getDefault());
    }

    public static String dayMonth(boolean turkish, long millis, TimeZone zone) {
        return format("d MMMM", turkish, millis, zone);
    }

    /** {@link #dayMonth}, with the year only when it is another year than {@code nowMillis}'s ("3 Ocak 2027"). */
    public static String day(Context context, long millis, long nowMillis) {
        return day(turkish(context), millis, nowMillis, TimeZone.getDefault());
    }

    public static String day(boolean turkish, long millis, long nowMillis, TimeZone zone) {
        return year(millis, zone) == year(nowMillis, zone) ? dayMonth(turkish, millis, zone) : longDate(turkish, millis, zone);
    }

    /** "Ekim 2026" / "October 2026". */
    public static String monthYear(Context context, long millis) {
        return monthYear(turkish(context), millis, TimeZone.getDefault());
    }

    public static String monthYear(boolean turkish, long millis, TimeZone zone) {
        return format("LLLL yyyy", turkish, millis, zone);
    }

    /** The weekday's name, capitalised: "Cumartesi" / "Saturday". */
    public static String weekday(Context context, long millis) {
        return weekday(turkish(context), millis, TimeZone.getDefault());
    }

    public static String weekday(boolean turkish, long millis, TimeZone zone) {
        String name = format("EEEE", turkish, millis, zone);
        if (name.isEmpty()) return name;
        return name.substring(0, 1).toUpperCase(locale(turkish)) + name.substring(1);
    }

    /** "21:30", "08:05": 24 hours in both languages. */
    public static String time(long millis) {
        return time(millis, TimeZone.getDefault());
    }

    public static String time(long millis, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.ROOT);
        format.setTimeZone(zone);
        return format.format(new Date(millis));
    }

    /** "24 Ekim 2026 21:30" / "24 October 2026 21:30". */
    public static String dateTime(Context context, long millis) {
        return dateTime(turkish(context), millis, TimeZone.getDefault());
    }

    public static String dateTime(boolean turkish, long millis, TimeZone zone) {
        return longDate(turkish, millis, zone) + " " + time(millis, zone);
    }

    /** A {@code YYYY-MM-DD} day as the moment it starts in {@link #UTC} (format it in UTC too), or null. */
    @Nullable
    public static Long isoDay(@Nullable String isoDay) {
        if (isoDay == null || !isoDay.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        parser.setTimeZone(UTC);
        parser.setLenient(false);
        try {
            Date date = parser.parse(isoDay);
            return date == null ? null : date.getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    private static String format(String pattern, boolean turkish, long millis, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale(turkish));
        format.setTimeZone(zone);
        return format.format(new Date(millis));
    }

    private static int year(long millis, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(millis);
        return calendar.get(Calendar.YEAR);
    }
}
