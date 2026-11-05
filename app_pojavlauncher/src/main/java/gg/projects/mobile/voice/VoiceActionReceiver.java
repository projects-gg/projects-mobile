package gg.projects.mobile.voice;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import gg.projects.mobile.social.SocialService;

/**
 * "Reddet" on a ring's notification (not exported: only the app's own notifications send it): the ring stops on every
 * device; on the presence socket when the app has one, else through the service's route. The caller hears nothing more
 * than a time-out (the desktop's rule).
 */
public final class VoiceActionReceiver extends BroadcastReceiver {
    private static final String ACTION_DECLINE = "gg.projects.mobile.voice.DECLINE";
    private static final String EXTRA_ROOM = "gg.projects.mobile.voice.ROOM";

    public static PendingIntent decline(Context context, String room) {
        Intent intent = new Intent(context, VoiceActionReceiver.class).setAction(ACTION_DECLINE).putExtra(EXTRA_ROOM, room);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, ("decline|" + room).hashCode(), intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_DECLINE.equals(intent.getAction())) return;
        final String room = intent.getStringExtra(EXTRA_ROOM);
        if (!VoiceWire.isRoomId(room)) return;
        VoiceNotify.cancelRing(context, room);
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            SocialService.get(app);
            VoiceHub hub = VoiceHub.instance();
            if (hub == null) {
                pending.finish();
                return;
            }
            hub.decline(room, pending::finish);
        });
    }
}
