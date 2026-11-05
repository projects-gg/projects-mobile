package gg.projects.mobile.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.social.SocialNotifications;
import gg.projects.mobile.social.SocialOpenActivity;
import gg.projects.mobile.social.SocialRoutes;
import gg.projects.mobile.ui.AppDates;

/**
 * Voice chat's notifications, in the app's language: the ring ("Kaan_07 arıyor" with Katıl and Reddet, our own ring
 * looping until it ends, no full-screen takeover), the missed call, the stay ("Sesli sohbette" with Mikrofon and Ayrıl:
 * Android needs one for the foreground service anyway) and the word a stay's end leaves while no screen of the app shows.
 */
public final class VoiceNotify {
    private VoiceNotify() {}

    private static final String TAG = "ProjectsVoice";
    /** Rings and missed calls: they pop up and ring (the player tunes or silences them in Android's settings). */
    public static final String CHANNEL_RINGS = "projects_voice_rings";
    /** The stay's own line and its ending's word: no sound, no pop-up. */
    public static final String CHANNEL_STAY = "projects_voice_stay";
    static final int ID_RING = 0x5A000020;
    static final int ID_STAY = 0x5A000021;
    static final int ID_ENDED = 0x5A000022;
    static final int ID_MISSED = 0x5A000023;

    static String ringTag(String room) {
        return "voice_ring:" + room;
    }

    static String missedTag(String from) {
        return "voice_missed:" + from;
    }

    /** Our own ring (res/raw, drawn by {@link VoiceSounds#ring}). */
    public static Uri ringSound(Context context) {
        return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName() + "/" + R.raw.projects_voice_ring);
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        Context c = LanguageManager.wrap(context);
        // The friends group holds them (SocialNotifications makes it).
        SocialNotifications.ensureChannels(context);
        NotificationChannel rings = new NotificationChannel(CHANNEL_RINGS, c.getString(R.string.projects_voice_channel_rings),
                NotificationManager.IMPORTANCE_HIGH);
        rings.setGroup(SocialNotifications.GROUP);
        rings.setSound(ringSound(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        rings.enableVibration(true);
        rings.setVibrationPattern(new long[] {0, 300, 700, 300});
        NotificationChannel stay = new NotificationChannel(CHANNEL_STAY, c.getString(R.string.projects_voice_channel_stay),
                NotificationManager.IMPORTANCE_LOW);
        stay.setGroup(SocialNotifications.GROUP);
        stay.setShowBadge(false);
        manager.createNotificationChannel(rings);
        manager.createNotificationChannel(stay);
    }

    // ---- the ring (the launcher's or the game's process) ----

    /**
     * "Kaan_07 arıyor" with the others in the room under it, Katıl and Reddet; it rings with our own chime until it ends
     * (Android's "insistent" ring, never a full-screen takeover) and goes by itself at the ring's end.
     */
    public static void showRing(Context context, String room, String callerName, @Nullable String others, long timeoutMs,
                                PendingIntent join, PendingIntent decline, PendingIntent open) {
        Context c = LanguageManager.wrap(context);
        if (!SocialNotifications.canNotify(c)) return;
        ensureChannels(c);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_RINGS)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(c.getString(R.string.projects_voice_ring_calling, callerName))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(ringSound(c))
                .setVibrate(new long[] {0, 300, 700, 300})
                .setOngoing(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, c.getString(R.string.projects_voice_decline), decline)
                .addAction(0, c.getString(R.string.projects_voice_join), join);
        if (others != null) b.setContentText(c.getString(R.string.projects_voice_ring_others, others));
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs);
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;
        notify(c, ringTag(room), ID_RING, n);
    }

    public static void cancelRing(Context context, String room) {
        NotificationManagerCompat.from(context).cancel(ringTag(room), ID_RING);
    }

    /** "Cevapsız arama" / "Kaan_07 aradı": quiet (the channel's sound plays once at most), a tap opens the friends. */
    public static void showMissed(Context context, String from, String callerName) {
        Context c = LanguageManager.wrap(context);
        if (!SocialNotifications.canNotify(c)) return;
        ensureChannels(c);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_STAY)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(c.getString(R.string.projects_voice_missed))
                .setContentText(c.getString(R.string.projects_voice_missed_from, callerName))
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(SocialOpenActivity.open(c, SocialRoutes.FRIENDS, null));
        notify(c, missedTag(from), ID_MISSED, b.build());
    }

    public static void cancelMissed(Context context, String from) {
        NotificationManagerCompat.from(context).cancel(missedTag(from), ID_MISSED);
    }

    // ---- the stay (the voice process) ----

    /** "Sesli sohbette" over the room's name (or "Aranıyor…"), with Mikrofon and Ayrıl; a tap opens the room. */
    static Notification stay(Context context, String line, boolean micOff, PendingIntent mic, PendingIntent leave) {
        Context c = LanguageManager.wrap(context);
        ensureChannels(c);
        return new NotificationCompat.Builder(c, CHANNEL_STAY)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(c.getString(R.string.projects_voice_stay_title))
                .setContentText(line)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(SocialOpenActivity.open(c, SocialRoutes.VOICE, null))
                .addAction(0, c.getString(micOff ? R.string.projects_voice_unmute_mic : R.string.projects_voice_mute_mic), mic)
                .addAction(0, c.getString(R.string.projects_voice_leave), leave)
                .build();
    }

    /** The stay ended while no screen of the app shows: one quiet line that goes after a while. */
    static void ended(Context context, String text) {
        Context c = LanguageManager.wrap(context);
        if (!SocialNotifications.canNotify(c)) return;
        ensureChannels(c);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_STAY)
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(c, R.color.projects_gold_500))
                .setContentTitle(c.getString(R.string.projects_voice_title))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setTimeoutAfter(5 * 60_000)
                .setContentIntent(SocialOpenActivity.open(c, SocialRoutes.FRIENDS, null));
        notify(c, null, ID_ENDED, b.build());
    }

    static void cancelEnded(Context context) {
        NotificationManagerCompat.from(context).cancel(ID_ENDED);
    }

    /** A notice's words (the desktop's {@code left.*}, {@code moved}, {@code mutedByMod}, {@code mic.*}). */
    public static String noticeText(Context c, VoiceMachine.Notice.Kind kind, @Nullable VoiceMachine.LeftReason reason,
                                    @Nullable Long until, @Nullable VoiceMachine.Mic mic) {
        switch (kind) {
            case MOVED:
                return c.getString(R.string.projects_voice_moved);
            case MUTED_BY_MODERATOR:
                return c.getString(R.string.projects_voice_muted_by_mod);
            case MIC:
                return c.getString(mic == VoiceMachine.Mic.MISSING ? R.string.projects_voice_mic_missing
                        : R.string.projects_voice_mic_denied);
            case LEFT:
            default:
                if (reason == null) return c.getString(R.string.projects_voice_left_failed);
                switch (reason) {
                    case CLOSED: return c.getString(R.string.projects_voice_left_closed);
                    case ALONE: return c.getString(R.string.projects_voice_left_alone);
                    case NO_ANSWER: return c.getString(R.string.projects_voice_no_answer);
                    case ELSEWHERE: return c.getString(R.string.projects_voice_left_elsewhere);
                    case KICKED: return c.getString(R.string.projects_voice_left_kicked);
                    case CREW: return c.getString(R.string.projects_voice_left_crew);
                    case IDLE: return c.getString(R.string.projects_voice_left_idle);
                    case UNAVAILABLE: return c.getString(R.string.projects_voice_left_unavailable);
                    case RESTRICTED:
                        return until != null && until > 0
                                ? c.getString(R.string.projects_voice_left_restricted_until, AppDates.longDate(c, until * 1000L))
                                : c.getString(R.string.projects_voice_left_restricted);
                    case FAILED:
                    default:
                        return c.getString(R.string.projects_voice_left_failed);
                }
        }
    }

    /** A refusal's words (the desktop's {@code refused.*}); {@code name}: the friend for "şu an aranamıyor". */
    public static String refusalText(Context c, VoiceAnswer answer, @Nullable String name) {
        if (answer.refused == null) return "";
        switch (answer.refused) {
            case OFF: return c.getString(R.string.projects_voice_refused_off);
            case UNREACHABLE:
                return name == null ? c.getString(R.string.projects_voice_unreachable)
                        : c.getString(R.string.projects_voice_refused_unreachable, name);
            case RATE_LIMITED: return c.getString(R.string.projects_voice_refused_rate);
            case ACCOUNT_LIMITED: return c.getString(R.string.projects_voice_refused_daily);
            case STAFF_MUTED:
                return answer.until != null && answer.until > 0
                        ? c.getString(R.string.projects_voice_left_restricted_until, AppDates.longDate(c, answer.until * 1000L))
                        : c.getString(R.string.projects_voice_refused_staff);
            case ROOM_FULL: return c.getString(R.string.projects_voice_refused_full);
            case ROOMS_FULL: return c.getString(R.string.projects_voice_refused_rooms_full);
            case ROOM_GONE: return c.getString(R.string.projects_voice_refused_gone);
            case KICKED: return c.getString(R.string.projects_voice_refused_kicked);
            case NOT_MEMBER: return c.getString(R.string.projects_voice_refused_not_member);
            case NOT_ALLOWED: return c.getString(R.string.projects_voice_refused_not_allowed);
            case NOT_FOUND: return c.getString(R.string.projects_voice_refused_not_found);
            case INVALID_NAME: return c.getString(R.string.projects_voice_refused_invalid_name);
            case NAME_REJECTED: return c.getString(R.string.projects_voice_refused_name_rejected);
            case NOTHING: return "";
            case FAILED:
            default: return c.getString(R.string.projects_voice_refused_failed);
        }
    }

    private static void notify(Context c, @Nullable String tag, int id, Notification n) {
        try {
            NotificationManagerCompat.from(c).notify(tag, id, n);
        } catch (SecurityException e) {
            Log.w(TAG, "notification not shown");
        }
    }

    /** An intent of the voice process ({@code ACTION_MIC}, {@code ACTION_LEAVE}). */
    static PendingIntent serviceAction(Context context, String action) {
        Intent intent = new Intent(context, VoiceService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(context, action.hashCode(), intent, flags);
    }
}
