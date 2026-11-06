package gg.projects.mobile.afk.ui;

import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

/**
 * How a phone AFK account reads (the desktop's state line, {@code features/afk/state-line.tsx}): the server bars, the
 * words' tone and the row's main action. As the launcher and the 7/24 Bulut list: the words are bone and the colour is
 * in the bars; flare words only for a stop the server decided (ban, wrong password); a real-state warning (a drop, no
 * network, the phone stopping AFK) is a small gold triangle before bone words. Plain Java, JVM-tested.
 */
public final class AfkLook {
    private AfkLook() {}

    /** The words' tone (the Android side picks the colours: bone, ash, a gold mark, flare). */
    public enum Tone { OK, MUTED, WARN, ERROR }

    /** The server bars: in the game, on the way there, idle, stopped by something the player must fix. */
    public enum Signal { ONLINE, PENDING, IDLE, OFFLINE }

    /** The row's main button (the desktop's {@code primary-action.tsx}, without its client download states). */
    public enum Action { CONNECT, STOP, FIX_PASSWORD, FIX_LOGIN }

    /** Units of a span of time in words. */
    public enum Unit { SECOND, MINUTE, HOUR, DAY }

    public static Tone tone(AfkStatus s) {
        switch (s.state) {
            case IN_GAME:
                return Tone.OK;
            case WAITING:
            case NO_NETWORK:
                return Tone.WARN;
            case STOPPED:
                if (s.reason == null) return Tone.MUTED;
                return s.reason.kind == AfkReason.Kind.REFUSAL ? Tone.ERROR : Tone.WARN;
            default:
                // Connecting, logging in, configuring: on the way, nothing to worry about.
                return Tone.MUTED;
        }
    }

    public static Signal signal(AfkStatus s) {
        switch (s.state) {
            case IN_GAME:
                return Signal.ONLINE;
            case NO_NETWORK:
                return Signal.IDLE;
            case STOPPED:
                return refused(s) ? Signal.OFFLINE : Signal.IDLE;
            default:
                return Signal.PENDING;
        }
    }

    /** Stopped because the server keeps turning it away (it will not get in by trying again). */
    public static boolean refused(AfkStatus s) {
        return s.state == AfkState.STOPPED && s.reason != null && s.reason.kind == AfkReason.Kind.REFUSAL;
    }

    /** Stopped for a reason the player should read (the server's or the phone's), not by the player's own tap. */
    public static boolean stoppedBecause(AfkStatus s) {
        return s.state == AfkState.STOPPED && s.reason != null && s.reason.stops();
    }

    public static Action action(AfkStatus s) {
        if (s.state.running()) return Action.STOP;
        if (s.reason == AfkReason.WRONG_PASSWORD) return Action.FIX_PASSWORD;
        if (s.reason == AfkReason.LOGIN_TIMEOUT) return Action.FIX_LOGIN;
        return Action.CONNECT;
    }

    /** Whether "Hepsini bağla" takes the account: stopped, and not one the server refused (fixed on its own row first). */
    public static boolean bulkConnects(AfkStatus s) {
        return !s.state.running() && !refused(s);
    }

    /** How long the account has been in the game, or -1 when it is not (or the time is unknown). */
    public static long uptime(AfkStatus s, long now) {
        if (s.state != AfkState.IN_GAME || s.inGameSince <= 0) return -1;
        return Math.max(0, now - s.inGameSince);
    }

    /**
     * A span in words, two units at most, the larger first (design.md §7): seconds only in the first minute (the
     * desktop's uptime), then minutes, then hours with minutes, then days with hours; a zero second unit is left out.
     * Returns {@code {value, unit ordinal}} or {@code {value, unit, value, unit}}.
     */
    public static int[] span(long ms) {
        long seconds = Math.max(0, ms / 1000);
        if (seconds < 60) return new int[]{(int) seconds, Unit.SECOND.ordinal()};
        long minutes = seconds / 60;
        if (minutes < 60) return new int[]{(int) minutes, Unit.MINUTE.ordinal()};
        long hours = minutes / 60;
        if (hours < 24) {
            int m = (int) (minutes % 60);
            return m == 0 ? new int[]{(int) hours, Unit.HOUR.ordinal()} : new int[]{(int) hours, Unit.HOUR.ordinal(), m, Unit.MINUTE.ordinal()};
        }
        long days = hours / 24;
        int h = (int) (hours % 24);
        return h == 0 ? new int[]{(int) days, Unit.DAY.ordinal()} : new int[]{(int) days, Unit.DAY.ordinal(), h, Unit.HOUR.ordinal()};
    }

    /**
     * In how many ms the account's words change by themselves (the uptime's next second or minute, the reconnect
     * countdown), or 0 when they do not. The screen asks again then; nothing ticks while nothing changes.
     */
    public static long nextChange(AfkStatus s, long now) {
        if (s.state == AfkState.IN_GAME) {
            long up = uptime(s, now);
            if (up < 0) return 0;
            if (up < 60_000) return 1000 - (up % 1000);
            return 60_000 - (up % 60_000);
        }
        if (s.state == AfkState.WAITING && s.nextAttemptAt > now) {
            long left = s.nextAttemptAt - now;
            // AfkTexts.duration rounds up: whole seconds under a minute, whole minutes above.
            if (left <= 60_000) return Math.max(50, left % 1000 == 0 ? 1000 : left % 1000);
            long part = left % 60_000;
            return Math.max(50, part == 0 ? 60_000 : part);
        }
        if (s.state == AfkState.WAITING) return 1000;
        return 0;
    }
}
