package gg.projects.mobile.social.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.Presence;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Press;

/**
 * A friend's card (M19; the desktop's friend card): who they are and where, "Katıl" while they are on
 * Projects, "Discord'dan mesaj at", favourite, an invite to the player's crew, then removing and
 * blocking, each with one sentence to confirm.
 *
 * <p>M20: "Mesaj" first (CHAT1: direct messages between friends), "Katıl" beside it while the friend
 * is on Projects; "Ekibe davet et" asks which crew when the player has several (TEAM4); "Rapor et"
 * (MOD1) beside "Engelle".
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
        BadgeCard.bindTitleChip(activity, view.findViewById(R.id.projects_card_tag), friendId);
        TextView username = view.findViewById(R.id.projects_card_username);
        boolean showUser = !profile.username.isEmpty() && !profile.username.equals(profile.name());
        username.setText("@" + profile.username);
        username.setVisibility(showUser ? View.VISIBLE : View.GONE);
        TextView where = view.findViewById(R.id.projects_card_presence);
        where.setText(SocialUi.presenceLine(activity, presence));
        if (presence.onProjects()) where.setTextColor(ContextCompat.getColor(activity, R.color.projects_jade_400));
        TextView since = view.findViewById(R.id.projects_card_since);
        String day = CrewViews.sinceDay(activity, friend.since);
        since.setText(day == null ? "" : activity.getString(R.string.projects_social_friends_since, day));
        since.setVisibility(day == null ? View.GONE : View.VISIBLE);

        SocialGate gate = service.gate();
        View message = view.findViewById(R.id.projects_card_message);
        View join = view.findViewById(R.id.projects_card_join);
        boolean chat = service.chat().available();
        message.setVisibility(chat ? View.VISIBLE : View.GONE);
        join.setVisibility(presence.onProjects() ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.projects_card_buttons).setVisibility(chat || presence.onProjects() ? View.VISIBLE : View.GONE);
        message.setOnClickListener(v -> {
            dialog.dismiss();
            String me = service.state().myId();
            if (me != null) openChat(activity, ChatConv.direct(me, friendId));
        });
        join.setOnClickListener(v -> {
            dialog.dismiss();
            SocialUi.joinProjects(activity);
        });

        LinearLayout actions = view.findViewById(R.id.projects_card_actions);
        actions.addView(CrewViews.discordRow(activity, activity.getString(R.string.projects_social_message),
                v -> SocialUi.openDiscord(activity, profile)));
        final boolean favourite = service.prefs().favourites().contains(friendId);
        actions.addView(CrewViews.actionRow(activity, favourite ? R.drawable.projects_ic_star_fill : R.drawable.projects_ic_star,
                activity.getString(favourite ? R.string.projects_social_unfavorite : R.string.projects_social_favorite),
                R.color.projects_bone_100, v -> {
                    service.toggleFavourite(friendId);
                    dialog.dismiss();
                }));
        String me = service.state().myId();
        if (gate.teams && me != null) {
            // The crews whose rules let the player invite, and that this friend is not in or invited to yet.
            int open = 0;
            Crew only = null;
            for (Crew crew : service.state().crews()) {
                Team team = crew.team;
                if (!team.allows(me, team.permissions.invite) || team.member(friendId) != null || crew.invited.contains(friendId)) continue;
                open++;
                only = crew;
            }
            final Crew single = open == 1 ? only : null;
            boolean several = gate.multi && service.state().crews().size() > 1;
            if (open > 0) {
                actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_crew, activity.getString(R.string.projects_crew_invite_to),
                        R.color.projects_bone_100, v -> {
                            dialog.dismiss();
                            if (several) {
                                CrewSheets.inviteToCrews(activity, friendId);
                                return;
                            }
                            if (single == null) return;
                            service.inviteToTeam(single.id(), friendId, new SocialService.Result<Void>() {
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
        }
        actions.addView(CrewViews.divider(activity));
        actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_user_minus, activity.getString(R.string.projects_social_remove),
                R.color.projects_ash_300, v -> {
                    dialog.dismiss();
                    SocialUi.confirmDanger(activity, activity.getString(R.string.projects_social_remove_ask, profile.name()), null,
                            activity.getString(R.string.projects_social_remove), () -> service.removeFriend(friendId, done(activity,
                                    activity.getString(R.string.projects_social_removed, profile.name()))));
                }));
        if (gate.reports) {
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_flag, activity.getString(R.string.projects_report),
                    R.color.projects_ash_300, v -> {
                        dialog.dismiss();
                        ReportDialog.player(activity, profile);
                    }));
        }
        actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_prohibit, activity.getString(R.string.projects_social_block),
                R.color.projects_flare_400, v -> {
                    dialog.dismiss();
                    SocialUi.confirmDanger(activity, activity.getString(R.string.projects_social_block_title, profile.name()),
                            activity.getString(R.string.projects_social_block_ask), activity.getString(R.string.projects_social_block),
                            () -> service.block(profile, done(activity, activity.getString(R.string.projects_social_blocked, profile.name()))));
                }));
        join.setBackground(ChamferDrawable.outlined(activity));
        dialog.show();
        Press.attach(join, Press.Style.BUTTON);
        Press.attach(message, Press.Style.BUTTON);
    }

    /** Opens a conversation from wherever the card was (a screen on top of the friends list). */
    static void openChat(FragmentActivity activity, String conv) {
        android.os.Bundle args = new android.os.Bundle();
        args.putString(ChatFragment.ARG_CONV, conv);
        if (activity.getSupportFragmentManager().isStateSaved()) return;
        Tools.swapFragment(activity, ChatFragment.class, ChatFragment.TAG, args);
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
