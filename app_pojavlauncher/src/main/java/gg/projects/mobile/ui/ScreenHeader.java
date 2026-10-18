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

    /** A line under the title; null hides it. */
    public void setCaption(@Nullable CharSequence caption) {
        mCaption.setText(caption);
        mCaption.setVisibility(caption == null ? View.GONE : View.VISIBLE);
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
