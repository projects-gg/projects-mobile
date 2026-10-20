package gg.projects.mobile.social;

import java.util.Random;

/**
 * Reconnect delays for the presence socket (services/social README "What clients do on errors";
 * the desktop's backoff.rs): 2 s doubling to 5 minutes, each delay drawn from {@code [d/2, d]}; the
 * first reconnect after a healthy connection is spread over 0–5 s, so a service restart does not
 * bring every phone back at the same moment. Never a tight loop.
 */
public final class Backoff {
    public static final long MIN_MS = 2_000;
    public static final long MAX_MS = 5 * 60_000;
    public static final long FIRST_SPREAD_MS = 5_000;

    private final Random mRandom;
    private long mNext = MIN_MS;
    private boolean mHealthy;

    public Backoff(Random random) {
        mRandom = random;
    }

    /** A connection worked (it said hello): the next failure starts small again. */
    public void healthy() {
        mNext = MIN_MS;
        mHealthy = true;
    }

    /** The delay before the next attempt, after a failure or a lost connection. */
    public long nextDelayMs() {
        if (mHealthy) {
            mHealthy = false;
            return (long) (mRandom.nextDouble() * FIRST_SPREAD_MS);
        }
        long d = mNext;
        mNext = Math.min(MAX_MS, mNext * 2);
        return d / 2 + (long) (mRandom.nextDouble() * (d - d / 2));
    }

    /** At least {@code seconds} (the service asked to wait), then backoff as usual. */
    public long atLeastMs(long seconds) {
        long d = nextDelayMs();
        return Math.max(d, Math.min(3600_000L, seconds * 1000L));
    }

    public void reset() {
        mNext = MIN_MS;
        mHealthy = false;
    }
}
