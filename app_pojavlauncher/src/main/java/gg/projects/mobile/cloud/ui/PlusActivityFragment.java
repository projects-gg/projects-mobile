package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudApi;
import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;

/**
 * "Etkinlik": what the assistant did, in three tabs. Canlı: its feed as it happens (messages, offers, deals, finds;
 * counterparts' texts are masked by the service). Alım ve satımlar: the trades, with the average, lowest and highest
 * price per item. Geçen hafta: the weekly report's numbers for this account.
 */
public final class PlusActivityFragment extends CloudScreen implements CloudService.LiveListener {
    public static final String TAG = "ProjectsPlusActivityFragment";
    private static final int FEED = 0;
    private static final int TRADES = 1;
    private static final int WEEK = 2;

    private int mTab = FEED;
    private final List<CloudModel.FeedItem> mFeed = new ArrayList<>();
    @Nullable
    private String mFeedNext;
    private boolean mFeedLoaded;
    private boolean mFeedFailed;
    private final List<CloudModel.Deal> mDeals = new ArrayList<>();
    @Nullable
    private String mDealsNext;
    private boolean mDealsLoaded;
    @Nullable
    private CloudApi.DealSummary mSummary;
    @Nullable
    private List<CloudModel.WeeklyAccount> mWeek;
    private boolean mWeekFailed;
    @Nullable
    private CloudService.LiveHandle mLive;
    private boolean mTabsShown;

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_plus_activity));
    }

    @Override
    protected void started() {
        CloudModel.Account a = account();
        if (a == null) return;
        if (mLive == null) mLive = mService.watch(a.id, true, this);
        tabs();
        load(false);
        ensureCatalog();
    }

    @Override
    protected void catalogArrived() {
        draw();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mLive != null) {
            mLive.close();
            mLive = null;
        }
    }

    private void tabs() {
        if (mTop == null || mTabsShown) return;
        mTabsShown = true;
        List<CharSequence> names = new ArrayList<>();
        names.add(getString(R.string.projects_plus_feed));
        names.add(getString(R.string.projects_plus_trades));
        if (mService.plusFeature("report")) names.add(getString(R.string.projects_plus_week));
        LinearLayout tabs = CloudUi.segmented(ctx(), names.toArray(new CharSequence[0]), mTab, i -> {
            mTab = i;
            load(false);
            draw();
            if (mScroll != null) mScroll.scrollTo(0, 0);
        });
        tabs.setPadding(0, CloudUi.dp(ctx(), 2), 0, CloudUi.dp(ctx(), 6));
        mTop.addView(tabs);
        mTop.setVisibility(View.VISIBLE);
    }

    private void load(boolean more) {
        final CloudModel.Account a = account();
        if (a == null) return;
        switch (mTab) {
            case TRADES:
                if (mDealsLoaded && !more) return;
                mService.deals(a.id, more ? mDealsNext : null, new CloudService.Result<CloudApi.DealPage>() {
                    @Override
                    public void ok(CloudApi.DealPage page) {
                        if (!more) mDeals.clear();
                        mDeals.addAll(page.deals);
                        mDealsNext = page.next;
                        mDealsLoaded = true;
                        if (alive()) draw();
                    }

                    @Override
                    public void failed(CloudProblem problem) {
                        mDealsLoaded = true;
                        if (alive()) draw();
                    }
                });
                if (!more && mService.plusFeature("report")) {
                    mService.dealSummary(a.id, new CloudService.Result<CloudApi.DealSummary>() {
                        @Override
                        public void ok(CloudApi.DealSummary value) {
                            mSummary = value;
                            if (alive() && mTab == TRADES) draw();
                        }

                        @Override
                        public void failed(CloudProblem problem) {}
                    });
                }
                break;
            case WEEK:
                if (mWeek != null) return;
                mService.weekly(new CloudService.Result<List<CloudModel.WeeklyAccount>>() {
                    @Override
                    public void ok(List<CloudModel.WeeklyAccount> value) {
                        mWeek = value;
                        mWeekFailed = false;
                        if (alive()) draw();
                    }

                    @Override
                    public void failed(CloudProblem problem) {
                        mWeekFailed = true;
                        if (alive()) draw();
                    }
                });
                break;
            default:
                if (mFeedLoaded && !more) return;
                mService.feed(a.id, more ? mFeedNext : null, new CloudService.Result<CloudApi.FeedPage>() {
                    @Override
                    public void ok(CloudApi.FeedPage page) {
                        if (!more) mFeed.clear();
                        mFeed.addAll(page.items);
                        mFeedNext = page.next;
                        mFeedLoaded = true;
                        mFeedFailed = false;
                        if (alive()) draw();
                    }

                    @Override
                    public void failed(CloudProblem problem) {
                        mFeedFailed = !mFeedLoaded;
                        mFeedLoaded = true;
                        if (alive()) draw();
                    }
                });
                break;
        }
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a != null) mHeader.setCaption(a.name);
        if (getView() != null && mContent != null && mContent.getChildCount() == 0) draw();
    }

    private void draw() {
        LinearLayout content = clear();
        if (content == null) return;
        switch (mTab) {
            case TRADES: trades(content); break;
            case WEEK: week(content); break;
            default: feed(content); break;
        }
    }

    private View more(Context c, View.OnClickListener click) {
        android.widget.Button button = CloudUi.textButton(c, getString(R.string.projects_cloud_console_earlier), click);
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(button);
        box.setLayoutParams(CloudUi.matchWrap());
        return box;
    }

    // ---- the live feed ----

    private void feed(LinearLayout content) {
        Context c = ctx();
        if (!mFeedLoaded) {
            content.addView(CloudUi.loading(c));
            return;
        }
        if (mFeedFailed) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_cloud_error_host_offline)));
            return;
        }
        if (mFeed.isEmpty()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.AFK, getString(R.string.projects_plus_feed_empty)));
            return;
        }
        content.addView(CloudUi.gap(c, 6));
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        long now = mService.nowSeconds();
        for (CloudModel.FeedItem item : mFeed) rows.add(feedRow(c, item, now));
        CloudUi.fill(group, rows);
        content.addView(group);
        if (mFeedNext != null) content.addView(more(c, v -> load(true)));
    }

    private String item(@Nullable String id, @Nullable Long qty) {
        if (id == null) return "";
        String name = mService.itemName(id, lang());
        return CloudWords.qtyName(ctx(), qty == null ? 1 : qty, name);
    }

    private String money(@Nullable Long value) {
        return value == null ? "" : CloudWords.money(ctx(), value);
    }

    private View feedRow(Context c, CloudModel.FeedItem f, long now) {
        String who = f.counterpart == null ? "" : f.counterpart;
        String title;
        int icon = R.drawable.projects_ic_pulse;
        switch (f.kind == null ? "" : f.kind) {
            case "pm_in": title = getString(R.string.projects_plus_feed_pm_in, who); icon = R.drawable.projects_ic_chat; break;
            case "pm_out": title = getString(R.string.projects_plus_feed_pm_out, who); icon = R.drawable.projects_ic_chat; break;
            case "offer": title = getString(R.string.projects_plus_feed_offer, who, item(f.item, f.qty), money(f.price)); icon = R.drawable.projects_ic_arrows_left_right; break;
            case "counter": title = getString(R.string.projects_plus_feed_counter, who, money(f.price)); icon = R.drawable.projects_ic_arrows_left_right; break;
            case "deal": title = getString(R.string.projects_plus_feed_deal, who, item(f.item, f.qty), money(f.price)); icon = R.drawable.projects_ic_check_circle; break;
            case "approval": title = getString(R.string.projects_plus_feed_approval, item(f.item, f.qty)); icon = R.drawable.projects_ic_check_circle; break;
            case "approval_answer": title = getString(R.string.projects_plus_feed_approval_answer); icon = R.drawable.projects_ic_check_circle; break;
            case "trade_started": title = getString(R.string.projects_plus_feed_trade_started, who); icon = R.drawable.projects_ic_arrows_left_right; break;
            case "trade_done": title = getString(R.string.projects_plus_feed_trade_done, who); icon = R.drawable.projects_ic_check_circle; break;
            case "trade_failed": title = getString(R.string.projects_plus_feed_trade_failed, who); icon = R.drawable.projects_ic_warning_circle; break;
            case "trade_cancelled": title = getString(R.string.projects_plus_feed_trade_cancelled, who); icon = R.drawable.projects_ic_x; break;
            case "found": title = getString(R.string.projects_plus_feed_found, item(f.item, f.qty), money(f.price)); icon = R.drawable.projects_ic_backpack; break;
            case "suspicious": title = getString(R.string.projects_plus_feed_suspicious); icon = R.drawable.projects_ic_warning_circle; break;
            case "ai_stopped": title = getString(R.string.projects_plus_feed_ai_stopped); break;
            case "ai_resumed": title = getString(R.string.projects_plus_feed_ai_resumed); break;
            case "summary": title = getString(R.string.projects_plus_feed_summary); icon = R.drawable.projects_ic_history; break;
            case "review": title = getString(R.string.projects_plus_feed_review); icon = R.drawable.projects_ic_warning_circle; break;
            default: title = getString(R.string.projects_plus_feed_other); break;
        }
        CloudUi.Row row = CloudUi.row(c, icon, title, null);
        row.title.setTextSize(14.5f);
        if (f.text != null && !f.text.isEmpty()) row.setLine(f.text, R.color.projects_ash_300);
        row.count.setText(CloudWords.past(c, f.at, now, false));
        row.count.setTextColor(CloudUi.color(c, R.color.projects_ash_400));
        row.count.setTextSize(12.5f);
        row.count.setVisibility(View.VISIBLE);
        return row.view;
    }

    // ---- trades ----

    private void trades(LinearLayout content) {
        Context c = ctx();
        if (!mDealsLoaded) {
            content.addView(CloudUi.loading(c));
            return;
        }
        if (mDeals.isEmpty()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.PACK, getString(R.string.projects_plus_trades_empty)));
            return;
        }
        CloudApi.DealSummary summary = mSummary;
        if (summary != null && !summary.items.isEmpty()) {
            LinearLayout group = CloudUi.group(c);
            List<View> rows = new ArrayList<>();
            for (CloudModel.ItemSummary s : summary.items) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 10), CloudUi.dp(c, 16), CloudUi.dp(c, 10));
                row.addView(CloudItemArt.tile(c, s.item, 36), new LinearLayout.LayoutParams(CloudUi.dp(c, 36), CloudUi.dp(c, 36)));
                LinearLayout texts = new LinearLayout(c);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(CloudUi.text(c, mService.itemName(s.item, lang()), 15, R.color.projects_bone_100, true));
                String line = getString(R.string.projects_plus_summary_avg) + " " + CloudWords.money(c, s.avgUnit) + ", "
                        + getString(R.string.projects_plus_summary_min).toLowerCase(CloudWords.locale(c)) + " " + CloudWords.money(c, s.minUnit) + ", "
                        + getString(R.string.projects_plus_summary_max).toLowerCase(CloudWords.locale(c)) + " " + CloudWords.money(c, s.maxUnit);
                texts.addView(CloudUi.text(c, line, 13, R.color.projects_ash_300, false));
                LinearLayout.LayoutParams lp = CloudUi.weight(1f);
                lp.setMarginStart(CloudUi.dp(c, 12));
                row.addView(texts, lp);
                rows.add(row);
            }
            content.addView(CloudUi.gap(c, 6));
            CloudUi.fill(group, rows);
            content.addView(group);
            content.addView(CloudUi.gap(c, 12));
        } else {
            content.addView(CloudUi.gap(c, 6));
        }
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        long now = mService.nowSeconds();
        for (CloudModel.Deal d : mDeals) rows.add(dealRow(c, d, now));
        CloudUi.fill(group, rows);
        content.addView(group);
        if (mDealsNext != null) content.addView(more(c, v -> load(true)));
    }

    private View dealRow(Context c, CloudModel.Deal d, long now) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 64));
        row.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 10), CloudUi.dp(c, 16), CloudUi.dp(c, 10));
        row.addView(CloudItemArt.tile(c, d.item, 40), new LinearLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(CloudUi.text(c, item(d.item, d.qty), 15, R.color.projects_bone_100, true));
        String line;
        int color = R.color.projects_ash_300;
        switch (d.status == null ? "" : d.status) {
            case "failed": line = getString(R.string.projects_plus_trade_failed); color = R.color.projects_flare_400; break;
            case "cancelled": line = getString(R.string.projects_plus_trade_cancelled); break;
            case "review": line = getString(R.string.projects_plus_trade_review); color = R.color.projects_gold_300; break;
            default:
                line = getString("sell".equals(d.side) ? R.string.projects_plus_trade_sold : R.string.projects_plus_trade_bought, d.counterpart)
                        + ", " + getString("approved".equals(d.approval) ? R.string.projects_plus_trade_approved : R.string.projects_plus_trade_auto)
                        .toLowerCase(CloudWords.locale(c));
                break;
        }
        texts.addView(CloudUi.text(c, line, 13, color, false));
        texts.addView(CloudUi.text(c, CloudWords.past(c, d.at, now, true), 12, R.color.projects_ash_400, false));
        LinearLayout.LayoutParams lp = CloudUi.weight(1f);
        lp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(texts, lp);
        TextView price = CloudUi.figure(c, CloudWords.money(c, d.price), 15, "completed".equals(d.status) ? R.color.projects_bone_100 : R.color.projects_ash_400);
        row.addView(price);
        return row;
    }

    // ---- the weekly report ----

    private void week(LinearLayout content) {
        Context c = ctx();
        if (mWeek == null) {
            if (mWeekFailed) content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_cloud_error_unavailable)));
            else content.addView(CloudUi.loading(c));
            return;
        }
        CloudModel.WeeklyAccount mine = null;
        String id = accountId();
        for (CloudModel.WeeklyAccount w : mWeek) if (w.id.equals(id)) mine = w;
        if (mine == null) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.LOGS, getString(R.string.projects_plus_week_empty)));
            return;
        }
        content.addView(CloudUi.gap(c, 6));
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        rows.add(figure(c, getString(R.string.projects_plus_week_pms), String.valueOf(mine.pms)));
        rows.add(figure(c, getString(R.string.projects_plus_week_bought), String.valueOf(mine.bought)));
        rows.add(figure(c, getString(R.string.projects_plus_week_sold), String.valueOf(mine.sold)));
        rows.add(figure(c, getString(R.string.projects_plus_week_spent), CloudWords.money(c, mine.spent)));
        rows.add(figure(c, getString(R.string.projects_plus_week_earned), CloudWords.money(c, mine.earned)));
        rows.add(figure(c, getString(R.string.projects_plus_week_below), CloudWords.money(c, mine.belowLimit)));
        rows.add(figure(c, getString(R.string.projects_plus_week_above), CloudWords.money(c, mine.aboveFloor)));
        rows.add(figure(c, getString(R.string.projects_plus_week_blocked), String.valueOf(mine.blocked)));
        String pct = CloudWords.turkish(c) ? String.format(Locale.ROOT, "%%%d", mine.allowancePct) : String.format(Locale.ROOT, "%d%%", mine.allowancePct);
        rows.add(figure(c, getString(R.string.projects_plus_week_allowance), pct));
        CloudUi.fillFigures(group, rows);
        content.addView(group);
    }

    private static View figure(Context c, String label, String value) {
        return CloudUi.figureRow(c, label, value).view;
    }

    // ---- the live socket ----

    @Override
    public void liveState(CloudService.Live state) {}

    @Override
    public void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}

    @Override
    public void feed(List<CloudModel.FeedItem> items, boolean snapshot) {
        if (snapshot && mFeedLoaded) return;
        List<CloudModel.FeedItem> fresh = new ArrayList<>();
        for (CloudModel.FeedItem item : items) {
            boolean known = false;
            for (CloudModel.FeedItem have : mFeed) if (have.id.equals(item.id)) known = true;
            if (!known) fresh.add(item);
        }
        if (fresh.isEmpty()) return;
        // The feed is newest first; frames bring the newest last.
        for (CloudModel.FeedItem item : fresh) mFeed.add(0, item);
        mFeedLoaded = true;
        if (alive() && mTab == FEED) draw();
    }

    @Override
    public void approval(CloudModel.Approval approval) {}

    @Override
    public void hostOnline(boolean online) {}
}
