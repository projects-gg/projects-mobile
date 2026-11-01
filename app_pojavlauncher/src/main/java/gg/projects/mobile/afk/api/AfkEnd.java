package gg.projects.mobile.afk.api;

import java.util.Collections;
import java.util.List;

/**
 * Why AFK ended while accounts were running and nobody stopped it (Android 11+ reads the process's exit reason;
 * older phones say {@link Kind#UNKNOWN}). One morning notification says it with the right fix; the UI may show it
 * once too ({@link PhoneAfk#lastEnd}, {@link PhoneAfk#dismissLastEnd}).
 */
public final class AfkEnd {
    public enum Kind {
        /** The phone's battery manager (or the maker's) closed it: the fix is the battery setting. */
        CLOSED_BY_PHONE,
        /** Android needed the memory. */
        LOW_MEMORY,
        /** The app crashed. */
        CRASHED,
        /** The app was updated. */
        APP_UPDATED,
        /** The player closed the app from Android's settings or task manager (no notification). */
        STOPPED_BY_PLAYER,
        UNKNOWN
    }

    public final Kind kind;
    /** When it ended (ms since the epoch). */
    public final long at;
    /** The names that were running. */
    public final List<String> names;

    public AfkEnd(Kind kind, long at, List<String> names) {
        this.kind = kind == null ? Kind.UNKNOWN : kind;
        this.at = at;
        this.names = names == null ? Collections.<String>emptyList() : Collections.unmodifiableList(names);
    }

    /** The fix is the battery setting ("Ayarı aç"). */
    public boolean batteryFix() {
        return kind == Kind.CLOSED_BY_PHONE || kind == Kind.UNKNOWN;
    }
}
