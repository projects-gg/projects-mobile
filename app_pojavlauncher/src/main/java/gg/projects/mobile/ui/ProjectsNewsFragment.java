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
import gg.projects.mobile.content.HomeContent;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.NewsSeen;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.mods.Motion;

/**
 * Every visible Projects news entry, Studio's and the Discord feed's merged as on the desktop
 * (pinned first in their order, then newest). Rows that arrive later (the feed follows the
 * content) fade in where they belong while the others glide; covers keep their space.
 *
 * <p>News the player has not seen yet has a small gold dot before its date. The screen decides
 * with what was seen when it opened, so the dots stay for the whole visit, and counts everything
 * it shows as seen from then on; opening an article fades its dot out where it is.
 *
 * <p>M17: Minecraft's own updates (the feed's {@code minecraft[]}) come after every Projects post,
 * under a quiet "Minecraft" heading, labelled "Minecraft" in ash instead of a gold category, in the
 * app's language; they never carry the dot and never count as seen Projects news.
 */
public class ProjectsNewsFragment extends Fragment implements ProjectsContent.Listener {
    public static final String TAG = "ProjectsNewsFragment";
    private static final long DOT_FADE_MS = 200;
    private static final int TYPE_NEWS = 0;
    private static final int TYPE_HEADING = 1;
    /** The heading's row id: no news id has a space. */
    private static final String HEADING_ID = "minecraft heading";

    private final NewsAdapter mAdapter = new NewsAdapter();
    private RecyclerView mList;
    private View mMessage;
    private TextView mMessageTitle;
    private TextView mMessageBody;
    private Button mRetry;
    private TextView mStatus;
    private boolean mAnimateRows;
    /** What was seen when the screen opened. */
    private NewsSeen mSeenAtOpen;
    private final Runnable mSeenChanged = this::updateDots;

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
        if (mSeenAtOpen == null) mSeenAtOpen = NewsSeenStore.get(view.getContext());
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
        NewsSeenStore.addListener(mSeenChanged);
        ProjectsContent.addListener(this);
        ProjectsContent.refresh(requireContext(), false);
    }

    @Override
    public void onPause() {
        super.onPause();
        NewsSeenStore.removeListener(mSeenChanged);
        ProjectsContent.removeListener(this);
    }

    /** New to the player: published after what was seen when the screen opened, and not opened since. */
    private boolean isNew(NewsFeed.NewsItem item) {
        return mSeenAtOpen != null && mSeenAtOpen.isNew(item, System.currentTimeMillis())
                && !NewsSeenStore.get(requireContext()).isRead(item.id);
    }

    /** An article was opened: its dot fades out where it is. */
    private void updateDots() {
        if (mList == null || !isAdded()) return;
        for (int i = 0; i < mList.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = mList.getChildViewHolder(mList.getChildAt(i));
            if (holder instanceof NewsHolder) ((NewsHolder) holder).updateDot();
        }
    }

    @Override
    public void onContent(@Nullable Snapshot snapshot, boolean refreshing) {
        if (!isAdded() || getView() == null) return;
        HomeContent home = snapshot == null ? null : snapshot.home(System.currentTimeMillis());
        List<NewsFeed.NewsItem> items = home == null ? new ArrayList<NewsFeed.NewsItem>() : home.news;
        List<NewsFeed.NewsItem> minecraft = new ArrayList<>();
        if (home != null) for (NewsFeed.NewsItem entry : home.minecraft) minecraft.add(ProjectsUi.localized(requireContext(), entry));
        mAdapter.submit(snapshot, items, minecraft);
        // Everything this visit shows counts as seen from now on (the dots stay until it ends);
        // Minecraft's updates are not Projects news and move nothing.
        NewsSeenStore.advance(requireContext(), items);
        boolean message = false;
        if (items.isEmpty() && minecraft.isEmpty() && !(refreshing && (snapshot == null || !snapshot.hasContent()))) {
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
        String status = ProjectsUi.offlineSince(requireContext(), snapshot);
        if (status != null) mStatus.setText(status);
        mStatus.setVisibility(status != null ? View.VISIBLE : View.GONE);
    }

    /** What a row shows; rows re-bind only when this changes. */
    private static String content(Row row) {
        NewsFeed.NewsItem item = row.item;
        if (item == null) return HEADING_ID;
        return item.title + '\n' + item.summary + '\n' + item.publishedAtMillis() + '\n' + item.pinned + '\n'
                + item.category + '\n' + item.featured + '\n' + item.fromFeed() + '\n' + item.isMinecraft() + '\n'
                + (item.cover == null ? "" : item.cover.sha256);
    }

    /** Studio's featured covers and every Discord feed cover are shown (feed covers are prefetched). */
    private static boolean showsCover(NewsFeed.NewsItem item) {
        return item.cover != null && (item.featured || item.fromFeed());
    }

    /** A row of the list: a news entry, or the "Minecraft" heading ({@code item} null). */
    private static final class Row {
        final String id;
        @Nullable
        final NewsFeed.NewsItem item;

        Row(String id, @Nullable NewsFeed.NewsItem item) {
            this.id = id;
            this.item = item;
        }
    }

    private final class NewsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private Snapshot mSnapshot;
        private List<Row> mRows = new ArrayList<>();
        private final Map<String, Long> mIds = new HashMap<>();
        private final Set<String> mShown = new HashSet<>();

        void submit(Snapshot snapshot, List<NewsFeed.NewsItem> items, List<NewsFeed.NewsItem> minecraft) {
            mSnapshot = snapshot;
            final List<Row> rows = new ArrayList<>();
            for (NewsFeed.NewsItem item : items) rows.add(new Row(item.id, item));
            if (!minecraft.isEmpty()) {
                rows.add(new Row(HEADING_ID, null));
                for (NewsFeed.NewsItem entry : minecraft) rows.add(new Row(entry.id, entry));
            }
            final List<Row> old = mRows;
            mRows = rows;
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
                    return rows.size();
                }

                @Override
                public boolean areItemsTheSame(int oldPosition, int newPosition) {
                    return old.get(oldPosition).id.equals(rows.get(newPosition).id);
                }

                @Override
                public boolean areContentsTheSame(int oldPosition, int newPosition) {
                    return content(old.get(oldPosition)).equals(content(rows.get(newPosition)));
                }
            }).dispatchUpdatesTo(this);
        }

        @Override
        public long getItemId(int position) {
            String id = mRows.get(position).id;
            Long stable = mIds.get(id);
            if (stable == null) {
                stable = (long) mIds.size();
                mIds.put(id, stable);
            }
            return stable;
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).item == null ? TYPE_HEADING : TYPE_NEWS;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADING) {
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_projects_news_header, parent, false)) {};
            }
            View row = inflater.inflate(R.layout.item_projects_news, parent, false);
            Press.attach(row, Press.Style.CARD); // M14: rows press with a light tick
            return new NewsHolder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = mRows.get(position);
            if (holder instanceof NewsHolder && row.item != null) ((NewsHolder) holder).bind(mSnapshot, row.item);
            View view = holder.itemView;
            if (mAnimateRows && !mShown.contains(row.id) && position < 8) {
                view.setAlpha(0f);
                view.setTranslationY(14 * view.getResources().getDisplayMetrics().density);
                view.animate().alpha(1f).translationY(0f).setStartDelay(60L + position * 40L)
                        .setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
            }
            mShown.add(row.id);
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            holder.itemView.animate().cancel();
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);
            if (holder instanceof NewsHolder) ((NewsHolder) holder).mDot.animate().cancel();
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }
    }

    private final class NewsHolder extends RecyclerView.ViewHolder {
        private final CoverView mCover;
        private final TextView mCategory;
        private final View mDot;
        private final TextView mDate;
        private final TextView mPinned;
        private final TextView mTitle;
        private final TextView mSummary;
        private NewsFeed.NewsItem mItem;
        /** The item whose dot this row shows (or keeps the room of, after it faded). */
        private String mDotFor;

        NewsHolder(View view) {
            super(view);
            mCover = view.findViewById(R.id.projects_item_cover);
            mCover.setMaxCoverHeight(Math.round(180 * view.getResources().getDisplayMetrics().density));
            mCategory = view.findViewById(R.id.projects_item_category);
            mDot = view.findViewById(R.id.projects_item_dot);
            mDate = view.findViewById(R.id.projects_item_date);
            mPinned = view.findViewById(R.id.projects_item_pinned);
            mTitle = view.findViewById(R.id.projects_item_title);
            mSummary = view.findViewById(R.id.projects_item_summary);
        }

        void bind(final Snapshot snapshot, final NewsFeed.NewsItem item) {
            mItem = item;
            ProjectsUi.bindCategory(mCategory, item);
            mDate.setText(ProjectsUi.date(itemView.getContext(), item.publishedAtMillis()));
            mPinned.setVisibility(item.pinned ? View.VISIBLE : View.GONE);
            mTitle.setText(item.title);
            mSummary.setText(item.summary);
            mSummary.setVisibility(item.summary.isEmpty() ? View.GONE : View.VISIBLE);
            mCover.bind(snapshot, item, showsCover(item));
            itemView.setOnClickListener(v -> ProjectsUi.showArticle(requireActivity(), snapshot, item));
            if (isNew(item)) {
                mDot.animate().cancel();
                mDot.setAlpha(1f);
                mDot.setVisibility(View.VISIBLE);
                mDotFor = item.id;
            } else if (!item.id.equals(mDotFor)) {
                mDot.animate().cancel();
                mDot.setVisibility(View.GONE);
                mDotFor = null;
            } else if (mDot.getVisibility() == View.VISIBLE) {
                mDot.setVisibility(View.INVISIBLE);
            }
        }

        /** The article was read: the dot fades out and keeps its room, so the date stays put. */
        void updateDot() {
            if (mItem == null || !mItem.id.equals(mDotFor) || mDot.getVisibility() != View.VISIBLE || isNew(mItem)) return;
            if (Motion.enabled(mDot.getContext())) {
                mDot.animate().alpha(0f).setDuration(DOT_FADE_MS).setInterpolator(Motion.EASE_OUT)
                        .withEndAction(() -> mDot.setVisibility(View.INVISIBLE)).start();
            } else {
                mDot.setVisibility(View.INVISIBLE);
            }
        }
    }
}
