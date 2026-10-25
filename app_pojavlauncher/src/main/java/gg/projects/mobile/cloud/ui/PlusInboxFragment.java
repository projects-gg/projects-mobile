package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.CloudApi;
import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;

/**
 * "Mesajlar": the players who wrote to the account in game, newest first, with the last line and how many are new.
 * A conversation opens in {@link PlusThreadFragment}. The names are the counterparts' in-game names; no avatar is
 * fetched from anywhere.
 */
public final class PlusInboxFragment extends CloudScreen {
    public static final String TAG = "ProjectsPlusInboxFragment";
    private static final long REFRESH_MS = 20_000;

    private final List<CloudModel.InboxThread> mThreads = new ArrayList<>();
    private boolean mLoaded;
    private boolean mFailed;
    private String mShape = "";
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            load();
            View view = getView();
            if (view != null) view.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_plus_messages));
    }

    @Override
    public void onResume() {
        mShape = "";
        super.onResume();
        View view = getView();
        if (view != null) view.postDelayed(mRefresh, REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        View view = getView();
        if (view != null) view.removeCallbacks(mRefresh);
    }

    @Override
    protected void started() {
        load();
    }

    private void load() {
        CloudModel.Account a = account();
        if (a == null) return;
        mService.inbox(a.id, new CloudService.Result<CloudApi.InboxList>() {
            @Override
            public void ok(CloudApi.InboxList value) {
                mThreads.clear();
                mThreads.addAll(value.threads);
                mLoaded = true;
                mFailed = false;
                if (alive()) render();
            }

            @Override
            public void failed(CloudProblem problem) {
                mFailed = !mLoaded;
                if (alive()) render();
            }
        });
    }

    @Override
    protected void render() {
        final CloudModel.Account a = account();
        if (a == null) return;
        mHeader.setCaption(a.name);
        StringBuilder key = new StringBuilder().append(mLoaded).append(mFailed).append('|');
        for (CloudModel.InboxThread t : mThreads) key.append(t.name).append(t.lastAt).append(t.unread).append(t.state).append(',');
        if (key.toString().equals(mShape)) return;
        mShape = key.toString();
        LinearLayout content = clear();
        if (content == null) return;
        Context c = ctx();
        if (!mLoaded) {
            if (mFailed) {
                content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_cloud_error_host_offline)));
                content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_retry), v -> load()),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                content.addView(CloudUi.loading(c));
            }
            return;
        }
        if (mThreads.isEmpty()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.CHAT, getString(R.string.projects_plus_inbox_empty)));
            return;
        }
        content.addView(CloudUi.gap(c, 8));
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        long now = mService.nowSeconds();
        for (final CloudModel.InboxThread t : mThreads) {
            CloudUi.Row row = CloudUi.row(c, R.drawable.projects_ic_user_circle, t.name, v -> {
                Bundle args = args(a.id);
                args.putString(PlusThreadFragment.ARG_NAME, t.name);
                open(PlusThreadFragment.class, args);
            });
            String line;
            if ("blocked".equals(t.state)) line = getString(R.string.projects_plus_inbox_blocked);
            else if ("you".equals(t.lastFrom)) line = getString(R.string.projects_plus_inbox_you) + ": " + t.lastText;
            else if ("bot".equals(t.lastFrom)) line = getString(R.string.projects_plus_inbox_assistant) + ": " + t.lastText;
            else line = t.lastText;
            row.setLine(line, t.unread > 0 ? R.color.projects_bone_100 : R.color.projects_ash_400);
            row.line.setSingleLine(true);
            row.line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (t.unread > 0) {
                row.setCount(t.unread);
            } else {
                row.count.setText(CloudWords.past(c, t.lastAt, now, false));
                row.count.setTextColor(CloudUi.color(c, R.color.projects_ash_400));
                row.count.setTextSize(12.5f);
                row.count.setVisibility(View.VISIBLE);
            }
            rows.add(row.view);
        }
        CloudUi.fill(group, rows);
        content.addView(group);
    }
}
