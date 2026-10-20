package net.kdt.pojavlaunch.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.utils.NotificationUtils;

/**
 * Lazy service which allows the process not to get killed.
 * Can be created from context, can be killed statically
 */
public class ProgressService extends Service implements TaskCountListener {

    private NotificationManagerCompat notificationManagerCompat;

    /** Simple wrapper to start the service */
    public static void startService(Context context){
        Intent intent = new Intent(context, ProgressService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    private NotificationCompat.Builder mNotificationBuilder;
    // Projects fork (M17): the notification says what runs and how far ("Projects hazırlanıyor" and the
    // progress bar's line), and a tap opens the app.
    private gg.projects.mobile.join.ProgressNotice mNotice;
    /**
     * Projects fork (M18): while tasks run the CPU stays awake, and Wi-Fi out of its power save where
     * Android still allows that, so a download goes on at full speed after the player locks the phone
     * (a tester's "Projects hazır" came very late); both are let go the moment the tasks end.
     */
    private android.os.PowerManager.WakeLock mWakeLock;
    private android.net.wifi.WifiManager.WifiLock mWifiLock;
    /** Projects fork (M18): the service waits a moment before stopping: a task that follows at once keeps it. */
    private static final long STOP_GRACE_MS = 3000;
    private final Runnable mStopIfIdle = () -> {
        if (ProgressKeeper.getTaskCount() < 1) stopSelf();
    };

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
        notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
        Intent killIntent = new Intent(getApplicationContext(), ProgressService.class);
        killIntent.putExtra("kill", true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this, NotificationUtils.PENDINGINTENT_CODE_KILL_PROGRESS_SERVICE
                , killIntent, Build.VERSION.SDK_INT >=23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        mNotificationBuilder = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle(getString(R.string.lazy_service_default_title))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,  getString(R.string.notification_terminate), pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setOnlyAlertOnce(true)
                .setNotificationSilent();
        mNotice = new gg.projects.mobile.join.ProgressNotice(this, this::refreshNotification);
        mNotice.start();
    }

    /** Projects fork (M17): the progress moved: the notification follows (at most twice a second). */
    private void refreshNotification() {
        int tasks = ProgressKeeper.getTaskCount();
        if (tasks < 1 || mNotice == null) return;
        mNotice.fill(mNotificationBuilder, tasks);
        notificationManagerCompat.notify(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, mNotificationBuilder.build());
    }

    @SuppressLint("StringFormatInvalid")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null) {
            if(intent.getBooleanExtra("kill", false)) {
                stopSelf(); // otherwise Android tries to restart the service since it "crashed"
                Process.killProcess(Process.myPid());
                return START_NOT_STICKY;
            }
        }
        Log.d("ProgressService", "Started!");
        // Projects fork (M14): a plural ("1 task", not "1 tasks"); M17: what runs and how far.
        int tasks = ProgressKeeper.getTaskCount();
        mNotificationBuilder.setContentText(getResources().getQuantityString(R.plurals.projects_tasks_in_progress, tasks, tasks));
        if (mNotice != null) mNotice.fill(mNotificationBuilder, tasks);
        Notification notification = mNotificationBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, notification);
        }
        if(ProgressKeeper.getTaskCount() < 1) stopSelf();
        else {
            ProgressKeeper.addTaskCountListener(this, false);
            Tools.MAIN_HANDLER.removeCallbacks(mStopIfIdle);
            holdAwake(true);
        }

        return START_NOT_STICKY;
    }

    /** Projects fork (M18): keeps the CPU (and Wi-Fi) awake while tasks run; see {@link #mWakeLock}. */
    private void holdAwake(boolean hold) {
        try {
            if (hold) {
                if (mWakeLock == null) {
                    android.os.PowerManager power = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (power != null) {
                        mWakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ProjectsLauncher:tasks");
                        mWakeLock.setReferenceCounted(false);
                    }
                }
                // A safety limit: the lock goes with the last task, or after two hours at the latest.
                if (mWakeLock != null && !mWakeLock.isHeld()) mWakeLock.acquire(2 * 60 * 60 * 1000L);
                if (mWifiLock == null && Build.VERSION.SDK_INT < 34) {
                    android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wifi != null) {
                        mWifiLock = wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ProjectsLauncher:tasks");
                        mWifiLock.setReferenceCounted(false);
                    }
                }
                if (mWifiLock != null && !mWifiLock.isHeld()) mWifiLock.acquire();
            } else {
                if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
                if (mWifiLock != null && mWifiLock.isHeld()) mWifiLock.release();
            }
        } catch (RuntimeException e) {
            // A phone that refuses a lock still runs the tasks, only less eagerly with the screen off.
            Log.w("ProgressService", "wake lock " + (hold ? "not taken" : "not released"), e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        ProgressKeeper.removeTaskCountListener(this);
        if (mNotice != null) mNotice.stop();
        Tools.MAIN_HANDLER.removeCallbacks(mStopIfIdle);
        holdAwake(false);
    }

    @Override
    public void onUpdateTaskCount(int taskCount) {
        Tools.MAIN_HANDLER.post(()->{
            if(taskCount > 0) {
                Tools.MAIN_HANDLER.removeCallbacks(mStopIfIdle);
                holdAwake(true);
                mNotificationBuilder.setContentText(getResources().getQuantityString(R.plurals.projects_tasks_in_progress, taskCount, taskCount));
                if (mNotice != null) mNotice.fill(mNotificationBuilder, taskCount);
                notificationManagerCompat.notify(1, mNotificationBuilder.build());
            }else{
                // Projects fork (M18): not at once: a Projects start's next step (the game files after the
                // pack) begins a moment later, and a running service keeps it in the foreground, also when
                // the player has left the app (Android 12+ no longer lets an app in the background start one).
                Tools.MAIN_HANDLER.removeCallbacks(mStopIfIdle);
                Tools.MAIN_HANDLER.postDelayed(mStopIfIdle, STOP_GRACE_MS);
            }
        });
    }
}
