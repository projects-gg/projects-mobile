package gg.projects.mobile.social;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A push from the friends service, on whatever thread it arrives (FCM's own, or the debug build's
 * injection path): read with {@link PushMessage}, decided against the session on this phone and the
 * player's switches, then shown or taken back by {@link SocialNotifications}. It needs nothing of the
 * running app: a push that wakes the app reads the session file and the switches itself.
 */
public final class SocialPush {
    private SocialPush() {}

    private static final String TAG = "ProjectsSocial";

    public static void handle(Context context, Map<String, String> data) {
        Context app = context.getApplicationContext();
        PushMessage message = PushMessage.parse(data);
        if (message == null) {
            Log.i(TAG, "push ignored: not a message of this app's format");
            return;
        }
        SocialDebug debug = SocialDebug.read(app);
        SessionStore.Saved saved = new SessionStore(app, debug.override ? "session-debug.json" : "session.json").load();
        String signedIn = saved.token == null ? null : saved.userId;
        SocialPrefs prefs = new SocialPrefs(app);
        long now = SocialService.nowSeconds();
        Set<String> wanted = new HashSet<>();
        if (prefs.wantsRequests()) wanted.add(PushMessage.REQUEST);
        if (prefs.wantsInvites()) wanted.add(PushMessage.INVITE);
        if (prefs.wantsCallsLocally()) wanted.add(PushMessage.CALL);
        PushMessage.Decision decision = message.decide(signedIn, now, prefs.endedCalls(now), wanted, prefs.crewAudible(now));
        switch (decision.action) {
            case SHOW:
                Log.i(TAG, "push shown: " + message.type);
                if (PushMessage.REQUEST.equals(message.type)) {
                    SocialNotifications.showRequest(app, message.sender, message.name);
                } else if (PushMessage.INVITE.equals(message.type)) {
                    SocialNotifications.showInvite(app, message.sender, message.name, message.id, message.where, message.host,
                            decision.timeoutMs);
                } else if (PushMessage.CALL.equals(message.type)) {
                    SocialNotifications.showCall(app, message.id, message.sender, message.name,
                            SocialModel.Call.JOIN.equals(message.kind), decision.timeoutMs);
                }
                break;
            case CANCEL:
                Log.i(TAG, "push: call ended");
                if (message.id != null) {
                    prefs.addEndedCall(message.id, 0, now);
                    SocialNotifications.cancelCall(app, message.id);
                }
                break;
            case DROP:
            default:
                Log.i(TAG, "push dropped (" + message.type + "): " + decision.why);
                return;
        }
        // A running app reads the list again when its screens come back (it may have moved on).
        new Handler(Looper.getMainLooper()).post(() -> {
            if (SocialService.exists()) SocialService.get(app).pushArrived();
        });
    }
}
