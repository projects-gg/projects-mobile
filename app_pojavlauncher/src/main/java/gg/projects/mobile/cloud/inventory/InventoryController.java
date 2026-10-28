package gg.projects.mobile.cloud.inventory;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudService;

/**
 * One account's live inventory while a view shows it (CAFK-FEAT): it watches the account with the inventory, keeps the
 * newest frames (the player inventory, the window, whether it shows), sends moves through {@link InvQueue} and answers
 * what the view draws. Reusable: the inventory screen and any picker that lets the player point at an item (AFK+'s
 * "show me the item") drive the same controller; a picker simply never sends a move.
 */
public final class InventoryController implements CloudService.InventoryListener, CloudService.LiveListener {
    /** What the screen hears. Main thread. */
    public interface Host {
        /** Anything the view draws changed. */
        void changed();

        /** A line to show for a moment ({@link InvQueue#messageFor} keys). */
        void message(String key);

        /** What a screen reader hears after a move (a word key and the item's name). */
        void speak(String word, String item);

        /** The live connection's state (the console's notices). */
        void liveState(CloudService.Live state, boolean hostOnline);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final CloudService mService;
    private final String mAcct;
    private final Host mHost;
    private final String mLang;
    @Nullable
    private CloudService.LiveHandle mLive;
    private final InvQueue mQueue;

    @Nullable
    private InvFrames.Status mStatus;
    @Nullable
    private InvRules.State mTruth;
    private Map<Integer, InvItem> mWin = Collections.emptyMap();
    private long mWseq;
    @Nullable
    private String mWinType;
    private String mWinTitle = "";
    private int mWinSize;
    @Nullable
    private String mCursorOrigin;
    private CloudService.Live mLiveState = CloudService.Live.CONNECTING;
    private boolean mHostOnline = true;
    private boolean mStarted;

    public InventoryController(CloudService service, String acct, String lang, Host host) {
        mService = service;
        mAcct = acct;
        mLang = lang;
        mHost = host;
        mQueue = new InvQueue(new InvQueue.Hooks() {
            @Override
            public void send(InvOp op, InvQueue.Answer answer) {
                CloudService.LiveHandle live = mLive;
                if (live == null) {
                    MAIN.post(() -> answer.answered(InvFrames.Error.of("host_offline")));
                    return;
                }
                live.move(op, answer);
            }

            @Override
            public void changed() {
                mHost.changed();
            }

            @Override
            public void message(String key) {
                mHost.message(key);
            }

            @Override
            public void speak(String word, String item) {
                mHost.speak(word, item);
            }

            @Override
            public void origin(String key) {
                mCursorOrigin = key;
            }
        }, new InvQueue.Scheduler() {
            @Override
            public long now() {
                return SystemClock.uptimeMillis();
            }

            @Override
            public Object post(Runnable task, long delayMs) {
                MAIN.postDelayed(task, delayMs);
                return task;
            }

            @Override
            public void cancel(Object token) {
                MAIN.removeCallbacks((Runnable) token);
            }
        });
    }

    /** Starts watching (the view became visible). False when the account has no machine yet. */
    @MainThread
    public boolean start() {
        if (mStarted) return mLive != null;
        mStarted = true;
        mLive = mService.watch(mAcct, false, this, this);
        return mLive != null;
    }

    /** Stops watching (the view went): the inventory stream ends, moves on their way are forgotten. */
    @MainThread
    public void stop() {
        mStarted = false;
        mQueue.dispose();
        if (mLive != null) mLive.close();
        mLive = null;
    }

    // ---- what the view draws ----

    @Nullable
    public InvFrames.Status status() {
        return mStatus;
    }

    /** Whether frames have come since the inventory showed (before them the view waits). */
    public boolean ready() {
        return mStatus != null && (!mStatus.ok() || mTruth != null);
    }

    public CloudService.Live liveState() {
        return mLiveState;
    }

    public boolean hostOnline() {
        return mHostOnline;
    }

    public long wseq() {
        return mWseq;
    }

    @Nullable
    public String windowTitle() {
        return mWinType == null ? null : InvText.title(mWinTitle);
    }

    public InvLayout.Screen screen() {
        return InvLayout.screenFor(mWinType, mWinSize);
    }

    public Map<Integer, InvItem> window() {
        return mWin;
    }

    /** The player inventory as shown (moves on their way guessed in), with the slots that wait put in {@code waiting}. */
    public InvRules.State shown(Set<String> waiting) {
        InvRules.State base = mTruth == null ? InvRules.State.empty() : mTruth;
        return mQueue.shown(base, waiting);
    }

    /** The plan state for the action sheet and taps. */
    public InvPlan.State planState() {
        return new InvPlan.State(screen(), shown(new LinkedHashSet<String>()), mWin, mWseq, mCursorOrigin, mLang);
    }

    public boolean coolingDown() {
        return mQueue.coolingDown();
    }

    /** Sends a move (planned on the shown state); false when it waits out "too fast". */
    @MainThread
    public boolean run(@Nullable InvPlan.Step step) {
        return mQueue.run(step);
    }

    /** The account's features say moves are allowed (not looking only). */
    public boolean movesAllowed() {
        CloudModel.Account a = mService.account(mAcct);
        return a != null && a.features.inventoryMoves();
    }

    // ---- frames ----

    @Override
    public void invState(InvFrames.Status status) {
        mStatus = status;
        if (!status.ok()) {
            mTruth = null;
            mWin = Collections.emptyMap();
            mWseq = 0;
            mWinType = null;
        }
        mHost.changed();
    }

    @Override
    public void inventory(InvFrames.Inv frame) {
        mTruth = InvRules.State.of(frame);
        if (mTruth.cursor == null) mCursorOrigin = null;
        if (mStatus == null) mStatus = new InvFrames.Status("ok", null, null);
        mQueue.frame();
        mHost.changed();
    }

    @Override
    public void window(InvFrames.Win frame) {
        if (frame.closed) {
            if (frame.wseq == mWseq || mWseq == 0) {
                mWseq = 0;
                mWinType = null;
                mWinTitle = "";
                mWinSize = 0;
                mWin = Collections.emptyMap();
            }
        } else {
            mWseq = frame.wseq;
            mWinType = frame.type;
            mWinTitle = frame.title;
            mWinSize = frame.size;
            Map<Integer, InvItem> items = new HashMap<>();
            for (InvItem i : frame.items) items.put(i.slot, i);
            mWin = items;
        }
        mQueue.frame();
        mHost.changed();
    }

    // ---- the socket ----

    @Override
    public void liveState(CloudService.Live state) {
        mLiveState = state;
        if (state == CloudService.Live.LIVE) mHostOnline = true;
        mHost.liveState(state, mHostOnline);
    }

    @Override
    public void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}


    @Override
    public void approval(CloudModel.Approval approval) {}

    @Override
    public void hostOnline(boolean online) {
        mHostOnline = online;
        mHost.liveState(mLiveState, online);
    }
}
