package gg.projects.mobile.afk.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** M053: the morning notice says the day once it is not today's. */
public class AfkWhenTest {
    private static final TimeZone IST = TimeZone.getTimeZone("Europe/Istanbul");

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(IST, Locale.ROOT);
        c.clear();
        c.set(year, month - 1, day, hour, minute);
        return c.getTimeInMillis();
    }

    @Test
    public void todayIsOnlyTheClock() {
        long end = at(2026, 11, 6, 10, 55);
        assertEquals("10:55'te", AfkWhen.phrase(true, "10:55", end, at(2026, 11, 6, 23, 59), IST));
        assertEquals("at 10:55", AfkWhen.phrase(false, "10:55", end, at(2026, 11, 6, 11, 0), IST));
    }

    @Test
    public void yesterdaySaysSo() {
        long end = at(2026, 11, 5, 23, 40);
        assertEquals("dün 23:40'ta", AfkWhen.phrase(true, "23:40", end, at(2026, 11, 6, 0, 5), IST));
        assertEquals("yesterday at 23:40", AfkWhen.phrase(false, "23:40", end, at(2026, 11, 6, 9, 0), IST));
    }

    @Test
    public void olderSaysTheDate() {
        long end = at(2026, 11, 3, 3, 12);
        assertEquals("3 Kasım 03:12'de", AfkWhen.phrase(true, "03:12", end, at(2026, 11, 6, 8, 0), IST));
        assertEquals("on 3 Nov at 03:12", AfkWhen.phrase(false, "03:12", end, at(2026, 11, 6, 8, 0), IST));
    }

    @Test
    public void aLaterClockCountsAsToday() {
        long end = at(2026, 11, 6, 10, 55);
        assertEquals("10:55'te", AfkWhen.phrase(true, "10:55", end, at(2026, 11, 6, 10, 50), IST));
    }
}
