package gg.projects.mobile.social;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Logger;

import gg.projects.mobile.launch.ProjectsLaunchHook;

/**
 * FIX2: where the running game is, from its log, in the game's process ({@link GameActivity} reads the
 * lines; the desktop does the same). The logger's thread feeds it and is the only one that touches the
 * reader; the status can be read from anywhere, and a change reaches the listener (the game's presence
 * socket) on the main thread. Every log line costs one {@code contains} test here.
 */
public final class GameWhere {
    private GameWhere() {}

    public interface Listener {
        @MainThread
        void onWhere(GameActivity.Status status);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Logger.eventLogListener LOG = GameWhere::onLog;
    /** The logger thread's own. */
    @Nullable
    private static GameActivity sGame;
    private static volatile GameActivity.Status sStatus = GameActivity.Status.PLAYING;
    @Nullable
    private static Listener sListener;
    private static boolean sStarted;

    /** The game starts in this process: its log is followed from now on. */
    public static synchronized void start() {
        if (sStarted) return;
        sStarted = true;
        Logger.addLogListener(LOG);
    }

    public static GameActivity.Status status() {
        return sStatus;
    }

    /** The game is on that server now (Projects by any of its hosts; another one by its address). */
    public static boolean isOn(boolean projects, @Nullable String address) {
        return InGameRules.isOn(sStatus, projects, address);
    }

    @MainThread
    public static void setListener(@Nullable Listener listener) {
        sListener = listener;
    }

    private static void onLog(String piece) {
        for (String line : GameActivity.linesThatMatter(piece)) {
            GameActivity game = sGame;
            // Made at the first line that matters: by then the start's own join is known.
            if (game == null) sGame = game = GameActivity.started(ProjectsLaunchHook.joinTarget());
            final GameActivity.Status next = game.observe(line);
            if (next == null) continue;
            sStatus = next;
            MAIN.post(() -> {
                Listener listener = sListener;
                if (listener != null) listener.onWhere(next);
            });
        }
    }
}
