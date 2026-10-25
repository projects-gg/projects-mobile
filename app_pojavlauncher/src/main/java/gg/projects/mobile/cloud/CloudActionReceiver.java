package gg.projects.mobile.cloud;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.social.SessionStore;
import gg.projects.mobile.social.SocialDebug;

/**
 * "Reddet" on an approval's notification (not exported: only the app's own notifications send it). It rejects with
 * the session on this phone, even when the app is not running, and takes the notification away; an approval that
 * closed meanwhile goes away too. Approving is never done from here: only on the screen.
 */
public final class CloudActionReceiver extends BroadcastReceiver {
    private static final String TAG = "ProjectsCloud";
    static final String ACTION_REJECT = "gg.projects.mobile.cloud.REJECT";
    private static final String EXTRA_ACCOUNT = "gg.projects.mobile.cloud.ACCOUNT";
    private static final String EXTRA_APPROVAL = "gg.projects.mobile.cloud.APPROVAL";

    static PendingIntent reject(Context context, String account, String approval) {
        Intent intent = new Intent(context, CloudActionReceiver.class)
                .setAction(ACTION_REJECT)
                .putExtra(EXTRA_ACCOUNT, account)
                .putExtra(EXTRA_APPROVAL, approval);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, ("reject|" + approval).hashCode(), intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REJECT.equals(intent.getAction())) return;
        final String account = intent.getStringExtra(EXTRA_ACCOUNT);
        final String approval = intent.getStringExtra(EXTRA_APPROVAL);
        if (account == null || approval == null) return;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                SocialDebug social = SocialDebug.read(app);
                SessionStore.Saved saved = new SessionStore(app, social.override ? "session-debug.json" : "session.json").load();
                if (saved.token == null) return;
                CloudApi api = new CloudApi(CloudDebug.read(app).url, ProjectsContent.userAgent(), line -> Log.i(TAG, line));
                try {
                    api.decide(saved.token, account, approval, false, null);
                    Log.i(TAG, "approval rejected from its notification");
                    CloudNotifications.cancelApproval(app, approval);
                } catch (CloudProblem problem) {
                    Log.i(TAG, "approval not rejected from its notification: " + problem.kind);
                    // Closed meanwhile (answered elsewhere, expired): nothing to decide any more.
                    if (problem.is("approval_closed") || problem.is("approval_not_found")) CloudNotifications.cancelApproval(app, approval);
                }
            } finally {
                pending.finish();
            }
        }, "projects-cloud-reject").start();
        // The shade gives immediate feedback; the notification goes once the service answers.
        NotificationManagerCompat.from(app).cancel(CloudNotifications.approvalTag(approval), CloudNotifications.ID_DEAL);
    }
}
