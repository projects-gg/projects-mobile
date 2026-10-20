package gg.projects.mobile.join;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import java.lang.ref.WeakReference;

/**
 * M17: where the outcome of a Projects start goes (the lead's decision on M16's open flow, "Known
 * gaps"). A start that ends while the player is on the home screen goes on as before: the game
 * starts, or the error shows there. One that ends while the player is anywhere else (another screen
 * of the app, another app, the phone locked) never starts the game by itself: a notification says
 * "Projects hazır" (a tap opens the app, where Play is ready and the profile chosen) or that it
 * failed, and the home screen shows the failure when the player comes back. Nothing ends silently:
 * without notifications (Android 13+ asks for them; since M18 the app asks once, at the player's first
 * Projects start) the home screen still shows what happened.
 *
 * <p>The home screen registers itself while it is resumed ({@link #homeResumed}). The install keeps
 * running in the background either way: upstream's progress service holds the process in the
 * foreground with a progress notification ({@code ProgressService}). The failure is kept in the
 * app's preferences, so it is shown even when Android ended the process meanwhile; the "ready" mark
 * too, for half a day.
 */
public final class JoinOutcome {
    private JoinOutcome() {}

    private static final String TAG = "ProjectsJoinOutcome";
    /** Notification ids, apart from upstream's 1 to 5 and the reminders' 0x5xxxxxxx. */
    static final int NOTIFY_READY = 0x4A170001;
    static final int NOTIFY_FAILED = 0x4A170002;
    /**
     * M18: "Projects hazır" and its failure have their own channel, at high importance: the player left
     * the app on purpose and waits for this one, so it pops up over another app (upstream's one channel,
     * shared with the silent progress notification, only lists it in the shade).
     */
    static final String CHANNEL_ID = "projects_ready";
    private static final String PREFS = "projects_join_outcome";
    private static final String KEY_READY_AT = "readyAt";
    private static final String KEY_FAILED_AT = "failedAt";
    private static final String KEY_FAILED_NETWORK = "failedNetwork";
    private static final String KEY_FAILED_FILES = "failedFiles";
    private static final String KEY_FAILED_DETAILS = "failedDetails";
    /** A "ready" older than this is no news any more. */
    private static final long READY_KEPT_MS = 12L * 60 * 60 * 1000;
    private static final int MAX_DETAILS_CHARS = 16 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** What the home screen does with an outcome. */
    public interface Home {
        /** The start goes on: the game opens now. */
        void launch(String profileKey, @Nullable SyncNotice notice);

        /** The start failed: the error, here and now. */
        void showFailure(Failure failure);

        /** Forge or NeoForge needs upstream's installer first. */
        void showFinishLoader();

        /** It finished while the player was away: Play is ready (the profile chosen, a short cue). */
        void showReady(@Nullable SyncNotice notice);
    }

    /** Why a start failed. */
    public static final class Failure {
        /** No connection (else something else went wrong). */
        public final boolean network;
        /** The game files' check after the pack (else the pack itself). */
        public final boolean files;
        /** For "Ayrıntıları kopyala". */
        public final String details;

        public Failure(boolean network, boolean files, String details) {
            this.network = network;
            this.files = files;
            this.details = details == null ? "" : details;
        }
    }

    /**
     * M18: runs the file check of a start whose pack finished away from the home screen (the launcher
     * activity, also while it is in the background). Returns true when the check started.
     */
    public interface FilesStarter {
        boolean startFiles(String profileKey);
    }

    private static WeakReference<Home> sHome = new WeakReference<>(null);
    private static WeakReference<FilesStarter> sFilesStarter = new WeakReference<>(null);

    @MainThread
    public static void setFilesStarter(FilesStarter starter) {
        sFilesStarter = new WeakReference<>(starter);
    }

    @MainThread
    public static void clearFilesStarter(FilesStarter starter) {
        if (sFilesStarter.get() == starter) sFilesStarter.clear();
    }
    /** A ready outcome's sync notice (in memory only; the mark itself is in the preferences). */
    @Nullable
    private static SyncNotice sReadyNotice;
    private static boolean sFinishLoaderPending;

    /** The home screen is on screen: it gets what happened while it was not. */
    @MainThread
    public static void homeResumed(Context context, Home home) {
        sHome = new WeakReference<>(home);
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        long now = System.currentTimeMillis();
        NotificationManagerCompat notifications = NotificationManagerCompat.from(app);
        if (prefs.contains(KEY_FAILED_AT)) {
            Failure failure = new Failure(prefs.getBoolean(KEY_FAILED_NETWORK, false), prefs.getBoolean(KEY_FAILED_FILES, false),
                    prefs.getString(KEY_FAILED_DETAILS, ""));
            prefs.edit().remove(KEY_FAILED_AT).remove(KEY_FAILED_NETWORK).remove(KEY_FAILED_FILES).remove(KEY_FAILED_DETAILS).apply();
            notifications.cancel(NOTIFY_FAILED);
            home.showFailure(failure);
            return;
        }
        long readyAt = prefs.getLong(KEY_READY_AT, 0);
        if (readyAt > 0) {
            prefs.edit().remove(KEY_READY_AT).apply();
            notifications.cancel(NOTIFY_READY);
            SyncNotice notice = sReadyNotice;
            sReadyNotice = null;
            if (now - readyAt < READY_KEPT_MS && now >= readyAt) home.showReady(notice);
            return;
        }
        if (sFinishLoaderPending) {
            sFinishLoaderPending = false;
            home.showFinishLoader();
        }
    }

    /** The home screen left the screen (another screen, another app, the phone locked). */
    @MainThread
    public static void homePaused(Home home) {
        if (sHome.get() == home) sHome.clear();
    }

    /** True while the home screen is on screen. */
    @MainThread
    public static boolean homeVisible() {
        return sHome.get() != null;
    }

    /** A new start begins: what an earlier one left behind is old news. */
    @MainThread
    public static void starting(Context context) {
        Context app = context.getApplicationContext();
        prefs(app).edit().remove(KEY_READY_AT).remove(KEY_FAILED_AT).remove(KEY_FAILED_NETWORK)
                .remove(KEY_FAILED_FILES).remove(KEY_FAILED_DETAILS).apply();
        sReadyNotice = null;
        sFinishLoaderPending = false;
        NotificationManagerCompat notifications = NotificationManagerCompat.from(app);
        notifications.cancel(NOTIFY_READY);
        notifications.cancel(NOTIFY_FAILED);
    }

    /** The pack is ready ({@link ProjectsJoin}): the game starts if the home screen is up. */
    @MainThread
    public static void ready(Context context, String profileKey, @Nullable SyncNotice notice) {
        Home home = sHome.get();
        if (home != null) {
            home.launch(profileKey, notice);
            return;
        }
        sReadyNotice = notice;
        // M18: the game's own files (Java, Minecraft, its libraries) come next, away from the home screen
        // too, so "Projects hazır" means the game opens at once (before, Play still had them to download).
        // Their check ends here again, through filesReadyAway, or in failed.
        FilesStarter starter = sFilesStarter.get();
        if (starter != null && starter.startFiles(profileKey)) {
            Log.i(TAG, "pack ready while away from the home screen; the game files come next");
            return;
        }
        away(context.getApplicationContext());
    }

    /**
     * The game files are ready (the check that follows the pack), but the player is no longer on
     * the home screen: the game does not start by itself. Any thread.
     */
    public static void filesReadyAway(Context context) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> away(app));
    }

    @MainThread
    private static void away(Context app) {
        Log.i(TAG, "ready while away from the home screen; not starting the game");
        prefs(app).edit().putLong(KEY_READY_AT, System.currentTimeMillis()).apply();
        Home home = sHome.get();
        if (home != null) {
            // Back on the home screen meanwhile.
            homeResumed(app, home);
            return;
        }
        notifyPlayer(app, NOTIFY_READY, app.getString(R.string.projects_ready_title), app.getString(R.string.projects_ready_body),
                NotificationCompat.CATEGORY_STATUS);
    }

    /** A start failed: the error shows on the home screen, now or when the player comes back. Any thread. */
    public static void failed(Context context, boolean network, boolean files, String details) {
        final Context app = context.getApplicationContext();
        final Failure failure = new Failure(network, files, details);
        if (Looper.myLooper() == Looper.getMainLooper()) failedOnMain(app, failure);
        else MAIN.post(() -> failedOnMain(app, failure));
    }

    @MainThread
    private static void failedOnMain(Context app, Failure failure) {
        Home home = sHome.get();
        if (home != null) {
            home.showFailure(failure);
            return;
        }
        Log.i(TAG, "failed while away from the home screen: " + firstLine(failure.details));
        String details = failure.details.length() > MAX_DETAILS_CHARS ? failure.details.substring(0, MAX_DETAILS_CHARS) : failure.details;
        prefs(app).edit().remove(KEY_READY_AT).putLong(KEY_FAILED_AT, System.currentTimeMillis())
                .putBoolean(KEY_FAILED_NETWORK, failure.network).putBoolean(KEY_FAILED_FILES, failure.files)
                .putString(KEY_FAILED_DETAILS, details).apply();
        NotificationManagerCompat.from(app).cancel(NOTIFY_READY);
        notifyPlayer(app, NOTIFY_FAILED, app.getString(R.string.projects_join_failed_title), app.getString(message(failure)),
                NotificationCompat.CATEGORY_ERROR);
    }

    /** Forge or NeoForge needs upstream's installer (its own notification starts it). */
    @MainThread
    public static void finishLoader(Context context) {
        Home home = sHome.get();
        if (home != null) home.showFinishLoader();
        else sFinishLoaderPending = true;
    }

    /** Whether a failure is the connection's ({@link FailureKind}). */
    public static boolean isNetwork(Throwable failure, boolean fallback) {
        return FailureKind.isNetwork(failure, fallback);
    }

    /** The text of a failure, as the home screen's error says it. */
    public static int message(Failure failure) {
        if (failure.files) return failure.network ? R.string.projects_files_failed_network : R.string.projects_files_failed_generic;
        return failure.network ? R.string.projects_join_failed_network : R.string.projects_join_failed_generic;
    }

    /**
     * M18: true when Android 13+ would hide this app's notifications until the player allows them (the
     * app asks once, when the player first starts Projects: {@code ProjectsHomeCard}).
     */
    public static boolean needsPermission(Context context) {
        return Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private static void ensureChannel(Context app) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, app.getString(R.string.projects_ready_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(app.getString(R.string.projects_ready_channel_description));
        manager.createNotificationChannel(channel);
    }

    private static void notifyPlayer(Context app, int id, String title, String text, String category) {
        if (needsPermission(app)) {
            Log.i(TAG, "notifications not allowed; the home screen tells it on return");
            return;
        }
        ensureChannel(app);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(app, R.color.projects_gold_500))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(category)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(READY_KEPT_MS);
        Intent open = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (open != null) {
            // Back to the app as it was (the launcher icon's behaviour): the home screen takes it from there.
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            builder.setContentIntent(PendingIntent.getActivity(app, id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)));
        }
        try {
            NotificationManagerCompat.from(app).notify(id, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "notification not shown", e);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
