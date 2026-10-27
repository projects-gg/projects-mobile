package gg.projects.mobile.social.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.MutePresets;
import gg.projects.mobile.social.SocialCodes;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialMenus;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Request;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.HapticMap;
import gg.projects.mobile.ui.Haptics;

/**
 * FIX2: the same menu wherever a player or a crew shows (the friends list, the crew page and its
 * members, plans and calls, join requests, the invite picker, the home row, chat), opened by holding the
 * row (and its "⋮" where it has one). What it offers is {@link SocialMenus}' (the desktop's order); this
 * class gives the rows their words and icons and does what they say. Holding gives a long-press haptic,
 * the sheet scales in from the finger's side, and TalkBack reaches it as the row's "Seçenekler" action.
 */
public final class SocialMenuSheet {
    private SocialMenuSheet() {}

    /** Where the menu was opened (only for a crew's context and the player's own conversation). */
    public enum Place { FRIENDS, CREW, CHAT, DIRECT_CHAT, HOME, PLAN, CALL, PICKER, CHAT_LIST, REQUEST }

    /** One of a row's own actions (a home or invite row: "Bağlan", "Kapat"...), before its people. */
    public static final class Own {
        @DrawableRes
        final int icon;
        final String label;
        final int tint;
        final Runnable action;

        public Own(@DrawableRes int icon, String label, int tint, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.tint = tint;
            this.action = action;
        }
    }

    /**
     * Makes {@code row} open {@code open} when held, with a long-press haptic, and gives TalkBack the
     * same as an action named "Seçenekler" ("{name} için seçenekler" when the row has a name).
     */
    public static void attach(final View row, @Nullable CharSequence name, final View.OnLongClickListener open) {
        row.setOnLongClickListener(v -> {
            Haptics.perform(v, HapticMap.Kind.LONG_PRESS);
            return open.onLongClick(v);
        });
        Context context = row.getContext();
        String label = name == null || name.length() == 0 ? context.getString(R.string.projects_menu_options)
                : context.getString(R.string.projects_menu_options_for, name);
        Object previous = row.getTag(R.id.projects_menu_action);
        if (previous instanceof Integer) ViewCompat.removeAccessibilityAction(row, (Integer) previous);
        int id = ViewCompat.addAccessibilityAction(row, label, (view, arguments) -> open.onLongClick(view));
        row.setTag(R.id.projects_menu_action, id);
    }

    /** Takes {@link #attach}'s listener and action off a row that has no menu now. */
    public static void detach(View row) {
        row.setOnLongClickListener(null);
        row.setLongClickable(false);
        Object previous = row.getTag(R.id.projects_menu_action);
        if (previous instanceof Integer) ViewCompat.removeAccessibilityAction(row, (Integer) previous);
        row.setTag(R.id.projects_menu_action, null);
    }

    // ---- a player ----

    /** A player's menu by id (a friend, a crewmate, someone who asked); nothing for the player themself. */
    public static void person(FragmentActivity activity, String userId, @Nullable String crewId, Place place, @Nullable View anchor) {
        person(activity, userId, crewId, place, anchor, null);
    }

    /**
     * The same where "Rapor et" reports something of theirs ({@code report}; CHAT2: a reaction from the
     * reactions' list) in place of the player alone.
     */
    public static void person(FragmentActivity activity, String userId, @Nullable String crewId, Place place, @Nullable View anchor,
                              @Nullable Runnable report) {
        SocialState state = SocialService.get(activity).state();
        Profile profile = state.profile(userId);
        if (profile == null) profile = requester(state, userId);
        if (profile == null) return;
        person(activity, profile, crewId, place, SocialMenus.Pending.NONE, anchor, report);
    }

    @Nullable
    private static Profile requester(SocialState state, String userId) {
        for (Request r : state.incoming()) if (r.profile.id.equals(userId)) return r.profile;
        for (Request r : state.outgoing()) if (r.profile.id.equals(userId)) return r.profile;
        for (Profile p : state.blocked()) if (p.id.equals(userId)) return p;
        return null;
    }

    /** The same for a row about a request ({@code pending}): its answers come first. */
    public static void person(final FragmentActivity activity, final Profile profile, @Nullable final String crewId, Place place,
                              final SocialMenus.Pending pending, @Nullable View anchor) {
        person(activity, profile, crewId, place, pending, anchor, null);
    }

    private static void person(final FragmentActivity activity, final Profile profile, @Nullable final String crewId, Place place,
                               final SocialMenus.Pending pending, @Nullable View anchor, @Nullable final Runnable report) {
        final SocialService service = SocialService.get(activity);
        final SocialState state = service.state();
        final String me = state.myId();
        if (me == null) return;
        SocialGate gate = service.gate();
        final String id = profile.id;
        SocialMenus.PersonInput in = new SocialMenus.PersonInput();
        in.self = id.equals(me);
        in.friend = state.isFriend(id);
        in.crewmate = !state.crewsOf(id).isEmpty();
        for (Profile p : state.blocked()) if (p.id.equals(id)) in.blocked = true;
        for (Request r : state.incoming()) if (r.profile.id.equals(id)) in.incoming = true;
        for (Request r : state.outgoing()) if (r.profile.id.equals(id)) in.outgoing = true;
        in.pending = pending;
        in.onProjects = state.presence(id).onProjects();
        in.chat = service.chat().available();
        in.inTheirConversation = place == Place.DIRECT_CHAT;
        in.crewFriendRequests = gate.crewFriendRequests;
        in.favourite = service.prefs().favourites().contains(id);
        in.crewInvite = crewInvite(service, id);
        Crew here = state.crew(crewId);
        Member mine = here == null ? null : here.team.member(me);
        Member theirs = here == null ? null : here.team.member(id);
        in.leaderHere = mine != null && theirs != null && mine.founder && !in.self;
        in.removableHere = theirs != null && CrewSheets.mayRemove(here.team, mine, theirs, gate);
        in.reports = gate.reports;
        in.reportable = in.friend || in.crewmate || in.incoming || pending == SocialMenus.Pending.JOIN_REQUEST;
        List<SocialMenus.Row<SocialMenus.PersonAction>> rows = SocialMenus.person(in);
        if (rows.isEmpty()) return;

        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        list.addView(personHeader(activity, profile, SocialUi.presenceText(activity, state.presence(id))));
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        for (SocialMenus.Row<SocialMenus.PersonAction> row : rows) {
            if (row == null) {
                list.addView(CrewViews.divider(activity));
                continue;
            }
            final SocialMenus.PersonAction action = row.action;
            View.OnClickListener run = v -> {
                dialog.dismiss();
                if (action == SocialMenus.PersonAction.REPORT && report != null) report.run();
                else runPerson(activity, action, profile, crewId, pending, v);
            };
            // MOB-POLISH: "Discord'dan mesaj at" opens Discord: its own symbol in Blurple, as on the friend's card.
            View view = action == SocialMenus.PersonAction.DISCORD
                    ? CrewViews.discordRow(activity, personLabel(activity, action, in), run)
                    : CrewViews.actionRow(activity, personIcon(action), personLabel(activity, action, in), personTint(action), run);
            if (row.disabled) dim(view);
            list.addView(view);
        }
        show(dialog, anchor);
    }

    /**
     * "Ekibe davet et" (the desktop's rule): open while a crew the player may invite to does not have this friend
     * and can take them; dim when every such crew already invited them ("Davet edildi") or is full; none when
     * they are in all of them.
     */
    private static SocialMenus.Invite crewInvite(SocialService service, String friendId) {
        SocialState state = service.state();
        String me = state.myId();
        if (!service.gate().teams || me == null || !state.isFriend(friendId)) return SocialMenus.Invite.NONE;
        int limit = state.limits().teamMembers;
        boolean invited = false;
        boolean full = false;
        for (Crew crew : state.crews()) {
            Team team = crew.team;
            if (!team.allows(me, team.permissions.invite) || team.member(friendId) != null) continue;
            if (crew.invited.contains(friendId)) {
                invited = true;
            } else if (limit > 0 && team.members.size() >= limit) {
                full = true;
            } else {
                return SocialMenus.Invite.OPEN;
            }
        }
        return invited ? SocialMenus.Invite.INVITED : full ? SocialMenus.Invite.FULL : SocialMenus.Invite.NONE;
    }

    private static int personIcon(SocialMenus.PersonAction action) {
        switch (action) {
            case ACCEPT:
            case ACCEPT_REQUEST: return R.drawable.projects_ic_check;
            case DECLINE:
            case CANCEL_REQUEST: return R.drawable.projects_ic_x;
            case JOIN: return R.drawable.projects_join_glyph;
            case MESSAGE: return R.drawable.projects_ic_chat;
            case DISCORD: return R.drawable.projects_ic_discord_glyph;
            case ADD_FRIEND:
            case REQUEST_SENT: return R.drawable.projects_ic_user_plus;
            case FAVOURITE: return R.drawable.projects_ic_star;
            case UNFAVOURITE: return R.drawable.projects_ic_star_fill;
            case CREW_INVITE: return R.drawable.projects_ic_crew;
            case HAND_OVER: return R.drawable.projects_ic_crown_fill;
            case REMOVE_FROM_CREW:
            case REMOVE_FRIEND: return R.drawable.projects_ic_user_minus;
            case BLOCK:
            case UNBLOCK: return R.drawable.projects_ic_prohibit;
            case REPORT:
            default: return R.drawable.projects_ic_flag;
        }
    }

    private static String personLabel(Context c, SocialMenus.PersonAction action, SocialMenus.PersonInput in) {
        switch (action) {
            case ACCEPT: return c.getString(R.string.projects_social_accept);
            case DECLINE: return c.getString(R.string.projects_social_decline);
            case CANCEL_REQUEST: return c.getString(R.string.projects_social_cancel_request);
            case JOIN: return c.getString(R.string.projects_social_join);
            case MESSAGE: return c.getString(R.string.projects_menu_message);
            case DISCORD: return c.getString(R.string.projects_social_message);
            case ADD_FRIEND: return c.getString(R.string.projects_social_add);
            case REQUEST_SENT: return c.getString(R.string.projects_menu_request_sent);
            case ACCEPT_REQUEST: return c.getString(R.string.projects_menu_accept_request);
            case FAVOURITE: return c.getString(R.string.projects_social_favorite);
            case UNFAVOURITE: return c.getString(R.string.projects_social_unfavorite);
            case CREW_INVITE: return c.getString(in.crewInvite == SocialMenus.Invite.INVITED ? R.string.projects_crew_invite_sent
                    : R.string.projects_crew_invite_to);
            case HAND_OVER: return c.getString(R.string.projects_crew_hand_over);
            case REMOVE_FROM_CREW: return c.getString(R.string.projects_crew_remove);
            case REMOVE_FRIEND: return c.getString(R.string.projects_social_remove);
            case BLOCK: return c.getString(R.string.projects_social_block);
            case UNBLOCK: return c.getString(R.string.projects_social_unblock);
            case REPORT:
            default: return c.getString(R.string.projects_report);
        }
    }

    private static int personTint(SocialMenus.PersonAction action) {
        switch (action) {
            case BLOCK:
            case REMOVE_FROM_CREW: return R.color.projects_flare_400;
            case REMOVE_FRIEND:
            case REPORT: return R.color.projects_ash_300;
            default: return R.color.projects_bone_100;
        }
    }

    private static void runPerson(final FragmentActivity activity, SocialMenus.PersonAction action, final Profile profile,
                                  @Nullable final String crewId, SocialMenus.Pending pending, final View feedback) {
        final SocialService service = SocialService.get(activity);
        final String id = profile.id;
        final String name = profile.name();
        switch (action) {
            case ACCEPT:
                if (pending == SocialMenus.Pending.JOIN_REQUEST && crewId != null) {
                    service.acceptJoinRequest(crewId, id, json(activity, activity.getString(R.string.projects_requests_accepted, name)));
                } else {
                    service.accept(id, json(activity, activity.getString(R.string.projects_social_friend_added, name)));
                }
                break;
            case ACCEPT_REQUEST:
                service.accept(id, json(activity, activity.getString(R.string.projects_social_friend_added, name)));
                break;
            case DECLINE:
                if (pending == SocialMenus.Pending.JOIN_REQUEST && crewId != null) service.declineJoinRequest(crewId, id, quiet(activity));
                else service.decline(id, quiet(activity));
                break;
            case CANCEL_REQUEST:
                service.cancelRequest(id, quiet(activity));
                break;
            case JOIN:
                ConnectFlow.projects(activity);
                break;
            case MESSAGE: {
                String me = service.state().myId();
                if (me != null) FriendCard.openChat(activity, ChatConv.direct(me, id));
                break;
            }
            case DISCORD:
                SocialUi.openDiscord(activity, profile);
                break;
            case ADD_FRIEND:
                service.sendRequestToCrewmate(id, new SocialService.Result<JsonObject>() {
                    @Override
                    public void ok(JsonObject value) {
                        Haptics.confirm(feedback);
                        boolean friends = "friends".equals(gg.projects.mobile.social.Json.str(value, "status"));
                        SocialUi.toast(activity, friends ? activity.getString(R.string.projects_social_friend_added, name)
                                : activity.getString(R.string.projects_menu_request_sent_to));
                    }

                    @Override
                    public void failed(SocialProblem problem) {
                        // "user_not_found": not a crewmate any more, or they cannot be asked now (the service says no more).
                        if (problem.kind == SocialProblem.Kind.NO_ONE_WITH_CODE) {
                            Haptics.reject(feedback);
                            SocialUi.toast(activity, activity.getString(R.string.projects_social_error_crewmate_gone));
                        } else {
                            SocialUi.toastProblem(activity, problem, feedback);
                        }
                    }
                });
                break;
            case REQUEST_SENT:
                break;
            case FAVOURITE:
            case UNFAVOURITE:
                service.toggleFavourite(id);
                break;
            case CREW_INVITE:
                inviteToCrew(activity, id);
                break;
            case HAND_OVER:
                handOver(activity, crewId, profile);
                break;
            case REMOVE_FROM_CREW:
                if (crewId == null) break;
                SocialUi.confirm(activity, activity.getString(R.string.projects_crew_remove),
                        activity.getString(R.string.projects_crew_remove_ask, name), activity.getString(R.string.projects_crew_remove),
                        () -> service.removeMember(crewId, id, FriendCard.done(activity, activity.getString(R.string.projects_crew_removed, name))));
                break;
            case REMOVE_FRIEND:
                SocialUi.confirm(activity, activity.getString(R.string.projects_social_remove_ask, name), null,
                        activity.getString(R.string.projects_social_remove),
                        () -> service.removeFriend(id, FriendCard.done(activity, activity.getString(R.string.projects_social_removed, name))));
                break;
            case BLOCK:
                SocialUi.confirm(activity, activity.getString(R.string.projects_social_block_title, name),
                        activity.getString(R.string.projects_social_block_ask), activity.getString(R.string.projects_social_block), () -> {
                            SocialService.Result<Void> done = FriendCard.done(activity, activity.getString(R.string.projects_social_blocked, name));
                            if (pending == SocialMenus.Pending.JOIN_REQUEST && crewId != null) service.blockRequester(crewId, profile, done);
                            else service.block(profile, done);
                        });
                break;
            case UNBLOCK:
                service.unblock(id, FriendCard.done(activity, activity.getString(R.string.projects_social_unblocked, name)));
                break;
            case REPORT:
            default:
                ReportDialog.player(activity, profile);
                break;
        }
    }

    private static void inviteToCrew(final FragmentActivity activity, String friendId) {
        SocialService service = SocialService.get(activity);
        String me = service.state().myId();
        if (me == null) return;
        Crew single = null;
        int open = 0;
        for (Crew crew : service.state().crews()) {
            Team team = crew.team;
            if (!team.allows(me, team.permissions.invite) || team.member(friendId) != null || crew.invited.contains(friendId)) continue;
            open++;
            single = crew;
        }
        if (service.gate().multi && service.state().crews().size() > 1) {
            CrewSheets.inviteToCrews(activity, friendId);
            return;
        }
        if (open != 1 || single == null) return;
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
    }

    private static void handOver(final FragmentActivity activity, @Nullable final String crewId, final Profile profile) {
        final SocialService service = SocialService.get(activity);
        Crew crew = service.state().crew(crewId);
        if (crew == null) return;
        // The old leader becomes a moderator while the crew has room for one (at most 4).
        int moderators = 0;
        for (Member m : crew.team.members) if (m.moderator && !m.profile.id.equals(profile.id)) moderators++;
        String ask = activity.getString(service.gate().roles && moderators < 4 ? R.string.projects_crew_hand_over_ask_moderator
                : R.string.projects_crew_hand_over_ask, profile.name());
        SocialUi.confirm(activity, activity.getString(R.string.projects_crew_hand_over), ask, activity.getString(R.string.projects_crew_hand_over),
                () -> service.handOver(crew.id(), profile.id, json(activity, activity.getString(R.string.projects_crew_handed_over, profile.name()))));
    }

    // ---- a crew ----

    /**
     * A crew's menu: on its own page ({@code onItsPage}) nothing to open, in its own chat ({@code inItsChat})
     * no "Sohbeti aç".
     */
    public static void crew(final FragmentActivity activity, final String crewId, boolean onItsPage, boolean inItsChat, @Nullable View anchor) {
        final SocialService service = SocialService.get(activity);
        final SocialState state = service.state();
        final Crew crew = state.crew(crewId);
        final String me = state.myId();
        if (crew == null || me == null) return;
        final Team team = crew.team;
        SocialGate gate = service.gate();
        SocialMenus.CrewInput in = new SocialMenus.CrewInput();
        in.onItsPage = onItsPage;
        in.chat = service.chat().available() && !inItsChat;
        in.hasCode = !team.code.isEmpty();
        in.mayInvite = team.allows(me, team.permissions.invite);
        int limit = state.limits().teamMembers;
        boolean outside = false;
        for (SocialModel.Friend f : state.friends()) if (team.member(f.profile.id) == null) outside = true;
        in.inviteDim = (limit > 0 && team.members.size() >= limit) || !outside;
        List<String> order = state.crewIds();
        if (gate.multi && order.size() > 1) {
            int at = order.indexOf(crewId);
            in.first = at == 0;
            in.last = at == order.size() - 1;
        }
        long hub = state.hubNow(SocialService.nowSeconds());
        if (gate.multi) in.mute = crew.mute.mutedAt(hub) ? SocialMenus.Mute.MUTED : SocialMenus.Mute.UNMUTED;
        in.reports = gate.reports;
        Member mine = team.member(me);
        in.leader = mine != null && mine.founder;
        in.alone = team.members.size() <= 1;
        List<SocialMenus.Row<SocialMenus.CrewAction>> rows = SocialMenus.crew(in);
        if (rows.isEmpty()) return;

        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        list.addView(crewHeader(activity, crew));
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        for (SocialMenus.Row<SocialMenus.CrewAction> row : rows) {
            if (row == null) {
                list.addView(CrewViews.divider(activity));
                continue;
            }
            final SocialMenus.CrewAction action = row.action;
            View view = CrewViews.actionRow(activity, crewIcon(action), crewLabel(activity, action), crewTint(action), v -> {
                dialog.dismiss();
                runCrew(activity, action, crew, v);
            });
            if (row.disabled) dim(view);
            list.addView(view);
        }
        show(dialog, anchor);
    }

    private static int crewIcon(SocialMenus.CrewAction action) {
        switch (action) {
            case OPEN: return R.drawable.projects_ic_crew;
            case CHAT: return R.drawable.projects_ic_chat;
            case COPY_CODE: return R.drawable.projects_ic_copy;
            case INVITE: return R.drawable.projects_ic_user_plus;
            case MAKE_MAIN: return R.drawable.projects_ic_crown_fill;
            case MOVE_LEFT: return R.drawable.projects_ic_caret_left;
            case MOVE_RIGHT: return R.drawable.projects_ic_caret_right;
            case MUTE: return R.drawable.projects_ic_bell_slash;
            case UNMUTE: return R.drawable.projects_ic_bell;
            case REPORT: return R.drawable.projects_ic_flag;
            case LEAVE: return R.drawable.projects_ic_sign_out;
            case CLOSE:
            case DISBAND:
            default: return R.drawable.projects_ic_trash;
        }
    }

    private static String crewLabel(Context c, SocialMenus.CrewAction action) {
        switch (action) {
            case OPEN: return c.getString(R.string.projects_menu_open_crew);
            case CHAT: return c.getString(R.string.projects_menu_open_chat);
            case COPY_CODE: return c.getString(R.string.projects_menu_copy_code);
            case INVITE: return c.getString(R.string.projects_crew_invite_to);
            case MAKE_MAIN: return c.getString(R.string.projects_crews_make_main);
            case MOVE_LEFT: return c.getString(R.string.projects_crews_move_left);
            case MOVE_RIGHT: return c.getString(R.string.projects_crews_move_right);
            case MUTE: return c.getString(R.string.projects_crew_mute);
            case UNMUTE: return c.getString(R.string.projects_crew_unmute);
            case REPORT: return c.getString(R.string.projects_report_crew);
            case LEAVE: return c.getString(R.string.projects_crew_leave);
            case CLOSE: return c.getString(R.string.projects_crew_close);
            case DISBAND:
            default: return c.getString(R.string.projects_crew_disband);
        }
    }

    private static int crewTint(SocialMenus.CrewAction action) {
        switch (action) {
            case LEAVE:
            case CLOSE:
            case DISBAND: return R.color.projects_flare_400;
            case REPORT: return R.color.projects_ash_300;
            default: return R.color.projects_bone_100;
        }
    }

    private static void runCrew(final FragmentActivity activity, SocialMenus.CrewAction action, final Crew crew, View feedback) {
        final SocialService service = SocialService.get(activity);
        final Team team = crew.team;
        switch (action) {
            case OPEN: {
                Bundle args = new Bundle();
                args.putString(CrewFragment.ARG_CREW, team.id);
                if (!activity.getSupportFragmentManager().isStateSaved()) Tools.swapFragment(activity, CrewFragment.class, CrewFragment.TAG, args);
                break;
            }
            case CHAT:
                FriendCard.openChat(activity, ChatConv.crew(team.id));
                break;
            case COPY_CODE: {
                String code = SocialCodes.crewCode(team.code);
                SocialUi.copy(activity, activity.getString(R.string.projects_crew_code), code);
                Haptics.confirm(feedback);
                SocialUi.toast(activity, activity.getString(R.string.projects_social_copied));
                break;
            }
            case INVITE:
                CrewSheets.inviteFriends(activity, team.id);
                break;
            case MAKE_MAIN:
                move(activity, team.id, 0);
                break;
            case MOVE_LEFT:
            case MOVE_RIGHT: {
                int at = service.state().crewIds().indexOf(team.id);
                if (at >= 0) move(activity, team.id, action == SocialMenus.CrewAction.MOVE_LEFT ? at - 1 : at + 1);
                break;
            }
            case MUTE: {
                long now = service.state().hubNow(SocialService.nowSeconds());
                MuteSheet.show(activity, activity.getString(R.string.projects_crew_notifications), team.name, null, now, null, null,
                        new MuteSheet.Choice() {
                            @Override
                            public void preset(MutePresets.Preset preset, View row) {
                                service.muteCrew(team.id, preset, CrewSheets.result(activity, row));
                            }

                            @Override
                            public void unmute(View button) {
                                service.unmuteCrew(team.id, CrewSheets.result(activity, button));
                            }
                        });
                break;
            }
            case UNMUTE:
                service.unmuteCrew(team.id, CrewSheets.result(activity, feedback));
                break;
            case REPORT:
                ReportDialog.crew(activity, team, null);
                break;
            case LEAVE:
                SocialUi.confirm(activity, activity.getString(R.string.projects_crew_leave), activity.getString(R.string.projects_crew_leave_ask),
                        activity.getString(R.string.projects_crew_leave),
                        () -> service.leaveTeam(team.id, FriendCard.done(activity, activity.getString(R.string.projects_crew_left))));
                break;
            case CLOSE:
                SocialUi.confirm(activity, activity.getString(R.string.projects_crew_close), activity.getString(R.string.projects_crew_close_ask),
                        activity.getString(R.string.projects_crew_close),
                        () -> service.disbandTeam(team.id, FriendCard.done(activity, activity.getString(R.string.projects_crew_disbanded_done))));
                break;
            case DISBAND:
            default:
                SocialUi.confirm(activity, activity.getString(R.string.projects_crew_disband), activity.getString(R.string.projects_crew_disband_ask),
                        activity.getString(R.string.projects_crew_disband),
                        () -> service.disbandTeam(team.id, FriendCard.done(activity, activity.getString(R.string.projects_crew_disbanded_done))));
                break;
        }
    }

    /** Moves a crew to {@code position} in the player's order (0 makes it the main crew). */
    static void move(final FragmentActivity activity, String crewId, int position) {
        SocialService service = SocialService.get(activity);
        List<String> order = new ArrayList<>(service.state().crewIds());
        if (!order.remove(crewId)) return;
        int at = Math.max(0, Math.min(position, order.size()));
        order.add(at, crewId);
        service.setCrewOrder(order, new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, null);
            }
        });
        Crew crew = service.state().crew(crewId);
        if (crew != null) {
            SocialUi.toast(activity, at == 0 ? activity.getString(R.string.projects_crews_made_main, crew.team.name)
                    : activity.getString(R.string.projects_crews_moved, crew.team.name, at + 1));
        }
    }

    // ---- rows that show several people ----

    /**
     * A row about several people (the home row, an invite, a plan, a call): its own actions first, then
     * each person by name; a person opens their menu ({@code crewId}: the row's crew, for its context).
     */
    public static void people(final FragmentActivity activity, @Nullable CharSequence title, List<Own> own, List<String> userIds,
                              @Nullable final String crewId, @Nullable View anchor) {
        final SocialService service = SocialService.get(activity);
        SocialState state = service.state();
        String me = state.myId();
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        if (title != null) {
            TextView quote = sheet.findViewById(R.id.projects_actions_quote);
            quote.setText(title);
            quote.setVisibility(View.VISIBLE);
        }
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        for (final Own o : own) {
            list.addView(CrewViews.actionRow(activity, o.icon, o.label, o.tint, v -> {
                dialog.dismiss();
                o.action.run();
            }));
        }
        boolean divided = own.isEmpty();
        for (final String id : userIds) {
            if (id.equals(me)) continue;
            Profile p = state.profile(id);
            if (p == null) continue;
            if (!divided) {
                list.addView(CrewViews.divider(activity));
                divided = true;
            }
            View row = personRow(activity, p, SocialUi.presenceText(activity, state.presence(id)));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                person(activity, id, crewId, Place.HOME, v);
            });
            list.addView(row);
        }
        if (list.getChildCount() == 0) return;
        show(dialog, anchor);
    }

    // ---- pieces ----

    private static SocialService.Result<JsonObject> json(final FragmentActivity activity, final String done) {
        return new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                SocialUi.toast(activity, done);
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, null);
            }
        };
    }

    private static SocialService.Result<Void> quiet(final FragmentActivity activity) {
        return new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, null);
            }
        };
    }

    /** A dim row: shown so the player knows it is there, not offered now ("İstek gönderildi"). */
    private static void dim(View row) {
        row.setAlpha(0.5f);
        row.setEnabled(false);
        row.setClickable(false);
    }

    /** The sheet's top: whose menu it is (face, name, where they are). */
    private static View personHeader(Context context, Profile profile, @Nullable String line) {
        View header = personRow(context, profile, line);
        header.setClickable(false);
        header.setFocusable(false);
        header.setBackground(null);
        ViewCompat.setAccessibilityHeading(header, true);
        return header;
    }

    private static View personRow(Context context, Profile profile, @Nullable String line) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(56 * dp));
        row.setPaddingRelative(Math.round(10 * dp), Math.round(4 * dp), Math.round(10 * dp), Math.round(8 * dp));
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        row.setClickable(true);
        row.setFocusable(true);
        AvatarView avatar = new AvatarView(context);
        avatar.setProfile(profile);
        avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, new LinearLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp)));
        row.addView(names(context, profile.name(), line), namesParams(dp));
        gg.projects.mobile.ui.Press.attach(row, gg.projects.mobile.ui.Press.Style.ROW);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static View crewHeader(Context context, Crew crew) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(Math.round(10 * dp), Math.round(4 * dp), Math.round(10 * dp), Math.round(8 * dp));
        CrewMarkView mark = new CrewMarkView(context);
        mark.setLook(crew.team.emblem, crew.team.color, crew.team.frame);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp)));
        int limit = SocialService.get(context).state().limits().teamMembers;
        String members = limit > 0 ? context.getString(R.string.projects_crew_members, crew.team.members.size(), limit)
                : context.getResources().getQuantityString(R.plurals.projects_crew_member_count, crew.team.members.size(), crew.team.members.size());
        row.addView(names(context, crew.team.name, members), namesParams(dp));
        ViewCompat.setAccessibilityHeading(row, true);
        return row;
    }

    private static LinearLayout.LayoutParams namesParams(float dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Math.round(12 * dp));
        return lp;
    }

    private static View names(Context context, String name, @Nullable String line) {
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(name);
        title.setTextSize(16);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        try {
            title.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        names.addView(title);
        if (line != null && !line.isEmpty()) {
            TextView sub = new TextView(context);
            sub.setText(line);
            sub.setTextSize(13);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            names.addView(sub);
        }
        return names;
    }

    /** Shows the sheet with its own motion (a short scale and fade, the system's animation scale applies). */
    private static void show(AlertDialog dialog, @Nullable View anchor) {
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.ProjectsMenuSheetAnimation);
        dialog.show();
    }

    /** Whether a profile is the signed-in player (no menu on one's own row). */
    static boolean isMe(Context context, String userId) {
        String me = SocialService.get(context).state().myId();
        return me != null && me.equals(userId);
    }
}
