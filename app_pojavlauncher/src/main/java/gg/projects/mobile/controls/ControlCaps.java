package gg.projects.mobile.controls;

import android.graphics.Rect;
import android.text.method.TransformationMethod;
import android.view.View;

/**
 * The Android side of {@link ControlLabels#upper}: the on-screen controls in capitals with
 * {@link java.util.Locale#ROOT}, so a Turkish phone shows "HIDE GUI", not "HİDE GUİ".
 */
public final class ControlCaps implements TransformationMethod {
    /** Shown in capitals with {@link java.util.Locale#ROOT}; the label itself stays as written. */
    public static final ControlCaps INSTANCE = new ControlCaps();

    private ControlCaps() {}

    @Override
    public CharSequence getTransformation(CharSequence source, View view) {
        return source == null ? null : ControlLabels.upper(source.toString());
    }

    @Override
    public void onFocusChanged(View view, CharSequence sourceText, boolean focused, int direction, Rect previouslyFocusedRect) {}
}
