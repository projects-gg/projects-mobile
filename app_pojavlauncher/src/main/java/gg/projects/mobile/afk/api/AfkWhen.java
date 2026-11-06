package gg.projects.mobile.afk.api;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import gg.projects.mobile.cloud.TrGrammar;

/**
 * M053: when a morning notice's AFK end happened, as words that fit the notice's sentence. Today only the clock
 * ("10:55'te", "at 10:55"), yesterday with "dün"/"yesterday", older with the date ("5 Kasım 10:55'te",
 * "on 5 Nov at 10:55"): a notice the player left for days no longer reads as today's (M052's "AFK 10:55'te durdu.").
 */
public final class AfkWhen {
    private AfkWhen() {}

    private static final Locale TR = new Locale("tr", "TR");

    /**
     * @param clock the time as the phone shows it ("10:55", "10:55 AM"); Turkish takes its ending from it
     * @param at    the end's wall-clock ms
     * @param now   the wall-clock ms now
     */
    public static String phrase(boolean turkish, String clock, long at, long now, TimeZone zone) {
        int days = daysBefore(at, now, zone);
        if (turkish) {
            String time = TrGrammar.locativeTime(clock);
            if (days <= 0) return time;
            if (days == 1) return "dün " + time;
            return date(at, zone, TR, "d MMMM") + " " + time;
        }
        if (days <= 0) return "at " + clock;
        if (days == 1) return "yesterday at " + clock;
        return "on " + date(at, zone, Locale.ENGLISH, "d MMM") + " at " + clock;
    }

    /** Calendar days between {@code at} and {@code now} in {@code zone} (0: the same day; negative: a later clock). */
    static int daysBefore(long at, long now, TimeZone zone) {
        return (int) (dayNumber(now, zone) - dayNumber(at, zone));
    }

    /** The local day's number (the offset of that moment, so summer time does not shift it); times are after 1970. */
    private static long dayNumber(long ms, TimeZone zone) {
        return (ms + zone.getOffset(ms)) / 86_400_000L;
    }

    private static String date(long ms, TimeZone zone, Locale locale, String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, locale);
        f.setTimeZone(zone);
        return f.format(new Date(ms));
    }
}
