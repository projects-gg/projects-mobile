package gg.projects.mobile.afk.api;

/** Where an AFK account is. Stable; new states may be added at the end (treat an unknown one as {@link #CONNECTING}). */
public enum AfkState {
    /** Not running (the player stopped it, or it stopped for a reason: {@link AfkStatus#reason}). */
    STOPPED,
    /** Opening the connection (its turn may wait a few seconds: one server sees one attempt at a time). */
    CONNECTING,
    /** Connected, the server is checking the name. */
    LOGGING_IN,
    /** The server is sending its settings (also during a proxy's server switch). */
    CONFIGURING,
    /** In game: {@link AfkStatus#inGameSince}. */
    IN_GAME,
    /** Dropped; the next attempt is at {@link AfkStatus#nextAttemptAt} ({@link AfkStatus#reason} says why). */
    WAITING,
    /** No usable network ({@link AfkReason#NO_NETWORK} or {@link AfkReason#WAITING_WIFI}); goes on by itself. */
    NO_NETWORK;

    /** Anything but {@link #STOPPED}: the account takes one of the {@link AfkLimits#MAX_RUNNING} places. */
    public boolean running() {
        return this != STOPPED;
    }

    /** On its way in (connecting, logging in, configuring). */
    public boolean joining() {
        return this == CONNECTING || this == LOGGING_IN || this == CONFIGURING;
    }
}
