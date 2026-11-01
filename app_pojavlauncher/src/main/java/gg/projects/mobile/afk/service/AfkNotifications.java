package gg.projects.mobile.afk.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.language.LanguageManager;

/**
 * PHONE-AFK's notifications (WIP, API step): the ongoing "AFK hesapları" one (low importance, "Hepsini durdur"); the
 * alerts follow with the engine.
 */
public final class AfkNotifications {
    private AfkNotifications() {}

    public static final String CHANNEL_RUNNING = "projects_afk_running";
    public static final String CHANNEL_ALERTS = "projects_afk_alerts";
    static final int ID_RUNNING = 0x5A000040;

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        Context c = LanguageManager.wrap(context);
        NotificationChannel running = new NotificationChannel(CHANNEL_RUNNING, c.getString(R.string.projects_afk_channel_running),
                NotificationManager.IMPORTANCE_LOW);
        running.setShowBadge(false);
        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS, c.getString(R.string.projects_afk_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH);
        manager.createNotificationChannel(running);
        manager.createNotificationChannel(alerts);
    }

    static void startForeground(Service service) {
        ensureChannels(service);
        Context c = LanguageManager.wrap(service);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopAll = PendingIntent.getService(service, 1, AfkService.stopAllIntent(service, false), flags);
        android.app.Notification n = new NotificationCompat.Builder(c, CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(c.getString(R.string.projects_afk_notify_title))
                .setContentText(c.getString(R.string.projects_afk_notify_starting))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(AfkOpenActivity.open(c, null))
                .addAction(0, c.getString(R.string.projects_afk_notify_stop_all), stopAll)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(ID_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            service.startForeground(ID_RUNNING, n);
        }
    }
}
