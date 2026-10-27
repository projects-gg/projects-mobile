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

    /**
     * MOB1b: the crew card's tag toggle (TEAM6), a 36 dp chip inside its 48 dp target (6 dp in at the top and the
     * bottom): a bone hairline ("Etiketi tak"), faint gold when worn (activated: "Etiket takılı"), a faint fill and
     * no line while the crew is hidden or its texts are locked (selected). Chamfered like the desktop's WearToggle
     * (it was the one rounded action on the crew card).
     */
    public static Drawable toggle(Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        float cut = 7 * d;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_activated}, new ChamferDrawable(0x1FF6D58E, 0x5CF6D58E, d, cut, null));
        states.addState(new int[]{android.R.attr.state_selected}, new ChamferDrawable(0x0AF3EBDD, 0, 0f, cut, null));
        states.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(0x1AF3EBDD, 0x66F3EBDD, d, cut, null));
        states.addState(new int[]{android.R.attr.state_focused},
                new ChamferDrawable(0, ContextCompat.getColor(context, R.color.projects_gold_300), 2 * d, cut, null));
        states.addState(new int[]{}, new ChamferDrawable(0, 0x33F3EBDD, d, cut, null));
        int inset = Math.round(6 * d);
        return new android.graphics.drawable.InsetDrawable(states, 0, inset, 0, inset);
    }

    /**
     * FEAT-M: a key of the movement pad (design.md §6 "Cloud movement"): {@link #outlined} while up, and while it is
     * activated (a direction held, sneaking on) faint gold with a gold-300 edge, the key that stays pressed in.
     */
    public static Drawable key(Context context) {
        Rect padding = padding(context, 8, 6);
        float cut = cut(context);
        float stroke = context.getResources().getDisplayMetrics().density;
        int gold = ContextCompat.getColor(context, R.color.projects_gold_300);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, new ChamferDrawable(0, 0x1FF3EBDD, stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_activated, android.R.attr.state_focused},
                new ChamferDrawable(0x24F6D58E, gold, 2 * stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_activated}, new ChamferDrawable(0x24F6D58E, gold, 1.5f * stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(0x1AF3EBDD, 0x66F3EBDD, stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_focused}, new ChamferDrawable(0, gold, 2 * stroke, cut, padding));
        states.addState(new int[]{}, new ChamferDrawable(0, 0x47F3EBDD, stroke, cut, padding));
        return states;
    }

    /**
     * FEAT-M: a walk button of the movement panel that the machine just refused: {@link #key}'s shape with a flare-400
     * edge (70 %, the launcher's) while the line above says why.
     */
    public static Drawable keyRefused(Context context) {
        Rect padding = padding(context, 8, 6);
        float cut = cut(context);
        float stroke = context.getResources().getDisplayMetrics().density;
        int flare = (ContextCompat.getColor(context, R.color.projects_flare_400) & 0x00FFFFFF) | 0xB3000000;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, new ChamferDrawable(0, 0x1FF3EBDD, stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(0x1AF3EBDD, flare, 1.5f * stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_focused},
                new ChamferDrawable(0, ContextCompat.getColor(context, R.color.projects_gold_300), 2 * stroke, cut, padding));
        states.addState(new int[]{}, new ChamferDrawable(0, flare, 1.5f * stroke, cut, padding));
        return states;
    }

    /**
     * QA-TASTE: the launcher's danger button (packages/ui Button "danger"): flare-400 at 14 % over obsidian-900 with a
     * flare-400 hairline at 45 %, 22 % while pressed; obsidian-700 while disabled. For what is hard to take back.
     */
    public static Drawable danger(Context context) {
        Rect padding = padding(context, 16, 8);
        float cut = cut(context);
        float stroke = context.getResources().getDisplayMetrics().density;
        int base = ContextCompat.getColor(context, R.color.projects_obsidian_900);
        int flare = ContextCompat.getColor(context, R.color.projects_flare_400);
        int ring = androidx.core.graphics.ColorUtils.setAlphaComponent(flare, 0x73);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                new ChamferDrawable(ContextCompat.getColor(context, R.color.projects_obsidian_700), cut, padding));
        states.addState(new int[]{android.R.attr.state_pressed},
                new ChamferDrawable(androidx.core.graphics.ColorUtils.blendARGB(base, flare, 0.22f), ring, stroke, cut, padding));
        states.addState(new int[]{android.R.attr.state_focused},
                new ChamferDrawable(androidx.core.graphics.ColorUtils.blendARGB(base, flare, 0.14f),
                        ContextCompat.getColor(context, R.color.projects_gold_300), 2 * stroke, cut, padding));
        states.addState(new int[]{}, new ChamferDrawable(androidx.core.graphics.ColorUtils.blendARGB(base, flare, 0.14f), ring, stroke, cut, padding));
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
