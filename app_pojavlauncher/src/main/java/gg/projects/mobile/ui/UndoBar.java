package gg.projects.mobile.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * M17: a short confirmation with "Geri al" right above the play bar (the desktop's toast with an
 * undo; Android's own toasts cannot carry a button). It rises 10 dp into place on the entrance
 * curve while it fades in (240 ms) and leaves faster (160 ms). It stays 6 s, longer than a plain
 * message because it holds a decision, and waits while a finger is on it. After "Geri al" its line
 * changes in place to what happened and it leaves soon after. A success shows as the jade mark; the
 * answer to "Geri al" as a quiet ash mark in the same place, so the line never moves. The sentence
 * stays bone. The surface is opaque and a step lighter than the card under it: it floats, and
 * nothing shows through it. TalkBack reads it as it appears. With animations off it just appears
 * and goes.
 */
@SuppressLint("ViewConstructor")
public final class UndoBar extends LinearLayout {
    private static final long STAY_MS = 6000;
    private static final long RESULT_STAY_MS = 1800;
    private static final long ENTER_MS = 240;
    private static final long EXIT_MS = 160;

    private final ImageView mMark;
    private final TextView mText;
    private final TextView mAction;
    private final Runnable mLeave = this::dismiss;
    private boolean mShowing;

    private UndoBar(Context context) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(Math.round(52 * dp));
        setPaddingRelative(Math.round(14 * dp), Math.round(2 * dp), Math.round(4 * dp), Math.round(2 * dp));
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(10 * dp);
        surface.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
        surface.setStroke(Math.max(1, Math.round(dp)), 0x29F3EBDD); // bone hairline
        setBackground(surface);
        setElevation(6 * dp);
        setClickable(true); // touches on the bar stay on it
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);

        mMark = new ImageView(context);
        setMark(R.drawable.projects_ic_check_circle, R.color.projects_jade_400);
        mMark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(mMark, new LayoutParams(Math.round(20 * dp), Math.round(20 * dp)));

        mText = new TextView(context);
        mText.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        mText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mText.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_regular));
        mText.setMaxLines(2);
        LayoutParams text = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        text.setMarginStart(Math.round(10 * dp));
        text.setMarginEnd(Math.round(4 * dp));
        addView(mText, text);

        mAction = new TextView(context);
        mAction.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
        mAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mAction.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        mAction.setGravity(Gravity.CENTER);
        mAction.setMinWidth(Math.round(64 * dp));
        mAction.setMinHeight(Math.round(48 * dp));
        mAction.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
        mAction.setBackgroundResource(R.drawable.projects_row_ripple);
        mAction.setClickable(true);
        mAction.setFocusable(true);
        Press.attach(mAction, Press.Style.BUTTON);
        addView(mAction, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);
    }

    /**
     * The home screen's bar: right above the play bar ({@code aboveId}), as wide as it with a 12 dp
     * margin. Made once and kept with the view it is attached to.
     */
    public static UndoBar attach(ConstraintLayout root, int aboveId) {
        Object existing = root.getTag(R.id.projects_undo_bar);
        if (existing instanceof UndoBar) return (UndoBar) existing;
        UndoBar bar = new UndoBar(root.getContext());
        bar.setId(View.generateViewId());
        float dp = root.getResources().getDisplayMetrics().density;
        ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.startToStart = aboveId;
        params.endToEnd = aboveId;
        params.bottomToTop = aboveId;
        params.setMarginStart(Math.round(12 * dp));
        params.setMarginEnd(Math.round(12 * dp));
        params.bottomMargin = Math.round(10 * dp);
        root.addView(bar, params);
        root.setTag(R.id.projects_undo_bar, bar);
        return bar;
    }

    /** Shows {@code message} with "Geri al" doing {@code onUndo} (and the bar leaving). */
    public void show(CharSequence message, @Nullable Runnable onUndo) {
        removeCallbacks(mLeave);
        mText.setText(message);
        mMark.animate().cancel();
        mMark.setAlpha(1f);
        setMark(R.drawable.projects_ic_check_circle, R.color.projects_jade_400);
        boolean undo = onUndo != null;
        mAction.setVisibility(undo ? VISIBLE : GONE);
        if (undo) {
            mAction.setText(R.string.projects_undo);
            mAction.setOnClickListener(v -> {
                // One decision: a second tap while the bar answers does nothing.
                mAction.setOnClickListener(null);
                onUndo.run();
            });
        }
        enter();
        postDelayed(mLeave, undo ? STAY_MS : RESULT_STAY_MS + 1200);
    }

    /**
     * After "Geri al": the line says what happened, in place, beside {@code mark} in ash (where the
     * jade check was), and the bar leaves soon after.
     */
    public void showResult(CharSequence message, @DrawableRes int mark) {
        removeCallbacks(mLeave);
        if (!mShowing) {
            show(message, null);
            setMark(mark, R.color.projects_ash_400);
            return;
        }
        if (Motion.enabled(getContext())) {
            mText.animate().cancel();
            mText.setAlpha(0f);
            mText.setText(message);
            mText.animate().alpha(1f).setDuration(140).setInterpolator(Motion.EASE_OUT).start();
            mAction.animate().alpha(0f).setDuration(110).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> {
                        mAction.setVisibility(GONE);
                        mAction.setAlpha(1f);
                    }).start();
            mMark.animate().cancel();
            mMark.animate().alpha(0f).setDuration(90).setInterpolator(Motion.EASE_OUT).withEndAction(() -> {
                setMark(mark, R.color.projects_ash_400);
                mMark.animate().alpha(1f).setDuration(140).setInterpolator(Motion.EASE_OUT).start();
            }).start();
        } else {
            mText.setText(message);
            mAction.setVisibility(GONE);
            setMark(mark, R.color.projects_ash_400);
        }
        postDelayed(mLeave, RESULT_STAY_MS);
    }

    private void setMark(@DrawableRes int mark, @ColorRes int tint) {
        mMark.setImageResource(mark);
        mMark.setImageTintList(ContextCompat.getColorStateList(getContext(), tint));
        mMark.setVisibility(VISIBLE);
    }

    private void enter() {
        animate().cancel();
        boolean again = mShowing && getVisibility() == VISIBLE;
        mShowing = true;
        setVisibility(VISIBLE);
        if (again || !Motion.enabled(getContext())) {
            setAlpha(1f);
            setTranslationY(0f);
            return;
        }
        setAlpha(0f);
        setTranslationY(10 * getResources().getDisplayMetrics().density);
        animate().alpha(1f).translationY(0f).setDuration(ENTER_MS).setInterpolator(Motion.EASE_OUT).setListener(null).start();
    }

    /** Leaves (faster than it came). */
    public void dismiss() {
        removeCallbacks(mLeave);
        if (!mShowing) return;
        mShowing = false;
        animate().cancel();
        if (!Motion.enabled(getContext()) || getVisibility() != VISIBLE) {
            setVisibility(GONE);
            return;
        }
        animate().alpha(0f).translationY(6 * getResources().getDisplayMetrics().density)
                .setDuration(EXIT_MS).setInterpolator(Motion.EASE_OUT)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!mShowing) setVisibility(GONE);
                    }
                }).start();
    }

    /** A finger on the bar holds it (reading, or about to press "Geri al"). */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            removeCallbacks(mLeave);
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && mShowing) {
            removeCallbacks(mLeave);
            postDelayed(mLeave, mAction.getVisibility() == VISIBLE ? STAY_MS / 2 : RESULT_STAY_MS);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mLeave);
        super.onDetachedFromWindow();
    }
}
