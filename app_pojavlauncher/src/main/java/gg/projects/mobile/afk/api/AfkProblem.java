package gg.projects.mobile.afk.api;

/**
 * What is wrong with a draft ({@link PhoneAfk#check}); each has a one-line text, string resource {@link #resName()}.
 * Stable; problems may be added.
 */
public enum AfkProblem {
    /** Not 3–16 letters, digits or underscores (the desktop's sentence). */
    NAME_INVALID("name_invalid", Field.NAME),
    /** Another AFK account has this name (ignoring case). */
    NAME_TAKEN("name_taken", Field.NAME),
    ADDRESS_MISSING("address_missing", Field.ADDRESS),
    ADDRESS_INVALID("address_invalid", Field.ADDRESS),
    PORT_INVALID("port_invalid", Field.ADDRESS),
    /** A server in the local network (the first version does not support them; phone-afk.md §4). */
    LOCAL_ADDRESS("local_address", Field.ADDRESS),
    TOO_MANY_COMMANDS("too_many_commands", Field.LOGIN_COMMANDS),
    COMMAND_TOO_LONG("command_too_long", Field.LOGIN_COMMANDS),
    /** The phone already keeps {@link AfkLimits#MAX_ACCOUNTS} (adding only). */
    TOO_MANY_ACCOUNTS("too_many_accounts", Field.NONE),
    /** The phone could not save the account (its storage or its key store); trying again may work. */
    NOT_SAVED("not_saved", Field.NONE);

    /** The input the problem belongs to. */
    public enum Field { NAME, ADDRESS, LOGIN_COMMANDS, NONE }

    private final String key;
    public final Field field;

    AfkProblem(String key, Field field) {
        this.key = key;
        this.field = field;
    }

    public String key() {
        return key;
    }

    /** {@code projects_afk_problem_name_invalid}. */
    public String resName() {
        return "projects_afk_problem_" + key;
    }
}
