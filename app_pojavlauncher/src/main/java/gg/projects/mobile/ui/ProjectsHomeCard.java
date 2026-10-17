package gg.projects.mobile.ui;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.DefaultVersion;
import gg.projects.mobile.content.HomeContent;
import gg.projects.mobile.content.LauncherConfig;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.NewsText;
import gg.projects.mobile.content.PackState;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.join.DefaultProfiles;
import gg.projects.mobile.join.ProjectsJoin;
import gg.projects.mobile.join.SyncNotice;
import gg.projects.mobile.mods.ModsSummary;
import gg.projects.mobile.mods.ProjectsModsFragment;

/**
 * The Projects card at the top of the main menu: the latest news (Studio's signed content and the
 * Discord bot's feed, merged as on the desktop), the event card with its countdown, the newest
 * patch notes, the "Modlar" row and the "Projects'e katıl" action.
 *
 * <p>Re-binding with the same content changes nothing on screen. When something changes, sections
 * that come or go fade in place while the rest glides, and a block that shows something else
 * cross-fades in place ({@link CardMotion}); with animations off everything just appears.
 */
public final class ProjectsHomeCard implements ProjectsContent.Listener {
    private static final int SKELETON = 0;
    private static final int CONTENT = 1;
    private static final int MESSAGE = 2;

    private final Fragment mFragment;
    private final mcVersionSpinner mVersionSpinner;
    private final View mPlayButton;

    private final ViewGroup mSceneRoot;
    private final TextView mStatus;
    private final View mMaintenance;
    private final TextView mMaintenanceTitle;
    private final TextView mMaintenanceMessage;
    private final View mSkeleton;
    private final View mNews;
    private final CoverView mNewsCover;
    private final TextView mNewsCategory;
    private final TextView mNewsDate;
    private final TextView mNewsTitle;
    private final TextView mNewsSummary;
    private final View mEvent;
    private final TextView mEventLabel;
    private final TextView mEventMore;
    private final ImageView mEventCaret;
    private final TextView mEventTitle;
    private final View mEventCountdown;
    private final TextView[] mEventCells;
    private final TextView mEventLeft;
    private final TextView mEventStarted;
    private final View mPatch;
    private final TextView mPatchMeta;
    private final ImageView mPatchCaret;
    private final TextView mPatchTitle;
    private final LinearLayout mPatchLines;
    private final View mMessage;
    private final TextView mMessageTitle;
    private final TextView mMessageBody;
    private final Button mJoinButton;
    private final Button mSecondaryButton;
    private final View mModsRow;
    private final TextView mModsCount;
    private final View mModsBadge;

    private Snapshot mSnapshot;
    private HomeContent mHome;
    private boolean mRefreshing;
    private int mModsToken;
    @Nullable
    private ModsSummary mModsSummary;
    private boolean mResumed;

    /** What is on screen: the card's structure and each block's identity. */
    private String mStructure;
    private String mNewsShown;
    private String mEventShown;
    private String mPatchShown;
    @Nullable
    private HomeContent.Event mEventBound;
    private int mEventCount;
    private String mPatchLinesShown;
    /** The recommended Minecraft version the default profile was last checked against. */
    @Nullable
    private String mRecommendedChecked;

    private final Runnable mTick = this::tick;

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
        View card = root.findViewById(R.id.projects_card);
        mSceneRoot = card.getParent() instanceof ViewGroup ? (ViewGroup) card.getParent() : (ViewGroup) card;
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
        mEvent = root.findViewById(R.id.projects_event);
        mEventLabel = root.findViewById(R.id.projects_event_label);
        mEventMore = root.findViewById(R.id.projects_event_more);
        mEventCaret = root.findViewById(R.id.projects_event_caret);
        mEventTitle = root.findViewById(R.id.projects_event_title);
        mEventCountdown = root.findViewById(R.id.projects_event_countdown);
        mEventCells = new TextView[]{root.findViewById(R.id.projects_event_days), root.findViewById(R.id.projects_event_hours),
                root.findViewById(R.id.projects_event_minutes), root.findViewById(R.id.projects_event_seconds)};
        mEventLeft = root.findViewById(R.id.projects_event_left);
        mEventStarted = root.findViewById(R.id.projects_event_started);
        mPatch = root.findViewById(R.id.projects_patch);
        mPatchMeta = root.findViewById(R.id.projects_patch_meta);
        mPatchCaret = root.findViewById(R.id.projects_patch_caret);
        mPatchTitle = root.findViewById(R.id.projects_patch_title);
        mPatchLines = root.findViewById(R.id.projects_patch_lines);
        mMessage = root.findViewById(R.id.projects_message);
        mMessageTitle = root.findViewById(R.id.projects_message_title);
        mMessageBody = root.findViewById(R.id.projects_message_body);
        mJoinButton = root.findViewById(R.id.projects_join_button);
        mSecondaryButton = root.findViewById(R.id.projects_secondary_button);
        mModsRow = root.findViewById(R.id.projects_mods_row);
        mModsCount = root.findViewById(R.id.projects_mods_row_count);
        mModsBadge = root.findViewById(R.id.projects_mods_row_badge);

        Context context = root.getContext();
        mNewsCover.setMaxCoverHeight(Math.round(200 * context.getResources().getDisplayMetrics().density));
        mJoinButton.setBackground(ChamferDrawable.primary(context));
        mSecondaryButton.setBackground(ChamferDrawable.secondary(context));
        mJoinButton.setOnClickListener(v -> join());
        mModsRow.setOnClickListener(v -> openMods());
        mEvent.setOnClickListener(v -> openEvent());
        mEventMore.setOnClickListener(v -> showOtherEvents());
        // The small "+N etkinlik" pill gets a full-size touch area without growing the card.
        mEvent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateMoreTouchArea());
    }

    /** Extends the "+N etkinlik" pill's touch area to the 48 dp minimum within the event block. */
    private void updateMoreTouchArea() {
        if (mEventMore.getVisibility() != View.VISIBLE || mEventMore.getWidth() == 0) {
            mEvent.setTouchDelegate(null);
            return;
        }
        Rect area = new Rect();
        mEventMore.getHitRect(area);
        View row = (View) mEventMore.getParent();
        area.offset(row.getLeft(), row.getTop());
        int min = Math.round(48 * mEvent.getResources().getDisplayMetrics().density);
        area.inset(-Math.max(0, (min - area.width() + 1) / 2), -Math.max(0, (min - area.height() + 1) / 2));
        mEvent.setTouchDelegate(new TouchDelegate(area, mEventMore));
    }

    public void onResume() {
        mResumed = true;
        mRecommendedChecked = null;
        ProjectsContent.addListener(this);
        ProjectsContent.refresh(mFragment.requireContext(), false);
        setJoining(ProjectsJoin.isBusy());
    }

    public void onPause() {
        mResumed = false;
        ProjectsContent.removeListener(this);
        mJoinButton.removeCallbacks(mBusyWatcher);
        mEvent.removeCallbacks(mTick);
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
        mRefreshing = refreshing;
        followRecommendedVersion(snapshot);
        refreshModsRow(snapshot);
        render(System.currentTimeMillis());
    }

    /**
     * Keeps upstream's default profile on the recommended Minecraft version (at start and when a
     * new config recommends another one); a version the player picked stays ({@link DefaultProfiles}).
     */
    private void followRecommendedVersion(@Nullable Snapshot snapshot) {
        LauncherConfig config = snapshot == null ? null : snapshot.config;
        String recommended = DefaultVersion.recommended(config);
        if (recommended.equals(mRecommendedChecked)) return;
        mRecommendedChecked = recommended;
        if (!DefaultProfiles.apply(mFragment.requireContext(), config)) return;
        // A reload alone refreshes the spinner's list, not the selection it shows.
        mVersionSpinner.reloadProfiles();
        String selected = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        int index = mVersionSpinner.getProfileAdapter().resolveProfileIndex(selected);
        if (index >= 0) mVersionSpinner.setSelection(index);
    }

    // ---- rendering ----

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static void setText(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private static void show(View view, boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    private static String coverKey(NewsFeed.NewsItem item, boolean shown) {
        return shown && item.cover != null ? String.valueOf(item.cover.sha256) : "";
    }

    /** Studio's featured covers and every Discord feed cover are shown on the card. */
    private static boolean showsCover(NewsFeed.NewsItem item) {
        return item.cover != null && (item.featured || item.fromFeed());
    }

    private void render(long now) {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null) return;
        Snapshot snapshot = mSnapshot;
        HomeContent home = snapshot == null ? null : snapshot.home(now);
        mHome = home;

        LauncherConfig.Maintenance maintenance = snapshot == null || snapshot.config == null ? null : snapshot.config.maintenance;
        boolean maintenanceOn = maintenance != null && maintenance.isActive(now);
        String status = ProjectsUi.offlineSince(activity, snapshot);

        NewsFeed.NewsItem notes = home == null ? null : home.patchNotes;
        NewsFeed.NewsItem top = null;
        if (home != null) {
            for (NewsFeed.NewsItem item : home.news) {
                // The patch notes tile already shows its article (as the desktop's column does).
                if (notes == null || !item.id.equals(notes.id)) {
                    top = item;
                    break;
                }
            }
        }
        HomeContent.Event event = home == null || home.events.isEmpty() ? null : home.events.get(0);
        ModsSummary pack = notes == null && mModsSummary != null && mModsSummary.changelog != null ? mModsSummary : null;
        boolean hasContent = top != null || event != null || notes != null || pack != null;

        int state;
        int messageTitle = 0;
        int messageBody = 0;
        boolean retry = false;
        if (hasContent) {
            state = CONTENT;
        } else if (snapshot == null || (mRefreshing && !snapshot.hasContent())) {
            state = SKELETON;
        } else if (snapshot.news != null || ProjectsUi.nothingPublished(snapshot)) {
            state = MESSAGE;
            messageTitle = R.string.projects_news_empty_title;
            messageBody = R.string.projects_news_empty_body;
        } else if (ProjectsUi.updateRequired(snapshot)) {
            state = MESSAGE;
            messageTitle = R.string.projects_news_update_title;
            messageBody = R.string.projects_news_update_body;
        } else {
            state = MESSAGE;
            messageTitle = R.string.projects_news_offline_title;
            messageBody = R.string.projects_news_offline_body;
            retry = true;
        }
        boolean live = event != null && event.isLive(now);
        int others = home == null ? 0 : Math.max(0, home.events.size() - 1);
        boolean eventOpens = event != null && ProjectsUi.eventOpens(home, event);
        String newsShown = top == null ? null : top.id + '\n' + top.title + '\n' + top.summary + '\n'
                + top.publishedAtMillis() + '\n' + coverKey(top, showsCover(top));
        String eventShown = event == null ? null : event.id + '\n' + live + '\n' + event.title;
        String patchShown = notes != null ? "news\n" + notes.id + '\n' + notes.title
                : pack != null ? "pack\n" + pack.packName + '\n' + pack.packVersion + '\n' + pack.changelog : null;
        boolean news = state == CONTENT && top != null;
        boolean allNews = state == SKELETON || (home != null && !home.news.isEmpty());
        String structure = state + "|" + maintenanceOn + "|" + (status != null) + "|" + messageTitle + "|" + retry + "|" + allNews
                + "|" + (news ? newsShown : "") + "|" + (state == CONTENT ? eventShown + "|" + others + "|" + eventOpens + "|" + patchShown : "");

        boolean changed = mStructure != null && !structure.equals(mStructure);
        if (changed && CardMotion.canAnimate(mSceneRoot)) {
            // Blocks that stay but show something else cross-fade in place.
            if (news && mNews.getVisibility() == View.VISIBLE && mNewsShown != null && !mNewsShown.equals(newsShown)) {
                CardMotion.crossfade(mNews);
            }
            if (state == CONTENT && event != null && mEvent.getVisibility() == View.VISIBLE && mEventShown != null
                    && !mEventShown.equals(eventShown)) {
                CardMotion.crossfade(mEvent);
            }
            if (state == CONTENT && patchShown != null && mPatch.getVisibility() == View.VISIBLE && mPatchShown != null
                    && !mPatchShown.equals(patchShown)) {
                CardMotion.crossfade(mPatch);
            }
            CardMotion.beginLayoutChange(mSceneRoot);
        }
        mStructure = structure;

        // Maintenance and the offline line.
        if (maintenanceOn) {
            setText(mMaintenanceTitle, isBlank(maintenance.title) ? activity.getString(R.string.projects_maintenance_title) : maintenance.title.trim());
            setText(mMaintenanceMessage, isBlank(maintenance.message) ? "" : maintenance.message.trim());
            show(mMaintenanceMessage, !isBlank(maintenance.message));
        }
        show(mMaintenance, maintenanceOn);
        if (status != null) setText(mStatus, status);
        show(mStatus, status != null);

        show(mSkeleton, state == SKELETON);
        show(mMessage, state == MESSAGE);
        if (state == MESSAGE) {
            setText(mMessageTitle, activity.getString(messageTitle));
            setText(mMessageBody, activity.getString(messageBody));
        }

        // The latest story.
        show(mNews, news);
        if (news) bindNews(activity, snapshot, top);
        mNewsShown = news ? newsShown : null;

        // The event card.
        boolean eventVisible = state == CONTENT && event != null;
        show(mEvent, eventVisible);
        mEventBound = eventVisible ? event : null;
        mEventShown = eventVisible ? eventShown : null;
        mEventCount = home == null ? 0 : home.events.size();
        if (eventVisible) bindEvent(activity, event, live, others, eventOpens, now);

        // The patch notes.
        boolean patchVisible = state == CONTENT && patchShown != null;
        show(mPatch, patchVisible);
        mPatchShown = patchVisible ? patchShown : null;
        if (patchVisible) bindPatch(activity, snapshot, notes, pack, now);

        if (retry) {
            showSecondary(R.string.projects_retry, v -> ProjectsContent.refresh(mFragment.requireContext(), true));
        } else if (allNews) {
            showSecondary(R.string.projects_all_news, v -> openAllNews());
        } else {
            show(mSecondaryButton, false);
        }
        scheduleTick(now);
    }

    private void bindNews(final FragmentActivity activity, final Snapshot snapshot, final NewsFeed.NewsItem item) {
        setText(mNewsCategory, ProjectsUi.categoryLabel(activity, item));
        setText(mNewsDate, ProjectsUi.date(activity, item.publishedAtMillis()));
        setText(mNewsTitle, item.title);
        setText(mNewsSummary, item.summary);
        show(mNewsSummary, !item.summary.isEmpty());
        mNewsCover.bind(snapshot, item, showsCover(item));
        mNews.setOnClickListener(v -> ProjectsUi.showArticle(activity, snapshot, item));
    }

    private void bindEvent(Context context, HomeContent.Event event, boolean live, int others, boolean opens, long now) {
        setText(mEventLabel, context.getString(live ? R.string.projects_event_live : R.string.projects_event_next));
        mEventLabel.setTextColor(ContextCompat.getColor(context, live ? R.color.projects_gold_300 : R.color.projects_ash_300));
        setText(mEventTitle, event.title);
        show(mEventMore, others > 0);
        if (others > 0) setText(mEventMore, context.getResources().getQuantityString(R.plurals.projects_events_more, others, others));
        boolean article = event.newsId != null && mHome != null && mHome.find(event.newsId) != null;
        show(mEventCaret, opens);
        if (opens) mEventCaret.setImageResource(article ? R.drawable.projects_ic_caret_right : R.drawable.projects_ic_arrow_square_out);
        mEvent.setClickable(opens);
        mEvent.setFocusable(opens);
        boolean started = live && event.openEnded;
        show(mEventCountdown, !started);
        show(mEventStarted, started);
        show(mEventLeft, live);
        updateCountdown(context, now);
    }

    /** The countdown digits (or "X önce başladı"), updated every second while the card is shown. */
    private void updateCountdown(Context context, long now) {
        HomeContent.Event event = mEventBound;
        if (event == null) return;
        boolean live = event.isLive(now);
        if (live && event.openEnded) {
            setText(mEventStarted, context.getString(R.string.projects_event_started, ProjectsUi.relative(event.startsAt, now)));
            return;
        }
        long remaining = Math.max(0, (live ? event.endsAt : event.startsAt) - now);
        long total = remaining / 1000;
        long[] values = {total / 86_400, (total % 86_400) / 3_600, (total % 3_600) / 60, total % 60};
        for (int i = 0; i < values.length; i++) {
            String digits = String.format(Locale.ROOT, "%02d", values[i]);
            fitDigits(mEventCells[i], digits.length());
            setText(mEventCells[i], digits);
        }
        String spoken = ProjectsUi.span(context, remaining);
        mEventCountdown.setContentDescription(live ? context.getString(R.string.projects_event_ends_in, spoken) : spoken);
    }

    /**
     * Sizes a countdown cell for {@code digits} tabular digits, so the value that changes every
     * second only redraws the cell instead of measuring the whole menu again (a wrap_content
     * TextView asks for a new layout on every text change).
     */
    private static void fitDigits(TextView cell, int digits) {
        Object sized = cell.getTag(R.id.projects_countdown_digits);
        if (sized instanceof Integer && (Integer) sized == digits) return;
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < digits; i++) sample.append('0');
        cell.setWidth((int) Math.ceil(cell.getPaint().measureText(sample.toString()))
                + cell.getCompoundPaddingLeft() + cell.getCompoundPaddingRight());
        cell.setTag(R.id.projects_countdown_digits, digits);
    }

    private void bindPatch(final FragmentActivity activity, final Snapshot snapshot, @Nullable final NewsFeed.NewsItem notes,
                           @Nullable ModsSummary pack, long now) {
        List<String> lines;
        if (notes != null) {
            setText(mPatchMeta, ProjectsUi.relative(notes.publishedAtMillis(), now));
            setText(mPatchTitle, notes.title);
            show(mPatchTitle, true);
            show(mPatchCaret, true);
            lines = NewsText.newsHighlights(notes.body, notes.summary, 2);
            mPatch.setClickable(true);
            mPatch.setFocusable(true);
            mPatch.setOnClickListener(v -> ProjectsUi.showArticle(activity, snapshot, notes));
        } else {
            String name = pack.packName == null ? "" : pack.packName;
            String version = pack.packVersion == null ? "" : pack.packVersion;
            setText(mPatchMeta, activity.getString(R.string.projects_pack_version, name, version).trim());
            show(mPatchTitle, false);
            show(mPatchCaret, false);
            lines = NewsText.highlights(pack.changelog, 4);
            mPatch.setOnClickListener(null);
            mPatch.setClickable(false);
            mPatch.setFocusable(false);
        }
        String joined = TextUtils.join("\n", lines);
        if (joined.equals(mPatchLinesShown)) return;
        mPatchLinesShown = joined;
        mPatchLines.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (String line : lines) {
            View row = inflater.inflate(R.layout.item_projects_patch_line, mPatchLines, false);
            ((TextView) row.findViewById(R.id.projects_patch_line_text)).setText(line);
            mPatchLines.addView(row);
        }
        show(mPatchLines, !lines.isEmpty());
    }

    private void scheduleTick(long now) {
        mEvent.removeCallbacks(mTick);
        HomeContent.Event event = mEventBound;
        if (!mResumed || event == null) return;
        long target = event.isLive(now) ? event.endsAt : event.startsAt;
        long delay = event.isLive(now) && event.openEnded ? 30_000 : ((target - now) % 1000 + 1000) % 1000 + 20;
        long boundary = HomeContent.nextBoundary(mHome == null ? new ArrayList<HomeContent.Event>() : mHome.events, now);
        if (boundary > 0) delay = Math.min(delay, Math.max(20, boundary - now + 20));
        mEvent.postDelayed(mTick, delay);
    }

    /** Every second: new digits; when an event starts or ends, the card re-orders itself on time. */
    private void tick() {
        if (!mFragment.isAdded() || mHome == null) return;
        long now = System.currentTimeMillis();
        List<HomeContent.Event> order = HomeContent.cardEvents(mHome.events, now);
        HomeContent.Event first = order.isEmpty() ? null : order.get(0);
        String shown = first == null ? null : first.id + '\n' + first.isLive(now) + '\n' + first.title;
        if (order.size() != mEventCount || (shown == null ? mEventShown != null : !shown.equals(mEventShown))) {
            render(now);
            return;
        }
        updateCountdown(mFragment.requireContext(), now);
        scheduleTick(now);
    }

    private void openEvent() {
        HomeContent.Event event = mEventBound;
        FragmentActivity activity = mFragment.getActivity();
        if (event == null || activity == null || mHome == null) return;
        ProjectsUi.openEvent(activity, mSnapshot, mHome, event);
    }

    private void showOtherEvents() {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null || mHome == null || mHome.events.size() < 2) return;
        long now = System.currentTimeMillis();
        List<HomeContent.Event> order = HomeContent.cardEvents(mHome.events, now);
        if (order.size() < 2) return;
        ProjectsUi.showOtherEvents(activity, mSnapshot, mHome, order.subList(1, order.size()), now);
    }

    private void showSecondary(int label, View.OnClickListener listener) {
        setText(mSecondaryButton, mSecondaryButton.getContext().getString(label));
        mSecondaryButton.setOnClickListener(listener);
        show(mSecondaryButton, true);
    }

    private void openAllNews() {
        Tools.swapFragment(mFragment.requireActivity(), ProjectsNewsFragment.class, ProjectsNewsFragment.TAG, null);
    }

    private void openMods() {
        Tools.swapFragment(mFragment.requireActivity(), ProjectsModsFragment.class, ProjectsModsFragment.TAG, null);
    }

    /** The "Modlar" row: shown while a pack is published, with the number of mods on and "Yeni". */
    private void refreshModsRow(@Nullable Snapshot snapshot) {
        boolean published = snapshot != null && snapshot.index != null && snapshot.index.packs != null
                && !snapshot.index.packs.isEmpty();
        final int token = ++mModsToken;
        if (!published) {
            mModsRow.setVisibility(View.GONE);
            mModsSummary = null;
            return;
        }
        if (mModsRow.getVisibility() != View.VISIBLE) {
            mModsCount.setText(null);
            mModsBadge.setVisibility(View.GONE);
            mModsRow.setVisibility(View.VISIBLE);
        }
        ModsSummary.load(mFragment.requireContext(), snapshot, summary -> {
            if (token != mModsToken || !mFragment.isAdded()) return;
            String before = mModsSummary == null ? null : mModsSummary.changelog;
            mModsSummary = summary;
            String after = summary == null ? null : summary.changelog;
            // The patch notes tile falls back to the pack's changelog.
            if (before == null ? after != null : !before.equals(after)) render(System.currentTimeMillis());
            if (summary != null && !summary.hasItems) {
                mModsRow.setVisibility(View.GONE);
                return;
            }
            if (summary == null || summary.enabled < 0) {
                mModsCount.setText(null);
                mModsBadge.setVisibility(View.GONE);
                return;
            }
            mModsCount.setText(mFragment.getResources().getQuantityString(R.plurals.projects_mods_on_count, summary.enabled, summary.enabled));
            mModsBadge.setVisibility(summary.newItems > 0 ? View.VISIBLE : View.GONE);
        });
    }

    /** One short line for what the sync before a launch adopted or could not do. */
    @Nullable
    static String noticeText(android.content.Context context, @Nullable SyncNotice notice) {
        if (notice == null) return null;
        if (notice.droppedMinecraft != null) return context.getString(R.string.projects_notice_dropped, notice.droppedMinecraft);
        List<String> parts = new ArrayList<>();
        if (notice.changes.size() == 1) {
            SyncNotice.Change change = notice.changes.get(0);
            if (!change.requiredBy.isEmpty()) {
                parts.add(context.getString(R.string.projects_notice_required, change.title, join(change.requiredBy)));
            } else if (PackState.MANUAL_REMOVED.equals(change.change)) {
                parts.add(context.getString(R.string.projects_notice_removed, change.title));
            } else if (PackState.MANUAL_DISABLED.equals(change.change)) {
                parts.add(context.getString(R.string.projects_notice_disabled, change.title));
            } else if (PackState.MANUAL_ENABLED.equals(change.change)) {
                parts.add(context.getString(R.string.projects_notice_enabled, change.title));
            } else if (PackState.MANUAL_REPLACED.equals(change.change)) {
                parts.add(context.getString(R.string.projects_notice_replaced, change.title));
            }
        } else if (notice.changes.size() > 1) {
            parts.add(context.getResources().getQuantityString(R.plurals.projects_notice_many, notice.changes.size(), notice.changes.size()));
        }
        if (notice.reset) parts.add(context.getString(R.string.projects_notice_reset));
        if (notice.issues > 0) parts.add(context.getString(R.string.projects_notice_issues));
        return parts.isEmpty() ? null : join(parts, " ");
    }

    private static String join(List<String> parts) {
        return join(parts, ", ");
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(separator);
            out.append(part);
        }
        return out.toString();
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
        if (PojavProfile.getCurrentProfileContent(activity, null) == null) {
            // No account yet: ask for one first, as Play does, instead of downloading the pack
            // and asking afterwards. Any account will do, an offline one too.
            Toast.makeText(activity, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return;
        }
        final WeakReference<ProjectsHomeCard> self = new WeakReference<>(this);
        boolean started = ProjectsJoin.start(activity, new ProjectsJoin.Callback() {
            @Override
            public void onReady(String profileKey, @Nullable SyncNotice notice) {
                ProjectsHomeCard card = self.get();
                if (card == null) return;
                FragmentActivity a = activityOrNull(self);
                String text = a == null ? null : noticeText(a, notice);
                if (text != null) Toast.makeText(a, text, Toast.LENGTH_LONG).show();
                card.launch(profileKey);
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
}
