package gg.projects.mobile.cloud.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
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
import androidx.appcompat.widget.SwitchCompat;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudEdit;
import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudText;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.Haptics;

/**
 * The sheets that edit one command or one auto-reply of a cloud account. They work on a copy: "Tamam" checks the row
 * with the site's rules and puts it back, closing the sheet keeps the list as it was. A secret line shows only its
 * preview until "Göster" asks the account's machine for it (sealed to a key made on this phone for that request);
 * "Değiştir" types a new one. A line with a password is secret by itself.
 */
final class CloudRowSheet {
    private CloudRowSheet() {}

    /** What the sheets need from the settings screen. */
    interface Host {
        Context context();

        CloudEdit.Config config();

        CloudModel.Limits limits();

        /** The account's server changes with this save (stored secrets must be typed again for it). */
        boolean serverChanged();

        /** CAFK-FEAT: the save checks the account's switches ask for (placeholders). */
        CloudEdit.Checks checks();

        void reveal(String rowId, CloudService.Result<String> result);

        /** The list changed (a row put back, deleted or moved). */
        void changed();
    }

    // ---- a command ----

    static void command(final Host host, final int index) {
        final Context c = host.context();
        final CloudEdit.Config config = host.config();
        final boolean isNew = index < 0;
        final CloudEdit.Command row = isNew
                ? new CloudEdit.Command(gg.projects.mobile.cloud.CloudApi.newRowId(new java.util.Random(), config.ids()), true, "join", 5,
                300, 600, Collections.<String>emptyList(), CloudEdit.Words.plain(""))
                : config.commands.get(index).copy();
        final Sheet sheet = new Sheet(c, c.getString(R.string.projects_cloud_command_title));

        final String[] triggers = {"join", "start", "reconnect", "interval", "daily"};
        CharSequence[] labels = {c.getString(R.string.projects_cloud_trigger_join), c.getString(R.string.projects_cloud_trigger_start),
                c.getString(R.string.projects_cloud_trigger_reconnect), c.getString(R.string.projects_cloud_trigger_interval),
                c.getString(R.string.projects_cloud_trigger_daily)};
        int selected = 0;
        for (int i = 0; i < triggers.length; i++) if (triggers[i].equals(row.on)) selected = i;
        sheet.body.addView(label(c, c.getString(R.string.projects_cloud_command_trigger)));
        final LinearLayout timing = new LinearLayout(c);
        timing.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] drawTiming = new Runnable[1];
        sheet.body.addView(CloudUi.choices(c, labels, selected, i -> {
            row.on = triggers[i];
            drawTiming[0].run();
        }));
        sheet.body.addView(timing, CloudUi.matchWrap());

        final CloudUi.Field delay = CloudUi.field(c, c.getString(R.string.projects_cloud_command_delay), String.valueOf(row.delay),
                null, InputType.TYPE_CLASS_NUMBER);
        delay.setHelp(c.getString(R.string.projects_cloud_issue_delay));
        delay.unit(c.getString(R.string.projects_cloud_settings_seconds));
        final CloudUi.Field everyMin = CloudUi.field(c, c.getString(R.string.projects_cloud_settings_at_least), String.valueOf(row.everyMin),
                null, InputType.TYPE_CLASS_NUMBER);
        final CloudUi.Field everyMax = CloudUi.field(c, c.getString(R.string.projects_cloud_settings_at_most), String.valueOf(row.everyMax),
                null, InputType.TYPE_CLASS_NUMBER);
        everyMin.unit(c.getString(R.string.projects_cloud_settings_seconds));
        everyMax.unit(c.getString(R.string.projects_cloud_settings_seconds));
        final TextView everyError = error(c);
        final LinearLayout times = new LinearLayout(c);
        times.setOrientation(LinearLayout.VERTICAL);
        final TextView timesError = error(c);
        drawTiming[0] = () -> {
            timing.removeAllViews();
            switch (row.on) {
                case "interval": {
                    LinearLayout pair = new LinearLayout(c);
                    pair.setOrientation(LinearLayout.HORIZONTAL);
                    CloudRowSheet.detach(everyMin.view);
                    CloudRowSheet.detach(everyMax.view);
                    pair.addView(everyMin.view, CloudUi.weight(1f));
                    pair.addView(everyMax.view, CloudUi.weight(1f));
                    timing.addView(pair, CloudUi.matchWrap());
                    timing.addView(everyError);
                    break;
                }
                case "daily":
                    timing.addView(label(c, c.getString(R.string.projects_cloud_command_times)));
                    timing.addView(times, CloudUi.matchWrap());
                    drawTimes(c, row, times);
                    timing.addView(CloudUi.note(c, c.getString(R.string.projects_cloud_command_times_help)));
                    timing.addView(timesError);
                    break;
                default:
                    detach(delay.view);
                    timing.addView(delay.view, CloudUi.matchWrap());
                    break;
            }
        };
        drawTiming[0].run();

        final SecretField words = new SecretField(host, row.id, c.getString(R.string.projects_cloud_command_text),
                c.getString(R.string.projects_cloud_command_text_hint), row.words);
        sheet.body.addView(words.view);

        final SwitchCompat use = CloudUi.toggle(c, c.getString(R.string.projects_cloud_command_use), row.enabled, on -> row.enabled = on);
        use.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 4), 0);
        sheet.body.addView(use);

        sheet.actions(isNew ? null : c.getString(R.string.projects_cloud_command_remove), () -> {
            config.commands.remove(index);
            host.changed();
            sheet.panel.dismiss();
        }, isNew || config.commands.size() < 2 ? null : (up -> {
            int to = index + (up ? -1 : 1);
            if (to < 0 || to >= config.commands.size()) return;
            Collections.swap(config.commands, index, to);
            host.changed();
            sheet.panel.dismiss();
        }), () -> {
            row.delay = number(delay.value(), -1);
            row.everyMin = number(everyMin.value(), -1);
            row.everyMax = number(everyMax.value(), -1);
            row.words = words.value();
            CloudEdit.Config trial = config.copy();
            int at = isNew ? trial.commands.size() : index;
            if (isNew) trial.commands.add(row.copy());
            else trial.commands.set(index, row.copy());
            delay.setError(null);
            everyError.setVisibility(View.GONE);
            timesError.setVisibility(View.GONE);
            words.setError(null);
            boolean ok = true;
            for (CloudEdit.Issue issue : CloudEdit.validate(trial, host.limits(), host.checks())) {
                if (!"commands".equals(issue.place) || issue.index != at) continue;
                String text = CloudWords.issue(c, issue, host.limits());
                switch (issue.field) {
                    case "delay": delay.setError(text); break;
                    case "every": show(everyError, text); break;
                    case "at": show(timesError, text); break;
                    default: words.setError(text); break;
                }
                ok = false;
            }
            if (!ok) {
                Haptics.reject(sheet.body);
                return false;
            }
            if (!row.on.equals("interval")) {
                row.everyMin = Math.max(row.everyMin, host.limits().minIntervalSec);
                row.everyMax = Math.max(row.everyMax, row.everyMin);
            }
            if (isNew) config.commands.add(row);
            else config.commands.set(index, row);
            host.changed();
            return true;
        });
        sheet.show();
    }

    private static void drawTimes(final Context c, final CloudEdit.Command row, final LinearLayout box) {
        box.removeAllViews();
        gg.projects.mobile.social.ui.WrapLayout chips = new gg.projects.mobile.social.ui.WrapLayout(c);
        chips.setGap(CloudUi.dp(c, 8));
        chips.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 4), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
        for (final String time : row.at) {
            Button chip = CloudUi.button(c, time, false, v -> {
                List<String> next = new ArrayList<>(row.at);
                next.remove(time);
                row.at = next;
                drawTimes(c, row, box);
            });
            chip.setCompoundDrawablesRelative(null, null, CloudUi.tinted(c, R.drawable.projects_ic_x, R.color.projects_ash_300, 14), null);
            chip.setCompoundDrawablePadding(CloudUi.dp(c, 6));
            chip.setContentDescription(c.getString(R.string.projects_cloud_command_remove_time, time));
            chips.addView(chip);
        }
        box.addView(chips, CloudUi.matchWrap());
        if (row.at.size() < 6) {
            Button add = CloudUi.textButton(c, c.getString(R.string.projects_cloud_command_add_time), v -> {
                TimePickerDialog dialog = new TimePickerDialog(c, (picker, hour, minute) -> {
                    String time = String.format(Locale.ROOT, "%02d:%02d", hour, minute);
                    if (row.at.contains(time)) return;
                    List<String> next = new ArrayList<>(row.at);
                    next.add(time);
                    Collections.sort(next);
                    row.at = next;
                    drawTimes(c, row, box);
                }, 8, 0, true);
                dialog.show();
            });
            box.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    // ---- an auto-reply ----

    static void rule(final Host host, final int index) {
        final Context c = host.context();
        final CloudEdit.Config config = host.config();
        final boolean isNew = index < 0;
        final CloudEdit.Rule row = isNew
                ? new CloudEdit.Rule(gg.projects.mobile.cloud.CloudApi.newRowId(new java.util.Random(), config.ids()), true, "", false, "any", false,
                Math.max(30, host.limits().minRuleCooldownSec), CloudEdit.Words.plain(""))
                : config.rules.get(index).copy();
        final Sheet sheet = new Sheet(c, c.getString(R.string.projects_cloud_rule_title));

        final CloudUi.Field match = CloudUi.field(c, c.getString(R.string.projects_cloud_rule_match), row.pattern,
                c.getString(R.string.projects_cloud_rule_match_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sheet.body.addView(match.view);
        final SwitchCompat regex = CloudUi.toggle(c, c.getString(R.string.projects_cloud_rule_regex), row.regex, on -> row.regex = on);
        regex.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 4), 0);
        sheet.body.addView(regex);

        final String[] sources = {"any", "player", "private", "server"};
        CharSequence[] labels = {c.getString(R.string.projects_cloud_rule_from_any), c.getString(R.string.projects_cloud_rule_from_player),
                c.getString(R.string.projects_cloud_rule_from_private), c.getString(R.string.projects_cloud_rule_from_server)};
        int selected = 0;
        for (int i = 0; i < sources.length; i++) if (sources[i].equals(row.from)) selected = i;
        sheet.body.addView(label(c, c.getString(R.string.projects_cloud_rule_from)));
        final Runnable[] fromChanged = new Runnable[1];
        sheet.body.addView(CloudUi.choices(c, labels, selected, i -> {
            row.from = sources[i];
            if (fromChanged[0] != null) fromChanged[0].run();
        }));

        final SwitchCompat owner = CloudUi.toggle(c, c.getString(R.string.projects_cloud_rule_owner_only), row.ownerOnly, on -> row.ownerOnly = on);
        owner.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 4), 0);
        sheet.body.addView(owner);
        final TextView ownerError = error(c);
        sheet.body.addView(ownerError);

        final CloudUi.Field cooldown = CloudUi.field(c, c.getString(R.string.projects_cloud_rule_cooldown), String.valueOf(row.cooldown),
                null, InputType.TYPE_CLASS_NUMBER);
        cooldown.unit(c.getString(R.string.projects_cloud_settings_seconds));
        sheet.body.addView(cooldown.view);

        final SecretField reply = new SecretField(host, row.id, c.getString(R.string.projects_cloud_rule_reply),
                c.getString(R.string.projects_cloud_rule_reply_hint), row.reply);
        sheet.body.addView(reply.view);
        // CAFK-FEAT: placeholders in the reply (the account's switch): chips that put {u} and {1}… at the cursor, the
        // one rule about the parts, and "Dene" on a sample message. Redrawn when the pattern, its kind or "from" changes.
        if (host.checks().placeholders) {
            final PlaceholderTools tools = new PlaceholderTools(c, reply, () -> match.value().trim(), () -> row.regex, () -> row.from,
                    () -> host.config().owner);
            sheet.body.addView(tools.view, CloudUi.matchWrap());
            match.input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    tools.draw();
                }
            });
            regex.setOnCheckedChangeListener((button, on) -> {
                Haptics.toggle(button, on);
                row.regex = on;
                tools.draw();
            });
            reply.onChanged(tools::draw);
            fromChanged[0] = tools::draw;
        }

        final SwitchCompat use = CloudUi.toggle(c, c.getString(R.string.projects_cloud_rule_use), row.enabled, on -> row.enabled = on);
        use.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 4), 0);
        sheet.body.addView(use);

        sheet.actions(isNew ? null : c.getString(R.string.projects_cloud_rule_remove), () -> {
            config.rules.remove(index);
            host.changed();
            sheet.panel.dismiss();
        }, null, () -> {
            row.pattern = match.value().trim();
            row.cooldown = number(cooldown.value(), -1);
            row.reply = reply.value();
            CloudEdit.Config trial = config.copy();
            int at = isNew ? trial.rules.size() : index;
            if (isNew) trial.rules.add(row.copy());
            else trial.rules.set(index, row.copy());
            match.setError(null);
            cooldown.setError(null);
            ownerError.setVisibility(View.GONE);
            reply.setError(null);
            boolean ok = true;
            for (CloudEdit.Issue issue : CloudEdit.validate(trial, host.limits(), host.checks())) {
                if (!"rules".equals(issue.place) || issue.index != at) continue;
                String text = CloudWords.issue(c, issue, host.limits());
                switch (issue.field) {
                    case "match": match.setError(text); break;
                    case "cooldown": cooldown.setError(text); break;
                    case "ownerOnly": show(ownerError, text); break;
                    default: reply.setError(text); break;
                }
                ok = false;
            }
            if (!ok) {
                Haptics.reject(sheet.body);
                return false;
            }
            if (isNew) config.rules.add(row);
            else config.rules.set(index, row);
            host.changed();
            return true;
        });
        sheet.show();
    }

    // ---- the parts ----

    interface Mover {
        void move(boolean up);
    }

    interface Apply {
        /** Puts the row back; false keeps the sheet open (something is marked). */
        boolean apply();
    }

    /** A sheet with a title, a scrolling body and the actions at the bottom. */
    static final class Sheet {
        final LinearLayout body;
        final BottomPanel panel;
        private final LinearLayout mRoot;

        Sheet(Context c, String title) {
            // One scrolling column, the actions at its end. (25 Oct: a pinned action bar under a weighted scroll left
            // the body empty inside BottomPanel on the emulator; reverted. Pinning needs its own measured attempt.)
            ScrollView scroll = new ScrollView(c);
            scroll.setClipToPadding(false);
            mRoot = new LinearLayout(c);
            mRoot.setOrientation(LinearLayout.VERTICAL);
            // A dialog's title, as the app's other sheets (ReactionSheet): Tektur 17 (design.md §3).
            TextView heading = CloudUi.text(c, title, 17, R.color.projects_bone_100, true);
            heading.setTypeface(CloudUi.font(c, R.font.tektur_semibold));
            heading.setPadding(CloudUi.dp(c, 24), CloudUi.dp(c, 2), CloudUi.dp(c, 24), CloudUi.dp(c, 6));
            if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            mRoot.addView(heading);
            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 8));
            mRoot.addView(body, CloudUi.matchWrap());
            scroll.addView(mRoot, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel = BottomPanel.of(c, scroll, 0.92f);
        }

        void actions(@Nullable String delete, @Nullable Runnable onDelete, @Nullable Mover mover, final Apply apply) {
            Context c = mRoot.getContext();
            if (mover != null) {
                LinearLayout move = new LinearLayout(c);
                move.setOrientation(LinearLayout.HORIZONTAL);
                move.setPadding(CloudUi.dp(c, 4), 0, CloudUi.dp(c, 4), 0);
                move.addView(CloudUi.textButton(c, c.getString(R.string.projects_cloud_command_move_up), v -> mover.move(true)));
                move.addView(CloudUi.textButton(c, c.getString(R.string.projects_cloud_command_move_down), v -> mover.move(false)));
                body.addView(move, CloudUi.matchWrap());
            }
            LinearLayout bar = new LinearLayout(c);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 8), CloudUi.dp(c, 16), CloudUi.dp(c, 16));
            if (delete != null && onDelete != null) {
                Button remove = CloudUi.textButton(c, delete, v -> onDelete.run());
                remove.setTextColor(CloudUi.color(c, R.color.projects_flare_400));
                bar.addView(remove);
            }
            View space = new View(c);
            bar.addView(space, CloudUi.weight(1f));
            Button done = CloudUi.button(c, c.getString(R.string.projects_cloud_settings_done), true, v -> {
                if (apply.apply()) {
                    Haptics.confirm(v);
                    panel.dismiss();
                }
            });
            done.setMinWidth(CloudUi.dp(c, 120));
            bar.addView(done);
            mRoot.addView(bar, CloudUi.matchWrap());
        }

        void show() {
            panel.show();
        }
    }

    /** A line's words that may be secret: the field, the "Gizli" switch, and for a stored secret its preview. */
    static final class SecretField {
        final LinearLayout view;
        private final Host mHost;
        private final String mRowId;
        private final String mLabel;
        private final String mHint;
        private CloudEdit.Words mWords;
        private boolean mShown;
        private boolean mRevealing;
        @Nullable
        private String mError;
        @Nullable
        private String mNote;
        @Nullable
        private EditText mInput;

        SecretField(Host host, String rowId, String label, String hint, CloudEdit.Words words) {
            mHost = host;
            mRowId = rowId;
            mLabel = label;
            mHint = hint;
            mWords = words;
            mShown = words.revealed;
            view = new LinearLayout(host.context());
            view.setOrientation(LinearLayout.VERTICAL);
            view.setLayoutParams(CloudUi.matchWrap());
            draw();
        }

        CloudEdit.Words value() {
            return mWords;
        }

        @Nullable
        private Runnable mChanged;

        /** Called when the words or their kind change (the placeholder tools redraw). */
        void onChanged(Runnable changed) {
            mChanged = changed;
        }

        /** Puts {@code token} at the cursor of a plain line (a placeholder chip); false when the line is secret or sealed. */
        boolean insert(String token) {
            if (mInput == null || mWords.secretKind()) return false;
            int start = Math.max(0, mInput.getSelectionStart());
            int end = Math.max(0, mInput.getSelectionEnd());
            mInput.getText().replace(Math.min(start, end), Math.max(start, end), token);
            mInput.requestFocus();
            return true;
        }

        void setError(@Nullable String error) {
            mError = error;
            draw();
        }

        private void draw() {
            final Context c = mHost.context();
            view.removeAllViews();
            mInput = null;
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 10), 0, 0);
            TextView name = CloudUi.text(c, mLabel, 14, R.color.projects_bone_100, true);
            head.addView(name, CloudUi.weight(1f));
            view.addView(head, CloudUi.matchWrap());

            if (mWords.kind == CloudEdit.Words.Kind.SEALED) {
                LinearLayout sealed = new LinearLayout(c);
                sealed.setOrientation(LinearLayout.HORIZONTAL);
                sealed.setGravity(Gravity.CENTER_VERTICAL);
                sealed.setBackgroundResource(R.drawable.projects_input);
                sealed.setMinimumHeight(CloudUi.dp(c, 52));
                sealed.setPadding(CloudUi.dp(c, 14), CloudUi.dp(c, 8), CloudUi.dp(c, 14), CloudUi.dp(c, 8));
                ImageView lock = new ImageView(c);
                lock.setImageDrawable(CloudUi.tinted(c, R.drawable.projects_ic_lock, R.color.projects_gold_300, 18));
                lock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                sealed.addView(lock, new LinearLayout.LayoutParams(CloudUi.dp(c, 18), CloudUi.dp(c, 18)));
                LinearLayout texts = new LinearLayout(c);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(CloudUi.text(c, mWords.preview == null ? "******" : mWords.preview, 15, R.color.projects_bone_100, false));
                texts.addView(CloudUi.text(c, c.getString(R.string.projects_cloud_secret_sealed), 12.5f, R.color.projects_ash_400, false));
                LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
                textsLp.setMarginStart(CloudUi.dp(c, 12));
                sealed.addView(texts, textsLp);
                LinearLayout.LayoutParams sealedLp = CloudUi.matchWrap();
                sealedLp.topMargin = CloudUi.dp(c, 8);
                sealedLp.leftMargin = CloudUi.dp(c, 8);
                sealedLp.rightMargin = CloudUi.dp(c, 8);
                view.addView(sealed, sealedLp);
                LinearLayout buttons = new LinearLayout(c);
                buttons.setOrientation(LinearLayout.HORIZONTAL);
                buttons.setPadding(CloudUi.dp(c, 2), 0, 0, 0);
                Button show = CloudUi.textButton(c, c.getString(R.string.projects_cloud_secret_show), this::reveal);
                show.setEnabled(!mRevealing && !mHost.serverChanged());
                show.setAlpha(show.isEnabled() ? 1f : 0.5f);
                buttons.addView(show);
                buttons.addView(CloudUi.textButton(c, c.getString(R.string.projects_cloud_secret_change), v -> {
                    mWords = CloudEdit.Words.secret("");
                    mShown = true;
                    mNote = null;
                    draw();
                    if (mInput != null) mInput.requestFocus();
                }));
                view.addView(buttons, CloudUi.matchWrap());
                if (mHost.serverChanged()) note(c, c.getString(R.string.projects_cloud_secret_reseal), R.color.projects_gold_300);
            } else {
                final boolean secret = mWords.secretKind();
                if (secret) {
                    Button eye = CloudUi.textButton(c, c.getString(mShown ? R.string.projects_cloud_secret_hide : R.string.projects_cloud_secret_show), v -> {
                        mShown = !mShown;
                        draw();
                    });
                    head.addView(eye);
                }
                EditText input = new EditText(c);
                input.setBackgroundResource(R.drawable.projects_input);
                input.setTypeface(CloudUi.font(c, R.font.figtree_regular));
                input.setTextSize(16);
                input.setTextColor(CloudUi.color(c, R.color.projects_bone_100));
                input.setHintTextColor(CloudUi.color(c, R.color.projects_ash_400));
                input.setPadding(CloudUi.dp(c, 14), CloudUi.dp(c, 12), CloudUi.dp(c, 14), CloudUi.dp(c, 12));
                input.setSingleLine(true);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                input.setMinimumHeight(CloudUi.dp(c, 52));
                input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                if (secret && !mShown) input.setTransformationMethod(PasswordTransformationMethod.getInstance());
                if (android.os.Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
                input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});
                input.setHint(mHint);
                input.setText(mWords.text);
                input.setSelection(input.getText().length());
                input.setContentDescription(mLabel);
                LinearLayout.LayoutParams inputLp = CloudUi.matchWrap();
                inputLp.topMargin = CloudUi.dp(c, 8);
                inputLp.leftMargin = CloudUi.dp(c, 8);
                inputLp.rightMargin = CloudUi.dp(c, 8);
                view.addView(input, inputLp);
                mInput = input;
                final boolean forced = CloudText.isSensitive(mWords.text);
                final SwitchCompat toggle = CloudUi.toggle(c, c.getString(R.string.projects_cloud_secret_mark), secret, on -> {
                    String text = mInput == null ? mWords.text : mInput.getText().toString();
                    mWords = on ? CloudEdit.Words.secret(text) : CloudEdit.Words.plain(text);
                    mShown = !on;
                    draw();
                    if (mChanged != null) mChanged.run();
                });
                toggle.setEnabled(!forced);
                toggle.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 4), 0);
                view.addView(toggle);
                final TextView help = CloudUi.note(c, c.getString(forced ? R.string.projects_cloud_secret_auto : R.string.projects_cloud_secret_help));
                view.addView(help);
                input.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        String text = s.toString();
                        if (text.equals(mWords.text)) return;
                        boolean sensitive = CloudText.isSensitive(text);
                        if (sensitive && !mWords.secretKind()) {
                            // A password line is secret by itself (the field stays where the player types).
                            mWords = CloudEdit.Words.secret(text);
                            mShown = true;
                            toggle.setChecked(true);
                        } else {
                            mWords = mWords.typed(text);
                        }
                        if (mChanged != null) mChanged.run();
                        toggle.setEnabled(!sensitive);
                        help.setText(c.getString(sensitive ? R.string.projects_cloud_secret_auto : R.string.projects_cloud_secret_help));
                    }
                });
            }
            if (mNote != null) note(c, mNote, R.color.projects_ash_300);
            if (mError != null) {
                TextView error = error(c);
                show(error, mError);
                view.addView(error);
            }
        }

        private void note(Context c, String text, int color) {
            TextView note = CloudUi.note(c, text);
            note.setTextColor(CloudUi.color(c, color));
            view.addView(note);
        }

        private void reveal(final View from) {
            if (mRevealing) return;
            mRevealing = true;
            mNote = null;
            draw();
            mHost.reveal(mRowId, new CloudService.Result<String>() {
                @Override
                public void ok(String text) {
                    mRevealing = false;
                    String preview = mWords.preview;
                    mWords = CloudEdit.Words.revealed(text, preview);
                    mShown = true;
                    draw();
                }

                @Override
                public void failed(CloudProblem problem) {
                    mRevealing = false;
                    Context c = mHost.context();
                    Haptics.reject(from);
                    int words;
                    if (problem.kind == CloudProblem.Kind.HOST_OFFLINE) words = R.string.projects_cloud_secret_offline;
                    else if (problem.kind == CloudProblem.Kind.RATE_LIMITED) words = R.string.projects_cloud_secret_limited;
                    else if (problem.is("secret_unreadable")) words = R.string.projects_cloud_secret_unreadable;
                    else {
                        mNote = CloudWords.problem(c, problem);
                        draw();
                        return;
                    }
                    mNote = c.getString(words);
                    draw();
                }
            });
        }
    }

    interface Text {
        String get();
    }

    interface Flag {
        boolean get();
    }

    /**
     * CAFK-FEAT §3.3 under a reply: "Yazanın adı" and one chip per capturing group of the pattern ("1. parça"…) that put
     * {@code {u}} and {@code {1}} at the cursor; once the reply holds one, the rule about the parts in one sentence; and
     * "Dene": a sample message tried on the phone with the save rules and the bot's value class ({@code {u}} is the
     * player's own name, else "Oyuncu"). Nothing for a secret reply (placeholders are plain text there).
     */
    static final class PlaceholderTools {
        final LinearLayout view;
        private final Context mContext;
        private final SecretField mReply;
        private final Text mPattern;
        private final Flag mRegex;
        private final Text mFrom;
        private final Text mOwner;
        private final TextView mChipsLabel;
        private final gg.projects.mobile.social.ui.WrapLayout mChips;
        private final TextView mRule;
        private final EditText mSample;
        private final TextView mResult;
        private String mDrawn = "";

        PlaceholderTools(Context c, SecretField reply, Text pattern, Flag regex, Text from, Text owner) {
            mContext = c;
            mReply = reply;
            mPattern = pattern;
            mRegex = regex;
            mFrom = from;
            mOwner = owner;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            mChipsLabel = CloudUi.text(c, c.getString(R.string.projects_feat_ph_add), 13, R.color.projects_ash_300, false);
            mChipsLabel.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 6), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
            view.addView(mChipsLabel, CloudUi.matchWrap());
            mChips = new gg.projects.mobile.social.ui.WrapLayout(c);
            mChips.setGap(CloudUi.dp(c, 8));
            mChips.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 2), CloudUi.dp(c, 8), CloudUi.dp(c, 2));
            view.addView(mChips, CloudUi.matchWrap());
            mRule = CloudUi.note(c, c.getString(R.string.projects_feat_ph_rule));
            view.addView(mRule);
            LinearLayout tryRow = new LinearLayout(c);
            tryRow.setOrientation(LinearLayout.HORIZONTAL);
            tryRow.setGravity(Gravity.CENTER_VERTICAL);
            tryRow.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 6), CloudUi.dp(c, 8), 0);
            mSample = new EditText(c);
            mSample.setBackgroundResource(R.drawable.projects_input);
            mSample.setTypeface(CloudUi.font(c, R.font.figtree_regular));
            mSample.setTextSize(15);
            mSample.setTextColor(CloudUi.color(c, R.color.projects_bone_100));
            mSample.setHintTextColor(CloudUi.color(c, R.color.projects_ash_400));
            mSample.setHint(c.getString(R.string.projects_feat_ph_sample));
            mSample.setSingleLine(true);
            mSample.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            mSample.setPadding(CloudUi.dp(c, 14), CloudUi.dp(c, 10), CloudUi.dp(c, 14), CloudUi.dp(c, 10));
            mSample.setMinimumHeight(CloudUi.dp(c, 48));
            mSample.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});
            if (android.os.Build.VERSION.SDK_INT >= 26) mSample.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            tryRow.addView(mSample, CloudUi.weight(1f));
            Button tryIt = CloudUi.button(c, c.getString(R.string.projects_feat_ph_try), false, v -> tryIt(v));
            LinearLayout.LayoutParams tryLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tryLp.setMarginStart(CloudUi.dp(c, 10));
            tryRow.addView(tryIt, tryLp);
            view.addView(tryRow, CloudUi.matchWrap());
            mResult = CloudUi.text(c, "", 14, R.color.projects_ash_300, false);
            mResult.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 6), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
            mResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            mResult.setVisibility(View.GONE);
            view.addView(mResult, CloudUi.matchWrap());
            draw();
        }

        void draw() {
            CloudEdit.Words words = mReply.value();
            boolean plain = words.kind == CloudEdit.Words.Kind.PLAIN;
            view.setVisibility(plain ? View.VISIBLE : View.GONE);
            if (!plain) return;
            mRule.setVisibility(gg.projects.mobile.cloud.CloudPlaceholders.holds(words.text) ? View.VISIBLE : View.GONE);
            List<String> chips = gg.projects.mobile.cloud.CloudPlaceholders.chips(mPattern.get(), mRegex.get(), mFrom.get());
            String key = chips.toString();
            if (key.equals(mDrawn)) return;
            mDrawn = key;
            mChipsLabel.setVisibility(chips.isEmpty() ? View.GONE : View.VISIBLE);
            mChips.removeAllViews();
            for (final String token : chips) {
                String label = "{u}".equals(token) ? mContext.getString(R.string.projects_feat_ph_sender)
                        : mContext.getString(R.string.projects_feat_ph_part, token.charAt(1) - '0');
                Button chip = CloudUi.button(mContext, label, false, v -> {
                    if (mReply.insert(token)) Haptics.tick(v);
                });
                chip.setTextSize(14);
                chip.setMinHeight(CloudUi.dp(mContext, 40));
                chip.setMinimumHeight(CloudUi.dp(mContext, 40));
                chip.setPadding(CloudUi.dp(mContext, 12), 0, CloudUi.dp(mContext, 12), 0);
                chip.setCompoundDrawablesRelative(CloudUi.tinted(mContext, R.drawable.projects_ic_plus, R.color.projects_gold_300, 14), null, null, null);
                chip.setCompoundDrawablePadding(CloudUi.dp(mContext, 6));
                mChips.addView(chip);
            }
        }

        private void tryIt(View from) {
            String sample = mSample.getText().toString();
            if (sample.trim().isEmpty()) {
                Haptics.reject(from);
                mSample.requestFocus();
                return;
            }
            String owner = mOwner.get();
            String sender = owner == null || owner.isEmpty() ? mContext.getString(R.string.projects_feat_ph_player) : owner;
            gg.projects.mobile.cloud.CloudPlaceholders.Run run = gg.projects.mobile.cloud.CloudPlaceholders.tryIt(mPattern.get(), mRegex.get(),
                    mFrom.get(), mReply.value().text, sample, sender);
            if (run.reply != null) {
                mResult.setText(mContext.getString(R.string.projects_feat_ph_matches, run.reply));
                mResult.setTextColor(CloudUi.color(mContext, R.color.projects_bone_100));
                Haptics.confirm(from);
            } else if ("value".equals(run.why)) {
                mResult.setText(mContext.getString(R.string.projects_feat_ph_no_reply));
                mResult.setTextColor(CloudUi.color(mContext, R.color.projects_gold_300));
                Haptics.tick(from);
            } else if ("pattern".equals(run.why)) {
                mResult.setText(mContext.getString(R.string.projects_feat_ph_fix_pattern));
                mResult.setTextColor(CloudUi.color(mContext, R.color.projects_gold_300));
                Haptics.reject(from);
            } else {
                mResult.setText(mContext.getString(R.string.projects_feat_ph_no_match));
                mResult.setTextColor(CloudUi.color(mContext, R.color.projects_ash_300));
                Haptics.tick(from);
            }
            mResult.setVisibility(View.VISIBLE);
        }
    }

    static TextView label(Context c, String text) {
        TextView label = CloudUi.text(c, text, 14, R.color.projects_bone_100, true);
        label.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 14), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
        return label;
    }

    static TextView error(Context c) {
        TextView error = CloudUi.text(c, "", 13, R.color.projects_flare_400, false);
        error.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 4), CloudUi.dp(c, 8), CloudUi.dp(c, 2));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        return error;
    }

    static void show(TextView error, String text) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
    }

    static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    static int number(String typed, int fallback) {
        try {
            return Integer.parseInt(typed.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String trimmed(@Nullable String s) {
        return s == null ? "" : s.trim();
    }

    static boolean empty(@Nullable String s) {
        return TextUtils.isEmpty(s == null ? null : s.trim());
    }
}
