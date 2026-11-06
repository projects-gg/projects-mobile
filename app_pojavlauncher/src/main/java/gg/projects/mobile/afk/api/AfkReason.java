package gg.projects.mobile.afk.api;

/**
 * Why an account dropped, waits or stopped. Each has a short sentence-case text in the app's language: the string
 * resource {@link #resName()} ({@code projects_afk_core_strings.xml}, English and Turkish; {@link AfkTexts#reason}).
 * The drop and stop wording is the desktop's ({@code afk.failure}, {@code afk.refusal}). Stable; new reasons may be
 * added (an unknown one reads as {@link #DISCONNECTED}).
 */
public enum AfkReason {
    // A drop: the account reconnects by itself.
    KICKED("kicked", Kind.DROP),
    LOGIN_REJECTED("login_rejected", Kind.DROP),
    CONNECTION_LOST("connection_lost", Kind.DROP),
    DISCONNECTED("disconnected", Kind.DROP),
    /** The server did not answer or refused the connection. */
    UNREACHABLE("unreachable", Kind.DROP),
    /** The address does not resolve (the name may be wrong; retried, a DNS failure can pass). */
    ADDRESS_NOT_FOUND("address_not_found", Kind.DROP),
    /** No network at all: goes on when one comes back ("Bağlantı yok"). */
    NO_NETWORK("no_network", Kind.DROP),
    /** Only mobile data while "Mobil veride sürsün" is off: goes on on Wi-Fi. */
    WAITING_WIFI("waiting_wifi", Kind.DROP),
    /** Something failed inside the app; retried like a drop. */
    ERROR("error", Kind.DROP),

    // The server keeps turning the account away: it stopped instead of trying again (the desktop's KickCause).
    BANNED("banned", Kind.REFUSAL),
    NOT_WHITELISTED("not_whitelisted", Kind.REFUSAL),
    WRONG_PASSWORD("wrong_password", Kind.REFUSAL),
    PREMIUM_ONLY("premium_only", Kind.REFUSAL),
    LOGGED_IN_ELSEWHERE("logged_in_elsewhere", Kind.REFUSAL),
    /** Stops only after {@code 5} in a row (the server may still hold a dropped session). */
    ALREADY_CONNECTED("already_connected", Kind.REFUSAL),
    /** Stops only after {@code 5} in a row (no or wrong login commands; a slow server once is not enough). */
    LOGIN_TIMEOUT("login_timeout", Kind.REFUSAL),
    /** The server does not take the phone client's Minecraft version (26.3). */
    UNSUPPORTED_VERSION("unsupported_version", Kind.REFUSAL),

    // The phone stopped it.
    /** {@link AfkLimits#BATTERY_STOP_PERCENT} or less while not charging. */
    BATTERY_LOW("battery_low", Kind.PHONE),
    /** The phone's thermal state reached "severe". */
    TOO_HOT("too_hot", Kind.PHONE),
    /** Android (or the maker's battery saver) closed the AFK process; {@link AfkEnd} says more. */
    PROCESS_ENDED("process_ended", Kind.PHONE),
    /** The app was updated while AFK ran. */
    APP_UPDATED("app_updated", Kind.PHONE);

    /** What kind of reason it is. */
    public enum Kind {
        /** Temporary: the account is reconnecting or waiting for a network. */
        DROP,
        /** Permanent: the account stopped (the server's answer will not change by trying again). */
        REFUSAL,
        /** The phone stopped the account. */
        PHONE
    }

    private final String key;
    public final Kind kind;

    AfkReason(String key, Kind kind) {
        this.key = key;
        this.kind = kind;
    }

    /** The account stopped for it (a refusal or the phone), as opposed to a drop it reconnects after. */
    public boolean stops() {
        return kind != Kind.DROP;
    }

    /** Stable id for storage and IPC ({@code "banned"}). */
    public String key() {
        return key;
    }

    /** The string resource with its text ({@code projects_afk_reason_banned}). */
    public String resName() {
        return "projects_afk_reason_" + key;
    }

    /** The reason with this {@link #key()}, or null. */
    public static AfkReason ofKey(String key) {
        if (key == null) return null;
        for (AfkReason r : values()) {
            if (r.key.equals(key)) return r;
        }
        return null;
    }
}
