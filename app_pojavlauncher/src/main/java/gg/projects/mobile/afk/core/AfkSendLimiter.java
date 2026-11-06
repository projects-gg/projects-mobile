package gg.projects.mobile.afk.core;

import java.util.ArrayDeque;

import gg.projects.mobile.afk.api.AfkLimits;

/**
 * The player's own lines per account: at most {@link AfkLimits#SEND_BURST} in {@link AfkLimits#SEND_BURST_WINDOW_MS}
 * and {@link AfkLimits#SEND_PER_MINUTE} a minute (servers kick for spam; no bot swarms from phones). Pure Java.
 */
public final class AfkSendLimiter {
    private static final long MINUTE_MS = 60_000L;
    private final ArrayDeque<Long> sent = new ArrayDeque<>();

    /** Takes a place for a line sent at {@code now}; false when it is too soon. */
    public boolean tryTake(long now) {
        while (!sent.isEmpty() && now - sent.peekFirst() >= MINUTE_MS) sent.pollFirst();
        if (sent.size() >= AfkLimits.SEND_PER_MINUTE) return false;
        int recent = 0;
        for (Long at : sent) {
            if (now - at < AfkLimits.SEND_BURST_WINDOW_MS) recent++;
        }
        if (recent >= AfkLimits.SEND_BURST) return false;
        sent.addLast(now);
        return true;
    }
}
