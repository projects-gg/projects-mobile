package gg.projects.mobile.afk.api;

/** {@link PhoneAfk#send}'s answer; each but {@link #SENT} has a text ({@link #resName()}). */
public enum AfkSendResult {
    SENT("sent"),
    EMPTY("empty"),
    /** Over {@link AfkLimits#MAX_LINE_CHARS}. */
    TOO_LONG("too_long"),
    /** Over the rate limit ({@link AfkLimits#SEND_BURST}, {@link AfkLimits#SEND_PER_MINUTE}): wait a little. */
    TOO_FAST("too_fast"),
    /** The account is running but not in game right now. */
    NOT_IN_GAME("not_in_game"),
    /** The account is not running. */
    NOT_RUNNING("not_running");

    private final String key;

    AfkSendResult(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static AfkSendResult ofKey(String key) {
        for (AfkSendResult r : values()) {
            if (r.key.equals(key)) return r;
        }
        return NOT_RUNNING;
    }

    /** {@code projects_afk_send_too_fast}. */
    public String resName() {
        return "projects_afk_send_" + key;
    }
}
