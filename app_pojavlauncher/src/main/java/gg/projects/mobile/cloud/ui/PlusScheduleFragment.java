package gg.projects.mobile.cloud.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Çalışma saatleri": the hours the assistant answers (Turkey time; outside them it only says the player is away and
 * starts no trades), and a break until a given time.
 */
public final class PlusScheduleFragment extends PlusScreen {
    public static final String TAG = "ProjectsPlusScheduleFragment";
    private static final TimeZone TURKEY = TimeZone.getTimeZone("Europe/Istanbul");

    @Override
    protected int title() {
        return R.string.projects_plus_schedule;
    }

    @Override
    protected boolean wantsCatalog() {
        return false;
    }

    @Override
    protected void draw(LinearLayout content) {
        final Context c = ctx();
        final PlusConfig e = mEdit;
        if (e == null) return;
        content.addView(CloudUi.gap(c, 8));
        LinearLayout on = CloudUi.group(c);
        on.addView(CloudUi.toggle(c, getString(R.string.projects_plus_schedule_switch), e.scheduleOn(), now -> {
            e.setScheduleOn(now);
            if (now && e.windows().isEmpty()) {
                e.setWindows(new ArrayList<>(java.util.Collections.singletonList(
                        new PlusConfig.Window(new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5)), "18:00", "23:00"))));
            }
            changed();
            redraw();
        }));
        content.addView(on);
        content.addView(CloudUi.note(c, getString(R.string.projects_plus_schedule_help)));

        final List<PlusConfig.Window> windows = e.windows();
        String all = null;
        for (String[] p : mProblems) if (p[0].equals("schedule.windows")) all = words(p[0], p[1]);
        if (all != null) {
            TextView error = CloudRowSheet.error(c);
            CloudRowSheet.show(error, all);
            content.addView(error);
        }
        for (int i = 0; i < windows.size(); i++) content.addView(window(c, e, windows, i));
        if (windows.size() < PlusConfig.WINDOWS_MAX) {
            // The same add row as the app's other lists, in a group of its own under the windows' cards.
            CloudUi.Row add = CloudUi.addRow(c, getString(R.string.projects_plus_schedule_add), v -> {
                List<PlusConfig.Window> next = new ArrayList<>(e.windows());
                next.add(new PlusConfig.Window(new ArrayList<>(Arrays.asList(6, 7)), "12:00", "23:00"));
                e.setWindows(next);
                changed();
                redraw();
            });
            LinearLayout addGroup = CloudUi.group(c);
            addGroup.addView(add.view);
            LinearLayout.LayoutParams addLp = CloudUi.matchWrap();
            addLp.topMargin = CloudUi.dp(c, 12);
            content.addView(addGroup, addLp);
        }

        // A break.
        content.addView(CloudUi.section(c, getString(R.string.projects_plus_break)));
        long now = mService.nowSeconds();
        Long until = e.pauseUntil();
        if (until != null && until > now) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_moon, R.color.projects_gold_300,
                    getString(R.string.projects_plus_break_on, CloudWords.until(c, until, now))));
            content.addView(CloudUi.textButton(c, getString(R.string.projects_plus_break_end), v -> {
                e.setPauseUntil(null);
                changed();
                redraw();
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        rows.add(breakRow(c, e, getString(R.string.projects_plus_break_1h), now + 3600));
        rows.add(breakRow(c, e, getString(R.string.projects_plus_break_4h), now + 4 * 3600));
        rows.add(breakRow(c, e, getString(R.string.projects_plus_break_morning), tomorrowMorning(mService.nowMs())));
        CloudUi.Row pick = CloudUi.row(c, R.drawable.projects_ic_clock, getString(R.string.projects_plus_break_pick), v -> pickBreak(e));
        rows.add(pick.view);
        CloudUi.fill(group, rows);
        content.addView(group);
    }

    private View breakRow(Context c, final PlusConfig e, String label, final long until) {
        CloudUi.Row row = CloudUi.actionRow(c, R.drawable.projects_ic_moon, label, v -> {
            Haptics.tick(v);
            e.setPauseUntil(until);
            changed();
            redraw();
        });
        return row.view;
    }

    private static long tomorrowMorning(long nowMs) {
        Calendar cal = Calendar.getInstance(TURKEY);
        cal.setTimeInMillis(nowMs);
        cal.add(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 8);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis() / 1000L;
    }

    private void pickBreak(final PlusConfig e) {
        final long nowMs = mService.nowMs();
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(nowMs + 3600_000L);
        new TimePickerDialog(requireActivity(), (view, h, m) -> {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(nowMs);
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= nowMs) cal.add(Calendar.DAY_OF_MONTH, 1);
            e.setPauseUntil(cal.getTimeInMillis() / 1000L);
            changed();
            redraw();
        }, start.get(Calendar.HOUR_OF_DAY), 0, true).show();
    }

    /** One window: the days as toggles, from and to, and removing it. */
    private View window(final Context c, final PlusConfig e, final List<PlusConfig.Window> windows, final int index) {
        final PlusConfig.Window w = windows.get(index);
        LinearLayout box = CloudUi.group(c);
        LinearLayout.LayoutParams boxLp = CloudUi.matchWrap();
        boxLp.topMargin = CloudUi.dp(c, 12);
        box.setLayoutParams(boxLp);
        LinearLayout days = new LinearLayout(c);
        days.setOrientation(LinearLayout.HORIZONTAL);
        days.setPadding(CloudUi.dp(c, 10), CloudUi.dp(c, 10), CloudUi.dp(c, 10), CloudUi.dp(c, 4));
        int[] names = {R.string.projects_plus_day_1, R.string.projects_plus_day_2, R.string.projects_plus_day_3, R.string.projects_plus_day_4,
                R.string.projects_plus_day_5, R.string.projects_plus_day_6, R.string.projects_plus_day_7};
        for (int d = 1; d <= 7; d++) {
            final int day = d;
            final boolean on = w.days.contains(day);
            Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
            b.setText(getString(names[d - 1]));
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setTypeface(CloudUi.font(c, R.font.figtree_semibold));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(CloudUi.dp(c, 44));
            b.setMinimumHeight(CloudUi.dp(c, 44));
            b.setPadding(0, 0, 0, 0);
            b.setBackground(ChamferDrawable.toggle(c));
            b.setActivated(on);
            b.setTextColor(CloudUi.color(c, on ? R.color.projects_gold_300 : R.color.projects_ash_300));
            if (android.os.Build.VERSION.SDK_INT >= 30) b.setStateDescription(on ? getString(R.string.projects_cloud_selected) : null);
            Press.attach(b, Press.Style.BUTTON);
            b.setOnClickListener(v -> {
                List<Integer> next = new ArrayList<>(w.days);
                if (on) next.remove(Integer.valueOf(day));
                else next.add(day);
                List<PlusConfig.Window> all = new ArrayList<>(e.windows());
                all.set(index, new PlusConfig.Window(next, w.from, w.to));
                e.setWindows(all);
                Haptics.tick(v);
                changed();
                redraw();
            });
            LinearLayout.LayoutParams lp = CloudUi.weight(1f);
            if (d > 1) lp.setMarginStart(CloudUi.dp(c, 4));
            days.addView(b, lp);
        }
        box.addView(days, CloudUi.matchWrap());
        String problem = problemAt("schedule.windows[" + index + "]");
        if (problem != null) {
            TextView error = CloudRowSheet.error(c);
            error.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 4));
            CloudRowSheet.show(error, problem);
            box.addView(error);
        }
        // Times are picked in place: rows without icons or carets, as in the quiet hours (one look for a time everywhere).
        CloudUi.Row from = CloudUi.valueRow(c, getString(R.string.projects_plus_schedule_from), w.from, v -> time(w.from, t -> {
            List<PlusConfig.Window> all = new ArrayList<>(e.windows());
            all.set(index, new PlusConfig.Window(w.days, t, w.to));
            e.setWindows(all);
            changed();
            redraw();
        }));
        box.addView(from.view);
        box.addView(CloudUi.textDivider(c));
        CloudUi.Row to = CloudUi.valueRow(c, getString(R.string.projects_plus_schedule_to), w.to, v -> time(w.to, t -> {
            List<PlusConfig.Window> all = new ArrayList<>(e.windows());
            all.set(index, new PlusConfig.Window(w.days, w.from, t));
            e.setWindows(all);
            changed();
            redraw();
        }));
        box.addView(to.view);
        if (windows.size() > 1 || !e.scheduleOn()) {
            box.addView(CloudUi.textDivider(c));
            Button remove = CloudUi.textButton(c, getString(R.string.projects_plus_schedule_remove), v -> {
                List<PlusConfig.Window> all = new ArrayList<>(e.windows());
                all.remove(index);
                e.setWindows(all);
                changed();
                redraw();
            });
            // Quiet, not flare: it is one edit among others, undone with the save bar's "Vazgeç" (two red links shouted).
            remove.setTextColor(CloudUi.color(c, R.color.projects_ash_300));
            LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            removeLp.setMarginStart(CloudUi.dp(c, 4));
            box.addView(remove, removeLp);
        }
        return box;
    }

    private interface Picked {
        void picked(String time);
    }

    private void time(String current, final Picked picked) {
        int hour = 18;
        int minute = 0;
        try {
            hour = Integer.parseInt(current.substring(0, 2));
            minute = Integer.parseInt(current.substring(3, 5));
        } catch (RuntimeException ignored) {
            // Not a time: start from 18:00.
        }
        new TimePickerDialog(requireActivity(), (view, h, m) -> picked.picked(String.format(Locale.ROOT, "%02d:%02d", h, m)), hour, minute, true).show();
    }
}
