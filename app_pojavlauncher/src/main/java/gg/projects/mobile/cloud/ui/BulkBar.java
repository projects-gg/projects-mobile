package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * The list's bar while accounts are chosen (CAFK-FEAT §3.4): how many, "Hepsini seç" and "Tamam" on its first line,
 * then the four actions two by two (Başlat, Durdur, Yeniden bağlan, Ayarları kopyala). It slides up from the bottom
 * like the settings' save bar (transform only) and goes the same way.
 */
final class BulkBar {
    interface Actions {
        void start();

        void stop();

        void restart();

        void copy();

        void selectAll();

        void done();
    }

    private final FrameLayout mHost;
    private final TextView mCount;
    private final Button[] mButtons;
    private boolean mShown;

    BulkBar(Context c, FrameLayout host, final Actions actions) {
        mHost = host;
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_900));
        View hairline = new View(c);
        hairline.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_700));
        bar.addView(hairline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, CloudUi.dp(c, 1))));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(CloudUi.dp(c, 20), CloudUi.dp(c, 4), CloudUi.dp(c, 8), 0);
        mCount = CloudUi.text(c, "", 15, R.color.projects_bone_100, true);
        mCount.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        head.addView(mCount, CloudUi.weight(1f));
        head.addView(CloudUi.textButton(c, c.getString(R.string.projects_feat_bulk_select_all), v -> actions.selectAll()));
        head.addView(CloudUi.textButton(c, c.getString(R.string.projects_feat_bulk_cancel), v -> actions.done()));
        bar.addView(head, CloudUi.matchWrap());
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 4), CloudUi.dp(c, 16), CloudUi.dp(c, 12));
        Button start = CloudUi.button(c, c.getString(R.string.projects_cloud_start), false, v -> actions.start());
        Button stop = CloudUi.button(c, c.getString(R.string.projects_cloud_stop), false, v -> actions.stop());
        Button restart = CloudUi.button(c, c.getString(R.string.projects_cloud_reconnect), false, v -> actions.restart());
        Button copy = CloudUi.button(c, c.getString(R.string.projects_feat_bulk_copy), false, v -> actions.copy());
        mButtons = new Button[]{start, stop, restart, copy};
        grid.addView(pair(c, start, stop), CloudUi.matchWrap());
        LinearLayout.LayoutParams second = CloudUi.matchWrap();
        second.topMargin = CloudUi.dp(c, 10);
        grid.addView(pair(c, restart, copy), second);
        bar.addView(grid, CloudUi.matchWrap());
        host.addView(bar);
    }

    private static LinearLayout pair(Context c, Button a, Button b) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(a, CloudUi.weight(1f));
        LinearLayout.LayoutParams lp = CloudUi.weight(1f);
        lp.setMarginStart(CloudUi.dp(c, 10));
        row.addView(b, lp);
        return row;
    }

    /**
     * Shows the bar with the chosen count (or hides it); {@code busy} while an action runs. {@code can}: start, stop,
     * reconnect, copy, each true while at least one chosen account can take it (a start for accounts all in game waits).
     */
    void set(boolean show, int chosen, boolean busy, boolean[] can) {
        Context c = mHost.getContext();
        mCount.setText(c.getResources().getQuantityString(R.plurals.projects_feat_bulk_selected, chosen, chosen));
        for (int i = 0; i < mButtons.length; i++) {
            Button b = mButtons[i];
            b.setEnabled(!busy && chosen > 0 && (can == null || can[i]));
            b.setAlpha(b.isEnabled() ? 1f : 0.5f);
        }
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

    View anchor() {
        return mButtons[0];
    }
}
