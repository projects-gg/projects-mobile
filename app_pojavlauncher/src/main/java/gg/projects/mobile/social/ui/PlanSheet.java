package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import gg.projects.mobile.social.PlanTimes;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Plan;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * M21 (TEAM5) "Plan yap": a day (today, tomorrow and the next five, as chips) and a time (a wheel of
 * the quarter hours the service takes that day), on the phone's own clock. No text, no game mode: a
 * plan is a time to meet on Projects. A refusal says why under the wheel and the sheet stays.
 */
final class PlanSheet {
    private PlanSheet() {}

    interface Done {
        void made(Plan plan);
    }

    static void show(final FragmentActivity activity, final String crewId, final Done done) {
        final SocialService service = SocialService.get(activity);
        final Crew crew = service.state().crew(crewId);
        if (crew == null) return;
        final Context context = activity;
        final float dp = context.getResources().getDisplayMetrics().density;
        final TimeZone zone = TimeZone.getDefault();
        final long now = SocialService.nowSeconds();
        final long hub = service.state().hubNow(now);
        final List<PlanTimes.Day> days = PlanTimes.days(now, zone);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(20 * dp), Math.round(4 * dp), Math.round(20 * dp), Math.round(4 * dp));
        if (service.state().crews().size() > 1) {
            TextView sub = new TextView(context);
            sub.setText(crew.team.name);
            sub.setTextSize(14);
            sub.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
            sub.setPadding(0, 0, 0, Math.round(6 * dp));
            content.addView(sub);
        }
        content.addView(heading(context, R.string.projects_plans_day));

        // The days: a row of chips that scrolls sideways on a narrow phone.
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        final LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, Math.round(4 * dp), 0, Math.round(4 * dp));
        scroller.addView(chips);
        content.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(heading(context, R.string.projects_plans_time));
        final NumberPicker wheel = new NumberPicker(context);
        wheel.setWrapSelectorWheel(false);
        // A wheel, not a field: the keyboard never opens over the sheet.
        wheel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= 29) wheel.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        wheel.setContentDescription(context.getString(R.string.projects_plans_time));
        LinearLayout.LayoutParams wheelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wheelLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(wheel, wheelLp);

        final TextView error = new TextView(context);
        error.setTextSize(13);
        error.setTextColor(ContextCompat.getColor(context, R.color.projects_flare_400));
        error.setGravity(Gravity.CENTER_HORIZONTAL);
        error.setPadding(0, Math.round(4 * dp), 0, 0);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        content.addView(error);

        final List<Long> slots = new ArrayList<>();
        final int[] day = {0};
        long first = PlanTimes.defaultSlot(hub, zone);
        int firstDay = PlanTimes.dayOf(days, first);
        if (firstDay < 0) {
            firstDay = 0;
            first = -1;
        }
        final List<TextView> dayViews = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            final int index = i;
            TextView chip = chip(context, PlanViews.dayLabel(context, days.get(i)));
            chip.setOnClickListener(v -> {
                if (index == day[0]) return;
                Haptics.tick(v);
                long was = slots.isEmpty() ? PlanTimes.defaultSlot(hub, zone) : slots.get(wheel.getValue());
                Long next = PlanTimes.sameTimeOn(days.get(index), was, service.state().hubNow(SocialService.nowSeconds()), zone);
                day[0] = index;
                for (int j = 0; j < dayViews.size(); j++) select(context, dayViews.get(j), j == index);
                fill(context, wheel, slots, days.get(index), next == null ? -1 : next,
                        service.state().hubNow(SocialService.nowSeconds()));
                error.setVisibility(View.GONE);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(40 * dp));
            lp.setMarginEnd(Math.round(8 * dp));
            chips.addView(chip, lp);
            dayViews.add(chip);
        }
        // Today with nothing left (late at night): tomorrow first.
        if (PlanTimes.slots(days.get(firstDay), hub).isEmpty() && firstDay + 1 < days.size()) firstDay++;
        day[0] = firstDay;
        for (int j = 0; j < dayViews.size(); j++) select(context, dayViews.get(j), j == firstDay);
        fill(context, wheel, slots, days.get(firstDay), first, hub);
        wheel.setOnValueChangedListener((picker, before, after) -> {
            Haptics.tick(picker);
            error.setVisibility(View.GONE);
        });
        final int shownDay = firstDay;
        scroller.post(() -> {
            View chip = dayViews.get(shownDay);
            if (chip.getRight() > scroller.getWidth()) scroller.scrollTo(chip.getLeft() - Math.round(20 * dp), 0);
        });

        android.widget.ScrollView wrap = new android.widget.ScrollView(context);
        wrap.addView(content);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_plans_make)
                .setView(wrap)
                .setPositiveButton(R.string.projects_plans_make, null)
                .setNegativeButton(R.string.projects_social_cancel, null)
                .create();
        dialog.show();
        final Button make = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (make == null) return;
        make.setEnabled(!slots.isEmpty());
        make.setOnClickListener(v -> {
            if (slots.isEmpty()) return;
            long at = slots.get(Math.max(0, Math.min(slots.size() - 1, wheel.getValue())));
            if (!PlanTimes.valid(at, service.state().hubNow(SocialService.nowSeconds()))) {
                // The sheet stayed open past that time: the wheel moves on.
                fill(context, wheel, slots, days.get(day[0]), -1, service.state().hubNow(SocialService.nowSeconds()));
                showError(error, context.getString(R.string.projects_social_error_plan_time), make);
                return;
            }
            make.setEnabled(false);
            service.makePlan(crew.id(), at, new SocialService.Result<Plan>() {
                @Override
                public void ok(Plan plan) {
                    Haptics.confirm(make);
                    dialog.dismiss();
                    if (done != null) done.made(plan);
                }

                @Override
                public void failed(SocialProblem problem) {
                    make.setEnabled(!slots.isEmpty());
                    showError(error, problemText(context, problem), make);
                }
            });
        });
    }

    /** The words of a refusal of "Plan yap" (the day's limits say so, not the general text). */
    static String problemText(Context context, SocialProblem problem) {
        switch (problem.kind) {
            case PLAN_TIME: return context.getString(R.string.projects_social_error_plan_time);
            case PLANS_FULL: return context.getString(R.string.projects_plans_full);
            case PLAN_EXISTS: return context.getString(R.string.projects_social_error_plan_exists);
            case RATE_LIMITED: return context.getString(R.string.projects_plans_today_limit);
            case ACCOUNT_LIMITED: return limitedText(context, problem.until);
            default: return SocialUi.problemText(context, problem);
        }
    }

    /** "Discord hesabın yeni olduğu için plan yapamazsın. 3 gün sonra açılır." */
    static String limitedText(Context context, Long until) {
        long now = SocialService.nowSeconds();
        int days = Math.max(1, SocialModel.Limited.daysLeft(until == null ? now + 86_400 : until, now));
        return context.getResources().getQuantityString(R.plurals.projects_limited_no_plans, days, days);
    }

    private static void showError(TextView error, String text, View feedback) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
        gg.projects.mobile.ui.CardMotion.fadeIn(error);
        Haptics.reject(feedback);
    }

    private static TextView heading(Context context, int text) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView heading = new TextView(context);
        heading.setText(text);
        heading.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
        heading.setTextSize(13);
        heading.setPadding(0, Math.round(14 * dp), 0, Math.round(4 * dp));
        try {
            heading.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        return heading;
    }

    private static TextView chip(Context context, String text) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(14);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), 0);
        chip.setBackgroundResource(R.drawable.projects_plan_day_chip);
        chip.setClickable(true);
        chip.setFocusable(true);
        try {
            chip.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            chip.setTypeface(Typeface.DEFAULT_BOLD);
        }
        Press.attach(chip, Press.Style.BUTTON);
        return chip;
    }

    private static void select(Context context, TextView chip, boolean selected) {
        chip.setSelected(selected);
        chip.setTextColor(ContextCompat.getColor(context, selected ? R.color.projects_on_gold : R.color.projects_bone_100));
    }

    /** The wheel shows {@code day}'s quarter hours, on {@code wanted} (or the nearest, or the first). */
    private static void fill(Context context, NumberPicker wheel, List<Long> slots, PlanTimes.Day day, long wanted, long hub) {
        slots.clear();
        slots.addAll(PlanTimes.slots(day, hub));
        wheel.setDisplayedValues(null);
        if (slots.isEmpty()) {
            wheel.setMinValue(0);
            wheel.setMaxValue(0);
            wheel.setDisplayedValues(new String[]{"—"});
            wheel.setEnabled(false);
            return;
        }
        wheel.setEnabled(true);
        String[] labels = new String[slots.size()];
        int at = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < slots.size(); i++) {
            labels[i] = CrewViews.time(context, slots.get(i));
            long gap = wanted < 0 ? (i == 0 ? 0 : Long.MAX_VALUE) : Math.abs(slots.get(i) - wanted);
            if (gap < best) {
                best = gap;
                at = i;
            }
        }
        wheel.setMinValue(0);
        wheel.setMaxValue(slots.size() - 1);
        wheel.setDisplayedValues(labels);
        wheel.setValue(at);
    }
}
