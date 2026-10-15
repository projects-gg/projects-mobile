package gg.projects.mobile.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.Snapshot;

/** Every visible Projects news entry, newest first (pinned on top). */
public class ProjectsNewsFragment extends Fragment implements ProjectsContent.Listener {
    public static final String TAG = "ProjectsNewsFragment";

    private final NewsAdapter mAdapter = new NewsAdapter();
    private View mMessage;
    private TextView mMessageTitle;
    private TextView mMessageBody;
    private Button mRetry;
    private TextView mStatus;

    public ProjectsNewsFragment() {
        super(R.layout.fragment_projects_news);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView list = view.findViewById(R.id.projects_news_list);
        list.setLayoutManager(new LinearLayoutManager(view.getContext()));
        list.setAdapter(mAdapter);
        mMessage = view.findViewById(R.id.projects_news_message);
        mMessageTitle = view.findViewById(R.id.projects_news_message_title);
        mMessageBody = view.findViewById(R.id.projects_news_message_body);
        mRetry = view.findViewById(R.id.projects_news_retry);
        mRetry.setBackground(ChamferDrawable.secondary(view.getContext()));
        mRetry.setOnClickListener(v -> ProjectsContent.refresh(requireContext(), true));
        mStatus = view.findViewById(R.id.projects_news_status);
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
        List<NewsFeed.NewsItem> items = snapshot == null || snapshot.news == null
                ? new ArrayList<NewsFeed.NewsItem>() : snapshot.news.visible(System.currentTimeMillis());
        mAdapter.set(snapshot, items);
        mMessage.setVisibility(View.GONE);
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
            mMessage.setVisibility(View.VISIBLE);
        }
        if (snapshot != null && snapshot.hasContent() && snapshot.offline) {
            mStatus.setText(getString(R.string.projects_offline_since, ProjectsUi.dateTime(requireContext(), snapshot.fetchedAt)));
            mStatus.setVisibility(View.VISIBLE);
        } else {
            mStatus.setVisibility(View.GONE);
        }
    }

    private final class NewsAdapter extends RecyclerView.Adapter<NewsHolder> {
        private Snapshot mSnapshot;
        private List<NewsFeed.NewsItem> mItems = new ArrayList<>();

        void set(Snapshot snapshot, List<NewsFeed.NewsItem> items) {
            mSnapshot = snapshot;
            mItems = items;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public NewsHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new NewsHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_projects_news, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull NewsHolder holder, int position) {
            holder.bind(mSnapshot, mItems.get(position));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    private final class NewsHolder extends RecyclerView.ViewHolder {
        private final ImageView mCover;
        private final TextView mCategory;
        private final TextView mDate;
        private final TextView mPinned;
        private final TextView mTitle;
        private final TextView mSummary;

        NewsHolder(View view) {
            super(view);
            mCover = view.findViewById(R.id.projects_item_cover);
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
            if (item.featured && item.cover != null) {
                ProjectsContent.loadCover(itemView.getContext(), snapshot, item.cover, mCover,
                        itemView.getResources().getDisplayMetrics().widthPixels);
            } else {
                mCover.setTag(null);
                mCover.setVisibility(View.GONE);
            }
            itemView.setOnClickListener(v -> ProjectsUi.showArticle(requireActivity(), snapshot, item));
        }
    }
}
