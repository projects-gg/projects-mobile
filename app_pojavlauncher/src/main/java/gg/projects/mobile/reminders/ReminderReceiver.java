package gg.projects.mobile.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The reminder alarm, and the moments Android drops alarms (a restart, an app update) or allows
 * exact ones again: tells what is due and sets the alarm for the next reminder. Only these actions
 * do anything; the work runs off the main thread.
 */
public final class ReminderReceiver extends BroadcastReceiver {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Reminders.ACTION_RING.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) {
            return;
        }
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        EXECUTOR.execute(() -> {
            try {
                Reminders.ring(app);
            } catch (RuntimeException e) {
                Log.w("ReminderReceiver", "reminders could not be checked", e);
            } finally {
                pending.finish();
            }
        });
    }
}
