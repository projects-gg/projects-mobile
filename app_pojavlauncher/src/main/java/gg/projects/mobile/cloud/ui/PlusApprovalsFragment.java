package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
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
import java.util.Locale;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.Haptics;

/**
 * "Onaylar": the deals the assistant asks about. Each open one shows the item, who it is with, the price, why it asks
 * and how long it waits (no answer counts as no); "Onayla", "Reddet", and for buying "Fiyatı düşür" (it bargains
 * again up to a lower amount). Approving happens only here, never from a notification. New ones arrive live; the
 * last day's answered ones stay below.
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
        ensureCatalog();
    }

    @Override
    protected void catalogArrived() {
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

    private String title(CloudModel.Approval ap) {
        String name = mService.itemName(ap.item, lang());
        return ap.qty > 1 ? ap.qty + " " + name : name;
    }

    /** An open approval. */
    private View card(final Context c, final CloudModel.Approval ap) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(CloudUi.dp(c, 10));
        bg.setColor(CloudUi.color(c, R.color.projects_obsidian_800));
        bg.setStroke(Math.max(1, CloudUi.dp(c, 1)), CloudUi.color(c, R.color.projects_obsidian_700));
        card.setBackground(bg);
        card.setPadding(CloudUi.dp(c, 14), CloudUi.dp(c, 14), CloudUi.dp(c, 14), CloudUi.dp(c, 10));
        LinearLayout.LayoutParams cardLp = CloudUi.matchWrap();
        cardLp.topMargin = CloudUi.dp(c, 10);
        card.setLayoutParams(cardLp);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(CloudItemArt.tile(c, ap.item, 44), new LinearLayout.LayoutParams(CloudUi.dp(c, 44), CloudUi.dp(c, 44)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(CloudUi.text(c, title(ap), 16, R.color.projects_bone_100, true));
        boolean buy = "buy".equals(ap.side);
        texts.addView(CloudUi.text(c, getString(buy ? R.string.projects_plus_buying_from : R.string.projects_plus_selling_to, ap.counterpart),
                13.5f, R.color.projects_ash_300, false));
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginStart(CloudUi.dp(c, 12));
        head.addView(texts, textsLp);
        TextView left = CloudUi.figure(c, "", 13, R.color.projects_gold_300);
        left.setGravity(Gravity.END);
        head.addView(left);
        mCountdowns.add(left);
        mCounting.add(ap);
        card.addView(head);

        TextView price = CloudUi.figure(c, CloudWords.money(c, ap.price), 24, R.color.projects_bone_100);
        price.setPadding(0, CloudUi.dp(c, 12), 0, CloudUi.dp(c, 2));
        card.addView(price);
        for (String why : ap.why) {
            TextView reason = CloudUi.text(c, CloudWords.why(c, why), 13.5f, R.color.projects_ash_300, false);
            reason.setPadding(0, CloudUi.dp(c, 2), 0, 0);
            card.addView(reason);
        }

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, CloudUi.dp(c, 12), 0, 0);
        boolean busy = ap.id.equals(mDeciding);
        Button approve = CloudUi.button(c, getString(R.string.projects_plus_approve), true, v -> decide(v, ap, true, null));
        Button reject = CloudUi.button(c, getString(R.string.projects_cloud_reject), false, v -> decide(v, ap, false, null));
        approve.setEnabled(!busy && mDeciding == null);
        reject.setEnabled(!busy && mDeciding == null);
        buttons.addView(approve, CloudUi.weight(1f));
        LinearLayout.LayoutParams rejectLp = CloudUi.weight(1f);
        rejectLp.setMarginStart(CloudUi.dp(c, 10));
        buttons.addView(reject, rejectLp);
        card.addView(buttons, CloudUi.matchWrap());
        if (buy) {
            Button lower = CloudUi.textButton(c, getString(R.string.projects_plus_lower), v -> lower(ap));
            lower.setEnabled(mDeciding == null);
            card.addView(lower, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.setContentDescription(null);
        return card;
    }

    private View closedRow(Context c, CloudModel.Approval ap) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 64));
        row.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 10), CloudUi.dp(c, 16), CloudUi.dp(c, 10));
        row.addView(CloudItemArt.tile(c, ap.item, 40), new LinearLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(CloudUi.text(c, title(ap), 15, R.color.projects_bone_100, true));
        int state;
        switch (ap.state == null ? "" : ap.state) {
            case "approved": state = R.string.projects_plus_state_approved; break;
            case "rejected": state = R.string.projects_plus_state_rejected; break;
            case "expired": state = R.string.projects_plus_state_expired; break;
            default: state = R.string.projects_plus_state_closed; break;
        }
        // Who and how much as one phrase ("Zeynep_MC ile 7.680": "ile" needs no ending on a name), the outcome on the
        // right; a chain of three commas read as data, not a sentence.
        texts.addView(CloudUi.text(c, getString(R.string.projects_plus_approval_with, ap.counterpart, CloudWords.money(c, ap.price)),
                13, R.color.projects_ash_400, false));
        LinearLayout.LayoutParams lp = CloudUi.weight(1f);
        lp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(texts, lp);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stateLp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(CloudUi.text(c, getString(state), 13, R.color.projects_ash_300, false), stateLp);
        return row;
    }

    /** The countdowns, every second while one is open; an approval that ran out moves below. */
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
            String clock = String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60);
            mCountdowns.get(i).setText(getString(R.string.projects_plus_left, clock));
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
                toast(getString(approve ? R.string.projects_plus_approved : R.string.projects_cloud_rejected));
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

    /** "Fiyatı düşür": an amount below the price; the assistant bargains again up to it. */
    private void lower(final CloudModel.Approval ap) {
        final Context c = ctx();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 16));
        TextView title = CloudUi.text(c, getString(R.string.projects_plus_lower_title), 18, R.color.projects_bone_100, true);
        title.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), 0);
        root.addView(title);
        root.addView(CloudUi.note(c, getString(R.string.projects_plus_lower_help)));
        final PlusRowSheet.Amount amount = new PlusRowSheet.Amount(c, title(ap), gg.projects.mobile.cloud.PlusConfig.suggestedTarget(ap.price));
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
    public void feed(List<CloudModel.FeedItem> items, boolean snapshot) {}

    @Override
    public void approval(CloudModel.Approval approval) {
        replace(approval);
        if (alive()) render();
    }

    @Override
    public void hostOnline(boolean online) {}
}
