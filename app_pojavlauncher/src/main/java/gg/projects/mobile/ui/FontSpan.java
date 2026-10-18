package gg.projects.mobile.ui;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

import androidx.annotation.NonNull;

/** A run of text in a bundled font (TypefaceSpan(Typeface) needs Android 9). */
public final class FontSpan extends MetricAffectingSpan {
    private final Typeface mTypeface;

    public FontSpan(@NonNull Typeface typeface) {
        mTypeface = typeface;
    }

    @Override
    public void updateDrawState(TextPaint paint) {
        paint.setTypeface(mTypeface);
    }

    @Override
    public void updateMeasureState(@NonNull TextPaint paint) {
        paint.setTypeface(mTypeface);
    }
}
