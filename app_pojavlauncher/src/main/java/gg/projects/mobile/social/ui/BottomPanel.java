package gg.projects.mobile.social.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * MOB1: a sheet that rises from the bottom (the emoji and sticker picker, who reacted, a crew tag's
 * card): the screen dims, the sheet slides up on the entrance curve (280 ms) and leaves faster (190 ms),
 * interruptible; a drag on its top (the handle) pulls it down and a fling or a long pull closes it. Its
 * top corners are round (16 dp), the surface opaque obsidian with a hairline; it is as wide as the
 * screen and at most {@code maxHeightFraction} of it. With animations off it just appears and goes.
 * TABLET-M: on a wide window it rises in the middle, at most {@code projects_sheet_max} (640 dp) wide; the
 * dimmed sides close it like the space above.
 */
public final class BottomPanel {
    private final Dialog mDialog;
    private final LinearLayout mSheet;
    private final ContentFrame mContent;
    private boolean mClosing;
    /** The close callback ran (once, whichever way the sheet went). */
    private boolean mClosed;
    @Nullable
    private Runnable mOnClosed;

    private BottomPanel(Context context, float maxHeightFraction) {
        mDialog = new Dialog(context, R.style.ProjectsBottomPanel);
        float dp = context.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(context);
        root.setOnClickListener(v -> dismiss());
        mSheet = new LinearLayout(context);
        mSheet.setOrientation(LinearLayout.VERTICAL);
        mSheet.setClickable(true);
        GradientDrawable surface = new GradientDrawable();
        float r = 16 * dp;
        surface.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        surface.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_900));
        surface.setStroke(Math.max(1, Math.round(dp)), ContextCompat.getColor(context, R.color.projects_hairline));
        mSheet.setBackground(surface);
        mSheet.setElevation(12 * dp);
        // The handle: a drag here pulls the sheet down.
        View handle = new View(context);
        GradientDrawable bar = new GradientDrawable();
        bar.setCornerRadius(2 * dp);
        bar.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_600));
        handle.setBackground(bar);
        FrameLayout grip = new FrameLayout(context);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(Math.round(36 * dp), Math.round(4 * dp), Gravity.CENTER);
        grip.addView(handle, barLp);
        grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mSheet.addView(grip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(20 * dp)));
        mContent = new ContentFrame(context);
        mSheet.addView(mContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxHeight = Math.round(context.getResources().getDisplayMetrics().heightPixels * maxHeightFraction);
        int maxWidth = context.getResources().getDimensionPixelSize(R.dimen.projects_sheet_max);
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(new MaxHeightFrame(context, maxHeight, maxWidth, mSheet), sheetLp);
        attachDrag(grip, dp);
        mDialog.setContentView(root);
        mDialog.setCanceledOnTouchOutside(true);
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.55f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        mDialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                dismiss();
                return true;
            }
            return false;
        });
        // However the sheet goes (its own slide, the dialog dismissed, the activity's windows removed on a
        // recreation), its owner hears it once and lets go of the service.
        mDialog.setOnDismissListener(d -> closed());
        mSheet.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                closed();
            }
        });
    }

    /**
     * A sheet of at most {@code maxHeightFraction} of the screen holding {@code content}; a height the content
     * set for itself (the picker's half screen) is kept, so the sheet does not change size with what it shows.
     */
    public static BottomPanel of(Context context, View content, float maxHeightFraction) {
        BottomPanel panel = new BottomPanel(context, maxHeightFraction);
        ViewGroup.LayoutParams own = content.getLayoutParams();
        boolean fixed = own != null && own.height > 0;
        panel.mContent.mPreferred = fixed ? own.height : 0;
        panel.mContent.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                fixed ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private void closed() {
        if (mClosed) return;
        mClosed = true;
        if (mOnClosed != null) mOnClosed.run();
    }

    public BottomPanel onClosed(@Nullable Runnable onClosed) {
        mOnClosed = onClosed;
        return this;
    }

    public Dialog dialog() {
        return mDialog;
    }

    public boolean showing() {
        return mDialog.isShowing() && !mClosing;
    }

    public void show() {
        mDialog.show();
        mSheet.setVisibility(View.INVISIBLE);
        mSheet.post(() -> {
            mSheet.setVisibility(View.VISIBLE);
            if (!Motion.enabled(mSheet.getContext())) return;
            mSheet.setTranslationY(mSheet.getHeight());
            mSheet.animate().translationY(0f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_DRAWER).start();
        });
    }

    /** Slides down, then goes. */
    public void dismiss() {
        if (mClosing || !mDialog.isShowing()) return;
        mClosing = true;
        Runnable gone = () -> {
            try {
                mDialog.dismiss();
            } catch (RuntimeException ignored) {
                // The activity went first.
            }
            closed();
        };
        if (!Motion.enabled(mSheet.getContext()) || mSheet.getHeight() == 0) {
            gone.run();
            return;
        }
        mSheet.animate().cancel();
        mSheet.animate().translationY(mSheet.getHeight()).setDuration(Motion.EXIT_MS).setInterpolator(Motion.EASE_IN)
                .withEndAction(gone).start();
    }

    /** A drag on the handle moves the sheet down; a fling or half its height closes it, else it settles back. */
    private void attachDrag(View grip, float dp) {
        final int slop = ViewConfiguration.get(grip.getContext()).getScaledTouchSlop();
        final float fling = 900 * dp;
        grip.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            boolean dragging;
            VelocityTracker tracker;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Leaving: a touch would cancel the slide and with it the close (the sheet would stay, deaf).
                if (mClosing) return true;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        dragging = false;
                        tracker = VelocityTracker.obtain();
                        tracker.addMovement(event);
                        mSheet.animate().cancel();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (tracker != null) tracker.addMovement(event);
                        float dy = event.getRawY() - startY;
                        if (!dragging && Math.abs(dy) > slop) dragging = true;
                        if (dragging) mSheet.setTranslationY(Math.max(0f, dy));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float speed = 0;
                        if (tracker != null) {
                            tracker.addMovement(event);
                            tracker.computeCurrentVelocity(1000);
                            speed = tracker.getYVelocity();
                            tracker.recycle();
                            tracker = null;
                        }
                        float moved = mSheet.getTranslationY();
                        if (dragging && (speed > fling || moved > mSheet.getHeight() * 0.4f)) {
                            dismiss();
                        } else {
                            mSheet.animate().translationY(0f).setDuration(220).setInterpolator(Motion.EASE_OUT).start();
                        }
                        dragging = false;
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    /**
     * Holds the content: as tall as it is, or with {@code mPreferred} exactly that tall while the window has
     * room (fewer results never shrink the sheet; an open keyboard still does).
     */
    private static final class ContentFrame extends FrameLayout {
        int mPreferred;

        ContentFrame(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (mPreferred <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            int size = MeasureSpec.getSize(heightMeasureSpec);
            int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ? mPreferred : Math.min(mPreferred, size);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    /** Holds the sheet at most {@code max} tall (a long list scrolls inside) and {@code maxWidth} wide. */
    private static final class MaxHeightFrame extends FrameLayout {
        private final int mMax;
        private final int mMaxWidth;

        MaxHeightFrame(Context context, int max, int maxWidth, View child) {
            super(context);
            mMax = max;
            mMaxWidth = maxWidth;
            addView(child, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = MeasureSpec.getSize(heightMeasureSpec);
            int limit = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ? mMax : Math.min(mMax, size);
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (mMaxWidth > 0 && width > mMaxWidth) widthMeasureSpec = MeasureSpec.makeMeasureSpec(mMaxWidth, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }
}
