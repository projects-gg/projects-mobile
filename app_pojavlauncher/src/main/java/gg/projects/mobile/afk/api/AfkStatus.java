package gg.projects.mobile.afk.api;

/**
 * An account's live status ({@link AfkListener#onStatus}). Times are wall-clock milliseconds; 0 means "none".
 * Immutable. Stable; fields may be added.
 */
public final class AfkStatus {
    public final String accountId;
    public final String name;
    public final AfkState state;
    /** When {@link #state} began. */
    public final long since;
    /** IN_GAME: when the account got in (unchanged by a proxy's server switch); else 0. */
    public final long inGameSince;
    /** WAITING: when the next attempt starts; else 0. */
    public final long nextAttemptAt;
    /** Attempts since the last lasting connection (two minutes in game); 0 before the first drop. */
    public final int attempt;
    /** WAITING, NO_NETWORK: why; STOPPED: why it stopped, or null when the player stopped it. */
    public final AfkReason reason;
    /** The server's own words for {@link #reason} (one line, colours removed, secrets masked), or null. */
    public final String serverMessage;

    public AfkStatus(String accountId, String name, AfkState state, long since, long inGameSince, long nextAttemptAt,
                     int attempt, AfkReason reason, String serverMessage) {
        this.accountId = accountId;
        this.name = name;
        this.state = state == null ? AfkState.STOPPED : state;
        this.since = since;
        this.inGameSince = inGameSince;
        this.nextAttemptAt = nextAttemptAt;
        this.attempt = attempt;
        this.reason = reason;
        this.serverMessage = serverMessage;
    }

    /** A stopped account with nothing to say (no service, or the player stopped it). */
    public static AfkStatus stopped(String accountId, String name) {
        return new AfkStatus(accountId, name, AfkState.STOPPED, 0, 0, 0, 0, null, null);
    }

    @Override
    public String toString() {
        return "AfkStatus{" + name + " " + state + (reason == null ? "" : " " + reason) + "}";
    }
}
