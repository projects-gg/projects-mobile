package gg.projects.mobile.social.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.SocialModel.Request;
import gg.projects.mobile.social.SocialModel.TeamInvite;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;

/**
 * M20: the cards of what waits for an answer, so it can be reported where it is (MOD1: "Rapor et" in a
 * friend request and in a crew invite): a crew invite (the crew's mark, who invited, "Kabul et",
 * "Reddet", "Ekibi rapor et") and a friend request ("Kabul et", "Reddet", "Engelle", "Rapor et").
 */
final class InviteCard {
    private InviteCard() {}

    static void show(final FragmentActivity activity, final TeamInvite invite) {
        final SocialService service = SocialService.get(activity);
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(Math.round(10 * dp), 0, Math.round(10 * dp), Math.round(10 * dp));
        CrewMarkView mark = new CrewMarkView(activity);
        mark.setLook(invite.team.emblem, invite.team.color, invite.team.frame);
        head.addView(mark, new LinearLayout.LayoutParams(Math.round(48 * dp), Math.round(48 * dp)));
        LinearLayout names = new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(invite.team.tag.isEmpty() ? invite.team.name : invite.team.name + " [" + invite.team.tag + "]");
        name.setTextSize(17);
        name.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
        names.addView(name);
        TextView by = new TextView(activity);
        by.setText(activity.getString(R.string.projects_crew_invited_by, invite.from.name()));
        by.setTextSize(13);
        by.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
        names.addView(by);
        if (invite.team.motto != null) {
            TextView motto = new TextView(activity);
            motto.setText(invite.team.motto);
            motto.setTextSize(13);
            motto.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_300));
            names.addView(motto);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Math.round(12 * dp));
        head.addView(names, lp);
        list.addView(head);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_check, activity.getString(R.string.projects_social_accept),
                R.color.projects_gold_300, v -> {
                    dialog.dismiss();
                    service.acceptTeamInvite(invite.team.id, new SocialService.Result<JsonObject>() {
                        @Override
                        public void ok(JsonObject value) {
                            Haptics.confirm(v);
                            SocialUi.toast(activity, activity.getString(R.string.projects_crew_joined, invite.team.name));
                            gg.projects.mobile.social.SocialModel.Team joined = gg.projects.mobile.social.SocialModel.Team.read(
                                    gg.projects.mobile.social.Json.obj(value, "team"));
                            CrewFragment.selectNext(joined == null ? null : joined.id);
                        }

                        @Override
                        public void failed(SocialProblem problem) {
                            SocialUi.toastProblem(activity, problem, null);
                        }
                    });
                }));
        list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_x, activity.getString(R.string.projects_social_decline),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    service.declineTeamInvite(invite.team.id, quiet(activity));
                }));
        if (service.gate().reports) {
            list.addView(CrewViews.divider(activity));
            list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_flag, activity.getString(R.string.projects_report_crew),
                    R.color.projects_ash_300, v -> {
                        dialog.dismiss();
                        ReportDialog.crew(activity, invite.team, null);
                    }));
        }
        dialog.show();
    }

    static void request(final FragmentActivity activity, final Request request) {
        final SocialService service = SocialService.get(activity);
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(Math.round(10 * dp), 0, Math.round(10 * dp), Math.round(10 * dp));
        AvatarView avatar = new AvatarView(activity);
        avatar.setProfile(request.profile);
        avatar.setSurfaceColor(ContextCompat.getColor(activity, R.color.projects_obsidian_800));
        head.addView(avatar, new LinearLayout.LayoutParams(Math.round(48 * dp), Math.round(48 * dp)));
        LinearLayout names = new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(request.profile.name());
        name.setTextSize(17);
        name.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
        names.addView(name);
        if (!request.profile.username.isEmpty() && !request.profile.username.equals(request.profile.name())) {
            TextView user = new TextView(activity);
            user.setText("@" + request.profile.username);
            user.setTextSize(13);
            user.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
            names.addView(user);
        }
        TextView line = new TextView(activity);
        line.setText(R.string.projects_social_wants_to_add);
        line.setTextSize(13);
        line.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_300));
        names.addView(line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Math.round(12 * dp));
        head.addView(names, lp);
        list.addView(head);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_check, activity.getString(R.string.projects_social_accept),
                R.color.projects_gold_300, v -> {
                    dialog.dismiss();
                    service.accept(request.profile.id, new SocialService.Result<JsonObject>() {
                        @Override
                        public void ok(JsonObject value) {
                            SocialUi.toast(activity, activity.getString(R.string.projects_social_friend_added, request.profile.name()));
                        }

                        @Override
                        public void failed(SocialProblem problem) {
                            SocialUi.toastProblem(activity, problem, null);
                        }
                    });
                }));
        list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_x, activity.getString(R.string.projects_social_decline),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    service.decline(request.profile.id, quiet(activity));
                }));
        list.addView(CrewViews.divider(activity));
        if (service.gate().reports) {
            list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_flag, activity.getString(R.string.projects_report),
                    R.color.projects_ash_300, v -> {
                        dialog.dismiss();
                        ReportDialog.player(activity, request.profile);
                    }));
        }
        list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_prohibit, activity.getString(R.string.projects_social_block),
                R.color.projects_flare_400, v -> {
                    dialog.dismiss();
                    SocialUi.confirm(activity, activity.getString(R.string.projects_social_block_title, request.profile.name()),
                            activity.getString(R.string.projects_social_block_ask), activity.getString(R.string.projects_social_block),
                            () -> service.block(request.profile, FriendCard.done(activity,
                                    activity.getString(R.string.projects_social_blocked, request.profile.name()))));
                }));
        dialog.show();
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
}
