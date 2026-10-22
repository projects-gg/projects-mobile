package gg.projects.mobile.social.ui;

import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.List;

import gg.projects.mobile.social.ChatMessage;
import gg.projects.mobile.social.Reports;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.TeamSummary;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;

/**
 * "Rapor et" (M20, MOD1; the desktop's dialog): who or what is reported, the fixed reasons of that
 * kind, a note only moderators see (200 characters) and, for a player or a message, "Ayrıca engelle"
 * (not ticked). "Gönder" stays off until a reason is picked; the reported one never learns about it.
 */
public final class ReportDialog {
    private ReportDialog() {}

    public static void player(FragmentActivity activity, Profile player) {
        show(activity, Reports.Target.PLAYER, player, null, player.name(), null, null, null, null);
    }

    /**
     * FIX2: a plan's maker, from its card: the plan (its time and note) goes with the report as its
     * context; the note's own reason ("Uygunsuz içerik") comes first.
     */
    public static void plan(FragmentActivity activity, gg.projects.mobile.social.SocialModel.Plan plan, Profile maker) {
        show(activity, Reports.Target.PLAN, maker, null, maker.name(), null, null, null, plan);
    }

    /** {@code code}: only for a crew looked up by its code or (M21) its invite link. */
    public static void crew(FragmentActivity activity, TeamSummary crew, @Nullable String code) {
        show(activity, Reports.Target.CREW, null, crew, crew.name, code, null, null, null);
    }

    public static void message(FragmentActivity activity, ChatMessage message, List<ChatMessage> before, @Nullable Profile sender) {
        show(activity, Reports.Target.MESSAGE, sender, null, sender == null ? "" : sender.name(), null, message, before, null);
    }

    private static int reasonLabel(Reports.Target target, String reason) {
        switch (reason) {
            case Reports.NAME: return target == Reports.Target.CREW ? R.string.projects_report_reason_name_crew : R.string.projects_report_reason_name;
            case Reports.PROFILE: return R.string.projects_report_reason_profile;
            case Reports.HARASSMENT: return R.string.projects_report_reason_harassment;
            case Reports.INAPPROPRIATE: return target == Reports.Target.PLAN ? R.string.projects_report_reason_note
                    : R.string.projects_report_reason_inappropriate;
            case Reports.SPAM: return R.string.projects_report_reason_spam;
            case Reports.SCAM: return R.string.projects_report_reason_scam;
            case Reports.OTHER:
            default: return R.string.projects_report_reason_other;
        }
    }

    /** A moderator's notice names its reason as a player's report does (0: none or one not known here). */
    static int noticeReason(@Nullable String reason) {
        if (reason == null) return 0;
        switch (reason) {
            case Reports.NAME:
            case Reports.PROFILE:
            case Reports.HARASSMENT:
            case Reports.INAPPROPRIATE:
            case Reports.SPAM:
            case Reports.SCAM:
            case Reports.OTHER:
                return reasonLabel(Reports.Target.PLAYER, reason);
            default:
                return 0;
        }
    }

    private static void show(final FragmentActivity activity, final Reports.Target target, @Nullable final Profile player,
                             @Nullable final TeamSummary crew, String name, @Nullable final String code,
                             @Nullable final ChatMessage message, @Nullable final List<ChatMessage> before,
                             @Nullable final gg.projects.mobile.social.SocialModel.Plan plan) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(20 * dp), Math.round(8 * dp), Math.round(20 * dp), Math.round(4 * dp));

        // Who or what: the face or the crew's mark and the name (a message shows its text).
        LinearLayout who = new LinearLayout(activity);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        if (crew != null) {
            CrewMarkView mark = new CrewMarkView(activity);
            mark.setLook(crew.emblem, crew.color, crew.frame);
            who.addView(mark, new LinearLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp)));
        } else if (player != null) {
            AvatarView avatar = new AvatarView(activity);
            avatar.setProfile(player);
            avatar.setSurfaceColor(ContextCompat.getColor(activity, R.color.projects_obsidian_800));
            who.addView(avatar, new LinearLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp)));
        }
        LinearLayout names = new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(name);
        title.setTextSize(16);
        title.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
        try {
            title.setTypeface(ResourcesCompat.getFont(activity, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        names.addView(title);
        if (message != null || plan != null) {
            TextView quote = new TextView(activity);
            // FIX2: a plan shows its time and its note, what the report carries.
            String subject = plan == null ? "" : activity.getString(R.string.projects_report_plan,
                    PlanViews.when(activity, plan.at, SocialService.nowSeconds()));
            quote.setText(message != null ? message.text : plan.note == null ? subject : subject + " · " + plan.note);
            quote.setTextSize(13);
            quote.setMaxLines(3);
            quote.setEllipsize(android.text.TextUtils.TruncateAt.END);
            quote.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
            names.addView(quote);
        }
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        namesLp.setMarginStart(Math.round(12 * dp));
        who.addView(names, namesLp);
        content.addView(who);

        TextView why = new TextView(activity);
        why.setText(R.string.projects_report_why);
        why.setTextSize(13);
        why.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_300));
        why.setPadding(0, Math.round(16 * dp), 0, Math.round(2 * dp));
        try {
            why.setTypeface(ResourcesCompat.getFont(activity, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        content.addView(why);

        final RadioGroup reasons = new RadioGroup(activity);
        reasons.setOrientation(RadioGroup.VERTICAL);
        final List<String> keys = Reports.reasons(target);
        ColorStateList tint = new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ContextCompat.getColor(activity, R.color.projects_gold_400), ContextCompat.getColor(activity, R.color.projects_ash_400)});
        for (int i = 0; i < keys.size(); i++) {
            RadioButton option = new RadioButton(activity);
            option.setId(View.generateViewId());
            option.setTag(keys.get(i));
            option.setText(reasonLabel(target, keys.get(i)));
            option.setTextSize(15);
            option.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
            option.setMinHeight(Math.round(46 * dp));
            option.setButtonTintList(tint);
            reasons.addView(option, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(reasons);

        TextView noteLabel = new TextView(activity);
        noteLabel.setText(R.string.projects_report_note);
        noteLabel.setTextSize(13);
        noteLabel.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_300));
        noteLabel.setPadding(0, Math.round(12 * dp), 0, Math.round(6 * dp));
        content.addView(noteLabel);
        final EditText note = new EditText(activity);
        note.setBackgroundResource(R.drawable.projects_input);
        note.setHint(R.string.projects_report_note_hint);
        note.setHintTextColor(ContextCompat.getColor(activity, R.color.projects_obsidian_600));
        note.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
        note.setTextSize(15);
        note.setMinHeight(Math.round(48 * dp));
        note.setMaxLines(4);
        note.setPadding(Math.round(12 * dp), Math.round(10 * dp), Math.round(12 * dp), Math.round(10 * dp));
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Reports.NOTE_MAX)});
        note.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Under the note: who reads it (the desktop's line) and how much is left.
        LinearLayout under = new LinearLayout(activity);
        under.setOrientation(LinearLayout.HORIZONTAL);
        under.setPadding(0, Math.round(4 * dp), 0, 0);
        TextView help = new TextView(activity);
        help.setText(R.string.projects_report_note_help);
        help.setTextSize(12);
        help.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
        under.addView(help, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView count = new TextView(activity);
        count.setTextSize(12);
        count.setGravity(Gravity.END);
        count.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
        count.setText(activity.getString(R.string.projects_crew_count, 0, Reports.NOTE_MAX));
        under.addView(count, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(under, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        note.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int c, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int c) {}
            @Override public void afterTextChanged(Editable s) {
                count.setText(activity.getString(R.string.projects_crew_count, s.toString().codePointCount(0, s.length()), Reports.NOTE_MAX));
            }
        });

        final CheckBox block = new CheckBox(activity);
        if (Reports.offersBlock(target) && player != null) {
            block.setText(R.string.projects_report_block);
            block.setTextSize(15);
            block.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
            block.setMinHeight(Math.round(48 * dp));
            block.setButtonTintList(tint);
            block.setChecked(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(4 * dp);
            content.addView(block, lp);
        }
        // The reported one never learns who it was (the desktop says it the same way).
        TextView quiet = new TextView(activity);
        quiet.setText(R.string.projects_report_private);
        quiet.setTextSize(13);
        quiet.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
        quiet.setPadding(0, Math.round(10 * dp), 0, Math.round(4 * dp));
        content.addView(quiet);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(target == Reports.Target.CREW ? R.string.projects_report_crew : R.string.projects_report)
                .setView(scroll)
                .setPositiveButton(R.string.projects_report_send, null)
                .setNegativeButton(R.string.projects_social_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            final Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            send.setEnabled(false);
            reasons.setOnCheckedChangeListener((group, id) -> {
                send.setEnabled(id != View.NO_ID);
                View picked = group.findViewById(id);
                if (picked != null) Haptics.perform(picked, gg.projects.mobile.ui.HapticMap.Kind.TOGGLE_ON);
            });
            send.setOnClickListener(v -> {
                View picked = reasons.findViewById(reasons.getCheckedRadioButtonId());
                if (picked == null) return;
                String reason = (String) picked.getTag();
                boolean blocking = block.getParent() != null && block.isChecked();
                JsonObject body;
                switch (target) {
                    case CREW:
                        body = Reports.crew(crew.id, code, reason, note.getText().toString());
                        break;
                    case MESSAGE:
                        body = Reports.message(message, before, reason, note.getText().toString(), blocking);
                        break;
                    case PLAN:
                        body = Reports.planMaker(player.id, plan.crew, plan.id, reason, note.getText().toString(), blocking);
                        break;
                    case PLAYER:
                    default:
                        body = Reports.player(player.id, reason, note.getText().toString(), blocking);
                        break;
                }
                send.setEnabled(false);
                final View feedback = send;
                SocialService.get(activity).report(body, blocking ? player : null, new SocialService.Result<JsonObject>() {
                    @Override
                    public void ok(JsonObject value) {
                        Haptics.confirm(feedback);
                        dialog.dismiss();
                        // A second report of the same target while the first is open ("duplicate") says the same.
                        SocialUi.toast(activity, activity.getString(R.string.projects_report_sent));
                    }

                    @Override
                    public void failed(SocialProblem problem) {
                        send.setEnabled(true);
                        Haptics.reject(feedback);
                        String text = problem.kind == SocialProblem.Kind.RATE_LIMITED
                                ? activity.getString(R.string.projects_report_rate) : SocialUi.problemText(activity, problem);
                        SocialUi.toast(activity, text);
                    }
                });
            });
        });
        dialog.show();
    }
}
