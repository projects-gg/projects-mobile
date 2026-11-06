package gg.projects.mobile.afk.api;

/**
 * When the login commands go (the desktop's {@code onlyFirstLogin}): {@link AfkLimits#LOGIN_COMMAND_GAP_SECONDS}
 * after the join and between commands.
 */
public enum AfkLoginMode {
    /** Every time the account joins a game: each connection and each server switch behind a proxy (the default). */
    EVERY_JOIN("every"),
    /** Once per connection: not again after a proxy's server switch. */
    FIRST_JOIN("first");

    private final String key;

    AfkLoginMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static AfkLoginMode ofKey(String key) {
        return "first".equals(key) ? FIRST_JOIN : EVERY_JOIN;
    }
}
