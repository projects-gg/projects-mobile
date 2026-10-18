package gg.projects.mobile.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageButton;

/** Every inflated {@code <ImageButton>} (M14): the icon press of {@link Press}. */
public class PressImageButton extends AppCompatImageButton {
    public PressImageButton(Context context) {
        this(context, null);
    }

    public PressImageButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        Press.attach(this, Press.Style.ICON);
    }
}
