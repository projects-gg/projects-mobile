package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** M19: "Sessize al" presets (the owner's: 1 saat, 8 saat, yarın sabaha kadar, ben açana kadar). */
public class MutePresetsTest {
    private static final TimeZone ISTANBUL = TimeZone.getTimeZone("Europe/Istanbul");
    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis() / 1000L;
    }

    private static int localHour(TimeZone zone, long seconds) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(seconds * 1000L);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    @Test
    public void hoursAreHours() {
        long now = at(ISTANBUL, 2026, 10, 21, 20, 15);
        assertEquals(now + 3600, (long) MutePresets.until(MutePresets.Preset.HOUR, now, ISTANBUL));
        assertEquals(now + 8 * 3600, (long) MutePresets.until(MutePresets.Preset.EIGHT_HOURS, now, ISTANBUL));
        assertNull(MutePresets.until(MutePresets.Preset.UNTIL_UNMUTED, now, ISTANBUL));
    }

    @Test
    public void theEveningMutesUntilTomorrowAtEight() {
        long now = at(ISTANBUL, 2026, 10, 21, 20, 15);
        long end = MutePresets.nextMorning(now, ISTANBUL);
        assertEquals(at(ISTANBUL, 2026, 10, 22, 8, 0), end);
        assertTrue(MutePresets.morningIsTomorrow(now, ISTANBUL));
    }

    @Test
    public void afterMidnightItIsThisMorning() {
        long now = at(ISTANBUL, 2026, 10, 22, 1, 30);
        assertEquals(at(ISTANBUL, 2026, 10, 22, 8, 0), MutePresets.nextMorning(now, ISTANBUL));
        assertFalse(MutePresets.morningIsTomorrow(now, ISTANBUL));
        long eight = at(ISTANBUL, 2026, 10, 22, 8, 0);
        assertEquals("08:00 itself is not the next 08:00", at(ISTANBUL, 2026, 10, 23, 8, 0), MutePresets.nextMorning(eight, ISTANBUL));
    }

    @Test
    public void aNightAcrossTheClockChangeStillEndsAtEight() {
        // Berlin moves to summer time at 02:00 on 29 March 2026: the night is an hour shorter.
        long spring = at(BERLIN, 2026, 3, 28, 22, 0);
        long springEnd = MutePresets.nextMorning(spring, BERLIN);
        assertEquals(8, localHour(BERLIN, springEnd));
        assertEquals(9 * 3600, springEnd - spring);
        // Back to winter time at 03:00 on 25 October 2026: an hour longer.
        long autumn = at(BERLIN, 2026, 10, 24, 22, 0);
        long autumnEnd = MutePresets.nextMorning(autumn, BERLIN);
        assertEquals(8, localHour(BERLIN, autumnEnd));
        assertEquals(11 * 3600, autumnEnd - autumn);
        // Across the end of a year.
        long newYear = at(ISTANBUL, 2026, 12, 31, 23, 0);
        assertEquals(at(ISTANBUL, 2027, 1, 1, 8, 0), MutePresets.nextMorning(newYear, ISTANBUL));
    }

    @Test
    public void everyPresetStaysInsideTheServicesThirtyDays() {
        long now = at(ISTANBUL, 2026, 10, 21, 7, 59);
        for (MutePresets.Preset preset : MutePresets.Preset.values()) {
            Long until = MutePresets.until(preset, now, ISTANBUL);
            if (until == null) continue;
            assertTrue(until > now);
            assertTrue(until - now <= MutePresets.MAX_SECONDS);
        }
    }

    @Test
    public void theBodies() {
        long now = 1792521000L;
        assertEquals("{\"muted\":true,\"muteUntil\":1792524600}", MutePresets.body(MutePresets.Preset.HOUR, now, ISTANBUL));
        assertEquals("{\"muted\":true}", MutePresets.body(MutePresets.Preset.UNTIL_UNMUTED, now, ISTANBUL));
    }
}
