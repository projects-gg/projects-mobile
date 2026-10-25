package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * The bar under an editor while it has unsaved changes: one line (what saving does), "Vazgeç" and "Kaydet". It slides
 * up when the first change is made and down when the changes are saved or undone (transform only; reduced motion
 * shows and hides it).
 */
final class SaveBar {
    private final FrameLayout mHost;
    private final TextView mText;
    private final Button mSave;
    private final Button mCancel;
    private boolean mShown;

    SaveBar(Context c, FrameLayout host, final Runnable onCancel, final Runnable onSave) {
        mHost = host;
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_900));
        View hairline = new View(c);
        hairline.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_700));
        bar.addView(hairline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, CloudUi.dp(c, 1))));
        mText = CloudUi.text(c, "", 13.5f, R.color.projects_ash_300, false);
        mText.setPadding(CloudUi.dp(c, 20), CloudUi.dp(c, 10), CloudUi.dp(c, 20), 0);
        mText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar.addView(mText);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 8), CloudUi.dp(c, 16), CloudUi.dp(c, 12));
        mCancel = CloudUi.button(c, c.getString(R.string.projects_cloud_settings_cancel), false, v -> onCancel.run());
        buttons.addView(mCancel, CloudUi.weight(1f));
        mSave = CloudUi.button(c, c.getString(R.string.projects_cloud_settings_save), true, v -> onSave.run());
        LinearLayout.LayoutParams saveLp = CloudUi.weight(1f);
        saveLp.setMarginStart(CloudUi.dp(c, 10));
        buttons.addView(mSave, saveLp);
        bar.addView(buttons);
        host.addView(bar);
    }

    View saveButton() {
        return mSave;
    }

    void set(boolean show, boolean saving, CharSequence words) {
        Context c = mHost.getContext();
        mSave.setEnabled(!saving);
        mCancel.setEnabled(!saving);
        mSave.setText(c.getString(saving ? R.string.projects_cloud_settings_saving : R.string.projects_cloud_settings_save));
        mText.setText(saving ? c.getString(R.string.projects_cloud_settings_saving) : words);
        if (show == mShown) return;
        mShown = show;
        final View bottom = mHost;
        bottom.animate().cancel();
        if (show) {
            bottom.setVisibility(View.VISIBLE);
            if (Motion.enabled(c)) {
                bottom.post(() -> {
                    bottom.setTranslationY(bottom.getHeight());
                    bottom.animate().translationY(0f).setDuration(220).setInterpolator(Motion.EASE_OUT).start();
                });
            }
        } else if (Motion.enabled(c) && bottom.getHeight() > 0) {
            bottom.animate().translationY(bottom.getHeight()).setDuration(Motion.EXIT_MS).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> {
                        bottom.setVisibility(View.GONE);
                        bottom.setTranslationY(0f);
                    }).start();
        } else {
            bottom.setVisibility(View.GONE);
        }
    }
}
