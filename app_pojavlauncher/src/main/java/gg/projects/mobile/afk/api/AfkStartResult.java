package gg.projects.mobile.afk.api;

/** {@link PhoneAfk#start}'s answer; the ones the player should read have a text ({@link #resName()}). */
public enum AfkStartResult {
    /** Started (or starting): watch {@link AfkListener#onStatus}. */
    STARTED("started"),
    ALREADY_RUNNING("already_running"),
    /** {@link AfkLimits#MAX_RUNNING} accounts are already AFK. */
    LIMIT_REACHED("limit_reached"),
    /** Not in this build, below Android 8, or Studio's switch is off. */
    NOT_AVAILABLE("not_available"),
    /** No such account. */
    NO_ACCOUNT("no_account"),
    /** Android refused to start the service (the app was not on screen); try again from the app. */
    NOT_ALLOWED("not_allowed");

    private final String key;

    AfkStartResult(String key) {
        this.key = key;
    }

    public boolean ok() {
        return this == STARTED || this == ALREADY_RUNNING;
    }

    public String key() {
        return key;
    }

    /** {@code projects_afk_start_limit_reached}. */
    public String resName() {
        return "projects_afk_start_" + key;
    }
}
