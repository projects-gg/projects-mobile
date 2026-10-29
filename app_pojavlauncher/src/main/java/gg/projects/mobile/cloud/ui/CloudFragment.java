package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudStatus;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.ui.Press;

/**
 * "7/24 Bulut": the player's cloud accounts, one rounded group of rows (the server bars, the name, the state in the
 * desktop's words, and below it what needs the player: an approval, new messages, time running out). Signed out it
 * asks for the friends' Discord sign-in (one session for both); with no account it points at the site, where
 * accounts are bought. The words tick on their own (uptime, a reconnect countdown) and the list is read again while
 * it is on screen.
 */
public final class CloudFragment extends CloudScreen {
    public static final String TAG = "ProjectsCloudFragment";
    private static final long REFRESH_MS = 30_000;

    private String mShape = "";
    private final Map<String, AccountRow> mRows = new LinkedHashMap<>();
    /** CAFK-FEAT §3.4: the accounts chosen for an action on several at once (a long press starts choosing). */
    private final java.util.LinkedHashSet<String> mChosen = new java.util.LinkedHashSet<>();
    private boolean mChoosing;
    private boolean mBulkBusy;
    @androidx.annotation.Nullable
    private BulkBar mBulk;
    private final Runnable mTick = this::render;
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            mService.refresh(false);
            View view = getView();
            if (view != null) view.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_cloud_title));
        if (mBottom != null) {
            mBulk = new BulkBar(view.getContext(), mBottom, new BulkBar.Actions() {
                @Override
                public void start() {
                    bulk(gg.projects.mobile.cloud.CloudBulk.Action.START);
                }

                @Override
                public void stop() {
                    bulk(gg.projects.mobile.cloud.CloudBulk.Action.STOP);
                }

                @Override
                public void restart() {
                    bulk(gg.projects.mobile.cloud.CloudBulk.Action.RESTART);
                }

                @Override
                public void copy() {
                    copySettings();
                }

                @Override
                public void selectAll() {
                    for (CloudModel.Account a : mService.accounts()) mChosen.add(a.id);
                    chosenChanged();
                }

                @Override
                public void done() {
                    choose(false);
                }
            });
        }
    }

    /** Several accounts at once: from a service that names its features (phase 1) and with two accounts or more. */
    private boolean bulkAllowed() {
        return mService.features().present && mService.accounts().size() >= 2;
    }

    /**
     * Which of the bar's actions some chosen account can take now: start (its button would start it), stop (it runs or
     * waits to), reconnect (in game, connecting or waiting), copy (always: it copies onto them).
     */
    private boolean[] bulkCan() {
        boolean start = false, stop = false, restart = false;
        for (CloudModel.Account a : mService.accounts()) {
            if (!mChosen.contains(a.id)) continue;
            CloudStatus.Primary p = CloudStatus.primary(a);
            start |= p == CloudStatus.Primary.START;
            stop |= p == CloudStatus.Primary.STOP;
            restart |= CloudStatus.inGame(a) || "connecting".equals(a.live.state) || "waiting".equals(a.live.state);
        }
        return new boolean[]{start, stop, restart, true};
    }

    private void choose(boolean on) {
        mChoosing = on;
        if (!on) mChosen.clear();
        holdBack(on);
        chosenChanged();
    }

    private void chosenChanged() {
        // Accounts that left the list leave the choice.
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (CloudModel.Account a : mService.accounts()) ids.add(a.id);
        mChosen.retainAll(ids);
        if (mBulk != null) mBulk.set(mChoosing, mChosen.size(), mBulkBusy, bulkCan());
        long next = update();
        View view = getView();
        if (view != null && next > 0) {
            view.removeCallbacks(mTick);
            view.postDelayed(mTick, Math.max(1000, next));
        }
    }

    @Override
    protected void onBack() {
        if (mChoosing) {
            choose(false);
            return;
        }
        super.onBack();
    }

    /** Start, stop or reconnect the chosen accounts, one at a time in the list's order; one line for the outcome. */
    private void bulk(final gg.projects.mobile.cloud.CloudBulk.Action action) {
        if (mBulkBusy || mChosen.isEmpty()) return;
        List<String> ids = new ArrayList<>();
        for (CloudModel.Account a : mService.accounts()) if (mChosen.contains(a.id)) ids.add(a.id);
        mBulkBusy = true;
        chosenChanged();
        mService.bulk(action, ids, new CloudService.Result<gg.projects.mobile.cloud.CloudBulk.Outcome>() {
            @Override
            public void ok(gg.projects.mobile.cloud.CloudBulk.Outcome outcome) {
                mBulkBusy = false;
                if (!alive()) return;
                said(action, outcome);
                choose(false);
            }

            @Override
            public void failed(gg.projects.mobile.cloud.CloudProblem problem) {
                mBulkBusy = false;
                if (!alive()) return;
                chosenChanged();
                problem(problem, mBulk == null ? null : mBulk.anchor());
            }
        });
    }

    private void copySettings() {
        if (mBulkBusy) return;
        CloudCopySheet.open(ctx(), mService, mService.accounts(), mChosen, outcome -> {
            if (!alive()) return;
            said(gg.projects.mobile.cloud.CloudBulk.Action.COPY, outcome);
            choose(false);
        });
    }

    /** One line for the outcome, with the first failure's reason. */
    private void said(gg.projects.mobile.cloud.CloudBulk.Action action, gg.projects.mobile.cloud.CloudBulk.Outcome outcome) {
        android.content.res.Resources r = getResources();
        String reason = outcome.first == null ? "" : getString(reasonWords(outcome.first));
        int all;
        int some;
        int none;
        switch (action) {
            case STOP:
                all = R.plurals.projects_feat_bulk_stopped;
                some = R.plurals.projects_feat_bulk_stopped_some;
                none = R.string.projects_feat_bulk_none_stop;
                break;
            case RESTART:
                all = R.plurals.projects_feat_bulk_restarted;
                some = R.plurals.projects_feat_bulk_restarted_some;
                none = R.string.projects_feat_bulk_none_restart;
                break;
            case COPY:
                all = R.plurals.projects_feat_bulk_copied;
                some = R.plurals.projects_feat_bulk_copied_some;
                none = R.string.projects_feat_bulk_none_copy;
                break;
            default:
                all = R.plurals.projects_feat_bulk_started;
                some = R.plurals.projects_feat_bulk_started_some;
                none = R.string.projects_feat_bulk_none_start;
                break;
        }
        // The launcher's words (FEAT-L): a count when some went, none when nothing did.
        String text;
        if (outcome.failed == 0) text = r.getQuantityString(all, outcome.done, outcome.done);
        else if (outcome.done == 0) text = getString(none, reason);
        else text = r.getQuantityString(some, outcome.done, outcome.done, outcome.failed, reason);
        View anchor = mBulk == null ? null : mBulk.anchor();
        if (outcome.failed == 0) gg.projects.mobile.ui.Haptics.confirm(anchor);
        else gg.projects.mobile.ui.Haptics.reject(anchor);
        gg.projects.mobile.social.ui.SocialUi.toastLong(ctx(), text);
    }

    private static int reasonWords(gg.projects.mobile.cloud.CloudBulk.Reason reason) {
        switch (reason) {
            case EXPIRED: return R.string.projects_feat_bulk_reason_expired;
            case STAFF: return R.string.projects_feat_bulk_reason_staff;
            case BLOCKED: return R.string.projects_feat_bulk_reason_blocked;
            case FULL: return R.string.projects_feat_bulk_reason_full;
            case CHANGE_LIMIT: return R.string.projects_feat_bulk_reason_change_limit;
            case NOT_RUNNING: return R.string.projects_feat_bulk_reason_not_running;
            case RATE_LIMITED: return R.string.projects_feat_bulk_reason_rate_limited;
            case CONFLICT: return R.string.projects_feat_bulk_reason_conflict;
            case OFFLINE: return R.string.projects_feat_bulk_reason_offline;
            default: return R.string.projects_feat_bulk_reason_other;
        }
    }

    @Override
    protected boolean needsAccount() {
        return false;
    }

    @Override
    public void onResume() {
        mShape = "";
        super.onResume();
        mService.refresh(false);
        View view = getView();
        if (view != null) view.postDelayed(mRefresh, REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        View view = getView();
        if (view != null) {
            view.removeCallbacks(mRefresh);
            view.removeCallbacks(mTick);
        }
    }

    @Override
    protected void render() {
        View view = getView();
        if (view == null) return;
        view.removeCallbacks(mTick);
        String shape = shape();
        if (!shape.equals(mShape)) {
            mShape = shape;
            rebuild();
        }
        if (mChoosing && !bulkAllowed()) choose(false);
        if (mBulk != null) mBulk.set(mChoosing, mChosen.size(), mBulkBusy, bulkCan());
        long next = update();
        if (next > 0) view.postDelayed(mTick, Math.max(1000, next));
    }

    /** What decides the screen's blocks (the words inside them change in place). */
    private String shape() {
        StringBuilder key = new StringBuilder();
        CloudModel.Offer offer = mService.offer();
        CloudModel.Me me = mService.me();
        key.append(mService.available()).append(offer != null && offer.paused()).append(mService.load())
                .append(mService.plusOn()).append('|');
        if (me != null) key.append(me.restricted).append(me.restrictedUntil).append(me.canBuy).append('|');
        if (offer != null) key.append(offer.salesNew).append('|');
        for (CloudModel.Account a : mService.accounts()) key.append(a.id).append(a.name).append(a.plus()).append(',');
        return key.toString();
    }

    private void rebuild() {
        LinearLayout content = clear();
        if (content == null) return;
        mRows.clear();
        Context c = ctx();
        CloudModel.Offer offer = mService.offer();
        if (!mService.available()) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.CLOUD, getString(R.string.projects_cloud_error_unavailable)));
            return;
        }
        if (offer != null && offer.paused()) {
            String words = offer.maintenance != null ? offer.maintenance.in(lang()) : null;
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_clock, R.color.projects_gold_300,
                    TextUtils.isEmpty(words) ? getString(R.string.projects_cloud_maintenance) : words));
        }
        CloudService.Load load = mService.load();
        List<CloudModel.Account> accounts = mService.accounts();
        if (load == CloudService.Load.SIGNED_OUT) {
            LinearLayout empty = CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.CLOUD, getString(R.string.projects_cloud_signed_out));
            Button signIn = CloudUi.discordButton(c, getString(R.string.projects_social_sign_in), v -> mService.signIn(requireActivity()));
            empty.addView(centered(c, signIn));
            content.addView(empty);
            return;
        }
        if (accounts.isEmpty() && (load == CloudService.Load.LOADING || load == CloudService.Load.IDLE)) {
            content.addView(CloudUi.loading(c));
            return;
        }
        if (accounts.isEmpty() && load == CloudService.Load.FAILED) {
            LinearLayout empty = CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_cloud_load_failed));
            empty.addView(centered(c, CloudUi.button(c, getString(R.string.projects_cloud_retry), false, v -> mService.refresh(true))));
            content.addView(empty);
            return;
        }
        CloudModel.Me me = mService.me();
        if (me != null && me.restricted) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_flare_400,
                    me.restrictedUntil != null
                            ? getString(R.string.projects_cloud_restricted_until, CloudWords.day(c, me.restrictedUntil))
                            : getString(R.string.projects_cloud_restricted)));
        }
        if (accounts.isEmpty()) {
            LinearLayout empty = CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.CLOUD, getString(R.string.projects_cloud_empty_body));
            TextView title = CloudUi.text(c, getString(R.string.projects_cloud_empty_title), 17, R.color.projects_bone_100, true);
            title.setGravity(Gravity.CENTER);
            empty.addView(title, 1, CloudUi.matchWrap());
            ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = CloudUi.dp(c, 14);
            if (me == null || !me.restricted) {
                empty.addView(centered(c, CloudUi.button(c, getString(R.string.projects_cloud_start_site), true,
                        v -> mService.openSite(requireActivity(), "/"))));
            }
            content.addView(empty);
        } else {
            content.addView(CloudUi.gap(c, 8));
            LinearLayout group = CloudUi.group(c);
            List<View> rows = new ArrayList<>();
            for (CloudModel.Account a : accounts) {
                AccountRow row = new AccountRow(c, a.id);
                mRows.put(a.id, row);
                rows.add(row.view);
            }
            CloudUi.fill(group, rows);
            content.addView(group);
            if (me != null && me.canBuy && offer != null && offer.salesNew && !me.restricted) {
                Button buy = CloudUi.siteLink(c, getString(R.string.projects_cloud_buy), v -> mService.openSite(requireActivity(), "/"));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = CloudUi.dp(c, 6);
                content.addView(buy, lp);
            }
        }
        content.addView(CloudUi.gap(c, 22));
        LinearLayout settings = CloudUi.group(c);
        settings.addView(CloudUi.row(c, R.drawable.projects_ic_bell, getString(R.string.projects_cloud_notifications),
                v -> open(CloudNotifyFragment.class, null)).view);
        content.addView(settings);
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

    /** Writes every row's words; returns when they next change by themselves (ms), or 0. */
    private long update() {
        Context c = ctx();
        long nowMs = mService.nowMs();
        long next = 0;
        for (Map.Entry<String, AccountRow> e : mRows.entrySet()) {
            CloudModel.Account a = mService.account(e.getKey());
            if (a == null) continue;
            CloudStatus.Line line = CloudStatus.of(a, nowMs, lang());
            e.getValue().bind(c, a, line, mService);
            if (line.nextChangeMs > 0 && (next == 0 || line.nextChangeMs < next)) next = line.nextChangeMs;
        }
        return next;
    }

    /** One account in the list: the server bars, the name with the AFK+ mark, the state, what needs the player. */
    private final class AccountRow {
        final LinearLayout view;
        final SignalGlyph glyph;
        final TextView name;
        final TextView plus;
        final TextView state;
        final TextView extra;
        final ImageView check;
        final ImageView caret;

        AccountRow(Context c, final String id) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(CloudUi.dp(c, 68));
            view.setPaddingRelative(CloudUi.dp(c, 16), CloudUi.dp(c, 12), CloudUi.dp(c, 12), CloudUi.dp(c, 12));
            view.setBackgroundResource(R.drawable.projects_row_ripple);
            view.setClickable(true);
            view.setFocusable(true);
            view.setOnClickListener(v -> {
                if (mChoosing) {
                    if (!mChosen.remove(id)) mChosen.add(id);
                    gg.projects.mobile.ui.Haptics.tick(v);
                    chosenChanged();
                    return;
                }
                openFor(CloudAccountFragment.class, id);
            });
            // A long press starts choosing several accounts (CAFK-FEAT §3.4), with this one chosen.
            view.setOnLongClickListener(v -> {
                if (!bulkAllowed() || mChoosing) return false;
                gg.projects.mobile.ui.Haptics.perform(v, gg.projects.mobile.ui.HapticMap.Kind.LONG_PRESS);
                mChosen.add(id);
                choose(true);
                return true;
            });
            Press.attach(view, Press.Style.ROW);

            int unit = Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * 1.25f));
            glyph = new SignalGlyph(unit);
            ImageView mark = new ImageView(c);
            mark.setImageDrawable(glyph);
            mark.setScaleType(ImageView.ScaleType.CENTER);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(mark, new LinearLayout.LayoutParams(CloudUi.dp(c, 24), CloudUi.dp(c, 24)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout titleLine = new LinearLayout(c);
            titleLine.setOrientation(LinearLayout.HORIZONTAL);
            titleLine.setGravity(Gravity.CENTER_VERTICAL);
            name = CloudUi.text(c, "", 15.5f, R.color.projects_bone_100, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            // A game name is at most 16 characters: the mark sits right after it, not pushed to the edge.
            titleLine.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            plus = CloudUi.figure(c, getString(R.string.projects_cloud_plus_mark), 12.5f, R.color.projects_gold_300);
            LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plusLp.setMarginStart(CloudUi.dp(c, 8));
            titleLine.addView(plus, plusLp);
            texts.addView(titleLine);
            state = CloudUi.text(c, "", 13.5f, R.color.projects_ash_300, false);
            LinearLayout.LayoutParams stateLp = CloudUi.matchWrap();
            stateLp.topMargin = CloudUi.dp(c, 2);
            texts.addView(state, stateLp);
            extra = CloudUi.text(c, "", 13, R.color.projects_gold_300, false);
            extra.setVisibility(View.GONE);
            texts.addView(extra, stateLp);
            LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textsLp.setMarginStart(CloudUi.dp(c, 16));
            view.addView(texts, textsLp);

            check = new ImageView(c);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            check.setVisibility(View.GONE);
            LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(CloudUi.dp(c, 24), CloudUi.dp(c, 24));
            checkLp.setMarginStart(CloudUi.dp(c, 6));
            view.addView(check, checkLp);
            caret = new ImageView(c);
            caret.setImageResource(R.drawable.projects_ic_caret_right);
            caret.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_400)));
            caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams caretLp = new LinearLayout.LayoutParams(CloudUi.dp(c, 20), CloudUi.dp(c, 20));
            caretLp.setMarginStart(CloudUi.dp(c, 6));
            view.addView(caret, caretLp);
            view.setLayoutParams(CloudUi.matchWrap());
        }

        void bind(Context c, CloudModel.Account a, CloudStatus.Line line, CloudService service) {
            name.setText(a.name);
            plus.setVisibility(a.plus() && service.plusOn() ? View.VISIBLE : View.GONE);
            String words = CloudWords.state(c, line);
            state.setText(words);
            int tone;
            switch (line.tone) {
                // QA-TASTE: as the launcher, the words bone and the colour in the bars; colour in words only for trouble.
                case OK: tone = R.color.projects_bone_100; break;
                case ERROR: tone = R.color.projects_flare_400; break;
                // design.md section 2: a warning's words are bone after a small gold triangle, never gold.
                case WARN: tone = R.color.projects_bone_100; break;
                default: tone = R.color.projects_ash_300; break;
            }
            state.setTextColor(CloudUi.color(c, tone));
            CloudUi.warnMark(state, line.tone == CloudStatus.Tone.WARN);
            glyph.setSignal(line.signal);
            String more = null;
            boolean timeLine = false;
            int approvals = a.ai != null && a.plus() && service.plusOn() ? a.ai.pendingApprovals : 0;
            int unread = service.unread(a.id);
            if (approvals > 0) {
                more = c.getResources().getQuantityString(R.plurals.projects_cloud_home_approvals, approvals, approvals);
            } else if (unread > 0) {
                more = c.getResources().getQuantityString(R.plurals.projects_cloud_home_messages, unread, unread);
            } else if (CloudStatus.timeShort(a, service.nowMs())) {
                // Only the last 7 days (or past the end) ask something of the player; renewing is open any time now.
                more = CloudWords.timeLeft(c, CloudStatus.timeLeft(a, service.nowMs()));
                timeLine = true;
            }
            // MOB-POLISH (the launcher's SHEET-UX 31): the last week's time is bright and bold, not gold;
            // gold stays with actions (here: something AFK+ waits on the player for).
            if (!Boolean.valueOf(timeLine).equals(extra.getTag())) {
                extra.setTag(timeLine);
                extra.setTextColor(CloudUi.color(c, timeLine ? R.color.projects_bone_100 : R.color.projects_gold_300));
                extra.setTypeface(CloudUi.font(c, timeLine ? R.font.figtree_semibold : R.font.figtree_regular));
            }
            extra.setText(more);
            extra.setVisibility(more == null ? View.GONE : View.VISIBLE);
            StringBuilder said = new StringBuilder(a.name);
            if (plus.getVisibility() == View.VISIBLE) said.append(", ").append(plus.getText());
            said.append(", ").append(words);
            if (more != null) said.append(", ").append(more);
            boolean picked = mChosen.contains(a.id);
            check.setVisibility(mChoosing ? View.VISIBLE : View.GONE);
            caret.setVisibility(mChoosing ? View.GONE : View.VISIBLE);
            if (mChoosing) {
                check.setImageResource(picked ? R.drawable.projects_ic_check_square_fill : R.drawable.projects_ic_square);
                check.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, picked ? R.color.projects_gold_300 : R.color.projects_ash_400)));
                said.append(", ").append(c.getString(picked ? R.string.projects_cloud_selected : R.string.projects_feat_bulk_select));
            }
            view.setActivated(mChoosing && picked);
            view.setContentDescription(said);
        }
    }
}
