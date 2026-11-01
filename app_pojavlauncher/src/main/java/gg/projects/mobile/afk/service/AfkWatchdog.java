package gg.projects.mobile.afk.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;

import gg.projects.mobile.afk.api.AfkEnd;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.afk.store.AfkEnds;
import gg.projects.mobile.afk.store.AfkRuntime;

/**
 * PHONE-AFK's watchdog (phone-afk.md §3, §4). While accounts run, the service keeps an alarm
 * {@link #DELAY_MS} ahead and moves it on every few minutes; when the {@code :afk} process is gone (the phone or its
 * maker closed it) the alarm fires in a fresh {@code :afk} process: with the battery limit lifted by the player, AFK
 * comes back by itself (Android allows that start from the background); otherwise the end is recorded and one
 * notification says what happened and how to fix it. Never on boot.
 */
public final class AfkWatchdog extends BroadcastReceiver {
    private static final String TAG = "ProjectsAfk";
    static final long DELAY_MS = 15 * 60 * 1000L;
    private static final String ACTION = "gg.projects.mobile.afk.WATCHDOG";

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, AfkWatchdog.class).setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    static void arm(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DELAY_MS, pending(context));
        } catch (RuntimeException e) {
            Log.w(TAG, "AFK watchdog not set", e);
        }
    }

    static void disarm(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(pending(context));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        if (AfkService.running()) return; // The service is alive in this process: nothing happened.
        File dir = PhoneAfk.afkDir(context);
        AfkRuntime runtime = AfkRuntime.read(dir);
        if (runtime.running.isEmpty()) return;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean unrestricted = power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
        AfkEnd end = AfkEnds.detect(context, dir, runtime, false);
        if (end != null && end.kind == AfkEnd.Kind.STOPPED_BY_PLAYER) return;
        if (unrestricted && PhoneAfk.phoneSupported()) {
            try {
                ContextCompat.startForegroundService(context, AfkService.resumeIntent(context));
                // Back by itself: the end needs no notice.
                AfkEnds.dismiss(dir);
                return;
            } catch (IllegalStateException | SecurityException e) {
                Log.w(TAG, "AFK not resumed", e);
            }
        }
        if (end != null) AfkNotifications.end(context, end);
    }
}
