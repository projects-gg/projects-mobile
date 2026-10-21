package gg.projects.mobile.social.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.TimeZone;

import gg.projects.mobile.social.MutePresets;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Press;

/**
 * M20: the owner's "Sessize al" presets as one sheet, for a crew, for every crew together and for a
 * conversation on this phone: "1 saat", "8 saat", "Yarın sabaha kadar" ("Sabaha kadar" after
 * midnight) and "Ben açana kadar"; while muted, "Sessizde · …" and "Sesi aç" above them.
 */
final class MuteSheet {
    private MuteSheet() {}

    interface Choice {
        void preset(MutePresets.Preset preset, View row);

        void unmute(View button);
    }

    /**
     * @param state "Sessizde · Yarın 08:00" while muted, else null
     * @param top extra views above the presets (a switch), or null
     * @param bottom extra views under them (the all-crews line), or null
     */
    static AlertDialog show(FragmentActivity activity, String title, @Nullable String subtitle, @Nullable String state,
                            long nowSeconds, @Nullable View top, @Nullable View bottom, final Choice choice) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(20 * dp), Math.round(4 * dp), Math.round(20 * dp), 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(R.string.projects_close, null)
                .create();
        if (subtitle != null) {
            TextView sub = new TextView(activity);
            sub.setText(subtitle);
            sub.setTextSize(14);
            sub.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_300));
            sub.setPadding(0, 0, 0, Math.round(6 * dp));
            content.addView(sub);
        }
        if (top != null) content.addView(top);
        if (state != null) {
            TextView muted = new TextView(activity);
            muted.setText(state);
            muted.setTextColor(ContextCompat.getColor(activity, R.color.projects_gold_300));
            muted.setTextSize(14);
            muted.setPadding(0, Math.round(10 * dp), 0, Math.round(4 * dp));
            content.addView(muted);
            Button unmute = new Button(activity, null, android.R.attr.borderlessButtonStyle);
            unmute.setText(R.string.projects_crew_unmute);
            unmute.setAllCaps(false);
            unmute.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
            unmute.setBackground(ChamferDrawable.outlined(activity));
            Press.attach(unmute, Press.Style.BUTTON);
            unmute.setOnClickListener(v -> {
                dialog.dismiss();
                choice.unmute(v);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(4 * dp);
            content.addView(unmute, lp);
        }
        TextView heading = new TextView(activity);
        heading.setText(R.string.projects_crew_mute);
        heading.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_300));
        heading.setTextSize(13);
        heading.setPadding(0, Math.round(18 * dp), 0, Math.round(4 * dp));
        try {
            heading.setTypeface(ResourcesCompat.getFont(activity, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        content.addView(heading);
        TimeZone zone = TimeZone.getDefault();
        boolean tomorrow = MutePresets.morningIsTomorrow(nowSeconds, zone);
        String morning = activity.getString(tomorrow ? R.string.projects_crew_tomorrow_at : R.string.projects_crew_today_at,
                CrewViews.time(activity, MutePresets.nextMorning(nowSeconds, zone)));
        addPreset(activity, content, dialog, choice, MutePresets.Preset.HOUR, activity.getString(R.string.projects_crew_mute_hour), null);
        addPreset(activity, content, dialog, choice, MutePresets.Preset.EIGHT_HOURS, activity.getString(R.string.projects_crew_mute_eight), null);
        // Between midnight and 08:00 the next morning is today's: "Sabaha kadar".
        addPreset(activity, content, dialog, choice, MutePresets.Preset.MORNING,
                activity.getString(tomorrow ? R.string.projects_crew_mute_morning : R.string.projects_crew_mute_this_morning), morning);
        addPreset(activity, content, dialog, choice, MutePresets.Preset.UNTIL_UNMUTED, activity.getString(R.string.projects_crew_mute_forever), null);
        if (bottom != null) content.addView(bottom);
        dialog.show();
        return dialog;
    }

    private static void addPreset(FragmentActivity activity, LinearLayout content, final AlertDialog dialog, final Choice choice,
                                  final MutePresets.Preset preset, String label, @Nullable String detail) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(52 * dp));
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        row.setPaddingRelative(Math.round(8 * dp), 0, Math.round(8 * dp), 0);
        row.setClickable(true);
        row.setFocusable(true);
        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextSize(16);
        text.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (detail != null) {
            TextView sub = new TextView(activity);
            sub.setText(detail);
            sub.setTextSize(13);
            sub.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
            row.addView(sub);
        }
        Press.attach(row, Press.Style.ROW);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            choice.preset(preset, v);
        });
        content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A quiet line under the presets (the all-crews mute), pressable. */
    static TextView line(Context context, String text, View.OnClickListener listener) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView row = CrewViews.actionRow(context, R.drawable.projects_ic_bell_slash, text, R.color.projects_ash_300, listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(10 * dp);
        row.setLayoutParams(lp);
        return row;
    }
}
