package gg.projects.mobile.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;

/**
 * Two parts of one line set apart without a middle dot (the lead, 29 Oct 2026: no middle dot in any UI text, the same
 * family as semicolons and long dashes): a gap and each part's own tone, as the launcher sets them apart with a flex gap
 * and as SOC9's version line does ("Projects'te 1.21.4"). A list of facts says them with commas instead, and a
 * notification or a toast in a sentence.
 */
public final class Apart {
    /** The view's own colour: the part gets no colour of its own. */
    public static final int OWN = 0;
    /** An en space: about the launcher's 6 px gap between such parts. */
    public static final String GAP = String.valueOf((char) 0x2002);

    private Apart() {}

    /** "head tail": the head in the view's colour, the tail in its own tone ("Katılma istekleri 3", the count quieter). */
    public static CharSequence of(Context context, CharSequence head, CharSequence tail, @ColorRes int tailColor) {
        return of(context, head, OWN, tail, tailColor);
    }

    /** "head tail", each part in its own tone, or the view's ({@link #OWN}). */
    public static CharSequence of(Context context, CharSequence head, @ColorRes int headColor, CharSequence tail,
                                  @ColorRes int tailColor) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        tone(context, out, head, headColor);
        out.append(GAP);
        tone(context, out, tail, tailColor);
        return out;
    }

    /** Appends a part in its own tone ({@link #OWN}: the view's). */
    public static void tone(Context context, SpannableStringBuilder out, CharSequence part, @ColorRes int color) {
        int start = out.length();
        out.append(part);
        if (color != OWN && out.length() > start) {
            out.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, color)), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
