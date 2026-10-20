package gg.projects.mobile.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.DefaultVersion;
import gg.projects.mobile.content.FeedStatus;
import gg.projects.mobile.content.HomeContent;
import gg.projects.mobile.content.LauncherConfig;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.NewsSeen;
import gg.projects.mobile.content.NewsText;
import gg.projects.mobile.content.PackState;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.controls.KeyFix;
import gg.projects.mobile.controls.KeyFixer;
import gg.projects.mobile.join.DefaultProfiles;
import gg.projects.mobile.join.JoinOutcome;
import gg.projects.mobile.join.ProjectsJoin;
import gg.projects.mobile.join.ProjectsProfile;
import gg.projects.mobile.join.SyncNotice;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.mods.ModsSummary;
import gg.projects.mobile.mods.ProjectsModsFragment;
import gg.projects.mobile.reminders.EventReminders;
import gg.projects.mobile.reminders.Reminders;

/**
 * The Projects card at the top of the main menu: the notice (Studio's maintenance, else an active
 * problem on the status page), the latest news (Studio's signed content and the Discord bot's
 * feed, merged as on the desktop), the event card with its countdown and "Hatırlat" bell, the
 * newest patch notes, the "Modlar" row and the "Projects'e katıl" action. A small gold dot marks
 * news the player has not seen yet.
 *
 * <p>Re-binding with the same content changes nothing on screen. When something changes, sections
 * that come or go fade in place while the rest glides, and a block that shows something else
 * cross-fades in place ({@link CardMotion}); with animations off everything just appears.
 *
 * <p>M17: the story falls back to Minecraft's own newest update when there is no Projects story
 * (never the patch notes tile or the event card, never a "new" dot); a live event on the server
 * offers "Katıl" where the bell goes; an options.txt saved before 0.1.6 with the mods' clashing keys
 * gets a one-tap fix, offered once ({@link KeyFixer}), with "Geri al" ({@link UndoBar}); and the
 * card is the home screen of {@link JoinOutcome}: a start that ended while the player was away
 * shows its failure, or Play ready, when the player is back.
 */
public final class ProjectsHomeCard implements ProjectsContent.Listener, ProjectsUi.BellHandler, JoinOutcome.Home {
    /** M17: the key fix was offered on the home card once (fixed, set aside, or seen before a game). */
    private static final String KEY_FIX_OFFERED = "projects_keyfix_offered";
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    /** M17: a fix made while no home screen was there to show it; the next one shows it with "Geri al". */
    @Nullable
    private static KeyFixer.Applied sUnshownFix;
    private static final int SKELETON = 0;
    private static final int CONTENT = 1;
    private static final int MESSAGE = 2;

    /** While a status notice is on screen the card asks again at most this often... */
    private static final long STATUS_RECHECK_MS = 5 * 60_000L;
    /** ...and once more this long before it would expire (the bot renews it while the problem lasts)... */
    private static final long STATUS_RECHECK_BEFORE_EXPIRY_MS = 60_000L;
    /** ...but never sooner than this (the content is cached for a minute). */
    private static final long STATUS_RECHECK_MIN_MS = 60_000L;
    private static final long DOT_FADE_MS = 200;

    private final Fragment mFragment;
    private final mcVersionSpinner mVersionSpinner;
    private final View mPlayButton;
    @Nullable
    private final ActivityResultLauncher<String> mNotificationPermission;

    private final ViewGroup mSceneRoot;
    private final TextView mStatus;
    private final View mNotice;
    private final ImageView mNoticeIcon;
    private final TextView mNoticeTitle;
    private final TextView mNoticeMessage;
    private final TextView mNoticeLink;
    private final View mSkeleton;
    private final View mNews;
    private final CoverView mNewsCover;
    private final TextView mNewsCategory;
    private final View mNewsDot;
    private final TextView mNewsDate;
    private final TextView mNewsTitle;
    private final TextView mNewsSummary;
    private final View mEvent;
    private final TextView mEventLabel;
    private final TextView mEventMore;
    private final ImageView mEventCaret;
    private final TextView mEventTitle;
    private final TextView mEventWhen;
    private final View mEventCountdown;
    private final TextView[] mEventCells;
    private final TextView mEventLeft;
    private final TextView mEventStarted;
    private final ReminderBellView mEventBell;
    private final View mPatch;
    private final View mPatchDot;
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
    private final Drawable mAllNewsDot;
    // M17: the key fix's offer, the event's "Katıl" and the undo bar over the play bar.
    private final View mKeyFix;
    private final Button mKeyFixButton;
    private final View mKeyFixClose;
    private final View mEventActions;
    private final View mEventGap;
    private final Button mEventJoin;
    @Nullable
    private final UndoBar mUndoBar;
    /** M17: what the key fix would change now, while the card offers it (null: no offer). */
    @Nullable
    private List<KeyFix.Change> mKeyFixPlan;
    private int mKeyFixToken;

    private Snapshot mSnapshot;
    private HomeContent mHome;
    private boolean mRefreshing;
    private int mModsToken;
    @Nullable
    private ModsSummary mModsSummary;
    private boolean mResumed;

    /** What is on screen: the card's structure and each block's identity. */
    private String mStructure;
    private String mNoticeShown;
    private String mNewsShown;
    private String mEventShown;
    private String mPatchShown;
    @Nullable
    private HomeContent.Event mEventBound;
    private int mEventCount;
    private String mPatchLinesShown;
    /** The local day the event's start moment was last written for ("Yarın" becomes "Bugün" at midnight). */
    private long mEventWhenDay = Long.MIN_VALUE;
    /** The notice's backing drawable (the layout starts with the maintenance one). */
    private int mNoticeBackground = R.drawable.projects_maintenance_background;
    /** The news whose dot is on screen (shown, or faded out while it keeps its room). */
    @Nullable
    private String mNewsDotItem;
    @Nullable
    private String mPatchDotItem;
    /** The recommended Minecraft version the default profile was last checked against. */
    @Nullable
    private String mRecommendedChecked;
    /** A bell that waits for the notification permission. */
    @Nullable
    private PendingBell mPendingBell;

    private final Runnable mTick = this::tick;
    private final Runnable mStatusExpiry = () -> render(System.currentTimeMillis());
    private final Runnable mStatusRecheck = this::recheckStatus;
    private final Runnable mSeenChanged = this::onSeenChanged;

    /** Keeps the join button in its "preparing" state until the running join finishes. */
    private final Runnable mBusyWatcher = new Runnable() {
        @Override
        public void run() {
            if (ProjectsJoin.isBusy()) mJoinButton.postDelayed(this, 400);
            else setJoining(false);
        }
    };

    /** Turns the join button to "Hazırlanıyor" once the sheen of the accepted press has crossed it. */
    private final Runnable mShowJoining = () -> {
        if (ProjectsJoin.isBusy()) setJoining(true);
    };

    private static final class PendingBell {
        final ReminderBellView bell;
        final HomeContent.Event event;

        PendingBell(ReminderBellView bell, HomeContent.Event event) {
            this.bell = bell;
            this.event = event;
        }
    }

    public ProjectsHomeCard(Fragment fragment, View root, mcVersionSpinner versionSpinner, View playButton,
                            @Nullable ActivityResultLauncher<String> notificationPermission) {
        mFragment = fragment;
        mVersionSpinner = versionSpinner;
        mPlayButton = playButton;
        mNotificationPermission = notificationPermission;
        View card = root.findViewById(R.id.projects_card);
        mSceneRoot = card.getParent() instanceof ViewGroup ? (ViewGroup) card.getParent() : (ViewGroup) card;
        mStatus = root.findViewById(R.id.projects_status);
        mNotice = root.findViewById(R.id.projects_maintenance);
        mNoticeIcon = root.findViewById(R.id.projects_maintenance_icon);
        mNoticeTitle = root.findViewById(R.id.projects_maintenance_title);
        mNoticeMessage = root.findViewById(R.id.projects_maintenance_message);
        mNoticeLink = root.findViewById(R.id.projects_maintenance_link);
        mSkeleton = root.findViewById(R.id.projects_skeleton);
        mNews = root.findViewById(R.id.projects_news);
        mNewsCover = root.findViewById(R.id.projects_news_cover);
        mNewsCategory = root.findViewById(R.id.projects_news_category);
        mNewsDot = root.findViewById(R.id.projects_news_dot);
        mNewsDate = root.findViewById(R.id.projects_news_date);
        mNewsTitle = root.findViewById(R.id.projects_news_title);
        mNewsSummary = root.findViewById(R.id.projects_news_summary);
        mEvent = root.findViewById(R.id.projects_event);
        mEventLabel = root.findViewById(R.id.projects_event_label);
        mEventMore = root.findViewById(R.id.projects_event_more);
        mEventCaret = root.findViewById(R.id.projects_event_caret);
        mEventTitle = root.findViewById(R.id.projects_event_title);
        mEventWhen = root.findViewById(R.id.projects_event_when);
        mEventCountdown = root.findViewById(R.id.projects_event_countdown);
        mEventCells = new TextView[]{root.findViewById(R.id.projects_event_days), root.findViewById(R.id.projects_event_hours),
                root.findViewById(R.id.projects_event_minutes), root.findViewById(R.id.projects_event_seconds)};
        mEventLeft = root.findViewById(R.id.projects_event_left);
        mEventStarted = root.findViewById(R.id.projects_event_started);
        mEventBell = root.findViewById(R.id.projects_event_bell);
        mPatch = root.findViewById(R.id.projects_patch);
        mPatchDot = root.findViewById(R.id.projects_patch_dot);
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
        mKeyFix = root.findViewById(R.id.projects_keyfix);
        mKeyFixButton = root.findViewById(R.id.projects_keyfix_fix);
        mKeyFixClose = root.findViewById(R.id.projects_keyfix_close);
        mEventActions = root.findViewById(R.id.projects_event_actions);
        mEventGap = root.findViewById(R.id.projects_event_gap);
        mEventJoin = root.findViewById(R.id.projects_event_join);
        mUndoBar = root instanceof androidx.constraintlayout.widget.ConstraintLayout
                ? UndoBar.attach((androidx.constraintlayout.widget.ConstraintLayout) root, R.id._background_display_view) : null;

        Context context = root.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        mNewsCover.setMaxCoverHeight(Math.round(200 * density));
        // The bell's box ends at the block's edge (its row reaches into the padding); the icon moves
        // 5 dp toward it, under the caret above (both centred 9 dp from the content's end).
        mEventBell.setIconShift(5 * density);
        // M14: "Projects'e katıl" is the gold face (without OYNA's socket); every tappable part of the
        // card presses (rows and blocks barely move, buttons sink to 96%) with a haptic.
        PlayButtons.decorateJoin(mJoinButton);
        mSecondaryButton.setBackground(ChamferDrawable.secondary(context));
        Press.attach(mModsRow, Press.Style.ROW);
        Press.attach(mNews, Press.Style.CARD);
        Press.attach(mEvent, Press.Style.CARD);
        Press.attach(mPatch, Press.Style.CARD);
        Press.attach(mNotice, Press.Style.CARD);
        Press.attach(mEventMore, Press.Style.BUTTON);
        Press.attach(mEventBell, Press.Style.ICON);
        mAllNewsDot = ContextCompat.getDrawable(context, R.drawable.projects_news_dot);
        // M17: the offer's button and the event's "Katıl" are quiet outlined actions inside raised
        // blocks (OYNA stays the one loud thing); they press like every button.
        mKeyFixButton.setBackground(ChamferDrawable.outlined(context));
        Press.attach(mKeyFixButton, Press.Style.BUTTON);
        Press.attach(mKeyFixClose, Press.Style.ICON);
        mEventJoin.setBackground(ChamferDrawable.outlined(context));
        Press.attach(mEventJoin, Press.Style.BUTTON);
        mKeyFixButton.setOnClickListener(v -> fixKeys());
        mKeyFixClose.setOnClickListener(v -> setKeyFixAside());
        mEventJoin.setOnClickListener(v -> join(mEventJoin));
        mJoinButton.setOnClickListener(v -> join(mJoinButton));
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
        // Shown afresh: a dot that went out since (the news list was visited) leaves no gap.
        mNewsDotItem = null;
        mPatchDotItem = null;
        NewsSeenStore.addListener(mSeenChanged);
        ProjectsContent.addListener(this);
        ProjectsContent.refresh(mFragment.requireContext(), false);
        setJoining(ProjectsJoin.isBusy());
        // A reminder whose moment came while notifications were off is told now.
        Reminders.tellInApp(mFragment.requireContext());
        // M17: a start that ended while the player was away says how it went; a key fix asked for
        // in the game is made now that the game is closed, or offered once.
        JoinOutcome.homeResumed(mFragment.requireContext(), this);
        checkKeyFix();
    }

    /** An article was opened somewhere: its dot goes out. */
    private void onSeenChanged() {
        if (mFragment.isAdded()) render(System.currentTimeMillis());
    }

    public void onPause() {
        mResumed = false;
        JoinOutcome.homePaused(this);
        NewsSeenStore.removeListener(mSeenChanged);
        ProjectsContent.removeListener(this);
        mJoinButton.removeCallbacks(mBusyWatcher);
        mJoinButton.removeCallbacks(mShowJoining);
        mEvent.removeCallbacks(mTick);
        mNotice.removeCallbacks(mStatusExpiry);
        mNotice.removeCallbacks(mStatusRecheck);
    }

    private void setJoining(boolean joining) {
        mJoinButton.removeCallbacks(mShowJoining);
        mJoinButton.removeCallbacks(mBusyWatcher);
        mJoinButton.setEnabled(!joining);
        mJoinButton.setText(joining ? R.string.projects_join_preparing : R.string.projects_join);
        // M17: "Katıl" waits too, and its disabled look fades (150 ms) instead of snapping.
        if (mEventJoin.isEnabled() == joining) {
            mEventJoin.setEnabled(!joining);
            float alpha = joining ? 0.45f : 1f;
            if (Motion.enabled(mEventJoin.getContext()) && mEventJoin.isShown()) {
                mEventJoin.animate().alpha(alpha).setDuration(150).setInterpolator(Motion.EASE_OUT).start();
            } else {
                mEventJoin.animate().cancel();
                mEventJoin.setAlpha(alpha);
            }
        }
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
        long now = System.currentTimeMillis();
        // Reminders follow events Studio moved or hid.
        Reminders.onContent(mFragment.requireContext(), snapshot, now);
        render(now);
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
        // Studio's maintenance notice wins; the status notice shows only while it has not expired.
        FeedStatus feedStatus = maintenanceOn || home == null ? null : home.status;
        String offline = ProjectsUi.offlineSince(activity, snapshot);

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
            // M17: without a Projects story, Minecraft's newest update fills the place (never the
            // patch notes tile or the event card; content-format.md "Minecraft news").
            if (top == null && !home.minecraft.isEmpty()) top = ProjectsUi.localized(activity, home.minecraft.get(0));
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
        String noticeShown = maintenanceOn ? "maintenance\n" + maintenance.title + '\n' + maintenance.message
                : feedStatus != null ? "status\n" + feedStatus.level + '\n' + feedStatus.title + '\n' + feedStatus.message
                + '\n' + (feedStatus.url != null) : null;
        String newsShown = top == null ? null : top.id + '\n' + top.title + '\n' + top.summary + '\n'
                + top.publishedAtMillis() + '\n' + coverKey(top, showsCover(top)) + '\n' + top.isMinecraft();
        String eventShown = event == null ? null : eventKey(event, now);
        String patchShown = notes != null ? "news\n" + notes.id + '\n' + notes.title
                : pack != null ? "pack\n" + pack.packName + '\n' + pack.packVersion + '\n' + pack.changelog : null;
        boolean news = state == CONTENT && top != null;
        boolean allNews = state == SKELETON || (home != null && (!home.news.isEmpty() || !home.minecraft.isEmpty()));
        boolean keyFix = mKeyFixPlan != null && !mKeyFixPlan.isEmpty();
        String structure = state + "|" + noticeShown + "|" + (offline != null) + "|" + messageTitle + "|" + retry + "|" + allNews
                + "|" + keyFix
                + "|" + (news ? newsShown : "") + "|" + (state == CONTENT ? eventShown + "|" + others + "|" + eventOpens + "|" + patchShown : "");

        boolean changed = mStructure != null && !structure.equals(mStructure);
        if (changed && CardMotion.canAnimate(mSceneRoot)) {
            // Blocks that stay but show something else cross-fade in place.
            if (noticeShown != null && mNotice.getVisibility() == View.VISIBLE && mNoticeShown != null
                    && !mNoticeShown.equals(noticeShown)) {
                CardMotion.crossfade(mNotice);
            }
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

        // The notice and the offline line.
        if (maintenanceOn) bindMaintenance(activity, maintenance);
        else if (feedStatus != null) bindStatus(activity, feedStatus);
        show(mNotice, noticeShown != null);
        mNoticeShown = noticeShown;
        scheduleStatus(feedStatus, now);
        if (offline != null) setText(mStatus, offline);
        show(mStatus, offline != null);

        show(mSkeleton, state == SKELETON);
        show(mMessage, state == MESSAGE);
        // M17: the key fix's offer, whatever the news says.
        show(mKeyFix, keyFix);
        if (!keyFix) mKeyFixButton.setEnabled(true);
        if (state == MESSAGE) {
            setText(mMessageTitle, activity.getString(messageTitle));
            setText(mMessageBody, activity.getString(messageBody));
        }

        NewsSeen seen = NewsSeenStore.get(activity);

        // The latest story.
        show(mNews, news);
        if (news) bindNews(activity, snapshot, top);
        mNewsShown = news ? newsShown : null;
        mNewsDotItem = bindDot(mNewsDot, mNewsDotItem, news ? top.id : null, news && seen.isNew(top, now));

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
        boolean notesShown = patchVisible && notes != null;
        mPatchDotItem = bindDot(mPatchDot, mPatchDotItem, notesShown ? notes.id : null, notesShown && seen.isNew(notes, now));

        if (retry) {
            showSecondary(R.string.projects_retry, v -> ProjectsContent.refresh(mFragment.requireContext(), true), false);
        } else if (allNews) {
            // The dot when news the card does not show is new.
            boolean unseen = false;
            if (home != null) {
                for (NewsFeed.NewsItem item : home.news) {
                    boolean onCard = (news && item.id.equals(top.id)) || (notesShown && item.id.equals(notes.id));
                    if (!onCard && seen.isNew(item, now)) {
                        unseen = true;
                        break;
                    }
                }
            }
            showSecondary(R.string.projects_all_news, v -> openAllNews(), unseen);
        } else {
            show(mSecondaryButton, false);
        }
        scheduleTick(now);
    }

    /**
     * Shows {@code dot} for {@code item} while it is new. A dot that goes out while its item stays
     * on screen (the article was opened) fades and keeps its room, so the date beside it does not
     * move; anything else just hides it. Returns the item whose dot is on screen.
     */
    @Nullable
    private static String bindDot(View dot, @Nullable String shownFor, @Nullable String item, boolean isNew) {
        if (isNew) {
            dot.animate().cancel();
            dot.setAlpha(1f);
            dot.setVisibility(View.VISIBLE);
            return item;
        }
        if (item != null && item.equals(shownFor) && dot.getVisibility() != View.GONE) {
            if (dot.getVisibility() == View.VISIBLE && dot.getAlpha() > 0f) {
                if (Motion.enabled(dot.getContext()) && dot.isAttachedToWindow()) {
                    dot.animate().alpha(0f).setDuration(DOT_FADE_MS).setInterpolator(Motion.EASE_OUT)
                            .withEndAction(() -> dot.setVisibility(View.INVISIBLE)).start();
                } else {
                    dot.setVisibility(View.INVISIBLE);
                }
            }
            return item;
        }
        dot.animate().cancel();
        dot.setAlpha(1f);
        dot.setVisibility(View.GONE);
        return null;
    }

    /** Swaps the notice's backing only when it changes (a new drawable would restart its ripple). */
    private void setNoticeBackground(int drawable) {
        if (mNoticeBackground == drawable) return;
        mNoticeBackground = drawable;
        mNotice.setBackgroundResource(drawable);
    }

    private void bindMaintenance(Context context, LauncherConfig.Maintenance maintenance) {
        setNoticeBackground(R.drawable.projects_maintenance_background);
        mNoticeIcon.setImageResource(R.drawable.projects_ic_wrench);
        mNoticeIcon.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_gold_300));
        setText(mNoticeTitle, isBlank(maintenance.title) ? context.getString(R.string.projects_maintenance_title) : maintenance.title.trim());
        setText(mNoticeMessage, isBlank(maintenance.message) ? "" : maintenance.message.trim());
        show(mNoticeMessage, !isBlank(maintenance.message));
        show(mNoticeLink, false);
        mNotice.setOnClickListener(null);
        mNotice.setClickable(false);
        mNotice.setFocusable(false);
    }

    /** The status page's active problem: an outage in the error colour with a faint ring, else gold. */
    private void bindStatus(final FragmentActivity activity, final FeedStatus status) {
        boolean outage = status.level == FeedStatus.Level.OUTAGE;
        setNoticeBackground(outage ? R.drawable.projects_notice_outage_background : R.drawable.projects_maintenance_background);
        mNoticeIcon.setImageResource(outage ? R.drawable.projects_ic_warning_octagon
                : status.level == FeedStatus.Level.DEGRADED ? R.drawable.projects_ic_warning : R.drawable.projects_ic_megaphone);
        mNoticeIcon.setImageTintList(ContextCompat.getColorStateList(activity,
                outage ? R.color.projects_flare_400 : R.color.projects_gold_300));
        setText(mNoticeTitle, status.title.trim());
        setText(mNoticeMessage, status.message.trim());
        show(mNoticeMessage, !isBlank(status.message));
        final String url = status.url;
        boolean link = url != null;
        show(mNoticeLink, link);
        mNotice.setClickable(link);
        mNotice.setFocusable(link);
        mNotice.setOnClickListener(link ? v -> ProjectsUi.openUrl(activity, url) : null);
        if (!link) mNotice.setClickable(false);
    }

    /**
     * While a status notice is on screen: it leaves at its {@code expiresAt} without waiting for a
     * refresh, and the card asks the servers again now and then and shortly before it expires, so it
     * goes soon after the problem is over and stays while the bot keeps renewing it.
     */
    private void scheduleStatus(@Nullable FeedStatus status, long now) {
        mNotice.removeCallbacks(mStatusExpiry);
        mNotice.removeCallbacks(mStatusRecheck);
        if (status == null || !mResumed) return;
        mNotice.postDelayed(mStatusExpiry, Math.max(0, status.expiresAt - now) + 50);
        mNotice.postDelayed(mStatusRecheck, recheckDelay(status, now));
    }

    private static long recheckDelay(FeedStatus status, long now) {
        long beforeExpiry = status.expiresAt - STATUS_RECHECK_BEFORE_EXPIRY_MS - now;
        return Math.max(STATUS_RECHECK_MIN_MS, Math.min(beforeExpiry, STATUS_RECHECK_MS));
    }

    private void recheckStatus() {
        if (!mResumed || !mFragment.isAdded()) return;
        ProjectsContent.refresh(mFragment.requireContext(), true);
        FeedStatus status = mHome == null ? null : mHome.status;
        if (status != null) mNotice.postDelayed(mStatusRecheck, recheckDelay(status, System.currentTimeMillis()));
    }

    /** What the event block shows: a change cross-fades it (it went live, "Katıl" came or went). */
    private static String eventKey(HomeContent.Event event, long now) {
        return event.id + '\n' + event.isLive(now) + '\n' + event.title + '\n' + event.offersJoin(now);
    }

    private void bindNews(final FragmentActivity activity, final Snapshot snapshot, final NewsFeed.NewsItem item) {
        ProjectsUi.bindCategory(mNewsCategory, item);
        setText(mNewsDate, ProjectsUi.date(activity, item.publishedAtMillis()));
        setText(mNewsTitle, item.title);
        setText(mNewsSummary, item.summary);
        show(mNewsSummary, !item.summary.isEmpty());
        mNewsCover.bind(snapshot, item, showsCover(item));
        mNews.setOnClickListener(v -> ProjectsUi.showArticle(activity, snapshot, item));
    }

    private void bindEvent(Context context, final HomeContent.Event event, boolean live, int others, boolean opens, long now) {
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
        // M17: a live event on the server offers "Katıl" in the bell's place (never before it starts).
        boolean joinable = event.offersJoin(now);
        show(mEventCountdown, !started);
        show(mEventStarted, started);
        show(mEventGap, !started);
        show(mEventLeft, live);
        show(mEventJoin, joinable);
        if (joinable) mEventJoin.setContentDescription(context.getString(R.string.projects_event_join) + ", " + event.title);
        // "X önce başladı" alone keeps its old place under the title; beside "Katıl" it centres on the button.
        ViewGroup.MarginLayoutParams actions = (ViewGroup.MarginLayoutParams) mEventActions.getLayoutParams();
        int top = Math.round((started && !joinable ? 6 : 10) * context.getResources().getDisplayMetrics().density);
        if (actions.topMargin != top) {
            actions.topMargin = top;
            mEventActions.setLayoutParams(actions);
        }
        // An upcoming event: when it starts, and the bell.
        show(mEventWhen, !live);
        show(mEventBell, !live);
        if (!live) {
            mEventWhenDay = Long.MIN_VALUE;
            boolean set = Reminders.isSet(context, event.id);
            if (mEventBell.isSet() != set) mEventBell.setSet(set, false);
            mEventBell.setOnClickListener(v -> onBell(mEventBell, event));
        }
        updateCountdown(context, now);
    }

    /** The local day of {@code millis} (after 1970), to notice midnight cheaply. */
    private static long localDay(long millis) {
        return (millis + TimeZone.getDefault().getOffset(millis)) / 86_400_000L;
    }

    /** The countdown digits (or "X önce başladı"), updated every second while the card is shown. */
    private void updateCountdown(Context context, long now) {
        HomeContent.Event event = mEventBound;
        if (event == null) return;
        boolean live = event.isLive(now);
        if (!live) {
            long day = localDay(now);
            if (day != mEventWhenDay) {
                mEventWhenDay = day;
                setText(mEventWhen, ProjectsUi.startMoment(context, event.startsAt, now));
            }
        }
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
        String shown = first == null ? null : eventKey(first, now);
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
        ProjectsUi.showOtherEvents(activity, mSnapshot, mHome, order.subList(1, order.size()), now, this);
    }

    // ---- reminders ----

    /**
     * The "Hatırlat" bell: sets or removes the event's reminder. Setting one needs notifications:
     * on Android 13 and newer the permission is asked for now (never before a bell is tapped), and
     * when notifications are off for good the player is offered the settings instead.
     */
    @Override
    public void onBell(ReminderBellView bell, HomeContent.Event event) {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null) return;
        if (Reminders.isSet(activity, event.id)) {
            Reminders.toggle(activity, event);
            bell.setSet(false, true);
            return;
        }
        Reminders.ensureChannel(activity);
        if (Reminders.canNotify(activity)) {
            setReminder(activity, bell, event);
        } else if (Build.VERSION.SDK_INT >= 33 && mNotificationPermission != null
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mPendingBell = new PendingBell(bell, event);
            mNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            showNotificationsOff(activity);
        }
    }

    /** The answer to the notification permission asked for by {@link #onBell}. */
    public void onNotificationPermission(boolean granted) {
        PendingBell pending = mPendingBell;
        mPendingBell = null;
        FragmentActivity activity = mFragment.getActivity();
        if (pending == null || activity == null || !mFragment.isAdded()) return;
        if (granted && Reminders.canNotify(activity)) {
            setReminder(activity, pending.bell, pending.event);
        } else if (granted || !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
            // Android will not ask again (or only this app's reminders are off): the settings can.
            showNotificationsOff(activity);
        } else {
            Toast.makeText(activity, R.string.projects_reminder_needs_notifications, Toast.LENGTH_SHORT).show();
        }
    }

    private void setReminder(final FragmentActivity activity, final ReminderBellView bell, HomeContent.Event event) {
        if (!Reminders.toggle(activity, event)) return;
        bell.setSet(true, true);
        if (EventReminders.ringsAt(event.startsAt) <= System.currentTimeMillis()) {
            // Less than five minutes left: the reminder itself comes at once.
            Reminders.tellInApp(activity);
        } else if (Reminders.takeFirstExplanation(activity)) {
            // A beat after the bell, so the swing is not held back by the message appearing.
            bell.postDelayed(() -> {
                if (mFragment.isAdded()) Toast.makeText(activity, R.string.projects_reminder_explained, Toast.LENGTH_LONG).show();
            }, 180);
        }
    }

    private static void showNotificationsOff(final FragmentActivity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_reminder_notifications_off_title)
                .setMessage(R.string.projects_reminder_notifications_off)
                .setPositiveButton(R.string.projects_open_settings, (d, w) -> openNotificationSettings(activity))
                .setNegativeButton(R.string.projects_not_now, null)
                .show();
    }

    /** The reminder channel's settings when only it is off, else the app's notification settings. */
    private static void openNotificationSettings(FragmentActivity activity) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) {
            boolean channel = Reminders.channelBlocked(activity);
            intent = new Intent(channel ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            if (channel) intent.putExtra(Settings.EXTRA_CHANNEL_ID, "projects_event_reminders");
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null));
        }
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.getPackageName(), null)));
        }
    }

    // ---- actions ----

    private void showSecondary(int label, View.OnClickListener listener, boolean dot) {
        Context context = mSecondaryButton.getContext();
        setText(mSecondaryButton, context.getString(label));
        mSecondaryButton.setOnClickListener(listener);
        Drawable[] current = mSecondaryButton.getCompoundDrawablesRelative();
        if ((current[0] != null) != dot) {
            mSecondaryButton.setCompoundDrawablesRelativeWithIntrinsicBounds(dot ? mAllNewsDot : null, null, null, null);
            mSecondaryButton.setCompoundDrawablePadding(dot ? Math.round(7 * context.getResources().getDisplayMetrics().density) : 0);
        }
        mSecondaryButton.setContentDescription(dot ? context.getString(R.string.projects_all_news_new) : null);
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
            mModsBadge.setVisibility(summary.hasNews() ? View.VISIBLE : View.GONE);
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

    /** "Projects'e katıl" without a tap: "Tekrar başlat" after Sodium kept the game from starting. */
    public void joinNow() {
        join(mJoinButton);
    }

    /**
     * M16: OYNA on the Projects profile. It goes through the join, so the pack is installed and in
     * step before the game starts (before, OYNA on a profile that had never joined started plain
     * vanilla). {@code playButton} gets the press's answer, the card's button shows the join running.
     */
    public void playThroughJoin(View playButton) {
        join(playButton);
    }

    private void join(View pressed) {
        FragmentActivity activity = mFragment.requireActivity();
        // M14: the press confirms when the join starts and rejects (a short shake) when it cannot.
        if (ProjectsJoin.isBusy()) {
            PlayButtons.reject(pressed);
            Toast.makeText(activity, R.string.projects_join_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (ProgressLayout.hasProcesses()) {
            PlayButtons.reject(pressed);
            Toast.makeText(activity, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        if (PojavProfile.getCurrentProfileContent(activity, null) == null) {
            // No account yet: ask for one first, as Play does, instead of downloading the pack
            // and asking afterwards. Any account will do, an offline one too.
            PlayButtons.reject(pressed);
            Toast.makeText(activity, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return;
        }
        // M17: the offer of the key fix was seen before a game; it does not come back.
        if (mKeyFix.getVisibility() == View.VISIBLE) markKeyFixOffered();
        // M17: the outcome goes to whichever home screen is up when it comes (JoinOutcome): the game
        // starts only while the player is on it; away, a notification tells how it went.
        final Context app = activity.getApplicationContext();
        boolean started = ProjectsJoin.start(activity, new ProjectsJoin.Callback() {
            @Override
            public void onReady(String profileKey, @Nullable SyncNotice notice) {
                JoinOutcome.ready(app, profileKey, notice);
            }

            @Override
            public void onFinishLoaderInstall() {
                JoinOutcome.finishLoader(app);
            }

            @Override
            public void onFailed(boolean network, String details) {
                JoinOutcome.failed(app, network, false, details);
            }
        });
        if (started) {
            // A disabled face draws no sheen: the button turns to "Hazırlanıyor" once it has crossed.
            // A second tap meanwhile meets the busy check above. A press on OYNA sheens OYNA, and the
            // card's button shows the join at once.
            boolean sheen = PlayButtons.confirm(pressed);
            if (sheen && pressed == mJoinButton) mJoinButton.postDelayed(mShowJoining, PlayButtonDrawable.SHEEN_MS);
            else setJoining(true);
        }
    }

    // ---- JoinOutcome.Home (M17) ----

    @Override
    public void launch(String profileKey, @Nullable SyncNotice notice) {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null || !mFragment.isAdded()) return;
        String text = noticeText(activity, notice);
        if (text != null) Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
        launch(profileKey);
    }

    @Override
    public void showFailure(JoinOutcome.Failure failure) {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null || !mFragment.isAdded()) return;
        ProjectsUi.showError(activity, R.string.projects_join_failed_title, JoinOutcome.message(failure), failure.details);
    }

    @Override
    public void showFinishLoader() {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null || !mFragment.isAdded()) return;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_join_finish_loader_title)
                .setMessage(R.string.projects_join_finish_loader)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Back after a start finished while the player was away: the Projects profile is the chosen one,
     * a short line says it is ready, and one sheen crosses Play once the screen has settled, so the
     * eye finds where to press. No haptic: nothing was pressed.
     */
    @Override
    public void showReady(@Nullable SyncNotice notice) {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null || !mFragment.isAdded()) return;
        select(ProjectsProfile.KEY);
        String text = noticeText(activity, notice);
        Toast.makeText(activity, text != null ? text : activity.getString(R.string.projects_ready_title),
                text != null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        final Context context = activity;
        mPlayButton.postDelayed(() -> {
            if (!mResumed || !mPlayButton.isShown()) return;
            if (mPlayButton.getBackground() instanceof PlayButtonDrawable) {
                ((PlayButtonDrawable) mPlayButton.getBackground()).sheen(context);
            }
        }, 420);
    }

    /** The profile the spinner shows and Play starts, written down for the game process. */
    private void select(String profileKey) {
        if (!mFragment.isAdded() || mFragment.getView() == null) return;
        mVersionSpinner.reloadProfiles();
        int index = mVersionSpinner.getProfileAdapter().resolveProfileIndex(profileKey);
        if (index >= 0) mVersionSpinner.setProfileSelection(index);
        // The game runs in another process: write the selection to disk before it starts.
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                .commit();
    }

    /**
     * Selects the Projects profile and starts it the way Play does, so upstream's launch checks all
     * apply (not through Play's click: Play itself may send the Projects profile here, REVIEW1 A2).
     */
    private void launch(String profileKey) {
        if (!mFragment.isAdded() || mFragment.getView() == null) return;
        select(profileKey);
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    // ---- the key fix (M17) ----

    /**
     * Looks at the Projects profile's options.txt off the main thread: a fix asked for in the game is
     * made now that the game is closed (with "Geri al"); otherwise the clashes are offered once.
     */
    private void checkKeyFix() {
        final Context app = mFragment.requireContext().getApplicationContext();
        final boolean offered = keyFixOffered();
        final int token = ++mKeyFixToken;
        // A fix made while no home screen could show it (the menu left meanwhile) is shown now.
        KeyFixer.Applied unshown = sUnshownFix;
        sUnshownFix = null;
        if (unshown != null) showFixed(unshown);
        KeyFixer.run(() -> {
            File gameDir = ProjectsProfile.knownGameDir();
            KeyFixer.Applied applied = null;
            List<KeyFix.Change> plan = null;
            if (!KeyFixer.gameRunning(app)) applied = KeyFixer.applyQueued(app, gameDir);
            if (applied == null && !offered && !KeyFixer.queued(app, gameDir)) {
                List<KeyFix.Change> changes = KeyFixer.plan(app, gameDir);
                if (!changes.isEmpty()) plan = changes;
            }
            final KeyFixer.Applied done = applied;
            final List<KeyFix.Change> offer = plan;
            MAIN.post(() -> {
                // The file changed: the player hears of it and gets "Geri al", here or on the next visit.
                if (done != null) {
                    markKeyFixOffered();
                    if (mResumed && mFragment.isAdded()) showFixed(done);
                    else sUnshownFix = done;
                }
                if (token != mKeyFixToken || !mFragment.isAdded()) return;
                mKeyFixPlan = offer;
                render(System.currentTimeMillis());
            });
        });
    }

    /**
     * "Tuş çakışmalarını düzelt": the offer folds away and the bar says what happened, with "Geri al".
     * While the game runs (it keeps its keys in memory) the fix waits for it to close.
     */
    private void fixKeys() {
        if (mKeyFixPlan == null) return;
        mKeyFixButton.setEnabled(false);
        final Context app = mFragment.requireContext().getApplicationContext();
        final int token = ++mKeyFixToken;
        KeyFixer.run(() -> {
            File gameDir = ProjectsProfile.knownGameDir();
            KeyFixer.Applied applied = null;
            boolean queued = false;
            boolean failed = false;
            try {
                if (KeyFixer.gameRunning(app)) {
                    KeyFixer.queue(app, gameDir);
                    queued = true;
                } else {
                    applied = KeyFixer.apply(app, gameDir);
                }
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("ProjectsKeyFix", "fix not made", e);
                failed = true;
            }
            final KeyFixer.Applied done = applied;
            final boolean waits = queued;
            final boolean broke = failed;
            MAIN.post(() -> {
                if (!mFragment.isAdded()) {
                    // Left meanwhile: the next visit shows the fix with its "Geri al".
                    if (done != null) {
                        markKeyFixOffered();
                        sUnshownFix = done;
                    }
                    return;
                }
                if (broke) {
                    // The file could not be written: the offer stays, the button can be pressed again.
                    Haptics.reject(mKeyFixButton);
                    mKeyFixButton.setEnabled(true);
                    Toast.makeText(mFragment.requireContext(), R.string.projects_keyfix_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                markKeyFixOffered();
                if (token == mKeyFixToken) {
                    mKeyFixPlan = null;
                    render(System.currentTimeMillis());
                }
                if (done != null) {
                    Haptics.confirm(mKeyFixButton);
                    showFixed(done);
                } else if (waits && mUndoBar != null) {
                    Haptics.confirm(mKeyFixButton);
                    mUndoBar.show(mFragment.getString(R.string.projects_keyfix_queued), () -> {
                        KeyFixer.run(() -> KeyFixer.unqueue(app));
                        mUndoBar.showResult(mFragment.getString(R.string.projects_keyfix_undone), R.drawable.projects_ic_keyboard);
                    });
                } else if (mUndoBar != null) {
                    // Nothing clashed any more (the keys were changed in the game meanwhile).
                    mUndoBar.show(mFragment.getString(R.string.projects_keyfix_done), null);
                }
            });
        });
    }

    /** "Tuş çakışmaları düzeltildi" with "Geri al", which puts the old keys back. */
    private void showFixed(final KeyFixer.Applied applied) {
        if (mUndoBar == null) {
            Toast.makeText(mFragment.requireContext(), R.string.projects_keyfix_done, Toast.LENGTH_SHORT).show();
            return;
        }
        final Context app = mFragment.requireContext().getApplicationContext();
        mUndoBar.show(mFragment.getString(R.string.projects_keyfix_done), () -> KeyFixer.run(() -> {
            boolean undone;
            try {
                undone = KeyFixer.undo(app, applied);
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("ProjectsKeyFix", "fix not taken back", e);
                undone = false;
            }
            final boolean back = undone;
            MAIN.post(() -> {
                if (!mFragment.isAdded()) return;
                if (back) mUndoBar.showResult(mFragment.getString(R.string.projects_keyfix_undone), R.drawable.projects_ic_keyboard);
                else mUndoBar.dismiss();
            });
        }));
    }

    /** "Şimdi değil": the offer folds away and does not come back (the MODLAR key still offers the fix). */
    private void setKeyFixAside() {
        markKeyFixOffered();
        mKeyFixToken++;
        mKeyFixPlan = null;
        render(System.currentTimeMillis());
    }

    private static boolean keyFixOffered() {
        return LauncherPreferences.DEFAULT_PREF.getBoolean(KEY_FIX_OFFERED, false);
    }

    private static void markKeyFixOffered() {
        if (!keyFixOffered()) LauncherPreferences.DEFAULT_PREF.edit().putBoolean(KEY_FIX_OFFERED, true).apply();
    }
}
