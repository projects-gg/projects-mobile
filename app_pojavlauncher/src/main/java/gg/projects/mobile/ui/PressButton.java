package gg.projects.mobile.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatButton;

/** Every inflated {@code <Button>} (M14): press feedback and haptics ({@link Press}). */
public class PressButton extends AppCompatButton {
    public PressButton(Context context) {
        this(context, null);
    }

    public PressButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        Press.attach(this, Press.Style.BUTTON);
    }
}
