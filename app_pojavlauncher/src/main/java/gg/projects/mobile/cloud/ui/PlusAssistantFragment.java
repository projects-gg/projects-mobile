package gg.projects.mobile.cloud.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudEdit;
import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.cloud.plus.PlusText;
import gg.projects.mobile.social.ChatText;
import gg.projects.mobile.ui.Haptics;

/**
 * "Asistan": how the assistant talks (tone, how it introduces itself, the status it gives), who it answers and in
 * which language, when it saves the allowance, what it looks for in chat, when the daily summary comes, and the
 * players it never answers. A text the service has not passed yet says so under its field.
 */
public final class PlusAssistantFragment extends PlusScreen {
    public static final String TAG = "ProjectsPlusAssistantFragment";

    @Override
    protected int title() {
        return R.string.projects_plus_chat_settings;
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

    private static int index(String[] values, @Nullable String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    @Override
    protected void draw(LinearLayout content) {
        final Context c = ctx();
        final PlusConfig e = mEdit;
        final CloudModel.PlusDoc doc = mDoc;
        if (e == null || doc == null) return;

        // The tone and the words it uses about itself. Every control of this form takes a field's label (as "Kendini
        // nasıl tanıtsın" and "Durum" do); the grey section title is only for "Engellenen oyuncular", a list.
        content.addView(CloudRowSheet.label(c, getString(R.string.projects_plus_persona)));
        content.addView(CloudUi.segmented(c, new CharSequence[]{getString(R.string.projects_plus_preset_friendly),
                getString(R.string.projects_plus_preset_brief), getString(R.string.projects_plus_preset_polite),
                getString(R.string.projects_plus_preset_playful)}, Math.max(0, index(PlusConfig.PRESETS, e.preset())), i -> {
            e.setPreset(PlusConfig.PRESETS[i]);
            changed();
            sample();
        }));
        // How a reply reads with these settings (the tag only while it is on and valid; the player's talk when chosen).
        mSample = CloudUi.note(c, "");
        content.addView(mSample);
        sample();
        CloudUi.Field intro = CloudUi.field(c, getString(R.string.projects_plus_intro), e.intro(), getString(R.string.projects_plus_intro_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        intro.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PlusConfig.INTRO_MAX)});
        // The site's label and help ("Tanıtım", "İlk mesajında kendini böyle tanıtır."); a check's state replaces the help.
        intro.setHelp(getString(R.string.projects_plus_intro_help));
        judge(intro, doc.intro, "persona.intro");
        watch(intro.input, s -> {
            e.setIntro(s.trim());
            changed();
        });
        content.addView(intro.view);

        content.addView(CloudRowSheet.label(c, getString(R.string.projects_plus_status)));
        final String[] presets = PlusConfig.STATUS_PRESETS;
        CharSequence[] statusLabels = {getString(R.string.projects_plus_status_back_soon), getString(R.string.projects_plus_status_back_evening),
                getString(R.string.projects_plus_status_back_tomorrow), getString(R.string.projects_plus_status_weekend),
                getString(R.string.projects_plus_status_busy), getString(R.string.projects_plus_status_own)};
        String preset = e.statusPreset();
        int selected = preset != null ? index(presets, preset) : e.status().isEmpty() ? -1 : presets.length;
        final LinearLayout own = new LinearLayout(c);
        own.setOrientation(LinearLayout.VERTICAL);
        final CloudUi.Field status = CloudUi.field(c, getString(R.string.projects_plus_status_own), e.status(), getString(R.string.projects_plus_status_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        status.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PlusConfig.STATUS_MAX)});
        judge(status, doc.status, "persona.status");
        watch(status.input, s -> {
            if (e.statusPreset() != null) return;
            e.setStatus(s.trim());
            changed();
        });
        own.addView(status.view);
        own.setVisibility(selected == presets.length ? View.VISIBLE : View.GONE);
        // On the page the choices sit in a group like every other list here (in a sheet they need none).
        LinearLayout statusGroup = CloudUi.group(c);
        statusGroup.addView(CloudUi.choices(c, statusLabels, selected, true, i -> {
            if (i < presets.length) {
                e.setStatusPreset(presets[i]);
                own.setVisibility(View.GONE);
            } else {
                e.setStatusPreset(null);
                e.setStatus(status.value().trim());
                own.setVisibility(View.VISIBLE);
                status.input.requestFocus();
            }
            changed();
        }));
        content.addView(statusGroup);
        content.addView(own, CloudUi.matchWrap());

        talk(content, e);

        // Who it answers, and how.
        content.addView(CloudRowSheet.label(c, getString(R.string.projects_plus_scope)));
        final String[] scopes = {"all", "trade", "off"};
        // The site's options are sentences ("Yalnız ticaret mesajlarına"): rows, not chips that would cut them.
        LinearLayout scopeGroup = CloudUi.group(c);
        scopeGroup.addView(CloudUi.choices(c, new CharSequence[]{getString(R.string.projects_plus_scope_all), getString(R.string.projects_plus_scope_trade),
                getString(R.string.projects_plus_scope_off)}, Math.max(0, index(scopes, e.scope())), true, i -> {
            e.setScope(scopes[i]);
            changed();
        }));
        content.addView(scopeGroup);
        LinearLayout away = CloudUi.group(c);
        away.addView(CloudUi.toggle(c, getString(R.string.projects_plus_away), e.away(), on -> {
            e.setAway(on);
            changed();
        }));
        LinearLayout.LayoutParams awayLp = CloudUi.matchWrap();
        awayLp.topMargin = CloudUi.dp(c, 12);
        content.addView(away, awayLp);

        content.addView(CloudRowSheet.label(c, getString(R.string.projects_plus_lang)));
        final String[] langs = {"auto", "tr", "en"};
        // The language the assistant writes in (the site's "Yazana göre" / "Türkçe" / "İngilizce"), not the app's own.
        LinearLayout langGroup = CloudUi.group(c);
        langGroup.addView(CloudUi.choices(c, new CharSequence[]{getString(R.string.projects_plus_lang_auto), getString(R.string.projects_plus_lang_tr),
                getString(R.string.projects_plus_lang_en)}, Math.max(0, index(langs, e.lang())), true, i -> {
            e.setLang(langs[i]);
            changed();
        }));
        content.addView(langGroup);

        if (mService.plusFeature("saver")) {
            content.addView(CloudRowSheet.label(c, getString(R.string.projects_plus_saver)));
            Integer saveAt = e.saveAt();
            int at = saveAt == null ? 2 : saveAt == 80 ? 0 : 1;
            content.addView(CloudUi.segmented(c, new CharSequence[]{getString(R.string.projects_plus_saver_80), getString(R.string.projects_plus_saver_50),
                    getString(R.string.projects_plus_saver_off)}, at, i -> {
                e.setSaveAt(i == 0 ? Integer.valueOf(80) : i == 1 ? Integer.valueOf(50) : null);
                changed();
            }));
        }

        // What it looks for, and the daily summary.
        content.addView(CloudUi.gap(c, 16));
        LinearLayout look = CloudUi.group(c);
        look.addView(CloudUi.toggle(c, getString(R.string.projects_plus_scan), e.scanChat(), on -> {
            e.setScanChat(on);
            changed();
        }));
        look.addView(CloudUi.textDivider(c));
        look.addView(CloudUi.toggle(c, getString(R.string.projects_plus_proactive), e.proactivePm(), on -> {
            e.setProactivePm(on);
            changed();
        }));
        if (mService.plusFeature("report")) {
            look.addView(CloudUi.textDivider(c));
            // A time picked in place, like the quiet hours and the working hours (no icon, no caret).
            final CloudUi.Row[] summary = new CloudUi.Row[1];
            summary[0] = CloudUi.valueRow(c, getString(R.string.projects_plus_summary_at), e.summaryAt(),
                    v -> pickTime(e.summaryAt(), time -> {
                        e.setSummaryAt(time);
                        summary[0].count.setText(time);
                        changed();
                    }));
            look.addView(summary[0].view);
        }
        content.addView(look);

        // Players it never answers.
        content.addView(CloudUi.section(c, getString(R.string.projects_plus_blocked)));
        blocked(content, e);
    }

    @Nullable
    private TextView mSample;

    /** "Örnek cevap: …" for the chosen tone, in the player's talk when that is on, after the tag when it is on. */
    private void sample() {
        PlusConfig e = mEdit;
        TextView sample = mSample;
        if (e == null || sample == null) return;
        int i = Math.max(0, index(PlusConfig.PRESETS, e.preset()));
        int[] plain = {R.string.projects_talk_plain_friendly, R.string.projects_talk_plain_brief, R.string.projects_talk_plain_polite,
                R.string.projects_talk_plain_playful};
        int[] player = {R.string.projects_talk_sample_friendly, R.string.projects_talk_sample_brief, R.string.projects_talk_sample_polite,
                R.string.projects_talk_sample_playful};
        String line = getString(e.playerTalk() ? player[i] : plain[i]);
        String tag = e.tagText().trim();
        if (e.tagOn() && PlusText.checkPrefix(tag, names(e)) == null) line = ChatText.clean(tag) + " " + line;
        sample.setText(getString(R.string.projects_talk_sample) + ": " + line);
    }

    /** The names a tag may not hold: the friends and the blocked players of these settings. */
    private static List<String> names(PlusConfig e) {
        List<String> names = new ArrayList<>(e.friends());
        names.addAll(e.blocked());
        return names;
    }

    /**
     * How it talks (AFKPLUS-TONE, 27 Oct 2026, the launcher's and the site's words): like a player or not, small talk said
     * as its opposite ("Konu dışına çıkmasın", on by default), and the tag before its lines (the player's choice, off by
     * default), checked while it is typed.
     */
    private void talk(LinearLayout content, final PlusConfig e) {
        final Context c = ctx();
        LinearLayout talk = CloudUi.group(c);
        talk.addView(CloudUi.toggle(c, getString(R.string.projects_talk_player), e.playerTalk(), on -> {
            e.setPlayerTalk(on);
            changed();
            sample();
        }));
        TextView playerHelp = CloudUi.text(c, getString(R.string.projects_talk_player_help), 13, R.color.projects_ash_400, false);
        playerHelp.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
        talk.addView(playerHelp);
        talk.addView(CloudUi.textDivider(c));
        talk.addView(CloudUi.toggle(c, getString(R.string.projects_talk_on_topic), !e.smallTalk(), on -> {
            e.setSmallTalk(!on);
            changed();
        }));
        talk.addView(CloudUi.textDivider(c));
        final LinearLayout tagBox = new LinearLayout(c);
        tagBox.setOrientation(LinearLayout.VERTICAL);
        tagBox.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 8));
        final CloudUi.Field tag = CloudUi.field(c, getString(R.string.projects_tag_label), e.tagText(), PlusConfig.TAG_DEFAULT,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tag.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        String stored = problemAt("persona.prefix.text");
        if (stored != null) tag.setError(stored);
        watch(tag.input, s -> {
            e.setTag(e.tagOn(), s);
            String typed = s.trim();
            String problem = e.tagOn() ? PlusText.checkPrefix(typed, names(e)) : null;
            tag.setError(problem == null ? null : PlusWords.tagProblem(c, typed.isEmpty() ? "empty" : problem));
            changed();
            sample();
        });
        tagBox.addView(tag.view);
        tagBox.setVisibility(e.tagOn() ? View.VISIBLE : View.GONE);
        talk.addView(CloudUi.toggle(c, getString(R.string.projects_tag_switch), e.tagOn(), on -> {
            String text = tag.value().trim().isEmpty() ? PlusConfig.TAG_DEFAULT : tag.value();
            // Off: a tag that would not pass goes back to what is stored, so turning it off never saves a bad one.
            if (!on && PlusText.checkPrefix(text, names(e)) != null && mOriginal != null) text = mOriginal.tagText();
            e.setTag(on, text);
            if (!text.equals(tag.value())) tag.input.setText(text);
            tag.setError(null);
            tagBox.setVisibility(on ? View.VISIBLE : View.GONE);
            changed();
            sample();
        }));
        talk.addView(tagBox, CloudUi.matchWrap());
        LinearLayout.LayoutParams talkLp = CloudUi.matchWrap();
        talkLp.topMargin = CloudUi.dp(c, 12);
        content.addView(talk, talkLp);
    }

    private void judge(CloudUi.Field field, @Nullable CloudModel.Judge judge, String path) {
        String problem = problemAt(path);
        if (problem != null) {
            field.setError(problem);
            return;
        }
        if (judge == null || judge.state == null) return;
        if ("pending".equals(judge.state)) field.setHelp(getString(R.string.projects_plus_judge_pending));
        else if (!"ok".equals(judge.state)) field.setError(CloudWords.judgeText(ctx(), judge.reason));
    }

    private void blocked(LinearLayout content, final PlusConfig e) {
        final Context c = ctx();
        final List<String> names = new ArrayList<>(e.blocked());
        if (names.isEmpty()) {
            content.addView(CloudUi.note(c, getString(R.string.projects_plus_blocked_empty)));
        } else {
            LinearLayout group = CloudUi.group(c);
            for (int i = 0; i < names.size(); i++) {
                final String name = names.get(i);
                if (i > 0) group.addView(CloudUi.rowDivider(c));
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(CloudUi.dp(c, 52));
                row.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 4), 0);
                row.addView(CloudUi.text(c, name, 15, R.color.projects_bone_100, false), CloudUi.weight(1f));
                Button unblock = CloudUi.textButton(c, getString(R.string.projects_plus_unblock), v -> {
                    List<String> next = new ArrayList<>(e.blocked());
                    next.remove(name);
                    e.setBlocked(next);
                    Haptics.tick(v);
                    changed();
                    redraw();
                });
                unblock.setContentDescription(getString(R.string.projects_plus_unblock) + ", " + name);
                row.addView(unblock);
                group.addView(row, CloudUi.matchWrap());
            }
            content.addView(group);
        }
        if (names.size() >= PlusConfig.BLOCKED_MAX) return;
        LinearLayout add = new LinearLayout(c);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.setGravity(Gravity.BOTTOM);
        final CloudUi.Field field = CloudUi.field(c, getString(R.string.projects_plus_blocked_add), null, getString(R.string.projects_plus_blocked_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        String problem = problemAt("blocked");
        if (problem != null) field.setError(problem);
        add.addView(field.view, CloudUi.weight(1f));
        Button block = CloudUi.button(c, getString(R.string.projects_plus_blocked_add), false, v -> {
            String name = field.value().trim();
            if (!CloudEdit.isGameName(name)) {
                field.setError(getString(R.string.projects_cloud_issue_name));
                Haptics.reject(v);
                return;
            }
            List<String> next = new ArrayList<>(e.blocked());
            for (String n : next) if (n.equalsIgnoreCase(name)) return;
            next.add(name);
            e.setBlocked(next);
            Haptics.confirm(v);
            changed();
            redraw();
        });
        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockLp.setMarginStart(CloudUi.dp(c, 4));
        blockLp.bottomMargin = CloudUi.dp(c, 2);
        add.addView(block, blockLp);
        content.addView(add, CloudUi.matchWrap());
        field.label.setVisibility(View.GONE);
    }

    private interface Picked {
        void picked(String time);
    }

    private void pickTime(String current, final Picked picked) {
        int hour = 21;
        int minute = 0;
        try {
            hour = Integer.parseInt(current.substring(0, 2));
            minute = Integer.parseInt(current.substring(3, 5));
        } catch (RuntimeException ignored) {
            // Not a time: start from 21:00.
        }
        new TimePickerDialog(requireActivity(), (view, h, m) -> picked.picked(String.format(Locale.ROOT, "%02d:%02d", h, m)), hour, minute, true).show();
    }
}
