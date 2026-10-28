package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * The order card's ready answers: chamfered quiet chips (the two-shape rule: an action is chamfered, never a pill) that
 * wrap to the next line when they do not fit (big text, 360 dp), 48 dp targets.
 */
final class PlusChips extends ViewGroup {
    private final int mGap;

    PlusChips(Context c) {
        super(c);
        mGap = CloudUi.dp(c, 8);
        setLayoutParams(CloudUi.matchWrap());
    }

    /** A chip that does {@code click} (a tick, then the answer). */
    Button add(CharSequence label, View.OnClickListener click) {
        Context c = getContext();
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(CloudUi.font(c, R.font.figtree_semibold));
        b.setTextColor(CloudUi.color(c, R.color.projects_bone_100));
        b.setMinHeight(CloudUi.dp(c, 44));
        b.setMinimumHeight(CloudUi.dp(c, 44));
        b.setMinWidth(CloudUi.dp(c, 48));
        b.setMinimumWidth(CloudUi.dp(c, 48));
        b.setGravity(android.view.Gravity.CENTER);
        b.setBackground(ChamferDrawable.toggle(c));
        // After the background, and as start/end: the button style's own start/end padding left the words on the edges.
        b.setPaddingRelative(CloudUi.dp(c, 14), CloudUi.dp(c, 6), CloudUi.dp(c, 14), CloudUi.dp(c, 6));
        Press.attach(b, Press.Style.BUTTON);
        b.setOnClickListener(v -> {
            Haptics.tick(v);
            click.onClick(v);
        });
        addView(b, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return b;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int max = width - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = 0;
        int line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = child.getMeasuredWidth();
            if (x > 0 && x + w > max) {
                x = 0;
                y += line + mGap;
                line = 0;
            }
            x += w + mGap;
            line = Math.max(line, child.getMeasuredHeight());
        }
        setMeasuredDimension(width, y + line + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int max = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = 0;
        int line = 0;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + w > max) {
                x = 0;
                y += line + mGap;
                line = 0;
            }
            int left = rtl ? r - l - getPaddingRight() - x - w : getPaddingLeft() + x;
            child.layout(left, getPaddingTop() + y, left + w, getPaddingTop() + y + h);
            x += w + mGap;
            line = Math.max(line, h);
        }
    }
}
