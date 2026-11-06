package gg.projects.mobile.afk.api;

/**
 * One AFK account as stored on the phone (no login commands: those stay sealed until a connection or the edit
 * screen needs them, {@link PhoneAfk#edit}). Immutable. Stable; fields may be added.
 */
public final class AfkAccount {
    /** Stable id (random, never shown). */
    public final String id;
    /** The offline name ({@link AfkLimits#NAME_MIN}–{@link AfkLimits#NAME_MAX} of {@code [A-Za-z0-9_]}). */
    public final String name;
    /** The server's host as the player wrote it (lower case, IDN in ASCII). */
    public final String host;
    /** The port the player wrote, or 0: none written (the server's SRV record, else {@link AfkLimits#DEFAULT_PORT}). */
    public final int port;
    /** How many login commands the account has (their text is sealed). */
    public final int loginCommandCount;
    public final AfkLoginMode loginMode;
    /** The sneak anti-AFK (on by default: Projects' AFK check wants movement). */
    public final boolean sneak;
    /** "Mobil veride sürsün" (off by default: Wi-Fi only). */
    public final boolean mobileData;
    /** When it was added (ms since the epoch). */
    public final long createdAt;

    public AfkAccount(String id, String name, String host, int port, int loginCommandCount, AfkLoginMode loginMode,
                      boolean sneak, boolean mobileData, long createdAt) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.loginCommandCount = loginCommandCount;
        this.loginMode = loginMode == null ? AfkLoginMode.EVERY_JOIN : loginMode;
        this.sneak = sneak;
        this.mobileData = mobileData;
        this.createdAt = createdAt;
    }

    /** The address as the player reads it: {@code play.example.com} or {@code play.example.com:25566}. */
    public String address() {
        String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return port == 0 ? h : h + ":" + port;
    }

    public boolean hasLoginCommands() {
        return loginCommandCount > 0;
    }

    @Override
    public String toString() {
        // Never the login commands (they are not here anyway).
        return "AfkAccount{" + id + ", " + name + " @ " + address() + "}";
    }
}
