package gg.projects.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

/**
 * Stands in for an account's head until there is one (M14). An offline account's default head is
 * cut from a game jar on the phone, so a fresh install has none until the game is downloaded; a
 * rounded obsidian tile with the account glyph keeps the top bar's chip and the account list in
 * shape. Same size and corners as {@link PixelHeadDrawable}.
 */
public final class HeadPlaceholderDrawable extends Drawable {
    private final Paint mTile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    @Nullable private final Drawable mGlyph;
    private final int mSize;
    private final float mRadius;

    public HeadPlaceholderDrawable(Context context, int sizePx, float radiusPx) {
        mSize = sizePx;
        mRadius = radiusPx;
        mTile.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_700));
        Drawable glyph = ContextCompat.getDrawable(context, R.drawable.projects_account_head_glyph);
        mGlyph = glyph == null ? null : glyph.mutate();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        mRect.set(bounds);
        canvas.drawRoundRect(mRect, mRadius, mRadius, mTile);
        if (mGlyph == null) return;
        int inset = Math.round(bounds.width() / 6f);
        mGlyph.setBounds(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        mGlyph.draw(canvas);
    }

    @Override
    public int getIntrinsicWidth() {
        return mSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSize;
    }

    @Override
    public void setAlpha(int alpha) {
        mTile.setAlpha(alpha);
        if (mGlyph != null) mGlyph.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mTile.setColorFilter(colorFilter);
        if (mGlyph != null) mGlyph.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
