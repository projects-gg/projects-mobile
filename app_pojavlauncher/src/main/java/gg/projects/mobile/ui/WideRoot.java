package gg.projects.mobile.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import net.kdt.pojavlaunch.R;

/**
 * TABLET-M: the launcher's root. On a window wider than a column ({@code projects_column_max}, 720 dp) every screen
 * except those that lay a wide window out themselves ({@link FullWidth}: the home, the chats beside a conversation)
 * sits in one centred column, as the mods screen has since 0.1.x: rows, headers and bars keep a phone's lengths
 * instead of spreading over a tablet. The strips beside the column belong to it: a drag along it or a wheel there
 * scrolls the screen (a tablet held in both hands has its thumbs on them); a tap or a sideways drag there does
 * nothing.
 */
public final class WideRoot extends ConstraintLayout {
    private static final int NONE = 0;
    /** Down beside the column, not a drag yet. */
    private static final int PENDING = 1;
    /** A drag beside the column, handed to it. */
    private static final int FORWARDING = 2;
    /** A tap or a sideways drag beside the column: nothing gets it. */
    private static final int IGNORED = 3;

    private final int mColumnMax;
    private final int mSlop;
    @Nullable
    private View mColumn;
    private int mState = NONE;
    @Nullable
    private MotionEvent mDown;
    /** How far the gesture moves sideways to land inside the column (at its nearest edge). */
    private float mShiftX;

    public WideRoot(Context context) {
        this(context, null);
    }

    public WideRoot(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mColumnMax = getResources().getDimensionPixelSize(R.dimen.projects_column_max);
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Nullable
    private View column() {
        if (mColumn == null) mColumn = findViewById(R.id.container_fragment);
        return mColumn;
    }

    /** True: the screen in the container keeps to the centred column; false: it takes the window's width. */
    public void setColumn(boolean column) {
        View container = column();
        if (container == null || !(container.getLayoutParams() instanceof LayoutParams)) return;
        LayoutParams lp = (LayoutParams) container.getLayoutParams();
        int max = column ? mColumnMax : 0;
        if (lp.matchConstraintMaxWidth == max) return;
        lp.matchConstraintMaxWidth = max;
        container.setLayoutParams(lp);
    }

    /** The point lies in a strip beside the column, level with it. */
    private boolean beside(View column, float x, float y) {
        if (column.getVisibility() != VISIBLE || column.getWidth() >= getWidth()) return false;
        if (y < column.getTop() || y >= column.getBottom()) return false;
        return x < column.getLeft() || x >= column.getRight();
    }

    private float shiftInto(View column, float x) {
        float inside = Math.max(column.getLeft() + 1, Math.min(column.getRight() - 1, x));
        return inside - x;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        View column = column();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            if (column != null && beside(column, event.getX(), event.getY())) {
                mDown = MotionEvent.obtain(event);
                mShiftX = shiftInto(column, event.getX());
                mState = PENDING;
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
        if (mState == NONE) return super.dispatchTouchEvent(event);
        boolean end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (column == null) {
            if (end) reset();
            return true;
        }
        if (mState == PENDING && action == MotionEvent.ACTION_MOVE && mDown != null) {
            float dx = event.getX() - mDown.getX();
            float dy = event.getY() - mDown.getY();
            if (dx * dx + dy * dy > (float) mSlop * mSlop) {
                if (Math.abs(dy) >= Math.abs(dx)) {
                    mState = FORWARDING;
                    // The column hears the gesture from its start: the down where it began, then this move.
                    forward(column, mDown);
                } else {
                    mState = IGNORED;
                }
            }
        }
        if (mState == FORWARDING) forward(column, event);
        if (end) reset();
        return true;
    }

    private void forward(View column, MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        copy.offsetLocation(mShiftX + getScrollX() - column.getLeft(), getScrollY() - column.getTop());
        column.dispatchTouchEvent(copy);
        copy.recycle();
    }

    private void reset() {
        if (mDown != null) {
            mDown.recycle();
            mDown = null;
        }
        mState = NONE;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        View column = column();
        if (column != null && event.getActionMasked() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && beside(column, event.getX(), event.getY())) {
            // A wheel or a touchpad's scroll beside the column scrolls what is under the pointer's level in it.
            MotionEvent copy = MotionEvent.obtain(event);
            copy.offsetLocation(shiftInto(column, event.getX()) + getScrollX() - column.getLeft(), getScrollY() - column.getTop());
            boolean handled = column.dispatchGenericMotionEvent(copy);
            copy.recycle();
            return handled;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }
}
