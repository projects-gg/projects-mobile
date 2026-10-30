package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import gg.projects.mobile.ui.AppDates;

/** CHAT-UX's day line: calendar days in the phone's zone, never 24-hour spans (the owner's 31 Oct ask). */
public class ChatDaysTest {
    private static final TimeZone ISTANBUL = TimeZone.getTimeZone("Europe/Istanbul");
    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute, int second, int ms) {
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(year, month - 1, day, hour, minute, second);
        c.set(Calendar.MILLISECOND, ms);
        return c.getTimeInMillis();
    }

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute) {
        return at(zone, year, month, day, hour, minute, 0, 0);
    }

    @Test
    public void todayYesterdayDaysAgoThenTheDate() {
        long now = at(ISTANBUL, 2026, 10, 31, 12, 0);
        assertEquals(ChatDays.Kind.TODAY, ChatDays.kind(at(ISTANBUL, 2026, 10, 31, 0, 5), now, ISTANBUL));
        assertEquals(ChatDays.Kind.YESTERDAY, ChatDays.kind(at(ISTANBUL, 2026, 10, 30, 23, 59), now, ISTANBUL));
        assertEquals(ChatDays.Kind.DAYS_AGO, ChatDays.kind(at(ISTANBUL, 2026, 10, 28, 9, 0), now, ISTANBUL));
        assertEquals(3, ChatDays.daysAgo(at(ISTANBUL, 2026, 10, 28, 9, 0), now, ISTANBUL));
        assertEquals(6, ChatDays.daysAgo(at(ISTANBUL, 2026, 10, 25, 9, 0), now, ISTANBUL));
        assertEquals(ChatDays.Kind.DAYS_AGO, ChatDays.kind(at(ISTANBUL, 2026, 10, 25, 9, 0), now, ISTANBUL));
        long seventh = at(ISTANBUL, 2026, 10, 24, 22, 0);
        assertEquals(ChatDays.Kind.DATE, ChatDays.kind(seventh, now, ISTANBUL));
        // The date: this year's without its year, another year's with it.
        assertEquals("24 Ekim", AppDates.day(true, seventh, now, ISTANBUL));
        assertEquals("24 October", AppDates.day(false, seventh, now, ISTANBUL));
        long lastYear = at(ISTANBUL, 2025, 12, 31, 18, 0);
        assertEquals(ChatDays.Kind.DATE, ChatDays.kind(lastYear, now, ISTANBUL));
        assertEquals("31 Aral\u0131k 2025", AppDates.day(true, lastYear, now, ISTANBUL));
        assertEquals("31 December 2025", AppDates.day(false, lastYear, now, ISTANBUL));
    }

    @Test
    public void bothSidesOfMidnight() {
        long now = at(ISTANBUL, 2026, 10, 31, 12, 0);
        assertEquals(ChatDays.Kind.YESTERDAY, ChatDays.kind(at(ISTANBUL, 2026, 10, 30, 23, 59, 59, 999), now, ISTANBUL));
        assertEquals(ChatDays.Kind.TODAY, ChatDays.kind(at(ISTANBUL, 2026, 10, 31, 0, 0, 0, 0), now, ISTANBUL));
        // A moment a little ahead of the clock (another device's) is today, never "-1 days".
        assertEquals(ChatDays.Kind.TODAY, ChatDays.kind(now + 2 * 60_000L, now, ISTANBUL));
    }

    @Test
    public void acrossNewYear() {
        long now = at(ISTANBUL, 2027, 1, 1, 0, 30);
        assertEquals(ChatDays.Kind.YESTERDAY, ChatDays.kind(at(ISTANBUL, 2026, 12, 31, 23, 50), now, ISTANBUL));
        assertEquals(6, ChatDays.daysAgo(at(ISTANBUL, 2026, 12, 26, 12, 0), now, ISTANBUL));
        long seventh = at(ISTANBUL, 2026, 12, 25, 12, 0);
        assertEquals(ChatDays.Kind.DATE, ChatDays.kind(seventh, now, ISTANBUL));
        assertEquals("25 Aral\u0131k 2026", AppDates.day(true, seventh, now, ISTANBUL));
    }

    @Test
    public void summerTimeEndsBetween() {
        // Europe/Berlin leaves summer time on 25 Oct 2026: calendar days, not 24-hour spans.
        long now = at(BERLIN, 2026, 10, 26, 0, 30);
        assertEquals(2, ChatDays.daysAgo(at(BERLIN, 2026, 10, 24, 23, 30), now, BERLIN));
        assertEquals(ChatDays.Kind.DAYS_AGO, ChatDays.kind(at(BERLIN, 2026, 10, 24, 23, 30), now, BERLIN));
        assertEquals(ChatDays.Kind.YESTERDAY, ChatDays.kind(at(BERLIN, 2026, 10, 25, 0, 10), now, BERLIN));
    }

    @Test
    public void theMessagesWholeTime() {
        long t = at(ISTANBUL, 2026, 10, 28, 14, 5);
        assertEquals("28 Ekim 2026 \u00c7ar\u015famba 14:05", AppDates.fullTime(true, t, ISTANBUL));
        assertEquals("Wednesday, 28 October 2026 at 14:05", AppDates.fullTime(false, t, ISTANBUL));
    }
}
