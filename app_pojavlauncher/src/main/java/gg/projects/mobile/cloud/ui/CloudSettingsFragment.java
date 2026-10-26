package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudEdit;
import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudServer;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudStatus;
import gg.projects.mobile.cloud.CloudText;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Ayarlar" of a cloud account, in three tabs: General (name, server, version, console language, anti-AFK, the
 * player's own name), Commands and Auto-reply (rows edited in sheets). Changes wait in the screen until "Kaydet"; the
 * bar at the bottom shows while there are any, and leaving asks first. Secret lines are sealed on this phone when
 * saved; a server change seals revealed ones again and asks to retype the rest. A change made elsewhere meanwhile is
 * not overwritten: the screen offers the newer settings instead.
 */
public final class CloudSettingsFragment extends CloudScreen implements CloudRowSheet.Host {
    public static final String TAG = "ProjectsCloudSettingsFragment";
    private static final int GENERAL = 0;
    private static final int COMMANDS = 1;
    private static final int REPLIES = 2;

    @Nullable
    private CloudModel.Account mBase;
    private CloudEdit.Config mOriginal;
    private CloudEdit.Config mConfig;
    private String mName = "";
    private String mHost = "";
    private String mPort = "";
    private String mVersion = "auto";
    private String mLang = "tr";
    private int mTab = GENERAL;
    private final List<CloudEdit.Issue> mIssues = new ArrayList<>();
    private boolean mSaving;
    @Nullable
    private CloudModel.Account mConflict;
    @Nullable
    private CloudModel.Versions mVersions;
    @Nullable
    private SaveBar mBar;

    @Override
    protected void created(View view) {
        Context c = view.getContext();
        mHeader.setTitle(getString(R.string.projects_cloud_settings));
        if (mBottom != null) {
            mBar = new SaveBar(c, mBottom, () -> {
                if (mBase != null && !mSaving) begin(mBase);
            }, this::save);
        }
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a == null) {
            LinearLayout content = clear();
            if (content != null) content.addView(CloudUi.loading(ctx()));
            return;
        }
        mHeader.setCaption(a.name);
        if (mBase == null) {
            begin(a);
            return;
        }
        // A change made elsewhere while nothing is edited here: the screen shows it.
        if (a.rev != mBase.rev && !dirty() && !mSaving) begin(a);
    }

    private void begin(CloudModel.Account a) {
        mBase = a;
        mOriginal = CloudEdit.from(a.config);
        mConfig = mOriginal.copy();
        mName = a.name;
        mHost = a.server.host;
        mPort = a.server.port == 25565 || a.server.port == 0 ? "" : String.valueOf(a.server.port);
        mVersion = a.version == null || a.version.isEmpty() ? "auto" : a.version;
        mLang = "en".equals(a.lang) ? "en" : "tr";
        mIssues.clear();
        mConflict = null;
        tabs();
        draw();
        bar();
        if (mVersions == null) {
            mService.versions(new CloudService.Result<CloudModel.Versions>() {
                @Override
                public void ok(CloudModel.Versions value) {
                    mVersions = value;
                    if (alive() && mTab == GENERAL && !hasFocusedField()) draw();
                }

                @Override
                public void failed(CloudProblem problem) {}
            });
        }
    }

    private boolean hasFocusedField() {
        View focused = getView() == null ? null : getView().findFocus();
        return focused instanceof EditText;
    }

    // ---- what changed ----

    private int portValue() {
        // Empty: the base's own form of the default (0 asks the server's SRV record; 0 and 25565 are one server).
        if (mPort.trim().isEmpty()) return mBase != null && mBase.server.port == 25565 ? 25565 : 0;
        try {
            return Integer.parseInt(mPort.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean serverDirty() {
        if (mBase == null) return false;
        if (mHost.trim().isEmpty()) return true;
        return !CloudServer.sameServer(new CloudServer(mHost.trim(), Math.max(0, portValue())), mBase.server);
    }

    private boolean dirty() {
        if (mBase == null) return false;
        return !mName.equals(mBase.name) || serverDirty() || !mVersion.equals(mBase.version == null ? "auto" : mBase.version)
                || !mLang.equals("en".equals(mBase.lang) ? "en" : "tr") || !mConfig.same(mOriginal);
    }

    @Nullable
    private String issue(String place, int index, String field) {
        for (CloudEdit.Issue i : mIssues) {
            if (i.place.equals(place) && i.index == index && (field == null || i.field.equals(field))) {
                return CloudWords.issue(ctx(), i, mService.limits());
            }
        }
        return null;
    }

    // ---- drawing ----

    private void tabs() {
        if (mTop == null) return;
        mTop.removeAllViews();
        CharSequence[] names = {getString(R.string.projects_cloud_settings_general), getString(R.string.projects_cloud_settings_commands),
                getString(R.string.projects_cloud_settings_replies)};
        LinearLayout tabs = CloudUi.segmented(ctx(), names, mTab, i -> {
            mTab = i;
            draw();
            if (mScroll != null) mScroll.scrollTo(0, 0);
        });
        tabs.setPadding(0, CloudUi.dp(ctx(), 2), 0, CloudUi.dp(ctx(), 6));
        mTop.addView(tabs);
        mTop.setVisibility(View.VISIBLE);
    }

    private void draw() {
        LinearLayout content = clear();
        if (content == null || mBase == null) return;
        Context c = ctx();
        if (mConflict != null) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_gold_300,
                    getString(R.string.projects_cloud_settings_conflict)));
            final CloudModel.Account newer = mConflict;
            content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_settings_load_latest), v -> begin(newer)),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        switch (mTab) {
            case COMMANDS: commands(content); break;
            case REPLIES: replies(content); break;
            default: general(content); break;
        }
    }

    private interface Typed {
        void typed(String text);
    }

    private static void watch(EditText input, final Typed typed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                typed.typed(s.toString());
            }
        });
    }

    private void general(LinearLayout content) {
        final Context c = ctx();
        final CloudModel.Account a = mBase;
        if (a == null) return;
        int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

        CloudUi.Field name = CloudUi.field(c, getString(R.string.projects_cloud_settings_name), mName, null, text);
        name.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        name.setError(issue("general", -1, "name"));
        watch(name.input, s -> {
            mName = s.trim();
            bar();
        });
        content.addView(name.view);

        LinearLayout server = new LinearLayout(c);
        server.setOrientation(LinearLayout.HORIZONTAL);
        server.setGravity(Gravity.TOP);
        CloudUi.Field host = CloudUi.field(c, getString(R.string.projects_cloud_settings_host), mHost, "play.projects.gg",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        host.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(253)});
        host.setError(issue("general", -1, "server"));
        watch(host.input, s -> {
            mHost = s;
            bar();
        });
        server.addView(host.view, CloudUi.weight(1f));
        CloudUi.Field port = CloudUi.field(c, getString(R.string.projects_cloud_settings_port), mPort,
                getString(R.string.projects_cloud_settings_port_hint), InputType.TYPE_CLASS_NUMBER);
        port.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        port.setError(issue("general", -1, "port"));
        watch(port.input, s -> {
            mPort = s;
            bar();
        });
        server.addView(port.view, new LinearLayout.LayoutParams(CloudUi.dp(c, 116), ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(server, CloudUi.matchWrap());
        if (a.serverChangesLimit > 0) {
            content.addView(CloudUi.note(c, getString(R.string.projects_cloud_settings_server_changes, a.serverChangesUsed, a.serverChangesLimit)));
        }

        content.addView(CloudRowSheet.label(c, getString(R.string.projects_cloud_settings_version)));
        content.addView(picker(c, versionLabel(mVersion), v -> versions()));
        String versionIssue = issue("general", -1, "version");
        if (versionIssue != null) {
            TextView error = CloudRowSheet.error(c);
            CloudRowSheet.show(error, versionIssue);
            content.addView(error);
        }

        content.addView(CloudRowSheet.label(c, getString(R.string.projects_cloud_settings_lang)));
        content.addView(CloudUi.segmented(c, new CharSequence[]{getString(R.string.projects_language_turkish), getString(R.string.projects_language_english)}, "en".equals(mLang) ? 1 : 0, i -> {
            mLang = i == 1 ? "en" : "tr";
            bar();
        }));

        content.addView(CloudUi.gap(c, 20));
        LinearLayout antiGroup = CloudUi.group(c);
        final LinearLayout delays = new LinearLayout(c);
        delays.setOrientation(LinearLayout.VERTICAL);
        SwitchCompat anti = CloudUi.toggle(c, getString(R.string.projects_cloud_settings_anti_afk), mConfig.antiAfk, on -> {
            mConfig.antiAfk = on;
            delays.setVisibility(on ? View.VISIBLE : View.GONE);
            bar();
        });
        antiGroup.addView(anti);
        content.addView(antiGroup);
        content.addView(CloudUi.note(c, getString(R.string.projects_cloud_settings_anti_afk_help)));
        LinearLayout pair = new LinearLayout(c);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        CloudUi.Field least = CloudUi.field(c, getString(R.string.projects_cloud_settings_at_least), String.valueOf(mConfig.delayMin), null,
                InputType.TYPE_CLASS_NUMBER);
        watch(least.input, s -> {
            mConfig.delayMin = CloudRowSheet.number(s, -1);
            bar();
        });
        CloudUi.Field most = CloudUi.field(c, getString(R.string.projects_cloud_settings_at_most), String.valueOf(mConfig.delayMax), null,
                InputType.TYPE_CLASS_NUMBER);
        watch(most.input, s -> {
            mConfig.delayMax = CloudRowSheet.number(s, -1);
            bar();
        });
        least.unit(getString(R.string.projects_cloud_settings_seconds));
        most.unit(getString(R.string.projects_cloud_settings_seconds));
        pair.addView(least.view, CloudUi.weight(1f));
        pair.addView(most.view, CloudUi.weight(1f));
        delays.addView(pair, CloudUi.matchWrap());
        String antiIssue = issue("general", -1, "antiAfk");
        if (antiIssue != null) {
            TextView error = CloudRowSheet.error(c);
            CloudRowSheet.show(error, antiIssue);
            delays.addView(error);
        }
        delays.setVisibility(mConfig.antiAfk ? View.VISIBLE : View.GONE);
        content.addView(delays, CloudUi.matchWrap());

        content.addView(CloudUi.gap(c, 8));
        CloudUi.Field owner = CloudUi.field(c, getString(R.string.projects_cloud_settings_owner), mConfig.owner, null, text);
        owner.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        owner.setHelp(getString(R.string.projects_cloud_settings_owner_help));
        owner.setError(issue("general", -1, "owner"));
        watch(owner.input, s -> {
            mConfig.owner = s.trim();
            bar();
        });
        content.addView(owner.view);

        // CAFK-FEAT: the account's own inventory choice, while the service offers the inventory (it applies at the
        // account's next connection; nothing restarts for it).
        if (a.features.inventory()) {
            content.addView(CloudUi.gap(c, 16));
            LinearLayout invGroup = CloudUi.group(c);
            boolean shown = mConfig.inventory != null ? mConfig.inventory : a.features.inventoryOn;
            SwitchCompat inv = CloudUi.toggle(c, getString(R.string.projects_feat_inv_setting), shown, on -> {
                mConfig.inventory = on;
                bar();
            });
            invGroup.addView(inv);
            content.addView(invGroup);
            content.addView(CloudUi.note(c, getString(R.string.projects_feat_inv_private)));
        }

        // Movement: the account's own choice while the service and its machine have it (README "Movement switches").
        // With no slot free it cannot be turned on: the switch waits, with the reason. Saving restarts the account, as
        // every saved change does (nothing extra to say).
        final CloudModel.MovementFeatures move = a.features.movement;
        if (a.features.movementSwitch() && move != null) {
            content.addView(CloudUi.gap(c, 16));
            LinearLayout moveGroup = CloudUi.group(c);
            boolean shown = mConfig.movement != null ? mConfig.movement : move.on;
            boolean blocked = !move.on && move.slot != null && !shown;
            SwitchCompat sw = CloudUi.toggle(c, getString(R.string.projects_feat_movement), shown, on -> {
                mConfig.movement = on;
                bar();
            });
            sw.setEnabled(!blocked);
            moveGroup.addView(sw);
            content.addView(moveGroup);
            String moveIssue = issue("general", -1, "movement");
            if (moveIssue != null) {
                TextView error = CloudRowSheet.error(c);
                CloudRowSheet.show(error, moveIssue);
                content.addView(error);
            }
            content.addView(CloudUi.note(c, blocked ? gg.projects.mobile.cloud.movement.Movement.slot(c, move.slot, 1)
                    : getString(R.string.projects_feat_move_setting_line)));
        }
    }

    /** A field-like row that opens a choice (the version list). */
    private View picker(Context c, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.projects_input);
        row.setMinimumHeight(CloudUi.dp(c, 52));
        row.setPadding(CloudUi.dp(c, 14), 0, CloudUi.dp(c, 10), 0);
        row.setClickable(true);
        row.setFocusable(true);
        TextView text = CloudUi.text(c, value, 16, R.color.projects_bone_100, false);
        row.addView(text, CloudUi.weight(1f));
        ImageView caret = new ImageView(c);
        caret.setImageResource(R.drawable.projects_ic_caret_down_bold);
        caret.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_300)));
        caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(caret, new LinearLayout.LayoutParams(CloudUi.dp(c, 18), CloudUi.dp(c, 18)));
        row.setOnClickListener(click);
        row.setContentDescription(getString(R.string.projects_cloud_settings_version) + ", " + value);
        Press.attach(row, Press.Style.ROW);
        LinearLayout.LayoutParams lp = CloudUi.matchWrap();
        lp.leftMargin = CloudUi.dp(c, 8);
        lp.rightMargin = CloudUi.dp(c, 8);
        lp.topMargin = CloudUi.dp(c, 4);
        row.setLayoutParams(lp);
        return row;
    }

    private String versionLabel(String value) {
        if ("auto".equals(value)) return getString(R.string.projects_cloud_settings_version_auto);
        if (mVersions != null) for (CloudModel.Version v : mVersions.versions) if (v.value.equals(value)) return v.label;
        return value;
    }

    /** The version list with a search, the recommended one marked. */
    private void versions() {
        final Context c = ctx();
        if (mVersions == null) {
            mService.versions(new CloudService.Result<CloudModel.Versions>() {
                @Override
                public void ok(CloudModel.Versions value) {
                    mVersions = value;
                    if (alive()) versions();
                }

                @Override
                public void failed(CloudProblem problem) {
                    if (alive()) problem(problem, null);
                }
            });
            return;
        }
        final CloudModel.Versions all = mVersions;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
        TextView title = CloudUi.text(c, getString(R.string.projects_cloud_settings_version), 18, R.color.projects_bone_100, true);
        title.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 4));
        root.addView(title);
        final EditText search = new EditText(c);
        search.setBackgroundResource(R.drawable.projects_input);
        search.setHint(getString(R.string.projects_cloud_settings_version_search));
        search.setHintTextColor(CloudUi.color(c, R.color.projects_ash_400));
        search.setTextColor(CloudUi.color(c, R.color.projects_bone_100));
        search.setTypeface(CloudUi.font(c, R.font.figtree_regular));
        search.setSingleLine(true);
        search.setMinHeight(CloudUi.dp(c, 48));
        search.setPadding(CloudUi.dp(c, 14), 0, CloudUi.dp(c, 14), 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams searchLp = CloudUi.matchWrap();
        searchLp.topMargin = CloudUi.dp(c, 6);
        searchLp.bottomMargin = CloudUi.dp(c, 6);
        root.addView(search, searchLp);
        ScrollView scroll = new ScrollView(c);
        final LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CloudUi.dp(c, 340)));
        final BottomPanel panel = BottomPanel.of(c, root, 0.85f);
        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                List<String[]> rows = new ArrayList<>();
                String auto = getString(R.string.projects_cloud_settings_version_auto);
                if (q.isEmpty() || auto.toLowerCase(Locale.ROOT).contains(q)) rows.add(new String[]{"auto", auto});
                for (CloudModel.Version v : all.versions) {
                    if (q.isEmpty() || v.label.toLowerCase(Locale.ROOT).contains(q) || v.value.contains(q)) rows.add(new String[]{v.value, v.label});
                }
                if (rows.isEmpty()) {
                    list.addView(CloudUi.note(c, getString(R.string.projects_cloud_settings_version_none)));
                    return;
                }
                for (final String[] r : rows) {
                    LinearLayout row = new LinearLayout(c);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setMinimumHeight(CloudUi.dp(c, 48));
                    row.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), 0);
                    row.setBackgroundResource(R.drawable.projects_row_ripple);
                    row.setClickable(true);
                    row.setFocusable(true);
                    boolean chosen = r[0].equals(mVersion);
                    TextView label = CloudUi.text(c, r[1], 15, chosen ? R.color.projects_gold_300 : R.color.projects_bone_100, chosen);
                    row.addView(label, CloudUi.weight(1f));
                    if (r[0].equals(all.recommended)) {
                        row.addView(CloudUi.text(c, getString(R.string.projects_cloud_settings_recommended), 13, R.color.projects_ash_400, false));
                    }
                    row.setOnClickListener(v -> {
                        Haptics.tick(v);
                        mVersion = r[0];
                        panel.dismiss();
                        draw();
                        bar();
                    });
                    Press.attach(row, Press.Style.ROW);
                    list.addView(row, CloudUi.matchWrap());
                }
            }
        };
        watch(search, s -> fill.run());
        fill.run();
        panel.show();
    }

    private void commands(LinearLayout content) {
        final Context c = ctx();
        CloudModel.Limits limits = mService.limits();
        content.addView(CloudUi.note(c, getString(R.string.projects_cloud_command_count, mConfig.commands.size(), limits.maxCommandRows)));
        // Adding is the list's last row (a floating link under it sat at uneven gaps).
        CloudUi.Row add = CloudUi.addRow(c, getString(R.string.projects_cloud_command_add), v -> CloudRowSheet.command(this, -1));
        CloudUi.enable(add, mConfig.commands.size() < limits.maxCommandRows);
        if (mConfig.commands.isEmpty()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.LOGS, getString(R.string.projects_cloud_command_empty)));
            LinearLayout only = CloudUi.group(c);
            only.addView(add.view);
            content.addView(only);
        } else {
            LinearLayout group = CloudUi.group(c);
            List<View> rows = new ArrayList<>();
            for (int i = 0; i < mConfig.commands.size(); i++) {
                final int index = i;
                CloudEdit.Command cmd = mConfig.commands.get(i);
                CloudUi.Row row = CloudUi.row(c, cmd.words.secretKind() ? R.drawable.projects_ic_lock_line : R.drawable.projects_ic_terminal,
                        preview(cmd.words), v -> CloudRowSheet.command(this, index));
                row.title.setSingleLine(true);
                row.title.setEllipsize(TextUtils.TruncateAt.END);
                String problem = issue("commands", i, null);
                if (problem == null && cmd.words.kind == CloudEdit.Words.Kind.SEALED && serverDirty()) {
                    problem = getString(R.string.projects_cloud_secret_reseal);
                }
                if (problem != null) row.setLine(problem, R.color.projects_flare_400);
                else if (cmd.paused) row.setLine(getString(R.string.projects_cloud_mod_paused_row), R.color.projects_flare_400);
                else row.setLine(trigger(cmd), R.color.projects_ash_400);
                if (!cmd.enabled) row.view.setAlpha(0.55f);
                rows.add(row.view);
            }
            rows.add(add.view);
            CloudUi.fill(group, rows);
            content.addView(group);
        }
    }

    private void replies(LinearLayout content) {
        final Context c = ctx();
        CloudModel.Limits limits = mService.limits();
        // CAFK-FEAT: console kinds came on but these rules still match the old way until the settings are saved once.
        if (mBase != null && mBase.features.legacyRules) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_gold_300, getString(R.string.projects_feat_legacy_rules)));
            Button now = CloudUi.textButton(c, getString(R.string.projects_feat_legacy_save), v -> saveConfig(true));
            now.setEnabled(!mSaving);
            content.addView(now, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(CloudUi.note(c, getString(R.string.projects_cloud_rule_count, mConfig.rules.size(), limits.maxRules)));
        CloudUi.Row add = CloudUi.addRow(c, getString(R.string.projects_cloud_rule_add), v -> CloudRowSheet.rule(this, -1));
        CloudUi.enable(add, mConfig.rules.size() < limits.maxRules);
        if (mConfig.rules.isEmpty()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.CHAT, getString(R.string.projects_cloud_rule_empty)));
            LinearLayout only = CloudUi.group(c);
            only.addView(add.view);
            content.addView(only);
        } else {
            LinearLayout group = CloudUi.group(c);
            List<View> rows = new ArrayList<>();
            for (int i = 0; i < mConfig.rules.size(); i++) {
                final int index = i;
                CloudEdit.Rule rule = mConfig.rules.get(i);
                CloudUi.Row row = CloudUi.row(c, rule.reply.secretKind() ? R.drawable.projects_ic_lock_line : R.drawable.projects_ic_chat,
                        preview(rule.reply), v -> CloudRowSheet.rule(this, index));
                row.title.setSingleLine(true);
                row.title.setEllipsize(TextUtils.TruncateAt.END);
                String problem = issue("rules", i, null);
                if (problem == null && rule.reply.kind == CloudEdit.Words.Kind.SEALED && serverDirty()) {
                    problem = getString(R.string.projects_cloud_secret_reseal);
                }
                if (problem != null) row.setLine(problem, R.color.projects_flare_400);
                else if (rule.paused) row.setLine(getString(R.string.projects_cloud_mod_paused_row), R.color.projects_flare_400);
                else if (CloudEdit.placeholderIssue(rule, i, mService.checks(mBase)) != null) {
                    // A reply stored before the switch came on that the save rules refuse: the machine leaves it out.
                    row.setLine(getString(R.string.projects_feat_rule_not_sent), R.color.projects_gold_300);
                }
                else row.setLine(getString(R.string.projects_cloud_rule_line, rule.pattern, CloudWords.span(c, rule.cooldown)), R.color.projects_ash_400);
                if (!rule.enabled) row.view.setAlpha(0.55f);
                rows.add(row.view);
            }
            rows.add(add.view);
            CloudUi.fill(group, rows);
            content.addView(group);
        }
    }

    private static String preview(CloudEdit.Words w) {
        switch (w.kind) {
            case SEALED: return w.preview == null ? "******" : w.preview;
            case SECRET: return CloudText.preview(w.text);
            default: return w.text;
        }
    }

    private String trigger(CloudEdit.Command cmd) {
        Context c = ctx();
        switch (cmd.on) {
            case "start": return getString(R.string.projects_cloud_trigger_start_short, cmd.delay);
            case "reconnect": return getString(R.string.projects_cloud_trigger_reconnect_short, cmd.delay);
            case "interval": return getString(R.string.projects_cloud_trigger_interval_short, CloudWords.span(c, cmd.everyMin), CloudWords.span(c, cmd.everyMax));
            case "daily": return getString(R.string.projects_cloud_trigger_daily_short, TextUtils.join(", ", cmd.at));
            default: return getString(R.string.projects_cloud_trigger_join_short, cmd.delay);
        }
    }

    // ---- the bar and saving ----

    private void bar() {
        if (mBar == null || mBase == null) return;
        boolean dirty = dirty();
        holdBack(dirty);
        // Only the inventory choice changed: it applies at the next connection, nothing restarts (CAFK-FEAT).
        boolean onlyInventory = mName.equals(mBase.name) && !serverDirty() && mVersion.equals(mBase.version == null ? "auto" : mBase.version)
                && mLang.equals("en".equals(mBase.lang) ? "en" : "tr") && mConfig.onlyInventoryDiffers(mOriginal);
        mBar.set(dirty || mSaving, mSaving, getString(onlyInventory ? R.string.projects_feat_inv_setting_note : CloudStatus.inGame(mBase)
                ? R.string.projects_cloud_settings_restart_note : R.string.projects_cloud_settings_dirty));
    }

    private void save() {
        saveConfig(false);
    }

    /** Saves the screen's changes; {@code force} sends the settings even unchanged ("Şimdi kaydet" for old matching). */
    private void saveConfig(boolean force) {
        final CloudModel.Account base = mBase;
        if (base == null || mSaving) return;
        Context c = ctx();
        List<CloudEdit.Issue> issues = new ArrayList<>(CloudEdit.validate(mConfig, mService.limits(), mService.checks(base)));
        if (!CloudEdit.isGameName(mName)) issues.add(new CloudEdit.Issue("general", -1, "name", "format"));
        boolean serverDirty = serverDirty();
        if (serverDirty) {
            CloudServer.Host host = CloudServer.normalizeHost(mHost);
            if (host.value == null) {
                issues.add(new CloudEdit.Issue("general", -1, "server",
                        host.problem == CloudServer.Problem.DOT ? "dot" : host.problem == CloudServer.Problem.PRIVATE ? "private" : "format"));
            }
            if (CloudServer.checkPort(portValue()) != null) issues.add(new CloudEdit.Issue("general", -1, "port", "port"));
        }
        if (!issues.isEmpty()) {
            showIssues(issues);
            return;
        }
        mSaving = true;
        mIssues.clear();
        bar();
        String name = mName.equals(base.name) ? null : mName;
        String hostTyped = serverDirty ? mHost.trim() : null;
        String version = mVersion.equals(base.version == null ? "auto" : base.version) ? null : mVersion;
        String lang = mLang.equals("en".equals(base.lang) ? "en" : "tr") ? null : mLang;
        CloudEdit.Config config = mConfig.same(mOriginal) && !force ? null : mConfig;
        mService.save(base, name, hostTyped, portValue(), version, lang, config, new CloudService.Result<CloudService.SaveOutcome>() {
            @Override
            public void ok(CloudService.SaveOutcome outcome) {
                mSaving = false;
                if (!alive()) return;
                if (outcome.saved != null) {
                    Haptics.confirm(saveButton());
                    toast(getString(R.string.projects_cloud_settings_saved, outcome.saved.name));
                    begin(outcome.saved);
                } else if (outcome.conflict != null) {
                    mConflict = outcome.conflict;
                    draw();
                    bar();
                } else {
                    showIssues(outcome.issues);
                }
            }

            @Override
            public void failed(CloudProblem problem) {
                mSaving = false;
                if (!alive()) return;
                bar();
                problem(problem, saveButton());
            }
        });
    }

    private void showIssues(List<CloudEdit.Issue> issues) {
        mIssues.clear();
        mIssues.addAll(issues);
        int tab = mTab;
        if (!issues.isEmpty()) {
            String place = issues.get(0).place;
            tab = "commands".equals(place) ? COMMANDS : "rules".equals(place) ? REPLIES : GENERAL;
        }
        mTab = tab;
        tabs();
        draw();
        bar();
        Haptics.reject(saveButton());
        toast(getString(R.string.projects_cloud_settings_fix_first));
    }

    @Nullable
    private View saveButton() {
        return mBar == null ? null : mBar.saveButton();
    }

    @Override
    protected void onBack() {
        if (!dirty()) {
            leaveNow();
            return;
        }
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.projects_cloud_discard_title)
                .setMessage(R.string.projects_cloud_discard_body)
                .setPositiveButton(R.string.projects_cloud_discard_confirm, (d, w) -> leaveNow())
                .setNegativeButton(R.string.projects_cloud_discard_keep, null)
                .show();
    }

    // ---- CloudRowSheet.Host ----

    @Override
    public Context context() {
        return requireContext();
    }

    @Override
    public CloudEdit.Config config() {
        return mConfig;
    }

    @Override
    public CloudModel.Limits limits() {
        return mService.limits();
    }

    @Override
    public boolean serverChanged() {
        return serverDirty();
    }

    @Override
    public CloudEdit.Checks checks() {
        return mService.checks(mBase != null ? mBase : account());
    }

    @Override
    public void reveal(String rowId, final CloudService.Result<String> result) {
        if (mBase == null) return;
        mService.reveal(mBase.id, rowId, new CloudService.Result<String>() {
            @Override
            public void ok(String value) {
                if (alive()) result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                if (alive()) result.failed(problem);
            }
        });
    }

    @Override
    public void changed() {
        mIssues.clear();
        draw();
        bar();
    }
}
