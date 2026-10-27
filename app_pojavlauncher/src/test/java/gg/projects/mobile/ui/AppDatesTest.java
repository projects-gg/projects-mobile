package gg.projects.mobile.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** MOB-POLISH: dates in the app's language, English in en-GB, clock times on 24 hours (AppDates). */
public class AppDatesTest {
    private static final TimeZone ISTANBUL = TimeZone.getTimeZone("Europe/Istanbul");

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(ISTANBUL);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void englishIsBritishWhateverThePhoneUses() {
        long t = at(2026, 10, 24, 21, 30);
        assertEquals("24 October 2026", AppDates.longDate(false, t, ISTANBUL));
        assertEquals("24 Oct 2026", AppDates.mediumDate(false, t, ISTANBUL));
        assertEquals("24 October", AppDates.dayMonth(false, t, ISTANBUL));
        assertEquals("October 2026", AppDates.monthYear(false, t, ISTANBUL));
        assertEquals("Saturday", AppDates.weekday(false, t, ISTANBUL));
        assertEquals("24 October 2026 21:30", AppDates.dateTime(false, t, ISTANBUL));
    }

    @Test
    public void turkishStaysAsThePhoneWroteIt() {
        long t = at(2026, 10, 24, 21, 30);
        assertEquals("24 Ekim 2026", AppDates.longDate(true, t, ISTANBUL));
        assertEquals("24 Eki 2026", AppDates.mediumDate(true, t, ISTANBUL));
        assertEquals("24 Ekim", AppDates.dayMonth(true, t, ISTANBUL));
        assertEquals("Ekim 2026", AppDates.monthYear(true, t, ISTANBUL));
        assertEquals("Cumartesi", AppDates.weekday(true, t, ISTANBUL));
        assertEquals("Çarşamba", AppDates.weekday(true, at(2026, 10, 28, 12, 0), ISTANBUL));
        assertEquals("24 Ekim 2026 21:30", AppDates.dateTime(true, t, ISTANBUL));
    }

    @Test
    public void clockTimesAreAlwaysTwentyFourHours() {
        assertEquals("01:24", AppDates.time(at(2026, 10, 24, 1, 24), ISTANBUL));
        assertEquals("13:05", AppDates.time(at(2026, 10, 24, 13, 5), ISTANBUL));
        assertEquals("00:00", AppDates.time(at(2026, 10, 25, 0, 0), ISTANBUL));
        assertEquals("the zone decides the hour, not the language", "22:24", AppDates.time(at(2026, 10, 24, 1, 24), AppDates.UTC));
    }

    @Test
    public void theYearOnlyWhenItIsAnotherOne() {
        long now = at(2026, 10, 27, 12, 0);
        assertEquals("3 November", AppDates.day(false, at(2026, 11, 3, 9, 0), now, ISTANBUL));
        assertEquals("3 Kasım", AppDates.day(true, at(2026, 11, 3, 9, 0), now, ISTANBUL));
        assertEquals("3 January 2027", AppDates.day(false, at(2027, 1, 3, 9, 0), now, ISTANBUL));
        assertEquals("3 Ocak 2027", AppDates.day(true, at(2027, 1, 3, 9, 0), now, ISTANBUL));
    }

    @Test
    public void isoDaysAreCalendarDaysInAnyZone() {
        Long day = AppDates.isoDay("2026-10-24");
        assertNotNull(day);
        assertEquals("24 October 2026", AppDates.longDate(false, day, AppDates.UTC));
        assertEquals("Ekim 2026", AppDates.monthYear(true, day, AppDates.UTC));
        assertNull(AppDates.isoDay("2026-13-01"));
        assertNull(AppDates.isoDay("2026-02-30"));
        assertNull(AppDates.isoDay("24.10.2026"));
        assertNull(AppDates.isoDay(""));
        assertNull(AppDates.isoDay(null));
    }
}
