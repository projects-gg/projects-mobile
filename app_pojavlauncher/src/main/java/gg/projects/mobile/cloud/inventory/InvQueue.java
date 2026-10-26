package gg.projects.mobile.cloud.inventory;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves on their way (the launcher's controller.ts). Every move is sent at once and shown waiting until the machine
 * answers and a frame shows what the server made of it; a refusal puts the view back and says one short line. Nothing
 * is ever sent again by itself: after "too fast" the queue waits out the machine's time and says so when the player taps
 * meanwhile.
 */
public final class InvQueue {
    /** No frame this long after an {@code ok}: the server changed nothing. */
    public static final long SETTLE_MS = 1600;
    /** No answer this long (the machine's own limit is 3 s): the account is out of reach. */
    public static final long ANSWER_MS = 5000;

    public interface Scheduler {
        long now();

        Object post(Runnable task, long delayMs);

        void cancel(Object token);
    }

    public interface Answer {
        /** {@code error} null: {@code ok}. */
        void answered(@Nullable InvFrames.Error error);
    }

    public interface Hooks {
        /** Sends the move; {@code answer} is called once (on the same thread as the queue's other calls). */
        void send(InvOp op, Answer answer);

        void changed();

        /** A line to show: {@code slowDown}, {@code refused}, {@code tradeLocked}, {@code viewOnly}, {@code notRunning}, {@code unreachable}, {@code off}. */
        void message(String key);

        void speak(String word, String item);

        /** Where the cursor's item came from (a pickup into an empty cursor). */
        void origin(String key);
    }

    /** The line an error brings; null for {@code stale} and {@code invalid} (the view simply redraws). */
    @Nullable
    public static String messageFor(InvFrames.Error error) {
        switch (error.code) {
            case "rate_limited": return "slowDown";
            case "busy_trade": return "tradeLocked";
            case "not_running": return "notRunning";
            case "inventory_off": return "view".equals(error.reason) ? "viewOnly" : "off";
            case "host_offline":
            case "not_found":
                return "unreachable";
            default:
                return null;
        }
    }

    private static final class Pending {
        final InvPlan.Step step;
        boolean ok;
        boolean framed;
        boolean answered;
        @Nullable
        Object timer;

        Pending(InvPlan.Step step) {
            this.step = step;
        }
    }

    private final Hooks mHooks;
    private final Scheduler mScheduler;
    private final List<Pending> mPending = new ArrayList<>();
    private long mCoolUntil;
    private boolean mDisposed;

    public InvQueue(Hooks hooks, Scheduler scheduler) {
        mHooks = hooks;
        mScheduler = scheduler;
    }

    public boolean coolingDown() {
        return mScheduler.now() < mCoolUntil;
    }

    public boolean idle() {
        return mPending.isEmpty();
    }

    /** The player inventory with the guesses of moves not yet framed, and the slots that wait. */
    public InvRules.State shown(InvRules.State base, Set<String> waitingKeys) {
        InvRules.State state = base;
        for (Pending p : mPending) {
            waitingKeys.addAll(p.step.keys);
            if (p.step.predict != null && !p.framed) state = p.step.predict.apply(state);
        }
        return state;
    }

    public Set<String> waiting() {
        Set<String> keys = new LinkedHashSet<>();
        for (Pending p : mPending) keys.addAll(p.step.keys);
        return keys;
    }

    /** Sends a move. Returns false when it waits out "too fast" instead (and says so). */
    public boolean run(@Nullable InvPlan.Step step) {
        if (step == null || mDisposed) return true;
        if (coolingDown()) {
            mHooks.message("slowDown");
            return false;
        }
        final Pending entry = new Pending(step);
        mPending.add(entry);
        if (step.origin != null) mHooks.origin(step.origin);
        mHooks.changed();
        entry.timer = mScheduler.post(() -> {
            if (entry.answered) return;
            entry.answered = true;
            answer(entry, InvFrames.Error.of("host_offline"));
        }, ANSWER_MS);
        mHooks.send(step.op, error -> {
            if (entry.answered) return;
            entry.answered = true;
            answer(entry, error);
        });
        return true;
    }

    /** A new frame arrived (inventory or window). */
    public void frame() {
        boolean changed = false;
        for (Pending p : new ArrayList<>(mPending)) {
            if (!p.framed) {
                p.framed = true;
                changed = true;
            }
            if (p.ok) settle(p, true);
        }
        if (changed) mHooks.changed();
    }

    /** The view is gone: timers stop, moves on their way are forgotten. */
    public void dispose() {
        mDisposed = true;
        for (Pending p : mPending) if (p.timer != null) mScheduler.cancel(p.timer);
        mPending.clear();
    }

    private void answer(Pending entry, @Nullable InvFrames.Error error) {
        if (mDisposed || !mPending.contains(entry)) return;
        if (entry.timer != null) mScheduler.cancel(entry.timer);
        entry.timer = null;
        if (error == null) {
            entry.ok = true;
            if (entry.framed) settle(entry, true);
            else entry.timer = mScheduler.post(() -> settle(entry, false), SETTLE_MS);
            return;
        }
        if ("rate_limited".equals(error.code)) mCoolUntil = mScheduler.now() + Math.max(1, error.retryAfter) * 1000L;
        remove(entry);
        String message = messageFor(error);
        if (message != null) mHooks.message(message);
    }

    /** The move is over: a frame came after it ({@code seen}), or none came in time after its {@code ok}. */
    private void settle(Pending entry, boolean seen) {
        if (!mPending.contains(entry)) return;
        remove(entry);
        InvPlan.Step step = entry.step;
        if (!seen && step.mustChange) {
            mHooks.message("refused");
            return;
        }
        if (seen && step.speech != null && step.speechItem != null) mHooks.speak(step.speech, step.speechItem);
    }

    private void remove(Pending entry) {
        if (entry.timer != null) mScheduler.cancel(entry.timer);
        entry.timer = null;
        mPending.remove(entry);
        mHooks.changed();
    }
}
