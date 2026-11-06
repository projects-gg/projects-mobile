package gg.projects.mobile.afk.api;

import java.util.List;

/**
 * What a watching screen hears ({@link PhoneAfk#watch}), always on the main thread. Every method has an empty
 * default: implement what the screen needs. Stable; methods may be added (with defaults).
 */
public interface AfkListener {
    /**
     * Every stored account's status: when watching starts, when the service starts or ends, and after accounts are
     * added or removed. Accounts not running are {@link AfkState#STOPPED} (with the reason they stopped for, if any).
     */
    default void onStatuses(List<AfkStatus> all) {}

    /** One account's status changed. */
    default void onStatus(AfkStatus status) {}

    /** The console lines the service kept for an account (oldest first), when watching starts or the service does. */
    default void onBacklog(String accountId, List<AfkLine> lines) {}

    /** A new console line. */
    default void onLine(AfkLine line) {}

    /** Something the player should see (its notification is already posted). */
    default void onAlert(AfkAlert alert) {}
}
