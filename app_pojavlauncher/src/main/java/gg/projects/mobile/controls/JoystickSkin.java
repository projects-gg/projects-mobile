package gg.projects.mobile.controls;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import net.kdt.pojavlaunch.customcontrols.ControlData;

import io.github.controlwear.virtual.joystick.android.JoystickView;

/**
 * The Projects look of the joystick (M15): a dark translucent base with a bone ring and four
 * small chevrons, a bone knob; while a thumb is on it the ring brightens, the knob turns gold and
 * the chevrons of the direction it walks light up. The library draws the base and the knob with
 * the colours set here; only the chevrons are drawn on top.
 */
public final class JoystickSkin {
    private static final int KNOB = 0xE6F3EBDD; // bone 90 %
    private static final int KNOB_ACTIVE = 0xFFF6D58E; // gold-300
    private static final int CHEVRON = 0x6BF3EBDD; // bone 42 %
    private static final int CHEVRON_LIT = 0xFFF6D58E;
    private static final int RING_ACTIVE = 0xB3F3EBDD; // bone 70 %
    public static final float BASE_RATIO = ControlStyle.STICK_BASE_RATIO;
    public static final float KNOB_RATIO = ControlStyle.STICK_KNOB_RATIO;

    private final JoystickView view;
    private final float dp;
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private int baseFill, baseRing;
    private float ringDp;
    private boolean active;
    private int direction = -1;

    public JoystickSkin(JoystickView view) {
        this.view = view;
        dp = view.getResources().getDisplayMetrics().density;
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        view.setButtonSizeRatio(KNOB_RATIO);
        view.setBackgroundSizeRatio(BASE_RATIO);
    }

    /** True for a joystick the layout marks with {@link ControlStyle#STICK}. */
    public static boolean wants(ControlData data) {
        return data != null && ControlStyle.STICK.equals(data.icon);
    }

    /** The joystick's own colours (its fill, ring and ring width) and the current state. */
    public void apply(ControlData data) {
        baseFill = data.bgColor;
        baseRing = data.strokeColor;
        ringDp = Math.max(1f, data.strokeWidth);
        paint();
    }

    private void paint() {
        view.setBackgroundColor(baseFill);
        int ring = active ? RING_ACTIVE : baseRing;
        // The library paints the ring at its own alpha (opaque by default) over the colour's.
        view.setBorderAlpha(ring >>> 24);
        view.setBorderColor(ring);
        view.setBorderWidth(Math.round((active ? ringDp + 0.5f : ringDp) * dp));
        view.setButtonColor(active ? KNOB_ACTIVE : KNOB);
        view.invalidate();
    }

    /** A thumb came down or left. */
    public void setActive(boolean value) {
        if (active == value) return;
        active = value;
        if (!value) direction = -1;
        paint();
    }

    /** The walking direction (GamepadJoystick's 0 = east, counter-clockwise, -1 none). */
    public void setDirection(int value) {
        if (direction == value) return;
        direction = value;
        view.invalidate();
    }

    /** Draws the chevrons after the library drew the base and the knob. */
    public void drawOver(Canvas canvas) {
        int w = view.getWidth(), h = view.getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f * BASE_RATIO;
        float d = radius - 11f * dp;
        float half = 4.5f * dp;
        chevronPaint.setStrokeWidth(2f * dp);
        for (int quarter = 0; quarter < 4; quarter++) {
            // 0 east, 1 north, 2 west, 3 south; a diagonal lights both of its neighbours.
            boolean lit = active && ControlStyle.chevronLit(direction, quarter);
            chevronPaint.setColor(lit ? CHEVRON_LIT : CHEVRON);
            double angle = Math.toRadians(quarter * 90.0);
            float x = cx + (float) Math.cos(angle) * d;
            float y = cy - (float) Math.sin(angle) * d;
            chevron.reset();
            // An arrow head pointing away from the centre.
            float ox = (float) Math.cos(angle), oy = (float) -Math.sin(angle);
            float px = -oy, py = ox;
            chevron.moveTo(x - ox * half * 0.55f + px * half, y - oy * half * 0.55f + py * half);
            chevron.lineTo(x + ox * half * 0.55f, y + oy * half * 0.55f);
            chevron.lineTo(x - ox * half * 0.55f - px * half, y - oy * half * 0.55f - py * half);
            canvas.drawPath(chevron, chevronPaint);
        }
    }
}
