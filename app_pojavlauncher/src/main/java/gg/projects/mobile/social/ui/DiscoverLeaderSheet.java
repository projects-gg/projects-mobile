package gg.projects.mobile.social.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import gg.projects.mobile.cloud.TrGrammar;
import gg.projects.mobile.social.DiscoverModel;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.AppDates;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * MOB-PARITY "Keşif" (the desktop's leader block on the crew page, {@code discover-block.tsx}): for the crew's leader,
 * where the crew stands ("Keşifte görünüyor.", "Bugün 18:00'de bakılacak.", a refusal's wait), what it still needs
 * (members, the crew's and the account's age, not hidden), the text (10-160 characters) and up to three tags with
 * "Keşfe gönder" / "Değişikliği gönder", and "Keşiften çıkar" (asked once). The status is read when the sheet opens.
 */
final class DiscoverLeaderSheet {
    private DiscoverLeaderSheet() {}

    static void show(final FragmentActivity activity, final String crewId) {
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        final LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        final Holder h = new Holder(activity, crewId, list, dialog);
        h.render();
        h.read("GET", null);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setWindowAnimations(R.style.ProjectsMenuSheetAnimation);
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        gg.projects.mobile.ui.FocusRing.attach(dialog);
    }

    private static final class Holder {
        final FragmentActivity activity;
        final String crewId;
        final LinearLayout list;
        final AlertDialog dialog;
        @Nullable
        DiscoverModel.Status status;
        boolean loading = true, failed, busy;
        /** The editor's text and tags (kept across a re-render). */
        @Nullable
        String text;
        final List<String> tags = new ArrayList<>();
        boolean edited;

        Holder(FragmentActivity activity, String crewId, LinearLayout list, AlertDialog dialog) {
            this.activity = activity;
            this.crewId = crewId;
            this.list = list;
            this.dialog = dialog;
        }

        void read(String method, @Nullable JsonObject body) {
            busy = !"GET".equals(method);
            final boolean leaving = "DELETE".equals(method);
            final boolean sending = "PUT".equals(method);
            render();
            SocialService.get(activity).discoverStatus(crewId, method, body, new SocialService.Result<JsonObject>() {
                @Override
                public void ok(JsonObject value) {
                    loading = false;
                    busy = false;
                    failed = false;
                    status = DiscoverModel.readStatus(value);
                    if (status == null) failed = true;
                    if (leaving) SocialUi.toast(activity, activity.getString(R.string.projects_discover_off_done));
                    if (sending) edited = false;
                    if (dialog.isShowing()) render();
                }

                @Override
                public void failed(SocialProblem problem) {
                    loading = false;
                    busy = false;
                    if ("GET".equals(method)) {
                        failed = true;
                        if (dialog.isShowing()) render();
                        return;
                    }
                    // A refusal (a requirement, a wait, the week's edits): the status says why.
                    SocialUi.toastProblem(activity, problem, null);
                    read("GET", null);
                }
            });
        }

        void render() {
            Context c = activity;
            list.removeAllViews();
            list.addView(text(c, c.getString(R.string.projects_discover_block_title), 17, R.color.projects_bone_100, true));
            if (loading && status == null) {
                ProgressBar progress = new ProgressBar(c);
                progress.setIndeterminateTintList(ContextCompat.getColorStateList(c, R.color.projects_gold_400));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 26), dp(c, 26));
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                lp.topMargin = dp(c, 16);
                lp.bottomMargin = dp(c, 16);
                list.addView(progress, lp);
                return;
            }
            if (failed || status == null) {
                list.addView(text(c, c.getString(R.string.projects_discover_block_failed), 14, R.color.projects_ash_300, false));
                list.addView(CrewViews.actionRow(c, R.drawable.projects_ic_arrows_clockwise, c.getString(R.string.projects_retry),
                        R.color.projects_bone_100, v -> {
                            loading = true;
                            failed = false;
                            read("GET", null);
                        }));
                return;
            }
            DiscoverModel.Status s = status;
            String line = statusLine(c, s);
            if (line != null) list.addView(text(c, line, 14, R.color.projects_bone_100, false));
            if ("closed".equals(s.state)) return;
            list.addView(text(c, c.getString(R.string.projects_discover_block_help), 13, R.color.projects_ash_400, false));
            if (!s.checksHold()) {
                for (String need : needs(c, s)) list.addView(text(c, need, 14, R.color.projects_ash_300, false));
            } else if (s.editsLeft <= 0) {
                int days = DiscoverModel.daysUntil(s.editsReset, System.currentTimeMillis());
                list.addView(text(c, c.getResources().getQuantityString(R.plurals.projects_discover_block_edits, days, days), 14,
                        R.color.projects_ash_300, false));
            } else if (!"refused".equals(s.state) || s.retryAt == null || s.retryAt * 1000L <= System.currentTimeMillis()) {
                editor(c, s);
            }
            if (s.on()) {
                list.addView(CrewViews.divider(c));
                list.addView(CrewViews.actionRow(c, R.drawable.projects_ic_x, c.getString(R.string.projects_discover_off_confirm),
                        R.color.projects_flare_400, v -> SocialUi.confirmDanger(activity, c.getString(R.string.projects_discover_off_confirm),
                                c.getString(R.string.projects_discover_off_ask), c.getString(R.string.projects_discover_off_confirm),
                                () -> read("DELETE", null))));
            }
        }

        private void editor(final Context c, final DiscoverModel.Status s) {
            if (!edited) {
                DiscoverModel.Text start = s.changeWaits || "waiting".equals(s.state) ? s.draft : s.listed != null ? s.listed : s.draft;
                text = start == null ? "" : start.text;
                tags.clear();
                if (start != null) tags.addAll(start.tags);
            }
            TextView label = text(c, c.getString(R.string.projects_discover_block_text), 13, R.color.projects_ash_300, true);
            list.addView(label);
            final TextView count = text(c, "", 12, R.color.projects_ash_400, false);
            count.setGravity(Gravity.END);
            final EditText field = new EditText(c);
            field.setBackgroundResource(R.drawable.projects_input);
            field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            field.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
            field.setHintTextColor(ContextCompat.getColor(c, R.color.projects_ash_400));
            field.setHint(R.string.projects_discover_block_placeholder);
            field.setContentDescription(c.getString(R.string.projects_discover_block_text));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            field.setMinLines(3);
            field.setMaxLines(5);
            field.setGravity(Gravity.TOP | Gravity.START);
            field.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
            field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(DiscoverModel.TEXT_MAX * 2)});
            if (android.os.Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            field.setText(text);
            LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fieldLp.setMarginStart(dp(c, 10));
            fieldLp.setMarginEnd(dp(c, 10));
            list.addView(field, fieldLp);
            list.addView(count);
            list.addView(text(c, c.getString(R.string.projects_discover_block_tags) + ". " + c.getString(R.string.projects_discover_block_tags_help),
                    13, R.color.projects_ash_300, false));
            WrapLayout chips = new WrapLayout(c);
            chips.setGap(dp(c, 6));
            chips.setPadding(dp(c, 10), 0, dp(c, 10), 0);
            final Button send = new Button(c, null, android.R.attr.borderlessButtonStyle);
            final Runnable refresh = () -> {
                count.setText(c.getString(R.string.projects_crew_count, DiscoverModel.textLength(text), DiscoverModel.TEXT_MAX));
                boolean may = !busy && DiscoverModel.maySend(s, text == null ? "" : text, tags);
                send.setEnabled(may);
                // The disabled face is obsidian: the words go ash there.
                send.setTextColor(ContextCompat.getColor(c, may ? R.color.projects_on_gold : R.color.projects_ash_400));
            };
            for (final String tag : DiscoverModel.TAGS) {
                final TextView chip = new TextView(c);
                chip.setText(DiscoverFragment.tagWords(tag));
                chip.setTextSize(14);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(c, 12), 0, dp(c, 12), 0);
                int inset = dp(c, 6);
                chip.setBackground(new android.graphics.drawable.InsetDrawable(SocialChips.choice(c, 7), 0, inset, 0, inset));
                chip.setMinHeight(dp(c, 48));
                chip.setClickable(true);
                chip.setFocusable(true);
                final Runnable mark = () -> {
                    boolean on = tags.contains(tag);
                    chip.setSelected(on);
                    chip.setTextColor(ContextCompat.getColor(c, on ? R.color.projects_bone_100 : R.color.projects_ash_300));
                };
                mark.run();
                Press.attach(chip, Press.Style.BUTTON);
                chip.setOnClickListener(v -> {
                    boolean on = !tags.contains(tag);
                    if (on && tags.size() >= DiscoverModel.MAX_TAGS) {
                        Haptics.reject(v);
                        return;
                    }
                    Haptics.toggle(v, on);
                    if (on) tags.add(tag);
                    else tags.remove(tag);
                    edited = true;
                    mark.run();
                    refresh.run();
                });
                chips.addView(chip);
            }
            list.addView(chips);
            send.setText(s.on() ? R.string.projects_discover_block_send_change : R.string.projects_discover_block_send);
            send.setAllCaps(false);
            send.setBackground(ChamferDrawable.primary(c));
            send.setMinHeight(dp(c, 48));
            Press.attach(send, Press.Style.BUTTON);
            send.setOnClickListener(v -> {
                if (text == null || !DiscoverModel.maySend(s, text, tags)) {
                    Haptics.reject(v);
                    return;
                }
                Haptics.confirm(v);
                read("PUT", DiscoverModel.body(text, tags));
            });
            LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sendLp.topMargin = dp(c, 10);
            sendLp.setMarginStart(dp(c, 10));
            sendLp.setMarginEnd(dp(c, 10));
            list.addView(send, sendLp);
            field.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence cs, int start, int n, int after) {
                }

                @Override
                public void onTextChanged(CharSequence cs, int start, int before, int n) {
                }

                @Override
                public void afterTextChanged(Editable e) {
                    text = e.toString();
                    edited = true;
                    refresh.run();
                }
            });
            refresh.run();
        }

        /** The one line of where the crew stands; null while it is off. */
        @Nullable
        private static String statusLine(Context c, DiscoverModel.Status s) {
            long now = System.currentTimeMillis();
            switch (s.state) {
                case "waiting":
                case "listed": {
                    boolean waits = "waiting".equals(s.state) || s.changeWaits;
                    if (!waits) return c.getString(R.string.projects_discover_block_listed);
                    boolean today = DiscoverModel.waveDay(s.waveAt, now, TimeZone.getDefault()) == DiscoverModel.Day.TODAY;
                    String time = AppDates.time(s.waveAt * 1000L);
                    String at = AppDates.turkish(c) ? TrGrammar.locativeTime(time) : time;
                    if ("waiting".equals(s.state)) {
                        return c.getString(today ? R.string.projects_discover_block_waiting_today : R.string.projects_discover_block_waiting_tomorrow, at);
                    }
                    return c.getString(today ? R.string.projects_discover_block_change_today : R.string.projects_discover_block_change_tomorrow, at);
                }
                case "refused": {
                    if (s.retryAt == null || s.retryAt * 1000L <= now) return null;
                    int days = DiscoverModel.daysUntil(s.retryAt, now);
                    return c.getResources().getQuantityString(R.plurals.projects_discover_block_refused, days, days);
                }
                case "closed":
                    return c.getString(R.string.projects_discover_block_closed);
                case "held":
                    return c.getString(R.string.projects_discover_block_held);
                default:
                    return null;
            }
        }

        private static List<String> needs(Context c, DiscoverModel.Status s) {
            List<String> out = new ArrayList<>();
            if (!s.members.holds()) out.add(c.getString(R.string.projects_discover_need_members, s.members.need, s.members.have));
            if (!s.crewDays.holds()) {
                out.add(c.getResources().getQuantityString(R.plurals.projects_discover_need_crew_days, s.crewDays.need, s.crewDays.need));
            }
            if (!s.leaderDays.holds()) {
                out.add(c.getResources().getQuantityString(R.plurals.projects_discover_need_leader_days, s.leaderDays.need, s.leaderDays.need));
            }
            if (!s.visible) out.add(c.getString(R.string.projects_discover_need_hidden));
            return out;
        }
    }

    private static TextView text(Context c, String value, int sp, int color, boolean semibold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(c, color));
        if (semibold) {
            try {
                t.setTypeface(ResourcesCompat.getFont(c, R.font.figtree_semibold));
            } catch (RuntimeException ignored) {
                // The theme's font.
            }
        }
        int d = dp(c, 10);
        t.setPadding(d, dp(c, 6), d, dp(c, 4));
        return t;
    }

    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
