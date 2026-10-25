package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.Haptics;

/**
 * "Harcama sınırları": how much the assistant may spend (per deal, a day, in total), the balance it never goes
 * below, when it asks first, and how long it waits for an answer (no answer counts as no). An empty field is no limit;
 * without a per-deal limit and a total budget it only reports finds. Amounts are written out in words as they are
 * typed.
 */
public final class PlusSafetyFragment extends PlusScreen {
    public static final String TAG = "ProjectsPlusSafetyFragment";
    private static final String[] AMOUNTS = {"perDealMax", "dailySpendMax", "totalBudget", "approvalAbove", "newCounterpartApprovalAbove"};

    @Override
    protected int title() {
        return R.string.projects_plus_safety;
    }

    @Override
    protected void draw(LinearLayout content) {
        final Context c = ctx();
        final PlusConfig e = mEdit;
        if (e == null) return;
        if (!e.canSpend()) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_wallet, R.color.projects_ash_300,
                    getString(R.string.projects_plus_notify_only_note)));
        }
        for (final String key : AMOUNTS) {
            Long value = e.limit(key);
            final PlusRowSheet.Amount amount = new PlusRowSheet.Amount(c, CloudWords.limitLabel(c, key), value == null ? 0 : value);
            amount.field.input.setHint(getString(R.string.projects_plus_limit_none));
            String problem = problemAt("limits." + key);
            if (problem != null) amount.field.setError(problem);
            amount.field.input.addTextChangedListener(watcher(() -> {
                e.setLimit(key, amount.value());
                changed();
            }));
            content.addView(amount.field.view);
        }
        final PlusRowSheet.Amount floor = new PlusRowSheet.Amount(c, CloudWords.limitLabel(c, "balanceFloor"), e.balanceFloor());
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

        content.addView(CloudUi.gap(c, 18));
        content.addView(CloudUi.textButton(c, getString(R.string.projects_plus_reset_spent), this::askReset),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
