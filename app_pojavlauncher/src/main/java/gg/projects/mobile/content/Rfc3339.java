package gg.projects.mobile.content;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC 3339 timestamps ({@code 2026-10-15T12:00:00Z}, offsets and fractions allowed) to epoch
 * milliseconds. java.time is not available on every Android version the app supports.
 */
public final class Rfc3339 {
    private Rfc3339() {}

    private static final Pattern PATTERN = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})$");

    /** Epoch milliseconds, or null when the text is not a valid RFC 3339 timestamp. */
    public static Long parse(String text) {
        if (text == null) return null;
        Matcher m = PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        int second = Integer.parseInt(m.group(6));
        if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return null;
        if (hour > 23 || minute > 59 || second > 60) return null;
        if (second == 60) second = 59; // leap second: close enough for display and ordering

        long millis = 0;
        String fraction = m.group(7);
        if (fraction != null) {
            String digits = (fraction.substring(1) + "00").substring(0, 3);
            millis = Integer.parseInt(digits);
        }
        long offsetSeconds = 0;
        String zone = m.group(8);
        if (!zone.equalsIgnoreCase("Z")) {
            int oh = Integer.parseInt(zone.substring(1, 3));
            int om = Integer.parseInt(zone.substring(4, 6));
            if (oh > 23 || om > 59) return null;
            offsetSeconds = (oh * 3600L + om * 60L) * (zone.charAt(0) == '-' ? -1 : 1);
        }
        long days = daysFromCivil(year, month, day);
        long seconds = days * 86400L + hour * 3600L + minute * 60L + second - offsetSeconds;
        return seconds * 1000L + millis;
    }

    private static int daysInMonth(int year, int month) {
        switch (month) {
            case 2:
                boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
                return leap ? 29 : 28;
            case 4: case 6: case 9: case 11:
                return 30;
            default:
                return 31;
        }
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
    static long daysFromCivil(long year, int month, int day) {
        year -= month <= 2 ? 1 : 0;
        long era = (year >= 0 ? year : year - 399) / 400;
        long yoe = year - era * 400;
        long doy = (153L * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
}
