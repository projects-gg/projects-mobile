package gg.projects.mobile.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

/**
 * MOB-POLISH: a section of the Projects card (the desktop's news column blocks, HOME-TASTE): no raised
 * backing of its own, only a hairline along its top edge, so the card holds sections instead of cards
 * inside a card. A section that opens something gets the card's gold ripple over its whole area.
 */
public final class SectionDrawable extends Drawable {
    private final Paint mPaint = new Paint();
    private final int mLine;

    private SectionDrawable(Context context) {
        mPaint.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_700));
        // One device pixel at least, about a dp on the phones we target.
        mLine = Math.max(1, Math.round(context.getResources().getDisplayMetrics().density));
    }

    /** The hairline only (a section that does nothing when tapped). */
    public static Drawable divider(Context context) {
        return new SectionDrawable(context);
    }

    /** The hairline under the card's gold ripple (a section that opens something). */
    public static Drawable pressable(Context context) {
        ColorStateList ripple = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.projects_ripple));
        return new RippleDrawable(ripple, new SectionDrawable(context), new ColorDrawable(0xFFFFFFFF));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        canvas.drawRect(b.left, b.top, b.right, b.top + mLine, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
