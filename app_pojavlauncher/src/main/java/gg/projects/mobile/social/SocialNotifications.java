package gg.projects.mobile.social;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.language.LanguageManager;

/**
 * The friends feature's system notifications, in the app's language (pushes carry no text; soc6.md):
 * three channels the player can tune in Android's settings ("Davetler" and "Ekip çağrıları" pop up,
 * "Arkadaşlık istekleri" only lists), one notification per sender or call under a tag, so a newer
 * one takes the older one's place without alerting again, and a call's notification goes when the
 * call ends or runs out. A tap opens the screen that shows it.
 */
public final class SocialNotifications {
    private SocialNotifications() {}

    private static final String TAG = "ProjectsSocial";
    public static final String GROUP = "projects_social";
    public static final String CHANNEL_INVITES = "projects_social_invites";
    public static final String CHANNEL_CALLS = "projects_social_calls";
    public static final String CHANNEL_REQUESTS = "projects_social_requests";

    /** Ids apart from upstream's (1 to 5), the reminders' and "Projects hazır"'s; the tag tells them apart. */
    static final int ID_REQUEST = 0x5A000001;
    static final int ID_INVITE = 0x5A000002;
    static final int ID_CALL = 0x5A000003;

    /** Creates (or renames, in the app's language) the channels. */
    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        Context c = LanguageManager.wrap(context);
        manager.createNotificationChannelGroup(new NotificationChannelGroup(GROUP, c.getString(R.string.projects_social_title)));
        NotificationChannel invites = new NotificationChannel(CHANNEL_INVITES, c.getString(R.string.projects_social_channel_invites),
                NotificationManager.IMPORTANCE_HIGH);
        invites.setGroup(GROUP);
        NotificationChannel calls = new NotificationChannel(CHANNEL_CALLS, c.getString(R.string.projects_social_channel_calls),
                NotificationManager.IMPORTANCE_HIGH);
        calls.setGroup(GROUP);
        NotificationChannel requests = new NotificationChannel(CHANNEL_REQUESTS, c.getString(R.string.projects_social_channel_requests),
                NotificationManager.IMPORTANCE_DEFAULT);
        requests.setGroup(GROUP);
        manager.createNotificationChannel(invites);
        manager.createNotificationChannel(calls);
        manager.createNotificationChannel(requests);
    }

    /** Notifications can show at all (Android 13+'s permission and the app's switch). */
    public static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /** The channel is not switched off in Android's settings (a channel not made yet counts as on). */
    public static boolean channelOn(Context context, String channel) {
        if (Build.VERSION.SDK_INT < 26) return true;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel c = manager == null ? null : manager.getNotificationChannel(channel);
        if (c != null && c.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        if (Build.VERSION.SDK_INT >= 28 && manager != null && c != null) {
            NotificationChannelGroup group = manager.getNotificationChannelGroup(GROUP);
            if (group != null && group.isBlocked()) return false;
        }
        return true;
    }

    static String requestTag(String sender) {
        return "request:" + sender;
    }

    static String inviteTag(String sender) {
        return "invite:" + sender;
    }

    static String callTag(String call) {
        return "call:" + call;
    }

    public static void showRequest(Context context, String sender, @Nullable String name) {
        Context c = LanguageManager.wrap(context);
        String who = name == null || name.isEmpty() ? c.getString(R.string.projects_social_someone) : name;
        post(c, CHANNEL_REQUESTS, requestTag(sender), ID_REQUEST,
                c.getString(R.string.projects_social_push_request, who), null,
                NotificationCompat.PRIORITY_DEFAULT, 0, SocialOpenActivity.open(c, SocialRoutes.FRIENDS, null));
    }

    public static void showInvite(Context context, String sender, @Nullable String name, String inviteId,
                                  @Nullable String where, @Nullable String host, long timeoutMs) {
        Context c = LanguageManager.wrap(context);
        String who = name == null || name.isEmpty() ? c.getString(R.string.projects_social_someone) : name;
        String place = "projects".equals(where) ? c.getString(R.string.projects_social_server_projects)
                : host != null && !host.isEmpty() ? host : null;
        post(c, CHANNEL_INVITES, inviteTag(sender), ID_INVITE, c.getString(R.string.projects_social_push_invite, who), place,
                NotificationCompat.PRIORITY_HIGH, timeoutMs, SocialOpenActivity.open(c, SocialRoutes.INVITE, inviteId));
    }

    public static void showCall(Context context, String callId, String caller, @Nullable String name, boolean join, long timeoutMs) {
        Context c = LanguageManager.wrap(context);
        String who = name == null || name.isEmpty() ? c.getString(R.string.projects_crew_someone) : name;
        post(c, CHANNEL_CALLS, callTag(callId), ID_CALL, c.getString(R.string.projects_crew_call_theirs, who),
                c.getString(join ? R.string.projects_social_on_projects : R.string.projects_crew_call_ready),
                NotificationCompat.PRIORITY_HIGH, timeoutMs, SocialOpenActivity.open(c, SocialRoutes.CREW, callId));
    }

    private static void post(Context c, String channel, String tag, int id, String title, @Nullable String text, int priority,
                             long timeoutMs, PendingIntent open) {
        if (!canNotify(c)) return;
        ensureChannels(c);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(title)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setPriority(priority)
                .setAutoCancel(true)
                // A newer one under the same tag takes its place quietly (requests re-sent after a cancel).
                .setOnlyAlertOnce(true)
                .setContentIntent(open);
        if (text != null) builder.setContentText(text);
        if (timeoutMs > 0) builder.setTimeoutAfter(timeoutMs);
        try {
            NotificationManagerCompat.from(c).notify(tag, id, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "notification not shown", e);
        }
    }

    public static void cancelCall(Context context, String callId) {
        NotificationManagerCompat.from(context).cancel(callTag(callId), ID_CALL);
    }

    public static void cancelInvite(Context context, String sender) {
        NotificationManagerCompat.from(context).cancel(inviteTag(sender), ID_INVITE);
    }

    /** Signed out: none of the friends' notifications stays. */
    public static void cancelAll(Context context) {
        cancelMatching(context, true, true, true);
    }

    /**
     * What a screen now shows needs no notification any more: the friends list takes requests and
     * invites, the crew page calls.
     */
    static void cancelSeen(Context context, SocialState state, boolean friends, boolean crew) {
        if (!friends && !crew) return;
        cancelMatching(context, friends, friends, crew);
        if (Build.VERSION.SDK_INT < 23) {
            // No list of what is up: the ones the list knows of.
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            if (friends) {
                for (SocialModel.Request r : state.incoming()) manager.cancel(requestTag(r.profile.id), ID_REQUEST);
                for (SocialModel.Invite i : state.invites(SocialService.nowSeconds())) manager.cancel(inviteTag(i.fromId), ID_INVITE);
            }
            if (crew) for (SocialModel.Call call : state.calls(SocialService.nowSeconds())) manager.cancel(callTag(call.id), ID_CALL);
        }
    }

    private static void cancelMatching(Context context, boolean requests, boolean invites, boolean calls) {
        if (Build.VERSION.SDK_INT < 23) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            for (StatusBarNotification n : manager.getActiveNotifications()) {
                int id = n.getId();
                if ((requests && id == ID_REQUEST) || (invites && id == ID_INVITE) || (calls && id == ID_CALL)) {
                    manager.cancel(n.getTag(), id);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "notifications not listed", e);
        }
    }
}
