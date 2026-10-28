package gg.projects.mobile.cloud.inventory;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudStatus;
import gg.projects.mobile.cloud.items.ItemArt;
import gg.projects.mobile.cloud.items.ItemAtlases;
import gg.projects.mobile.cloud.ui.CloudScreen;
import gg.projects.mobile.cloud.ui.CloudSettingsFragment;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.HapticMap;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.PixelSprites;
import gg.projects.mobile.ui.Press;

/**
 * "Envanter" of a cloud account (CAFK-FEAT §3.1, docs/design.md §6 "Cloud inventory"): the account's inventory and the
 * window the server opened, live, as the game's own screen. A tap is the game's left click: it picks a stack up into
 * the hand slot under the window and puts it down where the next tap lands; a long press opens the slot's actions under
 * the item's tooltip. Every move vibrates; a refusal rolls back with one short line. The view watches only while it is
 * on screen. Looking only ("view") and a trade's lock keep their line and leave the items as they are.
 *
 * <p>Reusable as a picker ({@link #pick}): a tap on an item then hands it back to the screen that asked (AFK+'s "show the
 * item"), and nothing moves.
 */
public final class CloudInventoryFragment extends CloudScreen implements InventoryController.Host {
    public static final String TAG = "ProjectsCloudInventoryFragment";
    static final String ARG_PICK = "gg.projects.mobile.cloud.inventory.PICK";
    /** The picked item's fields in the result bundle (see {@link #pick}). */
    public static final String PICKED_ACCOUNT = "acct";
    public static final String PICKED_AREA = "area";
    public static final String PICKED_SLOT = "slot";
    public static final String PICKED_WSEQ = "wseq";
    public static final String PICKED_ID = "id";
    public static final String PICKED_COUNT = "count";
    public static final String PICKED_NAME = "name";
    public static final String PICKED_CMD = "cmd";
    public static final String PICKED_MODEL = "model";
    private static final long FLASH_MS = 3500;

    @Nullable
    private InventoryController mController;
    @Nullable
    private InventoryView mView;
    @Nullable
    private PixelLines.HandSlot mHand;
    @Nullable
    private TextView mLine;
    @Nullable
    private Button mAction;
    @Nullable
    private LinearLayout mNotices;
    private ItemArt mArt = ItemArt.standIns();
    private int mArtPx;
    private String mShape = "";
    private String mNoticeShown = "";
    @Nullable
    private String mFlash;
    private final Runnable mFlashEnd = () -> {
        mFlash = null;
        refresh();
    };
    /** The player just turned the inventory on: it shows at the next connection (until the machine says otherwise). */
    private boolean mTurnedOn;
    private boolean mBusy;
    @Nullable
    private BottomPanel mSheet;

    /**
     * Opens the inventory as a picker for {@code account}: a tap on an item closes it and sets the fragment result
     * {@code requestKey} on {@code from}'s parent fragment manager with {@link #PICKED_ACCOUNT}, {@link #PICKED_AREA}
     * ("inv" or "win"), {@link #PICKED_SLOT}, {@link #PICKED_WSEQ}, {@link #PICKED_ID}, {@link #PICKED_COUNT} and, when the
     * item has them, {@link #PICKED_NAME}, {@link #PICKED_CMD}, {@link #PICKED_MODEL}.
     */
    public static void pick(Fragment from, String account, String requestKey) {
        Bundle args = CloudScreen.args(account);
        args.putString(ARG_PICK, requestKey);
        SocialUi.open(from, CloudInventoryFragment.class, TAG, args);
    }

    @Nullable
    private String pickKey() {
        Bundle args = getArguments();
        return args == null ? null : args.getString(ARG_PICK);
    }

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_feat_inventory));
    }

    @Override
    protected void started() {
        CloudModel.Account a = account();
        if (a == null) return;
        mHeader.setCaption(a.name);
        if (mController == null) {
            mController = new InventoryController(mService, a.id, lang(), this);
            mController.start();
        }
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mController != null) mController.stop();
        mController = null;
        if (mSheet != null) mSheet.dismiss();
        View view = getView();
        if (view != null) view.removeCallbacks(mFlashEnd);
        mShape = "";
    }

    // ---- drawing ----

    @Override
    protected void render() {
        CloudModel.Account a = account();
        LinearLayout content = mContent;
        if (content == null) return;
        if (a == null) {
            if (!"loading".equals(mShape)) {
                mShape = "loading";
                content.removeAllViews();
                content.addView(CloudUi.loading(ctx()));
            }
            return;
        }
        mHeader.setCaption(a.name);
        String shape = shape(a);
        if (!shape.equals(mShape)) {
            mShape = shape;
            build(a, shape);
        }
        notices(a);
        if ("ok".equals(shape)) refresh();
    }

    private String shape(CloudModel.Account a) {
        if (!a.features.inventory()) return "service";
        InventoryController c = mController;
        if (c == null) return "loading";
        InvFrames.Status status = c.status();
        if (status == null) return CloudStatus.inGame(a) || !mTurnedOn ? "loading" : "restart";
        if (status.ok()) return c.ready() ? "ok" : "loading";
        String reason = status.reason == null ? "service" : status.reason;
        if (mTurnedOn && "setting".equals(reason)) return "restart";
        return reason;
    }

    private void build(final CloudModel.Account a, String shape) {
        LinearLayout content = clear();
        if (content == null) return;
        Context c = ctx();
        mView = null;
        mHand = null;
        mLine = null;
        mAction = null;
        mNoticeShown = "";
        mNotices = new LinearLayout(c);
        mNotices.setOrientation(LinearLayout.VERTICAL);
        content.addView(mNotices, CloudUi.matchWrap());
        switch (shape) {
            case "loading":
                content.addView(CloudUi.loading(c));
                return;
            case "not_running":
                content.addView(CloudUi.empty(c, PixelSprites.Sprite.INVENTORY, getString(R.string.projects_feat_inv_not_running)));
                return;
            case "version":
                content.addView(CloudUi.empty(c, PixelSprites.Sprite.INVENTORY, getString(R.string.projects_feat_inv_version)));
                return;
            case "setting": {
                LinearLayout empty = CloudUi.empty(c, PixelSprites.Sprite.INVENTORY, getString(R.string.projects_feat_inv_off));
                Button on = CloudUi.button(c, getString(R.string.projects_feat_inv_turn_on), true, v -> turnOn(v));
                on.setEnabled(!mBusy);
                empty.addView(centered(c, on));
                content.addView(empty);
                return;
            }
            case "restart": {
                LinearLayout empty = CloudUi.empty(c, PixelSprites.Sprite.INVENTORY, getString(R.string.projects_feat_inv_restart));
                if (CloudStatus.inGame(a) || "connecting".equals(a.live.state) || "waiting".equals(a.live.state)) {
                    Button again = CloudUi.button(c, getString(R.string.projects_feat_inv_reconnect_now), false, v -> reconnect(v));
                    again.setEnabled(!mBusy);
                    empty.addView(centered(c, again));
                }
                content.addView(empty);
                return;
            }
            case "ok":
                break;
            default:
                content.addView(CloudUi.empty(c, PixelSprites.Sprite.INVENTORY, getString(R.string.projects_feat_inv_service)));
                return;
        }

        // The game's window, as wide as the screen allows on whole pixels (a little into the page's side margins).
        InventoryView view = new InventoryView(c);
        view.setLabels(this::label);
        view.setListener(new InventoryView.Listener() {
            @Override
            public void tap(InvLayout.Spot spot) {
                onTap(spot);
            }

            @Override
            public void longPress(InvLayout.Spot spot) {
                onLongPress(spot);
            }
        });
        LinearLayout.LayoutParams viewLp = CloudUi.matchWrap();
        viewLp.topMargin = CloudUi.dp(c, 6);
        viewLp.leftMargin = -CloudUi.dp(c, 8);
        viewLp.rightMargin = -CloudUi.dp(c, 8);
        // The page's side padding must not clip that bleed (it cut the window's sides, its outline and bevel, on the
        // emulator); the slots themselves stay inside the page.
        content.setClipToPadding(false);
        content.addView(view, viewLp);
        mView = view;

        // Under it, a reserved line: what to do with a carried stack, a lock's words, or a move's answer; and its button.
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 56));
        PixelLines.HandSlot hand = new PixelLines.HandSlot(c);
        hand.setOnClickListener(v -> putBack(v));
        Press.attach(hand, Press.Style.ICON);
        LinearLayout.LayoutParams handLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        handLp.setMarginEnd(CloudUi.dp(c, 12));
        row.addView(hand, handLp);
        mHand = hand;
        TextView line = CloudUi.text(c, "", 14, R.color.projects_ash_300, false);
        line.setLineSpacing(0, 1.15f);
        line.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(line, CloudUi.weight(1f));
        mLine = line;
        Button action = CloudUi.textButton(c, "", v -> lineAction(v));
        row.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mAction = action;
        LinearLayout.LayoutParams rowLp = CloudUi.matchWrap();
        rowLp.topMargin = CloudUi.dp(c, 4);
        content.addView(row, rowLp);

        TextView note = CloudUi.note(c, getString(R.string.projects_feat_inv_private));
        note.setPaddingRelative(0, note.getPaddingTop(), 0, note.getPaddingBottom());
        content.addView(note, CloudUi.matchWrap());
        // The line under the window and the note under it start and end at the window's visible edge (design.md §6 "Our
        // frame"), whatever whole-pixel scale the window took: set in the window's own layout pass, before it draws.
        final int bleed = viewLp.leftMargin;
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int inset = bleed + view.edge();
            align(row, inset);
            align(note, inset);
        });
    }

    private static void align(View v, int inset) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (lp.getMarginStart() == inset && lp.getMarginEnd() == inset) return;
        lp.setMarginStart(inset);
        lp.setMarginEnd(inset);
        v.setLayoutParams(lp);
    }

    private static View centered(Context c, View child) {
        FrameLayout box = new FrameLayout(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = CloudUi.dp(c, 18);
        box.addView(child, lp);
        box.setLayoutParams(CloudUi.matchWrap());
        return box;
    }

    /** The live connection's state, where it needs words (the console's notices). */
    private void notices(CloudModel.Account a) {
        LinearLayout notices = mNotices;
        InventoryController c = mController;
        if (notices == null) return;
        String key = "";
        int words = 0;
        int tone = R.color.projects_gold_300;
        if (a.host == null) {
            key = "nohost";
            words = R.string.projects_cloud_console_no_host;
            tone = R.color.projects_ash_300;
        } else if (c != null) {
            CloudService.Live live = c.liveState();
            if (!c.hostOnline() || live == CloudService.Live.HOST_OFFLINE) {
                key = "host";
                words = R.string.projects_cloud_console_host_offline;
            } else if (live == CloudService.Live.LOST) {
                key = "lost";
                words = R.string.projects_cloud_console_link_lost;
            } else if (live == CloudService.Live.REPLACED) {
                key = "replaced";
                words = R.string.projects_cloud_console_replaced;
            } else if (live == CloudService.Live.SIGNED_OUT) {
                key = "ended";
                words = R.string.projects_cloud_console_ended;
            }
        }
        CloudModel.Offer offer = mService.offer();
        if (offer != null && offer.paused()) {
            key += "|paused";
        }
        if (key.equals(mNoticeShown)) return;
        mNoticeShown = key;
        notices.removeAllViews();
        Context ctx = ctx();
        if (offer != null && offer.paused()) {
            String m = offer.maintenance != null ? offer.maintenance.in(lang()) : null;
            notices.addView(CloudUi.notice(ctx, R.drawable.projects_ic_clock, R.color.projects_gold_300,
                    TextUtils.isEmpty(m) ? getString(R.string.projects_cloud_maintenance) : m));
        }
        if (words != 0) notices.addView(CloudUi.notice(ctx, R.drawable.projects_ic_warning_circle, tone, getString(words)));
        if ("replaced".equals(key) || key.startsWith("replaced")) {
            notices.addView(CloudUi.textButton(ctx, getString(R.string.projects_cloud_console_reopen), v -> {
                mService.reopen(a.id);
                Haptics.tick(v);
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** The window, the hand slot and the line under them, from the controller's newest state. */
    private void refresh() {
        InventoryController c = mController;
        InventoryView view = mView;
        CloudModel.Account a = account();
        if (c == null || view == null || a == null) return;
        Set<String> waiting = new LinkedHashSet<>();
        InvRules.State shown = c.shown(waiting);
        InvLayout.Screen screen = c.screen();
        String title = c.windowTitle();
        if (title != null && title.isEmpty()) title = getString(R.string.projects_feat_inv_menu);
        view.setInteractive(true);
        view.show(screen, shown, c.window(), waiting, title == null ? getString(R.string.projects_feat_inv_equipment) : title,
                getString(R.string.projects_feat_inventory));
        int itemPx = 16 * Math.max(1, view.scale());
        if (itemPx != mArtPx && view.getWidth() > 0) {
            mArtPx = itemPx;
            mArt = ItemAtlases.art(ctx(), a.version, itemPx, art -> {
                if (!alive()) return;
                mArt = art;
                if (mView != null) mView.setArt(art);
                refresh();
            });
            view.setArt(mArt);
        } else if (view.getWidth() == 0) {
            view.post(this::refresh);
        }

        boolean carrying = shown.cursor != null && pickKey() == null;
        if (mHand != null) {
            mHand.setVisibility(carrying ? View.VISIBLE : View.GONE);
            if (carrying) {
                mHand.show(shown.cursor, mArt, Math.max(1, view.scale()), waiting.contains("cursor"));
                mHand.setContentDescription(getString(R.string.projects_feat_inv_hand, InvText.speech(shown.cursor, lang(), words())));
            }
        }
        InvFrames.Status status = c.status();
        String text = null;
        int tone = R.color.projects_ash_300;
        String action = null;
        if (mFlash != null) {
            text = flashText(mFlash);
            tone = R.color.projects_bone_100;
        } else if (carrying) {
            text = getString(R.string.projects_feat_inv_carry);
        } else if (status != null && status.tradeLock()) {
            text = getString(R.string.projects_feat_inv_trade);
        } else if (pickKey() != null) {
            text = getString(R.string.projects_feat_inv_pick_hint);
        } else if (!c.movesAllowed()) {
            text = getString(R.string.projects_feat_inv_view_only);
        }
        if (carrying) action = getString(R.string.projects_feat_inv_put_back);
        else if (c.wseq() != 0 && c.movesAllowed() && pickKey() == null && (status == null || !status.tradeLock())) {
            action = getString(R.string.projects_feat_inv_close_menu);
        }
        if (mLine != null) {
            mLine.setText(text == null ? "" : text);
            mLine.setTextColor(CloudUi.color(ctx(), tone));
        }
        if (mAction != null) {
            mAction.setText(action == null ? "" : action);
            mAction.setVisibility(action == null ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private String flashText(String key) {
        switch (key) {
            case "slowDown": return getString(R.string.projects_feat_inv_slow);
            case "tradeLocked": return getString(R.string.projects_feat_inv_trade);
            case "viewOnly": return getString(R.string.projects_feat_inv_view_only);
            case "notRunning": return getString(R.string.projects_feat_inv_not_running);
            case "off": return getString(R.string.projects_feat_inv_off);
            case "unreachable": return getString(R.string.projects_feat_inv_unreachable);
            default: return getString(R.string.projects_feat_inv_refused);
        }
    }

    // ---- what the player does ----

    private boolean canMove() {
        InventoryController c = mController;
        if (c == null || !c.movesAllowed()) return false;
        InvFrames.Status status = c.status();
        return status != null && status.ok() && !status.tradeLock();
    }

    /** A move tried where nothing moves: the lock on the slot and the line in bone for a moment. */
    private void refuse(InvLayout.Spot spot, String key) {
        if (mView != null) {
            mView.showLock(spot.key());
            Haptics.reject(mView);
        }
        flash(key);
    }

    private void onTap(InvLayout.Spot spot) {
        InventoryController c = mController;
        if (c == null || mView == null) return;
        InvPlan.State state = c.planState();
        String pick = pickKey();
        if (pick != null) {
            InvItem item = state.at(spot);
            if (item != null) picked(spot, item, state.wseq, pick);
            return;
        }
        if (!InvLayout.slotUsable(state.screen, spot)) return;
        if (state.at(spot) == null && state.inv.cursor == null) return;
        if (!canMove()) {
            InvFrames.Status status = c.status();
            refuse(spot, status != null && status.tradeLock() ? "tradeLocked" : "viewOnly");
            return;
        }
        InvPlan.Step step = InvPlan.click(state, spot, 0);
        if (step == null) return;
        if (c.run(step)) Haptics.perform(mView, HapticMap.Kind.PRESS);
        else Haptics.reject(mView);
    }

    private void onLongPress(final InvLayout.Spot spot) {
        final InventoryController c = mController;
        if (c == null || mView == null || pickKey() != null) return;
        InvPlan.State state = c.planState();
        if (!canMove()) {
            if (state.at(spot) != null) {
                InvFrames.Status status = c.status();
                refuse(spot, status != null && status.tradeLock() ? "tradeLocked" : "viewOnly");
            }
            return;
        }
        List<InvPlan.Row> rows = InvPlan.rows(state, spot, true, false);
        if (rows.isEmpty()) return;
        Haptics.perform(mView, HapticMap.Kind.LONG_PRESS);
        mView.setLit(spot.key());
        sheet(spot, state.at(spot), rows);
    }

    /** The slot's actions under the item's tooltip (solid obsidian; rows a finger tall; dropping apart, in flare). */
    private void sheet(final InvLayout.Spot spot, @Nullable InvItem item, List<InvPlan.Row> rows) {
        final Context c = ctx();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, CloudUi.dp(c, 12));
        if (item != null) {
            // The window's own GUI pixel (the launcher's menu head takes the inventory's too): the same letters as above.
            int unit = mView != null && mView.scale() > 1 ? mView.scale() : Math.max(2, Math.round(c.getResources().getDisplayMetrics().density * 1.5f));
            PixelLines.Tooltip head = new PixelLines.Tooltip(c, unit);
            head.setLines(InvText.tooltip(item, lang(), words()));
            head.setPadding(CloudUi.dp(c, 24), CloudUi.dp(c, 4), CloudUi.dp(c, 24), CloudUi.dp(c, 12));
            head.setContentDescription(InvText.speech(item, lang(), words()));
            root.addView(head, CloudUi.matchWrap());
            View line = new View(c);
            line.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_700));
            root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, CloudUi.dp(c, 1))));
        }
        final BottomPanel[] panel = new BottomPanel[1];
        for (final InvPlan.Row r : rows) {
            if (r.apart) {
                View gap = new View(c);
                gap.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_700));
                LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, CloudUi.dp(c, 1)));
                gapLp.topMargin = CloudUi.dp(c, 4);
                gapLp.bottomMargin = CloudUi.dp(c, 4);
                gapLp.setMarginStart(CloudUi.dp(c, 24));
                root.addView(gap, gapLp);
            }
            TextView row = CloudUi.text(c, word(r.word), 16, r.danger ? R.color.projects_flare_400 : R.color.projects_bone_100, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(CloudUi.dp(c, 52));
            row.setPadding(CloudUi.dp(c, 24), 0, CloudUi.dp(c, 24), 0);
            row.setBackgroundResource(R.drawable.projects_row_ripple);
            row.setClickable(true);
            row.setFocusable(true);
            Press.attach(row, Press.Style.ROW);
            row.setOnClickListener(v -> {
                InventoryController ctl = mController;
                if (panel[0] != null) panel[0].dismiss();
                if (ctl == null) return;
                InvPlan.Step step = r.planner.plan(ctl.planState());
                if (step == null) return;
                if (ctl.run(step)) Haptics.perform(mView, HapticMap.Kind.PRESS);
                else Haptics.reject(mView);
            });
            root.addView(row, CloudUi.matchWrap());
        }
        ScrollView scroll = new ScrollView(c);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel[0] = BottomPanel.of(c, scroll, 0.8f).onClosed(() -> {
            if (mView != null) mView.setLit(null);
            mSheet = null;
        });
        mSheet = panel[0];
        panel[0].show();
    }

    private String word(String key) {
        switch (key) {
            case "take": return getString(R.string.projects_feat_inv_take);
            case "takeHalf": return getString(R.string.projects_feat_inv_take_half);
            case "place": return getString(R.string.projects_feat_inv_place);
            case "swap": return getString(R.string.projects_feat_inv_swap);
            case "placeOne": return getString(R.string.projects_feat_inv_place_one);
            case "putBack": return getString(R.string.projects_feat_inv_put_back);
            case "toInventory": return getString(R.string.projects_feat_inv_to_inventory);
            case "toHotbar": return getString(R.string.projects_feat_inv_to_hotbar);
            case "putOn": return getString(R.string.projects_feat_inv_put_on);
            case "toOffhand": return getString(R.string.projects_feat_inv_to_offhand);
            case "click": return getString(R.string.projects_feat_inv_click);
            case "rightClick": return getString(R.string.projects_feat_inv_right_click);
            case "shiftClick": return getString(R.string.projects_feat_inv_shift_click);
            case "hold": return getString(R.string.projects_feat_inv_hold);
            case "use": return getString(R.string.projects_feat_inv_use);
            case "dropOne": return getString(R.string.projects_feat_inv_drop_one);
            case "dropStack": return getString(R.string.projects_feat_inv_drop_stack);
            default: return getString(R.string.projects_feat_inv_drop);
        }
    }

    private void putBack(View from) {
        InventoryController c = mController;
        if (c == null) return;
        InvPlan.Step step = InvPlan.putBack(c.planState());
        if (step == null) return;
        if (c.run(step)) Haptics.perform(from, HapticMap.Kind.PRESS);
        else Haptics.reject(from);
    }

    /** The line's button: "Yerine koy" while a stack is carried, else "Menüyü kapat" with a window open. */
    private void lineAction(View from) {
        InventoryController c = mController;
        if (c == null) return;
        InvPlan.State state = c.planState();
        if (state.inv.cursor != null) {
            putBack(from);
            return;
        }
        InvPlan.Step step = InvPlan.close(state);
        if (step == null) return;
        if (c.run(step)) Haptics.perform(from, HapticMap.Kind.PRESS);
    }

    private void picked(InvLayout.Spot spot, InvItem item, long wseq, String requestKey) {
        CloudModel.Account a = account();
        if (a == null) return;
        Bundle result = new Bundle();
        result.putString(PICKED_ACCOUNT, a.id);
        result.putString(PICKED_AREA, spot.area == InvLayout.Area.WIN ? "win" : "inv");
        result.putInt(PICKED_SLOT, spot.slot);
        result.putLong(PICKED_WSEQ, wseq);
        result.putString(PICKED_ID, item.id);
        result.putInt(PICKED_COUNT, item.count);
        if (item.name != null) result.putString(PICKED_NAME, InvText.cleanText(item.name));
        if (item.cmd != null) result.putInt(PICKED_CMD, item.cmd);
        if (item.model != null) result.putString(PICKED_MODEL, item.model);
        if (mView != null) Haptics.confirm(mView);
        getParentFragmentManager().setFragmentResult(requestKey, result);
        leaveNow();
    }

    private void turnOn(final View from) {
        final CloudModel.Account a = account();
        if (a == null || mBusy) return;
        mBusy = true;
        mShape = "";
        render();
        mService.setInventory(a.id, true, new CloudService.Result<CloudService.SaveOutcome>() {
            @Override
            public void ok(CloudService.SaveOutcome outcome) {
                mBusy = false;
                if (!alive()) return;
                if (outcome.saved != null) {
                    Haptics.confirm(from);
                    mTurnedOn = true;
                } else {
                    // A stored row the save checks refuse: the settings open on its tab with it marked, as the toast
                    // says (opened plainly they showed "Genel" with nothing marked).
                    Haptics.reject(from);
                    SocialUi.toastLong(ctx(), getString(R.string.projects_feat_inv_fix_settings));
                    open(CloudSettingsFragment.class, CloudSettingsFragment.marked(a.id, outcome.issues));
                }
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

    // ---- the controller's calls ----

    @Override
    public void changed() {
        if (!alive()) return;
        InventoryController c = mController;
        if (c != null && c.status() != null && !"setting".equals(c.status().reason)) mTurnedOn = false;
        render();
    }

    @Override
    public void message(String key) {
        if (!alive()) return;
        if (mView != null && !"slowDown".equals(key)) Haptics.reject(mView);
        flash(key);
    }

    private void flash(String key) {
        mFlash = key;
        View view = getView();
        if (view != null) {
            view.removeCallbacks(mFlashEnd);
            view.postDelayed(mFlashEnd, FLASH_MS);
        }
        refresh();
    }

    @Override
    public void speak(String word, String item) {
        if (mView == null) return;
        int res;
        switch (word) {
            case "pickedUp": res = R.string.projects_feat_inv_picked_up; break;
            case "placed": res = R.string.projects_feat_inv_placed; break;
            case "swapped": res = R.string.projects_feat_inv_swapped; break;
            case "moved": res = R.string.projects_feat_inv_moved; break;
            case "dropped": res = R.string.projects_feat_inv_dropped; break;
            case "used": res = R.string.projects_feat_inv_used; break;
            case "held": res = R.string.projects_feat_inv_held_now; break;
            default: res = R.string.projects_feat_inv_put_back_done; break;
        }
        mView.announceForAccessibility(getString(res, item));
    }

    @Override
    public void liveState(CloudService.Live state, boolean hostOnline) {
        if (!alive()) return;
        CloudModel.Account a = account();
        if (a != null) notices(a);
    }

    // ---- words ----

    private InvText.Words mWords;

    InvText.Words words() {
        if (mWords != null) return mWords;
        final Context c = ctx();
        mWords = new InvText.Words() {
            @Override
            public String durability(int left, int max) {
                return c.getString(R.string.projects_feat_inv_durability, left, max);
            }

            @Override
            public String insideStack(String item, int n) {
                return c.getString(R.string.projects_feat_inv_inside, item, n);
            }

            @Override
            public String insideMore(int n) {
                return c.getString(R.string.projects_feat_inv_inside_more, n);
            }

            @Override
            public String insideMoreUnknown() {
                return c.getString(R.string.projects_feat_inv_inside_unknown);
            }

            @Override
            public String partial() {
                return c.getString(R.string.projects_feat_inv_partial);
            }

            @Override
            public String count(int n) {
                return c.getString(R.string.projects_feat_inv_count, n);
            }

            @Override
            public String durabilityPercent(int p) {
                return c.getString(R.string.projects_feat_inv_durability_pct, p);
            }

            @Override
            public String enchanted() {
                return c.getString(R.string.projects_feat_inv_enchanted);
            }
        };
        return mWords;
    }

    /** A slot as a screen reader says it: the item (or "Boş"), its place where that matters, held, waiting. */
    private String label(InvLayout.Spot spot, @Nullable InvItem item, boolean held, boolean waiting) {
        StringBuilder out = new StringBuilder(item == null ? getString(R.string.projects_feat_inv_empty) : InvText.speech(item, lang(), words()));
        int place = 0;
        switch (spot.kind) {
            case HEAD: place = R.string.projects_feat_inv_head; break;
            case CHEST: place = R.string.projects_feat_inv_chest; break;
            case LEGS: place = R.string.projects_feat_inv_legs; break;
            case FEET: place = R.string.projects_feat_inv_feet; break;
            case OFFHAND: place = R.string.projects_feat_inv_offhand; break;
            default: break;
        }
        if (place != 0) out.append(", ").append(getString(place));
        if (spot.kind == InvLayout.Kind.HOTBAR) {
            out.append(", ").append(getString(R.string.projects_feat_inv_hotbar, spot.slot - InvLayout.HOTBAR_FIRST + 1));
        }
        if (held) out.append(", ").append(getString(R.string.projects_feat_inv_held));
        if (waiting) out.append(", ").append(getString(R.string.projects_feat_inv_waiting));
        return out.toString();
    }

    /** For tests and the picker: the newest frames' window items. */
    @Nullable
    Map<Integer, InvItem> windowItems() {
        return mController == null ? null : mController.window();
    }
}
