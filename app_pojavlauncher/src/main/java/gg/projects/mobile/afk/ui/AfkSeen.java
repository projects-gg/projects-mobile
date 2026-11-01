package gg.projects.mobile.afk.ui;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import gg.projects.mobile.afk.api.AfkAlert;

/**
 * What an account's row says it has waiting since the player last looked at its console (in memory while the launcher
 * runs): the name came up in chat, a private message, a window the server opened. The notification already went out;
 * the row keeps the word until the console is opened. Drops and stops are the state line's, not this.
 */
public final class AfkSeen {
    private AfkSeen() {}

    private static final Map<String, AfkAlert.Kind> WAITING = new HashMap<>();

    public static synchronized void note(AfkAlert alert) {
        switch (alert.kind) {
            case MENTION:
            case PRIVATE_MESSAGE:
            case SERVER_WINDOW:
                // A window the server opened (a bot check) outranks a message, a message outranks a mention.
                AfkAlert.Kind had = WAITING.get(alert.accountId);
                if (had == null || rank(alert.kind) >= rank(had)) WAITING.put(alert.accountId, alert.kind);
                break;
            default:
                break;
        }
    }

    @Nullable
    public static synchronized AfkAlert.Kind waiting(String accountId) {
        return WAITING.get(accountId);
    }

    /** The player opened the account's console. */
    public static synchronized void seen(String accountId) {
        WAITING.remove(accountId);
    }

    private static int rank(AfkAlert.Kind kind) {
        switch (kind) {
            case SERVER_WINDOW: return 3;
            case PRIVATE_MESSAGE: return 2;
            default: return 1;
        }
    }
}
