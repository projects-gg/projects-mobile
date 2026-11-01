package gg.projects.mobile.afk.api;

/**
 * PHONE-AFK's fixed limits (docs/tasks/phone-afk.md §3, §7). Stable: the UI shows them and the engine enforces them.
 */
public final class AfkLimits {
    private AfkLimits() {}

    /** Android 8: the AFK row is hidden below it ({@link PhoneAfk#phoneSupported()}). */
    public static final int MIN_SDK = 26;
    /** Accounts AFK at the same time (memory and battery; the UI says so plainly). */
    public static final int MAX_RUNNING = 3;
    /** AFK accounts kept on the phone. */
    public static final int MAX_ACCOUNTS = 20;
    /** Minecraft's name rules (the desktop's): 3–16 of {@code [A-Za-z0-9_]}. */
    public static final int NAME_MIN = 3;
    public static final int NAME_MAX = 16;
    /** Login commands per account. */
    public static final int MAX_LOGIN_COMMANDS = 5;
    /** Minecraft's chat limit: a login command and a line the player sends. */
    public static final int MAX_LINE_CHARS = 256;
    /** A server address as typed (host[:port]). */
    public static final int MAX_ADDRESS_CHARS = 253;
    /** Console lines kept per account while the service runs (older ones go; nothing is kept on disk). */
    public static final int CONSOLE_LINES = 300;
    /** The player's own lines: at most {@link #SEND_BURST} in {@link #SEND_BURST_WINDOW_MS}... */
    public static final int SEND_BURST = 3;
    public static final long SEND_BURST_WINDOW_MS = 3_000L;
    /** ...and at most this many a minute (servers kick for spam; phone-afk.md §8 "Kötüye kullanım"). */
    public static final int SEND_PER_MINUTE = 20;
    /** The client's view distance: less data and CPU. */
    public static final int VIEW_DISTANCE = 2;
    /** Battery percent at or under which AFK stops while the phone is not charging. */
    public static final int BATTERY_STOP_PERCENT = 15;
    /** Minecraft's default port (the address without a port also looks up the server's SRV record). */
    public static final int DEFAULT_PORT = 25565;
    /** Seconds before the first login command and between two of them (the desktop's default). */
    public static final int LOGIN_COMMAND_GAP_SECONDS = 2;
}
