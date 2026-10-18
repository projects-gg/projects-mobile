package gg.projects.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * The gold play actions (M14; the desktop's PlayButton, design.md's "one loud thing"): a hextech
 * cut face with the gold gradient (gold-200 → 400 → 500 → 600), an inner bevel (a light top edge,
 * an ember foot), optionally seated in a darker socket plate, darker while pressed. A sheen crosses
 * it once when the action is accepted ({@link #sheen}); nothing moves on its own.
 */
public final class PlayButtonDrawable extends Drawable {
    private final Paint mSocketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFocusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mSocket = new Path();
    private final Path mFace = new Path();
    private final RectF mFaceRect = new RectF();
    private final Matrix mSheenMatrix = new Matrix();
    private final Rect mPadding;

    private final boolean mWithSocket;
    private final float mSocketInset;
    private final float mCut;
    private final float mDensity;
    private final int mGold200, mGold400, mGold500, mGold600, mDisabled, mInk;

    private boolean mPressed;
    private boolean mEnabled = true;
    private boolean mFocused;
    private float mSheen = -1f;
    private ValueAnimator mSheenAnimator;
    private LinearGradient mFaceUp, mFaceDown;
    private int mAlpha = 255;

    public PlayButtonDrawable(Context context, boolean withSocket) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mWithSocket = withSocket;
        mSocketInset = withSocket ? 3 * mDensity : 0f;
        mCut = (withSocket ? 11 : 9) * mDensity;
        mGold200 = ContextCompat.getColor(context, R.color.projects_gold_200);
        mGold400 = ContextCompat.getColor(context, R.color.projects_gold_400);
        mGold500 = ContextCompat.getColor(context, R.color.projects_gold_500);
        mGold600 = ContextCompat.getColor(context, R.color.projects_gold_600);
        mDisabled = ContextCompat.getColor(context, R.color.projects_obsidian_700);
        mInk = ContextCompat.getColor(context, R.color.projects_ember_900);
        mFocusPaint.setStyle(Paint.Style.STROKE);
        mFocusPaint.setStrokeWidth(2 * mDensity);
        mFocusPaint.setColor(ContextCompat.getColor(context, R.color.projects_gold_200));
        int h = Math.round(16 * mDensity + mSocketInset), v = Math.round(8 * mDensity + mSocketInset);
        mPadding = new Rect(h, v, h, v);
    }

    /** One crossing of the sheen (about 0.65 s); none with animations off. */
    public void sheen(Context context) {
        if (!Motion.enabled(context)) return;
        if (mSheenAnimator != null) mSheenAnimator.cancel();
        mSheenAnimator = ValueAnimator.ofFloat(0f, 1f);
        mSheenAnimator.setDuration(650);
        mSheenAnimator.setInterpolator(new android.view.animation.PathInterpolator(0.45f, 0f, 0.25f, 1f));
        mSheenAnimator.addUpdateListener(a -> {
            mSheen = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        mSheenAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mSheen = -1f;
                invalidateSelf();
            }
        });
        mSheenAnimator.start();
    }

    private static void chamfer(Path path, float l, float t, float r, float b, float cut) {
        cut = Math.min(cut, Math.min(r - l, b - t) / 2f);
        path.reset();
        path.moveTo(l + cut, t);
        path.lineTo(r, t);
        path.lineTo(r, b - cut);
        path.lineTo(r - cut, b);
        path.lineTo(l, b);
        path.lineTo(l, t + cut);
        path.close();
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        chamfer(mSocket, bounds.left, bounds.top, bounds.right, bounds.bottom, mCut + mSocketInset * 0.6f);
        float l = bounds.left + mSocketInset, t = bounds.top + mSocketInset;
        float r = bounds.right - mSocketInset, b = bounds.bottom - mSocketInset;
        mFaceRect.set(l, t, r, b);
        chamfer(mFace, l, t, r, b, mCut);
        mSocketPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                0xE63F0A0A, 0xF20A090C, Shader.TileMode.CLAMP));
        mFaceUp = new LinearGradient(0, t, 0, b, new int[]{mGold200, mGold400, mGold500, mGold600},
                new float[]{0f, 0.38f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        mFaceDown = new LinearGradient(0, t, 0, b, new int[]{mGold400, mGold500, mGold600, mGold600},
                new float[]{0f, 0.35f, 0.8f, 1f}, Shader.TileMode.CLAMP);
        float band = (r - l) / 3f;
        mSheenPaint.setShader(new LinearGradient(0, 0, band, 0,
                new int[]{0x00FFFFFF, 0x8CFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        if (mWithSocket) {
            mSocketPaint.setAlpha(mAlpha);
            canvas.drawPath(mSocket, mSocketPaint);
        }
        if (!mEnabled) {
            mFacePaint.setShader(null);
            mFacePaint.setColor(mDisabled);
            mFacePaint.setAlpha(mAlpha);
            canvas.drawPath(mFace, mFacePaint);
            return;
        }
        mFacePaint.setColor(0xFFFFFFFF);
        mFacePaint.setShader(mPressed ? mFaceDown : mFaceUp);
        mFacePaint.setAlpha(mAlpha);
        canvas.drawPath(mFace, mFacePaint);

        int save = canvas.save();
        canvas.clipPath(mFace);
        // Inner bevel: a light top edge and an ember foot (the desktop's inset shadows).
        mEdgePaint.setColor(mPressed ? 0x40FFFFFF : 0x8CFFFFFF);
        mEdgePaint.setAlpha(Math.round(mEdgePaint.getAlpha() * (mAlpha / 255f)));
        canvas.drawRect(mFaceRect.left, mFaceRect.top, mFaceRect.right, mFaceRect.top + mDensity, mEdgePaint);
        mEdgePaint.setColor(mInk);
        mEdgePaint.setAlpha(Math.round(0x59 * (mAlpha / 255f)));
        canvas.drawRect(mFaceRect.left, mFaceRect.bottom - 2 * mDensity, mFaceRect.right, mFaceRect.bottom, mEdgePaint);
        if (mSheen >= 0f) {
            float width = mFaceRect.width();
            float band = width / 3f;
            float x = mFaceRect.left - band * 1.4f + (width + band * 2.8f) * mSheen;
            mSheenMatrix.reset();
            mSheenMatrix.setTranslate(x, 0);
            mSheenMatrix.postSkew(-0.32f, 0f, x, mFaceRect.centerY());
            mSheenPaint.getShader().setLocalMatrix(mSheenMatrix);
            mSheenPaint.setAlpha(mAlpha);
            canvas.drawRect(mFaceRect, mSheenPaint);
        }
        canvas.restoreToCount(save);

        if (mFocused) canvas.drawPath(mFace, mFocusPaint);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(@NonNull int[] state) {
        boolean pressed = false, enabled = false, focused = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) pressed = true;
            else if (s == android.R.attr.state_enabled) enabled = true;
            else if (s == android.R.attr.state_focused) focused = true;
        }
        boolean changed = pressed != mPressed || enabled != mEnabled || focused != mFocused;
        mPressed = pressed;
        mEnabled = enabled;
        mFocused = focused;
        if (changed) invalidateSelf();
        return changed;
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        padding.set(mPadding);
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mFacePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
