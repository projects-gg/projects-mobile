package gg.projects.mobile.reminders;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.content.HomeContent;
import gg.projects.mobile.content.Snapshot;

/**
 * Event reminders on the phone: the store (the app's preferences), one alarm for the next
 * reminder, and what the player is told 5 minutes before an event starts. The rules are in
 * {@link EventReminders}.
 *
 * <p>The alarm is exact while Android allows it (before 12, and with "Alarms &amp; reminders"
 * on), else inexact; both run in Doze (the {@code AllowWhileIdle} kinds, which Android rate-limits)
 * and the text is worked out when it rings, so a late alarm still says the right thing. A phone
 * restart or an app update sets the alarm again ({@link ReminderReceiver}); a reminder missed while
 * the phone was off says the event is on, if it still is. While the game is on screen nothing is
 * shown (the reminder counts as told, as on the desktop). When notifications are off the reminder
 * waits and the main menu tells it the next time it opens.
 */
public final class Reminders {
    private Reminders() {}

    private static final String TAG = "Reminders";
    private static final String PREFS = "projects_reminders";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_EXPLAINED = "explained";
    static final String CHANNEL_ID = "projects_event_reminders";
    static final String ACTION_RING = "gg.projects.mobile.reminders.RING";
    private static final Object LOCK = new Object();

    // ---- store ----

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Map<String, EventReminders.Reminder> load(Context context) {
        synchronized (LOCK) {
            return EventReminders.fromJson(prefs(context).getString(KEY_ITEMS, null));
        }
    }

    private static void save(Context context, Map<String, EventReminders.Reminder> stored) {
        synchronized (LOCK) {
            prefs(context).edit().putString(KEY_ITEMS, EventReminders.toJson(stored)).apply();
        }
    }

    /** True while the player has a reminder set for {@code eventId}. */
    public static boolean isSet(Context context, String eventId) {
        return load(context).containsKey(eventId);
    }

    /**
     * Sets or removes the reminder for {@code event}; returns true when it is now set. Less than
     * five minutes before the start the reminder is due at once, so the caller tells it right away
     * ({@link #tellInApp}).
     */
    public static boolean toggle(Context context, HomeContent.Event event) {
        boolean set;
        synchronized (LOCK) {
            Map<String, EventReminders.Reminder> next = EventReminders.toggle(load(context), event);
            set = next.containsKey(event.id);
            save(context, next);
        }
        schedule(context);
        return set;
    }

    /** True the first time a reminder is set: the card explains it once. */
    public static boolean takeFirstExplanation(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            if (prefs.getBoolean(KEY_EXPLAINED, false)) return false;
            prefs.edit().putBoolean(KEY_EXPLAINED, true).apply();
            return true;
        }
    }

    /**
     * Follows the content the home screen shows (moved, hidden and ended events) and sets the alarm
     * again: Android drops an app's alarms when it is force-stopped.
     */
    public static void onContent(Context context, Snapshot snapshot, long now) {
        if (snapshot == null || !snapshot.hasContent()) return;
        synchronized (LOCK) {
            Map<String, EventReminders.Reminder> stored = load(context);
            if (stored.isEmpty()) return;
            Map<String, EventReminders.Reminder> next = EventReminders.reconcile(stored, snapshot.home(now).events, now);
            if (next != stored) save(context, next);
        }
        schedule(context);
    }

    // ---- telling the player ----

    /**
     * The alarm rang (or the phone restarted, or the app was updated): tells the reminders that are
     * due with a notification and sets the alarm for the next one.
     */
    static void ring(Context context) {
        long now = System.currentTimeMillis();
        boolean gameOnScreen = isGameOnScreen(context);
        boolean canNotify = canNotify(context);
        List<EventReminders.Due> told = new ArrayList<>();
        synchronized (LOCK) {
            Map<String, EventReminders.Reminder> stored = EventReminders.prune(load(context), now);
            List<String> ids = new ArrayList<>();
            for (EventReminders.Due due : EventReminders.due(stored, now)) {
                // In game nothing shows; with notifications off the main menu tells it later.
                if (!gameOnScreen && !canNotify) continue;
                ids.add(due.reminder.id);
                if (!gameOnScreen) told.add(due);
            }
            save(context, EventReminders.markFired(stored, ids, now));
        }
        for (EventReminders.Due due : told) notify(context, due, now);
        schedule(context);
    }

    /**
     * Tells the reminders that are due while the main menu is on screen (notifications were off at
     * their moment, or the alarm is late): a short message each, then they count as told.
     */
    public static void tellInApp(Context context) {
        long now = System.currentTimeMillis();
        List<EventReminders.Due> due;
        synchronized (LOCK) {
            Map<String, EventReminders.Reminder> stored = EventReminders.prune(load(context), now);
            due = EventReminders.due(stored, now);
            if (due.isEmpty()) {
                if (stored.size() != load(context).size()) save(context, stored);
                return;
            }
            List<String> ids = new ArrayList<>();
            for (EventReminders.Due d : due) ids.add(d.reminder.id);
            save(context, EventReminders.markFired(stored, ids, now));
        }
        for (EventReminders.Due d : due) {
            String text = d.running
                    ? context.getString(R.string.projects_reminder_running_toast, d.reminder.title)
                    : context.getResources().getQuantityString(R.plurals.projects_reminder_starts_in_toast, d.minutes,
                    d.reminder.title, d.minutes);
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        }
        schedule(context);
    }

    private static void notify(Context context, EventReminders.Due due, long now) {
        ensureChannel(context);
        String text = due.running
                ? context.getString(R.string.projects_reminder_running)
                : context.getResources().getQuantityString(R.plurals.projects_reminder_starts_in, due.minutes, due.minutes);
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(context, R.color.projects_gold_500))
                .setContentTitle(due.reminder.title)
                .setContentText(text)
                // The text says when; Android's own "in 4m" next to it would round the other way.
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                // Gone by itself once the event is over.
                .setTimeoutAfter(Math.max(1, due.reminder.endsAt - now));
        if (open != null) {
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            builder.setContentIntent(PendingIntent.getActivity(context, notificationId(due.reminder.id), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | immutable()));
        }
        try {
            NotificationManagerCompat.from(context).notify(notificationId(due.reminder.id), builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "reminder not shown", e);
        }
    }

    /** One notification per event, apart from upstream's own ids (1 to 5). */
    static int notificationId(String eventId) {
        return 0x50000000 | (eventId.hashCode() & 0x0FFFFFFF);
    }

    private static int immutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    /** True while notifications can reach the player (permission, app and reminder channel). */
    public static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL_ID);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    /** True when only the reminder channel is switched off (the app's other notifications are on). */
    public static boolean channelBlocked(Context context) {
        if (Build.VERSION.SDK_INT < 26) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL_ID);
        return channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.projects_reminder_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.projects_reminder_channel_description));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    /** The game process shows its screen (MainActivity runs in ":game"). */
    private static boolean isGameOnScreen(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes == null) return false;
        String game = context.getPackageName() + ":game";
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (game.equals(process.processName)
                    && process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true;
            }
        }
        return false;
    }

    // ---- the alarm ----

    /** Sets the alarm for the next reminder, or cancels it when none is waiting. */
    static void schedule(Context context) {
        Long next = EventReminders.nextRing(load(context), System.currentTimeMillis());
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent ring = PendingIntent.getBroadcast(context, 0,
                new Intent(context, ReminderReceiver.class).setAction(ACTION_RING),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable());
        if (next == null) {
            alarms.cancel(ring);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, ring);
            } else if (Build.VERSION.SDK_INT >= 23) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, ring);
            } else {
                alarms.setExact(AlarmManager.RTC_WAKEUP, next, ring);
            }
        } catch (SecurityException e) {
            // Exact alarms were taken away between the check and the call.
            if (Build.VERSION.SDK_INT >= 23) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, ring);
            else alarms.set(AlarmManager.RTC_WAKEUP, next, ring);
        }
    }
}
