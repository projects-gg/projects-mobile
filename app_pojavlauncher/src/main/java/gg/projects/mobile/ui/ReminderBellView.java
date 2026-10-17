package gg.projects.mobile.ui;

import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ToggleButton;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * The "Hatırlat" toggle beside an upcoming event (the desktop's {@code ReminderBell}): a quiet bell,
 * gold and filled while a reminder is set. Setting one swings it once, hung from its top
 * (rotation only, 420 ms, dying out from 14°); the fill cross-fades in 200 ms. With animations off
 * it just changes. Turned off mid-swing, it settles from where it is instead of jumping.
 */
public final class ReminderBellView extends FrameLayout {
    private static final long FILL_MS = 200;
    private static final long SWING_MS = 420;
    private static final long SETTLE_MS = 120;
    /** The rows' gold ripple (projects_row_ripple). */
    private static final int RIPPLE = 0x1FF6D58E;

    private final FrameLayout mSwing;
    private final ImageView mOff;
    private final ImageView mOn;
    private boolean mSet;
    private ObjectAnimator mMotion;
    private float mShift;

    public ReminderBellView(Context context) {
        this(context, null);
    }

    public ReminderBellView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        setContentDescription(context.getString(R.string.projects_reminder_bell));
        int icon = Math.round(22 * getResources().getDisplayMetrics().density);
        mSwing = new FrameLayout(context);
        addView(mSwing, new LayoutParams(icon, icon, Gravity.CENTER));
        mOff = icon(context, R.drawable.projects_ic_bell, R.color.projects_ash_300);
        mOn = icon(context, R.drawable.projects_ic_bell_on, R.color.projects_gold_300);
        mOn.setAlpha(0f);
        mSwing.addView(mOff, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mSwing.addView(mOn, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // Hung from its top, like the desktop's bell (origin 50% 14%).
        mSwing.setPivotX(icon / 2f);
        mSwing.setPivotY(icon * 0.14f);
    }

    private static ImageView icon(Context context, int drawable, int color) {
        ImageView view = new ImageView(context);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setImageResource(drawable);
        view.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, color)));
        return view;
    }

    /**
     * Moves the icon {@code px} toward the end, within the same touch area (the home card lines it
     * up with the caret above it); the press ripple follows the icon.
     */
    public void setIconShift(float px) {
        mShift = px;
        mSwing.setTranslationX(px);
        updateRipple(getWidth(), getHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateRipple(w, h);
    }

    /** The cards' quiet gold ripple, in a circle around the icon instead of Android's large borderless one. */
    private void updateRipple(int w, int h) {
        if (w == 0 || h == 0) return;
        float density = getResources().getDisplayMetrics().density;
        int diameter = Math.max(0, Math.min(w, h) - Math.round(10 * density));
        int shift = Math.round(mShift);
        int left = Math.max(0, (w - diameter) / 2 + shift);
        int right = Math.max(0, w - diameter - left);
        int top = (h - diameter) / 2;
        ShapeDrawable circle = new ShapeDrawable(new OvalShape());
        circle.getPaint().setColor(Color.WHITE);
        Drawable mask = new InsetDrawable(circle, left, top, right, h - diameter - top);
        setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask));
    }

    public boolean isSet() {
        return mSet;
    }

    /** Shows whether a reminder is set; {@code animate} for the player's own press. */
    public void setSet(boolean set, boolean animate) {
        boolean changed = set != mSet;
        mSet = set;
        boolean motion = animate && changed && Motion.enabled(getContext()) && isAttachedToWindow();
        mOff.animate().cancel();
        mOn.animate().cancel();
        if (motion) {
            mOff.animate().alpha(set ? 0f : 1f).setDuration(FILL_MS).setInterpolator(Motion.EASE_OUT).start();
            mOn.animate().alpha(set ? 1f : 0f).setDuration(FILL_MS).setInterpolator(Motion.EASE_OUT).start();
            if (set) swing();
            else settle();
        } else {
            if (mMotion != null) mMotion.cancel();
            mSwing.setRotation(0f);
            mOff.setAlpha(set ? 0f : 1f);
            mOn.setAlpha(set ? 1f : 0f);
        }
    }

    /** ±14°, dying out: 0, 14, -10, 6, -3, 0 at 0, 18, 40, 62, 82 and 100%. */
    private void swing() {
        if (mMotion != null) mMotion.cancel();
        float[] times = {0f, 0.18f, 0.4f, 0.62f, 0.82f, 1f};
        float[] angles = {mSwing.getRotation(), 14f, -10f, 6f, -3f, 0f};
        Keyframe[] frames = new Keyframe[times.length];
        for (int i = 0; i < times.length; i++) {
            frames[i] = Keyframe.ofFloat(times[i], angles[i]);
            if (i > 0) frames[i].setInterpolator(new AccelerateDecelerateInterpolator());
        }
        mMotion = ObjectAnimator.ofPropertyValuesHolder(mSwing, PropertyValuesHolder.ofKeyframe(View.ROTATION, frames));
        mMotion.setDuration(SWING_MS);
        mMotion.start();
    }

    private void settle() {
        if (mMotion != null) mMotion.cancel();
        if (mSwing.getRotation() == 0f) return;
        mMotion = ObjectAnimator.ofFloat(mSwing, View.ROTATION, mSwing.getRotation(), 0f);
        mMotion.setDuration(SETTLE_MS);
        mMotion.setInterpolator(Motion.EASE_OUT);
        mMotion.start();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        // A toggle: TalkBack reads "Hatırlat" with its on or off state.
        info.setClassName(ToggleButton.class.getName());
        info.setCheckable(true);
        info.setChecked(mSet);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mMotion != null) mMotion.cancel();
        super.onDetachedFromWindow();
    }
}
