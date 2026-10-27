package gg.projects.mobile.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/**
 * A settings row that only informs ("Projects Launcher hakkında"): not selectable, yet its title in the
 * titles' colour. AndroidX paints a non-selectable row's title in the summary's grey, and the About row
 * read as switched off (MOB-POLISH).
 */
public class InfoPreference extends Preference {
    public InfoPreference(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View title = holder.findViewById(android.R.id.title);
        if (!(title instanceof TextView)) return;
        TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            ColorStateList primary = a.getColorStateList(0);
            if (primary != null) ((TextView) title).setTextColor(primary);
        } finally {
            a.recycle();
        }
    }
}
