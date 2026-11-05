package gg.projects.mobile.voice.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.voice.VoiceActionActivity;
import gg.projects.mobile.voice.VoiceAnswer;
import gg.projects.mobile.voice.VoiceHub;
import gg.projects.mobile.voice.VoiceLink;
import gg.projects.mobile.voice.VoiceMachine;
import gg.projects.mobile.voice.VoiceNotify;
import gg.projects.mobile.voice.VoiceState;
import gg.projects.mobile.voice.VoiceWire;

/**
 * Voice chat's flows as every surface starts them (the friend card, the person menu, the crew page, the call bar): the
 * call with its question ("{name} aransın mı?", the desktop's VOICE-CALL; "Aramadan önce sor" turns it off), the
 * microphone asked for first (a "no" joins to listen), and each refusal in the desktop's words.
 */
public final class VoiceUi {
    private VoiceUi() {}

    /** The hub, when voice chat may show at all (null: nothing of it shows). */
    @Nullable
    public static VoiceHub hub(Context context) {
        if (!SocialService.exists()) SocialService.get(context);
        VoiceHub hub = VoiceHub.instance();
        return hub != null && hub.available() ? hub : null;
    }

    /** What a friend's voice action is: call them, invite them into the player's friend room, or none. */
    public enum FriendAction { NONE, CALL, CALL_OFF, INVITE, TOGETHER }

    public static FriendAction friendAction(Context context, String friend) {
        VoiceHub hub = hub(context);
        if (hub == null || !hub.prefs().enabled()) return FriendAction.NONE;
        VoiceState state = VoiceLink.get(context).state();
        if (state != null && state.person(friend) != null) return FriendAction.TOGETHER;
        boolean callable = hub.callable(friend);
        if (state != null && !state.crewRoom && state.live()) return callable ? FriendAction.INVITE : FriendAction.CALL_OFF;
        return callable ? FriendAction.CALL : FriendAction.CALL_OFF;
    }

    /** "Sesli arama başlat" or "Odaya davet et" for {@code friend}, as {@link #friendAction} says. */
    public static void friend(final FragmentActivity activity, final String friend, final String name) {
        switch (friendAction(activity, friend)) {
            case CALL:
                call(activity, friend, name);
                break;
            case INVITE:
                invite(activity, friend, name);
                break;
            case TOGETHER:
                SocialUi.toast(activity, activity.getString(R.string.projects_voice_together));
                break;
            case CALL_OFF:
                SocialUi.toast(activity, activity.getString(R.string.projects_voice_unreachable));
                break;
            default:
                break;
        }
    }

    /** The call: asked first (unless the player turned that off), then the microphone, then the ring. */
    public static void call(final FragmentActivity activity, final String friend, final String name) {
        final VoiceHub hub = hub(activity);
        if (hub == null) return;
        Runnable go = () -> withMic(activity, () -> hub.call(friend, answer -> {
            if (!answer.ok()) refused(activity, answer, name);
        }));
        if (!hub.prefs().askBeforeCall()) {
            go.run();
            return;
        }
        boolean dnd = SocialService.get(activity).state().presence(friend).dnd;
        SocialUi.confirm(activity, activity.getString(R.string.projects_voice_ask, name),
                dnd ? activity.getString(R.string.projects_voice_ask_dnd) : null, activity.getString(R.string.projects_voice_ask_yes), go);
    }

    /** "Odaya davet et": the room rings them (30 s), they may join for 10 minutes. */
    public static void invite(final Activity activity, final String person, final String name) {
        VoiceLink.get(activity).invite(person, answer -> {
            if (!answer.ok()) refused(activity, answer, name);
        });
    }

    /** "Katıl" on a ring or a missed call. */
    public static void answer(final FragmentActivity activity, final String room) {
        final VoiceHub hub = hub(activity);
        if (hub == null) return;
        withMic(activity, () -> hub.answer(room, answer -> {
            if (!answer.ok()) refused(activity, answer, null);
        }));
    }

    /** "Katıl" on a crew room ({@code room} null: its "Genel"). */
    public static void joinCrew(final FragmentActivity activity, final String crew, @Nullable final String room) {
        final VoiceHub hub = hub(activity);
        if (hub == null) return;
        withMic(activity, () -> hub.joinCrew(crew, room, answer -> {
            if (!answer.ok()) refused(activity, answer, null);
        }));
    }

    /** "Yeni oda". */
    public static void newCrewRoom(final FragmentActivity activity, final String crew) {
        final VoiceHub hub = hub(activity);
        if (hub == null) return;
        withMic(activity, () -> hub.newCrewRoom(crew, answer -> {
            if (!answer.ok()) refused(activity, answer, null);
        }));
    }

    /** Runs {@code go} once the microphone was asked for (whatever the answer: a "no" joins to listen). */
    public static void withMic(Activity activity, Runnable go) {
        if (VoiceLink.micAllowed(activity)) {
            go.run();
            return;
        }
        VoiceActionActivity.askMicThen(activity, go);
    }

    /** A refusal in the desktop's words, in the app's line. */
    public static void refused(@Nullable Context context, VoiceAnswer answer, @Nullable String name) {
        if (context == null || answer.ok()) return;
        String text = VoiceNotify.refusalText(context, answer, name);
        if (!text.isEmpty()) SocialUi.toastLong(context, text);
    }

    /** A notice from the stay ("Odada kimse kalmadı.", "Bir moderatör seni susturdu." …); "Tekrar ara" after no answer. */
    public static void notice(final FragmentActivity activity, VoiceLink.Notice notice) {
        if (notice.kind == VoiceMachine.Notice.Kind.LEFT && notice.reason == VoiceMachine.LeftReason.NO_ANSWER
                && notice.friend != null) {
            final String friend = notice.friend;
            final String name = nameOf(activity, friend);
            SocialUi.toastAction(activity, activity.getString(R.string.projects_voice_no_answer),
                    activity.getString(R.string.projects_voice_call_again), () -> {
                        VoiceHub hub = hub(activity);
                        // "Tekrar ara" is the call just asked for again: no second question (the desktop's rule).
                        if (hub != null) withMic(activity, () -> hub.call(friend, a -> refused(activity, a, name)));
                    });
            return;
        }
        if (notice.kind == VoiceMachine.Notice.Kind.MIC && notice.mic == VoiceMachine.Mic.DENIED) {
            SocialUi.toastAction(activity, activity.getString(R.string.projects_voice_mic_denied),
                    activity.getString(R.string.projects_voice_mic_settings), () -> openAppSettings(activity));
            return;
        }
        String text = VoiceNotify.noticeText(activity, notice.kind, notice.reason, notice.until, notice.mic);
        SocialUi.toastLong(activity, text);
    }

    public static void openAppSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.getPackageName(), null)));
        } catch (RuntimeException ignored) {
            // No settings screen on this device.
        }
    }

    /** Someone's name as the friends feature knows it, or "Bir oyuncu". */
    public static String nameOf(Context context, String id) {
        VoiceHub hub = VoiceHub.instance();
        if (hub != null) return hub.nameOr(id);
        return context.getString(R.string.projects_voice_someone);
    }

    /** A person's name in a room: the friends feature's, else the room's card. */
    public static String nameOf(Context context, VoiceState.Person person) {
        VoiceHub hub = VoiceHub.instance();
        String known = hub == null ? null : SocialService.get(context).state().profile(person.id) == null ? null : hub.nameOr(person.id);
        if (known != null) return known;
        if (person.cardName != null) return person.cardName;
        return nameOf(context, person.id);
    }

    /** The room's name as its surfaces show it: the friend for a two-person call, the crew room's name, or "Sesli sohbet". */
    public static String roomTitle(Context context, VoiceState state) {
        if (state.crewRoom) {
            String name = state.name == null ? "Genel" : state.name;
            return VoiceHub.roomName(context, name, state.general);
        }
        if (state.name != null) return state.name;
        if (state.calling != null) return nameOf(context, state.calling);
        if (state.people.size() == 1) return nameOf(context, state.people.get(0));
        if (state.people.isEmpty()) return context.getString(R.string.projects_voice_call_kind);
        String first = nameOf(context, state.people.get(0));
        if (state.people.size() == 2) return first + ", " + nameOf(context, state.people.get(1));
        return context.getString(R.string.projects_voice_names_more, first, state.people.size() - 1);
    }

    /** The line under it: "Aranıyor…", "Yeniden bağlanıyor…", "Bağlanıyor…", the call's time, or "N kişi". */
    public static String roomLine(Context context, VoiceState state, long nowUnix) {
        if (state.reconnecting()) return context.getString(R.string.projects_voice_reconnecting);
        if (VoiceState.JOINING.equals(state.phase)) return context.getString(R.string.projects_voice_joining);
        if (state.calling != null) return context.getString(R.string.projects_voice_ringing);
        if (state.oneToOne() && state.togetherSince != null) return clock(Math.max(0, nowUnix - state.togetherSince));
        int count = state.people.size() + 1;
        return context.getResources().getQuantityString(R.plurals.projects_voice_count, count, count);
    }

    /** A call's time: "04:18", "1:02:09". */
    public static String clock(long seconds) {
        long h = seconds / 3600;
        long m = (seconds / 60) % 60;
        long s = seconds % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
    }

    /** A ring's friend room is the player's friend's (true) or a crew's. */
    public static boolean friendRoom(String room) {
        return VoiceWire.isFriendRoom(room);
    }
}
