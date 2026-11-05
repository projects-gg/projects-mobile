package gg.projects.mobile.social.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.social.CrewRoleRules;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * MOB-PARITY "Yetkiler" (the desktop's crew page grid): what moderators and members of a crew may do, for its leader.
 * Each right has a "Moderatörler" and an "Üyeler" chip (removing members, editing the look and managing requests are
 * never the plain members'); a tap saves at once ("Yetkiler güncellendi"), one change at a time.
 */
final class CrewRoles {
    private CrewRoles() {}

    static void show(final FragmentActivity activity, final String crewId) {
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        final LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        final boolean[] busy = {false};
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            Crew crew = SocialService.get(activity).state().crew(crewId);
            if (crew == null) {
                dialog.dismiss();
                return;
            }
            list.removeAllViews();
            list.addView(title(activity));
            boolean requests = SocialService.get(activity).gate().requests;
            for (CrewRoleRules.Right right : CrewRoleRules.rights(requests)) {
                list.addView(rightRow(activity, crew, right, busy[0], (level, chip) -> {
                    if (busy[0]) return;
                    busy[0] = true;
                    render[0].run();
                    JsonObject permissions = new JsonObject();
                    permissions.addProperty(right.key, level);
                    JsonObject body = new JsonObject();
                    body.add("permissions", permissions);
                    SocialService.get(activity).editCrew(crewId, body, false, new SocialService.Result<JsonObject>() {
                        @Override
                        public void ok(JsonObject value) {
                            busy[0] = false;
                            if (dialog.isShowing()) render[0].run();
                            SocialUi.toast(activity, activity.getString(R.string.projects_parity_roles_saved));
                        }

                        @Override
                        public void failed(SocialProblem problem) {
                            busy[0] = false;
                            if (dialog.isShowing()) render[0].run();
                            SocialUi.toastProblem(activity, problem, null);
                        }
                    });
                }));
            }
        };
        render[0].run();
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.ProjectsMenuSheetAnimation);
        dialog.show();
        gg.projects.mobile.ui.FocusRing.attach(dialog);
    }

    private interface Pick {
        void level(String level, View chip);
    }

    private static TextView title(Context context) {
        TextView title = new TextView(context);
        title.setText(R.string.projects_parity_roles_title);
        title.setTextSize(17);
        title.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        try {
            title.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        int d = dp(context, 10);
        title.setPadding(d, 0, d, dp(context, 4));
        return title;
    }

    private static View rightRow(Context context, Crew crew, CrewRoleRules.Right right, boolean busy, Pick pick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 6));
        TextView label = new TextView(context);
        label.setText(label(right.key));
        label.setTextSize(15);
        label.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        row.addView(label);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        String level = CrewRoleRules.level(crew.team.permissions, right.key);
        List<View> made = new ArrayList<>();
        made.add(chip(context, R.string.projects_parity_roles_moderators, CrewRoleRules.moderatorsMay(level), busy,
                v -> pick.level(CrewRoleRules.toggleModerators(level), v)));
        if (right.members) {
            made.add(chip(context, R.string.projects_parity_roles_members, CrewRoleRules.membersMay(level), busy,
                    v -> pick.level(CrewRoleRules.toggleMembers(level), v)));
        }
        for (View chip : made) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48));
            lp.setMarginEnd(dp(context, 8));
            chips.addView(chip, lp);
        }
        row.addView(chips);
        return row;
    }

    private static View chip(Context context, int text, boolean on, boolean busy, View.OnClickListener click) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(14);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(context, 64));
        chip.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        chip.setTextColor(ContextCompat.getColor(context, on ? R.color.projects_bone_100 : R.color.projects_ash_300));
        // A 36 dp chip inside its 48 dp target.
        int inset = dp(context, 6);
        chip.setBackground(new android.graphics.drawable.InsetDrawable(SocialChips.choice(context, 7), 0, inset, 0, inset));
        chip.setSelected(on);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setEnabled(!busy);
        chip.setAlpha(busy ? 0.6f : 1f);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            chip.setStateDescription(context.getString(on ? R.string.projects_parity_role_on : R.string.projects_parity_role_off));
        }
        Press.attach(chip, Press.Style.BUTTON);
        chip.setOnClickListener(v -> {
            Haptics.toggle(v, !on);
            click.onClick(v);
        });
        return chip;
    }

    private static int label(String key) {
        switch (key) {
            case "invite": return R.string.projects_parity_roles_invite;
            case "call": return R.string.projects_parity_roles_call;
            case "kick": return R.string.projects_parity_roles_kick;
            case "edit": return R.string.projects_parity_roles_edit;
            default: return R.string.projects_parity_roles_admit;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
