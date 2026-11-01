package gg.projects.mobile.afk.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcAccountSpinner;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.ConfirmDialog;
import gg.projects.mobile.ui.Press;

/**
 * OYNA while AFK runs on the phone (phone-afk.md §3, the desktop's {@code afkGuard}; rules in {@link AfkPlayRules}): the
 * chosen account's name AFK asks "{ad} şu an AFK'da" with "AFK'yı durdur ve oyna" (it starts again after the game),
 * "AFK açık kalsın" and "Vazgeç"; another name plays without a question. While an account stays AFK, a phone short of
 * free memory for the game's heap asks "Yine de oyna" first (PLAY-AGAIN's check). The questions are the app's own
 * dialog ({@link ConfirmDialog}'s look).
 */
public final class AfkPlayGate {
    private AfkPlayGate() {}

    /** Runs {@code play} now, or after the player's answers ({@code pressed}: the button that asked, kept for its press). */
    public static void check(Fragment fragment, @Nullable View pressed, Runnable play) {
        final Activity activity = fragment.getActivity();
        if (activity == null || !fragment.isAdded()) return;
        if (!AfkUi.available(activity)) {
            play.run();
            return;
        }
        final PhoneAfk afk = PhoneAfk.get(activity);
        int running = afk.runningCount();
        if (running == 0) {
            play.run();
            return;
        }
        String name = selectedName(activity);
        final AfkStatus same = name == null ? null : afk.afkByName(name);
        final int others = running - (same != null ? 1 : 0);
        final boolean fits = AfkPlayRules.memoryFits(Tools.getFreeDeviceMemory(activity), LauncherPreferences.PREF_RAM_ALLOCATION);
        switch (AfkPlayRules.first(same != null, others, fits)) {
            case SAME_NAME:
                askSameName(activity, fragment, afk, same, others, fits, play);
                break;
            case MEMORY:
                askMemory(activity, fragment, play);
                break;
            default:
                play.run();
                break;
        }
    }

    /** The name OYNA will play: the chip's account (a swipe on its way lands first), else the saved choice. */
    @Nullable
    private static String selectedName(Activity activity) {
        View spinner = activity.findViewById(R.id.account_spinner);
        if (spinner instanceof mcAccountSpinner) {
            MinecraftAccount account = ((mcAccountSpinner) spinner).getSelectedAccount();
            return account == null ? null : account.username;
        }
        String name = PojavProfile.getCurrentProfileName(activity);
        return TextUtils.isEmpty(name) ? null : name;
    }

    private static void askSameName(final Activity activity, final Fragment fragment, final PhoneAfk afk, final AfkStatus same,
                                    final int others, final boolean fits, final Runnable play) {
        question(activity, activity.getString(R.string.projects_afk_ui_guard_title, same.name),
                activity.getString(R.string.projects_afk_ui_guard_body),
                activity.getString(R.string.projects_afk_ui_guard_stop), () -> {
                    if (!fragment.isAdded()) return;
                    // The account stops only when the game goes ahead (a "Vazgeç" on the memory question keeps it AFK).
                    Runnable stopAndPlay = () -> {
                        afk.stopForGame(same.accountId);
                        play.run();
                    };
                    next(activity, fragment, AfkPlayRules.afterSameName(false, others, fits), stopAndPlay);
                },
                activity.getString(R.string.projects_afk_ui_guard_keep), () -> {
                    if (!fragment.isAdded()) return;
                    next(activity, fragment, AfkPlayRules.afterSameName(true, others, fits), play);
                });
    }

    private static void next(Activity activity, Fragment fragment, AfkPlayRules.Step step, Runnable play) {
        if (step == AfkPlayRules.Step.MEMORY) askMemory(activity, fragment, play);
        else play.run();
    }

    private static void askMemory(Activity activity, final Fragment fragment, final Runnable play) {
        ConfirmDialog.show(activity, activity.getString(R.string.projects_afk_ui_memory_title),
                activity.getString(R.string.projects_afk_ui_memory_body),
                activity.getString(R.string.projects_afk_ui_memory_play), false, () -> {
                    if (fragment.isAdded()) play.run();
                });
    }

    /**
     * A question with two choices and "Vazgeç": ConfirmDialog's Tektur title and close mark, one sentence, then the
     * choices one under the other (two long Turkish labels never fit side by side at 360 dp): the gold one, the outlined
     * one, and the ghost "Vazgeç".
     */
    static AlertDialog question(Activity activity, CharSequence title, CharSequence message, CharSequence first,
                                final Runnable onFirst, CharSequence second, final Runnable onSecond) {
        Context c = activity;
        final AlertDialog[] dialog = new AlertDialog[1];
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AfkUi.dp(c, 20), AfkUi.dp(c, 14), AfkUi.dp(c, 12), AfkUi.dp(c, 12));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setTypeface(AfkUi.font(c, R.font.tektur_semibold));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        name.setTextColor(AfkUi.color(c, R.color.projects_bone_100));
        name.setText(title);
        androidx.core.view.ViewCompat.setAccessibilityHeading(name, true);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton close = new ImageButton(c);
        close.setImageResource(R.drawable.projects_ic_x);
        close.setImageTintList(ColorStateList.valueOf(AfkUi.color(c, R.color.projects_ash_300)));
        close.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        close.setContentDescription(c.getString(R.string.projects_close));
        int pad = AfkUi.dp(c, 10);
        close.setPadding(pad, pad, pad, pad);
        close.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        Press.attach(close, Press.Style.ICON);
        close.setOnClickListener(v -> dialog[0].dismiss());
        head.addView(close, new LinearLayout.LayoutParams(AfkUi.dp(c, 40), AfkUi.dp(c, 40)));
        root.addView(head);

        TextView words = new TextView(c);
        words.setText(message);
        words.setTypeface(AfkUi.font(c, R.font.figtree_regular));
        words.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        words.setTextColor(AfkUi.color(c, R.color.projects_bone_100));
        words.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wordsLp.topMargin = AfkUi.dp(c, 6);
        wordsLp.setMarginEnd(AfkUi.dp(c, 8));
        root.addView(words, wordsLp);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.addView(choice(c, first, true, v -> {
            dialog[0].dismiss();
            onFirst.run();
        }), choiceLp(c, 18));
        buttons.addView(choice(c, second, false, v -> {
            dialog[0].dismiss();
            onSecond.run();
        }), choiceLp(c, 8));
        Button cancel = new Button(c, null, android.R.attr.borderlessButtonStyle);
        cancel.setText(R.string.projects_afk_ui_cancel);
        cancel.setAllCaps(false);
        cancel.setTypeface(AfkUi.font(c, R.font.figtree_semibold));
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancel.setTextColor(AfkUi.color(c, R.color.projects_ash_300));
        cancel.setBackgroundResource(R.drawable.projects_row_ripple);
        cancel.setMinHeight(AfkUi.dp(c, 44));
        cancel.setMinimumHeight(AfkUi.dp(c, 44));
        Press.attach(cancel, Press.Style.BUTTON);
        cancel.setOnClickListener(v -> dialog[0].dismiss());
        buttons.addView(cancel, choiceLp(c, 4));
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.setMarginEnd(AfkUi.dp(c, 8));
        root.addView(buttons, buttonsLp);

        dialog[0] = new AlertDialog.Builder(activity).setView(root).create();
        dialog[0].show();
        return dialog[0];
    }

    private static Button choice(Context c, CharSequence label, boolean primary, View.OnClickListener click) {
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setBackground(primary ? ChamferDrawable.primary(c) : ChamferDrawable.outlined(c));
        b.setTypeface(AfkUi.font(c, R.font.figtree_semibold));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(AfkUi.color(c, primary ? R.color.projects_on_gold : R.color.projects_bone_100));
        b.setMinHeight(AfkUi.dp(c, 48));
        b.setMinimumHeight(AfkUi.dp(c, 48));
        b.setLetterSpacing(0f);
        Press.attach(b, Press.Style.BUTTON);
        b.setOnClickListener(click);
        return b;
    }

    private static LinearLayout.LayoutParams choiceLp(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AfkUi.dp(c, topDp);
        return lp;
    }
}
