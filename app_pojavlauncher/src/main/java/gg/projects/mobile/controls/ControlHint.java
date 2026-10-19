package gg.projects.mobile.controls;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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

import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * A short line beside an on-screen key (M16): why a MODLAR key does nothing right now ("no key in
 * the game", "this mod isn't in this profile"). It grows out of the key it belongs to (from 96 %,
 * a few dp towards the key, faded in, 160 ms) and leaves faster (110 ms); shown again while it is
 * up, it moves and changes at once. It stays while the finger is on the key and a few seconds
 * after, and goes when the panel closes or the controls hide. It takes no touch: the game and the
 * keys under it keep theirs. The look is the keys' own: an obsidian chamfered panel with a bone
 * hairline and Figtree. With animations off it appears and disappears.
 */
@SuppressLint("ViewConstructor")
public final class ControlHint extends View {
    static final long ENTER_MS = 160;
    static final long EXIT_MS = 110;
    /** How long it stays after the finger leaves the key: about two short sentences' reading. */
    static final long VISIBLE_MS = 2800;
    private static final Interpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);

    private static final int FILL = 0xF2121016; // obsidian-900 at 95 %
    private static final int RING = 0x52F3EBDD; // bone at 32 %
    private static final int INK = 0xFFF3EBDD; // bone-100

    private final float dp;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private StaticLayout text;
    private String shown = "";
    private boolean showing;
    private final Runnable hideLater = this::hide;

    public ControlHint(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        float sp = context.getResources().getDisplayMetrics().scaledDensity;
        Typeface face = null;
        try {
            face = ResourcesCompat.getFont(context, R.font.figtree_regular);
        } catch (RuntimeException e) {
            // The platform font is fine.
        }
        if (face != null) textPaint.setTypeface(face);
        textPaint.setTextSize(13f * sp);
        textPaint.setColor(INK);
        fillPaint.setColor(FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(1f, dp));
        ringPaint.setColor(RING);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Above the keys (upstream lifts every key to a translation Z of 10), without a shadow.
        setOutlineProvider(null);
        setTranslationZ(12f);
        setVisibility(GONE);
    }

    /** Takes no touch: whatever is under it gets it. */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    /**
     * Shows {@code message} beside {@code key} (a child of the same layout), clear of {@code clear}
     * (the key itself, or the whole panel a drawer's key is part of, so no other key is covered).
     * While {@code held} it stays; {@link #release} starts its time.
     */
    public void show(View key, android.graphics.RectF clear, String message, boolean held) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || message == null || message.isEmpty()) return;
        boolean again = showing && getVisibility() == VISIBLE;
        int margin = Math.round(12 * dp);
        int padX = Math.round(12 * dp), padY = Math.round(9 * dp);
        // Wide enough for each of the two sentences (what, then how to fix it) to keep its own line.
        int maxText = Math.max(Math.round(120 * dp), Math.min(Math.round(336 * dp), parent.getWidth() - 2 * margin) - 2 * padX);
        if (text == null || !message.equals(shown)) {
            text = layout(message, maxText);
            shown = message;
        }
        int w = Math.round(widest(text)) + 2 * padX;
        int h = text.getHeight() + 2 * padY;

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
        if (again || !Motion.enabled(getContext())) {
            // Already up (another key): it moves and changes at once, like a second tooltip.
            rest();
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
        if (!again) announceForAccessibility(message);
        removeCallbacks(hideLater);
        if (!held) postDelayed(hideLater, VISIBLE_MS);
    }

    /** The finger left the key: the line stays long enough to be read, then goes. */
    public void release() {
        if (!showing) return;
        removeCallbacks(hideLater);
        postDelayed(hideLater, VISIBLE_MS);
    }

    /** Leaves (faster than it came). */
    public void hide() {
        removeCallbacks(hideLater);
        if (!showing) return;
        showing = false;
        animate().cancel();
        if (!Motion.enabled(getContext()) || getVisibility() != VISIBLE) {
            setVisibility(GONE);
            rest();
            return;
        }
        animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(EXIT_MS).setInterpolator(EASE_OUT).withLayer()
                .withEndAction(() -> {
                    if (showing) return;
                    setVisibility(GONE);
                    rest();
                });
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
