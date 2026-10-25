package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

import gg.projects.mobile.cloud.CloudModel.Account;
import gg.projects.mobile.cloud.CloudModel.Hold;
import gg.projects.mobile.cloud.CloudModel.Live;

/**
 * A cloud account in words, the launcher's {@code status.ts} rule for rule: its state line (the hold wins over the
 * live state, as the contract says), what to do about a stop, its main action and its paid time. Pure, so the list,
 * the account screen, the console and the tests read the same thing; the screens word the keys.
 */
public final class CloudStatus {
    private CloudStatus() {}

    public enum Tone { OK, MUTED, WARN, ERROR }

    /** The state glyph: in the game, on the way there, idle, stopped by a problem. */
    public enum Signal { ONLINE, PENDING, IDLE, OFFLINE }

    public enum State {
        RUNNING, RUNNING_NOW, CONNECTING, WAITING, WAITING_IN, QUEUED, QUEUED_FULL, STOPPED, UNKNOWN, EXPIRED, SUSPENDED,
        RESTRICTED, NO_HOST, PAUSED, OFF, SERVER_BLOCKED, SERVER_BLOCKED_BECAUSE, STOPPED_BECAUSE, INVALID_COMMAND
    }

    public enum Hint {
        WRONG_PASSWORD, SECRET_UNREADABLE, UNREADABLE_COMMAND_ROW, UNREADABLE_REPLY_ROW, INVALID_COMMAND, INVALID_COMMAND_ROW,
        INVALID_REPLY_ROW, PREMIUM_ONLY, NOT_WHITELISTED, LOGGED_IN_ELSEWHERE, ALREADY_CONNECTED, LOGIN_TIMEOUT,
        UNSUPPORTED_VERSION, CRASH_LOOP, FORBIDDEN_TARGET, SERVER_BLOCKED, NO_HOST
    }

    /**
     * Whether the remedy is in the account's settings (a login command, a secret, a server or a version to pick): the
     * account screen then offers the settings as its main action, since starting again would stop the same way.
     */
    public static boolean fixInSettings(@Nullable Hint hint) {
        if (hint == null) return false;
        switch (hint) {
            case WRONG_PASSWORD:
            case SECRET_UNREADABLE:
            case UNREADABLE_COMMAND_ROW:
            case UNREADABLE_REPLY_ROW:
            case INVALID_COMMAND:
            case INVALID_COMMAND_ROW:
            case INVALID_REPLY_ROW:
            case PREMIUM_ONLY:
            case LOGIN_TIMEOUT:
            case UNSUPPORTED_VERSION:
            case CRASH_LOOP:
            case FORBIDDEN_TARGET:
            case SERVER_BLOCKED:
                return true;
            default:
                return false;
        }
    }

    /** The stop causes the screens word; anything else reads like a plain disconnect ({@code other}). */
    public static final List<String> CAUSES = Arrays.asList("banned", "notWhitelisted", "wrongPassword", "premiumOnly",
            "loggedInElsewhere", "alreadyConnected", "loginTimeout", "unsupportedVersion", "secretUnreadable", "invalidCommand",
            "crashLoop", "forbiddenTarget");

    public static final class Line {
        public final State state;
        public final Signal signal;
        public final Tone tone;
        /** {@link State#STOPPED_BECAUSE}: the cause's code ({@code other} when unknown). */
        @Nullable
        public final String cause;
        /** {@link State#SERVER_BLOCKED_BECAUSE}: Studio's words. */
        @Nullable
        public final String blockedReason;
        /** {@link State#RUNNING}: how long it has been in the game, in seconds (whole minutes after the first). */
        public final long uptime;
        /** {@link State#WAITING_IN}: seconds to the next attempt. */
        public final long seconds;
        @Nullable
        public final Hint hint;
        /** The row a hint names, counted as the settings list them (1-based), or 0. */
        public final int hintRow;
        /** Milliseconds until the words change by themselves; -1 when they do not. */
        public final long nextChangeMs;

        Line(State state, Signal signal, Tone tone, @Nullable String cause, @Nullable String blockedReason, long uptime, long seconds,
             @Nullable Hint hint, int hintRow, long nextChangeMs) {
            this.state = state;
            this.signal = signal;
            this.tone = tone;
            this.cause = cause;
            this.blockedReason = blockedReason;
            this.uptime = uptime;
            this.seconds = seconds;
            this.hint = hint;
            this.hintRow = hintRow;
            this.nextChangeMs = nextChangeMs;
        }

        static Line still(State state, Signal signal, Tone tone) {
            return new Line(state, signal, tone, null, null, 0, 0, null, 0, -1);
        }

        static Line still(State state, Signal signal, Tone tone, @Nullable Hint hint, int row) {
            return new Line(state, signal, tone, null, null, 0, 0, hint, row, -1);
        }
    }

    @Nullable
    private static Hint causeHint(String cause) {
        switch (cause) {
            case "wrongPassword": return Hint.WRONG_PASSWORD;
            case "secretUnreadable": return Hint.SECRET_UNREADABLE;
            case "invalidCommand": return Hint.INVALID_COMMAND;
            case "premiumOnly": return Hint.PREMIUM_ONLY;
            case "notWhitelisted": return Hint.NOT_WHITELISTED;
            case "loggedInElsewhere": return Hint.LOGGED_IN_ELSEWHERE;
            case "alreadyConnected": return Hint.ALREADY_CONNECTED;
            case "loginTimeout": return Hint.LOGIN_TIMEOUT;
            case "unsupportedVersion": return Hint.UNSUPPORTED_VERSION;
            case "crashLoop": return Hint.CRASH_LOOP;
            case "forbiddenTarget": return Hint.FORBIDDEN_TARGET;
            default: return null;
        }
    }

    /** The hint of a stop that names its row (older machines name none): which command or auto-reply to fix. */
    private static Line rowLine(Account account, State state, String cause, @Nullable String row) {
        boolean invalid = "invalidCommand".equals(cause);
        int command = -1;
        int rule = -1;
        if (row != null) {
            for (int i = 0; i < account.config.commands.size(); i++) if (account.config.commands.get(i).id.equals(row)) command = i;
            for (int i = 0; i < account.config.rules.size(); i++) if (account.config.rules.get(i).id.equals(row)) rule = i;
        }
        Hint hint;
        int n = 0;
        if (command >= 0) {
            hint = invalid ? Hint.INVALID_COMMAND_ROW : Hint.UNREADABLE_COMMAND_ROW;
            n = command + 1;
        } else if (rule >= 0) {
            hint = invalid ? Hint.INVALID_REPLY_ROW : Hint.UNREADABLE_REPLY_ROW;
            n = rule + 1;
        } else {
            hint = invalid ? Hint.INVALID_COMMAND : Hint.SECRET_UNREADABLE;
        }
        return new Line(state, Signal.OFFLINE, Tone.ERROR, state == State.STOPPED_BECAUSE ? cause : null, null, 0, 0, hint, n, -1);
    }

    /** The account's state at {@code nowMs} (the service's clock); {@code lang} picks Studio's words of a blocked server. */
    public static Line of(Account account, long nowMs, String lang) {
        Hold hold = account.hold;
        if (hold != null) {
            switch (hold.kind) {
                case "suspended": return Line.still(State.SUSPENDED, Signal.IDLE, Tone.ERROR);
                case "restricted": return Line.still(State.RESTRICTED, Signal.IDLE, Tone.ERROR);
                case "expired": return Line.still(State.EXPIRED, Signal.IDLE, Tone.WARN);
                case "paused": return Line.still("off".equals(hold.mode) ? State.OFF : State.PAUSED, Signal.IDLE, Tone.MUTED);
                case "no_host": return Line.still(State.NO_HOST, Signal.IDLE, Tone.MUTED, Hint.NO_HOST, 0);
                case "server_blocked": {
                    String reason = hold.blockedReason == null ? null : hold.blockedReason.in(lang);
                    if (reason != null && !reason.isEmpty()) {
                        return new Line(State.SERVER_BLOCKED_BECAUSE, Signal.OFFLINE, Tone.ERROR, null, reason, 0, 0, Hint.SERVER_BLOCKED, 0, -1);
                    }
                    return Line.still(State.SERVER_BLOCKED, Signal.OFFLINE, Tone.ERROR, Hint.SERVER_BLOCKED, 0);
                }
                case "cause": {
                    String cause = hold.cause == null ? "other" : hold.cause;
                    if ("invalidCommand".equals(cause)) return rowLine(account, State.INVALID_COMMAND, cause, hold.row);
                    if ("secretUnreadable".equals(cause)) return rowLine(account, State.STOPPED_BECAUSE, cause, hold.row);
                    String known = CAUSES.contains(cause) ? cause : "other";
                    return new Line(State.STOPPED_BECAUSE, Signal.OFFLINE, Tone.ERROR, known, null, 0, 0, causeHint(cause), 0, -1);
                }
                default:
                    // A hold this phone does not know yet: the most generic words of its kind.
                    return Line.still(State.STOPPED, Signal.IDLE, Tone.MUTED);
            }
        }
        if (account.expired) return Line.still(State.EXPIRED, Signal.IDLE, Tone.WARN);
        Live live = account.live;
        switch (live.state) {
            case "running": {
                if (live.since == null) return Line.still(State.RUNNING_NOW, Signal.ONLINE, Tone.OK);
                double up = Math.max(0, nowMs / 1000.0 - live.since);
                long shown = up < 60 ? (long) up : (long) (up / 60) * 60;
                // The words change where the shown seconds (first minute) or minutes do.
                double next = up < 60 ? 1000 - (nowMs % 1000) : 60_000 - ((up * 1000) % 60_000);
                return new Line(State.RUNNING, Signal.ONLINE, Tone.OK, null, null, shown, 0, null, 0, Math.max(250, Math.round(next)));
            }
            case "connecting": return Line.still(State.CONNECTING, Signal.PENDING, Tone.MUTED);
            case "queued": return Line.still("target_cap".equals(live.reason) ? State.QUEUED_FULL : State.QUEUED, Signal.IDLE, Tone.MUTED);
            case "waiting": {
                if (live.retryAt == null) return Line.still(State.WAITING, Signal.IDLE, Tone.MUTED);
                long left = live.retryAt * 1000 - nowMs;
                if (left <= 1000) return Line.still(State.CONNECTING, Signal.PENDING, Tone.MUTED);
                long rest = left % 1000;
                return new Line(State.WAITING_IN, Signal.IDLE, Tone.MUTED, null, null, 0, (left + 999) / 1000, null, 0, rest == 0 ? 1000 : rest);
            }
            case "stopped": return Line.still(State.STOPPED, Signal.IDLE, Tone.MUTED);
            default: return Line.still(State.UNKNOWN, Signal.IDLE, Tone.MUTED);
        }
    }

    /** In the game, or on its way there: a stop makes sense. */
    public static boolean active(Account account) {
        return "run".equals(account.desired) && account.hold == null && !account.expired;
    }

    /** The client is in the game now (the console can send). */
    public static boolean inGame(Account account) {
        return active(account) && "running".equals(account.live.state);
    }

    /** What the main button does. */
    public enum Primary { START, STOP, RENEW, NONE }

    public static Primary primary(Account account) {
        String hold = account.hold == null ? null : account.hold.kind;
        if (account.expired || "expired".equals(hold)) return Primary.RENEW;
        if ("suspended".equals(hold) || "restricted".equals(hold)) return Primary.NONE;
        // A stop for good ends with the next start (the player fixed it; the service clears the hold).
        boolean stoppedForGood = "cause".equals(hold) || "server_blocked".equals(hold);
        return "run".equals(account.desired) && !stoppedForGood ? Primary.STOP : Primary.START;
    }

    /** The paid time left; for an expired account how long its settings are kept. */
    public static final class TimeLeft {
        public enum Unit { DAYS, HOURS, MINUTES, GRACE_DAYS }

        public final Unit unit;
        public final long count;

        TimeLeft(Unit unit, long count) {
            this.unit = unit;
            this.count = count;
        }
    }

    public static TimeLeft timeLeft(Account account, long nowMs) {
        double now = nowMs / 1000.0;
        if (account.expired) return new TimeLeft(TimeLeft.Unit.GRACE_DAYS, Math.max(0, (long) Math.ceil((account.graceUntil - now) / 86_400)));
        double left = account.expiresAt - now;
        if (left >= 86_400) return new TimeLeft(TimeLeft.Unit.DAYS, (long) Math.floor(left / 86_400));
        if (left >= 3600) return new TimeLeft(TimeLeft.Unit.HOURS, (long) Math.floor(left / 3600));
        return new TimeLeft(TimeLeft.Unit.MINUTES, Math.max(1, (long) Math.ceil(left / 60)));
    }

    /** Renewing is open now (the site's checkout takes it); while expired, reactivation. */
    public static boolean renewable(Account account) {
        return account.renewalOpen || account.expired;
    }

    /** "60.00" → "60 TL", "59.90" → "59,90 TL" (English "59.90 TL"). */
    public static String price(String priceTl, String lang) {
        try {
            double value = Double.parseDouble(priceTl);
            long cents = Math.round(value * 100);
            String whole = Long.toString(cents / 100);
            if (cents % 100 == 0) return whole + " TL";
            String fraction = String.format(java.util.Locale.ROOT, "%02d", cents % 100);
            return whole + ("en".equals(lang) ? "." : ",") + fraction + " TL";
        } catch (NumberFormatException e) {
            return priceTl + " TL";
        }
    }
}
