package gg.projects.mobile.social.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.List;

import gg.projects.mobile.social.HomeLine;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Invite;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.CardMotion;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Press;

/**
 * The main menu's "Arkadaşlar" row (M19): shown while Friends are on, it says the most useful thing
 * in one line ({@link HomeLine}) and offers "Katıl" when joining is one tap away. A change of what it
 * says cross-fades in place; the row coming or going glides the menu below.
 */
public final class SocialHomeRow implements SocialService.Listener {
    private final Fragment mFragment;
    private final ViewGroup mSceneRoot;
    private final View mRow;
    private final ImageView mIcon;
    private final LinearLayout mFaces;
    private final TextView mTitle;
    private final TextView mLine;
    private final Button mAction;
    private final View mDot;
    private String mShown = "";
    private final Runnable mTick = this::render;

    public SocialHomeRow(Fragment fragment, View root) {
        mFragment = fragment;
        mRow = root.findViewById(R.id.projects_social_row);
        mSceneRoot = (ViewGroup) mRow.getParent();
        mIcon = mRow.findViewById(R.id.projects_social_row_icon);
        mFaces = mRow.findViewById(R.id.projects_social_row_faces);
        mTitle = mRow.findViewById(R.id.projects_social_row_title);
        mLine = mRow.findViewById(R.id.projects_social_row_line);
        mAction = mRow.findViewById(R.id.projects_social_row_action);
        mDot = mRow.findViewById(R.id.projects_social_row_dot);
        Context context = root.getContext();
        mAction.setBackground(ChamferDrawable.outlined(context));
        Press.attach(mAction, Press.Style.BUTTON);
        Press.attach(mRow, Press.Style.CARD);
        mRow.setOnClickListener(v -> openFriends());
        mRow.setVisibility(SocialService.get(context).enabled() ? View.VISIBLE : View.GONE);
    }

    public void onResume() {
        SocialService service = SocialService.get(mFragment.requireContext());
        service.addListener(this);
        service.surfaceShown(SocialService.Surface.HOME, true);
        render();
    }

    public void onPause() {
        SocialService service = SocialService.get(mFragment.requireContext());
        service.removeListener(this);
        service.surfaceShown(SocialService.Surface.HOME, false);
        mRow.removeCallbacks(mTick);
    }

    @Override
    public void onSocialChanged() {
        render();
    }

    private void openFriends() {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null) return;
        HomeLine line = SocialService.get(activity).homeLine();
        android.os.Bundle args = new android.os.Bundle();
        if (line.kind == HomeLine.Kind.CALL || line.kind == HomeLine.Kind.CREW_ON_PROJECTS || line.kind == HomeLine.Kind.PLAN
                || line.kind == HomeLine.Kind.JOIN_REQUEST) {
            // M20: the crew the call names first, or the one with the most players on Projects; M21: the plan's
            // crew, the crew a join request waits in.
            if (line.crew != null) args.putString(CrewFragment.ARG_CREW, line.crew.id());
            Tools.swapFragment(activity, CrewFragment.class, CrewFragment.TAG, args);
        } else if (line.kind == HomeLine.Kind.MESSAGE && line.unread != null) {
            // M20: the conversation itself, or the list when several friends wrote.
            if (line.unread.conversations > 1) {
                Tools.swapFragment(activity, ChatListFragment.class, ChatListFragment.TAG, null);
            } else {
                args.putString(ChatFragment.ARG_CONV, line.unread.conv);
                Tools.swapFragment(activity, ChatFragment.class, ChatFragment.TAG, args);
            }
        } else {
            Tools.swapFragment(activity, SocialFragment.class, SocialFragment.TAG, null);
        }
    }

    private void render() {
        if (!mFragment.isAdded()) return;
        // MOD1: a moderator's note shows once, on the first screen that sees it (the home screen too).
        FragmentActivity host = mFragment.getActivity();
        if (host != null && mFragment.isResumed() && SocialService.get(host).signedIn()) AccountNotes.showNotices(host);
        Context context = mFragment.requireContext();
        SocialService service = SocialService.get(context);
        boolean visible = service.enabled();
        if (visible != (mRow.getVisibility() == View.VISIBLE)) {
            CardMotion.beginLayoutChange(mSceneRoot);
            mRow.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (!visible) return;
        HomeLine line = service.homeLine();
        // A call or an invite ends at its time with no message: the row looks again then (nothing else ticks).
        mRow.removeCallbacks(mTick);
        long now = SocialService.nowSeconds();
        long next = line.call != null ? service.state().secondsLeft(line.call, now)
                : line.invite != null ? line.invite.expires - now : 0;
        // M21: a plan comes 15 minutes before its time, says "Başladı" at it and goes when it is over.
        long hub = service.state().hubNow(now);
        if (line.plan != null) {
            next = line.plan.at > hub ? line.plan.at - hub : line.plan.at + HomeLine.PLAN_AFTER_SECONDS - hub;
        } else if (next <= 0) {
            String me = service.state().myId();
            for (SocialModel.Plan p : service.state().plans(now)) {
                String answer = me == null ? null : p.answers.get(me);
                if (!SocialModel.Plan.YES.equals(answer) && !SocialModel.Plan.MAYBE.equals(answer)) continue;
                long in = p.at - HomeLine.PLAN_WINDOW_SECONDS - hub;
                if (in > 0 && (next <= 0 || in < next)) next = in;
            }
        }
        if (next > 0) mRow.postDelayed(mTick, Math.min(next, 3600) * 1000L + 500);
        String title;
        String text;
        boolean gold = false;
        boolean join = false;
        switch (line.kind) {
            case SIGNED_OUT:
                title = context.getString(R.string.projects_social_home_signed_out);
                text = context.getString(R.string.projects_social_home_connect);
                gold = true;
                break;
            case LOADING:
                title = context.getString(R.string.projects_social_title);
                text = context.getString(R.string.projects_social_home_loading);
                break;
            case UNAVAILABLE:
                title = context.getString(R.string.projects_social_title);
                text = context.getString(R.string.projects_social_home_unavailable);
                break;
            case CALL: {
                Profile caller = line.faces.isEmpty() ? null : line.faces.get(0);
                // M20: with several crews the call names its crews; it says where it is.
                java.util.List<String> names = new java.util.ArrayList<>();
                if (line.call != null) {
                    for (SocialModel.Crew c : service.state().callCrews(line.call)) names.add(c.team.name);
                }
                title = gg.projects.mobile.social.SocialNotifications.callTitle(context, caller == null ? null : caller.name(),
                        names, line.severalCrews);
                text = line.call == null ? context.getString(R.string.projects_crew_call_ready)
                        : gg.projects.mobile.social.SocialNotifications.callPlace(context, line.call.place(), line.call.serverHost);
                gold = true;
                break;
            }
            case MESSAGE: {
                Profile from = line.faces.isEmpty() ? null : line.faces.get(0);
                title = context.getString(R.string.projects_chat_push,
                        from == null ? context.getString(R.string.projects_social_someone) : from.name());
                int unread = line.unread == null ? line.count : line.unread.count;
                text = context.getResources().getQuantityString(R.plurals.projects_chat_unread, unread, unread);
                gold = true;
                break;
            }
            case INVITE: {
                Profile from = line.faces.isEmpty() ? null : line.faces.get(0);
                Invite invite = line.invite;
                title = context.getString(R.string.projects_social_push_invite,
                        from == null ? context.getString(R.string.projects_social_someone) : from.name());
                text = invite != null && invite.onProjects() ? context.getString(R.string.projects_social_server_projects)
                        : invite != null && invite.host != null ? invite.host : context.getString(R.string.projects_social_invited_you);
                gold = true;
                join = invite != null && invite.onProjects();
                break;
            }
            case CREW_ON_PROJECTS:
                // M20: "Ekiplerinden N kişi Projects'te" for several crews (each player once), the busiest one named.
                title = context.getResources().getQuantityString(line.severalCrews ? R.plurals.projects_crews_home_projects
                        : R.plurals.projects_crew_home_projects, line.count, line.count);
                text = line.crew != null ? line.crew.team.name : service.state().team() == null ? "" : service.state().team().name;
                join = true;
                break;
            case FRIENDS_ON_PROJECTS: {
                String first = line.faces.isEmpty() ? "" : line.faces.get(0).name();
                title = line.count <= 1 ? context.getString(R.string.projects_social_home_projects_alone, first)
                        : context.getResources().getQuantityString(R.plurals.projects_social_home_projects_with, line.count - 1, first, line.count - 1);
                text = context.getString(R.string.projects_social_title);
                join = true;
                break;
            }
            case REQUEST: {
                Profile from = line.faces.isEmpty() ? null : line.faces.get(0);
                title = context.getString(R.string.projects_social_push_request,
                        from == null ? context.getString(R.string.projects_social_someone_unknown) : from.name());
                text = context.getResources().getQuantityString(R.plurals.projects_social_home_requests, line.count, line.count);
                gold = true;
                break;
            }
            case PLAN: {
                // M21: "Gece Kartalları planı" over "Bugün 22:00 · 3 geliyor" ("Başladı · 3 geliyor" with "Katıl").
                SocialModel.Plan plan = line.plan;
                String crewName = line.crew != null ? line.crew.team.name : "";
                title = context.getString(R.string.projects_plans_home_title, crewName);
                boolean under = plan != null && plan.underWay(hub);
                String when = plan == null ? "" : under ? context.getString(R.string.projects_plans_started_line)
                        : PlanViews.when(context, plan.at, now);
                text = line.count > 0 ? when + " · " + context.getResources().getQuantityString(R.plurals.projects_plans_coming,
                        line.count, line.count) : when;
                gold = true;
                join = under;
                break;
            }
            case JOIN_REQUEST: {
                // M21: "Ayşe ekibine katılmak istiyor" ("Ayşe, Gece Kartalları ekibine …" with several crews).
                Profile from = line.faces.isEmpty() ? null : line.faces.get(0);
                String who = from == null ? context.getString(R.string.projects_social_someone_unknown) : from.name();
                title = line.severalCrews && line.crew != null
                        ? context.getString(R.string.projects_requests_notice_crew, who, line.crew.team.name)
                        : context.getString(R.string.projects_requests_notice, who);
                text = context.getResources().getQuantityString(R.plurals.projects_requests_count, line.count, line.count);
                gold = true;
                break;
            }
            case CREW_INVITE: {
                SocialModel.TeamInvite invite = line.teamInvite;
                title = invite == null ? context.getString(R.string.projects_crew_home_invite)
                        : context.getString(R.string.projects_crew_invited_you, invite.from.name(), invite.team.name);
                text = context.getString(R.string.projects_crew_home_invite);
                gold = true;
                break;
            }
            case ONLINE:
                title = context.getString(R.string.projects_social_title);
                text = context.getResources().getQuantityString(R.plurals.projects_social_home_online, line.count, line.count);
                break;
            case NOBODY_ONLINE:
                title = context.getString(R.string.projects_social_title);
                text = context.getString(R.string.projects_social_home_nobody);
                break;
            case NO_FRIENDS:
            default:
                title = context.getString(R.string.projects_social_title);
                text = context.getString(R.string.projects_social_home_no_friends);
                break;
        }
        StringBuilder facesKey = new StringBuilder();
        for (Profile p : line.faces) facesKey.append(p.id).append(p.faceKey()).append(',');
        String shown = line.kind + "|" + title + "|" + text + "|" + gold + "|" + join + "|" + line.pending + "|" + facesKey;
        if (shown.equals(mShown)) return;
        boolean kindChanged = !mShown.startsWith(line.kind + "|");
        if (!mShown.isEmpty() && kindChanged) CardMotion.crossfade(mRow);
        mShown = shown;
        mTitle.setText(title);
        mLine.setText(text);
        mLine.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        mLine.setTextColor(ContextCompat.getColor(context, gold ? R.color.projects_gold_300 : R.color.projects_ash_400));
        mAction.setVisibility(join ? View.VISIBLE : View.GONE);
        if (join) mAction.setOnClickListener(v -> {
            FragmentActivity activity = mFragment.getActivity();
            if (activity != null) SocialUi.joinProjects(activity);
        });
        mDot.setVisibility(line.pending ? View.VISIBLE : View.GONE);
        mRow.setContentDescription(line.pending ? title + ". " + text + ". " + context.getString(R.string.projects_social_pending) : null);
        bindFaces(context, line.faces);
    }

    private void bindFaces(Context context, List<Profile> faces) {
        mIcon.setVisibility(faces.isEmpty() ? View.VISIBLE : View.GONE);
        mFaces.setVisibility(faces.isEmpty() ? View.GONE : View.VISIBLE);
        mFaces.removeAllViews();
        float dp = context.getResources().getDisplayMetrics().density;
        int size = Math.round((faces.size() == 1 ? 44 : 34) * dp);
        for (int i = 0; i < faces.size(); i++) {
            AvatarView avatar = new AvatarView(context);
            avatar.setProfile(faces.get(i));
            avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_900));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            if (i > 0) params.setMarginStart(-Math.round(12 * dp));
            mFaces.addView(avatar, params);
        }
    }
}
