package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.annotation.Nullable;
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
 * the quarter hours the service takes that day), on the phone's own clock. A refusal says why under the
 * wheel and the sheet stays.
 *
 * <p>FIX2 ({@code planNotes}): an optional short note under the time ("Bedwars, sesli gelin"; one line,
 * 80 characters, no links or phone numbers, the chat's own check before anything is sent), and its own
 * small sheet to add, change or clear it later.
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

        // FIX2: the note, when the service keeps one (old services take a plan without it).
        final EditText note = service.gate().planNotes ? noteField(context, null) : null;
        final TextView counter = note == null ? null : counter(context);
        if (note != null) {
            content.addView(heading(context, R.string.projects_plans_note));
            content.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(counter);
        }

        final TextView error = new TextView(context);
        error.setTextSize(13);
        error.setTextColor(ContextCompat.getColor(context, R.color.projects_flare_400));
        error.setGravity(Gravity.CENTER_HORIZONTAL);
        error.setPadding(0, Math.round(4 * dp), 0, 0);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        content.addView(error);
        if (note != null) watch(context, note, counter, error);

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
        // The keyboard comes only when the note is tapped, and then the sheet makes room for it.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
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
            String text = note == null ? null : note.getText().toString();
            String why = noteCheck(context, text);
            if (why != null) {
                showError(error, why, make);
                return;
            }
            make.setEnabled(false);
            service.makePlan(crew.id(), at, text, new SocialService.Result<Plan>() {
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

    // ---- the note (FIX2) ----

    interface NoteDone {
        void saved(Plan plan);
    }

    /**
     * "Not ekle" / "Notu düzenle": the plan's maker writes, changes or clears its note (an empty field
     * clears it). The field has the focus and the keyboard is up as the sheet opens.
     */
    static void note(final FragmentActivity activity, final Plan plan, @Nullable final NoteDone done) {
        final SocialService service = SocialService.get(activity);
        final Context context = activity;
        final float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(20 * dp), Math.round(4 * dp), Math.round(20 * dp), Math.round(4 * dp));
        TextView when = new TextView(context);
        when.setText(PlanViews.when(context, plan.at, SocialService.nowSeconds()));
        when.setTextSize(14);
        when.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
        when.setPadding(0, 0, 0, Math.round(8 * dp));
        content.addView(when);
        final EditText field = noteField(context, plan.note);
        content.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView counter = counter(context);
        content.addView(counter);
        final TextView error = new TextView(context);
        error.setTextSize(13);
        error.setTextColor(ContextCompat.getColor(context, R.color.projects_flare_400));
        error.setPadding(0, Math.round(4 * dp), 0, 0);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        content.addView(error);
        watch(context, field, counter, error);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(plan.note == null ? R.string.projects_plans_note_add : R.string.projects_plans_note_edit)
                .setView(content)
                .setPositiveButton(R.string.projects_plans_note_save, null)
                .setNegativeButton(R.string.projects_social_cancel, null)
                .create();
        if (dialog.getWindow() != null) {
            // The field is what this sheet is for: it has the focus and the keyboard at once.
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        field.requestFocus();
        dialog.show();
        final Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (save == null) return;
        save.setOnClickListener(v -> {
            String text = field.getText().toString();
            String why = noteCheck(context, text);
            if (why != null) {
                showError(error, why, save);
                return;
            }
            String clean = gg.projects.mobile.social.PlanNote.clean(text);
            if (clean.equals(plan.note == null ? "" : plan.note)) {
                dialog.dismiss();
                return;
            }
            save.setEnabled(false);
            service.setPlanNote(plan, clean, new SocialService.Result<Plan>() {
                @Override
                public void ok(Plan stored) {
                    Haptics.confirm(save);
                    dialog.dismiss();
                    SocialUi.toast(activity, activity.getString(clean.isEmpty() ? R.string.projects_plans_note_removed
                            : R.string.projects_plans_note_saved));
                    if (done != null) done.saved(stored);
                }

                @Override
                public void failed(SocialProblem problem) {
                    save.setEnabled(true);
                    if (problem.kind == SocialProblem.Kind.PLAN_GONE) {
                        dialog.dismiss();
                        SocialUi.toast(activity, activity.getString(R.string.projects_social_error_plan_gone));
                        return;
                    }
                    showError(error, noteProblemText(context, problem), save);
                }
            });
        });
    }

    /** A note's refusal: a change too many today says so (answers and notes share 20 changes a day). */
    static String noteProblemText(Context context, SocialProblem problem) {
        if (problem.kind == SocialProblem.Kind.RATE_LIMITED) return context.getString(R.string.projects_plans_changes_limit);
        return problemText(context, problem);
    }

    /** "Notu kaldır": the maker's own note goes at once; someone else's (a leader or moderator) after one sentence. */
    static void clearNote(final FragmentActivity activity, final Plan plan, @Nullable final NoteDone done) {
        final SocialService service = SocialService.get(activity);
        Runnable clear = () -> service.setPlanNote(plan, "", new SocialService.Result<Plan>() {
            @Override
            public void ok(Plan stored) {
                SocialUi.toast(activity, activity.getString(R.string.projects_plans_note_removed));
                if (done != null) done.saved(stored);
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toast(activity, problem.kind == SocialProblem.Kind.PLAN_GONE
                        ? activity.getString(R.string.projects_social_error_plan_gone) : noteProblemText(activity, problem));
            }
        });
        if (plan.by.equals(service.state().myId())) {
            clear.run();
        } else {
            SocialUi.confirm(activity, activity.getString(R.string.projects_plans_note_remove),
                    activity.getString(R.string.projects_plans_note_remove_ask), activity.getString(R.string.projects_plans_note_remove), clear);
        }
    }

    /** One line, 80 characters (a little more is let in so the counter can say it is too long). */
    private static EditText noteField(Context context, @Nullable String text) {
        float dp = context.getResources().getDisplayMetrics().density;
        EditText field = new EditText(context);
        field.setBackgroundResource(R.drawable.projects_input);
        field.setHint(R.string.projects_plans_note_hint);
        field.setHintTextColor(ContextCompat.getColor(context, R.color.projects_obsidian_600));
        field.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        field.setTextSize(15);
        field.setMinHeight(Math.round(48 * dp));
        field.setSingleLine(true);
        field.setPadding(Math.round(12 * dp), Math.round(10 * dp), Math.round(12 * dp), Math.round(10 * dp));
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(gg.projects.mobile.social.PlanNote.MAX + 20)});
        if (text != null) {
            field.setText(text);
            field.setSelection(field.getText().length());
        }
        field.setContentDescription(context.getString(R.string.projects_plans_note));
        return field;
    }

    private static TextView counter(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView counter = new TextView(context);
        counter.setTextSize(12);
        counter.setGravity(Gravity.END);
        counter.setPadding(0, Math.round(4 * dp), Math.round(2 * dp), 0);
        counter.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
        counter.setVisibility(View.INVISIBLE);
        return counter;
    }

    /** The counter shows near the limit and turns flare past it; a problem goes as soon as the text changes. */
    private static void watch(final Context context, EditText field, final TextView counter, final TextView error) {
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String clean = gg.projects.mobile.social.PlanNote.clean(s.toString());
                int length = clean.codePointCount(0, clean.length());
                counter.setVisibility(length >= gg.projects.mobile.social.PlanNote.COUNTER_FROM ? View.VISIBLE : View.INVISIBLE);
                counter.setText(context.getString(R.string.projects_crew_count, length, gg.projects.mobile.social.PlanNote.MAX));
                counter.setTextColor(ContextCompat.getColor(context, length > gg.projects.mobile.social.PlanNote.MAX
                        ? R.color.projects_flare_400 : R.color.projects_ash_400));
                if (error.getVisibility() == View.VISIBLE) error.setVisibility(View.GONE);
            }
        });
    }

    /** What is wrong with a note before it is sent (the chat's own checks), or null. */
    @Nullable
    private static String noteCheck(Context context, @Nullable String text) {
        switch (gg.projects.mobile.social.PlanNote.check(text)) {
            case LINK: return context.getString(R.string.projects_plans_note_link);
            case PHONE: return context.getString(R.string.projects_plans_note_phone);
            case TOO_LONG: return context.getString(R.string.projects_plans_note_long);
            default: return null;
        }
    }

    /** The words of a refusal of "Plan yap" (the day's limits say so, not the general text). */
    static String problemText(Context context, SocialProblem problem) {
        switch (problem.kind) {
            case PLAN_NOTE:
                // FIX2: the chat's reasons; a refusal without one is a form problem (too long, an empty-looking note).
                if ("link".equals(problem.reason)) return context.getString(R.string.projects_plans_note_link);
                if ("phone".equals(problem.reason)) return context.getString(R.string.projects_plans_note_phone);
                if ("words".equals(problem.reason)) return context.getString(R.string.projects_plans_note_words);
                return context.getString(R.string.projects_plans_note_long);
            case CHAT_MUTED:
                return problem.until == null ? context.getString(R.string.projects_plans_note_words)
                        : context.getString(R.string.projects_plans_note_muted, SocialUi.moment(context, problem.until));
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
