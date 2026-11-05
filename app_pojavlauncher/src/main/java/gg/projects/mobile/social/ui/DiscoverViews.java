package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Press;

/** MOB-PARITY "Ekip bul": the quiet row that opens it on "Ekip kur ya da katıl" (the desktop's DiscoverEntry). */
final class DiscoverViews {
    private DiscoverViews() {}

    static View entry(Context context, View.OnClickListener open) {
        float d = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.projects_social_section_background);
        row.setPadding(Math.round(14 * d), Math.round(12 * d), Math.round(12 * d), Math.round(12 * d));
        row.setMinimumHeight(Math.round(56 * d));
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout tile = new FrameLayout(context);
        tile.setBackground(new ChamferDrawable(0x12FFFFFF, 6 * d, null));
        ImageView icon = new ImageView(context);
        Drawable glyph = ContextCompat.getDrawable(context, R.drawable.projects_ic_magnifying_glass);
        if (glyph != null) glyph = glyph.mutate();
        icon.setImageDrawable(glyph);
        icon.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_bone_100));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(icon, new FrameLayout.LayoutParams(Math.round(19 * d), Math.round(19 * d), Gravity.CENTER));
        row.addView(tile, new LinearLayout.LayoutParams(Math.round(36 * d), Math.round(36 * d)));

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.projects_discover_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        try {
            title.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        words.addView(title);
        TextView line = new TextView(context);
        line.setText(R.string.projects_discover_entry_line);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        line.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
        words.addView(line);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsLp.setMarginStart(Math.round(12 * d));
        row.addView(words, wordsLp);

        ImageView caret = new ImageView(context);
        caret.setImageResource(R.drawable.projects_ic_caret_right);
        caret.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_ash_400));
        caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(caret, new LinearLayout.LayoutParams(Math.round(16 * d), Math.round(16 * d)));

        Press.attach(row, Press.Style.CARD);
        row.setOnClickListener(open);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(8 * d);
        lp.bottomMargin = Math.round(4 * d);
        row.setLayoutParams(lp);
        return row;
    }
}
