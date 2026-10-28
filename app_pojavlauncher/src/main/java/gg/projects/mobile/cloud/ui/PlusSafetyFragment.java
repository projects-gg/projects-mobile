package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.Haptics;

/**
 * "İnce ayar": what the assistant may do (only reply, haggle and ask, haggle and close), which sides it trades, what
 * deals may be paid with (only while Studio's item swaps are on) and with whom (everyone, or the player's friends by
 * their in-game names); then "Harcama": how much the assistant may spend (per deal, a day, in total), the balance it never goes
 * below, when it asks first, and how long it waits for an answer (no answer counts as no). An empty field is no limit;
 * without a per-deal limit and a total budget it only reports finds. Amounts are written out in words as they are
 * typed. Above the fields, what it has spent today and from the total budget, when the machine answered (CAFK-W5).
 */
public final class PlusSafetyFragment extends PlusScreen {
    public static final String TAG = "ProjectsPlusSafetyFragment";
    private static final String[] AMOUNTS = {"perDealMax", "dailySpendMax", "totalBudget", "approvalAbove", "newCounterpartApprovalAbove"};

    /** The live figures; null when the machine did not answer (then nothing is said about them). */
    @Nullable
    private CloudModel.Spent mSpent;
    @Nullable
    private CloudUi.Figure mToday;
    @Nullable
    private CloudUi.Figure mTotal;

    @Override
    protected int title() {
        return R.string.projects_fine_title;
    }

    @Override
    protected void begin(CloudModel.PlusDoc doc) {
        // A fresh read says what is spent now; a save's answer without the figures keeps the last ones.
        if (mDoc == null || doc.spent != null) mSpent = doc.spent;
        super.begin(doc);
    }

    @Override
    protected void draw(LinearLayout content) {
        final Context c = ctx();
        final PlusConfig e = mEdit;
        if (e == null) return;
        fine(content, e);
        content.addView(CloudUi.section(c, getString(R.string.projects_fine_spending)));
        if (!e.canSpend()) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_wallet, R.color.projects_ash_300,
                    getString(R.string.projects_plus_notify_only_note)));
        }
        // Bare figures on a page about spending could read as real money: the site's first line says whose money it is.
        content.addView(CloudUi.note(c, getString(R.string.projects_plus_limits_money)));
        spent(content, e);
        for (final String key : AMOUNTS) {
            Long value = e.limit(key);
            final PlusAmount amount = new PlusAmount(c, CloudWords.limitLabel(c, key), value == null ? 0 : value);
            amount.field.input.setHint(getString(R.string.projects_plus_limit_none));
            String problem = problemAt("limits." + key);
            if (problem != null) amount.field.setError(problem);
            amount.field.input.addTextChangedListener(watcher(() -> {
                e.setLimit(key, amount.value());
                changed();
            }));
            content.addView(amount.field.view);
        }
        final PlusAmount floor = new PlusAmount(c, CloudWords.limitLabel(c, "balanceFloor"), e.balanceFloor());
        floor.field.input.setHint("0");
        String floorProblem = problemAt("limits.balanceFloor");
        if (floorProblem != null) floor.field.setError(floorProblem);
        floor.field.input.addTextChangedListener(watcher(() -> {
            Long v = floor.value();
            e.setLimit("balanceFloor", v == null ? 0L : v);
            changed();
        }));
        content.addView(floor.field.view);

        final CloudUi.Field timeout = CloudUi.field(c, CloudWords.limitLabel(c, "approvalTimeoutMin"), String.valueOf(e.approvalTimeoutMin()), null,
                InputType.TYPE_CLASS_NUMBER);
        timeout.input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});
        timeout.setHelp(getString(R.string.projects_plus_limit_timeout_help));
        String timeoutProblem = problemAt("limits.approvalTimeoutMin");
        if (timeoutProblem != null) timeout.setError(timeoutProblem);
        timeout.input.addTextChangedListener(watcher(() -> {
            e.setLimit("approvalTimeoutMin", (long) CloudRowSheet.number(timeout.value(), 0));
            changed();
        }));
        content.addView(timeout.unit(getString(R.string.projects_plus_limit_minutes)).view);
        // The limits' one quiet line (afkplus-free.md §3.5): never said to the other player.
        content.addView(CloudUi.note(c, getString(R.string.projects_fine_secret)));

        content.addView(CloudUi.gap(c, 18));
        content.addView(CloudUi.textButton(c, getString(R.string.projects_plus_reset_spent), this::askReset),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A choice's words with its one line under it (the outcome of these choices is not obvious). */
    private static CharSequence choiceText(Context c, int title, int help) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder(c.getString(title));
        int start = out.length();
        out.append('\n').append(c.getString(help));
        out.setSpan(new android.text.style.AbsoluteSizeSpan(13, true), start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.ForegroundColorSpan(CloudUi.color(c, R.color.projects_ash_400)), start, out.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    private static int index(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }

    /** "İnce ayar" (afkplus-free.md §3.1, the launcher's group): the authority, the sides, the payment, the counterparts. */
    private void fine(LinearLayout content, final PlusConfig e) {
        final Context c = ctx();
        CloudModel.PlusDoc doc = mDoc;
        content.addView(CloudUi.section(c, getString(R.string.projects_fine_authority)));
        LinearLayout authority = CloudUi.group(c);
        authority.addView(CloudUi.choices(c, new CharSequence[]{
                choiceText(c, R.string.projects_fine_reply, R.string.projects_fine_reply_help),
                choiceText(c, R.string.projects_fine_ask, R.string.projects_fine_ask_help),
                choiceText(c, R.string.projects_fine_deal, R.string.projects_fine_deal_help)},
                index(PlusConfig.AUTHORITIES, e.authority()), true, i -> {
                    e.setAuthority(PlusConfig.AUTHORITIES[i]);
                    changed();
                }));
        content.addView(authority);
        String authorityProblem = problemAt("authority");
        if (authorityProblem != null) content.addView(errorLine(c, authorityProblem));

        LinearLayout sides = CloudUi.group(c);
        sides.addView(CloudUi.toggle(c, getString(R.string.projects_fine_buy), e.allows("buy"), on -> {
            e.setAllows("buy", on);
            changed();
        }));
        sides.addView(CloudUi.textDivider(c));
        sides.addView(CloudUi.toggle(c, getString(R.string.projects_fine_sell), e.allows("sell"), on -> {
            e.setAllows("sell", on);
            changed();
        }));
        LinearLayout.LayoutParams sidesLp = CloudUi.matchWrap();
        sidesLp.topMargin = CloudUi.dp(c, 12);
        content.addView(sides, sidesLp);

        // Item swaps only while Studio has them on (features.itemPay); without them the payment is money.
        if (doc != null && doc.rules.itemPay) {
            content.addView(CloudRowSheet.label(c, getString(R.string.projects_fine_pay)));
            content.addView(CloudUi.segmented(c, new CharSequence[]{getString(R.string.projects_fine_pay_money),
                    getString(R.string.projects_fine_pay_items), getString(R.string.projects_fine_pay_both)},
                    index(PlusConfig.PAYS, e.pay()), i -> {
                        e.setPay(PlusConfig.PAYS[i]);
                        changed();
                    }));
            String payProblem = problemAt("pay");
            if (payProblem != null) content.addView(errorLine(c, payProblem));
        }

        content.addView(CloudRowSheet.label(c, getString(R.string.projects_fine_who)));
        final boolean friendsOnly = "friends".equals(e.whoMode());
        content.addView(CloudUi.segmented(c, new CharSequence[]{getString(R.string.projects_fine_who_all),
                getString(R.string.projects_fine_who_friends)}, friendsOnly ? 1 : 0, i -> {
            e.setWho(i == 1 ? "friends" : "all", e.friends());
            changed();
            redraw();
        }));
        if (friendsOnly) friends(content, e);
    }

    private static android.widget.TextView errorLine(Context c, String words) {
        android.widget.TextView error = CloudRowSheet.error(c);
        CloudRowSheet.show(error, words);
        return error;
    }

    /** The friends' in-game names ("Kimlerle: Yalnız arkadaşlarım"), as the blocked players' list. */
    private void friends(LinearLayout content, final PlusConfig e) {
        final Context c = ctx();
        final List<String> names = new ArrayList<>(e.friends());
        if (names.isEmpty()) {
            content.addView(CloudUi.note(c, getString(R.string.projects_fine_friends_empty)));
        } else {
            LinearLayout group = CloudUi.group(c);
            for (int i = 0; i < names.size(); i++) {
                final String name = names.get(i);
                if (i > 0) group.addView(CloudUi.textDivider(c));
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(CloudUi.dp(c, 52));
                row.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 4), 0);
                row.addView(CloudUi.text(c, name, 15, R.color.projects_bone_100, false), CloudUi.weight(1f));
                String problem = problemAt("who.friends[" + i + "]");
                if (problem != null) row.addView(CloudUi.text(c, problem, 12.5f, R.color.projects_flare_400, false));
                android.widget.Button remove = CloudUi.textButton(c, getString(R.string.projects_fine_friends_remove_short), v -> {
                    List<String> next = new ArrayList<>(e.friends());
                    next.remove(name);
                    e.setWho("friends", next);
                    Haptics.tick(v);
                    changed();
                    redraw();
                });
                remove.setContentDescription(getString(R.string.projects_fine_friends_remove, name));
                row.addView(remove);
                group.addView(row, CloudUi.matchWrap());
            }
            content.addView(group);
        }
        if (names.size() >= PlusConfig.FRIENDS_MAX) {
            content.addView(CloudUi.note(c, getResources().getQuantityString(R.plurals.projects_fine_friends_full, PlusConfig.FRIENDS_MAX,
                    PlusConfig.FRIENDS_MAX)));
            return;
        }
        LinearLayout add = new LinearLayout(c);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.setGravity(android.view.Gravity.BOTTOM);
        final CloudUi.Field field = CloudUi.field(c, getString(R.string.projects_fine_friends_add), null, getString(R.string.projects_fine_friends_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        String listProblem = problemAt("who.friends");
        if (listProblem != null) field.setError(listProblem);
        add.addView(field.view, CloudUi.weight(1f));
        android.widget.Button addButton = CloudUi.button(c, getString(R.string.projects_fine_friends_add), false, v -> {
            String name = field.value().trim();
            if (!gg.projects.mobile.cloud.CloudEdit.isGameName(name)) {
                field.setError(getString(R.string.projects_fine_friends_format));
                Haptics.reject(v);
                return;
            }
            for (String n : e.friends()) {
                if (n.equalsIgnoreCase(name)) {
                    field.setError(getString(R.string.projects_fine_friends_duplicate));
                    Haptics.reject(v);
                    return;
                }
            }
            for (String n : e.blocked()) {
                if (n.equalsIgnoreCase(name)) {
                    field.setError(getString(R.string.projects_fine_friends_blocked));
                    Haptics.reject(v);
                    return;
                }
            }
            List<String> next = new ArrayList<>(e.friends());
            next.add(name);
            e.setWho("friends", next);
            Haptics.confirm(v);
            changed();
            redraw();
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMarginStart(CloudUi.dp(c, 4));
        addLp.bottomMargin = CloudUi.dp(c, 2);
        add.addView(addButton, addLp);
        content.addView(add, CloudUi.matchWrap());
        field.label.setVisibility(View.GONE);
    }

    /** Spent today and from the total budget; left out without the machine's answer, or while it may not buy and has spent nothing. */
    private void spent(LinearLayout content, PlusConfig e) {
        mToday = null;
        mTotal = null;
        CloudModel.Spent spent = mSpent;
        if (spent == null || (!e.canSpend() && spent.total == 0 && spent.today == 0)) return;
        Context c = ctx();
        mToday = CloudUi.figureRow(c, getString(R.string.projects_plus_spent_today), CloudWords.money(c, spent.today));
        mTotal = CloudUi.figureRow(c, getString(R.string.projects_plus_spent_total), CloudWords.money(c, spent.total));
        List<View> rows = new ArrayList<>();
        rows.add(mToday.view);
        rows.add(mTotal.view);
        LinearLayout group = CloudUi.group(c);
        CloudUi.fillFigures(group, rows);
        content.addView(group);
        content.addView(CloudUi.gap(c, 14));
    }

    private void showSpent(@Nullable CloudModel.Spent spent) {
        mSpent = spent;
        if (spent == null || mToday == null || mTotal == null) return;
        Context c = ctx();
        mToday.set(CloudWords.money(c, spent.today));
        mTotal.set(CloudWords.money(c, spent.total));
    }

    private static TextWatcher watcher(final Runnable changed) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                changed.run();
            }
        };
    }

    private void askReset(final View from) {
        SocialUi.confirm(requireActivity(), getString(R.string.projects_plus_reset_spent), getString(R.string.projects_plus_reset_spent_body),
                getString(R.string.projects_plus_reset_spent), () -> {
                    CloudModel.Account a = account();
                    if (a == null) return;
                    mService.control(a.id, "reset_spent", null, new CloudService.Result<JsonObject>() {
                        @Override
                        public void ok(JsonObject value) {
                            if (!alive()) return;
                            Haptics.confirm(from);
                            CloudModel.AiView ai = CloudModel.AiView.read(Json.obj(value, "ai"));
                            CloudModel.Spent before = mSpent;
                            // The total starts again from zero; today's figure is not touched by a reset.
                            showSpent(ai != null && ai.spent != null ? ai.spent
                                    : before == null ? null : new CloudModel.Spent(0, before.today));
                            toast(getString(R.string.projects_plus_reset_spent_done));
                        }

                        @Override
                        public void failed(CloudProblem problem) {
                            if (alive()) problem(problem, from);
                        }
                    });
                });
    }
}
