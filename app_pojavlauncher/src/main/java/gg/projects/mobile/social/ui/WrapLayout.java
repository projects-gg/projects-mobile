package gg.projects.mobile.social.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

/**
 * MOB1: children in rows that wrap (a message's reaction chips): left to right (right to left in an
 * RTL layout), {@link #gap} between them and between rows, as wide as the widest row.
 */
public final class WrapLayout extends ViewGroup {
    private int mGap;

    public WrapLayout(Context context) {
        this(context, null);
    }

    public WrapLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mGap = Math.round(4 * context.getResources().getDisplayMetrics().density);
    }

    public void setGap(int px) {
        mGap = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int max = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        int widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, MeasureSpec.makeMeasureSpec(Math.max(0, max), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + w > max) {
                y += rowHeight + mGap;
                x = 0;
                rowHeight = 0;
            }
            x += w + mGap;
            rowHeight = Math.max(rowHeight, h);
            widest = Math.max(widest, x - mGap);
        }
        int width = widest + getPaddingLeft() + getPaddingRight();
        int height = y + rowHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int max = width - getPaddingLeft() - getPaddingRight();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int x = 0;
        int y = getPaddingTop();
        int rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + w > max) {
                y += rowHeight + mGap;
                x = 0;
                rowHeight = 0;
            }
            int left = rtl ? width - getPaddingRight() - x - w : getPaddingLeft() + x;
            child.layout(left, y, left + w, y + h);
            x += w + mGap;
            rowHeight = Math.max(rowHeight, h);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
