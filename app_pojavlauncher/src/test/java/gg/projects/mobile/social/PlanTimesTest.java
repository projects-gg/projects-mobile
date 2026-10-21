package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** M21 (TEAM5): "Plan yap" offers today and six more days, in quarter hours the service takes. */
public class PlanTimesTest {
    private static final TimeZone ISTANBUL = TimeZone.getTimeZone("Europe/Istanbul");
    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");
    /** 20 October 2026, 21:30 in Istanbul (a Tuesday). */
    private static final long NOW = 1_792_521_000L;

    private static String clock(long at, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(at * 1000L);
        return String.format(java.util.Locale.ROOT, "%02d.%02d %02d:%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    @Test
    public void sevenDaysFromToday() {
        List<PlanTimes.Day> days = PlanTimes.days(NOW, ISTANBUL);
        assertEquals(7, days.size());
        assertEquals("20.10 00:00", clock(days.get(0).start, ISTANBUL));
        assertEquals(Calendar.TUESDAY, days.get(0).weekday);
        assertEquals(Calendar.MONDAY, days.get(6).weekday);
        assertEquals("26.10 00:00", clock(days.get(6).start, ISTANBUL));
        for (int i = 0; i < 7; i++) assertEquals(i, days.get(i).offset);
        assertEquals(0, PlanTimes.dayOf(days, NOW));
        assertEquals(1, PlanTimes.dayOf(days, NOW + 3 * 3600));
    }

    @Test
    public void todayOffersOnlyTheQuarterHoursAhead() {
        List<PlanTimes.Day> days = PlanTimes.days(NOW, ISTANBUL);
        List<Long> today = PlanTimes.slots(days.get(0), NOW);
        assertEquals(9, today.size());
        assertEquals("20.10 21:45", clock(today.get(0), ISTANBUL));
        assertEquals("20.10 23:45", clock(today.get(8), ISTANBUL));
        assertEquals(96, PlanTimes.slots(days.get(1), NOW).size());
        // The last day is still within the service's 7 days.
        List<Long> last = PlanTimes.slots(days.get(6), NOW);
        assertEquals(96, last.size());
        for (long at : last) assertTrue(PlanTimes.valid(at, NOW));
        // A quarter hour less than a minute away is not offered.
        assertEquals("20.10 21:45", clock(PlanTimes.slots(days.get(0), NOW + 14 * 60 - 1).get(0), ISTANBUL));
        assertEquals("20.10 22:00", clock(PlanTimes.slots(days.get(0), NOW + 14 * 60 + 1).get(0), ISTANBUL));
    }

    @Test
    public void theServicesRule() {
        assertTrue(PlanTimes.valid(NOW + 900, NOW));
        assertFalse("not a quarter hour", PlanTimes.valid(NOW + 600, NOW));
        assertFalse("now", PlanTimes.valid(NOW, NOW));
        assertTrue("seven days ahead", PlanTimes.valid(NOW + 7 * 86_400, NOW));
        assertFalse("more", PlanTimes.valid(NOW + 7 * 86_400 + 900, NOW));
    }

    @Test
    public void theFirstChoiceIsTonightAtNineLikeTheDesktops() {
        // 15:10 and 20:30: tonight at 21:00.
        assertEquals("20.10 21:00", clock(PlanTimes.defaultSlot(NOW - 6 * 3600 - 20 * 60, ISTANBUL), ISTANBUL));
        assertEquals("20.10 21:00", clock(PlanTimes.defaultSlot(NOW - 3600, ISTANBUL), ISTANBUL));
        // 20:40: 21:00 is less than half an hour away, so is 21:00 as the next whole hour: 22:00.
        assertEquals("20.10 22:00", clock(PlanTimes.defaultSlot(NOW - 50 * 60, ISTANBUL), ISTANBUL));
        // 21:30: the next whole hour, half an hour away.
        assertEquals("20.10 22:00", clock(PlanTimes.defaultSlot(NOW, ISTANBUL), ISTANBUL));
        assertEquals("20.10 23:00", clock(PlanTimes.defaultSlot(NOW + 600, ISTANBUL), ISTANBUL));
        assertEquals("20.10 23:00", clock(PlanTimes.defaultSlot(NOW + 50 * 60, ISTANBUL), ISTANBUL));
        // 23:20 and 23:50: midnight or later, so tomorrow at 21:00.
        assertEquals("21.10 21:00", clock(PlanTimes.defaultSlot(NOW + 3600 + 50 * 60, ISTANBUL), ISTANBUL));
        assertEquals("21.10 21:00", clock(PlanTimes.defaultSlot(NOW + 2 * 3600 + 20 * 60, ISTANBUL), ISTANBUL));
    }

    @Test
    public void anotherDayKeepsTheTimeOfDay() {
        List<PlanTimes.Day> days = PlanTimes.days(NOW, ISTANBUL);
        long tonight = PlanTimes.defaultSlot(NOW, ISTANBUL);
        assertEquals("21.10 22:00", clock(PlanTimes.sameTimeOn(days.get(1), tonight, NOW, ISTANBUL), ISTANBUL));
        long tomorrowEvening = PlanTimes.sameTimeOn(days.get(1), tonight - 2 * 3600, NOW, ISTANBUL);
        assertEquals("21.10 20:00", clock(tomorrowEvening, ISTANBUL));
        assertEquals("today's 20:00 is gone: the nearest", "20.10 21:45",
                clock(PlanTimes.sameTimeOn(days.get(0), tomorrowEvening, NOW, ISTANBUL), ISTANBUL));
        // Today at 23:59 has nothing left.
        assertNull(PlanTimes.sameTimeOn(days.get(0), tonight, NOW + 2 * 3600 + 29 * 60, ISTANBUL));
    }

    @Test
    public void daysFromTodayOnThePhonesCalendar() {
        assertEquals(0, PlanTimes.daysFrom(NOW, NOW + 3600, ISTANBUL));
        assertEquals("00:30 is tomorrow", 1, PlanTimes.daysFrom(NOW, NOW + 3 * 3600, ISTANBUL));
        assertEquals(6, PlanTimes.daysFrom(NOW, NOW + 6 * 86_400, ISTANBUL));
        assertEquals(-1, PlanTimes.daysFrom(NOW, NOW - 86_400, ISTANBUL));
    }

    @Test
    public void aDayOfAClockChangeHasItsOwnLength() {
        // Berlin goes back an hour on 25 October 2026 at 03:00: that day has 25 hours, 100 quarter hours.
        long saturdayNoon = 1_792_836_000L; // 24 October 2026, 12:00 in Berlin (10:00 UTC)
        assertEquals("24.10 12:00", clock(saturdayNoon, BERLIN));
        List<PlanTimes.Day> days = PlanTimes.days(saturdayNoon, BERLIN);
        PlanTimes.Day sunday = days.get(1);
        assertEquals(25 * 3600, sunday.end - sunday.start);
        List<Long> slots = PlanTimes.slots(sunday, saturdayNoon);
        assertEquals(100, slots.size());
        for (long at : slots) assertEquals(0, at % 900);
        assertEquals(1, PlanTimes.daysFrom(saturdayNoon, slots.get(99), BERLIN));
    }
}
