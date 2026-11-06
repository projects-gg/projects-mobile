package gg.projects.mobile.afk.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkEnd;
import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.AfkTexts;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.social.SocialNotifications;

/**
 * PHONE-AFK's notifications (phone-afk.md §4), in the app's language: the ongoing "AFK hesapları" one while the service
 * runs (low importance, a line per account, "Hepsini durdur"), and "AFK uyarıları" (high: the name in chat, a private
 * message, a server window, out of the game for three minutes, stopped, battery, heat, the phone closing AFK). One
 * notification per account and kind, changed in place; the same one again within half a minute changes quietly.
 */
public final class AfkNotifications {
    private AfkNotifications() {}

    private static final String TAG = "ProjectsAfk";
    public static final String CHANNEL_RUNNING = "projects_afk_running";
    public static final String CHANNEL_ALERTS = "projects_afk_alerts";
    static final int ID_RUNNING = 0x5A000040;
    static final int ID_ALERT = 0x5A000041;
    static final int ID_END = 0x5A000042;
    private static final long QUIET_REPEAT_MS = 30_000L;
    private static final Map<String, Long> LAST_LOUD = new HashMap<>();

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

    private static int immutable() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    /** The ongoing notification for {@code statuses} (running accounts; empty while starting). */
    static Notification running(Context context, List<AfkStatus> statuses) {
        Context c = LanguageManager.wrap(context);
        int inGame = 0;
        for (AfkStatus s : statuses) {
            if (s.state == AfkState.IN_GAME) inGame++;
        }
        String text;
        if (statuses.isEmpty()) {
            text = c.getString(R.string.projects_afk_notify_starting);
        } else if (inGame > 0) {
            text = c.getResources().getQuantityString(R.plurals.projects_afk_notify_in_game, inGame, inGame);
        } else {
            // Nobody in game: the first account by name ("SleepyFox_42: 23 saniye sonra yeniden bağlanacak"), so a state
            // never reads as everyone's; the expanded notification lists them all.
            AfkStatus first = statuses.get(0);
            text = c.getString(R.string.projects_afk_notify_account_line, first.name,
                    AfkTexts.state(c, first, System.currentTimeMillis()));
        }
        NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
        long now = System.currentTimeMillis();
        for (AfkStatus s : statuses) {
            inbox.addLine(c.getString(R.string.projects_afk_notify_account_line, s.name, AfkTexts.state(c, s, now)));
        }
        PendingIntent stopAll = PendingIntent.getService(c, 1, AfkService.stopAllIntent(c, false), immutable());
        return new NotificationCompat.Builder(c, CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(c.getString(R.string.projects_afk_notify_title))
                .setContentText(text)
                .setStyle(statuses.size() > 1 ? inbox : null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(AfkOpenActivity.open(c, null))
                .addAction(0, c.getString(R.string.projects_afk_notify_stop_all), stopAll)
                .build();
    }

    static void startForeground(Service service, Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(ID_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            service.startForeground(ID_RUNNING, n);
        }
    }

    static void updateRunning(Context context, Notification n) {
        notifyQuietly(context, null, ID_RUNNING, n);
    }

    private static String tagOf(String accountId, AfkAlert.Kind kind) {
        return "afk:" + accountId + ":" + kind.name();
    }

    /** An alert the engine raised. */
    static void alert(Context context, AfkAlert a) {
        Context c = LanguageManager.wrap(context);
        if (!SocialNotifications.canNotify(c)) return;
        String title;
        String text = a.text;
        switch (a.kind) {
            case MENTION:
                title = c.getString(R.string.projects_afk_alert_mention, a.name);
                break;
            case PRIVATE_MESSAGE:
                title = c.getString(R.string.projects_afk_alert_private, a.name);
                break;
            case SERVER_WINDOW:
                title = c.getString(R.string.projects_afk_alert_window, a.name);
                break;
            case DROPPED:
                title = c.getString(R.string.projects_afk_alert_dropped, a.name);
                text = c.getString(R.string.projects_afk_alert_dropped_text, AfkTexts.reason(c, a.reason == null ? AfkReason.DISCONNECTED : a.reason));
                break;
            case STOPPED:
            default:
                title = c.getString(R.string.projects_afk_alert_stopped, a.name);
                String why = AfkTexts.reason(c, a.reason);
                text = a.text.isEmpty() ? why : why + " (" + a.text + ")";
                break;
        }
        show(c, tagOf(a.accountId, a.kind), ID_ALERT, title, text, AfkOpenActivity.open(c, a.accountId), null);
    }

    static void clearAlert(Context context, String accountId, AfkAlert.Kind kind) {
        NotificationManagerCompat.from(context).cancel(tagOf(accountId, kind), ID_ALERT);
    }

    /** The phone stopped every account (battery or heat): one alert. */
    static void phoneStop(Context context, AfkReason reason) {
        Context c = LanguageManager.wrap(context);
        if (!SocialNotifications.canNotify(c)) return;
        String text = c.getString(reason == AfkReason.TOO_HOT ? R.string.projects_afk_alert_hot : R.string.projects_afk_alert_battery);
        show(c, "afk:phone", ID_ALERT, c.getString(R.string.projects_afk_end_title), text, AfkOpenActivity.open(c, null), null);
    }

    /** The morning notification after the phone closed AFK, with "Ayarı aç" when the battery setting is the fix. */
    static void end(Context context, AfkEnd end) {
        if (end == null || end.kind == AfkEnd.Kind.STOPPED_BY_PLAYER) return;
        Context c = LanguageManager.wrap(context);
        if (!SocialNotifications.canNotify(c)) return;
        PendingIntent fix = end.batteryFix() ? PendingIntent.getActivity(c, 2, batteryFix(c), immutable()) : null;
        show(c, "afk:end", ID_END, c.getString(R.string.projects_afk_end_title), AfkTexts.end(c, end),
                AfkOpenActivity.open(c, null), fix);
    }

    /** Android's battery question when AFK is still limited, else the app's own settings page. */
    static Intent batteryFix(Context c) {
        PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean unrestricted = power != null && power.isIgnoringBatteryOptimizations(c.getPackageName());
        Intent i = !unrestricted && PhoneAfk.phoneSupported()
                ? new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + c.getPackageName()))
                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + c.getPackageName()));
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static void show(Context c, String tag, int id, String title, String text, PendingIntent open, PendingIntent fix) {
        ensureChannels(c);
        long now = System.currentTimeMillis();
        Long last = LAST_LOUD.get(tag);
        boolean quiet = last != null && now - last < QUIET_REPEAT_MS;
        if (!quiet) LAST_LOUD.put(tag, now);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(quiet)
                .setAutoCancel(true)
                .setContentIntent(open);
        if (fix != null) b.addAction(0, c.getString(R.string.projects_afk_end_fix), fix);
        notifyQuietly(c, tag, id, b.build());
    }

    private static void notifyQuietly(Context c, String tag, int id, Notification n) {
        try {
            NotificationManagerCompat.from(c).notify(tag, id, n);
        } catch (SecurityException e) {
            Log.w(TAG, "AFK notification not shown", e);
        }
    }

    /** Every alert of the AFK accounts goes (the service stopped, or the data was erased). */
    static void cancelAlerts(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || Build.VERSION.SDK_INT < 23) return;
        try {
            for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications()) {
                if (n.getId() == ID_ALERT && n.getTag() != null && n.getTag().startsWith("afk:")) manager.cancel(n.getTag(), n.getId());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "notifications not listed", e);
        }
    }
}
