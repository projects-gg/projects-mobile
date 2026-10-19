package gg.projects.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

/**
 * The "hextech cut" of the design guide: a rectangle with diagonal chamfers on the top-left and
 * bottom-right corners. Actions use it; containers use a soft radius instead.
 */
public final class ChamferDrawable extends Drawable {
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke;
    private final float mCut;
    private final Rect mPadding;
    private final Path mPath = new Path();

    public ChamferDrawable(int color, float cutPx, @Nullable Rect padding) {
        this(color, 0, 0f, cutPx, padding);
    }

    public ChamferDrawable(int fillColor, int strokeColor, float strokeWidthPx, float cutPx, @Nullable Rect padding) {
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor(fillColor);
        if (strokeWidthPx > 0) {
            mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(strokeWidthPx);
            mStroke.setColor(strokeColor);
        } else {
            mStroke = null;
        }
        mCut = cutPx;
        mPadding = padding;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        float inset = mStroke == null ? 0 : mStroke.getStrokeWidth() / 2f;
        float l = bounds.left + inset, t = bounds.top + inset, r = bounds.right - inset, b = bounds.bottom - inset;
        float cut = Math.min(mCut, Math.min(r - l, b - t) / 2f);
        mPath.reset();
        mPath.moveTo(l + cut, t);
        mPath.lineTo(r, t);
        mPath.lineTo(r, b - cut);
        mPath.lineTo(r - cut, b);
        mPath.lineTo(l, b);
        mPath.lineTo(l, t + cut);
        mPath.close();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if ((mFill.getColor() >>> 24) > 0) canvas.drawPath(mPath, mFill);
        if (mStroke != null) canvas.drawPath(mPath, mStroke);
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        if (mPadding == null) return super.getPadding(padding);
        padding.set(mPadding);
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        mFill.setAlpha(alpha);
        if (mStroke != null) mStroke.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mFill.setColorFilter(colorFilter);
        if (mStroke != null) mStroke.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static Rect padding(Context context, int horizontalDp, int verticalDp) {
        float d = context.getResources().getDisplayMetrics().density;
        int h = Math.round(horizontalDp * d), v = Math.round(verticalDp * d);
        return new Rect(h, v, h, v);
    }

    private static float cut(Context context) {
        return 9 * context.getResources().getDisplayMetrics().density;
    }

    /** Gold primary action: gold-500, pressed gold-600, disabled obsidian-700. */
    public static Drawable primary(Context context) {
        Rect padding = padding(context, 16, 8);
        float cut = cut(context);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                new ChamferDrawable(ContextCompat.getColor(context, R.color.projects_obsidian_700), cut, padding));
        states.addState(new int[]{android.R.attr.state_pressed},
                new ChamferDrawable(ContextCompat.getColor(context, R.color.projects_gold_600), cut, padding));
        states.addState(new int[]{android.R.attr.state_focused},
                new ChamferDrawable(ContextCompat.getColor(context, R.color.projects_gold_500),
                        ContextCompat.getColor(context, R.color.projects_gold_300), 2 * context.getResources().getDisplayMetrics().density, cut, padding));
        states.addState(new int[]{},
                new ChamferDrawable(ContextCompat.getColor(context, R.color.projects_gold_500), cut, padding));
        return states;
    }

    /**
     * M17: a secondary action inside a raised block (the key fix's button, the event's "Katıl"),
     * where obsidian-700 would vanish into the block: a bone hairline at 28 %, a faint bone fill while
     * pressed, a dimmer hairline while disabled.
     */
    public static Drawable outlined(Context context) {
        Rect padding = padding(context, 14, 8);
        float cut = cut(context);
        float stroke = context.getResources().getDisplayMetrics().density;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, new ChamferDrawable(0, 0x1FF3EBDD, stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(0x1AF3EBDD, 0x66F3EBDD, stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_focused},
                new ChamferDrawable(0, ContextCompat.getColor(context, R.color.projects_gold_300), 2 * stroke, cut, padding));
        states.addState(new int[]{}, new ChamferDrawable(0, 0x47F3EBDD, stroke, cut, padding));
        return states;
    }

    /** Quiet secondary action: transparent with an obsidian-700 outline. */
    public static Drawable secondary(Context context) {
        Rect padding = padding(context, 14, 8);
        float cut = cut(context);
        float stroke = context.getResources().getDisplayMetrics().density;
        int border = ContextCompat.getColor(context, R.color.projects_obsidian_700);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                new ChamferDrawable(border, border, stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_focused},
                new ChamferDrawable(0, ContextCompat.getColor(context, R.color.projects_gold_300), 2 * stroke, cut, padding));
        states.addState(new int[]{}, new ChamferDrawable(0, border, stroke, cut, padding));
        return states;
    }
}
