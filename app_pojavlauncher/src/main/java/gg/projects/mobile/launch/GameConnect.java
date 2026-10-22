package gg.projects.mobile.launch;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import org.lwjgl.glfw.CallbackBridge;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FIX2 ("Bağlan" while the game runs, the owner's wish): the game closes the way its window's close
 * button closes it on a computer, so Minecraft leaves the server or saves the singleplayer world and
 * ends as it always does; the launcher then opens and starts into the server ({@link
 * ProjectsRelaunch#startConnect}). The player said yes before: in the launcher's question, or in the same
 * question over the game ({@link #EXTRA_CONNECT_ASK}, a notification's "Bağlan").
 *
 * <p>The game's process ends one of two ways, and both open the launcher: Minecraft's own {@code
 * System.exit} (Pojav hands it to Android's, whose shutdown hooks run) or its main method returning
 * ({@code JREUtils} ends the process after {@link #gameEnded}). A game still open after {@link #ASK_AFTER_MS}
 * gets the desktop's question ("Oyun kapanmadı": wait, force it closed, or let it close without coming
 * back); it is never stopped without the player's word. A game still starting (no window yet) has no world
 * open and stops at once.
 */
public final class GameConnect {
    private GameConnect() {}

    private static final String TAG = "ProjectsConnect";
    /** The launcher's question was answered: close now and connect ({@link ConnectTarget#wire()}). */
    public static final String EXTRA_CONNECT_NOW = "gg.projects.mobile.CONNECT_NOW";
    /** "Bağlan" from a notification while the game runs: the question first, over the game. */
    public static final String EXTRA_CONNECT_ASK = "gg.projects.mobile.CONNECT_ASK";
    /** The notification whose "Bağlan" this is (its tag and id): it goes once the question is on screen. */
    public static final String EXTRA_NOTIFICATION_TAG = "gg.projects.mobile.CONNECT_NOTIFICATION_TAG";
    public static final String EXTRA_NOTIFICATION_ID = "gg.projects.mobile.CONNECT_NOTIFICATION_ID";
    /**
     * Saving a singleplayer world takes a few seconds on a phone and leaving a server less; a game still
     * open after this may not answer any more, so the player is asked (the desktop waits as long).
     */
    static final long ASK_AFTER_MS = 20_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The game started in this process (a process started only by a connect request has none). */
    private static volatile boolean sGameStarted;
    /** Where the launcher starts once the game has closed; null: no connect asked (or the game failed). */
    @Nullable
    private static volatile ConnectTarget sPending;
    private static volatile long sRequestedAt;
    private static final AtomicBoolean sOpened = new AtomicBoolean();
    private static boolean sHooked;
    /** The game's activity the overdue question shows over. */
    private static WeakReference<Activity> sGame = new WeakReference<>(null);
    @Nullable
    private static Dialog sOverdue;

    /**
     * MainActivity starts the game in this process (any thread): from now on "Bağlan" closes it first, its
     * log says where it is ({@link gg.projects.mobile.social.GameWhere}) and, signed in to the friends
     * service, the player stays online for friends while it runs ({@link gg.projects.mobile.social.InGameSocial}).
     */
    public static void gameStarting(Context context) {
        sGameStarted = true;
        gg.projects.mobile.social.GameWhere.start();
        gg.projects.mobile.social.InGameSocial.start(context);
    }

    /**
     * MainActivity, first thing: a connect request that reached a process without a game (the game had
     * closed meanwhile) does not start one here. Asked in the launcher, the launcher starts into the server;
     * from a notification, the launcher just opens (the question is asked there, by its own "Bağlan").
     * This process ends. Returns true when the activity must stop here.
     */
    public static boolean divert(Activity activity) {
        if (sGameStarted) return false;
        Intent intent = activity.getIntent();
        if (intent == null) return false;
        ConnectTarget now = ConnectTarget.read(intent.getStringExtra(EXTRA_CONNECT_NOW));
        boolean ask = intent.getStringExtra(EXTRA_CONNECT_ASK) != null;
        if (now == null && !ask) return false;
        Log.i(TAG, "connect request without a game: to the launcher");
        try {
            if (now != null) {
                ProjectsRelaunch.startConnect(activity, now);
            } else {
                activity.startActivity(new Intent(activity, net.kdt.pojavlaunch.TestStorageActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "launcher not opened", e);
        }
        activity.finish();
        MAIN.postDelayed(Tools::fullyExit, 400);
        return true;
    }

    /** MainActivity (onCreate, onNewIntent): a connect request. True when the intent was one. */
    @MainThread
    public static boolean handle(FragmentActivity game, @Nullable Intent intent) {
        if (intent == null) return false;
        String now = intent.getStringExtra(EXTRA_CONNECT_NOW);
        String ask = intent.getStringExtra(EXTRA_CONNECT_ASK);
        String tag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG);
        int id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);
        if (now == null && ask == null) return false;
        intent.removeExtra(EXTRA_CONNECT_NOW);
        intent.removeExtra(EXTRA_CONNECT_ASK);
        intent.removeExtra(EXTRA_NOTIFICATION_TAG);
        intent.removeExtra(EXTRA_NOTIFICATION_ID);
        // A notification's action does not take the notification away by itself.
        if (tag != null && id != 0) NotificationManagerCompat.from(game).cancel(tag, id);
        ConnectTarget target = ConnectTarget.read(now != null ? now : ask);
        if (target == null || sPending != null) return true;
        if (isOn(target)) {
            // Already there: closing the game would only join the same server again.
            Toast.makeText(game, R.string.projects_connect_already, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (now != null) closeAndConnect(game, target);
        else gg.projects.mobile.social.ui.ConnectFlow.offer(game, target);
        return true;
    }

    /** The game is on {@code target}'s server now, as its log says. */
    public static boolean isOn(ConnectTarget target) {
        return gg.projects.mobile.social.GameWhere.isOn(target.projects, target.projects ? null : target.address);
    }

    /** Closes the game as its window's close would and comes back starting into {@code target}. */
    @MainThread
    public static void closeAndConnect(Activity game, ConnectTarget target) {
        if (sPending != null) {
            // Already closing: the newest answer is where it goes.
            sPending = target;
            return;
        }
        sPending = target;
        sOpened.set(false);
        sRequestedAt = SystemClock.elapsedRealtime();
        sGame = new WeakReference<>(game);
        final Context app = game.getApplicationContext();
        hookOnce(app);
        boolean asked;
        try {
            asked = CallbackBridge.nativeRequestClose();
        } catch (UnsatisfiedLinkError e) {
            asked = false;
        }
        Log.i(TAG, asked ? "close asked of the game" : "no game window yet: stopping");
        if (!asked) {
            // Still starting (no window, no world): nothing to save, so it stops now.
            open(app, "stopped while starting");
            MAIN.postDelayed(Tools::fullyExit, 400);
            return;
        }
        Toast.makeText(game, game.getString(R.string.projects_connect_closing, target.shown()), Toast.LENGTH_LONG).show();
        MAIN.postDelayed(() -> overdue(app), ASK_AFTER_MS);
    }

    /** "Oyun kapanmadı" (the desktop's): keep waiting, force it closed, or let it close without coming back. */
    @MainThread
    private static void overdue(final Context app) {
        if (sOpened.get() || sPending == null) return;
        Activity game = sGame.get();
        if (game == null || game.isFinishing() || game.isDestroyed() || (sOverdue != null && sOverdue.isShowing())) {
            MAIN.postDelayed(() -> overdue(app), ASK_AFTER_MS);
            return;
        }
        Log.w(TAG, "the game has not closed " + (SystemClock.elapsedRealtime() - sRequestedAt) + " ms after the request: asking");
        sOverdue = new AlertDialog.Builder(game)
                .setTitle(R.string.projects_connect_overdue_title)
                .setMessage(R.string.projects_connect_overdue_body)
                .setCancelable(false)
                .setPositiveButton(R.string.projects_connect_force, (d, w) -> {
                    open(app, "forced by the player");
                    MAIN.postDelayed(Tools::fullyExit, 400);
                })
                .setNeutralButton(R.string.projects_connect_wait, (d, w) -> MAIN.postDelayed(() -> overdue(app), ASK_AFTER_MS))
                // The close already asked goes on; only the start after it is dropped.
                .setNegativeButton(R.string.projects_social_cancel, (d, w) -> sPending = null)
                .show();
    }

    /** JREUtils: the game's main method returned and the process ends next. */
    public static void gameEnded(Context context, int exitCode) {
        if (exitCode != 0) {
            // The exit screen says what happened (a crash while closing); no start over it.
            sPending = null;
            return;
        }
        open(context.getApplicationContext(), "main returned");
    }

    /** The exit screen opens for a game that failed: it is not followed by a start. */
    public static void gameFailed() {
        sPending = null;
    }

    private static synchronized void hookOnce(final Context app) {
        if (sHooked) return;
        sHooked = true;
        try {
            // Minecraft ends with System.exit: Pojav calls Android's, which runs these hooks.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> open(app, "exit"), "ProjectsConnect"));
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "no exit hook", e);
        }
    }

    /** Opens the launcher starting into the waiting target (once). */
    private static void open(Context app, String how) {
        ConnectTarget target = sPending;
        if (target == null || !sOpened.compareAndSet(false, true)) return;
        Log.i(TAG, "game closed (" + how + ") " + (SystemClock.elapsedRealtime() - sRequestedAt) + " ms after the request");
        try {
            ProjectsRelaunch.startConnect(app, target);
        } catch (RuntimeException e) {
            Log.w(TAG, "launcher not opened", e);
        }
    }
}
