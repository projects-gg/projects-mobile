package gg.projects.mobile.afk.core;

import java.util.List;

import gg.projects.mobile.afk.api.AfkAccount;
import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkStatus;

/**
 * Everything the engine needs from its surroundings (the service on the phone, stand-ins in the JVM tests): time and
 * timers on the engine thread, the stored accounts, the app's words, and where its news goes.
 */
public interface AfkHost {
    /** Wall-clock milliseconds. */
    long now();

    /** Runs {@code r} on the engine thread after {@code delayMs}. */
    Cancel schedule(long delayMs, Runnable r);

    interface Cancel {
        void cancel();
    }

    /** An account as stored now with its login commands, or null when it was removed. */
    Loaded load(String accountId);

    final class Loaded {
        public final AfkAccount account;
        public final List<String> loginCommands;

        public Loaded(AfkAccount account, List<String> loginCommands) {
            this.account = account;
            this.loginCommands = loginCommands;
        }
    }

    /** The app's own console lines, in its language. */
    interface Words {
        String connecting(String address);

        String joined();

        String dropped(AfkReason reason, long waitMs);

        String stopped(AfkReason reason);

        String stoppedByPlayer();

        String transfer(String address);

        String window(String title);

        String respawned();

        String loginCommandsSent();

        /** The player saved new settings and asked to connect again with them. */
        String reconnecting();

        /** The app speaks Turkish (vanilla's keys are shown in it; the client tells servers {@code tr_tr}). */
        boolean turkish();
    }

    Words words();

    void status(AfkStatus status);

    void line(AfkLine line);

    void alert(AfkAlert alert);

    /** An alert no longer stands (the account is back in game): its notification goes. */
    void alertCleared(String accountId, AfkAlert.Kind kind);

    /** What runs or why accounts stopped changed (runtime.json, the ongoing notification, the wake lock). */
    void changed();
}
