package gg.projects.mobile.cloud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Map;

import gg.projects.mobile.social.SessionStore;
import gg.projects.mobile.social.SocialDebug;
import gg.projects.mobile.social.SocialRoutes;

/**
 * A 7/24 Bulut push ({@code type: "afk"}) from the friends service, on whatever thread it arrives: read with
 * {@link CloudNotice}, decided against the account signed in on this phone, then shown or taken back. It needs nothing
 * of the running app: a push that wakes the app reads the session file itself. Over the game it stays quiet in the
 * shade (no heads-up), as the friends' messages do.
 */
public final class CloudPush {
    private CloudPush() {}

    private static final String TAG = "ProjectsCloud";

    public static void handle(Context context, Map<String, String> data) {
        Context app = context.getApplicationContext();
        CloudNotice notice = CloudNotice.fromPush(data);
        if (notice == null) {
            Log.i(TAG, "cloud push ignored: not a notice of this app's format");
            return;
        }
        if (!new CloudPrefs(app).offerOn()) {
            // The service was off when the app last looked: nothing of it shows.
            Log.i(TAG, "cloud push dropped (" + notice.event + "): the service is off");
            return;
        }
        SocialDebug debug = SocialDebug.read(app);
        SessionStore.Saved saved = new SessionStore(app, debug.override ? "session-debug.json" : "session.json").load();
        String signedIn = saved.token == null ? null : saved.userId;
        CloudNotice.Decision decision = notice.decide(signedIn, System.currentTimeMillis() / 1000L);
        switch (decision.action) {
            case SHOW:
                Log.i(TAG, "cloud push shown: " + notice.event);
                CloudNotifications.show(app, notice);
                break;
            case CANCEL: {
                String approval = notice.approvalId();
                if (approval != null) CloudNotifications.cancelApproval(app, approval);
                break;
            }
            case DROP:
            default:
                Log.i(TAG, "cloud push dropped (" + notice.event + "): " + decision.why);
                return;
        }
        // A running app reads its accounts again (the push may have changed what they show).
        new Handler(Looper.getMainLooper()).post(() -> {
            if (CloudService.exists()) CloudService.get(app).refresh(true);
        });
    }

    /** Whether the game is on screen (a notice then stays in the shade without a heads-up). */
    static boolean gameRunning(Context context) {
        return SocialRoutes.gameRunning(context);
    }
}
