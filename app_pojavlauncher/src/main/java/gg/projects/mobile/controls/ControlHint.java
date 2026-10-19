package gg.projects.mobile.controls;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.ui.Haptics;

/**
 * A short line beside an on-screen key (M16): why a MODLAR key does nothing right now ("no key in
 * the game", "this mod isn't in this profile"). It grows out of the key it belongs to (from 96 %,
 * a few dp towards the key, faded in, 160 ms) and leaves faster (110 ms); shown again while it is
 * up, it moves and changes at once. It stays while the finger is on the key and a few seconds
 * after, and goes when the panel closes or the controls hide. The look is the keys' own: an
 * obsidian chamfered panel with a bone hairline and Figtree. With animations off it appears and
 * disappears.
 *
 * <p>M17: the line can carry one button under its text (the one-tap key fix, and "Geri al" once
 * it is asked for). Only that button takes touches, through a 48 dp target; everywhere else the
 * game and the keys under the line keep theirs, as before. A line with a button stays longer after
 * the finger leaves the key, so the button can be reached; a press sinks the button, clicks, and
 * the line answers in place with a soft refresh. TalkBack reads the line with its button and can
 * press it.
 */
@SuppressLint("ViewConstructor")
public final class ControlHint extends View {
    static final long ENTER_MS = 160;
    static final long EXIT_MS = 110;
    /** How long it stays after the finger leaves the key: about two short sentences' reading. */
    static final long VISIBLE_MS = 2800;
    /** M17: a line with a button stays long enough to move a thumb to it and decide. */
    static final long ACTION_VISIBLE_MS = 6000;
    private static final long REFRESH_MS = 160;
    private static final Interpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);

    private static final int FILL = 0xF2121016; // obsidian-900 at 95 %
    private static final int RING = 0x52F3EBDD; // bone at 32 %
    private static final int INK = 0xFFF3EBDD; // bone-100
    private static final int GOLD = 0xFFF6D58E; // gold-300
    private static final int BUTTON_FILL = 0x1FE89A2C; // gold-500 at 12 %
    private static final int BUTTON_FILL_PRESSED = 0x47E89A2C; // gold-500 at 28 %
    private static final int BUTTON_RING = 0xB3F6D58E; // gold-300 at 70 %

    /** M17: a button on the line: its label and what it does. */
    public static final class Action {
        public final String label;
        public final Runnable run;

        public Action(String label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }

    private final float dp;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint buttonPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path buttonShape = new Path();
    private final RectF button = new RectF();
    private StaticLayout text;
    private String shown = "";
    @Nullable
    private Action action;
    private String actionShown;
    private boolean showing;
    private View shownFor;
    /** 0 at rest, 1 pressed: the button sinks and its fill lifts. */
    private float press;
    private ValueAnimator pressAnimator;
    private boolean fingerOnButton;
    private final Runnable hideLater = this::hide;

    public ControlHint(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        float sp = context.getResources().getDisplayMetrics().scaledDensity;
        Typeface face = null;
        Typeface strong = null;
        try {
            face = ResourcesCompat.getFont(context, R.font.figtree_regular);
            strong = ResourcesCompat.getFont(context, R.font.figtree_semibold);
        } catch (RuntimeException e) {
            // The platform font is fine.
        }
        if (face != null) textPaint.setTypeface(face);
        buttonPaint.setTypeface(strong != null ? strong : Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(13f * sp);
        textPaint.setColor(INK);
        buttonPaint.setTextSize(13f * sp);
        buttonPaint.setColor(GOLD);
        fillPaint.setColor(FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(1f, dp));
        ringPaint.setColor(RING);
        buttonRingPaint.setStyle(Paint.Style.STROKE);
        buttonRingPaint.setStrokeWidth(Math.max(1f, dp));
        setWillNotDraw(false);
        quiet();
        // Above the keys (upstream lifts every key to a translation Z of 10), without a shadow.
        setOutlineProvider(null);
        setTranslationZ(12f);
        setVisibility(GONE);
    }

    /** No button: no touch, no focus; TalkBack hears the line through its announcement only. */
    private void quiet() {
        setClickable(false);
        setFocusable(false);
        setContentDescription(null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Takes a touch only on its button (M17); anything else goes to whatever is under the line, as
     * the line without a button always did.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (action == null || !showing) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!onButton(event.getX(), event.getY(), 0f)) return false;
                // An on-screen key under the button wins: the line never takes a jump or a sneak.
                if (overAKey(event.getX() + getLeft(), event.getY() + getTop())) return false;
                fingerOnButton = true;
                removeCallbacks(hideLater);
                pressTo(1f);
                Haptics.press(this);
                return true;
            case MotionEvent.ACTION_MOVE: {
                // A little slop, so a thumb that rolls does not cancel the press.
                boolean inside = onButton(event.getX(), event.getY(), 12f * dp);
                if (inside != fingerOnButton) {
                    fingerOnButton = inside;
                    pressTo(inside ? 1f : 0f);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                boolean run = fingerOnButton;
                fingerOnButton = false;
                pressTo(0f);
                if (run) {
                    performClick();
                } else {
                    postDelayed(hideLater, ACTION_VISIBLE_MS);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                fingerOnButton = false;
                pressTo(0f);
                postDelayed(hideLater, ACTION_VISIBLE_MS);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        Action current = action;
        if (current == null || !showing) return false;
        Haptics.confirm(this);
        current.run.run();
        return true;
    }

    /** True when the parent's point ({@code x}, {@code y}) is on a visible on-screen key of the layout. */
    private boolean overAKey(float x, float y) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) return false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == this || !(child instanceof net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface)
                    || child.getVisibility() != VISIBLE || child.getAlpha() <= 0f) continue;
            if (x >= child.getX() && x < child.getX() + child.getWidth() && y >= child.getY() && y < child.getY() + child.getHeight()) {
                return true;
            }
        }
        return false;
    }

    /** True for a point on the button's 48 dp target (plus {@code slop}). */
    private boolean onButton(float x, float y, float slop) {
        if (action == null) return false;
        float reachY = Math.max(0f, (48f * dp - button.height()) / 2f);
        return x >= button.left - slop && x <= button.right + slop
                && y >= button.top - reachY - slop && y <= button.bottom + reachY + slop;
    }

    private void pressTo(float target) {
        if (pressAnimator != null) pressAnimator.cancel();
        if (!Motion.enabled(getContext())) {
            press = target;
            invalidate();
            return;
        }
        pressAnimator = ValueAnimator.ofFloat(press, target);
        // Down fast, back a little slower (the keys' own rhythm).
        pressAnimator.setDuration(target > press ? 90 : 160);
        pressAnimator.setInterpolator(EASE_OUT);
        pressAnimator.addUpdateListener(a -> {
            press = (Float) a.getAnimatedValue();
            invalidate();
        });
        pressAnimator.start();
    }

    /** {@link #show(View, RectF, String, boolean, Action)} without a button. */
    public void show(View key, RectF clear, String message, boolean held) {
        show(key, clear, message, held, null);
    }

    /**
     * Shows {@code message} beside {@code key} (a child of the same layout), clear of {@code clear}
     * (the key itself, or the whole panel a drawer's key is part of, so no other key is covered),
     * with {@code action}'s button under it when there is one. While {@code held} it stays;
     * {@link #release} starts its time.
     */
    public void show(View key, RectF clear, String message, boolean held, @Nullable Action action) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || message == null || message.isEmpty()) return;
        boolean again = showing && getVisibility() == VISIBLE;
        String actionLabel = action == null ? null : action.label;
        boolean sameKey = again && key == shownFor;
        boolean changed = !message.equals(shown) || (actionLabel == null ? actionShown != null : !actionLabel.equals(actionShown));
        int margin = Math.round(12 * dp);
        int padX = Math.round(12 * dp), padY = Math.round(9 * dp);
        // Wide enough for each of the two sentences (what, then how to fix it) to keep its own line.
        int maxText = Math.max(Math.round(120 * dp), Math.min(Math.round(336 * dp), parent.getWidth() - 2 * margin) - 2 * padX);
        if (text == null || !message.equals(shown)) {
            text = layout(message, maxText);
            shown = message;
        }
        this.action = action;
        actionShown = actionLabel;
        int contentW = Math.round(widest(text));
        int contentH = text.getHeight();
        if (action != null) {
            float buttonW = Math.max(96 * dp, buttonPaint.measureText(action.label) + 28 * dp);
            float buttonH = 36 * dp;
            float top = padY + contentH + 10 * dp;
            button.set(padX, top, padX + buttonW, top + buttonH);
            contentW = Math.max(contentW, Math.round(buttonW));
            contentH += Math.round(10 * dp + buttonH);
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setContentDescription(message.replace('\n', ' ') + " " + action.label);
        } else {
            button.setEmpty();
            quiet();
        }
        int w = contentW + 2 * padX;
        int h = contentH + 2 * padY;

        // Under the panel, its left edge on the key's, kept on screen; above it when there is no room.
        float keyLeft = key.getX();
        boolean below = clear.bottom + 8 * dp + h <= parent.getHeight() - margin;
        int x = Math.round(Math.max(margin, Math.min(keyLeft, parent.getWidth() - margin - w)));
        int y = Math.round(below ? clear.bottom + 8 * dp : clear.top - 8 * dp - h);
        FrameLayout.LayoutParams params = getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) getLayoutParams() : new FrameLayout.LayoutParams(w, h);
        params.width = w;
        params.height = h;
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = x;
        params.topMargin = y;
        setLayoutParams(params);
        bringToFront();
        invalidate();

        animate().cancel();
        setVisibility(VISIBLE);
        showing = true;
        shownFor = key;
        boolean motion = Motion.enabled(getContext());
        if (again || !motion) {
            rest();
            // M17: the same line answering a press on its button refreshes softly in place; another
            // key's line moves and changes at once, like a second tooltip.
            if (sameKey && changed && motion) {
                setAlpha(0.55f);
                animate().alpha(1f).setDuration(REFRESH_MS).setInterpolator(EASE_OUT).withLayer();
            }
        } else {
            setPivotX(Math.max(0f, Math.min(w, keyLeft + key.getWidth() / 2f - x)));
            setPivotY(below ? 0f : h);
            setAlpha(0f);
            setScaleX(0.96f);
            setScaleY(0.96f);
            setTranslationY((below ? -4f : 4f) * dp);
            animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(ENTER_MS).setInterpolator(EASE_OUT).withLayer();
        }
        if (!again || changed) announceForAccessibility(action == null ? message : message + " " + action.label);
        removeCallbacks(hideLater);
        if (!held) postDelayed(hideLater, action != null ? ACTION_VISIBLE_MS : VISIBLE_MS);
    }

    /** The finger left the key: the line stays long enough to be read (and its button reached), then goes. */
    public void release() {
        if (!showing || fingerOnButton) return;
        removeCallbacks(hideLater);
        postDelayed(hideLater, action != null ? ACTION_VISIBLE_MS : VISIBLE_MS);
    }

    /** Leaves (faster than it came). */
    public void hide() {
        removeCallbacks(hideLater);
        if (!showing) return;
        showing = false;
        shownFor = null;
        fingerOnButton = false;
        animate().cancel();
        if (!Motion.enabled(getContext()) || getVisibility() != VISIBLE) {
            setVisibility(GONE);
            rest();
            settle();
            return;
        }
        animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(EXIT_MS).setInterpolator(EASE_OUT).withLayer()
                .withEndAction(() -> {
                    if (showing) return;
                    setVisibility(GONE);
                    rest();
                    settle();
                });
    }

    /** Gone: the button goes with it, so nothing takes a touch. */
    private void settle() {
        action = null;
        actionShown = null;
        press = 0f;
        quiet();
    }

    private void rest() {
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
        setTranslationY(0f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = ringPaint.getStrokeWidth() / 2f;
        float[] p = ControlStyle.outline(w, h, 6f * dp, inset);
        shape.reset();
        shape.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) shape.lineTo(p[i], p[i + 1]);
        shape.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (text == null) return;
        canvas.drawPath(shape, fillPaint);
        canvas.drawPath(shape, ringPaint);
        int save = canvas.save();
        canvas.translate(12 * dp, 9 * dp);
        text.draw(canvas);
        canvas.restoreToCount(save);
        Action current = action;
        if (current == null || button.isEmpty()) return;
        // The button: the keys' chamfer in a gold hairline; pressed, it sinks to 96 % and its fill lifts.
        save = canvas.save();
        float scale = Motion.enabled(getContext()) ? 1f - 0.04f * press : 1f;
        canvas.scale(scale, scale, button.centerX(), button.centerY());
        float[] p = ControlStyle.outline(button.width(), button.height(), 7f * dp, buttonRingPaint.getStrokeWidth() / 2f);
        buttonShape.reset();
        buttonShape.moveTo(button.left + p[0], button.top + p[1]);
        for (int i = 2; i < p.length; i += 2) buttonShape.lineTo(button.left + p[i], button.top + p[i + 1]);
        buttonShape.close();
        buttonFillPaint.setColor(ControlStyle.mix(BUTTON_FILL, BUTTON_FILL_PRESSED, press));
        buttonRingPaint.setColor(ControlStyle.mix(BUTTON_RING, GOLD, press));
        canvas.drawPath(buttonShape, buttonFillPaint);
        canvas.drawPath(buttonShape, buttonRingPaint);
        Paint.FontMetrics metrics = buttonPaint.getFontMetrics();
        float baseline = button.centerY() - (metrics.ascent + metrics.descent) / 2f;
        float labelX = button.centerX() - buttonPaint.measureText(current.label) / 2f;
        canvas.drawText(current.label, labelX, baseline, buttonPaint);
        canvas.restoreToCount(save);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return true;
    }

    private StaticLayout layout(String message, int width) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Balanced lines: two lines of about the same length read better than a long one and a word.
            return StaticLayout.Builder.obtain(message, 0, message.length(), textPaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f * dp, 1f)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setMaxLines(4)
                    .build();
        }
        //noinspection deprecation
        return new StaticLayout(message, textPaint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 2f * dp, false);
    }

    private static float widest(StaticLayout layout) {
        float max = 0f;
        for (int i = 0; i < layout.getLineCount(); i++) max = Math.max(max, layout.getLineWidth(i));
        return (float) Math.ceil(max);
    }
}
