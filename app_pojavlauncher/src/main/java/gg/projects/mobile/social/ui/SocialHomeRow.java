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
        if (line.kind == HomeLine.Kind.CALL) {
            Tools.swapFragment(activity, CrewFragment.class, CrewFragment.TAG, null);
        } else {
            Tools.swapFragment(activity, SocialFragment.class, SocialFragment.TAG, null);
        }
    }

    private void render() {
        if (!mFragment.isAdded()) return;
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
                title = context.getString(R.string.projects_crew_call_theirs,
                        caller == null ? context.getString(R.string.projects_crew_someone) : caller.name());
                text = context.getString(line.call != null && line.call.joinable()
                        ? R.string.projects_social_on_projects : R.string.projects_crew_call_ready);
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
                title = context.getResources().getQuantityString(R.plurals.projects_crew_home_projects, line.count, line.count);
                text = service.state().team() == null ? "" : service.state().team().name;
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
