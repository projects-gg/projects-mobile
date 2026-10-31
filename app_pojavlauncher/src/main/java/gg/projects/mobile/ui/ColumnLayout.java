package gg.projects.mobile.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * TABLET-M: a LinearLayout at most {@code android:maxWidth} wide, centred by its parent (its
 * {@code layout_gravity}): the home's column and its play bar on a tablet keep a phone's line lengths instead of
 * spreading over the window. Narrower than its maximum it behaves as a plain LinearLayout.
 */
public class ColumnLayout extends LinearLayout {
    private static final int[] ATTRS = {android.R.attr.maxWidth};
    private int mMaxWidth;

    public ColumnLayout(Context context) {
        this(context, null);
    }

    public ColumnLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, ATTRS);
        mMaxWidth = a.getDimensionPixelSize(0, 0);
        a.recycle();
    }

    public void setMaxWidth(int px) {
        if (mMaxWidth == px) return;
        mMaxWidth = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = MeasureSpec.getSize(widthMeasureSpec);
        if (mMaxWidth > 0 && size > mMaxWidth) {
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(mMaxWidth, mode == MeasureSpec.UNSPECIFIED ? MeasureSpec.AT_MOST : mode);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
