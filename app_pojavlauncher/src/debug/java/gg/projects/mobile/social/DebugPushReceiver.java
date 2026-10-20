package gg.projects.mobile.social;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Debug builds only (M19): a push as FCM would deliver it, sent from adb, so the path from a data
 * message to a notification can be tested where no real push can come (an emulator, a local friends
 * service that may not send to Google). Every string extra is one data field:
 * <pre>adb shell am broadcast -n gg.projects.mobile.debug/gg.projects.mobile.social.DebugPushReceiver \
 *     --es v 1 --es type invite --es invite 57 --es sender 1000... --es name Ayşe --es at 1792521000 \
 *     --es expires 1792524600 --es where projects --es to 1000...</pre>
 * The release build has neither this class nor its manifest entry.
 */
public final class DebugPushReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras = intent.getExtras();
        Map<String, String> data = new HashMap<>();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value instanceof String) data.put(key, (String) value);
            }
        }
        Log.i("ProjectsSocial", "debug push: " + data.get("type"));
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                SocialPush.handle(context, data);
            } finally {
                pending.finish();
            }
        }, "debug-push").start();
    }
}
