package gg.projects.mobile.social.ui;

import android.content.Context;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.Random;

import gg.projects.mobile.social.FriendGroups;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;

/**
 * MOB-PARITY: friend categories on the phone (the desktop's category header menu and "Kategoriye taşı"). A friend's
 * menu moves them into a category, back to "Arkadaşlar" or into a new one; a category's header opens its menu
 * ("Yeniden adlandır", "Yukarı taşı", "Aşağı taşı", "Yeni kategori…", "Kategoriyi sil"). Every change shows at once and
 * is saved by {@link SocialService#saveGroups}; deleting asks nothing and offers "Geri al", as on the desktop.
 */
final class FriendGroupSheets {
    private FriendGroupSheets() {}

    private static final Random RANDOM = new Random();

    /** "Kategoriye taşı": the categories, the current one marked, "Arkadaşlar" last, then "Yeni kategori…". */
    static void moveTo(final FragmentActivity activity, final Profile friend) {
        final SocialService service = SocialService.get(activity);
        final FriendGroups doc = service.state().groups();
        if (doc == null) return;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        TextView quote = sheet.findViewById(R.id.projects_actions_quote);
        quote.setText(activity.getString(R.string.projects_parity_groups_move_to));
        quote.setVisibility(View.VISIBLE);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        String current = doc.groupOf(friend.id);
        for (final FriendGroups.Group group : doc.groups) {
            list.addView(choiceRow(activity, group.name, group.id.equals(current), v -> {
                dialog.dismiss();
                move(activity, friend, group.id);
            }));
        }
        list.addView(choiceRow(activity, activity.getString(R.string.projects_parity_groups_rest), current == null, v -> {
            dialog.dismiss();
            move(activity, friend, null);
        }));
        list.addView(CrewViews.divider(activity));
        View add = CrewViews.actionRow(activity, R.drawable.projects_ic_plus, activity.getString(R.string.projects_parity_groups_new_menu),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    if (doc.full()) {
                        SocialUi.toast(activity, activity.getString(R.string.projects_parity_groups_limit));
                        return;
                    }
                    askName(activity, null, name -> {
                        FriendGroups now = service.state().groups();
                        if (now == null) return;
                        FriendGroups made = now.withNew(name, RANDOM);
                        if (made == now) return;
                        String id = made.lastId();
                        save(activity, made.withMember(friend.id, id), activity.getString(R.string.projects_parity_groups_moved,
                                friend.name(), made.group(id) == null ? name : made.group(id).name));
                    });
                });
        list.addView(add);
        show(dialog);
    }

    private static void move(FragmentActivity activity, Profile friend, @Nullable String groupId) {
        FriendGroups doc = SocialService.get(activity).state().groups();
        if (doc == null) return;
        String current = doc.groupOf(friend.id);
        if (groupId == null ? current == null : groupId.equals(current)) return;
        FriendGroups.Group group = doc.group(groupId);
        String done = group == null ? activity.getString(R.string.projects_parity_groups_moved_rest, friend.name())
                : activity.getString(R.string.projects_parity_groups_moved, friend.name(), group.name);
        save(activity, doc.withMember(friend.id, groupId), done);
    }

    /** A category header's menu. */
    static void groupMenu(final FragmentActivity activity, final String groupId) {
        final SocialService service = SocialService.get(activity);
        final FriendGroups doc = service.state().groups();
        final FriendGroups.Group group = doc == null ? null : doc.group(groupId);
        if (group == null) return;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        TextView quote = sheet.findViewById(R.id.projects_actions_quote);
        quote.setText(group.name);
        quote.setVisibility(View.VISIBLE);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        int at = doc.groups.indexOf(group);
        list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_pencil, activity.getString(R.string.projects_parity_groups_rename),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    askName(activity, group.name, name -> {
                        FriendGroups now = service.state().groups();
                        if (now != null) save(activity, now.withName(groupId, name), null);
                    });
                }));
        if (at > 0) {
            list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_arrow_up_bold, activity.getString(R.string.projects_parity_groups_move_up),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        FriendGroups now = service.state().groups();
                        if (now != null) save(activity, now.moved(groupId, -1), null);
                    }));
        }
        if (at < doc.groups.size() - 1) {
            list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_arrow_down_bold, activity.getString(R.string.projects_parity_groups_move_down),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        FriendGroups now = service.state().groups();
                        if (now != null) save(activity, now.moved(groupId, 1), null);
                    }));
        }
        list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_plus, activity.getString(R.string.projects_parity_groups_new_menu),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    if (doc.full()) {
                        SocialUi.toast(activity, activity.getString(R.string.projects_parity_groups_limit));
                        return;
                    }
                    askName(activity, null, name -> {
                        FriendGroups now = service.state().groups();
                        if (now != null) save(activity, now.withNew(name, RANDOM), null);
                    });
                }));
        list.addView(CrewViews.divider(activity));
        list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_trash, activity.getString(R.string.projects_parity_groups_remove),
                R.color.projects_flare_400, v -> {
                    dialog.dismiss();
                    final FriendGroups before = service.state().groups();
                    if (before == null) return;
                    save(activity, before.without(groupId), null);
                    // No question (the desktop's): the line offers "Geri al", which saves the categories as they were.
                    SocialUi.toastAction(activity, activity.getString(R.string.projects_parity_groups_removed, group.name),
                            activity.getString(R.string.projects_undo), () -> save(activity, before, null));
                }));
        show(dialog);
    }

    private static void save(final FragmentActivity activity, FriendGroups next, @Nullable final String done) {
        SocialService.get(activity).saveGroups(next, new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
                if (done != null) SocialUi.toast(activity, done);
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toast(activity, activity.getString(R.string.projects_parity_groups_failed));
            }
        });
    }

    private interface Named {
        void named(String name);
    }

    /** "Yeni kategori" or "Yeniden adlandır": one field, "Ekle" or "Kaydet", "Vazgeç". */
    private static void askName(final FragmentActivity activity, @Nullable String current, final Named named) {
        Context c = activity;
        FrameLayout box = new FrameLayout(c);
        int pad = dp(c, 20);
        box.setPadding(pad, dp(c, 8), pad, 0);
        final EditText input = new EditText(c);
        input.setBackgroundResource(R.drawable.projects_input);
        try {
            input.setTypeface(ResourcesCompat.getFont(c, R.font.figtree_regular));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
        input.setHintTextColor(ContextCompat.getColor(c, R.color.projects_ash_400));
        input.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setMinimumHeight(dp(c, 52));
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(FriendGroups.NAME_MAX * 2)});
        if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setHint(R.string.projects_parity_groups_name);
        input.setContentDescription(c.getString(R.string.projects_parity_groups_name));
        if (current != null) {
            input.setText(current);
            input.setSelection(input.getText().length());
        }
        box.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(current == null ? R.string.projects_parity_groups_new : R.string.projects_parity_groups_rename)
                .setView(box)
                .setPositiveButton(current == null ? R.string.projects_parity_groups_create : R.string.projects_parity_groups_save, null)
                .setNegativeButton(R.string.projects_social_cancel, null)
                .create();
        final Runnable submit = () -> {
            String name = FriendGroups.cleanName(input.getText().toString());
            if (name.isEmpty()) {
                Haptics.reject(input);
                return;
            }
            dialog.dismiss();
            if (!name.equals(current)) named.named(name);
        };
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
            input.requestFocus();
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            submit.run();
            return true;
        });
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    /** A choice among the categories: its name, a check on the one the friend is in. */
    private static View choiceRow(Context context, String name, boolean chosen, View.OnClickListener click) {
        TextView row = CrewViews.actionRow(context, R.drawable.projects_ic_check, name,
                chosen ? R.color.projects_bone_100 : R.color.projects_ash_300, click);
        // The check's place is kept on every row, so the names line up.
        android.graphics.drawable.Drawable check = row.getCompoundDrawablesRelative()[0];
        if (check != null && !chosen) check.setAlpha(0);
        row.setSelected(chosen);
        if (Build.VERSION.SDK_INT >= 30 && chosen) row.setStateDescription(context.getString(R.string.projects_parity_role_on));
        return row;
    }

    private static void show(AlertDialog dialog) {
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.ProjectsMenuSheetAnimation);
        dialog.show();
        gg.projects.mobile.ui.FocusRing.attach(dialog);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
