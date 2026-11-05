package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import gg.projects.mobile.ui.AppDates;

/**
 * MOB-PARITY "Oturumlar": when a session was last used, from the service's day ({@code lastUsedOn}, {@code YYYY-MM-DD},
 * a UTC day as the desktop reads it): today, yesterday or that day. Pure Java.
 */
public final class SessionDays {
    private SessionDays() {}

    private static final long DAY_MS = 86_400_000L;

    public enum Kind { TODAY, YESTERDAY, DAY }

    public static final class When {
        public final Kind kind;
        /** Midnight UTC of that day. */
        public final long millis;

        When(Kind kind, long millis) {
            this.kind = kind;
            this.millis = millis;
        }
    }

    /** Null without a readable day. A day after today (a clock behind) counts as today. */
    @Nullable
    public static When lastUsed(@Nullable String isoDay, long nowMillis) {
        Long at = AppDates.isoDay(isoDay);
        if (at == null) return null;
        long today = nowMillis / DAY_MS;
        long day = at / DAY_MS;
        if (day >= today) return new When(Kind.TODAY, at);
        if (day == today - 1) return new When(Kind.YESTERDAY, at);
        return new When(Kind.DAY, at);
    }
}
