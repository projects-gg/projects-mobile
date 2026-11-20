package gg.projects.mobile.cloud.auto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudStatus;
import gg.projects.mobile.cloud.items.ItemData;
import gg.projects.mobile.cloud.ui.CloudItemArt;
import gg.projects.mobile.cloud.ui.CloudScreen;
import gg.projects.mobile.cloud.ui.CloudSettingsFragment;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ui.WrapLayout;
import gg.projects.mobile.ui.Haptics;

/**
 * "Balık" or "Kazma" of a cloud account (services/cloud-afk README "Auto-fishing and auto-mining"; the site's live panel,
 * afk-web {@code auto-panel.tsx}, in the phone's parts): what the mode does now in one sentence (a state that waits on
 * the player carries the warning's small mark; mining names the block it looks at), "Duraklat" or "Devam Et" at its
 * end; the rod or pickaxe with its durability; today's numbers, the items in the game's slots and, for fishing, the last
 * catches with treasure marked; where it looks, a compass point and then up, ahead or down, sent together. The stream
 * runs only while the screen is visible; a mode that cannot run says why in one sentence, with the one thing to do.
 */
public final class CloudAutoFragment extends CloudScreen implements CloudService.AutoListener, CloudService.LiveListener {
    public static final String TAG = "ProjectsCloudAutoFragment";
    private static final String ARG_MODE = "gg.projects.mobile.cloud.AUTO_MODE";
    private static final long REFUSAL_MS = 4000;
    /** Where this phone last turned each account (compass point, look), kept while the app runs (the site's per account). */
    private static final Map<String, int[]> AIMED = new HashMap<>();

    public static Bundle args(String account, String mode) {
        Bundle args = args(account);
        args.putString(ARG_MODE, mode);
        return args;
    }

    @Nullable
    private CloudService.LiveHandle mHandle;
    @Nullable
    private AutoFrames.Fish mFish;
    @Nullable
    private AutoFrames.Mine mMine;
    @Nullable
    private CloudService.Live mLive;
    private boolean mBusy;
    private boolean mSending;
    private String mShape = "";
    @Nullable
    private String mRefusal;
    private boolean mNamesAsked;

    // The live panel's parts that change in place.
    @Nullable
    private TextView mStateText;
    @Nullable
    private TextView mTargetText;
    @Nullable
    private TextView mRefusalText;
    @Nullable
    private Button mToggle;
    @Nullable
    private LinearLayout mToolBox;
    private String mToolKey = "";
    /** The tool line's parts, kept so a change of durability moves the bar from where it was (no rebuild). */
    @Nullable
    private FrameLayout mToolSlot;
    @Nullable
    private String mToolItem;
    @Nullable
    private TextView mToolTitle;
    @Nullable
    private TextView mToolValue;
    @Nullable
    private DurabilityBar mToolBar;
    @Nullable
    private View mToolEmptySlot;
    @Nullable
    private TextView mToolEmpty;
    @Nullable
    private LinearLayout mToolRight;
    /** The item grid's slots by id, in order (a change of counts only updates them in place). */
    private final List<String> mSlotIds = new ArrayList<>();
    private final List<TextView> mSlotCounts = new ArrayList<>();
    private final List<View> mSlotCells = new ArrayList<>();
    @Nullable
    private TextView mPace;
    @Nullable
    private TextView[] mFigures;
    @Nullable
    private LinearLayout mItemsBox;
    private String mItemsKey = "";
    @Nullable
    private LinearLayout mLastBox;
    private String mLastKey = "";
    @Nullable
    private LinearLayout mDirRow;
    @Nullable
    private LinearLayout mTiltRow;
    @Nullable
    private TextView mAimHelp;

    private final Runnable mRefusalEnd = () -> {
        mRefusal = null;
        if (alive()) update();
    };

    private String mode() {
        Bundle args = getArguments();
        String mode = args == null ? null : args.getString(ARG_MODE);
        return AutoSettings.MINING.equals(mode) ? AutoSettings.MINING : AutoSettings.FISHING;
    }

    private boolean fishing() {
        return Auto.fishing(mode());
    }

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(Auto.tab(mode())));
    }

    @Override
    protected void started() {
        CloudModel.Account a = account();
        if (a == null) return;
        mHeader.setCaption(a.name);
        askNames(a);
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        closeWatch();
        mShape = "";
        mRefusal = null;
        View view = getView();
        if (view != null) view.removeCallbacks(mRefusalEnd);
    }

    /** The game's own names of the items in the player's language (the account's Minecraft version's item data). */
    private void askNames(CloudModel.Account a) {
        if (mNamesAsked) return;
        mNamesAsked = true;
        String mc = a.version == null || "auto".equals(a.version) ? null : a.version;
        if (mService.cachedItemData(mc) != null) return;
        mService.itemData(mc, new CloudService.Result<ItemData>() {
            @Override
            public void ok(ItemData value) {
                if (!alive()) return;
                // The names are in: the parts that name items are drawn again.
                mToolKey = "";
                mItemsKey = "";
                mLastKey = "";
                update();
            }

            @Override
            public void failed(CloudProblem problem) {}
        });
    }

    /** The account is wanted running: the machine can say what its modes do (the site's {@code wantedRunning}). */
    private static boolean wantedRunning(CloudModel.Account a) {
        String kind = a.hold == null ? null : a.hold.kind;
        return "run".equals(a.desired) && (kind == null || "paused".equals(kind) || "no_host".equals(kind));
    }

    /** The stream runs only while the mode can say something: on for the account, not limited, wanted running, a machine. */
    private void syncWatch(@Nullable CloudModel.Account a, @Nullable CloudModel.AutoFeature feature) {
        boolean want = a != null && feature != null && feature.mode && feature.on && feature.limited == null && a.host != null && wantedRunning(a);
        if (want && mHandle == null && !mOpening) {
            // The service tells the socket's state to the new watch before it returns (liveState, then render): that
            // render must not open a second watch.
            mOpening = true;
            try {
                mHandle = mService.watchAuto(a.id, this, this);
            } finally {
                mOpening = false;
            }
        } else if (!want && mHandle != null) {
            closeWatch();
        }
    }

    /** A watch is being opened: its own synchronous callbacks draw, but never open another. */
    private boolean mOpening;

    private void closeWatch() {
        if (mHandle != null) mHandle.close();
        mHandle = null;
        mFish = null;
        mMine = null;
    }

    @Nullable
    private AutoFrames.State state() {
        return fishing() ? mFish : mMine;
    }

    @Override
    protected void render() {
        if (mContent == null) return;
        CloudModel.Account a = account();
        String mode = mode();
        CloudModel.AutoFeature feature = a == null ? null : a.features.auto(mode);
        syncWatch(a, feature);
        AutoFrames.State s = state();
        // What holds it: the account's own word first (no need to wait for the machine), then the stream's.
        String shape;
        if (a == null) shape = "loading";
        else if (feature == null || !feature.mode) shape = "calm:service";
        else if (!feature.on) shape = "calm:setting";
        else if (feature.limited != null) shape = "calm:limited";
        else if (!wantedRunning(a) || (s != null && "not_running".equals(s.reason))) shape = "calm:not_running";
        else if (s == null) shape = mLive == CloudService.Live.LOST || mLive == CloudService.Live.HOST_OFFLINE
                || mLive == CloudService.Live.REFUSED ? "calm:away" : "loading";
        else if (s.off()) shape = "off:" + s.reason + (mBusy ? ":busy" : "");
        else shape = "live";
        if (!shape.equals(mShape)) {
            mShape = shape;
            if ("loading".equals(shape)) {
                LinearLayout content = clear();
                forget();
                if (content != null) content.addView(CloudUi.loading(ctx()));
            } else if (shape.startsWith("calm:")) {
                calm(a, shape.substring("calm:".length()));
            } else if (shape.startsWith("off:")) {
                buildOff(a, s);
            } else {
                build(a);
            }
        }
        update();
    }

    /** The parts of a drawn panel are gone (a quiet state or a redraw replaces them). */
    private void forget() {
        mStateText = null;
        mTargetText = null;
        mRefusalText = null;
        mToggle = null;
        mToolBox = null;
        mToolKey = "";
        mToolSlot = null;
        mToolItem = null;
        mToolTitle = null;
        mToolValue = null;
        mToolBar = null;
        mToolEmptySlot = null;
        mToolEmpty = null;
        mToolRight = null;
        mSlotIds.clear();
        mSlotCounts.clear();
        mSlotCells.clear();
        mPace = null;
        mFigures = null;
        mItemsBox = null;
        mItemsKey = "";
        mLastBox = null;
        mLastKey = "";
        mDirRow = null;
        mTiltRow = null;
        mAimHelp = null;
    }

    /** A sentence for a mode that cannot run here, with the one thing the player can do about it. */
    private void calm(@Nullable final CloudModel.Account a, String why) {
        Context c = ctx();
        String mode = mode();
        View action = null;
        String words;
        switch (why) {
            case "setting":
                words = Auto.off(c, mode, "setting");
                if (a != null) {
                    action = CloudUi.button(c, getString(R.string.projects_cloud_open_settings), false,
                            v -> openFor(CloudSettingsFragment.class, a.id));
                }
                break;
            case "limited": {
                CloudModel.AutoFeature feature = a == null ? null : a.features.auto(mode);
                words = Auto.limited(c, mode, feature == null ? null : feature.limited);
                action = CloudUi.siteLink(c, getString(R.string.projects_auto_limited_see),
                        v -> mService.openSite(requireActivity(), Auto.limitedPath(c)));
                break;
            }
            case "not_running":
                words = Auto.off(c, mode, "not_running");
                break;
            case "away":
                words = getString(mLive == CloudService.Live.HOST_OFFLINE ? R.string.projects_feat_move_machine_away
                        : R.string.projects_feat_inv_unreachable);
                break;
            default:
                words = Auto.off(c, mode, "service");
                break;
        }
        quiet(words, action);
    }

    /** Off by the machine's word: the sentence, and "Şimdi Yeniden Bağlan" where the next connection turns it on. */
    private void buildOff(final CloudModel.Account a, AutoFrames.State s) {
        Button action = null;
        if ("restart".equals(s.reason) && reconnectable(a)) {
            action = CloudUi.button(ctx(), getString(R.string.projects_feat_inv_reconnect_now), false, this::reconnect);
            action.setEnabled(!mBusy);
        }
        quiet(Auto.state(ctx(), mode(), s), action);
    }

    private static boolean reconnectable(CloudModel.Account a) {
        return CloudStatus.inGame(a) || "connecting".equals(a.live.state) || "waiting".equals(a.live.state);
    }

    /**
     * The quiet state: the mode's own item from the player's game (a rod, a pickaxe) on the empty state's tile, one
     * sentence, and the one action under it.
     */
    private void quiet(String words, @Nullable View action) {
        LinearLayout content = clear();
        forget();
        if (content == null) return;
        Context c = ctx();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 36), CloudUi.dp(c, 16), CloudUi.dp(c, 24));
        View tile = CloudItemArt.tile(c, fishing() ? "minecraft:fishing_rod" : "minecraft:diamond_pickaxe", 56);
        box.addView(tile, new LinearLayout.LayoutParams(CloudUi.dp(c, 56), CloudUi.dp(c, 56)));
        TextView text = CloudUi.text(c, words, 15, R.color.projects_ash_300, false);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(0, 1.15f);
        text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams lp = CloudUi.matchWrap();
        lp.topMargin = CloudUi.dp(c, 14);
        box.addView(text, lp);
        if (action != null) {
            FrameLayout holder = new FrameLayout(c);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.gravity = Gravity.CENTER_HORIZONTAL;
            alp.topMargin = CloudUi.dp(c, 18);
            holder.addView(action, alp);
            box.addView(holder, CloudUi.matchWrap());
        }
        content.addView(box, CloudUi.matchWrap());
    }

    private void build(CloudModel.Account a) {
        LinearLayout content = clear();
        forget();
        if (content == null) return;
        final Context c = ctx();
        final String mode = mode();

        // The sentence (and mining's block) beside "Duraklat" / "Devam Et"; a refusal holds a line under it for a moment.
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(CloudUi.dp(c, 64));
        head.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 14), 0, CloudUi.dp(c, 6));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView state = CloudUi.text(c, "", 15.5f, R.color.projects_bone_100, true);
        state.setLineSpacing(0, 1.1f);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        words.addView(state);
        mStateText = state;
        if (!fishing()) {
            TextView target = CloudUi.text(c, "", 13.5f, R.color.projects_ash_300, false);
            target.setPadding(0, CloudUi.dp(c, 2), 0, 0);
            target.setVisibility(View.GONE);
            words.addView(target);
            mTargetText = target;
        }
        TextView refusal = CloudUi.text(c, "", 13.5f, R.color.projects_flare_400, false);
        refusal.setPadding(0, CloudUi.dp(c, 4), 0, 0);
        refusal.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        refusal.setVisibility(View.GONE);
        words.addView(refusal);
        mRefusalText = refusal;
        LinearLayout.LayoutParams wordsLp = CloudUi.weight(1f);
        wordsLp.setMarginEnd(CloudUi.dp(c, 12));
        head.addView(words, wordsLp);
        Button toggle = CloudUi.button(c, getString(R.string.projects_auto_pause), false, this::toggle);
        toggle.setCompoundDrawablePadding(CloudUi.dp(c, 8));
        toggle.setVisibility(View.GONE);
        head.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mToggle = toggle;
        content.addView(head, CloudUi.matchWrap());

        // The rod or pickaxe in hand and its durability.
        LinearLayout tool = new LinearLayout(c);
        tool.setOrientation(LinearLayout.HORIZONTAL);
        tool.setGravity(Gravity.CENTER_VERTICAL);
        tool.setMinimumHeight(CloudUi.dp(c, 56));
        tool.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 8), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
        // Nothing in hand: an empty slot, quieter (the site's), and the sentence.
        View emptySlot = new View(c);
        GradientDrawable emptyBg = new GradientDrawable();
        emptyBg.setCornerRadius(CloudUi.dp(c, 8));
        emptyBg.setColor(CloudUi.color(c, R.color.projects_obsidian_800));
        emptySlot.setBackground(emptyBg);
        emptySlot.setAlpha(0.6f);
        tool.addView(emptySlot, new LinearLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        mToolEmptySlot = emptySlot;
        FrameLayout slot = new FrameLayout(c);
        tool.addView(slot, new LinearLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        mToolSlot = slot;
        TextView empty = CloudUi.text(c, getString(fishing() ? R.string.projects_auto_no_rod : R.string.projects_auto_no_pickaxe), 14,
                R.color.projects_ash_300, false);
        LinearLayout.LayoutParams emptyLp = CloudUi.weight(1f);
        emptyLp.setMarginStart(CloudUi.dp(c, 12));
        tool.addView(empty, emptyLp);
        mToolEmpty = empty;
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setBaselineAligned(true);
        TextView title = CloudUi.text(c, "", 14.5f, R.color.projects_bone_100, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, CloudUi.weight(1f));
        mToolTitle = title;
        TextView value = CloudUi.text(c, "", 13, R.color.projects_ash_400, false);
        tabular(value);
        value.setPaddingRelative(CloudUi.dp(c, 12), 0, 0, 0);
        top.addView(value);
        mToolValue = value;
        right.addView(top, CloudUi.matchWrap());
        DurabilityBar bar = new DurabilityBar(c);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CloudUi.dp(c, 6));
        barLp.topMargin = CloudUi.dp(c, 8);
        right.addView(bar, barLp);
        mToolBar = bar;
        LinearLayout.LayoutParams rightLp = CloudUi.weight(1f);
        rightLp.setMarginStart(CloudUi.dp(c, 12));
        tool.addView(right, rightLp);
        mToolRight = right;
        content.addView(tool, CloudUi.matchWrap());
        mToolBox = tool;

        // Today: the head with the pace, the figures, the items and (fishing) the last catches.
        LinearLayout todayHead = new LinearLayout(c);
        todayHead.setOrientation(LinearLayout.HORIZONTAL);
        todayHead.setGravity(Gravity.BOTTOM);
        TextView today = CloudUi.section(c, getString(R.string.projects_auto_today));
        todayHead.addView(today, CloudUi.weight(1f));
        TextView pace = CloudUi.text(c, "", 13, R.color.projects_ash_400, false);
        pace.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 6));
        tabular(pace);
        todayHead.addView(pace);
        mPace = pace;
        content.addView(todayHead, CloudUi.matchWrap());

        int[] labels = fishing()
                ? new int[]{R.string.projects_auto_caught, R.string.projects_auto_fish, R.string.projects_auto_treasure, R.string.projects_auto_junk}
                : new int[]{R.string.projects_auto_broken};
        LinearLayout figures = new LinearLayout(c);
        figures.setOrientation(LinearLayout.HORIZONTAL);
        figures.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 2), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
        mFigures = new TextView[labels.length];
        for (int i = 0; i < 4; i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            if (i < labels.length) {
                TextView figure = CloudUi.figure(c, "0", 20, R.color.projects_bone_50);
                cell.addView(figure);
                TextView label = CloudUi.text(c, getString(labels[i]), 12.5f, R.color.projects_ash_400, false);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams llp = CloudUi.matchWrap();
                llp.topMargin = CloudUi.dp(c, 3);
                cell.addView(label, llp);
                cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                mFigures[i] = figure;
            }
            figures.addView(cell, CloudUi.weight(1f));
        }
        content.addView(figures, CloudUi.matchWrap());

        LinearLayout items = new LinearLayout(c);
        items.setOrientation(LinearLayout.VERTICAL);
        content.addView(items, CloudUi.matchWrap());
        mItemsBox = items;
        if (fishing()) {
            LinearLayout last = new LinearLayout(c);
            last.setOrientation(LinearLayout.VERTICAL);
            content.addView(last, CloudUi.matchWrap());
            mLastBox = last;
        }

        // Where it looks: a compass point, then up, ahead or down; both go together, the look only once a point is set.
        content.addView(CloudUi.gap(c, 18));
        View rule = new View(c);
        rule.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_700));
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, CloudUi.dp(c, 1)));
        ruleLp.setMarginStart(CloudUi.dp(c, 8));
        ruleLp.setMarginEnd(CloudUi.dp(c, 8));
        content.addView(rule, ruleLp);
        content.addView(label(c, getString(R.string.projects_auto_aim_label)));
        CharSequence[] dirs = new CharSequence[Auto.DIRS.length];
        for (int i = 0; i < dirs.length; i++) dirs[i] = Auto.dir(c, Auto.DIRS[i]);
        int[] aimed = AIMED.get(a.id);
        LinearLayout dirRow = CloudUi.segmented(c, dirs, aimed == null ? -1 : aimed[0], i -> aim(i, -1));
        content.addView(dirRow, CloudUi.matchWrap());
        mDirRow = dirRow;
        content.addView(label(c, getString(R.string.projects_auto_tilt_label)));
        CharSequence[] tilts = new CharSequence[Auto.TILTS.length];
        for (int i = 0; i < tilts.length; i++) tilts[i] = Auto.tilt(c, Auto.TILTS[i]);
        LinearLayout tiltRow = CloudUi.segmented(c, tilts, aimed == null ? -1 : aimed[1], i -> aim(-1, i));
        content.addView(tiltRow, CloudUi.matchWrap());
        mTiltRow = tiltRow;
        TextView help = CloudUi.note(c, "");
        content.addView(help);
        mAimHelp = help;
        content.addView(CloudUi.gap(c, 16));
    }

    private static TextView label(Context c, CharSequence words) {
        TextView t = CloudUi.text(c, words, 14, R.color.projects_bone_100, true);
        t.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 14), CloudUi.dp(c, 8), CloudUi.dp(c, 6));
        return t;
    }

    private static void tabular(TextView t) {
        if (android.os.Build.VERSION.SDK_INT >= 21) t.setFontFeatureSettings("tnum");
    }

    /** The words and parts that change in place: the sentence, the button, the tool, today's numbers, the aim's state. */
    private void update() {
        AutoFrames.State s = state();
        if (s == null || mStateText == null || !"live".equals(mShape)) return;
        Context c = ctx();
        String mode = mode();
        mStateText.setText(Auto.state(c, mode, s));
        CloudUi.warnMark(mStateText, Auto.needsPlayer(s));
        if (mTargetText != null) {
            AutoFrames.Target target = mMine == null ? null : mMine.target;
            mTargetText.setText(target == null ? "" : getString(R.string.projects_auto_target, itemName(target.id)));
            mTargetText.setVisibility(target == null ? View.GONE : View.VISIBLE);
        }
        if (mRefusalText != null) {
            mRefusalText.setText(mRefusal == null ? "" : mRefusal);
            mRefusalText.setVisibility(mRefusal == null ? View.GONE : View.VISIBLE);
        }
        boolean away = mLive != CloudService.Live.LIVE;
        if (mToggle != null) {
            boolean resume = s.waitsForPlayer();
            boolean pause = s.on() || s.waiting();
            mToggle.setVisibility(resume || pause ? View.VISIBLE : View.GONE);
            mToggle.setText(resume ? R.string.projects_auto_resume : R.string.projects_auto_pause);
            mToggle.setCompoundDrawablesRelative(CloudUi.tinted(c, resume ? R.drawable.projects_ic_play_fill : R.drawable.projects_ic_pause_fill,
                    R.color.projects_bone_100, 16), null, null, null);
            mToggle.setEnabled(!mSending && !away);
        }
        drawTool(c, s);
        drawToday(c, s);
        boolean aimed = AIMED.containsKey(accountId());
        // An answer on its way does not dim the picks (a tap meanwhile simply goes back): no flicker on every turn.
        if (mDirRow != null) enableRow(mDirRow, !away);
        if (mTiltRow != null) enableRow(mTiltRow, aimed && !away);
        if (mAimHelp != null) {
            String help = getString(R.string.projects_auto_aim_help);
            mAimHelp.setText(aimed ? help : help + " " + getString(R.string.projects_auto_aim_pick_first));
        }
    }

    private static void enableRow(LinearLayout row, boolean enabled) {
        for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.45f);
    }

    /** The tool line: its picture and name, "40 / 64" and a bar the game's colours read (red at the setting's limit). */
    private void drawTool(Context c, AutoFrames.State s) {
        LinearLayout box = mToolBox;
        if (box == null) return;
        AutoFrames.Tool tool = s instanceof AutoFrames.Fish ? ((AutoFrames.Fish) s).rod : s instanceof AutoFrames.Mine ? ((AutoFrames.Mine) s).tool : null;
        if (mToolSlot == null || mToolTitle == null || mToolValue == null || mToolBar == null || mToolEmpty == null || mToolRight == null
                || mToolEmptySlot == null) return;
        int keep = keep();
        String key = tool == null ? "none" : tool.item + "|" + tool.dur + "|" + tool.max + "|" + keep;
        if (key.equals(mToolKey)) return;
        boolean first = mToolKey.isEmpty() || "none".equals(mToolKey);
        mToolKey = key;
        boolean held = tool != null && tool.max > 0;
        mToolEmptySlot.setVisibility(held ? View.GONE : View.VISIBLE);
        mToolEmpty.setVisibility(held ? View.GONE : View.VISIBLE);
        mToolSlot.setVisibility(held ? View.VISIBLE : View.GONE);
        mToolRight.setVisibility(held ? View.VISIBLE : View.GONE);
        if (!held) {
            box.setContentDescription(mToolEmpty.getText());
            return;
        }
        // The picture changes only with the item; the name, the figures and the bar change in place (the bar moves
        // from where it was).
        if (!tool.item.equals(mToolItem)) {
            mToolItem = tool.item;
            mToolSlot.removeAllViews();
            mToolSlot.addView(CloudItemArt.tile(c, tool.item, 40), new FrameLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        }
        String name = itemName(tool.item);
        mToolTitle.setText(name.isEmpty() ? getString(fishing() ? R.string.projects_auto_rod : R.string.projects_auto_pickaxe) : name);
        String figures = Auto.count(c, tool.dur) + " / " + Auto.count(c, tool.max);
        mToolValue.setText(figures);
        float left = tool.left();
        @ColorRes int tone = tool.dur <= keep ? R.color.projects_flare_400 : left <= 0.25f ? R.color.projects_gold_300 : R.color.projects_jade_400;
        mToolBar.set(left, CloudUi.color(c, tone), !first);
        box.setContentDescription(mToolTitle.getText() + ", " + getString(R.string.projects_auto_durability) + " " + figures);
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** The setting's durability limit (the stored one, else the default): the bar turns red there. */
    private int keep() {
        CloudModel.Account a = account();
        if (a == null) return AutoSettings.KEEP_DEFAULT;
        if (fishing()) return a.config.fishing == null ? AutoSettings.KEEP_DEFAULT : a.config.fishing.rod.keep;
        return a.config.mining == null ? AutoSettings.KEEP_DEFAULT : a.config.mining.tool.keep;
    }

    private void drawToday(Context c, AutoFrames.State s) {
        if (mFigures == null || mPace == null) return;
        if (s instanceof AutoFrames.Fish) {
            AutoFrames.Fish f = (AutoFrames.Fish) s;
            int[] values = {f.catches, f.fish, f.treasure, f.junk};
            for (int i = 0; i < 4; i++) setFigure(c, i, values[i]);
            mFigures[2].setTextColor(CloudUi.color(c, f.treasure > 0 ? R.color.projects_gold_300 : R.color.projects_bone_50));
            mPace.setText(f.perHour == null ? "" : getString(R.string.projects_auto_per_hour, Auto.rate(c, f.perHour)));
            drawItems(c, f.items, f.last, getString(R.string.projects_auto_caught_today), getString(R.string.projects_auto_no_catch));
            drawLast(c, f.last);
        } else if (s instanceof AutoFrames.Mine) {
            AutoFrames.Mine m = (AutoFrames.Mine) s;
            setFigure(c, 0, m.blocks);
            mPace.setText(m.perMinute == null ? "" : getString(R.string.projects_auto_per_minute, Auto.rate(c, m.perMinute)));
            drawItems(c, m.items, null, getString(R.string.projects_auto_broken_today), getString(R.string.projects_auto_no_blocks));
        }
    }

    private void setFigure(Context c, int i, int value) {
        if (mFigures == null || i >= mFigures.length || mFigures[i] == null) return;
        TextView figure = mFigures[i];
        figure.setText(Auto.count(c, value));
        View cell = (View) figure.getParent();
        TextView label = (TextView) ((ViewGroup) cell).getChildAt(1);
        cell.setContentDescription(label.getText() + ": " + Auto.count(c, value));
    }

    /** The game's fishing treasure that never comes as anything else (a bow or a rod may be junk); the site's set. */
    private static final java.util.Set<String> ALWAYS_TREASURE = new java.util.HashSet<>(java.util.Arrays.asList(
            "minecraft:enchanted_book", "minecraft:name_tag", "minecraft:nautilus_shell", "minecraft:saddle"));

    /** Items in the game's slots with their counts, most first; treasure gets a gold edge. */
    private void drawItems(Context c, List<AutoFrames.Count> items, @Nullable List<AutoFrames.Catch> last, String title, String none) {
        LinearLayout box = mItemsBox;
        if (box == null) return;
        StringBuilder key = new StringBuilder();
        for (AutoFrames.Count item : items) key.append(item.id).append(':').append(item.n).append(',');
        if (last != null) for (AutoFrames.Catch each : last) if (each.treasure()) key.append('*').append(each.id);
        String k = key.length() == 0 ? "-" : key.toString();
        if (k.equals(mItemsKey)) return;
        mItemsKey = k;
        java.util.Set<String> treasure = new java.util.HashSet<>(fishing() ? ALWAYS_TREASURE : java.util.Collections.<String>emptySet());
        if (last != null) for (AutoFrames.Catch each : last) if (each.treasure()) treasure.add(each.id);
        // The same items in the same order: only their counts change, in place (no new slots, TalkBack keeps its place).
        List<String> ids = new ArrayList<>();
        for (AutoFrames.Count item : items) ids.add(item.id + (treasure.contains(item.id) ? "*" : ""));
        if (!items.isEmpty() && ids.equals(mSlotIds) && mSlotCounts.size() == items.size()) {
            for (int i = 0; i < items.size(); i++) count(c, mSlotCounts.get(i), mSlotCells.get(i), items.get(i));
            return;
        }
        mSlotIds.clear();
        mSlotCounts.clear();
        mSlotCells.clear();
        box.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = CloudUi.text(c, none, 14, R.color.projects_ash_300, false);
            empty.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 10), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
            box.addView(empty);
            return;
        }
        box.addView(CloudUi.section(c, title));
        WrapLayout grid = new WrapLayout(c);
        grid.setGap(CloudUi.dp(c, 8));
        grid.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 4));
        for (AutoFrames.Count item : items) grid.addView(slot(c, item, treasure.contains(item.id)));
        mSlotIds.addAll(ids);
        box.addView(grid, CloudUi.matchWrap());
    }

    /** A slot's count as the game draws it (none for one), and its spoken name. */
    private void count(Context c, TextView n, View cell, AutoFrames.Count item) {
        n.setText(Auto.slotCount(c, item.n));
        n.setVisibility(item.n > 1 ? View.VISIBLE : View.GONE);
        cell.setContentDescription(getString(R.string.projects_auto_count, item.n, itemName(item.id)));
    }

    /** One item in its slot: the game's picture, its count at the corner as the game draws it, named for TalkBack. */
    private View slot(Context c, AutoFrames.Count item, boolean treasure) {
        FrameLayout cell = new FrameLayout(c);
        int size = CloudUi.dp(c, 48);
        View tile = CloudItemArt.tile(c, item.id, 48);
        if (treasure && tile.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) tile.getBackground()).setStroke(Math.max(1, CloudUi.dp(c, 1)), CloudUi.color(c, R.color.projects_gold_300));
        }
        cell.addView(tile, new FrameLayout.LayoutParams(size, size));
        TextView n = CloudUi.figure(c, "", 12.5f, R.color.projects_bone_50);
        n.setShadowLayer(0.01f, CloudUi.dp(c, 1), CloudUi.dp(c, 1), 0xF20A090C);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        nlp.setMarginEnd(CloudUi.dp(c, 4));
        nlp.bottomMargin = CloudUi.dp(c, 2);
        n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(n, nlp);
        count(c, n, cell, item);
        mSlotCounts.add(n);
        mSlotCells.add(cell);
        cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        cell.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        return cell;
    }

    /** The last catches, newest first: the item, treasure marked, enchanted said, and when. */
    private void drawLast(Context c, List<AutoFrames.Catch> last) {
        LinearLayout box = mLastBox;
        if (box == null) return;
        StringBuilder key = new StringBuilder();
        for (AutoFrames.Catch each : last) key.append(each.id).append(each.at).append(each.ench).append(',');
        String k = key.length() == 0 ? "-" : key.toString();
        if (k.equals(mLastKey)) return;
        mLastKey = k;
        box.removeAllViews();
        if (last.isEmpty()) return;
        box.addView(CloudUi.section(c, getString(R.string.projects_auto_last)));
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        java.text.DateFormat clock = DateFormat.getTimeFormat(c);
        for (AutoFrames.Catch each : last) rows.add(catchRow(c, each, clock));
        CloudUi.fillFigures(group, rows);
        box.addView(group);
    }

    private View catchRow(Context c, AutoFrames.Catch each, java.text.DateFormat clock) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 48));
        row.setPaddingRelative(CloudUi.dp(c, 12), CloudUi.dp(c, 4), CloudUi.dp(c, 16), CloudUi.dp(c, 4));
        row.addView(CloudItemArt.tile(c, each.id, null, 32, each.ench), new LinearLayout.LayoutParams(CloudUi.dp(c, 32), CloudUi.dp(c, 32)));
        String name = itemName(each.id);
        String shown = each.ench ? getString(R.string.projects_auto_enchanted, name) : name;
        boolean treasure = each.treasure();
        TextView title = CloudUi.text(c, shown, 14.5f, treasure ? R.color.projects_gold_200 : R.color.projects_bone_100, treasure);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = CloudUi.weight(1f);
        tlp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(title, tlp);
        String words = shown;
        if (treasure) {
            TextView mark = CloudUi.text(c, getString(R.string.projects_auto_treasure), 12.5f, R.color.projects_gold_300, false);
            mark.setPaddingRelative(CloudUi.dp(c, 8), 0, 0, 0);
            row.addView(mark);
            words += ", " + getString(R.string.projects_auto_treasure);
        }
        String at = each.at > 0 ? clock.format(new Date(each.at * 1000L)) : "";
        if (!at.isEmpty()) {
            TextView time = CloudUi.time(c, 13, R.color.projects_ash_400);
            time.setText(at);
            time.setGravity(Gravity.END);
            time.setPaddingRelative(CloudUi.dp(c, 12), 0, 0, 0);
            row.addView(time);
            words += ", " + at;
        }
        row.setContentDescription(words);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return row;
    }

    // ---- operations ----

    private void toggle(View from) {
        AutoFrames.State s = state();
        if (s == null) return;
        boolean resume = s.waitsForPlayer();
        send(from, resume ? AutoOp.resume(mode()) : AutoOp.pause(mode()), false, null);
    }

    /**
     * A compass point ({@code dir} >= 0) or a look ({@code tilt} >= 0) was picked: both go together as one aim, the look
     * only once a point is set (the site's rule). The picks show what went out; a refusal puts the last aim back.
     */
    private void aim(int dir, int tilt) {
        String acct = accountId();
        if (acct == null) return;
        int[] before = AIMED.get(acct);
        if (dir < 0 && before == null) return;
        final int d = dir >= 0 ? dir : before[0];
        final int t = tilt >= 0 ? tilt : before == null ? 1 : before[1];
        // The picked segment ticked already. Nothing went out (another answer is awaited, or the stream is gone): the
        // picks go back to the last aim.
        if (!send(null, AutoOp.aim(Auto.DIR_YAW[d], Auto.TILT_PITCH[t]), true, new int[]{d, t})) selectAim();
    }

    /** Sends one operation; false when it cannot go now. */
    private boolean send(@Nullable View from, AutoOp op, final boolean aim, @Nullable final int[] aimed) {
        CloudService.LiveHandle handle = mHandle;
        if (handle == null || mSending) return false;
        if (AutoOp.problem(op.op) != null) return false;
        if (from != null) Haptics.tick(from);
        mSending = true;
        endRefusal();
        update();
        final String acct = accountId();
        handle.autoOp(op, error -> {
            mSending = false;
            if (!alive()) return;
            if (error == null) {
                if (aimed != null && acct != null) AIMED.put(acct, aimed);
                selectAim();
                update();
                return;
            }
            selectAim();
            String words = Auto.refusal(ctx(), mode(), error, aim);
            if (words != null) refuse(words);
            else update();
        });
        return true;
    }

    /** The picks show the last aim that went out (a refused one goes back). */
    private void selectAim() {
        int[] aimed = AIMED.get(accountId());
        if (mDirRow != null) CloudUi.selectSegment(mDirRow, aimed == null ? -1 : aimed[0]);
        if (mTiltRow != null) CloudUi.selectSegment(mTiltRow, aimed == null ? -1 : aimed[1]);
    }

    private void refuse(String words) {
        View view = getView();
        if (view == null) return;
        mRefusal = words;
        Haptics.reject(mRefusalText != null ? mRefusalText : view);
        view.removeCallbacks(mRefusalEnd);
        view.postDelayed(mRefusalEnd, REFUSAL_MS);
        update();
    }

    private void endRefusal() {
        View view = getView();
        if (view != null) view.removeCallbacks(mRefusalEnd);
        mRefusal = null;
    }

    private void reconnect(final View from) {
        final CloudModel.Account a = account();
        if (a == null || mBusy) return;
        mBusy = true;
        mShape = "";
        render();
        mService.restart(a.id, new CloudService.Result<CloudModel.Account>() {
            @Override
            public void ok(CloudModel.Account value) {
                mBusy = false;
                if (!alive()) return;
                Haptics.confirm(from);
                toast(getString(R.string.projects_cloud_reconnecting, value.name));
                mShape = "";
                render();
            }

            @Override
            public void failed(CloudProblem p) {
                mBusy = false;
                if (!alive()) return;
                problem(p, from);
                mShape = "";
                render();
            }
        });
    }

    // ---- the stream ----

    @Override
    public void autoState(AutoFrames.Update update) {
        // A frame names the modes that changed; the other keeps its last state.
        if (update.fishing != null) mFish = update.fishing;
        if (update.mining != null) mMine = update.mining;
        if (alive()) render();
    }

    @Override
    public void liveState(CloudService.Live state) {
        mLive = state;
        // A lost socket: what it showed may be stale; the next watch sends the states first.
        if (state != CloudService.Live.LIVE) {
            mFish = null;
            mMine = null;
        }
        if (alive()) render();
    }

    @Override
    public void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}

    @Override
    public void approval(CloudModel.Approval approval) {}

    @Override
    public void hostOnline(boolean online) {
        if (!online) {
            mFish = null;
            mMine = null;
            mLive = CloudService.Live.HOST_OFFLINE;
            if (alive()) render();
        }
    }

    /** A rod's or a pickaxe's durability: a thin rounded bar, the fill moved on a transform-like redraw (eased, or at once). */
    private static final class DurabilityBar extends View {
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private float mShown;

        DurabilityBar(Context c) {
            super(c);
            mTrack.setColor(CloudUi.color(c, R.color.projects_obsidian_700));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(float left, int color, boolean animate) {
            mFill.setColor(color);
            final float target = Math.max(0f, Math.min(1f, left));
            if (!animate || !Motion.enabled(getContext())) {
                mShown = target;
                invalidate();
                return;
            }
            final float from = mShown;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(300);
            a.setInterpolator(Motion.EASE_OUT);
            a.addUpdateListener(v -> {
                mShown = from + (target - from) * (float) v.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float r = h / 2f;
            mRect.set(0, 0, getWidth(), h);
            canvas.drawRoundRect(mRect, r, r, mTrack);
            if (mShown > 0) {
                mRect.set(0, 0, Math.max(h, getWidth() * mShown), h);
                canvas.drawRoundRect(mRect, r, r, mFill);
            }
        }
    }

}
