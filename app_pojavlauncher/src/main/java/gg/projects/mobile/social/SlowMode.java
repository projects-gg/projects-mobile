package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * CHAT-CALM "Yavaş mod" (services/social README "Chat" → "Limits"; the launcher's {@code chat/slow.ts}): a crew's
 * leader or one of its moderators lets each other member write at most one new message every few seconds in the
 * crew's chat; they never wait themselves. The composer counts the wait down from the player's newest message there
 * (sent, or on its way; one that did not go does not count). The hub decides anyway: this only shows the wait and
 * keeps the phone from sending what the hub would hold back. Pure, with tests ({@code ChatCalmTest}).
 */
public final class SlowMode {
    private SlowMode() {}

    /** The steps a leader picks from (seconds; 0 is off), as the service takes them (up to half a minute). */
    public static final int[] STEPS = {0, 5, 10, 30};

    /**
     * A message's hub time turned into the phone's clock is off by up to a second (the hub's {@code now} comes in whole
     * seconds): the wait after such a message is counted a second longer, so a send at its end is never held back.
     */
    public static final long CLOCK_MARGIN_MS = 1_000;

    /** A crew's {@code slow} (0 when absent or not a number of seconds). */
    static int read(@Nullable JsonObject team) {
        if (team == null || !team.has("slow") || !team.get("slow").isJsonPrimitive()
                || !team.get("slow").getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        double seconds = team.get("slow").getAsDouble();
        return seconds > 0 && seconds <= 3600 ? (int) Math.round(seconds) : 0;
    }

    /** The crew's slow mode for {@code me} (seconds): 0 for its leader and moderators, outside the crew, and when off. */
    public static int forPlayer(@Nullable SocialModel.Team team, @Nullable String me) {
        if (team == null || me == null || team.slow <= 0) return 0;
        SocialModel.Member member = team.member(me);
        if (member == null || member.founder || member.moderator) return 0;
        return team.slow;
    }

    /** The step a leader's control shows for {@code seconds}: one of {@link #STEPS}, else off. */
    public static int step(int seconds) {
        for (int s : STEPS) if (s == seconds) return s;
        return 0;
    }

    /** The index of {@code seconds} in {@link #STEPS} (0, off, for a value that is no step). */
    public static int index(int seconds) {
        for (int i = 0; i < STEPS.length; i++) if (STEPS[i] == seconds) return i;
        return 0;
    }

    /**
     * When the player may send again (the phone's ms), or 0: {@code slowSeconds} after their newest message in the
     * conversation. A message that has not gone yet (written offline, on its way, held back until a moment) counts from
     * when it can go at the earliest, {@code nowMs} or later, so nothing written meanwhile reaches the hub too soon; a
     * failed one not at all. A sent message (or one deleted since, which the hub still remembers) counts from its hub
     * time in the phone's clock ({@code hubOffsetMs}: the hub's clock minus the phone's) and {@link #CLOCK_MARGIN_MS},
     * never later than now; the one the hub took last from this phone ({@code anchorId}, 0: none) from when its answer
     * came ({@code anchorAtMs}) when that is earlier (a resend answered with a message taken long before is not), and an
     * answer without its message from when it came ({@code sentAtMs}, 0: none). Every one of these is a moment at or
     * after the hub took the message: the wait never ends early.
     */
    public static long until(List<ChatBook.Entry> entries, @Nullable String me, int slowSeconds, long hubOffsetMs,
                             long anchorId, long anchorAtMs, long sentAtMs, long nowMs) {
        if (slowSeconds <= 0 || me == null) return 0;
        long newest = sentAtMs > 0 ? Math.min(sentAtMs, nowMs) : Long.MIN_VALUE;
        for (ChatBook.Entry e : entries) {
            long at;
            if (e.outbox != null) {
                if (e.outbox.state == ChatBook.Outbox.State.FAILED) continue;
                long notBefore = e.outbox.notBefore;
                // Long.MAX_VALUE: the hub took it already (its message comes with the next sync).
                at = notBefore == Long.MAX_VALUE ? Math.min(e.outbox.createdAt, nowMs)
                        : Math.max(Math.max(e.outbox.createdAt, notBefore), nowMs);
            } else if (e.message != null) {
                if (!me.equals(e.message.from)) continue;
                at = hubToPhone(e.message.id, hubOffsetMs, anchorId, anchorAtMs, nowMs);
            } else if (e.tombstone != null) {
                if (!me.equals(e.tombstone.from)) continue;
                at = hubToPhone(e.tombstone.id, hubOffsetMs, anchorId, anchorAtMs, nowMs);
            } else {
                continue;
            }
            if (at > newest) newest = at;
        }
        return newest == Long.MIN_VALUE ? 0 : newest + slowSeconds * 1000L;
    }

    private static long hubToPhone(long hubMs, long hubOffsetMs, long anchorId, long anchorAtMs, long nowMs) {
        long converted = Math.min(hubMs - hubOffsetMs + CLOCK_MARGIN_MS, nowMs);
        if (anchorId != 0 && hubMs == anchorId) return Math.min(anchorAtMs, converted);
        return converted;
    }

    /** Whole seconds left, rounded up; 0 once the player may send. */
    public static int left(long untilMs, long nowMs) {
        if (untilMs <= nowMs) return 0;
        return (int) Math.min(Integer.MAX_VALUE, (untilMs - nowMs + 999) / 1000);
    }

    /** How long until the seconds shown change (ms; the last step lands on the end itself), or -1 when nothing waits. */
    public static long nextTick(long untilMs, long nowMs) {
        long rest = untilMs - nowMs;
        if (rest <= 0) return -1;
        long step = rest % 1000;
        return step == 0 ? 1000 : step;
    }
}
