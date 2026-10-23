package gg.projects.mobile.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One of the desktop's content sprites ({@link PixelSprites}, ICON2), drawn crisp: every sprite
 * pixel is the same whole number of screen pixels (the largest 16 × n that fits the bounds),
 * centred on whole pixels, as plain rectangles without anti-aliasing, so nothing is ever blurred or
 * resampled. No intrinsic size: an ImageView gives it its content box (no scaling matrix), a
 * compound drawable the bounds it is given. Never tinted (the tones are the sprite's own).
 */
public final class PixelSpriteDrawable extends Drawable {
    private final PixelSprites.Sprite mSprite;
    private final boolean mQuiet;
    private final Paint mPaint = new Paint();
    private int mAlpha = 255;
    private int mScale = 1;
    private int mLeft;
    private int mTop;

    /** @param quiet one gold step darker (a mark at rest, the desktop's {@code quiet}) */
    public PixelSpriteDrawable(@NonNull PixelSprites.Sprite sprite, boolean quiet) {
        mSprite = sprite;
        mQuiet = quiet;
        mPaint.setAntiAlias(false);
        mPaint.setDither(false);
        mPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        mScale = PixelSprites.scale(Math.min(bounds.width(), bounds.height()));
        int size = 16 * mScale;
        mLeft = bounds.left + (bounds.width() - size) / 2;
        mTop = bounds.top + (bounds.height() - size) / 2;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        int s = mScale;
        if (mSprite.hasShadow()) {
            fill(PixelSprites.color('o', mQuiet));
            for (int[] run : mSprite.shadowRuns()) {
                canvas.drawRect(mLeft + run[0] * s, mTop + run[1] * s, mLeft + (run[0] + run[2]) * s,
                        mTop + (run[1] + 1) * s, mPaint);
            }
        }
        for (int[] run : mSprite.runs()) {
            fill(PixelSprites.color((char) run[3], mQuiet));
            canvas.drawRect(mLeft + run[0] * s, mTop + run[1] * s, mLeft + (run[0] + run[2]) * s,
                    mTop + (run[1] + 1) * s, mPaint);
        }
    }

    private void fill(int color) {
        mPaint.setColor(color);
        if (mAlpha != 255) mPaint.setAlpha(Color.alpha(color) * mAlpha / 255);
    }

    @Override
    public void setAlpha(int alpha) {
        if (alpha == mAlpha) return;
        mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
