package gg.projects.mobile.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.mods.Motion;

/**
 * Every visible Projects news entry, Studio's and the Discord feed's merged as on the desktop
 * (pinned first in their order, then newest). Rows that arrive later (the feed follows the
 * content) fade in where they belong while the others glide; covers keep their space.
 */
public class ProjectsNewsFragment extends Fragment implements ProjectsContent.Listener {
    public static final String TAG = "ProjectsNewsFragment";

    private final NewsAdapter mAdapter = new NewsAdapter();
    private RecyclerView mList;
    private View mMessage;
    private TextView mMessageTitle;
    private TextView mMessageBody;
    private Button mRetry;
    private TextView mStatus;
    private boolean mAnimateRows;

    public ProjectsNewsFragment() {
        super(R.layout.fragment_projects_news);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mList = view.findViewById(R.id.projects_news_list);
        mList.setLayoutManager(new LinearLayoutManager(view.getContext()));
        mAdapter.setHasStableIds(true);
        mList.setAdapter(mAdapter);
        if (Motion.enabled(view.getContext())) {
            DefaultItemAnimator animator = new DefaultItemAnimator();
            // New rows fade in place, the others glide; a changed row is re-bound in place.
            animator.setSupportsChangeAnimations(false);
            animator.setAddDuration(Motion.ENTER_MS);
            animator.setMoveDuration(Motion.ENTER_MS);
            animator.setRemoveDuration(Motion.EXIT_MS);
            mList.setItemAnimator(animator);
        } else {
            mList.setItemAnimator(null);
        }
        mAnimateRows = Motion.enabled(view.getContext());
        mMessage = view.findViewById(R.id.projects_news_message);
        mMessageTitle = view.findViewById(R.id.projects_news_message_title);
        mMessageBody = view.findViewById(R.id.projects_news_message_body);
        mRetry = view.findViewById(R.id.projects_news_retry);
        mRetry.setBackground(ChamferDrawable.secondary(view.getContext()));
        mRetry.setOnClickListener(v -> ProjectsContent.refresh(requireContext(), true));
        mStatus = view.findViewById(R.id.projects_news_status);
    }

    /** Slide up and fade in; leave a little faster (as the mod screen). */
    @Nullable
    @Override
    public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
        View view = getView();
        if (view == null || !Motion.enabled(view.getContext())) return null;
        float distance = 28 * view.getResources().getDisplayMetrics().density;
        AnimatorSet set = new AnimatorSet();
        if (enter) {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, distance, 0f));
            set.setDuration(Motion.ENTER_MS);
            set.setInterpolator(Motion.EASE_OUT);
        } else {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, distance * 0.6f));
            set.setDuration(Motion.EXIT_MS);
            set.setInterpolator(Motion.EASE_IN);
        }
        return set;
    }

    @Override
    public void onResume() {
        super.onResume();
        ProjectsContent.addListener(this);
        ProjectsContent.refresh(requireContext(), false);
    }

    @Override
    public void onPause() {
        super.onPause();
        ProjectsContent.removeListener(this);
    }

    @Override
    public void onContent(@Nullable Snapshot snapshot, boolean refreshing) {
        if (!isAdded() || getView() == null) return;
        List<NewsFeed.NewsItem> items = snapshot == null
                ? new ArrayList<NewsFeed.NewsItem>() : snapshot.home(System.currentTimeMillis()).news;
        mAdapter.submit(snapshot, items);
        boolean message = false;
        if (items.isEmpty() && !(refreshing && (snapshot == null || !snapshot.hasContent()))) {
            boolean empty = snapshot != null && (snapshot.news != null || ProjectsUi.nothingPublished(snapshot));
            if (empty) {
                mMessageTitle.setText(R.string.projects_news_empty_title);
                mMessageBody.setText(R.string.projects_news_empty_body);
                mRetry.setVisibility(View.GONE);
            } else if (ProjectsUi.updateRequired(snapshot)) {
                mMessageTitle.setText(R.string.projects_news_update_title);
                mMessageBody.setText(R.string.projects_news_update_body);
                mRetry.setVisibility(View.GONE);
            } else {
                mMessageTitle.setText(R.string.projects_news_offline_title);
                mMessageBody.setText(R.string.projects_news_offline_body);
                mRetry.setVisibility(View.VISIBLE);
            }
            message = true;
        }
        mMessage.setVisibility(message ? View.VISIBLE : View.GONE);
        if (snapshot != null && snapshot.hasContent() && snapshot.offline && !refreshing) {
            mStatus.setText(getString(R.string.projects_offline_since, ProjectsUi.dateTime(requireContext(), snapshot.fetchedAt)));
            mStatus.setVisibility(View.VISIBLE);
        } else {
            mStatus.setVisibility(View.GONE);
        }
    }

    /** What a row shows; rows re-bind only when this changes. */
    private static String content(NewsFeed.NewsItem item) {
        return item.title + '\n' + item.summary + '\n' + item.publishedAtMillis() + '\n' + item.pinned + '\n'
                + item.category + '\n' + item.featured + '\n' + item.fromFeed() + '\n'
                + (item.cover == null ? "" : item.cover.sha256);
    }

    /** Studio's featured covers and every Discord feed cover are shown (feed covers are prefetched). */
    private static boolean showsCover(NewsFeed.NewsItem item) {
        return item.cover != null && (item.featured || item.fromFeed());
    }

    private final class NewsAdapter extends RecyclerView.Adapter<NewsHolder> {
        private Snapshot mSnapshot;
        private List<NewsFeed.NewsItem> mItems = new ArrayList<>();
        private final Map<String, Long> mIds = new HashMap<>();
        private final Set<String> mShown = new HashSet<>();

        void submit(Snapshot snapshot, final List<NewsFeed.NewsItem> items) {
            mSnapshot = snapshot;
            final List<NewsFeed.NewsItem> old = mItems;
            mItems = new ArrayList<>(items);
            if (old.isEmpty()) {
                // First fill: rows enter with their own staggered animation, not the item animator's.
                notifyDataSetChanged();
                if (mAnimateRows) mList.postDelayed(() -> mAnimateRows = false, 700);
                return;
            }
            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return items.size();
                }

                @Override
                public boolean areItemsTheSame(int oldPosition, int newPosition) {
                    return old.get(oldPosition).id.equals(items.get(newPosition).id);
                }

                @Override
                public boolean areContentsTheSame(int oldPosition, int newPosition) {
                    return content(old.get(oldPosition)).equals(content(items.get(newPosition)));
                }
            }).dispatchUpdatesTo(this);
        }

        @Override
        public long getItemId(int position) {
            String id = mItems.get(position).id;
            Long stable = mIds.get(id);
            if (stable == null) {
                stable = (long) mIds.size();
                mIds.put(id, stable);
            }
            return stable;
        }

        @NonNull
        @Override
        public NewsHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new NewsHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_projects_news, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull NewsHolder holder, int position) {
            NewsFeed.NewsItem item = mItems.get(position);
            holder.bind(mSnapshot, item);
            View view = holder.itemView;
            if (mAnimateRows && !mShown.contains(item.id) && position < 8) {
                view.setAlpha(0f);
                view.setTranslationY(14 * view.getResources().getDisplayMetrics().density);
                view.animate().alpha(1f).translationY(0f).setStartDelay(60L + position * 40L)
                        .setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
            }
            mShown.add(item.id);
        }

        @Override
        public void onViewRecycled(@NonNull NewsHolder holder) {
            holder.itemView.animate().cancel();
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    private final class NewsHolder extends RecyclerView.ViewHolder {
        private final CoverView mCover;
        private final TextView mCategory;
        private final TextView mDate;
        private final TextView mPinned;
        private final TextView mTitle;
        private final TextView mSummary;

        NewsHolder(View view) {
            super(view);
            mCover = view.findViewById(R.id.projects_item_cover);
            mCover.setMaxCoverHeight(Math.round(180 * view.getResources().getDisplayMetrics().density));
            mCategory = view.findViewById(R.id.projects_item_category);
            mDate = view.findViewById(R.id.projects_item_date);
            mPinned = view.findViewById(R.id.projects_item_pinned);
            mTitle = view.findViewById(R.id.projects_item_title);
            mSummary = view.findViewById(R.id.projects_item_summary);
        }

        void bind(final Snapshot snapshot, final NewsFeed.NewsItem item) {
            mCategory.setText(ProjectsUi.categoryLabel(itemView.getContext(), item));
            mDate.setText(ProjectsUi.date(itemView.getContext(), item.publishedAtMillis()));
            mPinned.setVisibility(item.pinned ? View.VISIBLE : View.GONE);
            mTitle.setText(item.title);
            mSummary.setText(item.summary);
            mSummary.setVisibility(item.summary.isEmpty() ? View.GONE : View.VISIBLE);
            mCover.bind(snapshot, item, showsCover(item));
            itemView.setOnClickListener(v -> ProjectsUi.showArticle(requireActivity(), snapshot, item));
        }
    }
}
