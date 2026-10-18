package gg.projects.mobile.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatSpinner;

/** Every inflated {@code <Spinner>} (M14): a form field, so the quieter card press. */
public class PressSpinner extends AppCompatSpinner {
    public PressSpinner(Context context) {
        this(context, null);
    }

    public PressSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
        Press.attach(this, Press.Style.CARD);
    }
}
