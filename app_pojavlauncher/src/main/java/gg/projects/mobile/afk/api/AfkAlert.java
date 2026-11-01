package gg.projects.mobile.afk.api;

/**
 * Something the player should look at (phone-afk.md §4: the "AFK uyarıları" channel). The service already posted
 * the system notification; the UI may show it in place too. The app never answers for the player (no auto-reply:
 * the server's bot checks are the player's, sözleşme §7.14). Immutable. Stable; kinds may be added.
 */
public final class AfkAlert {
    public enum Kind {
        /** The account's name stood in someone's chat line ({@link #text}). */
        MENTION,
        /** A private message to the account ({@link #text}). */
        PRIVATE_MESSAGE,
        /** The server opened a window or a dialog (a menu, a check); {@link #text} is its title or empty. */
        SERVER_WINDOW,
        /** The account has been out of the game for a while and keeps trying ({@link #reason}). */
        DROPPED,
        /** The account stopped ({@link #reason}). */
        STOPPED
    }

    public final String accountId;
    public final String name;
    public final Kind kind;
    /** Plain text (masked), may be empty. */
    public final String text;
    public final AfkReason reason;
    public final long time;

    public AfkAlert(String accountId, String name, Kind kind, String text, AfkReason reason, long time) {
        this.accountId = accountId;
        this.name = name;
        this.kind = kind;
        this.text = text == null ? "" : text;
        this.reason = reason;
        this.time = time;
    }

    @Override
    public String toString() {
        return "AfkAlert{" + name + " " + kind + "}";
    }
}
