package gg.projects.mobile.cloud.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import gg.projects.mobile.cloud.CloudStatus;

/**
 * The desktop's server bars (SignalBars, docs/design.md §6, DOT1) as a drawable: five pixel bars rising left to
 * right, gold faces with a one-pixel shade below right when lit, dim where nothing is lit, and a flare pixel cross when
 * a stop needs the player. A 15 × 8 pixel grid drawn at a whole-pixel scale, so every edge is crisp. Static: no
 * blinking (the words beside it say the state; TalkBack reads those).
 *
 * <p>In the game: all five lit, green (the desktop's tone for five bars, SMALL10b 25 Oct: gold bars read as a high
 * ping and the game draws its own bars green). On the way (connecting): the first two in the hotter gold, the
 * launcher's "working". Idle: none lit. Stopped by a problem: none lit, dimmer, with the cross.
 */
public final class SignalGlyph extends Drawable {
    private static final int W = 15;
    private static final int H = 8;
    private static final int[][] CROSS = {{0, 0}, {4, 0}, {1, 1}, {3, 1}, {2, 2}, {1, 3}, {3, 3}, {0, 4}, {4, 4}};

    private static final int LIT_FACE = 0xFF5FCB8E;    // jade-400
    private static final int LIT_SHADE = 0xFF1B6843;   // jade-700
    private static final int HOT_FACE = 0xFFFAE5B3;    // gold-200
    private static final int HOT_SHADE = 0xFFC9741A;   // gold-600
    private static final int DIM_FACE = 0x33FFFFFF;
    private static final int DIM_SHADE = 0x14FFFFFF;
    private static final int OFF_FACE = 0x1FFFFFFF;
    private static final int OFF_SHADE = 0x0DFFFFFF;
    private static final int CROSS_COLOR = 0xFFF0624D; // flare-400

    private final Paint mPaint = new Paint();
    private final int mUnit;
    private CloudStatus.Signal mSignal = CloudStatus.Signal.IDLE;

    /** {@code unit}: the pixel size in screen pixels (a whole number keeps the edges crisp). */
    public SignalGlyph(int unit) {
        mUnit = Math.max(1, unit);
        mPaint.setAntiAlias(false);
    }

    public void setSignal(CloudStatus.Signal signal) {
        if (mSignal == signal) return;
        mSignal = signal;
        invalidateSelf();
    }

    @Override
    public int getIntrinsicWidth() {
        return W * mUnit;
    }

    @Override
    public int getIntrinsicHeight() {
        return H * mUnit;
    }

    private void cell(Canvas canvas, Rect b, int x, int y, int w, int h, int color) {
        mPaint.setColor(color);
        canvas.drawRect(b.left + x * mUnit, b.top + y * mUnit, b.left + (x + w) * mUnit, b.top + (y + h) * mUnit, mPaint);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        boolean offline = mSignal == CloudStatus.Signal.OFFLINE;
        for (int i = 0; i < 5; i++) {
            int face;
            int shade;
            if (mSignal == CloudStatus.Signal.ONLINE) {
                face = LIT_FACE;
                shade = LIT_SHADE;
            } else if (mSignal == CloudStatus.Signal.PENDING && i < 2) {
                face = HOT_FACE;
                shade = HOT_SHADE;
            } else {
                face = offline ? OFF_FACE : DIM_FACE;
                shade = offline ? OFF_SHADE : DIM_SHADE;
            }
            cell(canvas, b, 3 * i + 1, 5 - i, 2, 3 + i, shade);
            cell(canvas, b, 3 * i, 4 - i, 2, 3 + i, face);
        }
        if (offline) for (int[] p : CROSS) cell(canvas, b, p[0], p[1], 1, 1, CROSS_COLOR);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
