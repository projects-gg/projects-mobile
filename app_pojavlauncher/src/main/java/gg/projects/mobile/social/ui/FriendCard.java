package gg.projects.mobile.social.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.Presence;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Press;

/**
 * A friend's card (M19; the desktop's friend card): who they are and where, "Katıl" while they are on
 * Projects, "Discord'dan mesaj at" (the app has no chat), favourite, an invite to the player's crew,
 * then removing and blocking, each with one sentence to confirm.
 */
public final class FriendCard {
    private FriendCard() {}

    public static void show(final FragmentActivity activity, final String friendId) {
        final SocialService service = SocialService.get(activity);
        final Friend friend = service.state().friend(friendId);
        if (friend == null) return;
        final Profile profile = friend.profile;
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_friend, null, false);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(view).create();

        AvatarView avatar = view.findViewById(R.id.projects_card_avatar);
        avatar.setProfile(profile);
        avatar.setSurfaceColor(ContextCompat.getColor(activity, R.color.projects_obsidian_800));
        Presence presence = service.state().presence(friendId);
        avatar.setDot(SocialUi.dot(presence));
        ((TextView) view.findViewById(R.id.projects_card_name)).setText(profile.name());
        TextView username = view.findViewById(R.id.projects_card_username);
        boolean showUser = !profile.username.isEmpty() && !profile.username.equals(profile.name());
        username.setText("@" + profile.username);
        username.setVisibility(showUser ? View.VISIBLE : View.GONE);
        TextView where = view.findViewById(R.id.projects_card_presence);
        where.setText(SocialUi.presenceText(activity, presence));
        if (presence.onProjects()) where.setTextColor(ContextCompat.getColor(activity, R.color.projects_jade_400));
        TextView since = view.findViewById(R.id.projects_card_since);
        String day = CrewViews.day(activity, friend.since);
        since.setText(day == null ? "" : activity.getString(R.string.projects_social_friends_since, day));
        since.setVisibility(day == null ? View.GONE : View.VISIBLE);

        View join = view.findViewById(R.id.projects_card_join);
        join.setVisibility(presence.onProjects() ? View.VISIBLE : View.GONE);
        join.setOnClickListener(v -> {
            dialog.dismiss();
            SocialUi.joinProjects(activity);
        });

        LinearLayout actions = view.findViewById(R.id.projects_card_actions);
        actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_discord_glyph, activity.getString(R.string.projects_social_message),
                R.color.projects_bone_100, v -> SocialUi.openDiscord(activity, profile)));
        final boolean favourite = service.prefs().favourites().contains(friendId);
        actions.addView(CrewViews.actionRow(activity, favourite ? R.drawable.projects_ic_star_fill : R.drawable.projects_ic_star,
                activity.getString(favourite ? R.string.projects_social_unfavorite : R.string.projects_social_favorite),
                R.color.projects_bone_100, v -> {
                    service.toggleFavourite(friendId);
                    dialog.dismiss();
                }));
        SocialGate gate = service.gate();
        Team team = service.state().team();
        String me = service.state().myId();
        if (gate.teams && team != null && me != null && team.member(friendId) == null
                && team.allows(me, team.permissions.invite) && !service.state().teamInvited().contains(friendId)) {
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_crew, activity.getString(R.string.projects_crew_invite_to),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        service.inviteToTeam(team.id, friendId, new SocialService.Result<Void>() {
                            @Override
                            public void ok(Void value) {
                                SocialUi.toast(activity, activity.getString(R.string.projects_crew_invite_sent));
                            }

                            @Override
                            public void failed(SocialProblem problem) {
                                SocialUi.toastProblem(activity, problem, null);
                            }
                        });
                    }));
        }
        actions.addView(CrewViews.divider(activity));
        actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_user_minus, activity.getString(R.string.projects_social_remove),
                R.color.projects_ash_300, v -> {
                    dialog.dismiss();
                    SocialUi.confirm(activity, activity.getString(R.string.projects_social_remove_ask, profile.name()), null,
                            activity.getString(R.string.projects_social_remove), () -> service.removeFriend(friendId, done(activity,
                                    activity.getString(R.string.projects_social_removed, profile.name()))));
                }));
        actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_prohibit, activity.getString(R.string.projects_social_block),
                R.color.projects_flare_400, v -> {
                    dialog.dismiss();
                    SocialUi.confirm(activity, activity.getString(R.string.projects_social_block_title, profile.name()),
                            activity.getString(R.string.projects_social_block_ask), activity.getString(R.string.projects_social_block),
                            () -> service.block(profile, done(activity, activity.getString(R.string.projects_social_blocked, profile.name()))));
                }));
        dialog.show();
        Press.attach(join, Press.Style.BUTTON);
    }

    static SocialService.Result<Void> done(final FragmentActivity activity, final String message) {
        return new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
                SocialUi.toast(activity, message);
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, null);
            }
        };
    }

    /** A crewmate who is not a friend: where they are and "Katıl" (the crew page's member card). */
    public static boolean isFriend(SocialService service, String id) {
        return service.state().isFriend(id);
    }

    static boolean onProjects(SocialService service, String id) {
        SocialModel.Presence p = service.state().presence(id);
        return p.onProjects();
    }
}
