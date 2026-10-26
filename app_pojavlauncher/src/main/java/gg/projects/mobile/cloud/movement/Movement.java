package gg.projects.mobile.cloud.movement;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudService;

/**
 * Movement of a cloud account (CAFK-FEAT §3.7, phase 3): step by step in four directions, jump, sneak, go to coordinates
 * (at most 256 blocks), go to or follow the player's own main account, stop. Its service contract (services/cloud-afk
 * README "Movement") is still being written; until it is, the panel runs against {@link Mock} in debug builds that ask
 * for it ({@code projects-debug.json} {@code cloud.movementMock}) and is never shown in a release.
 */
public final class Movement {
    private Movement() {}

    /** Whether the account shows "Hareket": the service's movement for it, or the debug mock. */
    public static boolean shows(CloudService service, CloudModel.Account account) {
        return service.movementMock() && account.host != null;
    }

    /** Where the account stands: block coordinates and its dimension ({@code overworld}, {@code nether}, {@code end}). */
    public static final class Position {
        public final int x;
        public final int y;
        public final int z;
        public final String dimension;

        public Position(int x, int y, int z, String dimension) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
        }
    }

    public enum Activity { IDLE, WALKING, FOLLOWING }

    public interface Listener {
        @MainThread
        void changed();

        /** A refusal: {@code far} (more than 256 blocks), {@code not_near} (the main account is not in sight), {@code no_slot}. */
        @MainThread
        void refused(String code);
    }

    /** What the panel drives. Every call on the main thread. */
    public interface Backend {
        void attach(Listener listener);

        void detach();

        boolean enabled();

        void setEnabled(boolean on);

        @Nullable
        Position position();

        Activity activity();

        /** The walk's target ({@link Activity#WALKING}) or null. */
        @Nullable
        int[] target();

        /** The followed name ({@link Activity#FOLLOWING}) or null. */
        @Nullable
        String following();

        boolean sneaking();

        /** One block: {@code forward}, {@code back}, {@code left} or {@code right}. */
        void step(String direction);

        void jump();

        void sneak(boolean on);

        void goTo(int x, int y, int z);

        void goToMain(String owner);

        void followMain(String owner);

        void halt();
    }

    /** The farthest a walk may go (the contract's limit). */
    public static final int MAX_DISTANCE = 256;

    public static boolean withinReach(Position from, int x, int y, int z) {
        long dx = x - from.x;
        long dy = y - from.y;
        long dz = z - from.z;
        return dx * dx + dy * dy + dz * dz <= (long) MAX_DISTANCE * MAX_DISTANCE;
    }

    private static final Map<String, Mock> MOCKS = new HashMap<>();

    /** The backend for an account: the mock until the service carries movement. */
    public static Backend backend(String acct) {
        Mock mock = MOCKS.get(acct);
        if (mock == null) {
            mock = new Mock();
            MOCKS.put(acct, mock);
        }
        return mock;
    }

    /**
     * A stand-in machine for the panel (debug builds only): it walks a block a step, jumps, sneaks, walks to a spot a
     * block every 250 ms, follows a made-up main account and refuses what the contract refuses.
     */
    static final class Mock implements Backend {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        @Nullable
        private Listener mListener;
        private boolean mEnabled;
        private Position mPos = new Position(120, 64, -340, "overworld");
        private Activity mActivity = Activity.IDLE;
        @Nullable
        private int[] mTarget;
        @Nullable
        private String mFollowing;
        private boolean mSneaking;
        private final Runnable mWalk = this::walk;

        @Override
        public void attach(Listener listener) {
            mListener = listener;
        }

        @Override
        public void detach() {
            mListener = null;
        }

        private void changed() {
            if (mListener != null) mListener.changed();
        }

        @Override
        public boolean enabled() {
            return mEnabled;
        }

        @Override
        public void setEnabled(boolean on) {
            mEnabled = on;
            if (!on) halt();
            changed();
        }

        @Nullable
        @Override
        public Position position() {
            return mEnabled ? mPos : null;
        }

        @Override
        public Activity activity() {
            return mActivity;
        }

        @Nullable
        @Override
        public int[] target() {
            return mTarget;
        }

        @Nullable
        @Override
        public String following() {
            return mFollowing;
        }

        @Override
        public boolean sneaking() {
            return mSneaking;
        }

        @Override
        public void step(String direction) {
            int dx = "left".equals(direction) ? -1 : "right".equals(direction) ? 1 : 0;
            int dz = "forward".equals(direction) ? -1 : "back".equals(direction) ? 1 : 0;
            mPos = new Position(mPos.x + dx, mPos.y, mPos.z + dz, mPos.dimension);
            changed();
        }

        @Override
        public void jump() {
            mPos = new Position(mPos.x, mPos.y + 1, mPos.z, mPos.dimension);
            changed();
            MAIN.postDelayed(() -> {
                mPos = new Position(mPos.x, mPos.y - 1, mPos.z, mPos.dimension);
                changed();
            }, 420);
        }

        @Override
        public void sneak(boolean on) {
            mSneaking = on;
            changed();
        }

        @Override
        public void goTo(int x, int y, int z) {
            if (!withinReach(mPos, x, y, z)) {
                if (mListener != null) mListener.refused("far");
                return;
            }
            mFollowing = null;
            mTarget = new int[]{x, y, z};
            mActivity = Activity.WALKING;
            MAIN.removeCallbacks(mWalk);
            MAIN.postDelayed(mWalk, 250);
            changed();
        }

        private void walk() {
            int[] t = mTarget;
            if (t == null || mActivity != Activity.WALKING) return;
            int x = mPos.x + Integer.signum(t[0] - mPos.x);
            int y = mPos.y + Integer.signum(t[1] - mPos.y);
            int z = mPos.z + Integer.signum(t[2] - mPos.z);
            mPos = new Position(x, y, z, mPos.dimension);
            if (x == t[0] && y == t[1] && z == t[2]) {
                mActivity = Activity.IDLE;
                mTarget = null;
            } else {
                MAIN.postDelayed(mWalk, 250);
            }
            changed();
        }

        @Override
        public void goToMain(String owner) {
            if (owner.toLowerCase(java.util.Locale.ROOT).contains("uzak")) {
                if (mListener != null) mListener.refused("not_near");
                return;
            }
            goTo(mPos.x + 6, mPos.y, mPos.z - 4);
        }

        @Override
        public void followMain(String owner) {
            MAIN.removeCallbacks(mWalk);
            mTarget = null;
            mFollowing = owner;
            mActivity = Activity.FOLLOWING;
            changed();
        }

        @Override
        public void halt() {
            MAIN.removeCallbacks(mWalk);
            mTarget = null;
            mFollowing = null;
            mActivity = Activity.IDLE;
            changed();
        }
    }
}
