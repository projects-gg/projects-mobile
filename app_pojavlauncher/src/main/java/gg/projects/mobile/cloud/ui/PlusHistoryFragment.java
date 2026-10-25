package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusFormat;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.Haptics;

/**
 * "Ayar geçmişi": the AFK+ settings as they were saved, newest first, each with what changed in a few words, and
 * "Buna dön" to put an earlier one back (later ones stay in the history).
 */
public final class PlusHistoryFragment extends CloudScreen {
    public static final String TAG = "ProjectsPlusHistoryFragment";
    private static final int MAX_LINES = 4;

    private final List<CloudModel.HistoryEntry> mEntries = new ArrayList<>();
    private boolean mLoaded;
    private boolean mFailed;
    private boolean mRestoring;

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_plus_history));
    }

    @Override
    protected void started() {
        load();
    }

    private void load() {
        CloudModel.Account a = account();
        if (a == null) return;
        mService.history(a.id, new CloudService.Result<List<CloudModel.HistoryEntry>>() {
            @Override
            public void ok(List<CloudModel.HistoryEntry> value) {
                mEntries.clear();
                mEntries.addAll(value);
                mLoaded = true;
                mFailed = false;
                if (alive()) draw();
            }

            @Override
            public void failed(CloudProblem problem) {
                mFailed = !mLoaded;
                if (alive()) draw();
            }
        });
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a != null) mHeader.setCaption(a.name);
        if (mContent != null && mContent.getChildCount() == 0) draw();
    }

    private void draw() {
        LinearLayout content = clear();
        if (content == null) return;
        Context c = ctx();
        if (!mLoaded) {
            if (mFailed) {
                content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_cloud_error_unavailable)));
                content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_retry), v -> load()),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                content.addView(CloudUi.loading(c));
            }
            return;
        }
        if (mEntries.isEmpty()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.LOGS, getString(R.string.projects_plus_history_empty)));
            return;
        }
        long now = mService.nowSeconds();
        content.addView(CloudUi.gap(c, 6));
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < mEntries.size(); i++) rows.add(entry(c, i, now));
        CloudUi.fill(group, rows);
        content.addView(group);
    }

    private View entry(final Context c, final int index, long now) {
        final CloudModel.HistoryEntry e = mEntries.get(index);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 12), CloudUi.dp(c, 12), CloudUi.dp(c, 6));
        String when = CloudWords.past(c, e.at, now, true);
        if ("restore".equals(e.by)) when = when + ", " + getString(R.string.projects_plus_history_by_restore).toLowerCase(CloudWords.locale(c));
        TextView title = CloudUi.text(c, when, 15, R.color.projects_bone_100, true);
        box.addView(title);
        List<String> lines = new ArrayList<>();
        if (index + 1 < mEntries.size()) {
            for (PlusFormat.Change change : PlusFormat.diff(mEntries.get(index + 1).config, e.config)) {
                lines.add(CloudWords.change(c, change, mService));
                if (lines.size() == MAX_LINES) break;
            }
        }
        if (lines.isEmpty()) lines.add(getString(R.string.projects_plus_change_other));
        for (String line : lines) {
            TextView t = CloudUi.text(c, line, 13.5f, R.color.projects_ash_300, false);
            t.setPadding(0, CloudUi.dp(c, 3), 0, 0);
            box.addView(t);
        }
        if (index > 0) {
            android.widget.Button back = CloudUi.textButton(c, getString(R.string.projects_plus_history_restore), v -> askRestore(v, e));
            back.setEnabled(!mRestoring);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(-CloudUi.dp(c, 12));
            box.addView(back, lp);
        } else {
            box.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 12), CloudUi.dp(c, 12), CloudUi.dp(c, 12));
        }
        return box;
    }

    private void askRestore(final View from, final CloudModel.HistoryEntry e) {
        SocialUi.confirm(requireActivity(), getString(R.string.projects_plus_history_restore), getString(R.string.projects_plus_history_restore_body),
                getString(R.string.projects_plus_history_restore), () -> restore(from, e));
    }

    private void restore(final View from, CloudModel.HistoryEntry e) {
        CloudModel.Account a = account();
        if (a == null || mEntries.isEmpty() || mRestoring) return;
        mRestoring = true;
        long current = mEntries.get(0).aiRev;
        mService.restore(a.id, e.aiRev, current, new CloudService.Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc value) {
                mRestoring = false;
                if (!alive()) return;
                Haptics.confirm(from);
                toast(getString(R.string.projects_plus_history_restored));
                load();
            }

            @Override
            public void failed(CloudProblem problem) {
                mRestoring = false;
                if (!alive()) return;
                problem(problem, from);
                load();
            }
        });
    }
}
