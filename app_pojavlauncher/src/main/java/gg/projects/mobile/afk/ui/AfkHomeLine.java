package gg.projects.mobile.afk.ui;

import java.util.List;

import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

/**
 * The line the main menu's "AFK hesapları" row shows: what matters most, as 7/24 Bulut's row picks it. A stop the
 * player should read first (the server's or the phone's; flare), then the accounts on their way back (a step brighter),
 * then how many are in the game. Plain Java, JVM-tested.
 */
public final class AfkHomeLine {
    public enum Kind {
        /** One account stopped for a reason: "{name} durdu". */
        STOPPED_ONE,
        /** Several: "{count} AFK hesabı durdu". */
        STOPPED_MANY,
        /** Every running account is in the game: "{count} hesap oyunda". */
        ALL_IN_GAME,
        /** Some in the game: "{count} hesap AFK'da, {inGame} tanesi oyunda". */
        SOME_IN_GAME,
        /** One account running, not in the game yet: its own state words ("Wi-Fi bekleniyor"). */
        ONE_ON_THE_WAY,
        /** Several running, none in the game: "{count} hesap bağlanıyor". */
        MANY_ON_THE_WAY,
        /** Nothing running: "AFK'da hesap yok". */
        NONE_RUNNING
    }

    public final Kind kind;
    public final int count;
    public final int inGame;
    /** STOPPED_ONE and ONE_ON_THE_WAY: the account. */
    public final AfkStatus status;

    private AfkHomeLine(Kind kind, int count, int inGame, AfkStatus status) {
        this.kind = kind;
        this.count = count;
        this.inGame = inGame;
        this.status = status;
    }

    /** Whether the line is about trouble (the flare words). */
    public boolean alarming() {
        return kind == Kind.STOPPED_ONE || kind == Kind.STOPPED_MANY;
    }

    /** Whether accounts are on their way back without being in the game (a step brighter than the quiet line). */
    public boolean waiting() {
        return kind == Kind.ONE_ON_THE_WAY || kind == Kind.MANY_ON_THE_WAY;
    }

    public static AfkHomeLine of(List<AfkStatus> statuses) {
        AfkStatus firstStop = null;
        int stops = 0;
        int running = 0;
        int inGame = 0;
        AfkStatus firstRunning = null;
        for (AfkStatus s : statuses) {
            if (AfkLook.stoppedBecause(s)) {
                if (firstStop == null) firstStop = s;
                stops++;
            }
            if (s.state.running()) {
                if (firstRunning == null) firstRunning = s;
                running++;
                if (s.state == AfkState.IN_GAME) inGame++;
            }
        }
        if (stops == 1) return new AfkHomeLine(Kind.STOPPED_ONE, 1, inGame, firstStop);
        if (stops > 1) return new AfkHomeLine(Kind.STOPPED_MANY, stops, inGame, null);
        if (running == 0) return new AfkHomeLine(Kind.NONE_RUNNING, 0, 0, null);
        if (inGame == running) return new AfkHomeLine(Kind.ALL_IN_GAME, running, inGame, null);
        if (inGame == 0) {
            return running == 1 ? new AfkHomeLine(Kind.ONE_ON_THE_WAY, 1, 0, firstRunning)
                    : new AfkHomeLine(Kind.MANY_ON_THE_WAY, running, 0, null);
        }
        return new AfkHomeLine(Kind.SOME_IN_GAME, running, inGame, null);
    }
}
