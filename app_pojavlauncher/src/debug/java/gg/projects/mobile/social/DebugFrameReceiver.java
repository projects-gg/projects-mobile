package gg.projects.mobile.social;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.gson.JsonObject;

/**
 * Debug builds only (M051): one presence socket frame sent from adb, as if the friends service's hub said it, for screens
 * whose data a local service cannot make (who shares their screen in a voice room: {@code voice_rooms} with
 * {@code live}, a friend's {@code presence} with {@code live}):
 * <pre>adb shell am broadcast -n gg.projects.mobile.debug/gg.projects.mobile.social.DebugFrameReceiver \
 *     --es json '{"t":"presence","presence":{"id":"1000…","state":"launcher","voice":"in","live":true}}'</pre>
 * Only the shell (which holds DUMP) may send it. The release build has neither this class nor its manifest entry.
 */
public final class DebugFrameReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        JsonObject frame = Json.parseObject(intent.getStringExtra("json"));
        if (frame == null) return;
        Log.i("ProjectsSocial", "debug frame: " + Json.str(frame, "t", "?"));
        SocialService.get(context).debugFrame(frame);
    }
}
