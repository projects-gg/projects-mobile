package gg.projects.mobile.social;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import gg.projects.mobile.social.SocialModel.Plan;

/**
 * M21 (TEAM5, "Plan yap"): the days and quarter hours the plan sheet offers, in the phone's own time
 * zone, and the rule the service checks ({@code at} a whole quarter of an hour, after the hub's clock,
 * at most 7 days ahead; README "Crew plans"). The sheet offers today and the next six days; a time on
 * the phone's clock is a multiple of 15 minutes in every zone, since every zone's offset is one. Pure
 * Java.
 */
public final class PlanTimes {
    private PlanTimes() {}

    /** Days the sheet offers: today, tomorrow and the five after (all within the service's 7 days). */
    public static final int DAYS = 7;
    /** A time must be at least this far ahead when it is sent (the hub's clock and the trip there). */
    public static final long MIN_LEAD_SECONDS = 60;
    /** The first choice is the first whole hour at least this far ahead. */
    static final long DEFAULT_LEAD_SECONDS = 20 * 60;

    /** One day of the sheet: its local midnight and how many days from today it is. */
    public static final class Day {
        /** Local midnight, Unix seconds. */
        public final long start;
        /** Local midnight of the day after, Unix seconds (a day of a clock change is 23 or 25 hours). */
        public final long end;
        /** 0 today, 1 tomorrow, … */
        public final int offset;
        /** {@link Calendar#DAY_OF_WEEK} of the day. */
        public final int weekday;

        Day(long start, long end, int offset, int weekday) {
            this.start = start;
            this.end = end;
            this.offset = offset;
            this.weekday = weekday;
        }
    }

    /** Today and the next six days on the phone's calendar at {@code nowSeconds}. */
    public static List<Day> days(long nowSeconds, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(nowSeconds * 1000L);
        midnight(c);
        List<Day> out = new ArrayList<>();
        for (int i = 0; i < DAYS; i++) {
            long start = c.getTimeInMillis() / 1000L;
            int weekday = c.get(Calendar.DAY_OF_WEEK);
            c.add(Calendar.DAY_OF_MONTH, 1);
            midnight(c);
            out.add(new Day(start, c.getTimeInMillis() / 1000L, i, weekday));
        }
        return out;
    }

    private static void midnight(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    /** True when the service takes {@code at} now: a whole quarter hour, ahead of its clock, within 7 days. */
    public static boolean valid(long at, long hubNowSeconds) {
        return at % Plan.STEP_SECONDS == 0 && at > hubNowSeconds + MIN_LEAD_SECONDS
                && at <= hubNowSeconds + Plan.AHEAD_SECONDS;
    }

    /** The quarter hours of {@code day} the service would take now, earliest first. */
    public static List<Long> slots(Day day, long hubNowSeconds) {
        List<Long> out = new ArrayList<>();
        long first = day.start - mod(day.start, Plan.STEP_SECONDS);
        if (first < day.start) first += Plan.STEP_SECONDS;
        for (long at = first; at < day.end; at += Plan.STEP_SECONDS) {
            if (valid(at, hubNowSeconds)) out.add(at);
        }
        return out;
    }

    /** The sheet's first choice: the first whole hour at least 20 minutes ahead. */
    public static long defaultSlot(long hubNowSeconds, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis((hubNowSeconds + DEFAULT_LEAD_SECONDS) * 1000L);
        boolean whole = c.get(Calendar.MINUTE) == 0 && c.get(Calendar.SECOND) == 0 && c.get(Calendar.MILLISECOND) == 0;
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (!whole) c.add(Calendar.HOUR_OF_DAY, 1);
        long at = c.getTimeInMillis() / 1000L;
        // A clock change can leave a time off the quarter grid; the next quarter hour then.
        long rest = mod(at, Plan.STEP_SECONDS);
        if (rest != 0) at += Plan.STEP_SECONDS - rest;
        while (!valid(at, hubNowSeconds) && at <= hubNowSeconds + Plan.AHEAD_SECONDS) at += Plan.STEP_SECONDS;
        return at;
    }

    /** {@code x mod y} for y &gt; 0, never negative (Math.floorMod needs Android 7). */
    static long mod(long x, long y) {
        long r = x % y;
        return r < 0 ? r + y : r;
    }

    /** Which of {@code days} holds {@code at} (-1: none). */
    public static int dayOf(List<Day> days, long at) {
        for (int i = 0; i < days.size(); i++) if (at >= days.get(i).start && at < days.get(i).end) return i;
        return -1;
    }

    /**
     * Another day was picked: the same time of day on it when the service takes it, else the time of
     * that day nearest to it (an earlier time of today is gone), or null when the day has none.
     */
    public static Long sameTimeOn(Day day, long previous, long hubNowSeconds, TimeZone zone) {
        List<Long> slots = slots(day, hubNowSeconds);
        if (slots.isEmpty()) return null;
        Calendar was = Calendar.getInstance(zone);
        was.setTimeInMillis(previous * 1000L);
        int minutes = was.get(Calendar.HOUR_OF_DAY) * 60 + was.get(Calendar.MINUTE);
        Calendar c = Calendar.getInstance(zone);
        long best = slots.get(0);
        int bestGap = Integer.MAX_VALUE;
        for (long at : slots) {
            c.setTimeInMillis(at * 1000L);
            int gap = Math.abs(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) - minutes);
            if (gap < bestGap) {
                bestGap = gap;
                best = at;
            }
        }
        return best;
    }

    /** Days from today on the phone's calendar to {@code at} (0 today, 1 tomorrow, -1 yesterday). */
    public static int daysFrom(long nowSeconds, long at, TimeZone zone) {
        Calendar a = Calendar.getInstance(zone);
        a.setTimeInMillis(nowSeconds * 1000L);
        midnight(a);
        Calendar b = Calendar.getInstance(zone);
        b.setTimeInMillis(at * 1000L);
        midnight(b);
        // Whole days between the two midnights, rounded: a clock change makes one day 23 or 25 hours.
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86_400_000.0);
    }

    /** The plans' times taken in {@code plans} (the sheet marks them: one plan per crew and time). */
    public static List<Long> taken(List<Plan> plans) {
        List<Long> out = new ArrayList<>();
        for (Plan p : plans) out.add(p.at);
        Collections.sort(out);
        return out;
    }
}
