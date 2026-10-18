package gg.projects.mobile.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatCheckBox;

/**
 * The check box every layout inflates (M14): plays the toggle haptic when the player flips it (a
 * tap, a drag, the keyboard or an accessibility action), never when a screen sets its state in
 * code.
 */
public class ToggleCheckBox extends AppCompatCheckBox {
    private boolean mUserAction;

    public ToggleCheckBox(Context context) {
        this(context, null);
    }

    public ToggleCheckBox(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean outer = mUserAction;
        mUserAction = true;
        try {
            return super.onTouchEvent(event);
        } finally {
            mUserAction = outer;
        }
    }

    @Override
    public boolean performClick() {
        boolean outer = mUserAction;
        mUserAction = true;
        try {
            return super.performClick();
        } finally {
            mUserAction = outer;
        }
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        if (changed && mUserAction) Haptics.toggle(this, checked);
    }
}
