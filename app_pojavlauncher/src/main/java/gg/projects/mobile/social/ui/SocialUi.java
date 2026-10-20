package gg.projects.mobile.social.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import gg.projects.mobile.social.SocialModel.Presence;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialRoutes;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.ProjectsHomeCard;

/** Shared pieces of the friends screens: texts for presence and problems, the join question. */
public final class SocialUi {
    private SocialUi() {}

    /**
     * Leaves a friends screen (as back does), safely from {@code onResume} or an answer that comes late:
     * nothing while the activity saves its state, and asking twice pops it once (the screens are opened
     * with {@link Tools#swapFragment}, whose back stack entry is the class name).
     */
    public static void leave(Fragment fragment) {
        if (!fragment.isAdded() || fragment.isRemoving()) return;
        FragmentManager manager = fragment.getParentFragmentManager();
        if (manager.isStateSaved()) return;
        manager.popBackStack(fragment.getClass().getName(), FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** Opens a screen from a fragment that is on screen now (an answer that comes after the player left opens nothing). */
    public static void open(Fragment from, Class<? extends Fragment> screen, String tag) {
        open(from, screen, tag, null);
    }

    public static void open(Fragment from, Class<? extends Fragment> screen, String tag, @Nullable android.os.Bundle args) {
        if (!from.isAdded() || !from.isResumed() || from.getParentFragmentManager().isStateSaved()) return;
        Tools.swapFragment(from.requireActivity(), screen, tag, args);
    }

    /** Where a friend or crewmate is, in a few words. */
    public static String presenceText(Context context, Presence presence) {
        if (presence.playing()) {
            if (presence.onProjects()) return context.getString(R.string.projects_social_on_projects);
            if ("singleplayer".equals(presence.where)) return context.getString(R.string.projects_social_presence_singleplayer);
            if ("other".equals(presence.where)) {
                return presence.host != null && !presence.host.isEmpty()
                        ? context.getString(R.string.projects_social_presence_other_host, presence.host)
                        : context.getString(R.string.projects_social_presence_other);
            }
            return context.getString(R.string.projects_social_presence_playing);
        }
        if (presence.online()) {
            return context.getString(presence.dnd ? R.string.projects_social_presence_dnd : R.string.projects_social_presence_launcher);
        }
        String seen = lastSeenText(context, presence.lastSeen);
        return seen != null ? seen : context.getString(R.string.projects_social_presence_offline);
    }

    @Nullable
    public static String lastSeenText(Context context, @Nullable String lastSeen) {
        if (lastSeen == null) return null;
        switch (lastSeen) {
            case "today": return context.getString(R.string.projects_social_seen_today);
            case "yesterday": return context.getString(R.string.projects_social_seen_yesterday);
            case "week": return context.getString(R.string.projects_social_seen_week);
            case "long": return context.getString(R.string.projects_social_seen_long);
            default: return null;
        }
    }

    public static AvatarView.Dot dot(Presence presence) {
        if (!presence.online()) return AvatarView.Dot.NONE;
        if (presence.dnd) return AvatarView.Dot.DND;
        return presence.playing() ? AvatarView.Dot.PLAYING : AvatarView.Dot.LAUNCHER;
    }

    public static AvatarView.Dot statusDot(String status) {
        switch (status) {
            case "dnd": return AvatarView.Dot.DND;
            case "invisible": return AvatarView.Dot.INVISIBLE;
            default: return AvatarView.Dot.LAUNCHER;
        }
    }

    public static int statusLabel(String status) {
        switch (status) {
            case "dnd": return R.string.projects_social_status_dnd;
            case "invisible": return R.string.projects_social_status_invisible;
            default: return R.string.projects_social_status_online;
        }
    }

    /** The player's words for what went wrong (codes never shown). */
    public static String problemText(Context context, SocialProblem problem) {
        int id;
        switch (problem.kind) {
            case UNAVAILABLE: id = R.string.projects_social_error_unavailable; break;
            case SIGNED_OUT: id = R.string.projects_social_error_signed_out; break;
            case RATE_LIMITED: id = R.string.projects_social_error_rate_limited; break;
            case NOT_A_CODE: id = R.string.projects_social_error_not_a_code; break;
            case NO_ONE_WITH_CODE: id = R.string.projects_social_error_no_one; break;
            case OWN_CODE: id = R.string.projects_social_error_own_code; break;
            case ALREADY_FRIENDS: id = R.string.projects_social_error_already_friends; break;
            case ALREADY_REQUESTED: id = R.string.projects_social_error_already_requested; break;
            case YOU_BLOCKED: id = R.string.projects_social_error_you_blocked; break;
            case LIST_FULL: id = R.string.projects_social_error_list_full; break;
            case THEIR_LIST_FULL: id = R.string.projects_social_error_their_list_full; break;
            case TOO_MANY_REQUESTS: id = R.string.projects_social_error_too_many_requests; break;
            case THEY_HAVE_TOO_MANY_REQUESTS: id = R.string.projects_social_error_they_have_too_many; break;
            case REQUEST_GONE: id = R.string.projects_social_error_request_gone; break;
            case FRIEND_GONE: id = R.string.projects_social_error_friend_gone; break;
            case NOT_A_CREW_CODE: id = R.string.projects_crew_not_code; break;
            case NO_CREW_WITH_CODE: id = R.string.projects_social_error_no_crew; break;
            case CREW_INVITE_GONE: id = R.string.projects_social_error_crew_invite_gone; break;
            case MEMBER_GONE: id = R.string.projects_social_error_member_gone; break;
            case ALREADY_IN_CREW: id = R.string.projects_social_error_already_in_crew; break;
            case ALREADY_MEMBER: id = R.string.projects_social_error_already_member; break;
            case CREW_FULL: id = R.string.projects_social_error_crew_full; break;
            case LEADER_ONLY: id = R.string.projects_social_error_leader_only; break;
            case NOT_PERMITTED: id = R.string.projects_social_error_not_permitted; break;
            case HAND_OVER_FIRST: id = R.string.projects_social_error_hand_over_first; break;
            case BLOCKED_SOMEONE_IN_CREW: id = R.string.projects_social_error_blocked_in_crew; break;
            case CREW_INVITES_FULL: id = R.string.projects_social_error_crew_invites_full; break;
            case CREW_NAME: id = R.string.projects_social_error_crew_name; break;
            case CREW_NAME_REJECTED: id = R.string.projects_social_error_crew_name_rejected; break;
            case CREW_TAG: id = R.string.projects_social_error_crew_tag; break;
            case CREW_MOTTO: id = R.string.projects_social_error_crew_motto; break;
            case CREW_MOTTO_REJECTED: id = R.string.projects_social_error_crew_motto_rejected; break;
            case CREW_TEXT_TODAY: id = R.string.projects_social_error_crew_text_today; break;
            case CALL_ACTIVE: id = R.string.projects_social_error_call_active; break;
            case CALL_ENDED: id = R.string.projects_social_error_call_ended; break;
            case NOT_IN_CREW: id = R.string.projects_social_error_not_in_crew; break;
            case FAILED:
            default: id = R.string.projects_social_error_failed; break;
        }
        return context.getString(id);
    }

    public static void toast(@Nullable Context context, String text) {
        if (context != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    public static void toastProblem(@Nullable Context context, SocialProblem problem, @Nullable View feedback) {
        if (context == null) return;
        if (feedback != null) Haptics.reject(feedback);
        Toast.makeText(context, problemText(context, problem), Toast.LENGTH_LONG).show();
    }

    /** A question with one consequential choice ({@code confirm}) and "Vazgeç". */
    public static void confirm(Activity activity, @Nullable String title, @Nullable String message, String confirm,
                               final Runnable onConfirm) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        if (title != null) builder.setTitle(title);
        if (message != null) builder.setMessage(message);
        builder.setPositiveButton(confirm, (d, w) -> onConfirm.run())
                .setNegativeButton(R.string.projects_social_cancel, null)
                .show();
    }

    public static void copy(Context context, String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    public static void share(Activity activity, String text) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        try {
            activity.startActivity(Intent.createChooser(send, null));
        } catch (RuntimeException e) {
            copy(activity, "Projects", text);
            toast(activity, activity.getString(R.string.projects_social_copied));
        }
    }

    /** "Discord'dan mesaj at": the friend's Discord profile (the Discord app opens it when installed). */
    public static void openDiscord(Activity activity, Profile profile) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(profile.discordProfileUrl())).addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            toast(activity, activity.getString(R.string.projects_link_no_app));
        }
    }

    /**
     * "Katıl" (a friend on Projects, an invite, a crew call): the game is this app, so it asks first;
     * "Evet" goes home and starts Projects through "Projects'e katıl" (its checks: an account, a start
     * already running, the notification question once). While the game runs it is never closed: the
     * player joins from the game's own server list.
     */
    public static void joinProjects(final FragmentActivity activity) {
        if (SocialRoutes.gameRunning(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.projects_social_game_open_title)
                    .setMessage(R.string.projects_social_game_open_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_social_join_title)
                .setMessage(R.string.projects_social_join_body)
                .setPositiveButton(R.string.projects_social_yes, (d, w) -> {
                    ProjectsHomeCard.requestJoin();
                    Tools.backToMainMenu(activity);
                })
                .setNegativeButton(R.string.projects_social_no, null)
                .show();
    }

    /** An invite to another server: the phone joins it from the game's own list. */
    public static void otherServer(final Activity activity, final String host) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_social_invited_you)
                .setMessage(activity.getString(R.string.projects_social_invite_other_body, host))
                .setPositiveButton(R.string.projects_social_copy_address, (d, w) -> {
                    copy(activity, "Projects", host);
                    toast(activity, activity.getString(R.string.projects_social_address_copied));
                })
                .setNegativeButton(R.string.projects_close, null)
                .show();
    }

    public static SocialService service(Context context) {
        return SocialService.get(context);
    }
}
