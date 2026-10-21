package gg.projects.mobile.reminders;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.social.SocialNotifications;

/**
 * M21 (TEAM5): plan reminders on the phone: the store (the app's preferences), one alarm for the next
 * reminder (exact while Android allows it, else inexact; both run in Doze, like the event reminders'
 * alarm, which has its own), and the notification. The rules are in {@link PlanReminders}; the friends
 * feature tells the store what the plans call for ({@link #sync}) and what keeps them quiet
 * ({@link #setQuiet}). A phone restart or an app update sets the alarm again ({@link ReminderReceiver}).
 */
public final class PlanAlarms {
    private PlanAlarms() {}

    private static final String PREFS = "projects_plan_reminders";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_QUIET = "quiet";
    static final String ACTION_RING = "gg.projects.mobile.reminders.PLAN_RING";
    /** The event reminders' alarm is request 0. */
    private static final int REQUEST = 1;
    private static final Object LOCK = new Object();

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Map<String, PlanReminders.Item> load(Context context) {
        synchronized (LOCK) {
            return PlanReminders.fromJson(prefs(context).getString(KEY_ITEMS, null));
        }
    }

    private static void save(Context context, Map<String, PlanReminders.Item> stored) {
        synchronized (LOCK) {
            prefs(context).edit().putString(KEY_ITEMS, PlanReminders.toJson(stored)).apply();
        }
    }

    /**
     * The reminders the plans call for now (the socket's view of them): the store becomes exactly
     * these; one no longer called for takes its notification away.
     */
    public static void sync(Context context, Map<String, PlanReminders.Item> desired, long nowSeconds) {
        Map<String, PlanReminders.Item> before;
        Map<String, PlanReminders.Item> next;
        synchronized (LOCK) {
            before = load(context);
            next = PlanReminders.prune(PlanReminders.merge(before, desired, nowSeconds), nowSeconds);
            if (!next.equals(before)) save(context, next);
        }
        for (String plan : before.keySet()) {
            PlanReminders.Item now = next.get(plan);
            if (now == null || now.at != before.get(plan).at) SocialNotifications.cancelPlanReminder(context, plan);
        }
        schedule(context);
    }

    /** What keeps reminders quiet (do-not-disturb, muted crews), as last known. */
    public static void setQuiet(Context context, PlanReminders.Quiet quiet) {
        String json = PlanReminders.quietToJson(quiet);
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            if (json.equals(prefs.getString(KEY_QUIET, null))) return;
            prefs.edit().putString(KEY_QUIET, json).apply();
        }
    }

    /** Signed out (or the friends feature went): no reminder stays, none is up. */
    public static void clear(Context context) {
        Map<String, PlanReminders.Item> before = load(context);
        synchronized (LOCK) {
            prefs(context).edit().clear().apply();
        }
        for (String plan : before.keySet()) SocialNotifications.cancelPlanReminder(context, plan);
        schedule(context);
    }

    /** A plan went (cancelled): its reminder, and its notification if it is up. */
    public static void forget(Context context, String plan) {
        synchronized (LOCK) {
            Map<String, PlanReminders.Item> stored = load(context);
            if (stored.remove(plan) != null) save(context, stored);
        }
        SocialNotifications.cancelPlanReminder(context, plan);
        schedule(context);
    }

    /**
     * The alarm rang (or the phone restarted, or the app was updated): tells the reminders that are
     * due and sets the alarm for the next one. While the game is on screen, on do-not-disturb, while
     * the crew is muted or with notifications off nothing shows; the reminder counts as told.
     */
    static void ring(Context context) {
        long now = System.currentTimeMillis() / 1000L;
        boolean game = Reminders.isGameOnScreen(context);
        boolean notify = SocialNotifications.canNotify(context) && SocialNotifications.channelOn(context, SocialNotifications.CHANNEL_PLANS);
        List<PlanReminders.Item> told = new ArrayList<>();
        synchronized (LOCK) {
            Map<String, PlanReminders.Item> stored = PlanReminders.prune(load(context), now);
            PlanReminders.Quiet quiet = PlanReminders.quietFromJson(prefs(context).getString(KEY_QUIET, null));
            List<String> ids = new ArrayList<>();
            for (PlanReminders.Item item : PlanReminders.due(stored, now)) {
                ids.add(item.plan);
                if (!game && notify && !quiet.quiet(item.crew, now)) told.add(item);
            }
            save(context, PlanReminders.markFired(stored, ids, now));
        }
        for (PlanReminders.Item item : told) {
            SocialNotifications.showPlanReminder(context, item.plan, item.crew, item.crewName, item.at,
                    PlanReminders.minutesUntil(item.at, now), (item.until - now) * 1000L);
        }
        schedule(context);
    }

    /** Sets the alarm for the next plan reminder, or cancels it when none waits. */
    static void schedule(Context context) {
        Long next = PlanReminders.nextRing(load(context), System.currentTimeMillis() / 1000L);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent ring = PendingIntent.getBroadcast(context, REQUEST,
                new Intent(context, ReminderReceiver.class).setAction(ACTION_RING),
                PendingIntent.FLAG_UPDATE_CURRENT | Reminders.immutable());
        if (next == null) {
            alarms.cancel(ring);
            return;
        }
        long at = next * 1000L;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring);
            } else if (Build.VERSION.SDK_INT >= 23) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring);
            } else {
                alarms.setExact(AlarmManager.RTC_WAKEUP, at, ring);
            }
        } catch (SecurityException e) {
            // Exact alarms were taken away between the check and the call.
            if (Build.VERSION.SDK_INT >= 23) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring);
            else alarms.set(AlarmManager.RTC_WAKEUP, at, ring);
        }
    }
}
