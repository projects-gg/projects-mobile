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
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;

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
            case CREW_LIMIT: {
                int limit = SocialService.get(context).state().crewLimit();
                if (limit > 0) return context.getResources().getQuantityString(R.plurals.projects_crews_limit, limit, limit);
                id = R.string.projects_social_error_crew_limit;
                break;
            }
            case CREWS_CHANGED: id = R.string.projects_crews_changed; break;
            case CALL_NEEDS_SERVER: id = R.string.projects_social_error_call_needs_server; break;
            case CALL_SERVER_HIDDEN: id = R.string.projects_social_error_call_server_hidden; break;
            case ACCOUNT_LIMITED: return limitedText(context, problem);
            case ACCOUNT_RESTRICTED: return restrictedText(context, problem.until == null ? 0 : problem.until);
            case SIGNUPS_CLOSED: id = R.string.projects_social_signups_closed; break;
            case TEXT_LOCKED: id = R.string.projects_social_error_text_locked; break;
            case TARGET_GONE: id = R.string.projects_report_gone; break;
            case BLOCKS_FULL: id = R.string.projects_social_error_blocks_full; break;
            case CHAT_OFF: id = R.string.projects_chat_off; break;
            case CHAT_MUTED:
                if (problem.until != null) return context.getString(R.string.projects_chat_muted_until, moment(context, problem.until));
                id = R.string.projects_chat_not_sent;
                break;
            case CHAT_REJECTED:
                id = "link".equals(problem.reason) ? R.string.projects_chat_link
                        : "phone".equals(problem.reason) ? R.string.projects_chat_phone
                        : "words".equals(problem.reason) || "invalid".equals(problem.reason) ? R.string.projects_chat_words
                        : R.string.projects_chat_not_sent;
                break;
            case MESSAGE_GONE: id = R.string.projects_chat_gone; break;
            case CHAT_NOT_PERMITTED: id = R.string.projects_chat_not_allowed; break;
            case CHAT_TOO_OLD: id = R.string.projects_chat_too_old; break;
            case APPROVAL_REQUIRED: id = R.string.projects_social_error_approval_required; break;
            case JOIN_REQUEST_GONE: id = R.string.projects_social_error_join_request_gone; break;
            case CREW_REQUESTS_FULL: id = R.string.projects_social_error_crew_requests_full; break;
            case TOO_MANY_JOIN_REQUESTS: id = R.string.projects_social_error_too_many_join_requests; break;
            case PLAN_TIME: id = R.string.projects_social_error_plan_time; break;
            case PLANS_FULL: id = R.string.projects_plans_full; break;
            case PLAN_EXISTS: id = R.string.projects_social_error_plan_exists; break;
            case PLAN_GONE: id = R.string.projects_social_error_plan_gone; break;
            case TEAM_HIDDEN: id = R.string.projects_badge_hidden_why; break;
            case BADGES_OFF: id = R.string.projects_badge_error_off; break;
            case BADGE_REQUEST_CLOSED: id = R.string.projects_badge_refused_closed; break;
            case BADGE_REQUESTS_TODAY: id = R.string.projects_badge_refused_today; break;
            case CHAT_REACTIONS_FULL:
                id = "kinds".equals(problem.reason) ? R.string.projects_reactions_kinds : R.string.projects_reactions_full;
                break;
            case CHAT_FEATURE_OFF: id = R.string.projects_chat2_feature_off; break;
            case FAILED:
            default: id = R.string.projects_social_error_failed; break;
        }
        return context.getString(id);
    }

    /**
     * SPAM1: what a new Discord account may not do yet, where it happened ("Discord hesabın yeni olduğu
     * için ekip kuramazsın. 3 gün sonra açılır."); a daily cap says "bugün".
     */
    public static String limitedText(Context context, SocialProblem problem) {
        String item = problem.item == null ? "" : problem.item;
        long now = SocialService.nowSeconds();
        switch (item) {
            case gg.projects.mobile.social.SocialModel.Limited.CREW_FOUND: {
                int days = Math.max(1, gg.projects.mobile.social.SocialModel.Limited.daysLeft(problem.until == null ? now + 86_400 : problem.until, now));
                return context.getResources().getQuantityString(R.plurals.projects_limited_no_crew, days, days);
            }
            case gg.projects.mobile.social.SocialModel.Limited.CREW_INVITES:
                return context.getString(R.string.projects_limited_invites_today);
            case gg.projects.mobile.social.SocialModel.Limited.CODE_LOOKUPS:
                return context.getString(R.string.projects_limited_lookups_today);
            case gg.projects.mobile.social.SocialModel.Limited.CREW_LINK: {
                int days = Math.max(1, gg.projects.mobile.social.SocialModel.Limited.daysLeft(problem.until == null ? now + 86_400 : problem.until, now));
                return context.getResources().getQuantityString(R.plurals.projects_limited_no_link, days, days);
            }
            case gg.projects.mobile.social.SocialModel.Limited.CREW_PLANS: {
                int days = Math.max(1, gg.projects.mobile.social.SocialModel.Limited.daysLeft(problem.until == null ? now + 86_400 : problem.until, now));
                return context.getResources().getQuantityString(R.plurals.projects_limited_no_plans, days, days);
            }
            default:
                return context.getString(R.string.projects_limited_generic);
        }
    }

    /** MOD1: "Hesabın 24 Ekim tarihine kadar kısıtlandı." or "Hesabın kalıcı olarak kısıtlandı." (the desktop's dates) */
    public static String restrictedText(Context context, long until) {
        if (until <= 0) return context.getString(R.string.projects_mod_restricted_forever);
        return context.getString(R.string.projects_mod_restricted_until, day(context, until));
    }

    /** A day as the desktop writes it: "28 Ekim" (the year only when it is another one), for limits counted in days. */
    public static String day(Context context, long seconds) {
        return android.text.format.DateUtils.formatDateTime(context, seconds * 1000L, android.text.format.DateUtils.FORMAT_SHOW_DATE);
    }

    /** A moment as the phone writes it: "22 Ekim 2026 21:30" (the date in the phone's language, then the time). */
    public static String moment(Context context, long seconds) {
        java.util.Date date = new java.util.Date(seconds * 1000L);
        java.text.DateFormat day = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, java.util.Locale.getDefault());
        return day.format(date) + " " + android.text.format.DateFormat.getTimeFormat(context).format(date);
    }

    /** The community rules ("Topluluk kuralları"), in the browser. */
    public static void openRules(Activity activity) {
        gg.projects.mobile.ui.ProjectsUi.openUrl(activity, activity.getString(R.string.projects_mod_rules_url));
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
     * "Katıl" (a friend on Projects, an invite, a crew call): FIX2's "Bağlan" question, then the Projects
     * profile through its join; with the game open, the game closes and comes back connecting
     * ({@link ConnectFlow}).
     */
    public static void joinProjects(final FragmentActivity activity) {
        ConnectFlow.projects(activity);
    }

    public static SocialService service(Context context) {
        return SocialService.get(context);
    }
}
