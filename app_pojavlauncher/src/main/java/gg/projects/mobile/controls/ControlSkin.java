package gg.projects.mobile.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.customcontrols.ControlData;

import gg.projects.mobile.mods.Motion;

/**
 * Draws a Projects on-screen control (M15): a chamfered key in the button's own fill and ring,
 * a Phosphor glyph and a short label, with pressed, held and on states that ease in and out
 * ({@link ControlStyle}). A button opts in with a glyph name ({@link ControlData#icon}); every
 * other button keeps upstream's look. Nothing animates while the key rests.
 */
public final class ControlSkin {
    private final View view;
    private final float dp;
    private final float sp;
    private final boolean motion;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private int shapeW = -1, shapeH = -1;
    private float shapeCorner = -1;

    private int baseFill, baseRing;
    private float ringDp;
    private float corner;
    private String glyphName = "";
    private String altGlyphName = "";
    @Nullable private Drawable glyph;
    @Nullable private Drawable altGlyph;
    private String label = "";
    private String altLabel = "";
    private float labelPx, altLabelPx;
    private int fittedW = -1, fittedH = -1;
    // The tint each glyph was last given (a tint change builds a new colour filter).
    private int glyphInk, altGlyphInk;
    private boolean glyphInked, altGlyphInked;

    // Shown state and where it is heading.
    private float press, hold, on, alt;
    private boolean pressTo, holdTo, onTo, altTo;
    private long lastFrame;

    private static Typeface sTypeface;

    private ControlSkin(View view) {
        this.view = view;
        Context context = view.getContext();
        dp = context.getResources().getDisplayMetrics().density;
        sp = context.getResources().getDisplayMetrics().scaledDensity;
        motion = Motion.enabled(context);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeJoin(Paint.Join.MITER);
        labelPaint.setTypeface(typeface(context));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setLetterSpacing(0.04f);
    }

    /** The skin for {@code data} ({@code current} updated when there is one), or null for upstream's look. */
    @Nullable
    public static ControlSkin attach(View view, @Nullable ControlSkin current, ControlData data, boolean allCaps) {
        if (data == null || data.icon == null) return null;
        ControlSkin skin = current != null ? current : new ControlSkin(view);
        skin.update(data, allCaps);
        return skin;
    }

    private static Typeface typeface(Context context) {
        if (sTypeface == null) {
            try {
                sTypeface = ResourcesCompat.getFont(context, R.font.figtree_semibold);
            } catch (RuntimeException e) {
                sTypeface = null;
            }
            if (sTypeface == null) sTypeface = Typeface.DEFAULT_BOLD;
        }
        return sTypeface;
    }

    private void update(ControlData data, boolean allCaps) {
        baseFill = data.bgColor;
        baseRing = data.strokeColor;
        ringDp = Math.max(0f, data.strokeWidth);
        corner = data.cornerRadius;
        String name = ControlStyle.knownGlyph(data.icon) ? data.icon : "";
        if (!name.equals(glyphName)) {
            glyphName = name;
            glyph = load(name);
            glyphInked = false;
        }
        String text = ControlStyle.labelText(data.name, allCaps);
        if (!text.equals(label)) {
            label = text;
            fittedW = -1;
        }
        view.setContentDescription(label);
        view.invalidate();
    }

    @Nullable
    private Drawable load(String name) {
        int res = ControlGlyphs.drawable(name);
        if (res == 0) return null;
        Drawable d = AppCompatResources.getDrawable(view.getContext(), res);
        return d == null ? null : d.mutate();
    }

    public void setPressed(boolean pressed) {
        if (pressTo == pressed) return;
        pressTo = pressed;
        kick();
    }

    public void setHeld(boolean held) {
        if (holdTo == held) return;
        holdTo = held;
        kick();
    }

    public void setOn(boolean value) {
        if (onTo == value) return;
        onTo = value;
        kick();
    }

    /**
     * Shows another glyph and label in place of the button's own (an open drawer's close mark, the
     * hide button's "show" while the controls are hidden); a null label keeps the button's.
     */
    public void setAlt(boolean value, String glyphName, @Nullable String text) {
        if (!glyphName.equals(altGlyphName)) {
            altGlyphName = glyphName;
            altGlyph = load(glyphName);
            altGlyphInked = false;
        }
        String next = text == null ? "" : text;
        if (!next.equals(altLabel)) {
            altLabel = next;
            fittedW = -1;
        }
        if (altTo == value) {
            view.invalidate();
            return;
        }
        altTo = value;
        kick();
    }

    /** Jumps to the resting look of the current state (a key that disappeared mid-press). */
    public void settle() {
        press = pressTo ? 1f : 0f;
        hold = holdTo ? 1f : 0f;
        on = onTo ? 1f : 0f;
        alt = altTo ? 1f : 0f;
        lastFrame = 0;
        view.invalidate();
    }

    private void kick() {
        if (!motion) {
            settle();
            return;
        }
        if (lastFrame == 0) lastFrame = SystemClock.uptimeMillis() - 16;
        view.invalidate();
    }

    private boolean step() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16f : Math.min(64f, now - lastFrame);
        lastFrame = now;
        float pressTarget = pressTo ? 1f : 0f;
        press = ControlStyle.approach(press, pressTarget, dt,
                pressTarget > press ? ControlStyle.PRESS_TAU_MS : ControlStyle.RELEASE_TAU_MS);
        hold = ControlStyle.approach(hold, holdTo ? 1f : 0f, dt,
                holdTo ? ControlStyle.HOLD_TAU_MS : ControlStyle.RELEASE_TAU_MS);
        on = ControlStyle.approach(on, onTo ? 1f : 0f, dt, ControlStyle.TOGGLE_TAU_MS);
        alt = ControlStyle.approach(alt, altTo ? 1f : 0f, dt, ControlStyle.TOGGLE_TAU_MS);
        boolean moving = press != pressTarget || hold != (holdTo ? 1f : 0f) || on != (onTo ? 1f : 0f)
                || alt != (altTo ? 1f : 0f);
        if (!moving) lastFrame = 0;
        return moving;
    }

    public void draw(Canvas canvas) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean moving = motion && step();

        float maxRing = (ringDp + 0.5f) * dp;
        if (w != shapeW || h != shapeH || corner != shapeCorner) {
            float[] p = ControlStyle.outline(w, h, ControlStyle.chamfer(w, h, corner), maxRing / 2f);
            shape.reset();
            shape.moveTo(p[0], p[1]);
            for (int i = 2; i < p.length; i += 2) shape.lineTo(p[i], p[i + 1]);
            shape.close();
            shapeW = w;
            shapeH = h;
            shapeCorner = corner;
        }
        if (fittedW != w || fittedH != h) fitLabels(w, h);

        float scale = ControlStyle.scale(press, motion);
        int save = canvas.save();
        canvas.scale(scale, scale, w / 2f, h / 2f);

        fillPaint.setColor(ControlStyle.fill(baseFill, press, hold, on));
        canvas.drawPath(shape, fillPaint);
        if (ringDp > 0f) {
            ringPaint.setColor(ControlStyle.ring(baseRing, press, hold, on));
            ringPaint.setStrokeWidth(ControlStyle.ringWidth(ringDp * dp, dp, press, hold, on));
            canvas.drawPath(shape, ringPaint);
        }

        int ink = ControlStyle.ink(press, hold, on);
        int labelInk = ControlStyle.label(press, hold, on);
        float wDp = w / dp, hDp = h / dp;
        float glyphPx = ControlStyle.glyphDp(wDp, hDp) * dp;
        boolean hasGlyph = glyph != null;
        boolean altText = alt > 0.5f && !altLabel.isEmpty();
        String shownLabel = altText ? altLabel : label;
        float shownLabelPx = altText ? altLabelPx : labelPx;
        boolean hasLabel = !shownLabel.isEmpty();
        float capH = shownLabelPx * 0.72f;
        float gap = 3f * dp;
        float block = (hasGlyph ? glyphPx : 0f) + (hasLabel ? capH + (hasGlyph ? gap : 0f) : 0f);
        float top = (h - block) / 2f;

        if (hasGlyph) {
            int gl = Math.round((w - glyphPx) / 2f);
            int gt = Math.round(top);
            int gs = Math.round(glyphPx);
            if (!glyphInked || glyphInk != ink) {
                glyph.setTint(ink);
                glyphInk = ink;
                glyphInked = true;
            }
            drawGlyph(canvas, glyph, gl, gt, gs, altGlyph != null ? 1f - alt : 1f, 1f - 0.15f * alt);
            if (altGlyph != null && alt > 0f) {
                if (!altGlyphInked || altGlyphInk != ink) {
                    altGlyph.setTint(ink);
                    altGlyphInk = ink;
                    altGlyphInked = true;
                }
                drawGlyph(canvas, altGlyph, gl, gt, gs, alt, 0.85f + 0.15f * alt);
            }
        }
        if (hasLabel) {
            labelPaint.setTextSize(shownLabelPx);
            labelPaint.setColor(labelInk);
            float baseline = top + (hasGlyph ? glyphPx + gap : 0f) + capH;
            canvas.drawText(shownLabel, w / 2f, baseline, labelPaint);
        }
        canvas.restoreToCount(save);
        if (moving) view.postInvalidateOnAnimation();
    }

    private static void drawGlyph(Canvas canvas, Drawable d, int left, int top, int size, float alpha, float scale) {
        if (alpha <= 0.01f) return;
        d.setBounds(left, top, left + size, top + size);
        d.setAlpha(Math.round(255 * Math.min(1f, alpha)));
        if (scale != 1f) {
            int s = canvas.save();
            canvas.scale(scale, scale, left + size / 2f, top + size / 2f);
            d.draw(canvas);
            canvas.restoreToCount(s);
        } else {
            d.draw(canvas);
        }
    }

    private void fitLabels(int w, int h) {
        float wDp = w / dp, hDp = h / dp;
        float start = (glyph == null ? 12f : ControlStyle.labelSp(wDp, hDp)) * sp;
        float max = w - 8f * dp;
        ControlStyle.Measure measure = (text, size) -> {
            labelPaint.setTextSize(size);
            return labelPaint.measureText(text);
        };
        float floor = Math.min(start, ControlStyle.MIN_LABEL_DP * dp);
        labelPx = label.isEmpty() ? start : ControlStyle.fit(measure, label, max, start, floor);
        altLabelPx = altLabel.isEmpty() ? labelPx : ControlStyle.fit(measure, altLabel, max, start, floor);
        fittedW = w;
        fittedH = h;
    }
}
