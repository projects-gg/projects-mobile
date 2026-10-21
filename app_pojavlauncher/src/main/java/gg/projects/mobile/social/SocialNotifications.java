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
    /** M20: direct messages ("Mesajlar"; never their text: "Ayşe sana mesaj gönderdi"). */
    public static final String CHANNEL_MESSAGES = "projects_social_messages";

    /** Ids apart from upstream's (1 to 5), the reminders' and "Projects hazır"'s; the tag tells them apart. */
    static final int ID_REQUEST = 0x5A000001;
    static final int ID_INVITE = 0x5A000002;
    static final int ID_CALL = 0x5A000003;
    static final int ID_MESSAGE = 0x5A000004;

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
        NotificationChannel messages = new NotificationChannel(CHANNEL_MESSAGES, c.getString(R.string.projects_chat_channel),
                NotificationManager.IMPORTANCE_HIGH);
        messages.setGroup(GROUP);
        manager.createNotificationChannel(invites);
        manager.createNotificationChannel(calls);
        manager.createNotificationChannel(requests);
        manager.createNotificationChannel(messages);
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

    static String messageTag(String conv) {
        return "message:" + conv;
    }

    public static void showRequest(Context context, String sender, @Nullable String name) {
        Context c = LanguageManager.wrap(context);
        // Not a friend yet: "Biri" ("Someone"), not "Bir arkadaşın".
        String who = name == null || name.isEmpty() ? c.getString(R.string.projects_social_someone_unknown) : name;
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
        showCall(c, callId, null, callTitle(c, name, java.util.Collections.<String>emptyList(), false),
                c.getString(join ? R.string.projects_social_on_projects : R.string.projects_crew_call_ready), timeoutMs);
    }

    /**
     * M20: a crew call naming its crews and saying where it is ({@link #callTitle}, {@link #callPlace});
     * a tap opens the first crew it names.
     */
    public static void showCall(Context context, String callId, @Nullable String crewId, String title, String place, long timeoutMs) {
        Context c = LanguageManager.wrap(context);
        post(c, CHANNEL_CALLS, callTag(callId), ID_CALL, title, place, NotificationCompat.PRIORITY_HIGH, timeoutMs,
                SocialOpenActivity.open(c, SocialRoutes.CREW, crewId != null ? crewId : callId));
    }

    /**
     * "Ayşe ekibi çağırıyor" (one crew, or the crew's own page); for a player in several crews "Ayşe, Gece
     * Kartalları ekibini çağırıyor", "…, X ve Y ekiplerini çağırıyor" or "Ayşe 3 ekibi çağırıyor".
     */
    public static String callTitle(Context c, @Nullable String name, java.util.List<String> crewNames, boolean severalCrews) {
        String who = name == null || name.isEmpty() ? c.getString(R.string.projects_crew_someone) : name;
        if (!severalCrews || crewNames.isEmpty()) return c.getString(R.string.projects_crew_call_theirs, who);
        if (crewNames.size() == 1) return c.getString(R.string.projects_crews_call_one, who, crewNames.get(0));
        if (crewNames.size() == 2) return c.getString(R.string.projects_crews_call_two, who, crewNames.get(0), crewNames.get(1));
        return c.getResources().getQuantityString(R.plurals.projects_crews_call_many, crewNames.size(), who, crewNames.size());
    }

    /** "Projects'te", "mc.example.com sunucusunda" ("Başka sunucuda" without its address) or "Hazır mısın?". */
    public static String callPlace(Context c, SocialModel.Call.Place place, @Nullable String host) {
        switch (place) {
            case PROJECTS: return c.getString(R.string.projects_social_on_projects);
            case OTHER:
                return host != null && !host.isEmpty() ? c.getString(R.string.projects_social_presence_other_host, host)
                        : c.getString(R.string.projects_social_presence_other);
            case READY:
            default: return c.getString(R.string.projects_crew_call_ready);
        }
    }

    /** M20: "Ayşe sana mesaj gönderdi" (never the text); a tap opens the conversation. Silent for a muted one. */
    public static void showMessage(Context context, String conv, @Nullable String name, boolean silent) {
        Context c = LanguageManager.wrap(context);
        String who = name == null || name.isEmpty() ? c.getString(R.string.projects_social_someone) : name;
        if (!canNotify(c)) return;
        ensureChannels(c);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(c, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(c.getString(R.string.projects_chat_push, who))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(silent ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setSilent(silent)
                .setContentIntent(SocialOpenActivity.open(c, SocialRoutes.CHAT, conv));
        try {
            NotificationManagerCompat.from(c).notify(messageTag(conv), ID_MESSAGE, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "notification not shown", e);
        }
    }

    public static void cancelMessage(Context context, String conv) {
        NotificationManagerCompat.from(context).cancel(messageTag(conv), ID_MESSAGE);
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
        if (timeoutMs > 0) {
            builder.setTimeoutAfter(timeoutMs);
            // Before Android 8.0 the timeout does nothing: an alarm takes it away at its end.
            if (Build.VERSION.SDK_INT < 26) SocialNotificationExpiry.schedule(c, tag, id, timeoutMs);
        }
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
        if (Build.VERSION.SDK_INT < 23) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            for (StatusBarNotification n : manager.getActiveNotifications()) {
                if (n.getId() == ID_MESSAGE) manager.cancel(n.getTag(), n.getId());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "notifications not listed", e);
        }
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
