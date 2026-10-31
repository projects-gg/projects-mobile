package gg.projects.mobile.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;

/**
 * APK-UPD (0.4.6): the home card's news age by the chat's day rule: minutes and hours within the day, then "Dün",
 * "N gün önce" up to six days and the date, never Android's "Evvelsi gün".
 */
public class NewsAgeTest {
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static long at(ZoneId zone, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli();
    }

    private static void is(NewsAge.Kind kind, long count, long post, long now, ZoneId zone) {
        NewsAge age = NewsAge.of(post, now, TimeZone.getTimeZone(zone));
        assertEquals(kind, age.kind);
        assertEquals(count, age.count);
    }

    @Test
    public void withinTheDayMinutesAndHours() {
        long now = at(ISTANBUL, 10, 31, 12, 0);
        is(NewsAge.Kind.JUST_NOW, 0, now - 30_000, now, ISTANBUL);
        is(NewsAge.Kind.JUST_NOW, 0, now + 30_000, now, ISTANBUL);
        is(NewsAge.Kind.MINUTES, 5, now - 5 * 60_000, now, ISTANBUL);
        is(NewsAge.Kind.MINUTES, 59, now - 59 * 60_000 - 59_000, now, ISTANBUL);
        is(NewsAge.Kind.HOURS, 3, at(ISTANBUL, 10, 31, 9, 0), now, ISTANBUL);
        is(NewsAge.Kind.HOURS, 11, at(ISTANBUL, 10, 31, 0, 1), now, ISTANBUL);
    }

    @Test
    public void earlierDaysByTheCalendar() {
        long now = at(ISTANBUL, 10, 31, 12, 0);
        // The case TASTE-2 found: two days ago is "2 gün önce", not "Evvelsi gün".
        is(NewsAge.Kind.DAYS_AGO, 2, at(ISTANBUL, 10, 29, 12, 0), now, ISTANBUL);
        is(NewsAge.Kind.YESTERDAY, 1, at(ISTANBUL, 10, 30, 23, 59), now, ISTANBUL);
        is(NewsAge.Kind.DAYS_AGO, 6, at(ISTANBUL, 10, 25, 0, 0), now, ISTANBUL);
        is(NewsAge.Kind.DATE, 7, at(ISTANBUL, 10, 24, 23, 59), now, ISTANBUL);
        // Across midnight: half an hour is still minutes, two hours already "Dün".
        long justAfterMidnight = at(ISTANBUL, 10, 31, 0, 20);
        is(NewsAge.Kind.MINUTES, 30, at(ISTANBUL, 10, 30, 23, 50), justAfterMidnight, ISTANBUL);
        is(NewsAge.Kind.YESTERDAY, 1, at(ISTANBUL, 10, 30, 23, 0), at(ISTANBUL, 10, 31, 1, 0), ISTANBUL);
    }

    @Test
    public void aClockAheadOfThePostShowsTheDate() {
        long now = at(ISTANBUL, 10, 31, 12, 0);
        is(NewsAge.Kind.DATE, 0, now + 2 * 60_000, now, ISTANBUL);
    }

    @Test
    public void summerTimeEndingKeepsCalendarDays() {
        // Berlin, 25 October 2026: 03:00 CEST becomes 02:00 CET, a 25-hour day.
        long now = at(BERLIN, 10, 25, 23, 30);
        is(NewsAge.Kind.YESTERDAY, 1, at(BERLIN, 10, 24, 23, 30), now, BERLIN);
        is(NewsAge.Kind.HOURS, 24, at(BERLIN, 10, 25, 0, 30), now, BERLIN);
    }
}
