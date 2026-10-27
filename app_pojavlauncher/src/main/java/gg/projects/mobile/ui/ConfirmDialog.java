package gg.projects.mobile.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

/**
 * QA-TASTE (28 Oct): the app's own question, as the launcher's dialog: a Tektur title with a close mark, one sentence,
 * a ghost "Vazgeç" and the one action chamfered: gold, or the danger look for what is hard to take back. Gold is never on
 * "Vazgeç" (Android's text buttons painted both in the accent).
 */
public final class ConfirmDialog {
    private ConfirmDialog() {}

    public static AlertDialog show(Activity activity, @Nullable CharSequence title, @Nullable CharSequence message,
                                   CharSequence action, boolean danger, final Runnable onAction) {
        Context c = activity;
        final AlertDialog[] dialog = new AlertDialog[1];
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(c, 20), dp(c, 14), dp(c, 12), dp(c, 16));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setTypeface(ResourcesCompat.getFont(c, R.font.tektur_semibold));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        name.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
        if (title != null) name.setText(title);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton close = new ImageButton(c);
        close.setImageResource(R.drawable.projects_ic_x);
        close.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.projects_ash_300)));
        close.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        close.setContentDescription(c.getString(R.string.projects_close));
        close.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        close.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        Press.attach(close, Press.Style.ICON);
        close.setOnClickListener(v -> dialog[0].dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        root.addView(head);

        if (!TextUtils.isEmpty(message)) {
            TextView words = new TextView(c);
            words.setText(message);
            words.setTypeface(ResourcesCompat.getFont(c, R.font.figtree_regular));
            words.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            words.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
            words.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wordsLp.topMargin = dp(c, 6);
            wordsLp.setMarginEnd(dp(c, 8));
            root.addView(words, wordsLp);
        }

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = new Button(c, null, android.R.attr.borderlessButtonStyle);
        cancel.setText(R.string.projects_social_cancel);
        cancel.setAllCaps(false);
        cancel.setTypeface(ResourcesCompat.getFont(c, R.font.figtree_semibold));
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancel.setTextColor(ContextCompat.getColor(c, R.color.projects_ash_300));
        cancel.setBackgroundResource(R.drawable.projects_row_ripple);
        cancel.setMinHeight(dp(c, 44));
        cancel.setMinimumHeight(dp(c, 44));
        cancel.setMinWidth(0);
        cancel.setMinimumWidth(0);
        cancel.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        Press.attach(cancel, Press.Style.BUTTON);
        cancel.setOnClickListener(v -> dialog[0].dismiss());
        buttons.addView(cancel);
        Button go = new Button(c, null, android.R.attr.borderlessButtonStyle);
        go.setText(action);
        go.setAllCaps(false);
        go.setBackground(danger ? ChamferDrawable.danger(c) : ChamferDrawable.primary(c));
        go.setTypeface(ResourcesCompat.getFont(c, danger ? R.font.figtree_semibold : R.font.tektur_semibold));
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        go.setTextColor(ContextCompat.getColor(c, danger ? R.color.projects_flare_400 : R.color.projects_on_gold));
        go.setMinHeight(dp(c, 44));
        go.setMinimumHeight(dp(c, 44));
        go.setMinWidth(dp(c, 96));
        go.setMinimumWidth(dp(c, 96));
        Press.attach(go, Press.Style.BUTTON);
        go.setOnClickListener(v -> {
            dialog[0].dismiss();
            onAction.run();
        });
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        goLp.setMarginStart(dp(c, 8));
        goLp.setMarginEnd(dp(c, 8));
        buttons.addView(go, goLp);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.topMargin = dp(c, 18);
        root.addView(buttons, buttonsLp);

        dialog[0] = new AlertDialog.Builder(activity).setView(root).create();
        dialog[0].show();
        return dialog[0];
    }

    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
