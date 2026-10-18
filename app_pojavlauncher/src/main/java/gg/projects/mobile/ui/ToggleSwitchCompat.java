package gg.projects.mobile.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.appcompat.widget.SwitchCompat;

/**
 * The SwitchCompat every layout inflates (SwitchPreferenceCompat rows, the mod screen; M14): plays
 * the toggle haptic when the player flips it (a tap, a drag, the keyboard or an accessibility
 * action), never when a screen sets its state in code.
 */
public class ToggleSwitchCompat extends SwitchCompat {
    private boolean mUserAction;

    public ToggleSwitchCompat(Context context) {
        this(context, null);
    }

    public ToggleSwitchCompat(Context context, AttributeSet attrs) {
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
