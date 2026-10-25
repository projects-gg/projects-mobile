package gg.projects.mobile.cloud;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.social.SocialNotificationExpiry;
import gg.projects.mobile.social.SocialNotifications;
import gg.projects.mobile.social.SocialOpenActivity;
import gg.projects.mobile.social.SocialRoutes;
import gg.projects.mobile.social.ui.ActionToast;

/**
 * 7/24 Bulut's system notifications in the app's language (notices carry no text): three channels the player can tune
 * in Android's settings ("Bulut AFK uyarıları" and "AFK+ onay ve anlaşmalar" pop up, "AFK+ bilgi" only lists), one
 * notification per event and account under a tag (an approval under its own, so its end takes it away), and a tap
 * opens the screen that shows it. An approval carries one action, "Reddet": approving only happens on the screen,
 * where the deal and its countdown show (a money decision is never one tap from the notification shade).
 */
public final class CloudNotifications {
    private CloudNotifications() {}

    private static final String TAG = "ProjectsCloud";
    public static final String GROUP = "projects_cloud";
    public static final String CHANNEL_ALERTS = "projects_cloud_alerts";
    public static final String CHANNEL_DEALS = "projects_cloud_deals";
    public static final String CHANNEL_INFO = "projects_cloud_info";

    static final int ID_ALERT = 0x5A000010;
    static final int ID_DEAL = 0x5A000011;
    static final int ID_INFO = 0x5A000012;

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        Context c = LanguageManager.wrap(context);
        manager.createNotificationChannelGroup(new NotificationChannelGroup(GROUP, c.getString(R.string.projects_cloud_channel_group)));
        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS, c.getString(R.string.projects_cloud_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setGroup(GROUP);
        NotificationChannel deals = new NotificationChannel(CHANNEL_DEALS, c.getString(R.string.projects_cloud_channel_deals),
                NotificationManager.IMPORTANCE_HIGH);
        deals.setGroup(GROUP);
        NotificationChannel info = new NotificationChannel(CHANNEL_INFO, c.getString(R.string.projects_cloud_channel_info),
                NotificationManager.IMPORTANCE_DEFAULT);
        info.setGroup(GROUP);
        manager.createNotificationChannel(alerts);
        manager.createNotificationChannel(deals);
        manager.createNotificationChannel(info);
    }

    /** The channel a notice's kind goes to. */
    public static String channelOf(String kind) {
        switch (kind) {
            case CloudNotice.KIND_ALERT: return CHANNEL_ALERTS;
            case CloudNotice.KIND_DEAL: return CHANNEL_DEALS;
            default: return CHANNEL_INFO;
        }
    }

    private static int idOf(String kind) {
        switch (kind) {
            case CloudNotice.KIND_ALERT: return ID_ALERT;
            case CloudNotice.KIND_DEAL: return ID_DEAL;
            default: return ID_INFO;
        }
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

    static String approvalTag(String approval) {
        return "approval:" + approval;
    }

    /** The notice's own tag (a newer one with it replaces or removes it), else one per event and account. */
    private static String tagOf(CloudNotice n) {
        String approval = n.approvalId();
        if (approval != null) return approvalTag(approval);
        if (n.tag != null && !n.tag.isEmpty()) return n.tag;
        return n.event + ":" + (n.account() == null ? "all" : n.account());
    }

    /** Notices already shown by this process (the socket's and the push's copies share an id). */
    private static final java.util.Map<String, Boolean> SHOWN = new java.util.LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
            return size() > 64;
        }
    };

    /** True the first time a notice id is shown here; a copy that comes again (a push after the socket) is not. */
    static synchronized boolean firstShow(String id) {
        return SHOWN.put(id, Boolean.TRUE) == null;
    }

    /** Where a tap on it goes: the approvals, the messages, the account or the list. */
    private static PendingIntent openFor(Context c, CloudNotice n) {
        String acct = n.account();
        if (acct == null) return SocialOpenActivity.open(c, SocialRoutes.CLOUD, null);
        switch (n.event) {
            case "approval": return SocialOpenActivity.open(c, SocialRoutes.CLOUD_APPROVALS, acct);
            case "message": return SocialOpenActivity.open(c, SocialRoutes.CLOUD_INBOX, acct);
            default: return SocialOpenActivity.open(c, SocialRoutes.CLOUD_ACCOUNT, acct);
        }
    }

    /**
     * A system notification on the kind's channel. {@code silent}: the game is on screen, so it waits in the shade
     * without a heads-up or a sound (as the friends' messages do).
     */
    public static void show(Context context, CloudNotice n, boolean silent) {
        Context c = LanguageManager.wrap(context);
        if (!SocialNotifications.canNotify(c)) return;
        String[] words = CloudWords.notice(c, n, new CloudPrefs(c), System.currentTimeMillis() / 1000L);
        if (words == null) return;
        if (!firstShow(n.id)) return;
        ensureChannels(c);
        String channel = channelOf(n.kind);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(words[0])
                .setContentText(words[1])
                .setStyle(new NotificationCompat.BigTextStyle().bigText(words[1]))
                .setCategory("approval".equals(n.event) ? NotificationCompat.CATEGORY_REMINDER : NotificationCompat.CATEGORY_STATUS)
                .setPriority(silent ? NotificationCompat.PRIORITY_LOW : n.high ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT)
                .setSilent(silent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openFor(c, n));
        String tag = tagOf(n);
        String approval = n.approvalId();
        if ("approval".equals(n.event) && approval != null && n.account() != null) {
            builder.addAction(0, c.getString(R.string.projects_cloud_reject), CloudActionReceiver.reject(c, n.account(), approval));
            long expires = n.number("expires", 0);
            long left = expires * 1000L - System.currentTimeMillis();
            if (left > 0) {
                builder.setTimeoutAfter(left);
                if (Build.VERSION.SDK_INT < 26) SocialNotificationExpiry.schedule(c, tag, idOf(n.kind), left);
            }
        }
        try {
            NotificationManagerCompat.from(c).notify(tag, idOf(n.kind), builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "notification not shown", e);
        }
    }

    /**
     * The app is on screen: the notice goes to its own line at the bottom, with "Aç" to the screen it is about. False when
     * nothing showed (no words for it, or this notice already showed here).
     */
    public static boolean showInApp(final Activity activity, final CloudNotice n) {
        String[] words = CloudWords.notice(activity, n, new CloudPrefs(activity), System.currentTimeMillis() / 1000L);
        if (words == null || !firstShow(n.id)) return false;
        String text = "approval".equals(n.event) || "message".equals(n.event) ? words[0] + ": " + words[1] : words[1];
        final String acct = n.account();
        Runnable open = () -> {
            String target = acct == null ? SocialRoutes.CLOUD : "approval".equals(n.event) ? SocialRoutes.CLOUD_APPROVALS
                    : "message".equals(n.event) ? SocialRoutes.CLOUD_INBOX : SocialRoutes.CLOUD_ACCOUNT;
            SocialRoutes.set(target, acct);
            if (activity instanceof androidx.fragment.app.FragmentActivity) SocialRoutes.consume((androidx.fragment.app.FragmentActivity) activity);
        };
        ActionToast.show(activity, text, activity.getString(R.string.projects_cloud_open), open);
        return true;
    }

    public static void cancelApproval(Context context, String approval) {
        NotificationManagerCompat.from(context).cancel(approvalTag(approval), ID_DEAL);
    }

    /** Signed out, or the service off: none of 7/24 Bulut's notifications stays. */
    public static void cancelAll(Context context) {
        if (Build.VERSION.SDK_INT < 23) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            for (StatusBarNotification n : manager.getActiveNotifications()) {
                int id = n.getId();
                if (id == ID_ALERT || id == ID_DEAL || id == ID_INFO) manager.cancel(n.getTag(), id);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "notifications not listed", e);
        }
    }

    /** The push kinds this phone asks for: the channels the player keeps on (the events' switches live on the service). */
    public static java.util.List<String> wantedKinds(Context context) {
        java.util.List<String> kinds = new java.util.ArrayList<>();
        if (channelOn(context, CHANNEL_ALERTS)) kinds.add(CloudNotice.KIND_ALERT);
        if (channelOn(context, CHANNEL_DEALS)) kinds.add(CloudNotice.KIND_DEAL);
        if (channelOn(context, CHANNEL_INFO)) kinds.add(CloudNotice.KIND_INFO);
        return kinds;
    }

    @Nullable
    static String accountOf(@Nullable String tag) {
        if (tag == null) return null;
        int colon = tag.indexOf(':');
        return colon < 0 ? null : tag.substring(colon + 1);
    }
}
