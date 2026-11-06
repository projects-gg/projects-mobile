package gg.projects.mobile.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * M051: a name with something right after it ("RuzgarOkcu YAYINDA"): the row takes its whole width, the name only what
 * it needs, so whatever follows sits beside the name; a long name gives way (its own {@code layout_weight} and
 * ellipsis) and the tag never leaves. A plain horizontal LinearLayout measured as if it were "at most" its width, where
 * extra room goes nowhere and missing room comes off the weighted child.
 */
public final class NameLine extends LinearLayout {
    public NameLine(Context context) {
        this(context, null);
    }

    public NameLine(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int width = MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec);
        // The line keeps its place in the row; its children stay at the start.
        setMeasuredDimension(width, getMeasuredHeight());
    }
}
