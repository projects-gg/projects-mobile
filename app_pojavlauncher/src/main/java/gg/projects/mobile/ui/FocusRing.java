package gg.projects.mobile.ui;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

/**
 * MOB-PARITY (keyboards and gamepads on tablets and Chromebooks; design.md §8 "the focus ring belongs to the
 * keyboard"): the launcher's focus ring. With a keyboard or a gamepad's D-pad (Android's non-touch mode) the focused control gets a gold-300 ring,
 * 2 dp wide and 2 dp outside it, drawn in the window's overlay so every row, card and icon button has one without its
 * own drawable. Chamfered controls get a chamfered ring. Controls that already draw their own focus look (the
 * chamfered buttons and chips, OYNA, text fields) keep it. Touch hides the ring at once; nothing animates or runs while
 * the ring is hidden.
 */
public final class FocusRing implements ViewTreeObserver.OnGlobalFocusChangeListener,
        ViewTreeObserver.OnTouchModeChangeListener, ViewTreeObserver.OnPreDrawListener {
    private final ViewGroup mRoot;
    private final Ring mRing;
    private final int[] mRootAt = new int[2];
    private final int[] mViewAt = new int[2];
    private final Rect mRect = new Rect();
    @Nullable
    private View mTarget;

    private FocusRing(ViewGroup root) {
        mRoot = root;
        mRing = new Ring(root);
    }

    /**
     * A control whose visible shape is smaller than its touch target (the friends pill: a 36 dp pill in a 48 dp row) or
     * rounder than the ring's 10 dp: set as the view's {@code R.id.projects_focus_shape} tag, in pixels.
     */
    public static final class Shape {
        final int insetHorizontal, insetVertical;
        final float radius;

        public Shape(int insetHorizontal, int insetVertical, float radius) {
            this.insetHorizontal = insetHorizontal;
            this.insetVertical = insetVertical;
            this.radius = radius;
        }
    }

    /** Puts the ring on the activity's content; once per activity, after {@code setContentView}. */
    public static void attach(Activity activity) {
        attach(activity.findViewById(android.R.id.content));
    }

    /** The same in a dialog's window (the action sheets), once it shows. */
    public static void attach(android.app.Dialog dialog) {
        attach(dialog.findViewById(android.R.id.content));
    }

    private static void attach(@Nullable View content) {
        if (!(content instanceof ViewGroup)) return;
        FocusRing ring = new FocusRing((ViewGroup) content);
        ViewTreeObserver observer = content.getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener(ring);
        observer.addOnTouchModeChangeListener(ring);
        observer.addOnPreDrawListener(ring);
        content.getOverlay().add(ring.mRing);
    }

    @Override
    public void onGlobalFocusChanged(@Nullable View oldFocus, @Nullable View newFocus) {
        mTarget = newFocus;
        place();
    }

    @Override
    public void onTouchModeChanged(boolean isInTouchMode) {
        place();
    }

    @Override
    public boolean onPreDraw() {
        // A focused row that scrolls or slides carries its ring along; nothing to do while the ring is hidden.
        if (mTarget != null && !mRoot.isInTouchMode()) place();
        return true;
    }

    private void place() {
        View target = mTarget;
        if (target == null || mRoot.isInTouchMode() || !target.isShown() || !target.isFocused()
                || target.getWidth() == 0 || ownsFocusLook(target)) {
            mRing.hide();
            return;
        }
        mRoot.getLocationInWindow(mRootAt);
        target.getLocationInWindow(mViewAt);
        int left = mViewAt[0] - mRootAt[0], top = mViewAt[1] - mRootAt[1];
        mRect.set(left, top, left + Math.round(target.getWidth() * target.getScaleX()),
                top + Math.round(target.getHeight() * target.getScaleY()));
        Object tag = target.getTag(R.id.projects_focus_shape);
        Shape shape = tag instanceof Shape ? (Shape) tag : null;
        if (shape != null) mRect.inset(shape.insetHorizontal, shape.insetVertical);
        mRing.show(mRect, shape == null && chamfered(target), shape == null ? -1f : shape.radius);
    }

    /** True when the control draws its own focus state (a selector with {@code state_focused}, OYNA, a text field). */
    static boolean ownsFocusLook(View view) {
        if (view instanceof EditText) return true;
        return drawsFocus(view.getBackground());
    }

    private static boolean drawsFocus(@Nullable Drawable drawable) {
        if (drawable == null) return false;
        if (drawable instanceof PlayButtonDrawable) return true;
        if (drawable instanceof InsetDrawable) return drawsFocus(((InsetDrawable) drawable).getDrawable());
        if (drawable instanceof StateListDrawable) return hasFocusedState((StateListDrawable) drawable);
        return false;
    }

    private static boolean hasFocusedState(StateListDrawable states) {
        try {
            int count;
            if (Build.VERSION.SDK_INT >= 29) count = states.getStateCount();
            else count = (Integer) StateListDrawable.class.getMethod("getStateCount").invoke(states);
            for (int i = 0; i < count; i++) {
                int[] set;
                if (Build.VERSION.SDK_INT >= 29) set = states.getStateSet(i);
                else set = (int[]) StateListDrawable.class.getMethod("getStateSet", int.class).invoke(states, i);
                if (set == null) continue;
                for (int s : set) if (s == android.R.attr.state_focused) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // An old Android without the hidden accessors: the ring is drawn (a double ring beats none).
        }
        return false;
    }

    private static boolean chamfered(View view) {
        Drawable bg = view.getBackground();
        if (bg instanceof InsetDrawable) bg = ((InsetDrawable) bg).getDrawable();
        if (bg instanceof StateListDrawable) bg = bg.getCurrent();
        return bg instanceof ChamferDrawable;
    }

    /** The ring itself, a drawable in the content's overlay. */
    private static final class Ring extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mBox = new RectF();
        private final float mGap, mStroke, mRadius, mCut;
        private final View mHost;
        private boolean mShown;
        private boolean mChamfer;
        private float mRound;

        Ring(View host) {
            mHost = host;
            float d = host.getResources().getDisplayMetrics().density;
            mGap = 2 * d;
            mStroke = 2 * d;
            mRadius = 10 * d;
            mCut = 9 * d;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(mStroke);
            mPaint.setColor(ContextCompat.getColor(host.getContext(), R.color.projects_gold_300));
        }

        /** {@code radius} below zero: the ring's own 10 dp. */
        void show(Rect view, boolean chamfer, float radius) {
            float out = mGap + mStroke / 2f;
            // Kept inside the window: a row that spans it still shows its ring.
            float half = mStroke / 2f;
            float l = Math.max(half, view.left - out), t = Math.max(half, view.top - out);
            float r = Math.min(mHost.getWidth() - half, view.right + out), b = Math.min(mHost.getHeight() - half, view.bottom + out);
            float round = radius < 0f ? mRadius : radius + out;
            if (mShown && chamfer == mChamfer && round == mRound && mBox.left == l && mBox.top == t && mBox.right == r && mBox.bottom == b) return;
            mBox.set(l, t, r, b);
            mChamfer = chamfer;
            mRound = round;
            mShown = true;
            mPath.reset();
            if (chamfer) {
                float cut = Math.min(mCut + out, Math.min(r - l, b - t) / 2f);
                mPath.moveTo(l + cut, t);
                mPath.lineTo(r, t);
                mPath.lineTo(r, b - cut);
                mPath.lineTo(r - cut, b);
                mPath.lineTo(l, b);
                mPath.lineTo(l, t + cut);
                mPath.close();
            } else {
                float corner = Math.min(round, Math.min(r - l, b - t) / 2f);
                mPath.addRoundRect(mBox, corner, corner, Path.Direction.CW);
            }
            setBounds(0, 0, mHost.getWidth(), mHost.getHeight());
            invalidateSelf();
        }

        void hide() {
            if (!mShown) return;
            mShown = false;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mShown) canvas.drawPath(mPath, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
