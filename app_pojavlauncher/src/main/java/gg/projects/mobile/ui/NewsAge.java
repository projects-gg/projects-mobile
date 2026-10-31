package gg.projects.mobile.ui;

import java.util.TimeZone;

import gg.projects.mobile.social.ChatDays;

/**
 * How old a news post reads in the home card's meta line (APK-UPD for 0.4.6; the lead after TASTE-2: Android's own relative
 * words gave the dated "Evvelsi gün" where the launcher and the chat say "2 gün önce"). Under a minute "az önce", under an
 * hour the minutes, the rest of the same calendar day the hours; earlier days by the chat's day rule ({@link ChatDays}:
 * calendar days in the phone's zone): "Dün", "N gün önce" up to six days, then the date. A post ahead of the clock by more
 * than a minute shows its date. Pure, JVM-tested; the caller turns the kind into words.
 */
public final class NewsAge {
    public enum Kind { JUST_NOW, MINUTES, HOURS, YESTERDAY, DAYS_AGO, DATE }

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;

    public final Kind kind;
    /** Minutes, hours or days, as the kind counts (0 where it counts nothing). */
    public final long count;

    private NewsAge(Kind kind, long count) {
        this.kind = kind;
        this.count = count;
    }

    public static NewsAge of(long ms, long nowMs, TimeZone zone) {
        long ago = nowMs - ms;
        if (ago < -MINUTE_MS) return new NewsAge(Kind.DATE, 0);
        if (ago < MINUTE_MS) return new NewsAge(Kind.JUST_NOW, 0);
        if (ago < HOUR_MS) return new NewsAge(Kind.MINUTES, ago / MINUTE_MS);
        long days = ChatDays.daysAgo(ms, nowMs, zone);
        if (days <= 0) return new NewsAge(Kind.HOURS, ago / HOUR_MS);
        if (days == 1) return new NewsAge(Kind.YESTERDAY, 1);
        if (days <= 6) return new NewsAge(Kind.DAYS_AGO, days);
        return new NewsAge(Kind.DATE, days);
    }
}
