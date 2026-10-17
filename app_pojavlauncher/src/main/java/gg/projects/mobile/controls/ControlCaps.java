package gg.projects.mobile.controls;

import android.content.Context;
import android.graphics.Rect;
import android.text.method.TransformationMethod;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import gg.projects.mobile.content.AtomicFiles;

/**
 * The Android side of {@link ControlLabels}: the capitals of the on-screen controls, and the
 * default layout of an earlier version replaced by the new one while the player never changed it.
 */
public final class ControlCaps implements TransformationMethod {
    /** Shown in capitals with {@link java.util.Locale#ROOT}; the label itself stays as written. */
    public static final ControlCaps INSTANCE = new ControlCaps();

    private static final String TAG = "ControlCaps";
    private static final long MAX_LAYOUT_BYTES = 4L << 20;

    private ControlCaps() {}

    @Override
    public CharSequence getTransformation(CharSequence source, View view) {
        return source == null ? null : ControlLabels.upper(source.toString());
    }

    @Override
    public void onFocusChanged(View view, CharSequence sourceText, boolean focused, int direction, Rect previouslyFocusedRect) {}

    /**
     * Puts the app's default layout ({@code assetName}) over {@code installed} when that file is an
     * earlier version's untouched default ({@link ControlLabels#isOldDefault}). Runs before
     * upstream's own check, which would otherwise keep the old file and add the new one as
     * {@code new_default.json} beside it.
     */
    public static void replaceOldDefault(Context context, String assetName, File installed) {
        try {
            if (!installed.isFile() || !ControlLabels.isOldDefault(AtomicFiles.read(installed, MAX_LAYOUT_BYTES))) return;
            byte[] layout;
            try (InputStream in = context.getAssets().open(assetName)) {
                layout = AtomicFiles.readBounded(in, MAX_LAYOUT_BYTES);
            }
            AtomicFiles.write(installed, layout);
            Log.i(TAG, "the untouched default layout now has the new labels");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "default layout left as it is", e);
        }
    }
}
