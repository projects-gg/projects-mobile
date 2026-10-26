package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.cloud.CloudBulk;
import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudText;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.Haptics;

/**
 * "Ayarları kopyala" (CAFK-FEAT §3.4): first which account's settings, then what is copied to the chosen ones, the server
 * and version only when ticked, each secret line typed for the accounts (each its own, or the same for all: every one is
 * sealed for its own account on this phone), one sentence of what happens, and "Kopyala".
 */
final class CloudCopySheet {
    private CloudCopySheet() {}

    interface Done {
        void copied(CloudBulk.Outcome outcome);
    }

    static void open(final Context c, final CloudService service, final List<CloudModel.Account> accounts, final Set<String> chosen, final Done done) {
        final ScrollView scroll = new ScrollView(c);
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 16));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final BottomPanel panel = BottomPanel.of(c, scroll, 0.92f);
        root.addView(title(c, c.getString(R.string.projects_feat_copy_from)));
        CharSequence[] names = new CharSequence[accounts.size()];
        for (int i = 0; i < names.length; i++) names[i] = accounts.get(i).name;
        root.addView(CloudUi.choices(c, names, -1, i -> details(c, service, root, panel, accounts.get(i), accounts, chosen, done)));
        panel.show();
    }

    private static TextView title(Context c, String text) {
        TextView heading = CloudUi.text(c, text, 17, R.color.projects_bone_100, true);
        heading.setTypeface(CloudUi.font(c, R.font.tektur_semibold));
        heading.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 2), CloudUi.dp(c, 8), CloudUi.dp(c, 8));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        return heading;
    }

    private static void details(final Context c, final CloudService service, final LinearLayout root, final BottomPanel panel,
                                final CloudModel.Account source, List<CloudModel.Account> accounts, Set<String> chosen, final Done done) {
        final List<CloudModel.Account> targets = new ArrayList<>();
        for (CloudModel.Account a : accounts) if (chosen.contains(a.id) && !a.id.equals(source.id)) targets.add(a);
        root.removeAllViews();
        root.addView(title(c, c.getString(R.string.projects_feat_copy_title, source.name)));
        if (targets.isEmpty()) {
            root.addView(CloudUi.note(c, c.getString(R.string.projects_feat_copy_pick_targets)));
            return;
        }
        List<String> targetNames = new ArrayList<>();
        for (CloudModel.Account t : targets) targetNames.add(t.name);
        root.addView(CloudUi.note(c, c.getString(R.string.projects_feat_copy_to, TextUtils.join(", ", targetNames))));
        root.addView(CloudUi.note(c, c.getString(R.string.projects_feat_copy_what)));
        LinearLayout serverGroup = CloudUi.group(c);
        final boolean[] server = {false};
        SwitchCompat serverToggle = CloudUi.toggle(c, c.getString(R.string.projects_feat_copy_server), false, on -> server[0] = on);
        serverGroup.addView(serverToggle);
        LinearLayout.LayoutParams groupLp = CloudUi.matchWrap();
        groupLp.topMargin = CloudUi.dp(c, 8);
        root.addView(serverGroup, groupLp);

        // The secret lines: typed for each account, or once for all (each sealed for its own account).
        final Map<String, String> secrets = CloudBulk.secretRows(source.config);
        final LinearLayout fields = new LinearLayout(c);
        fields.setOrientation(LinearLayout.VERTICAL);
        final Map<String, CloudUi.Field> inputs = new LinkedHashMap<>();
        final boolean[] same = {false};
        final Runnable[] drawFields = new Runnable[1];
        drawFields[0] = () -> {
            fields.removeAllViews();
            inputs.clear();
            for (Map.Entry<String, String> row : secrets.entrySet()) {
                String preview = row.getValue();
                String first = preview.endsWith("******") ? preview.substring(0, preview.length() - 6) : "";
                if (same[0]) {
                    CloudUi.Field f = secretField(c, preview, first);
                    inputs.put("*|" + row.getKey(), f);
                    fields.addView(f.view);
                } else {
                    for (CloudModel.Account t : targets) {
                        CloudUi.Field f = secretField(c, c.getString(R.string.projects_feat_copy_secret_for, preview, t.name), first);
                        inputs.put(t.id + "|" + row.getKey(), f);
                        fields.addView(f.view);
                    }
                }
            }
        };
        if (!secrets.isEmpty()) {
            root.addView(CloudUi.gap(c, 10));
            root.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_feat_copy_secret_each),
                    c.getString(R.string.projects_feat_copy_secret_same)}, 0, i -> {
                same[0] = i == 1;
                drawFields[0].run();
            }));
            drawFields[0].run();
            root.addView(fields, CloudUi.matchWrap());
        }
        root.addView(CloudUi.note(c, c.getString(R.string.projects_feat_copy_confirm)));
        final TextView error = CloudUi.text(c, "", 13, R.color.projects_flare_400, false);
        error.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), 0);
        error.setVisibility(View.GONE);
        root.addView(error);
        final Button go = CloudUi.button(c, c.getString(R.string.projects_feat_copy_go), true, null);
        go.setOnClickListener(v -> {
            Map<String, Map<String, String>> typed = new HashMap<>();
            for (CloudModel.Account t : targets) typed.put(t.id, new HashMap<String, String>());
            for (Map.Entry<String, CloudUi.Field> e : inputs.entrySet()) {
                String text = e.getValue().value().trim();
                CloudText.Problem problem = CloudText.check(text);
                String row = e.getKey().substring(e.getKey().indexOf('|') + 1);
                String preview = secrets.get(row);
                String first = preview != null && preview.endsWith("******") ? preview.substring(0, preview.length() - 6).trim() : "";
                if (problem != null || text.equals(first)) {
                    Haptics.reject(v);
                    e.getValue().setError(c.getString(R.string.projects_feat_copy_secret_missing));
                    e.getValue().input.requestFocus();
                    return;
                }
                e.getValue().setError(null);
                String owner = e.getKey().substring(0, e.getKey().indexOf('|'));
                for (CloudModel.Account t : targets) {
                    if ("*".equals(owner) || owner.equals(t.id)) typed.get(t.id).put(row, text);
                }
            }
            go.setEnabled(false);
            List<String> ids = new ArrayList<>();
            for (CloudModel.Account t : targets) ids.add(t.id);
            service.copySettings(source, ids, server[0], typed, new CloudService.Result<CloudBulk.Outcome>() {
                @Override
                public void ok(CloudBulk.Outcome outcome) {
                    Haptics.confirm(v);
                    panel.dismiss();
                    done.copied(outcome);
                }

                @Override
                public void failed(CloudProblem problem) {
                    go.setEnabled(true);
                    Haptics.reject(v);
                    error.setText(gg.projects.mobile.cloud.CloudWords.problem(c, problem));
                    error.setVisibility(View.VISIBLE);
                }
            });
        });
        LinearLayout.LayoutParams goLp = CloudUi.matchWrap();
        goLp.topMargin = CloudUi.dp(c, 12);
        goLp.leftMargin = CloudUi.dp(c, 8);
        goLp.rightMargin = CloudUi.dp(c, 8);
        root.addView(go, goLp);
    }

    /** One secret line: the command's first word already typed, the rest the player's (visible, no suggestions). */
    private static CloudUi.Field secretField(Context c, String label, String first) {
        CloudUi.Field f = CloudUi.field(c, label, first, null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        f.input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});
        f.input.setSelection(f.input.getText().length());
        return f;
    }
}
