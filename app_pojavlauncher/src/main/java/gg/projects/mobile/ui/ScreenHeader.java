package gg.projects.mobile.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

/**
 * The header of a sub-screen (M14): a back button and the screen's title ({@code android:text}),
 * the same on every screen the player reaches from the main menu. Back goes through the
 * activity's own back handling, like the system's back.
 */
public final class ScreenHeader extends LinearLayout {
    private final TextView mTitle;
    private final TextView mCaption;

    public ScreenHeader(Context context) {
        this(context, null);
    }

    public ScreenHeader(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.projects_screen_header, this, true);
        mTitle = findViewById(R.id.projects_header_title);
        mCaption = findViewById(R.id.projects_header_caption);
        TypedArray a = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.text});
        mTitle.setText(a.getText(0));
        a.recycle();
        findViewById(R.id.projects_header_back).setOnClickListener(v -> {
            Activity activity = activityOf(getContext());
            if (activity != null) activity.onBackPressed();
        });
    }

    public void setTitle(CharSequence title) {
        mTitle.setText(title);
    }

    /** M20: the title as it reads now (a sheet opened from the screen names it). */
    public String getTitleText() {
        return mTitle.getText().toString();
    }

    /**
     * MOB1 (TEAM6): a view just before the title (a direct conversation's crew tag), once; null takes it away.
     */
    public void setTitleLead(@Nullable View lead) {
        LinearLayout row = findViewById(R.id.projects_header_row);
        Object before = row.getTag(R.id.projects_header_title);
        if (before instanceof View) row.removeView((View) before);
        row.setTag(R.id.projects_header_title, lead);
        if (lead == null) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(4 * getResources().getDisplayMetrics().density));
        lp.setMarginEnd(Math.round(6 * getResources().getDisplayMetrics().density));
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        row.addView(lead, row.indexOfChild(mTitle), lp);
    }

    /** A line under the title; null hides it. */
    public void setCaption(@Nullable CharSequence caption) {
        mCaption.setText(caption);
        mCaption.setVisibility(caption == null ? View.GONE : View.VISIBLE);
    }

    /**
     * M19: an icon action at the header's end (the friends screen's "Arkadaş ekle" and settings),
     * a 48 dp target with the icon buttons' ripple and press.
     */
    public android.widget.ImageButton addAction(@androidx.annotation.DrawableRes int icon,
                                                @androidx.annotation.StringRes int description, View.OnClickListener listener) {
        LinearLayout row = findViewById(R.id.projects_header_row);
        android.widget.ImageButton button = new android.widget.ImageButton(getContext());
        int size = Math.round(48 * getResources().getDisplayMetrics().density);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        button.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        button.setImageResource(icon);
        button.setImageTintList(androidx.core.content.ContextCompat.getColorStateList(getContext(), R.color.projects_bone_100));
        button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        button.setPadding(pad, pad, pad, pad);
        button.setContentDescription(getContext().getString(description));
        button.setOnClickListener(listener);
        Press.attach(button, Press.Style.ICON);
        if (row.getPaddingEnd() > 0) row.setPaddingRelative(row.getPaddingStart(), row.getPaddingTop(), Math.round(4 * getResources().getDisplayMetrics().density), row.getPaddingBottom());
        row.addView(button, new LinearLayout.LayoutParams(size, size));
        return button;
    }

    @Nullable
    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
