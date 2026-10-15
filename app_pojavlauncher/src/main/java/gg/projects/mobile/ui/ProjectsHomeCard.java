package gg.projects.mobile.ui;

import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.lang.ref.WeakReference;
import java.util.List;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.LauncherConfig;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.join.ProjectsJoin;

/**
 * The Projects card at the top of the main menu: the latest news from the signed content index
 * (cached copy first, then a refresh) and the "Projects'e katıl" action.
 */
public final class ProjectsHomeCard implements ProjectsContent.Listener {
    private final Fragment mFragment;
    private final mcVersionSpinner mVersionSpinner;
    private final View mPlayButton;

    private final TextView mStatus;
    private final View mMaintenance;
    private final TextView mMaintenanceTitle;
    private final TextView mMaintenanceMessage;
    private final View mSkeleton;
    private final View mNews;
    private final ImageView mNewsCover;
    private final TextView mNewsCategory;
    private final TextView mNewsDate;
    private final TextView mNewsTitle;
    private final TextView mNewsSummary;
    private final View mMessage;
    private final TextView mMessageTitle;
    private final TextView mMessageBody;
    private final Button mJoinButton;
    private final Button mSecondaryButton;

    private Snapshot mSnapshot;

    /** Keeps the join button in its "preparing" state until the running join finishes. */
    private final Runnable mBusyWatcher = new Runnable() {
        @Override
        public void run() {
            if (ProjectsJoin.isBusy()) mJoinButton.postDelayed(this, 400);
            else setJoining(false);
        }
    };

    public ProjectsHomeCard(Fragment fragment, View root, mcVersionSpinner versionSpinner, View playButton) {
        mFragment = fragment;
        mVersionSpinner = versionSpinner;
        mPlayButton = playButton;
        mStatus = root.findViewById(R.id.projects_status);
        mMaintenance = root.findViewById(R.id.projects_maintenance);
        mMaintenanceTitle = root.findViewById(R.id.projects_maintenance_title);
        mMaintenanceMessage = root.findViewById(R.id.projects_maintenance_message);
        mSkeleton = root.findViewById(R.id.projects_skeleton);
        mNews = root.findViewById(R.id.projects_news);
        mNewsCover = root.findViewById(R.id.projects_news_cover);
        mNewsCategory = root.findViewById(R.id.projects_news_category);
        mNewsDate = root.findViewById(R.id.projects_news_date);
        mNewsTitle = root.findViewById(R.id.projects_news_title);
        mNewsSummary = root.findViewById(R.id.projects_news_summary);
        mMessage = root.findViewById(R.id.projects_message);
        mMessageTitle = root.findViewById(R.id.projects_message_title);
        mMessageBody = root.findViewById(R.id.projects_message_body);
        mJoinButton = root.findViewById(R.id.projects_join_button);
        mSecondaryButton = root.findViewById(R.id.projects_secondary_button);

        mJoinButton.setBackground(ChamferDrawable.primary(root.getContext()));
        mSecondaryButton.setBackground(ChamferDrawable.secondary(root.getContext()));
        mJoinButton.setOnClickListener(v -> join());
    }

    public void onResume() {
        ProjectsContent.addListener(this);
        ProjectsContent.refresh(mFragment.requireContext(), false);
        setJoining(ProjectsJoin.isBusy());
    }

    public void onPause() {
        ProjectsContent.removeListener(this);
        mJoinButton.removeCallbacks(mBusyWatcher);
    }

    private void setJoining(boolean joining) {
        mJoinButton.removeCallbacks(mBusyWatcher);
        mJoinButton.setEnabled(!joining);
        mJoinButton.setText(joining ? R.string.projects_join_preparing : R.string.projects_join);
        if (joining) mJoinButton.postDelayed(mBusyWatcher, 400);
    }

    /** The Discord invite from the signed config, else {@code fallback}. */
    public static String discordUrl(String fallback) {
        Snapshot snapshot = ProjectsContent.current();
        LauncherConfig config = snapshot == null ? null : snapshot.config;
        String url = config == null ? null : config.discordUrl();
        return url != null ? url : fallback;
    }

    @Override
    public void onContent(@Nullable Snapshot snapshot, boolean refreshing) {
        if (!mFragment.isAdded()) return;
        mSnapshot = snapshot;
        long now = System.currentTimeMillis();
        FragmentActivity activity = mFragment.requireActivity();

        LauncherConfig.Maintenance maintenance = snapshot == null || snapshot.config == null ? null : snapshot.config.maintenance;
        if (maintenance != null && maintenance.isActive(now)) {
            mMaintenanceTitle.setText(isBlank(maintenance.title) ? activity.getString(R.string.projects_maintenance_title) : maintenance.title.trim());
            mMaintenanceMessage.setText(isBlank(maintenance.message) ? "" : maintenance.message.trim());
            mMaintenanceMessage.setVisibility(isBlank(maintenance.message) ? View.GONE : View.VISIBLE);
            mMaintenance.setVisibility(View.VISIBLE);
        } else {
            mMaintenance.setVisibility(View.GONE);
        }

        if (snapshot != null && snapshot.hasContent() && snapshot.offline) {
            mStatus.setText(activity.getString(R.string.projects_offline_since, ProjectsUi.dateTime(activity, snapshot.fetchedAt)));
            mStatus.setVisibility(View.VISIBLE);
        } else {
            mStatus.setVisibility(View.GONE);
        }

        List<NewsFeed.NewsItem> visible = snapshot == null || snapshot.news == null ? null : snapshot.news.visible(now);
        mSkeleton.setVisibility(View.GONE);
        mNews.setVisibility(View.GONE);
        mMessage.setVisibility(View.GONE);
        if (visible != null && !visible.isEmpty()) {
            showNews(activity, snapshot, visible.get(0));
            showSecondary(R.string.projects_all_news, v -> openAllNews());
        } else if (snapshot == null || (refreshing && !snapshot.hasContent())) {
            mSkeleton.setVisibility(View.VISIBLE);
            showSecondary(R.string.projects_all_news, v -> openAllNews());
        } else if (snapshot.news != null || ProjectsUi.nothingPublished(snapshot)) {
            showMessage(R.string.projects_news_empty_title, R.string.projects_news_empty_body);
            mSecondaryButton.setVisibility(View.GONE);
        } else if (ProjectsUi.updateRequired(snapshot)) {
            showMessage(R.string.projects_news_update_title, R.string.projects_news_update_body);
            mSecondaryButton.setVisibility(View.GONE);
        } else {
            showMessage(R.string.projects_news_offline_title, R.string.projects_news_offline_body);
            showSecondary(R.string.projects_retry, v -> ProjectsContent.refresh(mFragment.requireContext(), true));
        }
    }

    private void showNews(final FragmentActivity activity, final Snapshot snapshot, final NewsFeed.NewsItem item) {
        mNewsCategory.setText(ProjectsUi.categoryLabel(activity, item));
        mNewsDate.setText(ProjectsUi.date(activity, item.publishedAtMillis()));
        mNewsTitle.setText(item.title);
        mNewsSummary.setText(item.summary);
        mNewsSummary.setVisibility(item.summary.isEmpty() ? View.GONE : View.VISIBLE);
        if (item.featured && item.cover != null) {
            ProjectsContent.loadCover(activity, snapshot, item.cover, mNewsCover, mNews.getWidth() > 0 ? mNews.getWidth() : 720);
        } else {
            mNewsCover.setTag(null);
            mNewsCover.setVisibility(View.GONE);
        }
        mNews.setOnClickListener(v -> ProjectsUi.showArticle(activity, snapshot, item));
        mNews.setVisibility(View.VISIBLE);
    }

    private void showMessage(int title, int body) {
        mMessageTitle.setText(title);
        mMessageBody.setText(body);
        mMessage.setVisibility(View.VISIBLE);
    }

    private void showSecondary(int label, View.OnClickListener listener) {
        mSecondaryButton.setText(label);
        mSecondaryButton.setOnClickListener(listener);
        mSecondaryButton.setVisibility(View.VISIBLE);
    }

    private void openAllNews() {
        Tools.swapFragment(mFragment.requireActivity(), ProjectsNewsFragment.class, ProjectsNewsFragment.TAG, null);
    }

    private void join() {
        FragmentActivity activity = mFragment.requireActivity();
        if (ProjectsJoin.isBusy()) {
            Toast.makeText(activity, R.string.projects_join_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (ProgressLayout.hasProcesses()) {
            Toast.makeText(activity, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        final WeakReference<ProjectsHomeCard> self = new WeakReference<>(this);
        boolean started = ProjectsJoin.start(activity, new ProjectsJoin.Callback() {
            @Override
            public void onReady(String profileKey) {
                ProjectsHomeCard card = self.get();
                if (card != null) card.launch(profileKey);
            }

            @Override
            public void onNeedsOnlineAccount() {
                FragmentActivity a = activityOrNull(self);
                if (a != null) Tools.hasNoOnlineProfileDialog(a);
            }

            @Override
            public void onFinishLoaderInstall() {
                FragmentActivity a = activityOrNull(self);
                if (a == null) return;
                new AlertDialog.Builder(a)
                        .setTitle(R.string.projects_join_finish_loader_title)
                        .setMessage(R.string.projects_join_finish_loader)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }

            @Override
            public void onFailed(boolean network, String details) {
                FragmentActivity a = activityOrNull(self);
                if (a == null) return;
                ProjectsUi.showError(a, R.string.projects_join_failed_title,
                        network ? R.string.projects_join_failed_network : R.string.projects_join_failed_generic, details);
            }
        });
        if (started) setJoining(true);
    }

    private static FragmentActivity activityOrNull(WeakReference<ProjectsHomeCard> ref) {
        ProjectsHomeCard card = ref.get();
        if (card == null || !card.mFragment.isAdded()) return null;
        return card.mFragment.getActivity();
    }

    /** Selects the Projects profile and presses Play, so upstream's launch checks all apply. */
    private void launch(String profileKey) {
        if (!mFragment.isAdded() || mFragment.getView() == null) return;
        mVersionSpinner.reloadProfiles();
        int index = mVersionSpinner.getProfileAdapter().resolveProfileIndex(profileKey);
        if (index >= 0) mVersionSpinner.setProfileSelection(index);
        // The game runs in another process: write the selection to disk before it starts.
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                .commit();
        mPlayButton.performClick();
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
