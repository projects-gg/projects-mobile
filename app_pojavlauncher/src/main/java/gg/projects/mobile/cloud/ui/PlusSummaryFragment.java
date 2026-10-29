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
import gg.projects.mobile.cloud.PlusModel;
import gg.projects.mobile.ui.AppDates;

/**
 * "Özet" (PLUS-ACTIVITY: it was "Rapor"; the site's and the launcher's same name; the "Günlük özet" notification opens
 * it): last week's figures for this account in three groups (what was bought, what was sold, what ProBot AI did), then
 * the trade history of the last 30 days: each item's average price (its lowest and highest under it), then every trade
 * with its receipt's details. The week's figures only while Studio's report is on.
 */
public final class PlusSummaryFragment extends CloudScreen {
    public static final String TAG = "ProjectsPlusSummaryFragment";

    private final List<PlusModel.Receipt> mReceipts = new ArrayList<>();
    @Nullable
    private String mReceiptsNext;
    private boolean mReceiptsLoaded;
    private boolean mReceiptsFailed;
    @Nullable
    private CloudApi.DealSummary mSummary;
    @Nullable
    private List<CloudModel.WeeklyAccount> mWeek;
    private boolean mWeekFailed;

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_plus_report));
    }

    @Override
    protected void started() {
        loadReceipts(false);
        loadWeek();
        ensureItemNames();
    }

    @Override
    protected void namesArrived() {
        draw();
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a != null) mHeader.setCaption(a.name);
        if (getView() != null && mContent != null && mContent.getChildCount() == 0) draw();
    }

    @Nullable
    private PlusModel.Server server() {
        String id = accountId();
        CloudModel.PlusDoc doc = id == null ? null : mService.lastPlus(id);
        return doc == null ? null : doc.server;
    }

    private void loadReceipts(final boolean more) {
        final CloudModel.Account a = account();
        if (a == null || (mReceiptsLoaded && !more)) return;
        mService.receipts(a.id, more ? mReceiptsNext : null, new CloudService.Result<PlusModel.ReceiptPage>() {
            @Override
            public void ok(PlusModel.ReceiptPage page) {
                if (!more) mReceipts.clear();
                mReceipts.addAll(page.receipts);
                mReceiptsNext = page.next;
                mReceiptsLoaded = true;
                mReceiptsFailed = false;
                if (alive()) draw();
            }

            @Override
            public void failed(CloudProblem problem) {
                mReceiptsFailed = !mReceiptsLoaded;
                mReceiptsLoaded = true;
                if (alive()) draw();
            }
        });
        if (!more && mService.plusFeature("report")) {
            mService.dealSummary(a.id, new CloudService.Result<CloudApi.DealSummary>() {
                @Override
                public void ok(CloudApi.DealSummary value) {
                    mSummary = value;
                    if (alive()) draw();
                }

                @Override
                public void failed(CloudProblem problem) {}
            });
        }
    }

    private void loadWeek() {
        if (mWeek != null || !mService.plusFeature("report")) return;
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
    }

    private void draw() {
        LinearLayout content = clear();
        if (content == null) return;
        if (mService.plusFeature("report")) week(content);
        receipts(content);
    }

    private View more(Context c, int words, View.OnClickListener click) {
        android.widget.Button button = CloudUi.textButton(c, getString(words), click);
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(button);
        box.setLayoutParams(CloudUi.matchWrap());
        return box;
    }

    // ---- last week ----

    private void week(LinearLayout content) {
        Context c = ctx();
        content.addView(CloudUi.section(c, getString(R.string.projects_plus_week)));
        if (mWeek == null) {
            if (mWeekFailed) content.addView(CloudUi.note(c, getString(R.string.projects_cloud_error_unavailable)));
            else content.addView(CloudUi.loading(c));
            return;
        }
        CloudModel.WeeklyAccount mine = null;
        String id = accountId();
        for (CloudModel.WeeklyAccount w : mWeek) if (w.id.equals(id)) mine = w;
        if (mine == null) {
            content.addView(CloudUi.note(c, getString(R.string.projects_plus_week_empty)));
            return;
        }
        if (mine.weekFrom > 0 && mine.weekTo > mine.weekFrom) {
            long nowMs = mService.nowMs();
            // The window ends the next Monday 04:00: its last day is the Sunday before.
            content.addView(CloudUi.note(c, getString(R.string.projects_plus_week_range, AppDates.day(c, mine.weekFrom * 1000L, nowMs),
                    AppDates.day(c, (mine.weekTo - 86_400L) * 1000L, nowMs))));
        }
        PlusModel.Server server = server();
        // Three groups (the site's): one long list paired a buy's figure with a sale's.
        List<View> buys = new ArrayList<>();
        buys.add(figure(c, R.string.projects_plus_week_trades, String.valueOf(mine.bought)));
        buys.add(figure(c, R.string.projects_plus_week_spent, PlusWords.money(c, mine.spent, server)));
        if (mine.belowLimit > 0) buys.add(figure(c, R.string.projects_plus_week_below, PlusWords.money(c, mine.belowLimit, server)));
        content.addView(weekGroup(c, R.string.projects_plus_week_buys, buys));
        List<View> sales = new ArrayList<>();
        sales.add(figure(c, R.string.projects_plus_week_trades, String.valueOf(mine.sold)));
        sales.add(figure(c, R.string.projects_plus_week_earned, PlusWords.money(c, mine.earned, server)));
        if (mine.aboveFloor > 0) sales.add(figure(c, R.string.projects_plus_week_above, PlusWords.money(c, mine.aboveFloor, server)));
        content.addView(weekGroup(c, R.string.projects_plus_week_sales, sales));
        List<View> ai = new ArrayList<>();
        ai.add(figure(c, R.string.projects_plus_week_pms, String.valueOf(mine.pms)));
        ai.add(figure(c, R.string.projects_plus_week_blocked, String.valueOf(mine.blocked)));
        String pct = CloudWords.turkish(c) ? String.format(Locale.ROOT, "%%%d", mine.allowancePct) : String.format(Locale.ROOT, "%d%%", mine.allowancePct);
        ai.add(figure(c, R.string.projects_plus_week_allowance, pct));
        content.addView(weekGroup(c, R.string.projects_plus_assistant, ai));
        // The week is what the assistant did (27 Oct 2026: it can make mistakes).
        content.addView(CloudUi.note(c, getString(R.string.projects_ai_note)));
    }

    private View figure(Context c, int label, String value) {
        return CloudUi.figureRow(c, getString(label), value).view;
    }

    /** One of the week's groups: its name over its figures, in one panel. */
    private View weekGroup(Context c, int title, List<View> rows) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = CloudUi.matchWrap();
        lp.topMargin = CloudUi.dp(c, 10);
        box.setLayoutParams(lp);
        LinearLayout group = CloudUi.group(c);
        TextView name = CloudUi.text(c, getString(title), 13.5f, R.color.projects_ash_300, true);
        name.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 12), CloudUi.dp(c, 16), CloudUi.dp(c, 2));
        group.addView(name);
        CloudUi.fillFigures(group, rows);
        box.addView(group);
        return box;
    }

    // ---- the trade history ----

    private void receipts(LinearLayout content) {
        Context c = ctx();
        // "Takas geçmişi" and how far it goes, apart by a gap and tone (no middle dot in UI text).
        content.addView(CloudUi.section(c, gg.projects.mobile.ui.Apart.of(c, getString(R.string.projects_receipt_tab),
                getString(R.string.projects_receipt_period), R.color.projects_ash_400)));
        if (!mReceiptsLoaded) {
            content.addView(CloudUi.loading(c));
            return;
        }
        if (mReceiptsFailed) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_receipt_failed)));
            content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_retry), v -> {
                mReceiptsLoaded = false;
                loadReceipts(false);
                draw();
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        CloudApi.DealSummary summary = mSummary;
        boolean items = summary != null && !summary.items.isEmpty();
        if (!items && mReceipts.isEmpty()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.PACK, getString(R.string.projects_receipt_empty)));
            return;
        }
        if (items) {
            LinearLayout group = CloudUi.group(c);
            List<View> rows = new ArrayList<>();
            for (CloudModel.ItemSummary s : summary.items) rows.add(itemRow(c, s));
            CloudUi.fill(group, rows);
            content.addView(group);
            // The table counts by the piece; the trades under it say each one's whole amount.
            content.addView(CloudUi.note(c, getString(R.string.projects_receipt_per_item)));
        }
        if (!mReceipts.isEmpty()) {
            content.addView(CloudUi.section(c, getString(R.string.projects_receipt_trades)));
            LinearLayout group = CloudUi.group(c);
            List<View> rows = new ArrayList<>();
            long now = mService.nowSeconds();
            boolean money = false;
            for (PlusModel.Receipt r : mReceipts) {
                rows.add(receiptRow(c, r, now));
                money |= r.money();
            }
            CloudUi.fill(group, rows);
            content.addView(group);
            if (mReceiptsNext != null) content.addView(more(c, R.string.projects_receipt_more, v -> loadReceipts(true)));
            // A receipt with a bare figure could read as real money.
            if (money) content.addView(CloudUi.note(c, getString(R.string.projects_receipt_money_note)));
        }
    }

    /** An item's line of the table: its tile, its name and side, the average price, its lowest and highest under it. */
    private View itemRow(Context c, CloudModel.ItemSummary s) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 10), CloudUi.dp(c, 16), CloudUi.dp(c, 10));
        row.addView(CloudItemArt.tile(c, s.item, 36), new LinearLayout.LayoutParams(CloudUi.dp(c, 36), CloudUi.dp(c, 36)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(CloudUi.text(c, CloudWords.qtyName(c, s.qty, itemName(s.item)), 15, R.color.projects_bone_100, true));
        texts.addView(CloudUi.text(c, getString("sell".equals(s.side) ? R.string.projects_receipt_side_sold : R.string.projects_receipt_side_bought),
                13, R.color.projects_ash_400, false));
        LinearLayout.LayoutParams lp = CloudUi.weight(1f);
        lp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(texts, lp);
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        String avg = CloudWords.money(c, s.avgUnit);
        right.addView(CloudUi.figure(c, avg, 15, R.color.projects_bone_100));
        String range = s.minUnit == s.maxUnit ? null
                : getString(R.string.projects_receipt_range, CloudWords.money(c, s.minUnit), CloudWords.money(c, s.maxUnit));
        if (range != null) right.addView(CloudUi.text(c, range, 12.5f, R.color.projects_ash_400, false));
        row.addView(right);
        row.setContentDescription(itemName(s.item) + ", " + avg + (range == null ? "" : ", " + range));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return row;
    }

    private View receiptRow(Context c, PlusModel.Receipt r, long now) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 64));
        row.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 10), CloudUi.dp(c, 16), CloudUi.dp(c, 10));
        row.addView(CloudItemArt.tile(c, r.item, 40), new LinearLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        boolean sell = "sell".equals(r.side);
        String what = CloudWords.qtyName(c, r.qty, itemName(r.item));
        texts.addView(CloudUi.text(c, getString(sell ? R.string.projects_receipt_sold : R.string.projects_receipt_bought, what), 15,
                R.color.projects_bone_100, true));
        String party = getString(sell ? R.string.projects_receipt_party_sell : R.string.projects_receipt_party_buy, r.counterpart);
        String how;
        switch (r.approval) {
            case "approved": how = getString(R.string.projects_receipt_approved); break;
            case "owner": how = getString(R.string.projects_receipt_owner); break;
            default: how = getString(R.string.projects_receipt_auto); break;
        }
        texts.addView(CloudUi.text(c, party, 13, R.color.projects_ash_300, false));
        texts.addView(CloudUi.text(c, r.review ? getString(R.string.projects_receipt_review_note) : how, 12.5f, R.color.projects_ash_400, false));
        if (!r.money()) texts.addView(CloudUi.text(c, getString(R.string.projects_receipt_paid_with, itemName(r.pay)), 12.5f, R.color.projects_ash_400, false));
        texts.addView(CloudUi.text(c, CloudWords.past(c, r.at / 1000L, now, true), 12, R.color.projects_ash_400, false));
        LinearLayout.LayoutParams lp = CloudUi.weight(1f);
        lp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(texts, lp);
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        String priceText = PlusWords.price(c, r.price, r.pay, server(), this::itemName);
        right.addView(CloudUi.figure(c, priceText, 15, r.review ? R.color.projects_ash_400 : R.color.projects_bone_100));
        if (r.review) right.addView(CloudUi.text(c, getString(R.string.projects_receipt_review), 12.5f, R.color.projects_ash_300, false));
        row.addView(right);
        row.setContentDescription(what + ", " + party + ", " + priceText);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return row;
    }
}
