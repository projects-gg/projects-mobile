package gg.projects.mobile.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A player head drawn crisp (M14; design.md's PlayerHead: square pixel art, small radius): the
 * face is scaled by nearest neighbour to a size that is a whole multiple of 8 pixels, so every
 * skin pixel becomes the same number of screen pixels, centred in the bounds. The corners are
 * rounded with an anti-aliased edge while the pixels inside stay sharp.
 */
public final class PixelHeadDrawable extends Drawable {
    private final Bitmap mHead;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final RectF mRect = new RectF();
    private final int mSizePx;
    private final float mRadiusPx;

    /**
     * @param head  the face bitmap ({@code AccountHeads} stores 96 × 96: 8 × 8 skin pixels of 12)
     * @param sizePx the wanted size; rounded to the nearest multiple of 8 (at least 8)
     */
    public PixelHeadDrawable(@NonNull Bitmap head, int sizePx, float radiusPx) {
        mHead = head;
        mSizePx = PixelMath.crispHeadSize(sizePx);
        mRadiusPx = radiusPx;
        BitmapShader shader = new BitmapShader(head, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        mPaint.setShader(shader);
        mPaint.setFilterBitmap(false);
        mPaint.setDither(false);
    }


    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        float left = bounds.left + (bounds.width() - mSizePx) / 2;
        float top = bounds.top + (bounds.height() - mSizePx) / 2;
        // Whole pixels: a half-pixel offset would blur a column of every skin pixel.
        left = Math.round(left);
        top = Math.round(top);
        mRect.set(left, top, left + mSizePx, top + mSizePx);
        mMatrix.setScale(mSizePx / (float) mHead.getWidth(), mSizePx / (float) mHead.getHeight());
        mMatrix.postTranslate(left, top);
        Shader shader = mPaint.getShader();
        shader.setLocalMatrix(mMatrix);
        // Before Android 8 the paint keeps the shader as it was when set: set it again.
        mPaint.setShader(shader);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mHead.isRecycled()) return;
        canvas.drawRoundRect(mRect, mRadiusPx, mRadiusPx, mPaint);
    }

    @Override
    public int getIntrinsicWidth() {
        return mSizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSizePx;
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
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
