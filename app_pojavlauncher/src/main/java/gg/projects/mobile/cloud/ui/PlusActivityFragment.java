package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import gg.projects.mobile.cloud.CloudApi;
import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusModel;
import gg.projects.mobile.ui.AppDates;

/**
 * "Akış": what the assistant did, in three tabs. Akış: the audit log (every step and how it ended, live from the
 * machine; what this phone saw stays 7 days and shows while the machine is away), filtered by kind, under day headings.
 * Takas geçmişi: the receipts, with each item's average, lowest and highest price. Geçen hafta: the weekly report's
 * numbers for this account. Counterparts' texts are masked by the service; an unsuitable one stays folded until "Göster".
 */
public final class PlusActivityFragment extends CloudScreen implements CloudService.LiveListener {
    public static final String TAG = "ProjectsPlusActivityFragment";
    private static final int AUDIT = 0;
    private static final int RECEIPTS = 1;
    private static final int WEEK = 2;
    private static final String[] GROUPS = {null, "deals", "messages", "warnings"};

    private int mTab = AUDIT;
    private int mGroup = 0;
    /** Rows drawn at once (the phone keeps up to 3.000 entries; each live batch draws the log again). */
    private static final int AUDIT_PAGE = 100;
    private int mDrawLimit = AUDIT_PAGE;
    /** The log as shown: what the phone kept and what the machine sent, newest first, each entry once. */
    private final List<PlusModel.AuditEntry> mAudit = new ArrayList<>();
    private boolean mKeptRead;
    private boolean mAuditLoaded;
    /** {@code host_offline}, {@code feature_off} or another failure of the last page; null when it came. */
    @Nullable
    private String mAuditProblem;
    private boolean mAuditMore;
    private boolean mAuditLoading;
    private final Set<Long> mShown = new HashSet<>();
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
        ensureItemNames();
    }

    @Override
    protected void namesArrived() {
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
        names.add(getString(R.string.projects_audit_tab));
        names.add(getString(R.string.projects_receipt_tab));
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

    @Nullable
    private PlusModel.Server server() {
        String id = accountId();
        CloudModel.PlusDoc doc = id == null ? null : mService.lastPlus(id);
        return doc == null ? null : doc.server;
    }

    private void load(final boolean more) {
        final CloudModel.Account a = account();
        if (a == null) return;
        switch (mTab) {
            case RECEIPTS:
                if (mReceiptsLoaded && !more) return;
                mService.receipts(a.id, more ? mReceiptsNext : null, new CloudService.Result<PlusModel.ReceiptPage>() {
                    @Override
                    public void ok(PlusModel.ReceiptPage page) {
                        if (!more) mReceipts.clear();
                        mReceipts.addAll(page.receipts);
                        mReceiptsNext = page.next;
                        mReceiptsLoaded = true;
                        mReceiptsFailed = false;
                        if (alive() && mTab == RECEIPTS) draw();
                    }

                    @Override
                    public void failed(CloudProblem problem) {
                        mReceiptsFailed = !mReceiptsLoaded;
                        mReceiptsLoaded = true;
                        if (alive() && mTab == RECEIPTS) draw();
                    }
                });
                if (!more && mService.plusFeature("report")) {
                    mService.dealSummary(a.id, new CloudService.Result<CloudApi.DealSummary>() {
                        @Override
                        public void ok(CloudApi.DealSummary value) {
                            mSummary = value;
                            if (alive() && mTab == RECEIPTS) draw();
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
                        if (alive() && mTab == WEEK) draw();
                    }

                    @Override
                    public void failed(CloudProblem problem) {
                        mWeekFailed = true;
                        if (alive() && mTab == WEEK) draw();
                    }
                });
                break;
            default:
                if (!mKeptRead) {
                    mKeptRead = true;
                    mService.keptAudit(a.id, new CloudService.Result<List<PlusModel.AuditEntry>>() {
                        @Override
                        public void ok(List<PlusModel.AuditEntry> value) {
                            merge(value);
                            if (alive() && mTab == AUDIT) draw();
                        }

                        @Override
                        public void failed(CloudProblem problem) {}
                    });
                }
                if ((mAuditLoaded && !more) || mAuditLoading) return;
                mAuditLoading = true;
                Long before = more && !mAudit.isEmpty() ? mAudit.get(mAudit.size() - 1).s : null;
                mService.audit(a.id, before, GROUPS[mGroup], new CloudService.Result<PlusModel.AuditPage>() {
                    @Override
                    public void ok(PlusModel.AuditPage page) {
                        mAuditLoading = false;
                        merge(page.items);
                        mAuditMore = page.more;
                        mAuditLoaded = true;
                        mAuditProblem = null;
                        if (alive() && mTab == AUDIT) draw();
                    }

                    @Override
                    public void failed(CloudProblem problem) {
                        mAuditLoading = false;
                        mAuditLoaded = true;
                        mAuditMore = false;
                        mAuditProblem = problem.is("feature_off") ? "feature_off"
                                : problem.kind == CloudProblem.Kind.HOST_OFFLINE ? "host_offline" : "failed";
                        if (alive() && mTab == AUDIT) draw();
                    }
                });
                break;
        }
    }

    /** Adds entries to the log once each (by their sequence), newest first. */
    private void merge(List<PlusModel.AuditEntry> entries) {
        boolean changed = false;
        for (PlusModel.AuditEntry e : entries) {
            if (mShown.add(e.s)) {
                mAudit.add(e);
                changed = true;
            }
        }
        if (changed) Collections.sort(mAudit, (x, y) -> Long.compare(y.s, x.s));
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
            case RECEIPTS: receipts(content); break;
            case WEEK: week(content); break;
            default: audit(content); break;
        }
    }

    private View more(Context c, int words, View.OnClickListener click) {
        android.widget.Button button = CloudUi.textButton(c, getString(words), click);
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(button);
        box.setLayoutParams(CloudUi.matchWrap());
        return box;
    }

    // ---- the audit log ----

    private void audit(LinearLayout content) {
        final Context c = ctx();
        content.addView(CloudUi.segmented(c, new CharSequence[]{getString(R.string.projects_audit_filter_all),
                getString(R.string.projects_audit_filter_deals), getString(R.string.projects_audit_filter_messages),
                getString(R.string.projects_audit_filter_warnings)}, mGroup, i -> {
            mGroup = i;
            mDrawLimit = AUDIT_PAGE;
            draw();
        }));
        if ("feature_off".equals(mAuditProblem)) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.LOGS, getString(R.string.projects_audit_off)));
            return;
        }
        boolean away = "host_offline".equals(mAuditProblem) || "failed".equals(mAuditProblem);
        List<PlusModel.AuditEntry> shown = new ArrayList<>();
        for (PlusModel.AuditEntry e : mAudit) if (e.in(GROUPS[mGroup])) shown.add(e);
        if (away) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_ash_300,
                    getString(mAudit.isEmpty() ? R.string.projects_audit_offline_empty : R.string.projects_audit_offline)));
        }
        if (!mAuditLoaded && mAudit.isEmpty()) {
            content.addView(CloudUi.loading(c));
            return;
        }
        if (mAudit.isEmpty()) {
            if (!away) content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.AFK, getString(R.string.projects_audit_empty)));
            return;
        }
        if (shown.isEmpty()) {
            content.addView(CloudUi.note(c, getString(R.string.projects_audit_none_here)));
        } else {
            long now = mService.nowMs();
            String day = null;
            LinearLayout group = null;
            int drawn = Math.min(shown.size(), mDrawLimit);
            for (int i = 0; i < drawn; i++) {
                PlusModel.AuditEntry e = shown.get(i);
                String heading = dayHeading(c, e.t, now);
                if (!heading.equals(day)) {
                    day = heading;
                    content.addView(CloudUi.section(c, heading));
                    group = CloudUi.group(c);
                    content.addView(group);
                } else if (group != null) {
                    group.addView(CloudUi.textDivider(c));
                }
                if (group != null) group.addView(entryRow(c, e));
            }
        }
        // Older entries: first the ones the phone holds, then the service's next page.
        if (shown.size() > mDrawLimit) {
            content.addView(more(c, R.string.projects_audit_older, v -> {
                mDrawLimit += AUDIT_PAGE;
                draw();
            }));
        } else if (mAuditMore && !away) {
            content.addView(more(c, R.string.projects_audit_older, v -> {
                mDrawLimit = shown.size() + AUDIT_PAGE;
                load(true);
            }));
        }
        content.addView(CloudUi.note(c, getString(R.string.projects_audit_kept)));
        content.addView(CloudUi.note(c, getString(R.string.projects_ai_note)));
    }

    private static String dayHeading(Context c, long ms, long nowMs) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(ms);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(nowMs);
        long a = day.get(Calendar.YEAR) * 400L + day.get(Calendar.DAY_OF_YEAR);
        long b = today.get(Calendar.YEAR) * 400L + today.get(Calendar.DAY_OF_YEAR);
        if (a == b) return c.getString(R.string.projects_audit_today);
        if (b - a == 1) return c.getString(R.string.projects_audit_yesterday);
        return AppDates.day(c, ms, nowMs);
    }

    /** One entry: the time in its column, the line (an icon only where the kind means something), a message's text under it. */
    private View entryRow(final Context c, final PlusModel.AuditEntry e) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 12), CloudUi.dp(c, 16), CloudUi.dp(c, 12));
        TextView time = CloudUi.time(c, 13, R.color.projects_ash_400);
        time.setText(AppDates.time(e.t));
        row.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout first = new LinearLayout(c);
        first.setOrientation(LinearLayout.HORIZONTAL);
        first.setGravity(Gravity.TOP);
        int[] icon = PlusWords.auditIcon(e.k);
        ImageView glyph = new ImageView(c);
        if (icon[0] != 0) {
            glyph.setImageResource(icon[0]);
            glyph.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, icon[1])));
        }
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(CloudUi.dp(c, 16), CloudUi.dp(c, 16));
        glyphLp.topMargin = CloudUi.dp(c, 2);
        glyphLp.setMarginEnd(CloudUi.dp(c, 8));
        first.addView(glyph, glyphLp);
        String line = PlusWords.auditLine(c, e, server(), this::itemName);
        TextView words = CloudUi.text(c, line, 14.5f, R.color.projects_bone_100, false);
        words.setLineSpacing(0, 1.12f);
        first.addView(words, CloudUi.weight(1f));
        texts.addView(first);
        if (e.x != null && !e.x.isEmpty()) {
            final TextView said = CloudUi.text(c, e.h && !mShownHidden.contains(e.s) ? getString(R.string.projects_audit_hidden) : e.x, 14,
                    R.color.projects_ash_300, false);
            said.setLineSpacing(0, 1.12f);
            said.setPadding(0, CloudUi.dp(c, 4), 0, 0);
            texts.addView(said);
            if (e.h && !mShownHidden.contains(e.s)) {
                android.widget.Button show = CloudUi.textButton(c, getString(R.string.projects_audit_show), v -> {
                    mShownHidden.add(e.s);
                    draw();
                });
                show.setPadding(0, 0, CloudUi.dp(c, 12), 0);
                texts.addView(show, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(texts, textsLp);
        row.setContentDescription(time.getText() + ", " + line + (e.x != null && !(e.h && !mShownHidden.contains(e.s)) ? ", " + e.x : ""));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setLayoutParams(CloudUi.matchWrap());
        return row;
    }

    /** Folded entries the player opened ("Göster"), by sequence: they stay open while the screen is up. */
    private final Set<Long> mShownHidden = new HashSet<>();

    // ---- receipts ----

    private void receipts(LinearLayout content) {
        Context c = ctx();
        if (!mReceiptsLoaded) {
            content.addView(CloudUi.loading(c));
            return;
        }
        if (mReceiptsFailed) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_receipt_failed)));
            content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_retry), v -> {
                mReceiptsLoaded = false;
                load(false);
                draw();
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        if (mReceipts.isEmpty()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.PACK, getString(R.string.projects_receipt_empty)));
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
                texts.addView(CloudUi.text(c, itemName(s.item), 15, R.color.projects_bone_100, true));
                // One price (a single trade, or all the same): said once.
                String line = s.minUnit == s.maxUnit ? CloudWords.money(c, s.avgUnit)
                        : getString(R.string.projects_plus_summary_avg) + " " + CloudWords.money(c, s.avgUnit) + ", "
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
        boolean money = false;
        for (PlusModel.Receipt r : mReceipts) {
            rows.add(receiptRow(c, r, now));
            money |= r.money();
        }
        CloudUi.fill(group, rows);
        content.addView(group);
        if (mReceiptsNext != null) content.addView(more(c, R.string.projects_receipt_more, v -> load(true)));
        // A receipt with a bare figure could read as real money.
        if (money) content.addView(CloudUi.note(c, getString(R.string.projects_receipt_money_note)));
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
        if (mine.weekFrom > 0 && mine.weekTo > mine.weekFrom) {
            long nowMs = mService.nowMs();
            // The window ends the next Monday 04:00: its last day is the Sunday before.
            content.addView(CloudUi.note(c, getString(R.string.projects_plus_week_range, AppDates.day(c, mine.weekFrom * 1000L, nowMs),
                    AppDates.day(c, (mine.weekTo - 86_400L) * 1000L, nowMs))));
        } else {
            content.addView(CloudUi.gap(c, 6));
        }
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
        content.addView(CloudUi.note(c, getString(R.string.projects_ai_note)));
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
    public void audit(List<PlusModel.AuditEntry> items) {
        int before = mAudit.size();
        merge(items);
        if (mAudit.size() != before && alive() && mTab == AUDIT) draw();
    }

    @Override
    public void approval(CloudModel.Approval approval) {}

    @Override
    public void hostOnline(boolean online) {
        // The machine is back: the page the phone could not read comes now.
        if (online && "host_offline".equals(mAuditProblem)) {
            mAuditLoaded = false;
            if (mTab == AUDIT) load(false);
        }
    }
}
