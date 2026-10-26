package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudStatus;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusFormat;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.Haptics;

/**
 * One cloud account: its state with the server bars and what to do about a stop, the main action (start, stop,
 * extend), the console and the settings, its time, and for AFK+ the assistant's switch, its allowance and the
 * assistant's screens (each behind the service's switch for it). Orders, refunds and deleting stay on the site. The
 * screen watches the account live while it is open (the state changes as the account connects).
 *
 * <p>CAFK-FEAT (FEAT-M): "Envanter" and "Hareket" sit beside the console, each only for an account that may use it.
 */
public final class CloudAccountFragment extends CloudScreen implements CloudService.LiveListener {
    public static final String TAG = "ProjectsCloudAccountFragment";

    private String mShape = "";
    @Nullable
    private CloudUi.StateLine mState;
    @Nullable
    private TextView mHint;
    @Nullable
    private CloudUi.Row mTimeRow;
    @Nullable
    private CloudService.LiveHandle mLive;
    private boolean mBusy;
    private final Runnable mTick = this::tick;

    @Override
    public void onResume() {
        mShape = "";
        super.onResume();
    }

    @Override
    protected void started() {
        CloudModel.Account a = account();
        if (a != null && mLive == null) mLive = mService.watch(a.id, a.plus() && mService.plusOn(), this);
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
        mHeader.setTitle(a.name);
        mHeader.setCaption(a.server.label());
        String shape = shape(a);
        if (!shape.equals(mShape)) {
            mShape = shape;
            build(a);
        }
        tick();
    }

    private String shape(CloudModel.Account a) {
        CloudStatus.Line line = CloudStatus.of(a, mService.nowMs(), lang());
        StringBuilder key = new StringBuilder();
        key.append(a.rev).append(a.name).append(a.server.label()).append(CloudStatus.primary(a)).append(line.hint).append(line.hintRow)
                .append(CloudStatus.inGame(a)).append(a.expired).append(a.renewalOpen).append(a.renewalOpensAt).append(a.expiresAt)
                .append(a.tier).append(mService.plusOn()).append(mBusy).append('|');
        if (a.moderation != null) key.append(a.moderation.caseId).append(a.moderation.step).append(a.moderation.appealState);
        if (a.ai != null) {
            key.append('|').append(a.ai.state).append(a.ai.reason).append(a.ai.until).append(a.ai.pendingApprovals).append(a.ai.buy);
            if (a.ai.quota != null) key.append(a.ai.quota.pct5).append(a.ai.quota.pctWeek).append(a.ai.quota.w5hResetAt)
                    .append(a.ai.quota.weekResetAt).append(a.ai.quota.giftsLeft);
        }
        key.append('|').append(mService.unread(a.id));
        key.append('|').append(a.features.inventoryMode).append(a.features.inventoryOn).append(gg.projects.mobile.cloud.movement.Movement.shows(mService, a));
        CloudModel.Offer offer = mService.offer();
        if (offer != null && offer.plus != null) key.append(offer.plus.features).append(offer.plus.sales);
        return key.toString();
    }

    private void build(final CloudModel.Account a) {
        LinearLayout content = clear();
        if (content == null) return;
        final Context c = ctx();

        // The state: the bars and the words, then what to do about a stop.
        mState = CloudUi.stateLine(c, 16);
        LinearLayout.LayoutParams stateLp = CloudUi.matchWrap();
        stateLp.topMargin = CloudUi.dp(c, 10);
        mState.view.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), 0);
        mState.text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(mState.view, stateLp);
        mHint = CloudUi.note(c, "");
        content.addView(mHint);

        // The actions.
        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(CloudUi.dp(c, 4), CloudUi.dp(c, 10), CloudUi.dp(c, 4), CloudUi.dp(c, 4));
        CloudStatus.Primary primary = CloudStatus.primary(a);
        // A stop whose remedy is in the settings (a wrong password, a command to fix, a server or version to pick):
        // the settings are the gold action, since starting again would stop the same way; "Başlat" stays beside them.
        boolean fix = (primary == CloudStatus.Primary.START || primary == CloudStatus.Primary.NONE)
                && CloudStatus.fixInSettings(CloudStatus.of(a, mService.nowMs(), lang()).hint);
        if (fix) {
            actions.addView(CloudUi.button(c, getString(R.string.projects_cloud_open_settings), true,
                    v -> openFor(CloudSettingsFragment.class, a.id)), CloudUi.weight(1f));
            if (primary == CloudStatus.Primary.START) {
                LinearLayout.LayoutParams lp = CloudUi.weight(1f);
                lp.setMarginStart(CloudUi.dp(c, 10));
                actions.addView(CloudUi.button(c, getString(R.string.projects_cloud_start), false, v -> act(v, "start")), lp);
            }
        } else switch (primary) {
            case START: {
                Button start = CloudUi.button(c, getString(R.string.projects_cloud_start), true, v -> act(v, "start"));
                actions.addView(start, CloudUi.weight(1f));
                break;
            }
            case STOP: {
                Button stop = CloudUi.button(c, getString(R.string.projects_cloud_stop), false, v -> act(v, "stop"));
                actions.addView(stop, CloudUi.weight(1f));
                if (CloudStatus.inGame(a) || "connecting".equals(a.live.state) || "waiting".equals(a.live.state)) {
                    Button restart = CloudUi.button(c, getString(R.string.projects_cloud_reconnect), false, v -> act(v, "restart"));
                    LinearLayout.LayoutParams lp = CloudUi.weight(1f);
                    lp.setMarginStart(CloudUi.dp(c, 10));
                    actions.addView(restart, lp);
                }
                break;
            }
            case RENEW: {
                Button renew = CloudUi.button(c, getString(a.expired ? R.string.projects_cloud_reactivate : R.string.projects_cloud_renew), true,
                        v -> mService.openSite(requireActivity(), "/satin-al?hesap=" + a.id));
                actions.addView(renew, CloudUi.weight(1f));
                break;
            }
            case NONE:
            default:
                break;
        }
        if (actions.getChildCount() > 0) {
            for (int i = 0; i < actions.getChildCount(); i++) actions.getChildAt(i).setEnabled(!mBusy);
            content.addView(actions, CloudUi.matchWrap());
        }

        // A staff decision about the account, with the appeal on the site.
        String moderation = CloudWords.moderation(c, a);
        if (moderation != null) {
            LinearLayout notice = CloudUi.notice(c, R.drawable.projects_ic_warning_circle,
                    "suspend".equals(a.moderation == null ? null : a.moderation.step) ? R.color.projects_flare_400 : R.color.projects_gold_300,
                    moderation);
            content.addView(notice);
            if (a.moderation != null && a.moderation.appealCan) {
                Button appeal = CloudUi.siteLink(c, getString(R.string.projects_cloud_mod_appeal),
                        v -> mService.openSite(requireActivity(), "/hesap/" + a.id + "/itiraz"));
                content.addView(appeal, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        // The console, then the account's live inventory and its movement (CAFK-FEAT: each only while the service, the
        // machine and the client allow it; an account that may not use one shows no row for it), then the settings.
        content.addView(CloudUi.gap(c, 14));
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        rows.add(CloudUi.row(c, R.drawable.projects_ic_terminal, getString(R.string.projects_cloud_console),
                v -> openFor(CloudConsoleFragment.class, a.id)).view);
        if (a.features.inventory()) {
            CloudUi.Row inventory = CloudUi.row(c, R.drawable.projects_ic_backpack, getString(R.string.projects_feat_inventory),
                    v -> openFor(gg.projects.mobile.cloud.inventory.CloudInventoryFragment.class, a.id));
            if (!a.features.inventoryOn) {
                inventory.count.setText(getString(R.string.projects_feat_inventory_off_short));
                inventory.count.setTextColor(CloudUi.color(c, R.color.projects_ash_400));
                inventory.count.setVisibility(View.VISIBLE);
            }
            rows.add(inventory.view);
        }
        if (gg.projects.mobile.cloud.movement.Movement.shows(mService, a)) {
            rows.add(CloudUi.row(c, R.drawable.projects_ic_footprints, getString(R.string.projects_feat_movement),
                    v -> openFor(gg.projects.mobile.cloud.movement.CloudMovementFragment.class, a.id)).view);
        }
        rows.add(CloudUi.row(c, R.drawable.projects_ic_sliders, getString(R.string.projects_cloud_settings),
                v -> openFor(CloudSettingsFragment.class, a.id)).view);
        CloudUi.fill(group, rows);
        content.addView(group);

        // The time: what is left, and extending on the site.
        content.addView(CloudUi.section(c, getString(R.string.projects_cloud_time)));
        LinearLayout time = CloudUi.group(c);
        boolean renewable = CloudStatus.renewable(a);
        mTimeRow = CloudUi.row(c, R.drawable.projects_ic_clock, "",
                renewable ? v -> mService.openSite(requireActivity(), "/satin-al?hesap=" + a.id) : null);
        if (renewable) {
            mTimeRow.count.setText(getString(a.expired ? R.string.projects_cloud_reactivate : R.string.projects_cloud_renew));
            mTimeRow.count.setVisibility(View.VISIBLE);
        }
        time.addView(mTimeRow.view);
        content.addView(time);
        if (!renewable && !a.expired && a.renewalOpensAt > 0) {
            content.addView(CloudUi.note(c, getString(R.string.projects_cloud_renew_opens, CloudWords.day(c, a.renewalOpensAt))));
        }

        // AFK+.
        if (a.plus() && mService.plusOn()) buildPlus(content, a);
        else if (mService.plusOn() && !a.expired && plusSales()) {
            content.addView(CloudUi.gap(c, 6));
            // The site's own upgrade card goes there too: buying, with this account and AFK+ chosen.
            content.addView(CloudUi.siteLink(c, getString(R.string.projects_plus_upgrade),
                    v -> mService.openSite(requireActivity(), "/satin-al?hesap=" + a.id + "&tier=plus")), wrap());
        }

        // The rest is on the site.
        content.addView(CloudUi.gap(c, 18));
        content.addView(CloudUi.note(c, getString(R.string.projects_cloud_site_more)));
        content.addView(CloudUi.siteLink(c, getString(R.string.projects_cloud_open_site),
                v -> mService.openSite(requireActivity(), "/hesap/" + a.id)), wrap());
    }

    private boolean plusSales() {
        CloudModel.Offer offer = mService.offer();
        return offer != null && offer.plus != null && offer.plus.sales;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void buildPlus(LinearLayout content, final CloudModel.Account a) {
        final Context c = ctx();
        content.addView(CloudUi.section(c, getString(R.string.projects_plus_title)));
        LinearLayout assistant = CloudUi.group(c);
        PlusFormat.Ai ai = PlusFormat.ai(a.ai);
        boolean on = a.ai != null && ("on".equals(a.ai.state) || "paused".equals(a.ai.state));
        boolean lockedOff = ai == PlusFormat.Ai.OFF_STAFF || ai == PlusFormat.Ai.OFF_RESTRICTED || ai == PlusFormat.Ai.OFF_SERVICE
                || ai == PlusFormat.Ai.OFF_SERVER || ai == PlusFormat.Ai.OFF_MODERATION || ai == PlusFormat.Ai.UNKNOWN;
        final SwitchCompat toggle = CloudUi.toggle(c, getString(R.string.projects_plus_assistant_switch), on, now -> setAssistant(now));
        toggle.setEnabled(!lockedOff && !mBusy);
        assistant.addView(toggle);
        // The state in words only when the switch does not say it already ("Asistan çalışıyor" under a switch that is on
        // read twice): a break, the credits, maintenance, notify-only, staff decisions.
        if (ai != PlusFormat.Ai.ON && ai != PlusFormat.Ai.OFF_PLAYER) {
            TextView aiLine = CloudUi.text(c, CloudWords.ai(c, a.ai, mService.nowSeconds()), 13.5f, R.color.projects_ash_300, false);
            aiLine.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
            aiLine.setLineSpacing(0, 1.15f);
            assistant.addView(aiLine);
        }
        content.addView(assistant);

        // What asks something of the player (an approval, a message) comes right under the switch; the allowance after.
        content.addView(CloudUi.gap(c, 12));
        LinearLayout screens = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        CloudUi.Row approvals = CloudUi.row(c, R.drawable.projects_ic_check_circle, getString(R.string.projects_plus_approvals),
                v -> openFor(PlusApprovalsFragment.class, a.id));
        approvals.setCount(a.ai == null ? 0 : a.ai.pendingApprovals);
        rows.add(approvals.view);
        if (mService.plusFeature("inbox")) {
            CloudUi.Row messages = CloudUi.row(c, R.drawable.projects_ic_chats, getString(R.string.projects_plus_messages),
                    v -> openFor(PlusInboxFragment.class, a.id));
            messages.setCount(mService.unread(a.id));
            rows.add(messages.view);
        }
        rows.add(CloudUi.row(c, R.drawable.projects_ic_arrows_left_right, getString(R.string.projects_plus_rules),
                v -> openFor(PlusRulesFragment.class, a.id)).view);
        rows.add(CloudUi.row(c, R.drawable.projects_ic_user_circle, getString(R.string.projects_plus_assistant),
                v -> openFor(PlusAssistantFragment.class, a.id)).view);
        if (mService.plusFeature("schedule")) {
            rows.add(CloudUi.row(c, R.drawable.projects_ic_moon, getString(R.string.projects_plus_schedule),
                    v -> openFor(PlusScheduleFragment.class, a.id)).view);
        }
        rows.add(CloudUi.row(c, R.drawable.projects_ic_wallet, getString(R.string.projects_plus_safety),
                v -> openFor(PlusSafetyFragment.class, a.id)).view);
        rows.add(CloudUi.row(c, R.drawable.projects_ic_pulse, getString(R.string.projects_plus_activity),
                v -> openFor(PlusActivityFragment.class, a.id)).view);
        if (mService.plusFeature("history")) {
            rows.add(CloudUi.row(c, R.drawable.projects_ic_history, getString(R.string.projects_plus_history),
                    v -> openFor(PlusHistoryFragment.class, a.id)).view);
        }
        CloudUi.fill(screens, rows);
        content.addView(screens);

        if (a.ai != null && a.ai.quota != null) {
            // The unit's one name on every surface ("Kontör" / "AI credits", the owner's decision of 25 October) as the
            // section's title; the two windows under it only say how long they are.
            content.addView(CloudUi.section(c, getString(R.string.projects_plus_quota_title)));
            LinearLayout credits = CloudUi.group(c);
            CloudModel.QuotaBrief q = a.ai.quota;
            long now = mService.nowSeconds();
            credits.addView(quota(c, getString(R.string.projects_plus_quota_5h), q.pct5,
                    q.w5hResetAt == null || q.pct5 == 0 ? getString(R.string.projects_plus_quota_unused)
                            : getString(R.string.projects_plus_quota_resets, CloudWords.at(c, q.w5hResetAt, now))));
            credits.addView(quota(c, getString(R.string.projects_plus_quota_week), q.pctWeek,
                    q.pctWeek == 0 ? getString(R.string.projects_plus_quota_unused)
                            : getString(R.string.projects_plus_quota_resets_day, CloudWords.relativeDay(c, q.weekResetAt, now))));
            if (q.giftsLeft > 0) {
                TextView gift = CloudUi.text(c, getString(R.string.projects_plus_quota_gift_open), 13, R.color.projects_gold_300, false);
                gift.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
                credits.addView(gift);
            }
            content.addView(credits);
        }

        if (!a.expired) {
            content.addView(CloudUi.gap(c, 16));
            // An outlined secondary action (a quiet text line read as a heading); it asks before anything changes.
            LinearLayout.LayoutParams downLp = CloudUi.matchWrap();
            downLp.setMarginStart(CloudUi.dp(c, 4));
            downLp.setMarginEnd(CloudUi.dp(c, 4));
            content.addView(CloudUi.button(c, getString(R.string.projects_plus_downgrade), false, this::askDowngrade), downLp);
            // What it does, in the site's sentence: the name alone read as if the account were not 7/24 Bulut already.
            content.addView(CloudUi.note(c, getString(R.string.projects_plus_downgrade_note)));
        }
    }

    /** One allowance: its name and when it resets on one line, the bar under it. */
    private static View quota(Context c, String label, int pct, String resets) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 12), CloudUi.dp(c, 16), CloudUi.dp(c, 4));
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = CloudUi.text(c, label, 14, R.color.projects_bone_100, false);
        line.addView(name, CloudUi.weight(1f));
        // "%34 kullanıldı" / "34% used": a bare percentage read as what was left as often as what was used (the site's words).
        String figure = CloudWords.turkish(c) ? String.format(Locale.ROOT, "%%%d", pct) : String.format(Locale.ROOT, "%d%%", pct);
        TextView percent = CloudUi.text(c, c.getString(R.string.projects_plus_quota_used, figure), 13.5f, R.color.projects_ash_300, true);
        line.addView(percent);
        box.addView(line);
        CloudUi.Bar bar = new CloudUi.Bar(c);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, CloudUi.dp(c, 6));
        barLp.topMargin = CloudUi.dp(c, 8);
        box.addView(bar, barLp);
        bar.set(pct, false);
        TextView when = CloudUi.text(c, resets, 12.5f, R.color.projects_ash_400, false);
        LinearLayout.LayoutParams whenLp = CloudUi.matchWrap();
        whenLp.topMargin = CloudUi.dp(c, 6);
        whenLp.bottomMargin = CloudUi.dp(c, 6);
        box.addView(when, whenLp);
        box.setContentDescription(label + ", " + percent.getText() + ", " + resets);
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return box;
    }

    /** The words that change by themselves: the state (uptime, the reconnect countdown) and the time left. */
    private void tick() {
        View view = getView();
        CloudModel.Account a = account();
        if (view == null || a == null) return;
        view.removeCallbacks(mTick);
        Context c = ctx();
        CloudStatus.Line line = CloudStatus.of(a, mService.nowMs(), lang());
        if (mState != null) mState.set(CloudWords.state(c, line), line);
        if (mHint != null) {
            String hint = CloudWords.hint(c, line);
            mHint.setText(hint);
            mHint.setVisibility(TextUtils.isEmpty(hint) ? View.GONE : View.VISIBLE);
        }
        if (mTimeRow != null) {
            mTimeRow.title.setText(CloudWords.timeLeft(c, CloudStatus.timeLeft(a, mService.nowMs())));
            // Gold only in the last 7 days (the site's rule); before that it is plain information.
            mTimeRow.title.setTextColor(CloudUi.color(c, CloudStatus.timeShort(a, mService.nowMs())
                    ? R.color.projects_gold_300 : R.color.projects_bone_100));
            mTimeRow.setLine(a.expired ? null : getString(R.string.projects_cloud_ends_on, CloudWords.day(c, a.expiresAt)), R.color.projects_ash_400);
        }
        if (line.nextChangeMs > 0) view.postDelayed(mTick, Math.max(1000, line.nextChangeMs));
    }

    private void act(final View from, final String what) {
        final CloudModel.Account a = account();
        if (a == null || mBusy) return;
        mBusy = true;
        render();
        CloudService.Result<CloudModel.Account> done = new CloudService.Result<CloudModel.Account>() {
            @Override
            public void ok(CloudModel.Account value) {
                mBusy = false;
                if (!alive()) return;
                Haptics.confirm(from);
                int words = "start".equals(what) ? R.string.projects_cloud_started
                        : "stop".equals(what) ? R.string.projects_cloud_stopped_toast : R.string.projects_cloud_reconnecting;
                toast(getString(words, value.name));
                render();
            }

            @Override
            public void failed(CloudProblem p) {
                mBusy = false;
                if (!alive()) return;
                problem(p, from);
                render();
            }
        };
        if ("start".equals(what)) mService.start(a.id, done);
        else if ("stop".equals(what)) mService.stop(a.id, done);
        else mService.restart(a.id, done);
    }

    private void setAssistant(final boolean on) {
        final CloudModel.Account a = account();
        if (a == null || mBusy) return;
        mBusy = true;
        mService.control(a.id, on ? "resume" : "pause", null, new CloudService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                mBusy = false;
                if (alive()) render();
            }

            @Override
            public void failed(CloudProblem p) {
                mBusy = false;
                if (!alive()) return;
                problem(p, getView());
                mShape = "";
                render();
            }
        });
    }

    private void askDowngrade(final View from) {
        final CloudModel.Account a = account();
        if (a == null) return;
        mService.downgradePreview(a.id, new CloudService.Result<CloudModel.DowngradePreview>() {
            @Override
            public void ok(CloudModel.DowngradePreview preview) {
                if (!alive()) return;
                Context c = ctx();
                if (!preview.allowed) {
                    Haptics.reject(from);
                    SocialUi.toastLong(c, getString(R.string.projects_cloud_error_downgrade_limit));
                    return;
                }
                SocialUi.confirm(requireActivity(), getString(R.string.projects_plus_downgrade_title),
                        getString(R.string.projects_plus_downgrade_body, CloudWords.span(c, preview.plainGainSeconds)),
                        getString(R.string.projects_plus_downgrade), () -> downgrade(from));
            }

            @Override
            public void failed(CloudProblem p) {
                if (alive()) problem(p, from);
            }
        });
    }

    private void downgrade(final View from) {
        final CloudModel.Account a = account();
        if (a == null) return;
        mService.downgrade(a.id, new CloudService.Result<CloudModel.Account>() {
            @Override
            public void ok(CloudModel.Account value) {
                if (!alive()) return;
                toast(getString(R.string.projects_plus_downgrade_done));
                render();
            }

            @Override
            public void failed(CloudProblem p) {
                if (alive()) problem(p, from);
            }
        });
    }

    // ---- the live socket (the service stores what it brings; the screen redraws on its change) ----

    @Override
    public void liveState(CloudService.Live state) {}

    @Override
    public void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}

    @Override
    public void feed(List<CloudModel.FeedItem> items, boolean snapshot) {}

    @Override
    public void approval(CloudModel.Approval approval) {
        CloudModel.Account a = account();
        if (a != null) mService.fetchAccount(a.id, new CloudService.Result<CloudModel.Account>() {
            @Override
            public void ok(CloudModel.Account value) {}

            @Override
            public void failed(CloudProblem problem) {}
        });
    }

    @Override
    public void hostOnline(boolean online) {}
}
