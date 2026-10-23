package gg.projects.mobile.social.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * MOB1 (CHAT2 §4): the three dots of "yazıyor": an opacity wave (1.2 s) that runs only while the dots
 * are on screen and someone writes (at most 6 s after their last frame; the pill hides them then);
 * with animations off they stand still. Nothing runs while the view is hidden or detached.
 */
public final class TypingDots extends View {
    private static final long CYCLE_MS = 1200;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable
    private ValueAnimator mWave;
    private float mPhase;

    public TypingDots(Context context) {
        this(context, null);
    }

    public TypingDots(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint.setColor(ContextCompat.getColor(context, R.color.projects_ash_300));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = Math.min(getHeight() / 2f, getWidth() / 9f);
        float step = getWidth() / 3f;
        boolean moving = mWave != null;
        for (int i = 0; i < 3; i++) {
            float alpha = 1f;
            if (moving) {
                // Each dot peaks a third of a cycle after the one before it.
                float t = (mPhase - i / 3f + 1f) % 1f;
                alpha = 0.35f + 0.65f * (float) Math.max(0, Math.sin(Math.PI * Math.min(1f, t * 1.6f)));
            }
            mPaint.setAlpha(Math.round(255 * alpha));
            canvas.drawCircle(step * i + step / 2f, getHeight() / 2f, r, mPaint);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        update();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        update();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void update() {
        if (isShown() && isAttachedToWindow() && getWindowVisibility() == VISIBLE && Motion.enabled(getContext())) start();
        else stop();
    }

    private void start() {
        if (mWave != null) return;
        ValueAnimator wave = ValueAnimator.ofFloat(0f, 1f);
        wave.setDuration(CYCLE_MS);
        wave.setRepeatCount(ValueAnimator.INFINITE);
        wave.setInterpolator(new LinearInterpolator());
        wave.addUpdateListener(a -> {
            mPhase = (float) a.getAnimatedValue();
            invalidate();
        });
        mWave = wave;
        wave.start();
    }

    private void stop() {
        if (mWave == null) return;
        mWave.cancel();
        mWave = null;
        invalidate();
    }
}
