package gg.projects.mobile.social;

import androidx.annotation.Nullable;

/**
 * CREW-PLUS (the launcher's played.ts): how long a friend has been playing, said coarsely after the place on their line
 * ("Projects'te 1.21.4", a small clock, "25 dk"), as "son görülme" is coarse. The service sends when the game session
 * began (down to five minutes, the hub's clock) only from a friend who shares "Son görülme" and "Oynama süresi".
 * Nothing under five minutes; under an hour in five-minute steps; then whole hours, always rounded down. The friends
 * screen redraws it only at the next change, at most once a minute, and only while it is on screen.
 */
public final class PlayedTime {
    private PlayedTime() {}

    /** Under this long nothing is said (seconds). */
    public static final long FROM = 300;
    /** Under an hour the minutes go in steps of this (seconds). */
    private static final long STEP = 300;
    private static final long HOUR = 3_600;
    /** The screen looks again at most this often (seconds). */
    public static final long TICK_MIN = 60;

    public enum Unit { MINUTES, HOURS }

    /** "25 dk" ({@link Unit#MINUTES}, 25) or "1 sa" ({@link Unit#HOURS}, 1). */
    public static final class Played {
        public final Unit unit;
        public final int value;

        Played(Unit unit, int value) {
            this.unit = unit;
            this.value = value;
        }

        /** What a row's key carries: the text changes exactly when this does. */
        public String key() {
            return unit.name() + value;
        }
    }

    /** How long a session that began at {@code since} (Unix seconds) has run at {@code now}, coarsely; null for none yet. */
    @Nullable
    public static Played of(long since, long now) {
        if (since <= 0) return null;
        long elapsed = now - since;
        if (elapsed < FROM) return null;
        if (elapsed < HOUR) return new Played(Unit.MINUTES, (int) (elapsed / STEP * 5));
        return new Played(Unit.HOURS, (int) Math.min(Integer.MAX_VALUE, elapsed / HOUR));
    }

    /** The friend's session as their line shows it: only while they play. {@code hubNow}: the hub's clock (seconds). */
    @Nullable
    public static Played of(SocialModel.Presence presence, long hubNow) {
        return presence.playing() ? of(presence.playingSince, hubNow) : null;
    }

    /** In how many seconds the text of a session that began at {@code since} next changes after {@code now}. */
    public static long nextChange(long since, long now) {
        long elapsed = now - since;
        if (elapsed < FROM) return Math.max(1, since + FROM - now);
        long step = elapsed < HOUR ? STEP : HOUR;
        return since + (elapsed / step + 1) * step - now;
    }

    /** In how many seconds any of these sessions' texts changes: at least {@link #TICK_MIN}; -1 for none. */
    public static long nextTick(Iterable<Long> sinces, long now) {
        long next = Long.MAX_VALUE;
        for (Long since : sinces) if (since != null && since > 0) next = Math.min(next, nextChange(since, now));
        return next == Long.MAX_VALUE ? -1 : Math.max(TICK_MIN, next);
    }
}
