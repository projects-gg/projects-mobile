package gg.projects.mobile.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * The line under the top bar (M14). Usually a hairline; while a Microsoft account signs in
 * the background, a gold bar grows along it with each step and fades back into the hairline when
 * the sign-in is done (upstream drew a 4 dp orange line all the time: the finished state of that
 * progress bar). A failed sign-in turns it flare for a moment; the error dialog says what happened.
 */
public final class TopBarLine extends View {
    private final Paint mHairline = new Paint();
    private final Paint mBar = new Paint();
    private final int mGold;
    private final int mFlare;
    private final float mBarHeight;

    private float mProgress;
    private float mBarAlpha;
    private ValueAnimator mProgressAnimator;
    private ValueAnimator mFadeAnimator;

    public TopBarLine(Context context) {
        this(context, null);
    }

    public TopBarLine(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mHairline.setColor(ContextCompat.getColor(context, R.color.projects_hairline));
        mGold = ContextCompat.getColor(context, R.color.projects_gold_500);
        mFlare = ContextCompat.getColor(context, R.color.projects_flare_400);
        mBar.setColor(mGold);
        mBarHeight = 2 * getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** A sign-in moved to {@code step} of {@code steps}. */
    public void showProgress(int step, int steps) {
        mBar.setColor(mGold);
        cancelFade();
        mBarAlpha = 1f;
        float target = steps <= 0 ? 1f : Math.max(0f, Math.min(1f, step / (float) steps));
        animateProgressTo(target);
        if (target >= 1f) fadeOut(500);
    }

    /** The sign-in finished: the bar fills and leaves. */
    public void finish() {
        if (mBarAlpha <= 0f) return;
        animateProgressTo(1f);
        fadeOut(450);
    }

    /** The sign-in failed: flare for a moment, then the hairline again. */
    public void fail() {
        cancelFade();
        if (mProgressAnimator != null) mProgressAnimator.cancel();
        mBar.setColor(mFlare);
        mProgress = 1f;
        mBarAlpha = 1f;
        invalidate();
        fadeOut(2400);
    }

    private void animateProgressTo(float target) {
        if (mProgressAnimator != null) mProgressAnimator.cancel();
        if (!Motion.enabled(getContext()) || !isAttachedToWindow()) {
            mProgress = target;
            invalidate();
            return;
        }
        mProgressAnimator = ValueAnimator.ofFloat(mProgress, target);
        mProgressAnimator.setDuration(320);
        mProgressAnimator.setInterpolator(Motion.EASE_OUT);
        mProgressAnimator.addUpdateListener(a -> {
            mProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        mProgressAnimator.start();
    }

    private void fadeOut(long delay) {
        cancelFade();
        mFadeAnimator = ValueAnimator.ofFloat(1f, 0f);
        mFadeAnimator.setStartDelay(delay);
        mFadeAnimator.setDuration(Motion.EXIT_MS + 60);
        mFadeAnimator.addUpdateListener(a -> {
            mBarAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        mFadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBarAlpha = 0f;
                mProgress = 0f;
                invalidate();
            }
        });
        mFadeAnimator.start();
    }

    private void cancelFade() {
        if (mFadeAnimator != null) {
            mFadeAnimator.removeAllListeners();
            mFadeAnimator.cancel();
            mFadeAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelFade();
        if (mProgressAnimator != null) mProgressAnimator.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float bottom = getHeight();
        float hairline = Math.max(1f, getResources().getDisplayMetrics().density);
        canvas.drawRect(0, bottom - hairline, getWidth(), bottom, mHairline);
        if (mBarAlpha <= 0f || mProgress <= 0f) return;
        int base = mBar.getColor();
        mBar.setAlpha(Math.round(255 * mBarAlpha));
        float right = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() : getWidth() * mProgress;
        float left = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() * (1 - mProgress) : 0;
        canvas.drawRect(left, bottom - mBarHeight, right, bottom, mBar);
        mBar.setColor(base);
    }
}
