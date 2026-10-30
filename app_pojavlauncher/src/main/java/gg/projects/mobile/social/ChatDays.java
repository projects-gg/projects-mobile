package gg.projects.mobile.social;

import java.util.TimeZone;

/**
 * The chat's day line (CHAT-UX, the owner's 31 Oct 2026 ask; the launcher and the overlay use the same model): calendar
 * days in the phone's zone between a message and now, never 24-hour spans. 0 or less is today (a time a little ahead of
 * the clock too), 1 yesterday, 2 to 6 "N days ago", from 7 on the date itself. Pure: the caller turns the kind into
 * words, and the day line is worked out when the list is built (no timer).
 */
public final class ChatDays {
    private ChatDays() {}

    public enum Kind { TODAY, YESTERDAY, DAYS_AGO, DATE }

    private static final long DAY_MS = 86_400_000L;

    /**
     * The local calendar day of a moment: whole days since the epoch on the zone's own clock (its summer time counted).
     * Equal for two moments of one local day; the difference of two is the calendar days between them, New Year too.
     */
    public static long localDay(long ms, TimeZone zone) {
        long local = ms + zone.getOffset(ms);
        long day = local / DAY_MS;
        // Floor division: a moment before the epoch belongs to the day before.
        if (local % DAY_MS < 0) day--;
        return day;
    }

    /** Calendar days from the moment to now; negative while the moment is ahead of the clock. */
    public static long daysAgo(long ms, long nowMs, TimeZone zone) {
        return localDay(nowMs, zone) - localDay(ms, zone);
    }

    /** Which words the day line takes. */
    public static Kind kind(long ms, long nowMs, TimeZone zone) {
        long d = daysAgo(ms, nowMs, zone);
        if (d <= 0) return Kind.TODAY;
        if (d == 1) return Kind.YESTERDAY;
        if (d <= 6) return Kind.DAYS_AGO;
        return Kind.DATE;
    }
}
