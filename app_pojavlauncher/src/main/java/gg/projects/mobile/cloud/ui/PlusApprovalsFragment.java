package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusModel;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.Haptics;

/**
 * "Onaylar" ("Seni bekleyenler"): what waits for the player's answer. A deal the assistant asks about (the item, who it
 * is with, the price, why it asks, how long it waits: no answer counts as no; "Onayla", "Reddet", and for a buy paid with
 * money "Fiyatı düşür"); "Takas sende", a deal agreed where the player makes the trade ("Takas oldu" / "Olmadı", 24 hours);
 * "İlk görüş", a typed custom item met in a trade window ("Evet, bu" / "Hayır"). Money decisions happen only here, never
 * from a notification; no gold on them (the launcher's and the site's rule). New ones arrive live; the last day's answered
 * ones stay below; the AI note once under the waiting ones.
 */
public final class PlusApprovalsFragment extends CloudScreen implements CloudService.LiveListener {
    public static final String TAG = "ProjectsPlusApprovalsFragment";

    private final List<CloudModel.Approval> mApprovals = new ArrayList<>();
    private boolean mLoaded;
    private boolean mFailed;
    @Nullable
    private String mDeciding;
    @Nullable
    private CloudService.LiveHandle mLive;
    private final List<TextView> mCountdowns = new ArrayList<>();
    private final List<CloudModel.Approval> mCounting = new ArrayList<>();
    private final Runnable mTick = this::tick;
    private String mShape = "";

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_plus_approvals));
    }

    @Override
    public void onResume() {
        mShape = "";
        super.onResume();
    }

    @Override
    protected void started() {
        CloudModel.Account a = account();
        if (a == null) return;
        if (mLive == null) mLive = mService.watch(a.id, true, this);
        load();
        ensureItemNames();
    }

    @Override
    protected void namesArrived() {
        mShape = "";
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mLive != null) {
            mLive.close();
            mLive = null;
        }
        View view = getView();
        if (view != null) view.removeCallbacks(mTick);
    }

    private void load() {
        CloudModel.Account a = account();
        if (a == null) return;
        mService.approvals(a.id, new CloudService.Result<List<CloudModel.Approval>>() {
            @Override
            public void ok(List<CloudModel.Approval> value) {
                mApprovals.clear();
                mApprovals.addAll(value);
                mLoaded = true;
                mFailed = false;
                if (alive()) render();
            }

            @Override
            public void failed(CloudProblem problem) {
                mFailed = !mLoaded;
                if (alive()) render();
            }
        });
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a != null) mHeader.setCaption(a.name);
        long now = mService.nowSeconds();
        StringBuilder key = new StringBuilder().append(mLoaded).append(mFailed).append(mDeciding).append('|');
        if (a != null && a.ai != null) key.append(a.ai.state).append('|');
        for (CloudModel.Approval ap : mApprovals) key.append(ap.id).append(ap.state).append(ap.pending(now)).append(',');
        if (key.toString().equals(mShape)) return;
        mShape = key.toString();
        build(now);
    }

    private void build(long now) {
        LinearLayout content = clear();
        if (content == null) return;
        Context c = ctx();
        mCountdowns.clear();
        mCounting.clear();
        if (!mLoaded) {
            if (mFailed) {
                content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_cloud_error_unavailable)));
                content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_retry), v -> load()),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                content.addView(CloudUi.loading(c));
            }
            return;
        }
        List<CloudModel.Approval> open = new ArrayList<>();
        List<CloudModel.Approval> closed = new ArrayList<>();
        for (CloudModel.Approval ap : mApprovals) (ap.pending(now) ? open : closed).add(ap);
        Collections.sort(open, (x, y) -> Long.compare(x.expiresAt, y.expiresAt));
        Collections.sort(closed, (x, y) -> Long.compare(y.createdAt, x.createdAt));
        if (open.isEmpty()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.DONE, getString(R.string.projects_plus_approvals_empty)));
        } else {
            for (CloudModel.Approval ap : open) content.addView(card(c, ap));
            // The one AI note of this surface: under what the assistant made and asks about (AFKPLUS-TONE 16).
            content.addView(CloudUi.note(c, getString(R.string.projects_ai_note)));
        }
        if (!closed.isEmpty()) {
            content.addView(CloudUi.section(c, getString(R.string.projects_plus_approvals_recent)));
            LinearLayout group = CloudUi.group(c);
            List<View> rows = new ArrayList<>();
            for (CloudModel.Approval ap : closed) rows.add(closedRow(c, ap));
            CloudUi.fill(group, rows);
            content.addView(group);
        }
        tick();
    }

    @Nullable
    private PlusModel.Server server() {
        CloudModel.PlusDoc doc = mService.lastPlus(accountId() == null ? "" : accountId());
        return doc == null ? null : doc.server;
    }

    private String title(CloudModel.Approval ap) {
        return CloudWords.qtyName(ctx(), ap.qty, itemName(ap.item));
    }

    private String price(CloudModel.Approval ap) {
        return PlusWords.price(ctx(), ap.price, ap.pay, server(), this::itemName);
    }

    private static GradientDrawable cardBackground(Context c) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(CloudUi.dp(c, 10));
        bg.setColor(CloudUi.color(c, R.color.projects_obsidian_800));
        bg.setStroke(Math.max(1, CloudUi.dp(c, 1)), CloudUi.color(c, R.color.projects_obsidian_700));
        return bg;
    }

    /** The decision's buttons: the yes on a plate, the no quiet (a money decision takes no gold). */
    private LinearLayout buttons(Context c, final CloudModel.Approval ap, int yes, int no) {
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, CloudUi.dp(c, 12), 0, 0);
        boolean idle = mDeciding == null;
        Button approve = CloudUi.button(c, getString(yes), false, v -> decide(v, ap, true, null));
        Button reject = CloudUi.textButton(c, getString(no), v -> decide(v, ap, false, null));
        reject.setTextColor(CloudUi.color(c, R.color.projects_ash_300));
        approve.setEnabled(idle);
        reject.setEnabled(idle);
        buttons.addView(approve, CloudUi.weight(1f));
        LinearLayout.LayoutParams rejectLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rejectLp.setMarginStart(CloudUi.dp(c, 10));
        buttons.addView(reject, rejectLp);
        return buttons;
    }

    /** The head of a card: the item's tile, its name and one line under it; the time left on the right. */
    private LinearLayout head(Context c, CloudModel.Approval ap, String tileRef, String name, String line) {
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(CloudItemArt.tile(c, tileRef, customBase(tileRef), 44), new LinearLayout.LayoutParams(CloudUi.dp(c, 44), CloudUi.dp(c, 44)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(CloudUi.text(c, name, 16, R.color.projects_bone_100, true));
        texts.addView(CloudUi.text(c, line, 13.5f, R.color.projects_ash_300, false));
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginStart(CloudUi.dp(c, 12));
        head.addView(texts, textsLp);
        // Minutes or hours, as the site ("5 dk kaldı"); the card's tone, bright in the last two minutes.
        TextView left = CloudUi.text(c, "", 13, R.color.projects_ash_300, true);
        left.setGravity(Gravity.END);
        head.addView(left);
        mCountdowns.add(left);
        mCounting.add(ap);
        return head;
    }

    /** A custom item's material once seen (its tile), else null. */
    @Nullable
    private String customBase(String ref) {
        if (!ref.startsWith("custom:")) return null;
        CloudModel.PlusDoc doc = mService.lastPlus(accountId() == null ? "" : accountId());
        if (doc == null) return null;
        String k = ref.substring("custom:".length());
        for (com.google.gson.JsonObject custom : gg.projects.mobile.social.Json.objects(doc.config, "customItems")) {
            if (k.equals(gg.projects.mobile.social.Json.str(custom, "k"))) return gg.projects.mobile.social.Json.str(custom, "base");
        }
        return null;
    }

    /** An open approval, by its kind. */
    private View card(final Context c, final CloudModel.Approval ap) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground(c));
        card.setPadding(CloudUi.dp(c, 14), CloudUi.dp(c, 14), CloudUi.dp(c, 14), CloudUi.dp(c, 10));
        LinearLayout.LayoutParams cardLp = CloudUi.matchWrap();
        cardLp.topMargin = CloudUi.dp(c, 10);
        card.setLayoutParams(cardLp);
        boolean buy = "buy".equals(ap.side);
        String party = getString(buy ? R.string.projects_approval_party_buy : R.string.projects_approval_party_sell, ap.counterpart);

        if (ap.handoff()) {
            card.addView(kindTitle(c, R.string.projects_handoff_title));
            card.addView(head(c, ap, ap.item, title(ap), party));
            String deal = PlusWords.deal(c, ap.qty, ap.item, ap.price, ap.pay, server(), this::itemName);
            TextView body = CloudUi.text(c, getString(R.string.projects_handoff_body, ap.counterpart, deal), 14.5f, R.color.projects_bone_100, false);
            body.setLineSpacing(0, 1.15f);
            body.setPadding(0, CloudUi.dp(c, 12), 0, 0);
            card.addView(body);
            card.addView(buttons(c, ap, R.string.projects_handoff_yes, R.string.projects_handoff_no), CloudUi.matchWrap());
            return card;
        }

        if (ap.confirmItem()) {
            card.addView(kindTitle(c, R.string.projects_firstsight_title));
            String wanted = ap.customKey == null ? itemName(ap.item) : itemName("custom:" + ap.customKey);
            String seenRef = ap.seenId == null ? ap.item : ap.seenId;
            String seenName = ap.seenName != null && !ap.seenName.isEmpty() ? ap.seenName : itemName(seenRef);
            card.addView(head(c, ap, seenRef, seenName, party));
            for (String lore : ap.seenLore) {
                // The counterpart's own text on the item, shown as plain text (masked by the service).
                TextView line = CloudUi.text(c, lore, 13, R.color.projects_ash_400, false);
                line.setPadding(CloudUi.dp(c, 56), CloudUi.dp(c, 2), 0, 0);
                card.addView(line);
            }
            TextView question = CloudUi.text(c, getString(R.string.projects_firstsight_question, wanted), 15, R.color.projects_bone_100, true);
            question.setPadding(0, CloudUi.dp(c, 12), 0, 0);
            card.addView(question);
            card.addView(buttons(c, ap, R.string.projects_firstsight_yes, R.string.projects_firstsight_no), CloudUi.matchWrap());
            return card;
        }

        card.addView(head(c, ap, ap.item, title(ap), party));
        TextView price = CloudUi.figure(c, price(ap), 24, R.color.projects_bone_100);
        price.setPadding(0, CloudUi.dp(c, 12), 0, CloudUi.dp(c, 2));
        card.addView(price);
        for (String why : ap.why) {
            String words = "authority".equals(why) ? getString(R.string.projects_approval_why_authority) : CloudWords.why(c, why);
            TextView reason = CloudUi.text(c, words, 13.5f, R.color.projects_ash_300, false);
            reason.setPadding(0, CloudUi.dp(c, 2), 0, 0);
            card.addView(reason);
        }
        card.addView(buttons(c, ap, R.string.projects_plus_approve, R.string.projects_cloud_reject), CloudUi.matchWrap());
        // "Fiyatı düşür" bargains again: a buy for money, while the assistant is running.
        CloudModel.Account a = account();
        boolean running = a != null && a.ai != null && "on".equals(a.ai.state);
        if (buy && ap.money() && running) {
            Button lower = CloudUi.textButton(c, getString(R.string.projects_plus_lower), v -> lower(ap));
            lower.setEnabled(mDeciding == null);
            card.addView(lower, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.setContentDescription(null);
        return card;
    }

    private static TextView kindTitle(Context c, int words) {
        TextView title = CloudUi.text(c, c.getString(words), 13, R.color.projects_ash_300, true);
        title.setPadding(0, 0, 0, CloudUi.dp(c, 10));
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        return title;
    }

    private View closedRow(Context c, CloudModel.Approval ap) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 64));
        row.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 10), CloudUi.dp(c, 16), CloudUi.dp(c, 10));
        row.addView(CloudItemArt.tile(c, ap.item, customBase(ap.item), 40), new LinearLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(CloudUi.text(c, title(ap), 15, R.color.projects_bone_100, true));
        // Who and how much as one phrase ("Zeynep_MC ile 7.680": "ile" needs no ending on a name), the outcome on the right.
        texts.addView(CloudUi.text(c, getString(R.string.projects_plus_approval_with, ap.counterpart, price(ap)), 13, R.color.projects_ash_400, false));
        LinearLayout.LayoutParams lp = CloudUi.weight(1f);
        lp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(texts, lp);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stateLp.setMarginStart(CloudUi.dp(c, 12));
        TextView state = CloudUi.text(c, getString(stateWords(ap)), 13, R.color.projects_ash_300, false);
        state.setMaxWidth(CloudUi.dp(c, 140));
        state.setGravity(Gravity.END);
        row.addView(state, stateLp);
        return row;
    }

    private static int stateWords(CloudModel.Approval ap) {
        String state = ap.state == null ? "" : ap.state;
        if ("closed".equals(state)) {
            if ("deal_gone".equals(ap.closed)) return R.string.projects_approval_closed_gone;
            if ("ai_stopped".equals(ap.closed)) return R.string.projects_approval_closed_stopped;
            return R.string.projects_plus_state_closed;
        }
        if (ap.handoff()) {
            if ("approved".equals(state)) return R.string.projects_handoff_done;
            if ("rejected".equals(state)) return R.string.projects_handoff_not_done;
        }
        if (ap.confirmItem()) {
            if ("approved".equals(state)) return R.string.projects_firstsight_yes_done;
            if ("rejected".equals(state)) return R.string.projects_firstsight_no_done;
        }
        switch (state) {
            case "approved": return R.string.projects_plus_state_approved;
            case "rejected": return R.string.projects_plus_state_rejected;
            case "expired": return R.string.projects_plus_state_expired;
            default: return R.string.projects_plus_state_closed;
        }
    }

    /** The countdowns: the words change once a minute; an approval that ran out moves below. */
    private void tick() {
        View view = getView();
        if (view == null) return;
        view.removeCallbacks(mTick);
        long now = mService.nowSeconds();
        boolean expired = false;
        for (int i = 0; i < mCountdowns.size(); i++) {
            CloudModel.Approval ap = mCounting.get(i);
            long left = Math.max(0, ap.expiresAt - now);
            if (left == 0) expired = true;
            long minutes = (left + 59) / 60;
            String text;
            if (minutes > 60) {
                int hours = (int) ((minutes + 59) / 60);
                text = getResources().getQuantityString(R.plurals.projects_handoff_hours_left, hours, hours);
            } else if (minutes > 1) {
                text = getString(R.string.projects_plus_left, getString(R.string.projects_plus_minutes_short, (int) minutes));
            } else {
                text = getString(R.string.projects_plus_left_under);
            }
            // Only when the words change: a text set every second is an accessibility event every second.
            TextView countdown = mCountdowns.get(i);
            if (!TextUtils.equals(text, countdown.getText())) countdown.setText(text);
            countdown.setTextColor(CloudUi.color(ctx(), left <= 120 ? R.color.projects_bone_100 : R.color.projects_ash_300));
        }
        if (expired) {
            mShape = "";
            render();
            return;
        }
        if (!mCountdowns.isEmpty()) view.postDelayed(mTick, 1000 - (mService.nowMs() % 1000));
    }

    private void decide(final View from, final CloudModel.Approval ap, final boolean approve, @Nullable Long maxPrice) {
        final CloudModel.Account a = account();
        if (a == null || mDeciding != null) return;
        mDeciding = ap.id;
        render();
        mService.decide(a.id, ap.id, approve, maxPrice, new CloudService.Result<CloudModel.Approval>() {
            @Override
            public void ok(CloudModel.Approval value) {
                mDeciding = null;
                replace(value);
                if (!alive()) return;
                Haptics.confirm(from);
                int done;
                if (ap.handoff()) done = approve ? R.string.projects_handoff_done : R.string.projects_handoff_not_done;
                else if (ap.confirmItem()) done = approve ? R.string.projects_firstsight_yes_done : R.string.projects_firstsight_no_done;
                else done = approve ? R.string.projects_approval_sent_yes : R.string.projects_approval_sent_no;
                toast(getString(done));
                render();
            }

            @Override
            public void failed(CloudProblem problem) {
                mDeciding = null;
                if (!alive()) return;
                if (problem.is("approval_closed")) {
                    toast(getString(R.string.projects_cloud_error_approval_closed));
                    load();
                } else {
                    problem(problem, from);
                }
                render();
            }
        });
    }

    private void replace(CloudModel.Approval value) {
        for (int i = 0; i < mApprovals.size(); i++) {
            if (mApprovals.get(i).id.equals(value.id)) {
                mApprovals.set(i, value);
                return;
            }
        }
        mApprovals.add(value);
    }

    /** About 90 % of the price, to two significant digits: a starting point the player changes. */
    static long suggestedLower(long price) {
        long v = Math.max(1, price * 9 / 10);
        long step = 1;
        while (v / step >= 100) step *= 10;
        return Math.max(1, v / step * step);
    }

    /** "Fiyatı düşür": an amount below the price; the assistant bargains again up to it. */
    private void lower(final CloudModel.Approval ap) {
        final Context c = ctx();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 16));
        TextView title = CloudUi.text(c, getString(R.string.projects_plus_lower_title), 17, R.color.projects_bone_100, true);
        title.setTypeface(CloudUi.font(c, R.font.tektur_semibold));
        title.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), 0);
        root.addView(title);
        root.addView(CloudUi.note(c, getString(R.string.projects_plus_lower_help)));
        final PlusAmount amount = new PlusAmount(c, getString(R.string.projects_plus_lower_label), suggestedLower(ap.price));
        root.addView(amount.field.view);
        final BottomPanel panel = BottomPanel.of(c, root, 0.7f);
        Button go = CloudUi.button(c, getString(R.string.projects_plus_lower_go), true, v -> {
            Long value = amount.value();
            if (value == null || value < 1 || value >= ap.price) {
                amount.field.setError(getString(R.string.projects_plus_lower_range, CloudWords.money(c, ap.price)));
                Haptics.reject(v);
                return;
            }
            panel.dismiss();
            decide(v, ap, true, value);
        });
        LinearLayout.LayoutParams goLp = CloudUi.matchWrap();
        goLp.topMargin = CloudUi.dp(c, 14);
        goLp.leftMargin = CloudUi.dp(c, 8);
        goLp.rightMargin = CloudUi.dp(c, 8);
        root.addView(go, goLp);
        panel.show();
    }

    // ---- the live socket ----

    @Override
    public void liveState(CloudService.Live state) {}

    @Override
    public void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}

    @Override
    public void approval(CloudModel.Approval approval) {
        replace(approval);
        if (alive()) render();
    }

    @Override
    public void hostOnline(boolean online) {}
}
