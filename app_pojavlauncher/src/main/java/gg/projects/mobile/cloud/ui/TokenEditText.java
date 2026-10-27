package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import java.util.List;

import gg.projects.mobile.cloud.CloudPlaceholders;

/**
 * MOB-POLISH (the launcher's {@code TokenField}, SHEET-UX decision 22 and the sixth look): a reply field whose
 * placeholders read by name while it holds them as stored ({@code {u}}, {@code {1}}…): each shows as a small chip
 * ("Yazanın adı", "1. parça"), typing included. The caret never rests inside one, a selection takes it whole, and
 * Backspace or Delete beside or inside one removes all of it, so one press never leaves a broken "{u" behind.
 * Copy and paste carry the stored form, which comes back as a chip.
 */
public final class TokenEditText extends AppCompatEditText {
    /** Names a placeholder for its chip: {@code part} 0 is the sender ({@code {u}}), 1–9 a part of the pattern. */
    public interface Names {
        String name(int part);
    }

    @Nullable
    private Names mNames;
    private boolean mFixing;
    /** A deletion that cut into a token: the whole token's range in the text before the change, else null. */
    @Nullable
    private int[] mWiden;

    public TokenEditText(Context context) {
        super(context);
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                mWiden = null;
                if (mFixing || mNames == null || count == 0 || after != 0) return;
                int from = start;
                int to = start + count;
                for (CloudPlaceholders.Token t : CloudPlaceholders.tokens(s.toString())) {
                    int a = t.at;
                    int b = t.at + 3;
                    if (a < to && b > from) {
                        from = Math.min(from, a);
                        to = Math.max(to, b);
                    }
                }
                if (from != start || to != start + count) mWiden = new int[]{from, to, start, count};
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (mFixing) return;
                mFixing = true;
                try {
                    int[] widen = mWiden;
                    mWiden = null;
                    if (widen != null) {
                        // The pieces of the token left on either side of what was deleted go too.
                        int from = widen[0];
                        int length = (widen[1] - widen[0]) - widen[3];
                        if (from >= 0 && from + length <= s.length()) s.delete(from, from + length);
                    }
                    restyle(s);
                } finally {
                    mFixing = false;
                }
            }
        });
    }

    /** Shows placeholders as chips named by {@code names} (null: plain text, as for a secret line). */
    public void setNames(@Nullable Names names) {
        mNames = names;
        Editable text = getText();
        if (text != null) {
            mFixing = true;
            try {
                restyle(text);
            } finally {
                mFixing = false;
            }
        }
    }

    private void restyle(Spannable text) {
        for (Chip chip : text.getSpans(0, text.length(), Chip.class)) text.removeSpan(chip);
        if (mNames == null) return;
        Context c = getContext();
        for (CloudPlaceholders.Token t : CloudPlaceholders.tokens(text.toString())) {
            text.setSpan(new Chip(c, mNames.name(t.part)), t.at, t.at + 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (mFixing || mNames == null || getText() == null) return;
        int from = selStart;
        int to = selEnd;
        List<CloudPlaceholders.Token> tokens = CloudPlaceholders.tokens(getText().toString());
        for (CloudPlaceholders.Token t : tokens) {
            int a = t.at;
            int b = t.at + 3;
            if (from == to) {
                // A caret inside a token goes to its nearer edge.
                if (from > a && from < b) {
                    from = from - a <= b - from ? a : b;
                    to = from;
                }
            } else {
                if (from > a && from < b) from = a;
                if (to > a && to < b) to = b;
            }
        }
        if (from != selStart || to != selEnd) {
            mFixing = true;
            try {
                setSelection(Math.max(0, from), Math.min(getText().length(), to));
            } finally {
                mFixing = false;
            }
        }
    }

    /** A placeholder drawn as its name on a quiet plate (the launcher's: white 14 %, a 3 dp corner). */
    static final class Chip extends ReplacementSpan {
        private final String mLabel;
        private final float mPad;
        private final float mRadius;
        private final int mFill;
        private final int mInk;
        private final RectF mRect = new RectF();

        Chip(Context c, String label) {
            mLabel = label;
            float density = c.getResources().getDisplayMetrics().density;
            mPad = 5 * density;
            mRadius = 3 * density;
            mFill = 0x24FFFFFF;
            mInk = CloudUi.color(c, net.kdt.pojavlaunch.R.color.projects_bone_50);
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(paint.measureText(mLabel) + 2 * mPad);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                         @NonNull Paint paint) {
            Paint.FontMetrics fm = paint.getFontMetrics();
            float width = paint.measureText(mLabel) + 2 * mPad;
            int color = paint.getColor();
            mRect.set(x, y + fm.ascent - mPad * 0.2f, x + width, y + fm.descent + mPad * 0.2f);
            paint.setColor(mFill);
            canvas.drawRoundRect(mRect, mRadius, mRadius, paint);
            paint.setColor(mInk);
            canvas.drawText(mLabel, x + mPad, y, paint);
            paint.setColor(color);
        }
    }
}
