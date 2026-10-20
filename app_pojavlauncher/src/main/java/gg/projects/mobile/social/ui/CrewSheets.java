package gg.projects.mobile.social.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import gg.projects.mobile.social.MutePresets;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamNotifications;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/** The crew page's sheets: notifications ("Sessize al"), inviting friends, a member's card. */
final class CrewSheets {
    private CrewSheets() {}

    /**
     * "Ekip bildirimleri": "Ekip çağrıları" on or off and "Sessize al" (the owner's presets: 1 saat,
     * 8 saat, Yarın sabaha kadar, Ben açana kadar) or "Sesi aç". With {@code mute} both live on the
     * service and follow the player to every device; a service without it keeps only this phone's
     * switch. A muted player still sees calls on the crew page and can answer them.
     */
    static void notifications(final FragmentActivity activity) {
        final SocialService service = SocialService.get(activity);
        final SocialGate gate = service.gate();
        final TeamNotifications notifications = service.state().teamNotifications();
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(20 * dp), Math.round(8 * dp), Math.round(20 * dp), 0);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_crew_notifications)
                .setView(wrap(activity, content))
                .setNegativeButton(R.string.projects_close, null)
                .create();

        final SwitchCompat calls = new SwitchCompat(activity);
        calls.setText(R.string.projects_crew_calls);
        calls.setTextSize(16);
        calls.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
        calls.setMinHeight(Math.round(56 * dp));
        calls.setChecked(gate.mute ? notifications.calls : service.prefs().wantsCallsLocally());
        calls.setOnCheckedChangeListener((button, on) -> {
            Haptics.toggle(button, on);
            service.setCrewCalls(on, result(activity, null));
        });
        content.addView(calls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (gate.mute) {
            long now = service.state().hubNow(SocialService.nowSeconds());
            if (notifications.mutedAt(now)) {
                TextView state = new TextView(activity);
                state.setText(mutedText(activity, notifications, now));
                state.setTextColor(ContextCompat.getColor(activity, R.color.projects_gold_300));
                state.setTextSize(14);
                state.setPadding(0, Math.round(10 * dp), 0, Math.round(4 * dp));
                content.addView(state);
                Button unmute = new Button(activity, null, android.R.attr.borderlessButtonStyle);
                unmute.setText(R.string.projects_crew_unmute);
                unmute.setAllCaps(false);
                unmute.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
                unmute.setBackground(ChamferDrawable.outlined(activity));
                Press.attach(unmute, Press.Style.BUTTON);
                unmute.setOnClickListener(v -> {
                    dialog.dismiss();
                    service.unmute(result(activity, null));
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Math.round(4 * dp);
                content.addView(unmute, lp);
            }
            TextView title = new TextView(activity);
            title.setText(R.string.projects_crew_mute);
            title.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_300));
            title.setTextSize(13);
            title.setPadding(0, Math.round(18 * dp), 0, Math.round(4 * dp));
            try {
                title.setTypeface(ResourcesCompat.getFont(activity, R.font.figtree_semibold));
            } catch (RuntimeException ignored) {
                // The theme's font.
            }
            content.addView(title);
            String morning = MutePresets.morningIsTomorrow(now, TimeZone.getDefault())
                    ? activity.getString(R.string.projects_crew_tomorrow_at, CrewViews.time(activity, MutePresets.nextMorning(now, TimeZone.getDefault())))
                    : activity.getString(R.string.projects_crew_today_at, CrewViews.time(activity, MutePresets.nextMorning(now, TimeZone.getDefault())));
            addPreset(activity, content, dialog, MutePresets.Preset.HOUR, activity.getString(R.string.projects_crew_mute_hour), null);
            addPreset(activity, content, dialog, MutePresets.Preset.EIGHT_HOURS, activity.getString(R.string.projects_crew_mute_eight), null);
            addPreset(activity, content, dialog, MutePresets.Preset.MORNING, activity.getString(R.string.projects_crew_mute_morning), morning);
            addPreset(activity, content, dialog, MutePresets.Preset.UNTIL_UNMUTED, activity.getString(R.string.projects_crew_mute_forever), null);
        }
        dialog.show();
    }

    private static View wrap(Context context, View content) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        return scroll;
    }

    private static void addPreset(final FragmentActivity activity, LinearLayout content, final AlertDialog dialog,
                                  final MutePresets.Preset preset, String label, String detail) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(52 * dp));
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        row.setPaddingRelative(Math.round(8 * dp), 0, Math.round(8 * dp), 0);
        row.setClickable(true);
        row.setFocusable(true);
        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextSize(16);
        text.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (detail != null) {
            TextView sub = new TextView(activity);
            sub.setText(detail);
            sub.setTextSize(13);
            sub.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
            row.addView(sub);
        }
        Press.attach(row, Press.Style.ROW);
        final SocialService service = SocialService.get(activity);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            service.mute(preset, result(activity, v));
        });
        content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** "Sessizde · Yarın 08:00", or "Sessizde" until unmuted. */
    static String mutedText(Context context, TeamNotifications notifications, long nowSeconds) {
        if (notifications.muteUntil == null) return context.getString(R.string.projects_crew_muted);
        return context.getString(R.string.projects_crew_muted_until, CrewViews.moment(context, notifications.muteUntil, nowSeconds));
    }

    private static SocialService.Result<TeamNotifications> result(final FragmentActivity activity, final View feedback) {
        return new SocialService.Result<TeamNotifications>() {
            @Override
            public void ok(TeamNotifications value) {
                if (feedback != null) Haptics.confirm(feedback);
                long now = SocialService.get(activity).state().hubNow(SocialService.nowSeconds());
                if (value.mutedAt(now)) SocialUi.toast(activity, mutedText(activity, value, now));
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, null);
            }
        };
    }

    /** "Arkadaşlarını davet et": friends who are not in the crew, each with "Davet et" (or "Davet edildi"). */
    static void inviteFriends(final FragmentActivity activity) {
        final SocialService service = SocialService.get(activity);
        final Team team = service.state().team();
        if (team == null) return;
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(12 * dp), Math.round(8 * dp), Math.round(12 * dp), 0);
        List<Friend> friends = new ArrayList<>();
        for (Friend f : service.state().friends()) if (team.member(f.profile.id) == null) friends.add(f);
        if (friends.isEmpty()) {
            TextView none = new TextView(activity);
            none.setText(R.string.projects_crew_invite_none);
            none.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
            none.setTextSize(14);
            none.setPadding(Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp));
            content.addView(none);
        }
        for (final Friend friend : friends) {
            View row = android.view.LayoutInflater.from(activity).inflate(R.layout.item_projects_social_person, content, false);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            lp.setMarginStart(0);
            lp.setMarginEnd(0);
            AvatarView avatar = row.findViewById(R.id.projects_person_avatar);
            avatar.setProfile(friend.profile);
            avatar.setSurfaceColor(ContextCompat.getColor(activity, R.color.projects_obsidian_800));
            SocialModel.Presence p = service.state().presence(friend.profile.id);
            avatar.setDot(SocialUi.dot(p));
            ((TextView) row.findViewById(R.id.projects_person_name)).setText(friend.profile.name());
            ((TextView) row.findViewById(R.id.projects_person_line)).setText(SocialUi.presenceText(activity, p));
            final Button invite = row.findViewById(R.id.projects_person_action);
            invite.setVisibility(View.VISIBLE);
            invite.setBackground(ChamferDrawable.outlined(activity));
            Press.attach(invite, Press.Style.BUTTON);
            boolean invited = service.state().teamInvited().contains(friend.profile.id);
            invite.setText(invited ? R.string.projects_crew_invite_sent : R.string.projects_crew_invite);
            invite.setEnabled(!invited);
            invite.setOnClickListener(v -> {
                invite.setEnabled(false);
                service.inviteToTeam(team.id, friend.profile.id, new SocialService.Result<Void>() {
                    @Override
                    public void ok(Void value) {
                        Haptics.confirm(invite);
                        invite.setText(R.string.projects_crew_invite_sent);
                    }

                    @Override
                    public void failed(SocialProblem problem) {
                        invite.setEnabled(true);
                        SocialUi.toastProblem(activity, problem, invite);
                    }
                });
            });
            content.addView(row);
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_crew_invite_title)
                .setView(wrap(activity, content))
                .setNegativeButton(R.string.projects_close, null)
                .show();
    }

    /**
     * A crewmate's card: where they are, "Katıl", "Discord'dan mesaj at"; for the leader "Liderliği
     * devret", and "Ekipten çıkar" for whoever the crew's permissions let.
     */
    static void member(final FragmentActivity activity, final String userId) {
        final SocialService service = SocialService.get(activity);
        final Team team = service.state().team();
        final Member member = team == null ? null : team.member(userId);
        String me = service.state().myId();
        if (member == null || me == null) return;
        if (service.state().isFriend(userId) && !hasCrewPower(team, me, member, service.gate())) {
            // A friend: the friend card has everything (and "Katıl").
            FriendCard.show(activity, userId);
            return;
        }
        View view = android.view.LayoutInflater.from(activity).inflate(R.layout.dialog_projects_friend, null, false);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(view).create();
        AvatarView avatar = view.findViewById(R.id.projects_card_avatar);
        avatar.setProfile(member.profile);
        avatar.setSurfaceColor(ContextCompat.getColor(activity, R.color.projects_obsidian_800));
        SocialModel.Presence presence = service.state().presence(userId);
        avatar.setDot(SocialUi.dot(presence));
        ((TextView) view.findViewById(R.id.projects_card_name)).setText(member.profile.name());
        TextView role = view.findViewById(R.id.projects_card_username);
        role.setText(member.founder ? R.string.projects_crew_leader : member.moderator && service.gate().roles
                ? R.string.projects_crew_moderator : R.string.projects_crew_title);
        role.setVisibility(member.founder || (member.moderator && service.gate().roles) ? View.VISIBLE : View.GONE);
        TextView where = view.findViewById(R.id.projects_card_presence);
        where.setText(SocialUi.presenceText(activity, presence));
        view.findViewById(R.id.projects_card_since).setVisibility(View.GONE);
        View join = view.findViewById(R.id.projects_card_join);
        join.setVisibility(presence.onProjects() ? View.VISIBLE : View.GONE);
        Press.attach(join, Press.Style.BUTTON);
        join.setOnClickListener(v -> {
            dialog.dismiss();
            SocialUi.joinProjects(activity);
        });
        LinearLayout actions = view.findViewById(R.id.projects_card_actions);
        actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_discord_glyph, activity.getString(R.string.projects_social_message),
                R.color.projects_bone_100, v -> SocialUi.openDiscord(activity, member.profile)));
        Member mine = team.member(me);
        boolean powers = false;
        if (mine != null && mine.founder) {
            powers = true;
            actions.addView(CrewViews.divider(activity));
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_crown_fill, activity.getString(R.string.projects_crew_hand_over),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        // The old leader becomes a moderator while the crew has room for one (at most 4).
                        int moderators = 0;
                        for (Member m : team.members) if (m.moderator && !m.profile.id.equals(userId)) moderators++;
                        boolean room = moderators < 4;
                        String ask = activity.getString(service.gate().roles && room ? R.string.projects_crew_hand_over_ask_moderator
                                : R.string.projects_crew_hand_over_ask, member.profile.name());
                        SocialUi.confirm(activity, activity.getString(R.string.projects_crew_hand_over), ask,
                                activity.getString(R.string.projects_crew_hand_over), () -> service.handOver(team.id, userId,
                                        new SocialService.Result<JsonObject>() {
                                            @Override
                                            public void ok(JsonObject value) {
                                                SocialUi.toast(activity, activity.getString(R.string.projects_crew_handed_over, member.profile.name()));
                                            }

                                            @Override
                                            public void failed(SocialProblem problem) {
                                                SocialUi.toastProblem(activity, problem, null);
                                            }
                                        }));
                    }));
        }
        if (mayRemove(team, mine, member, service.gate())) {
            if (!powers) actions.addView(CrewViews.divider(activity));
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_user_minus, activity.getString(R.string.projects_crew_remove),
                    R.color.projects_flare_400, v -> {
                        dialog.dismiss();
                        SocialUi.confirm(activity, activity.getString(R.string.projects_crew_remove),
                                activity.getString(R.string.projects_crew_remove_ask, member.profile.name()),
                                activity.getString(R.string.projects_crew_remove), () -> service.removeMember(team.id, userId,
                                        FriendCard.done(activity, activity.getString(R.string.projects_crew_removed, member.profile.name()))));
                    }));
        }
        dialog.show();
    }

    private static boolean hasCrewPower(Team team, String me, Member member, SocialGate gate) {
        Member mine = team.member(me);
        return mine != null && (mine.founder || mayRemove(team, mine, member, gate));
    }

    /** The leader removes anyone; a moderator a plain member while the crew's permissions let moderators. */
    static boolean mayRemove(Team team, Member mine, Member member, SocialGate gate) {
        if (mine == null || member.founder || mine.profile.id.equals(member.profile.id)) return false;
        if (mine.founder) return true;
        if (!gate.roles || !mine.moderator || member.moderator) return false;
        return SocialModel.Permissions.allows(team.permissions.kick, false, true);
    }
}
