package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * "Sessize al" (the owner's presets, 20 October 2026; soc6.md): "1 saat", "8 saat", "Yarın sabaha
 * kadar" (the next 08:00 on the phone's clock, in its time zone, so a night across a daylight saving
 * change still ends at 08:00 local time) and "Ben açana kadar". The service keeps a timed mute at most
 * 30 days and only in the future; every preset stays inside that.
 */
public final class MutePresets {
    private MutePresets() {}

    public enum Preset { HOUR, EIGHT_HOURS, MORNING, UNTIL_UNMUTED }

    /** The hour "Yarın sabaha kadar" ends at. */
    public static final int MORNING_HOUR = 8;
    /** The service's longest timed mute. */
    public static final long MAX_SECONDS = 30L * 24 * 3600;

    /**
     * When {@code preset} ends (Unix seconds), from {@code nowSeconds} (the hub's clock when known);
     * null for "Ben açana kadar".
     */
    @Nullable
    public static Long until(Preset preset, long nowSeconds, TimeZone zone) {
        switch (preset) {
            case HOUR:
                return nowSeconds + 3600;
            case EIGHT_HOURS:
                return nowSeconds + 8 * 3600;
            case MORNING:
                return nextMorning(nowSeconds, zone);
            case UNTIL_UNMUTED:
            default:
                return null;
        }
    }

    /** The first 08:00 local time after {@code nowSeconds}. */
    public static long nextMorning(long nowSeconds, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(nowSeconds * 1000L);
        calendar.set(Calendar.HOUR_OF_DAY, MORNING_HOUR);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() / 1000L <= nowSeconds) {
            // The calendar day moves on and keeps 08:00 on the wall clock, whatever the offset does.
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            calendar.set(Calendar.HOUR_OF_DAY, MORNING_HOUR);
            calendar.set(Calendar.MINUTE, 0);
        }
        return calendar.getTimeInMillis() / 1000L;
    }

    /** True when the morning the preset ends at is the next calendar day ("Yarın 08:00"), else today. */
    public static boolean morningIsTomorrow(long nowSeconds, TimeZone zone) {
        Calendar now = Calendar.getInstance(zone);
        now.setTimeInMillis(nowSeconds * 1000L);
        Calendar end = Calendar.getInstance(zone);
        end.setTimeInMillis(nextMorning(nowSeconds, zone) * 1000L);
        return now.get(Calendar.YEAR) != end.get(Calendar.YEAR) || now.get(Calendar.DAY_OF_YEAR) != end.get(Calendar.DAY_OF_YEAR);
    }

    /** The body of {@code PATCH /v1/teams/notifications} for {@code preset}. */
    public static String body(Preset preset, long nowSeconds, TimeZone zone) {
        Long until = until(preset, nowSeconds, zone);
        return until == null ? "{\"muted\":true}" : "{\"muted\":true,\"muteUntil\":" + until + "}";
    }
}
