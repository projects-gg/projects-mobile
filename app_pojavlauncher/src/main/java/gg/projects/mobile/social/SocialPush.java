package gg.projects.mobile.social;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.language.LanguageManager;

/**
 * A push from the friends service, on whatever thread it arrives (FCM's own, or the debug build's
 * injection path): read with {@link PushMessage}, decided against the session on this phone and the
 * player's switches, then shown or taken back by {@link SocialNotifications}. It needs nothing of the
 * running app: a push that wakes the app reads the session file and the switches itself.
 *
 * <p>M20: a direct message ("Ayşe sana mesaj gönderdi"; nothing for a conversation muted here, silent
 * while the game is on screen), and a call naming its crews and its server.
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
        boolean[] config = prefs.lastConfig();
        if (!config[0] && !debug.forceFriends) {
            // Friends were turned off in the signed config: nothing of the feature shows (the registration goes
            // when the app next runs; a push sent before that is dropped here).
            Log.i(TAG, "push dropped (" + message.type + "): friends are off");
            return;
        }
        if (PushMessage.MESSAGE.equals(message.type) && !config[2] && !debug.forceChat) {
            Log.i(TAG, "push dropped (message): chat is off");
            return;
        }
        long now = SocialService.nowSeconds();
        Set<String> wanted = new HashSet<>();
        if (prefs.wantsRequests()) wanted.add(PushMessage.REQUEST);
        if (prefs.wantsInvites()) wanted.add(PushMessage.INVITE);
        if (prefs.wantsCallsLocally()) wanted.add(PushMessage.CALL);
        if (prefs.wantsMessages()) wanted.add(PushMessage.MESSAGE);
        Set<String> muted = prefs.mutedConversations(now);
        PushMessage.Decision decision = message.decide(signedIn, now, prefs.endedCalls(now), wanted, prefs.crewAudible(now), muted);
        switch (decision.action) {
            case SHOW:
                Log.i(TAG, "push shown: " + message.type);
                if (PushMessage.REQUEST.equals(message.type)) {
                    SocialNotifications.showRequest(app, message.sender, message.name);
                } else if (PushMessage.INVITE.equals(message.type)) {
                    SocialNotifications.showInvite(app, message.sender, message.name, message.id, message.where, message.host,
                            decision.timeoutMs);
                } else if (PushMessage.CALL.equals(message.type)) {
                    showCall(app, prefs, message, decision.timeoutMs);
                } else if (PushMessage.MESSAGE.equals(message.type)) {
                    // Over the game it stays in the shade, without a heads-up.
                    boolean silent = SocialRoutes.gameRunning(app);
                    SocialNotifications.showMessage(app, message.conv, message.name, silent);
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

    /** "Ayşe, Gece Kartalları ekibini çağırıyor · mc.example.com sunucusunda", from the crews' names as last known. */
    private static void showCall(Context app, SocialPrefs prefs, PushMessage message, long timeoutMs) {
        Context c = LanguageManager.wrap(app);
        Map<String, String> names = prefs.crewNames();
        List<String> named = new ArrayList<>();
        String first = null;
        for (String crew : message.crews) {
            String name = names.get(crew);
            if (name == null) continue;
            if (first == null) first = crew;
            named.add(name);
        }
        SocialModel.Call.Place place;
        if ("projects".equals(message.where)) place = SocialModel.Call.Place.PROJECTS;
        else if ("other".equals(message.where)) place = SocialModel.Call.Place.OTHER;
        else place = SocialModel.Call.JOIN.equals(message.kind) ? SocialModel.Call.Place.PROJECTS : SocialModel.Call.Place.READY;
        String title = SocialNotifications.callTitle(c, message.name, named, names.size() > 1);
        SocialNotifications.showCall(c, message.id, first, title, SocialNotifications.callPlace(c, place, message.host), timeoutMs);
    }
}
