package gg.projects.mobile.accounts;

/**
 * The account chip's swipe (SWIPE-M; the owner, 1 November 2026: quick account switches on the phone). Plain Java: pixels
 * and milliseconds in, decisions out, JVM-tested ({@code ChipSwipeTest}); {@code mcAccountSpinner} feeds it the chip's
 * touches and draws what it decides.
 *
 * <ul>
 * <li><b>A swipe</b>: the finger passes the touch slop sideways first (more across than down) before the hold time. From
 * then on the chip owns the touch: its press and the list's hold are cancelled.</li>
 * <li><b>A tap</b> ends within the slop, <b>a hold</b> stays within it past the hold time, <b>a vertical drag</b> passes the
 * slop downwards (or upwards) first: all three stay the spinner's, which opens the list (a tap, the hold's drag-to-open,
 * dragging down into the list).</li>
 * <li>On release a swipe switches when the finger went {@link #COMMIT_DP}, or when it was flicked at
 * {@link #FLING_DP_PER_S} the way it went; a flick back cancels it. Left is the next account, right the previous one
 * (mirrored right to left).</li>
 * </ul>
 *
 * <p>The motion: while the finger moves, the head and name follow it ({@link #followOf}); a switch slides them on and out
 * ({@link #exitShift}, {@link #outMs}) and brings the next account in from the other side ({@link #enterShift},
 * {@link #IN_MS}); a cancelled swipe settles back ({@link #SETTLE_MS}).
 */
public final class ChipSwipe {
    /** How far the finger goes for a switch. */
    public static final float COMMIT_DP = 24f;
    /** A flick at least this fast switches however short it was (dp per second). */
    public static final float FLING_DP_PER_S = 300f;
    /** How far the head and name slide when they leave and when the next ones come in. */
    public static final float SHIFT_DP = 12f;
    /** The head and name leaving (from rest; shorter when the finger already took them part of the way). */
    public static final long OUT_MS = 120;
    /** The next head and name coming in, and the chip's pill gliding to the new width. */
    public static final long IN_MS = 160;
    /** The head and name going back to rest after a swipe that did not switch. */
    public static final long SETTLE_MS = 200;
    /** The hold is decided this long before the list's own hold opens it, so a swipe never starts in an open list. */
    public static final long HOLD_LEAD_MS = 40;

    /** The one-time line under the chip: shown this long after the home screen appears... */
    public static final long HINT_DELAY_MS = 600;
    /** ...for at most this long at a time... */
    public static final long HINT_SHOW_MS = 6000;
    /** ...and never again once it was on screen this long in all (or the chip was touched). */
    public static final long HINT_SEEN_MS = 3000;

    /** A swipe is more across than down by this much. */
    static final float ACROSS = 1.2f;
    /** Past the commit distance the head and name move this part of the finger's way... */
    static final float RUBBER = 0.2f;
    /** ...up to this many times {@link #SHIFT_DP}. */
    static final float FOLLOW_MAX = 1.6f;
    /** How much the head and name fade while they follow the finger (at a full shift). */
    static final float FOLLOW_FADE = 0.4f;
    /** The window the release speed is measured over. */
    static final long VELOCITY_WINDOW_MS = 100;

    /** What a touch is so far. */
    public enum Touch {
        /** Within the slop, before the hold time: a tap, a hold or a swipe still. */
        UNDECIDED,
        /** A sideways swipe: the chip's. */
        SWIPE,
        /** A tap, a hold or a vertical drag: the spinner's. */
        OTHER
    }

    /** What a released swipe does. */
    public enum Result {
        /** Not a swipe. */
        NONE,
        NEXT,
        PREVIOUS,
        /** A swipe that does not switch: the head and name go back. */
        BACK
    }

    private static final int SAMPLES = 24;

    private final float mDensity;
    private final float mSlop;
    private final long mHoldMs;
    private Touch mTouch = Touch.OTHER;
    private boolean mRtl;
    private float mDownX;
    private float mDownY;
    private float mX;
    private float mY;
    private long mDownT;
    private final float[] mSampleX = new float[SAMPLES];
    private final long[] mSampleT = new long[SAMPLES];
    private int mSamples;
    private int mNext;

    /**
     * @param density     pixels per dp
     * @param touchSlopPx the platform's touch slop in pixels
     * @param holdMs      when a touch that has not become a swipe is a hold ({@link #holdFor})
     */
    public ChipSwipe(float density, float touchSlopPx, long holdMs) {
        mDensity = density;
        mSlop = touchSlopPx;
        mHoldMs = holdMs;
    }

    /** The hold time for a list whose own hold opens it after {@code listHoldMs}. */
    public static long holdFor(long listHoldMs) {
        return Math.max(60, listHoldMs - HOLD_LEAD_MS);
    }

    public void down(float x, float y, long t, boolean rtl) {
        mTouch = Touch.UNDECIDED;
        mRtl = rtl;
        mDownX = x;
        mDownY = y;
        mX = x;
        mY = y;
        mDownT = t;
        mSamples = 0;
        mNext = 0;
        sample(x, t);
    }

    /** The finger moved; returns what the touch is now ({@link Touch#SWIPE} from the move that decided it). */
    public Touch move(float x, float y, long t) {
        if (mTouch == Touch.OTHER) return mTouch;
        sample(x, t);
        mX = x;
        mY = y;
        if (mTouch == Touch.SWIPE) return mTouch;
        if (t - mDownT >= mHoldMs) {
            mTouch = Touch.OTHER;
            return mTouch;
        }
        float across = Math.abs(x - mDownX);
        float down = Math.abs(y - mDownY);
        if (across > mSlop && across > down * ACROSS) {
            mTouch = Touch.SWIPE;
        } else if (down > mSlop && down >= across) {
            mTouch = Touch.OTHER;
        }
        return mTouch;
    }

    /**
     * Decides a touch that is still undecided now: the finger is about to leave the chip, where the spinner's list would
     * open by dragging out of it. Sideways wins when the finger went past the slop more across than down.
     */
    public Touch decideNow() {
        if (mTouch != Touch.UNDECIDED) return mTouch;
        float across = Math.abs(mX - mDownX);
        float down = Math.abs(mY - mDownY);
        mTouch = across > mSlop && across > down ? Touch.SWIPE : Touch.OTHER;
        return mTouch;
    }

    /** The finger lifted: what the swipe does, or {@link Result#NONE} when the touch was not a swipe. */
    public Result up(float x, float y, long t) {
        if (mTouch != Touch.SWIPE) {
            mTouch = Touch.OTHER;
            return Result.NONE;
        }
        sample(x, t);
        mX = x;
        mTouch = Touch.OTHER;
        return decide(x - mDownX, velocity());
    }

    /** The touch was taken away (a cancel, a second finger's chip taken by the system). */
    public void cancel() {
        mTouch = Touch.OTHER;
    }

    public Touch touch() {
        return mTouch;
    }

    /** The finger's sideways travel since it went down, in pixels (negative to the left). */
    public float travel() {
        return mX - mDownX;
    }

    /** How far the head and name follow the finger now ({@link #followOf} of {@link #travel}). */
    public float follow() {
        return followOf(mX - mDownX);
    }

    /**
     * How far the head and name follow a finger {@code dx} pixels away from where it went down: nothing within the slop
     * (no jump when the swipe is recognised), then three quarters of the way, so they are a full {@link #SHIFT_DP} out at
     * {@link #COMMIT_DP}; past that a fifth, up to {@link #FOLLOW_MAX} shifts (a rubber band, not a wall).
     */
    public float followOf(float dx) {
        float shift = SHIFT_DP * mDensity;
        float range = Math.max(1f, COMMIT_DP * mDensity - mSlop);
        float past = Math.max(0f, Math.abs(dx) - mSlop);
        float moved = past <= range ? past * shift / range : shift + (past - range) * RUBBER;
        moved = Math.min(moved, shift * FOLLOW_MAX);
        return dx < 0 ? -moved : moved;
    }

    Result decide(float dx, float pxPerMs) {
        float fling = FLING_DP_PER_S / 1000f * mDensity;
        boolean flung = Math.abs(pxPerMs) >= fling;
        if (dx == 0f || (flung && (pxPerMs < 0) != (dx < 0))) return Result.BACK;
        if (flung || Math.abs(dx) >= COMMIT_DP * mDensity) {
            return (dx < 0) != mRtl ? Result.NEXT : Result.PREVIOUS;
        }
        return Result.BACK;
    }

    private void sample(float x, long t) {
        mSampleX[mNext] = x;
        mSampleT[mNext] = t;
        mNext = (mNext + 1) % SAMPLES;
        if (mSamples < SAMPLES) mSamples++;
    }

    /** The finger's sideways speed in pixels per millisecond over the last {@link #VELOCITY_WINDOW_MS}. */
    float velocity() {
        if (mSamples < 2) return 0f;
        int newest = (mNext - 1 + SAMPLES) % SAMPLES;
        int oldest = newest;
        for (int i = 1; i < mSamples; i++) {
            int at = (newest - i + SAMPLES) % SAMPLES;
            if (mSampleT[newest] - mSampleT[at] > VELOCITY_WINDOW_MS) break;
            oldest = at;
        }
        // Nothing else inside the window: the finger had stopped, so the last step (long ago) gives a speed near zero.
        if (oldest == newest) oldest = (newest - 1 + SAMPLES) % SAMPLES;
        long dt = mSampleT[newest] - mSampleT[oldest];
        return dt <= 0 ? 0f : (mSampleX[newest] - mSampleX[oldest]) / dt;
    }

    // ---- the motion's numbers ----

    /** The head and name's opacity {@code shiftPx} away from rest: down to 60 % at a full shift. */
    public static float followAlpha(float shiftPx, float density) {
        float shift = SHIFT_DP * density;
        return 1f - FOLLOW_FADE * Math.min(1f, Math.abs(shiftPx) / shift);
    }

    /**
     * Where the head and name stop when they leave towards {@code sign} (-1 left, 1 right) from {@code shiftNowPx}: a full
     * shift from rest, half a shift further than where the finger took them, never back the way they came.
     */
    public static float exitShift(float shiftNowPx, int sign, float density) {
        float shift = SHIFT_DP * density;
        float along = sign * shiftNowPx;
        return sign * Math.max(shift, along + shift / 2f);
    }

    /** How long the head and name take to leave from opacity {@code alphaNow}: {@link #OUT_MS} from rest, at least half. */
    public static long outMs(float alphaNow) {
        return Math.round(OUT_MS * Math.max(0.5f, Math.min(1f, alphaNow)));
    }

    /** Where the next head and name start: a shift on the other side from where the last ones left to. */
    public static float enterShift(int sign, float density) {
        return -sign * SHIFT_DP * density;
    }

    /** The way the head and name move for a result: the next account's leave towards the start edge (left; right in RTL). */
    public static int sign(Result result, boolean rtl) {
        int toStart = rtl ? 1 : -1;
        if (result == Result.NEXT) return toStart;
        if (result == Result.PREVIOUS) return -toStart;
        return 0;
    }

    /** The step in the account order for a result: 1 for the next account, -1 for the previous one, else 0. */
    public static int step(Result result) {
        if (result == Result.NEXT) return 1;
        if (result == Result.PREVIOUS) return -1;
        return 0;
    }

    /** The one-time line under the chip is due: two or more accounts, never done, on screen less than 3 s in all. */
    public static boolean hintDue(int accounts, boolean done, long seenMs) {
        return accounts >= 2 && !done && seenMs < HINT_SEEN_MS;
    }
}
