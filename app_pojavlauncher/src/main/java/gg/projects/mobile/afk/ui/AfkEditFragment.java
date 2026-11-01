package gg.projects.mobile.afk.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.TransformationMethod;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.afk.api.AfkAccount;
import gg.projects.mobile.afk.api.AfkDraft;
import gg.projects.mobile.afk.api.AfkDraftException;
import gg.projects.mobile.afk.api.AfkLimits;
import gg.projects.mobile.afk.api.AfkLoginMode;
import gg.projects.mobile.afk.api.AfkProblem;
import gg.projects.mobile.afk.api.AfkStartResult;
import gg.projects.mobile.afk.api.AfkTexts;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.ConfirmDialog;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Hesap ekle" and "{ad} ayarları" (the desktop's add field and settings sheet in one screen, as a phone wants it): the
 * username (adding only; a name of this phone's own username accounts fills it with one tap), the server address (empty
 * is play.projects.gg), the login commands (secret words masked, the eye shows them; at most
 * {@link AfkLimits#MAX_LOGIN_COMMANDS}), "Yalnız ilk girişte çalıştır", "AFK engelleme" and "Mobil veride sürsün". The
 * engine's rules say what is wrong, under the field it is about. Unsaved changes ride a rotation in memory only (a
 * ViewModel: the commands are secrets, never in saved state) and back asks before they are lost.
 */
public final class AfkEditFragment extends AfkScreen {
    public static final String TAG = "ProjectsAfkEditFragment";
    private static final String ARG_ACCOUNT = "gg.projects.mobile.afk.ACCOUNT";
    private static final String ARG_COMMANDS = "gg.projects.mobile.afk.COMMANDS";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ProjectsAfkEdit");
        t.setDaemon(true);
        return t;
    });

    /**
     * The draft as it started and as it is now, kept through a rotation (in memory only), and a save that finished
     * while the screen was being made again (its answer goes to the screen showing then).
     */
    public static final class Kept extends ViewModel {
        @Nullable
        AfkDraft start;
        @Nullable
        AfkDraft now;
        boolean asked;
        boolean saving;
        /** The saved account's name once a save went through, for the screen to say so and leave. */
        @Nullable
        String savedName;
        @Nullable
        List<AfkProblem> refused;
        /** The account went away while saving. */
        boolean gone;
        /** "Kaydet ve yeniden bağlan": the engine's answer to the reconnect after the save (null: no reconnect). */
        @Nullable
        AfkStartResult reconnected;
        @Nullable
        AfkEditFragment owner;
    }

    private Kept mKept;
    @Nullable
    private CloudUi.Field mName;
    @Nullable
    private CloudUi.Field mAddress;
    @Nullable
    private LinearLayout mCommands;
    @Nullable
    private CloudUi.Row mAddCommand;
    @Nullable
    private TextView mCommandsError;
    @Nullable
    private TextView mSecretNote;
    @Nullable
    private TextView mNoneError;
    @Nullable
    private View mBar;
    @Nullable
    private TextView mBarWords;
    @Nullable
    private Button mSave;
    /** "Yalnız kaydet", beside "Kaydet ve yeniden bağlan" while the account runs (null otherwise). */
    @Nullable
    private Button mSaveOnly;
    private boolean mSaving;
    private boolean mBarShown;
    /** Counts the form's builds: a late answer for an earlier build ("Vazgeç" built it again) lands nowhere. */
    private int mBuilds;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public static Bundle args(@Nullable String accountId, boolean commands) {
        Bundle args = new Bundle();
        args.putString(ARG_ACCOUNT, accountId);
        args.putBoolean(ARG_COMMANDS, commands);
        return args;
    }

    @Nullable
    private String accountId() {
        Bundle args = getArguments();
        return args == null ? null : args.getString(ARG_ACCOUNT);
    }

    private boolean adding() {
        return accountId() == null;
    }

    @Override
    protected void created(View view, @Nullable Bundle savedInstanceState) {
        mKept = new ViewModelProvider(this).get(Kept.class);
        mKept.owner = this;
        mSaving = mKept.saving;
        String id = accountId();
        if (id == null) {
            mHeader.setTitle(getString(R.string.projects_afk_ui_add));
            if (mKept.start == null) {
                AfkDraft draft = new AfkDraft();
                mKept.start = draft;
                mKept.now = AfkFormRules.copy(draft);
            }
            build();
            return;
        }
        AfkAccount account = mAfk.account(id);
        if (account == null) {
            leaveNow();
            return;
        }
        mHeader.setTitle(getString(R.string.projects_afk_ui_settings_title, account.name));
        if (mKept.start != null) {
            build();
            return;
        }
        if (mContent != null) mContent.addView(CloudUi.loading(ctx()));
        // The login commands are sealed: opening them takes the Keystore, off the main thread.
        mAfk.edit(id, draft -> {
            if (!alive()) return;
            if (draft == null) {
                leaveNow();
                return;
            }
            mKept.start = AfkFormRules.copy(draft);
            mKept.now = draft;
            build();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // A save that finished while the screen was being made again.
        if (alive() && mKept != null && !mKept.saving && (mKept.savedName != null || mKept.refused != null || mKept.gone)) saveDone();
    }

    @Override
    public void onDestroyView() {
        if (mKept != null && mKept.owner == this) mKept.owner = null;
        super.onDestroyView();
    }

    @Override
    protected void onBack() {
        if (!changed()) {
            leaveNow();
            return;
        }
        ConfirmDialog.show(requireActivity(), getString(R.string.projects_afk_ui_discard_title),
                getString(R.string.projects_afk_ui_discard_body), getString(R.string.projects_afk_ui_discard_confirm), true, () -> {
                    if (alive()) leaveNow();
                });
    }

    private boolean changed() {
        return mKept != null && mKept.now != null && (adding() ? hasInput(mKept.now) : AfkFormRules.changed(mKept.start, mKept.now));
    }

    /** Adding: anything typed counts (the defaults alone do not). */
    private static boolean hasInput(AfkDraft d) {
        return !d.name.trim().isEmpty() || !d.address.trim().isEmpty() || !AfkFormRules.commands(d.loginCommands).isEmpty()
                || d.loginMode != AfkLoginMode.EVERY_JOIN || !d.sneak || d.mobileData;
    }

    // ---- the form ----

    private void build() {
        mBuilds++;
        final LinearLayout content = clear();
        if (content == null || mKept.now == null) return;
        final Context c = ctx();
        final AfkDraft d = mKept.now;
        String id = accountId();

        mNoneError = CloudUi.text(c, "", 13.5f, R.color.projects_flare_400, false);
        mNoneError.setVisibility(View.GONE);
        mNoneError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        mNoneError.setPadding(AfkUi.dp(c, 8), AfkUi.dp(c, 8), AfkUi.dp(c, 8), 0);
        content.addView(mNoneError, CloudUi.matchWrap());

        if (id == null) {
            mName = CloudUi.field(c, getString(R.string.projects_afk_ui_name), d.name, null,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            mName.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AfkLimits.NAME_MAX)});
            // The password variation (no suggestions on any keyboard) puts a monospace face on: Figtree again.
            mName.input.setTypeface(AfkUi.font(c, R.font.figtree_regular));
            mName.setHelp(getString(R.string.projects_afk_ui_name_help));
            mName.input.addTextChangedListener(new Watcher(s -> {
                d.name = s;
                mName.setError(null);
                changedNow();
            }));
            content.addView(mName.view);
            phoneNames(content, d);
        } else {
            mName = null;
        }

        content.addView(CloudUi.section(c, getString(R.string.projects_afk_ui_connection)));
        mAddress = CloudUi.field(c, getString(R.string.projects_afk_ui_address), d.address, AfkFormRules.DEFAULT_ADDRESS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mAddress.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AfkLimits.MAX_ADDRESS_CHARS)});
        mAddress.setHelp(getString(R.string.projects_afk_ui_address_help));
        mAddress.input.addTextChangedListener(new Watcher(s -> {
            d.address = s;
            mAddress.setError(null);
            changedNow();
        }));
        content.addView(mAddress.view);

        // The login commands.
        TextView commandsTitle = CloudUi.section(c, getString(R.string.projects_afk_ui_commands));
        content.addView(commandsTitle);
        TextView intro = CloudUi.note(c, getString(R.string.projects_afk_ui_login_intro));
        content.addView(intro);
        if (d.loginCommandsUnreadable) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_gold_300,
                    getString(R.string.projects_afk_ui_login_unreadable)));
        }
        LinearLayout commandsGroup = CloudUi.group(c);
        LinearLayout.LayoutParams commandsLp = CloudUi.matchWrap();
        commandsLp.topMargin = AfkUi.dp(c, 6);
        mCommands = new LinearLayout(c);
        mCommands.setOrientation(LinearLayout.VERTICAL);
        commandsGroup.addView(mCommands, CloudUi.matchWrap());
        mAddDivider = CloudUi.rowDivider(c);
        commandsGroup.addView(mAddDivider);
        mAddCommand = CloudUi.addRow(c, getString(R.string.projects_afk_ui_login_add), v -> {
            if (d.loginCommands.size() >= AfkLimits.MAX_LOGIN_COMMANDS) return;
            if (mContent != null) gg.projects.mobile.ui.CardMotion.beginLayoutChange(mContent);
            d.loginCommands.add("");
            CommandRow row = addCommandRow(c, d, d.loginCommands.size() - 1);
            refreshCommands(d);
            changedNow();
            row.input.requestFocus();
            showKeyboard(row.input);
        });
        commandsGroup.addView(mAddCommand.view);
        content.addView(commandsGroup, commandsLp);
        mCommandRows.clear();
        for (int i = 0; i < d.loginCommands.size(); i++) addCommandRow(c, d, i);
        mCommandsError = CloudUi.text(c, "", 13, R.color.projects_flare_400, false);
        mCommandsError.setVisibility(View.GONE);
        mCommandsError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        mCommandsError.setPadding(AfkUi.dp(c, 8), AfkUi.dp(c, 6), AfkUi.dp(c, 8), 0);
        content.addView(mCommandsError, CloudUi.matchWrap());
        mSecretNote = CloudUi.note(c, getString(R.string.projects_afk_ui_secret_note));
        mSecretNote.setCompoundDrawablesRelative(CloudUi.tinted(c, R.drawable.projects_ic_lock, R.color.projects_ash_400, 14), null, null, null);
        mSecretNote.setCompoundDrawablePadding(AfkUi.dp(c, 6));
        content.addView(mSecretNote);
        LinearLayout loginGroup = CloudUi.group(c);
        SwitchCompat onlyFirst = CloudUi.toggle(c, getString(R.string.projects_afk_ui_only_first), d.loginMode == AfkLoginMode.FIRST_JOIN, on -> {
            d.loginMode = on ? AfkLoginMode.FIRST_JOIN : AfkLoginMode.EVERY_JOIN;
            changedNow();
        });
        loginGroup.addView(onlyFirst);
        LinearLayout.LayoutParams loginLp = CloudUi.matchWrap();
        loginLp.topMargin = AfkUi.dp(c, 10);
        content.addView(loginGroup, loginLp);
        content.addView(CloudUi.note(c, getString(R.string.projects_afk_ui_only_first_help)));
        refreshCommands(d);

        // In the game.
        content.addView(CloudUi.section(c, getString(R.string.projects_afk_ui_game)));
        LinearLayout gameGroup = CloudUi.group(c);
        gameGroup.addView(CloudUi.toggle(c, getString(R.string.projects_afk_ui_sneak), d.sneak, on -> {
            d.sneak = on;
            changedNow();
        }));
        content.addView(gameGroup);
        content.addView(CloudUi.note(c, getString(R.string.projects_afk_ui_sneak_help)));

        // The phone.
        content.addView(CloudUi.section(c, getString(R.string.projects_afk_ui_phone)));
        LinearLayout phoneGroup = CloudUi.group(c);
        phoneGroup.addView(CloudUi.toggle(c, getString(R.string.projects_afk_ui_mobile_data), d.mobileData, on -> {
            d.mobileData = on;
            changedNow();
        }));
        content.addView(phoneGroup);
        content.addView(CloudUi.note(c, getString(R.string.projects_afk_ui_mobile_data_help)));

        if (id != null) {
            // Deleting is in the row's menu too; here it is where a phone player looks for it.
            Button delete = CloudUi.textButton(c, getString(R.string.projects_afk_ui_remove_confirm), v -> confirmRemove());
            delete.setTextColor(AfkUi.color(c, R.color.projects_flare_400));
            LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            deleteLp.topMargin = AfkUi.dp(c, 18);
            content.addView(delete, deleteLp);
        }

        buildBar(c);
        changedNow();
        Bundle args = getArguments();
        if (args != null && args.getBoolean(ARG_COMMANDS) && !mKept.asked) {
            // "Şifreyi düzelt" / "Girişi düzelt": the commands, the first secret one ready to type.
            mKept.asked = true;
            content.post(() -> focusCommands(commandsTitle));
        }
    }

    /** "Bu telefondaki adlar": this phone's username accounts not yet AFK, one tap fills the name (read off the main thread). */
    private void phoneNames(final LinearLayout content, final AfkDraft d) {
        final int at = content.getChildCount();
        final int build = mBuilds;
        final List<String> afkNames = new ArrayList<>();
        for (AfkAccount a : mAfk.accounts()) afkNames.add(a.name);
        IO.execute(() -> {
            List<String> local = new ArrayList<>();
            String[] files = new File(Tools.DIR_ACCOUNT_NEW).list();
            if (files != null) {
                java.util.Arrays.sort(files, String.CASE_INSENSITIVE_ORDER);
                for (String f : files) {
                    if (!f.endsWith(".json")) continue;
                    try {
                        MinecraftAccount account = MinecraftAccount.load(f.substring(0, f.length() - 5));
                        if (account != null && !account.isMicrosoft && account.isLocal()) local.add(account.username);
                    } catch (RuntimeException ignored) {
                        // An account file that cannot be read offers nothing.
                    }
                }
            }
            final List<String> names = AfkFormRules.phoneNames(local, afkNames);
            mMain.post(() -> {
                if (!alive() || build != mBuilds || names.isEmpty() || mName == null) return;
                Context c = ctx();
                LinearLayout block = new LinearLayout(c);
                block.setOrientation(LinearLayout.VERTICAL);
                TextView label = CloudUi.text(c, getString(R.string.projects_afk_ui_phone_names), 13, R.color.projects_ash_300, false);
                label.setPadding(AfkUi.dp(c, 8), AfkUi.dp(c, 8), AfkUi.dp(c, 8), AfkUi.dp(c, 4));
                block.addView(label, CloudUi.matchWrap());
                HorizontalScrollView scroll = new HorizontalScrollView(c);
                scroll.setHorizontalScrollBarEnabled(false);
                scroll.setHorizontalFadingEdgeEnabled(true);
                scroll.setFadingEdgeLength(AfkUi.dp(c, 32));
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(AfkUi.dp(c, 6), 0, AfkUi.dp(c, 6), 0);
                for (final String name : names) {
                    Button chip = new Button(c, null, android.R.attr.borderlessButtonStyle);
                    chip.setText(name);
                    chip.setAllCaps(false);
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    chip.setTypeface(AfkUi.font(c, R.font.figtree_semibold));
                    chip.setTextColor(AfkUi.color(c, R.color.projects_bone_100));
                    chip.setBackground(ChamferDrawable.toggle(c));
                    chip.setPadding(AfkUi.dp(c, 14), 0, AfkUi.dp(c, 14), 0);
                    chip.setMinHeight(AfkUi.dp(c, 44));
                    chip.setMinimumHeight(AfkUi.dp(c, 44));
                    chip.setMinWidth(0);
                    chip.setMinimumWidth(0);
                    Press.attach(chip, Press.Style.BUTTON);
                    chip.setOnClickListener(v -> {
                        if (mName == null) return;
                        Haptics.tick(v);
                        mName.input.setText(name);
                        mName.input.setSelection(mName.input.length());
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (row.getChildCount() > 0) lp.setMarginStart(AfkUi.dp(c, 6));
                    row.addView(chip, lp);
                }
                scroll.addView(row);
                block.addView(scroll, CloudUi.matchWrap());
                LinearLayout target = mContent;
                if (target == null) return;
                target.addView(block, Math.min(at, target.getChildCount()), CloudUi.matchWrap());
                gg.projects.mobile.ui.CardMotion.fadeIn(block);
            });
        });
    }

    // ---- login command rows ----

    /** One login command: its label ("1. komut"), the line (a secret one masked), the eye while it is secret, remove. */
    private final class CommandRow {
        /** The row with the hairline over it (the first row's is hidden). */
        final LinearLayout wrap;
        final View divider;
        final LinearLayout view;
        final TextView label;
        final EditText input;
        final ImageButton eye;
        final Mask mask = new Mask();
        int index;

        CommandRow(Context c, final AfkDraft d, int at) {
            index = at;
            wrap = new LinearLayout(c);
            wrap.setOrientation(LinearLayout.VERTICAL);
            divider = CloudUi.textDivider(c);
            wrap.addView(divider);
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(AfkUi.dp(c, 14), AfkUi.dp(c, 10), AfkUi.dp(c, 6), AfkUi.dp(c, 8));
            wrap.addView(view, CloudUi.matchWrap());
            label = CloudUi.text(c, "", 13, R.color.projects_ash_300, true);
            view.addView(label, CloudUi.matchWrap());
            LinearLayout line = new LinearLayout(c);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            input = new EditText(c);
            input.setBackgroundResource(R.drawable.projects_input);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            input.setTextColor(AfkUi.color(c, R.color.projects_bone_100));
            input.setHintTextColor(AfkUi.color(c, R.color.projects_ash_400));
            input.setHint(R.string.projects_afk_ui_login_placeholder);
            input.setSingleLine(true);
            // No suggestions and no learning: a login command holds a password.
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            // After the input type: its password variation puts a monospace face on the field (the phone's fields are
            // Figtree, the cloud's secret rows too).
            input.setTypeface(AfkUi.font(c, R.font.figtree_regular));
            input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AfkLimits.MAX_LINE_CHARS)});
            input.setPadding(AfkUi.dp(c, 12), AfkUi.dp(c, 10), AfkUi.dp(c, 12), AfkUi.dp(c, 10));
            input.setMinimumHeight(AfkUi.dp(c, 48));
            input.setText(d.loginCommands.get(at));
            input.setTransformationMethod(mask);
            line.addView(input, CloudUi.weight(1f));
            eye = icon(c, R.drawable.projects_ic_team6_eye, R.string.projects_afk_ui_secret_show);
            eye.setOnClickListener(v -> {
                mask.hidden = !mask.hidden;
                Haptics.tick(v);
                input.setTransformationMethod(null);
                input.setTransformationMethod(mask);
                input.setSelection(input.length());
                eyeLook();
            });
            line.addView(eye, new LinearLayout.LayoutParams(AfkUi.dp(c, 44), AfkUi.dp(c, 44)));
            ImageButton remove = icon(c, R.drawable.projects_ic_x, R.string.projects_afk_ui_login_remove);
            remove.setOnClickListener(v -> {
                Haptics.tick(v);
                removeCommandRow(d, this);
                changedNow();
            });
            line.addView(remove, new LinearLayout.LayoutParams(AfkUi.dp(c, 44), AfkUi.dp(c, 44)));
            LinearLayout.LayoutParams lineLp = CloudUi.matchWrap();
            lineLp.topMargin = AfkUi.dp(c, 6);
            view.addView(line, lineLp);
            input.addTextChangedListener(new Watcher(s -> {
                if (index < d.loginCommands.size()) d.loginCommands.set(index, s);
                if (mCommandsError != null) mCommandsError.setVisibility(View.GONE);
                eyeLook();
                refreshSecretNote(d);
                changedNow();
            }));
            eyeLook();
        }

        void eyeLook() {
            boolean secret = AfkFormRules.secret(input.getText().toString());
            eye.setVisibility(secret ? View.VISIBLE : View.INVISIBLE);
            eye.setImageResource(mask.hidden ? R.drawable.projects_ic_team6_eye : R.drawable.projects_ic_team6_eye_slash);
            eye.setContentDescription(getString(mask.hidden ? R.string.projects_afk_ui_secret_show : R.string.projects_afk_ui_secret_hide));
        }

        void number(int at) {
            index = at;
            label.setText(getString(R.string.projects_afk_ui_login_line, at + 1));
            input.setContentDescription(label.getText());
        }
    }

    /**
     * A secret line's words after its command show as bullets while hidden (the desktop's one look for secrets). Like
     * Android's own password transformation, the shown text is a live view of the field's text, read character by
     * character ({@link AfkFormRules#shown}): a copy would fall behind the text as the player types and break drawing.
     */
    private static final class Mask implements TransformationMethod {
        boolean hidden = true;

        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            if (!hidden || source == null) return source;
            return new Masked(source);
        }

        @Override
        public void onFocusChanged(View view, CharSequence sourceText, boolean focused, int direction, Rect previouslyFocusedRect) {}
    }

    /** The field's text as a hidden line shows it, always as long as the text. */
    private static final class Masked implements CharSequence {
        private final CharSequence mSource;

        Masked(CharSequence source) {
            mSource = source;
        }

        @Override
        public int length() {
            return mSource.length();
        }

        @Override
        public char charAt(int index) {
            return AfkFormRules.shown(mSource, index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            StringBuilder out = new StringBuilder(Math.max(0, end - start));
            for (int i = start; i < end; i++) out.append(charAt(i));
            return out.toString();
        }

        @Override
        public String toString() {
            return subSequence(0, length()).toString();
        }
    }

    private final List<CommandRow> mCommandRows = new ArrayList<>();
    @Nullable
    private View mAddDivider;

    private CommandRow addCommandRow(Context c, AfkDraft d, int at) {
        CommandRow row = new CommandRow(c, d, at);
        row.divider.setVisibility(mCommandRows.isEmpty() ? View.GONE : View.VISIBLE);
        if (mCommands != null) mCommands.addView(row.wrap, CloudUi.matchWrap());
        mCommandRows.add(row);
        row.number(at);
        return row;
    }

    /** Takes one command out: its row goes (the rest glide up and are numbered again), the others keep what is typed. */
    private void removeCommandRow(AfkDraft d, CommandRow row) {
        int at = mCommandRows.indexOf(row);
        if (at < 0) return;
        if (mContent != null) gg.projects.mobile.ui.CardMotion.beginLayoutChange(mContent);
        if (at < d.loginCommands.size()) d.loginCommands.remove(at);
        mCommandRows.remove(at);
        if (mCommands != null) mCommands.removeView(row.wrap);
        for (int i = 0; i < mCommandRows.size(); i++) {
            CommandRow r = mCommandRows.get(i);
            r.number(i);
            r.divider.setVisibility(i == 0 ? View.GONE : View.VISIBLE);
        }
        refreshCommands(d);
    }

    private void refreshCommands(AfkDraft d) {
        boolean any = !d.loginCommands.isEmpty();
        if (mCommands != null) mCommands.setVisibility(any ? View.VISIBLE : View.GONE);
        // The hairline over "Komut ekle" only when commands are above it.
        if (mAddDivider != null) mAddDivider.setVisibility(any ? View.VISIBLE : View.GONE);
        if (mAddCommand != null) CloudUi.enable(mAddCommand, d.loginCommands.size() < AfkLimits.MAX_LOGIN_COMMANDS);
        refreshSecretNote(d);
    }

    private void refreshSecretNote(AfkDraft d) {
        if (mSecretNote == null) return;
        boolean secret = false;
        for (String l : d.loginCommands) secret |= AfkFormRules.secret(l);
        mSecretNote.setVisibility(secret ? View.VISIBLE : View.GONE);
    }

    private ImageButton icon(Context c, int icon, int description) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(AfkUi.color(c, R.color.projects_ash_300)));
        b.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = AfkUi.dp(c, 12);
        b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(getString(description));
        Press.attach(b, Press.Style.ICON);
        return b;
    }

    private void focusCommands(View title) {
        if (!alive()) return;
        if (mScroll != null) mScroll.smoothScrollTo(0, Math.max(0, title.getTop() - AfkUi.dp(ctx(), 8)));
        CommandRow target = null;
        for (CommandRow row : mCommandRows) {
            if (AfkFormRules.secret(row.input.getText().toString())) {
                target = row;
                break;
            }
        }
        if (target == null && !mCommandRows.isEmpty()) target = mCommandRows.get(0);
        if (target != null) {
            target.input.requestFocus();
            target.input.setSelection(target.input.length());
        }
    }

    private void showKeyboard(View view) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) ctx().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) view.post(() -> imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
    }

    // ---- the save bar ----

    private void buildBar(Context c) {
        if (mBottom == null) return;
        mBottom.removeAllViews();
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(AfkUi.color(c, R.color.projects_obsidian_900));
        bar.setPadding(AfkUi.dp(c, 16), AfkUi.dp(c, 10), AfkUi.dp(c, 16), AfkUi.dp(c, 12));
        View line = new View(c);
        line.setBackgroundColor(AfkUi.color(c, R.color.projects_obsidian_700));
        mBarWords = CloudUi.text(c, getString(R.string.projects_afk_ui_dirty), 13.5f, R.color.projects_ash_300, false);
        mBarWords.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar.addView(mBarWords, CloudUi.matchWrap());
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button discard = CloudUi.button(c, getString(R.string.projects_afk_ui_discard), false, v -> {
            if (adding()) {
                leaveNow();
                return;
            }
            // Back to what was saved.
            mKept.now = AfkFormRules.copy(mKept.start);
            build();
        });
        // A running account (the desktop's settings bar): "Yalnız kaydet" keeps the connection as it is, "Kaydet ve
        // yeniden bağlan" uses the new settings at once. On a phone the long one takes its own line under the two.
        String id = accountId();
        boolean live = id != null && mAfk.status(id).state.running();
        Button save;
        if (live) {
            mSaveOnly = CloudUi.button(c, getString(R.string.projects_afk_ui_save_only), false, v -> save(v, false));
            mSave = CloudUi.button(c, getString(R.string.projects_afk_ui_save_reconnect), true, v -> save(v, true));
            save = mSaveOnly;
        } else {
            mSaveOnly = null;
            mSave = CloudUi.button(c, getString(adding() ? R.string.projects_afk_ui_add : R.string.projects_afk_ui_save), true,
                    v -> save(v, false));
            save = mSave;
        }
        LinearLayout.LayoutParams discardLp = CloudUi.weight(1f);
        LinearLayout.LayoutParams saveLp = CloudUi.weight(1f);
        saveLp.setMarginStart(AfkUi.dp(c, 8));
        buttons.addView(discard, discardLp);
        buttons.addView(save, saveLp);
        LinearLayout.LayoutParams buttonsLp = CloudUi.matchWrap();
        buttonsLp.topMargin = AfkUi.dp(c, 8);
        bar.addView(buttons, buttonsLp);
        if (live) {
            LinearLayout.LayoutParams againLp = CloudUi.matchWrap();
            againLp.topMargin = AfkUi.dp(c, 8);
            bar.addView(mSave, againLp);
        }
        LinearLayout holder = new LinearLayout(c);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, AfkUi.dp(c, 1))));
        holder.addView(bar, CloudUi.matchWrap());
        mBottom.addView(holder);
        mBar = holder;
        mBarShown = false;
        mBottom.setVisibility(View.GONE);
    }

    /** The draft changed: the bar comes (adding: always, its words only while something is typed) and back asks first. */
    private void changedNow() {
        boolean dirty = changed();
        holdBack(dirty);
        boolean show = adding() || dirty;
        if (mBarWords != null) mBarWords.setVisibility(adding() ? View.GONE : View.VISIBLE);
        showBar(show);
        if (mSave != null) mSave.setEnabled(!mSaving);
        if (mSaveOnly != null) mSaveOnly.setEnabled(!mSaving);
    }

    /** The bar slides up from under the screen and back (transform only; with animations removed it simply shows). */
    private void showBar(boolean show) {
        final View bottom = mBottom;
        final View bar = mBar;
        if (bottom == null || bar == null || show == mBarShown) return;
        mBarShown = show;
        bar.animate().cancel();
        if (show) {
            bottom.setVisibility(View.VISIBLE);
            if (!Motion.enabled(bar.getContext())) {
                bar.setTranslationY(0f);
                return;
            }
            bar.post(() -> {
                bar.setTranslationY(bar.getHeight());
                bar.animate().translationY(0f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
            });
        } else {
            if (!Motion.enabled(bar.getContext())) {
                bottom.setVisibility(View.GONE);
                return;
            }
            bar.animate().translationY(bar.getHeight()).setDuration(Motion.EXIT_MS).setInterpolator(Motion.EASE_IN)
                    .withEndAction(() -> {
                        if (!mBarShown) bottom.setVisibility(View.GONE);
                    }).start();
        }
    }

    // ---- saving ----

    /** Saves the draft; {@code reconnect}: a running account then connects again at once with it ("Kaydet ve yeniden bağlan"). */
    private void save(final View from, final boolean reconnect) {
        if (mSaving || mKept.now == null) return;
        final AfkDraft d = AfkFormRules.copy(mKept.now);
        d.address = AfkFormRules.address(d.address);
        d.loginCommands = AfkFormRules.commands(d.loginCommands);
        final String id = accountId();
        List<AfkProblem> problems = mAfk.check(d, id);
        if (!problems.isEmpty()) {
            Haptics.reject(from);
            show(problems);
            return;
        }
        mSaving = true;
        changedNow();
        final Kept kept = mKept;
        kept.saving = true;
        kept.savedName = null;
        kept.refused = null;
        kept.gone = false;
        kept.reconnected = null;
        final gg.projects.mobile.afk.api.PhoneAfk afk = mAfk;
        IO.execute(() -> {
            AfkAccount saved = null;
            List<AfkProblem> failed = null;
            boolean gone = false;
            try {
                if (id == null) saved = afk.add(d);
                else {
                    saved = afk.update(id, d);
                    gone = saved == null;
                }
            } catch (AfkDraftException e) {
                failed = e.problems;
            }
            final AfkAccount account = saved;
            final List<AfkProblem> refused = failed;
            final boolean accountGone = gone || (refused == null && account == null);
            mMain.post(() -> {
                // The outcome stays with the draft: a screen made again meanwhile (a rotation) takes it.
                kept.saving = false;
                kept.refused = refused;
                kept.gone = accountGone;
                kept.savedName = refused == null && !accountGone ? account.name : null;
                // Only an account still running reconnects: one the player stopped meanwhile stays stopped.
                if (reconnect && kept.savedName != null && afk.status(id).state.running()) kept.reconnected = afk.reconnect(id);
                AfkEditFragment owner = kept.owner;
                if (owner != null && owner.alive() && owner.isResumed()) owner.saveDone();
            });
        });
    }

    /**
     * What the save answered: problems under their fields, or "{ad} eklendi" / "{ad} ayarları kaydedildi" / "{ad} yeniden
     * bağlanıyor" and back.
     */
    private void saveDone() {
        Kept kept = mKept;
        mSaving = false;
        View from = mSave;
        if (kept.refused != null) {
            List<AfkProblem> refused = kept.refused;
            kept.refused = null;
            changedNow();
            Haptics.reject(from);
            show(refused);
            return;
        }
        if (kept.gone) {
            kept.gone = false;
            holdBack(false);
            leaveNow();
            return;
        }
        String name = kept.savedName;
        if (name == null) return;
        kept.savedName = null;
        AfkStartResult again = kept.reconnected;
        kept.reconnected = null;
        Haptics.confirm(from);
        if (again == null) toast(getString(adding() ? R.string.projects_afk_ui_added : R.string.projects_afk_ui_saved, name));
        else toast(again.ok() ? getString(R.string.projects_afk_ui_reconnecting, name) : AfkTexts.start(ctx(), again));
        kept.start = kept.now == null ? null : AfkFormRules.copy(kept.now);
        holdBack(false);
        leaveNow();
    }

    /** Each problem under its field ("Önce işaretli alanları düzelt." when the field is off screen), the first one in view. */
    private void show(List<AfkProblem> problems) {
        Context c = ctx();
        View first = null;
        StringBuilder none = new StringBuilder();
        for (AfkProblem p : problems) {
            String words = AfkTexts.problem(c, p);
            switch (p.field) {
                case NAME:
                    if (mName != null) {
                        mName.setError(words);
                        if (first == null) first = mName.view;
                    }
                    break;
                case ADDRESS:
                    if (mAddress != null) {
                        mAddress.setError(words);
                        if (first == null) first = mAddress.view;
                    }
                    break;
                case LOGIN_COMMANDS:
                    if (mCommandsError != null) {
                        mCommandsError.setText(words);
                        mCommandsError.setVisibility(View.VISIBLE);
                        if (first == null) first = mCommandsError;
                    }
                    break;
                default:
                    if (none.length() > 0) none.append(' ');
                    none.append(words);
                    break;
            }
        }
        if (mNoneError != null) {
            mNoneError.setText(none);
            mNoneError.setVisibility(none.length() == 0 ? View.GONE : View.VISIBLE);
            if (none.length() > 0 && first == null) first = mNoneError;
        }
        final View target = first;
        if (target != null && mScroll != null) {
            mScroll.post(() -> {
                int[] at = new int[2];
                int[] scrollAt = new int[2];
                target.getLocationInWindow(at);
                mScroll.getLocationInWindow(scrollAt);
                int y = mScroll.getScrollY() + at[1] - scrollAt[1] - AfkUi.dp(c, 16);
                mScroll.smoothScrollTo(0, Math.max(0, y));
            });
        }
    }

    private void confirmRemove() {
        final String id = accountId();
        final AfkAccount account = id == null ? null : mAfk.account(id);
        if (account == null) return;
        ConfirmDialog.show(requireActivity(), getString(R.string.projects_afk_ui_remove_title, account.name),
                getString(R.string.projects_afk_ui_remove_body), getString(R.string.projects_afk_ui_remove_confirm), true, () -> {
                    if (!alive()) return;
                    // The engine stops a running account itself.
                    mAfk.remove(account.id);
                    AfkSeen.seen(account.id);
                    toast(getString(R.string.projects_afk_ui_removed, account.name));
                    holdBack(false);
                    leaveNow();
                });
    }

    /** A text watcher that hands on the new text. */
    private static final class Watcher implements TextWatcher {
        interface Changed {
            void changed(String text);
        }

        private final Changed mChanged;

        Watcher(Changed changed) {
            mChanged = changed;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            mChanged.changed(s.toString());
        }
    }
}
