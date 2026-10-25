package gg.projects.mobile.social;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;

/**
 * Android 5.0 to 7.1: an invite or a crew call notification goes away at its end ({@code setTimeoutAfter}
 * works from Android 8.0). An alarm, set when it is shown, takes it away; a newer one under the same tag
 * moves the alarm. Not exported: only this app's own alarm reaches it.
 */
public final class SocialNotificationExpiry extends BroadcastReceiver {
    private static final String EXTRA_TAG = "tag";
    private static final String EXTRA_ID = "id";

    @Override
    public void onReceive(Context context, Intent intent) {
        String tag = intent.getStringExtra(EXTRA_TAG);
        int id = intent.getIntExtra(EXTRA_ID, 0);
        if (tag != null && id != 0) NotificationManagerCompat.from(context).cancel(tag, id);
    }

    public static void schedule(Context context, String tag, int id, long timeoutMs) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent intent = new Intent(context, SocialNotificationExpiry.class)
                .putExtra(EXTRA_TAG, tag)
                .putExtra(EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pending = PendingIntent.getBroadcast(context, (tag + "#" + id).hashCode(), intent, flags);
        alarms.set(AlarmManager.RTC, System.currentTimeMillis() + timeoutMs, pending);
    }
}
