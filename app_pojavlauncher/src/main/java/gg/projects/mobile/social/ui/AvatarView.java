package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;


import gg.projects.mobile.social.SocialModel.Profile;

/**
 * A player's picture in the frame of the app's player heads (a soft square), with the presence dot
 * on its corner like the desktop's SocialAvatar: filled jade in game, a jade ring in the launcher,
 * gold with a bar on do-not-disturb, nothing offline (the face dims instead). Shape and the text next
 * to it carry the meaning, never the colour alone. Minecraft heads stay pixel-sharp.
 */
public final class AvatarView extends View {
    public enum Dot { NONE, PLAYING, LAUNCHER, DND, INVISIBLE }

    private final Paint mFace = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLetter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Matrix mMatrix = new Matrix();
    private final float mDp;
    @Nullable
    private Bitmap mBitmap;
    private boolean mPixelArt;
    @Nullable
    private String mKey;
    private String mLetterText = "";
    private Dot mDotKind = Dot.NONE;
    private boolean mDimmed;
    private int mSurface;

    public AvatarView(Context context) {
        this(context, null);
    }

    public AvatarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mTile.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_700));
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(Math.max(1f, mDp));
        mRing.setColor(0x1AFFFFFF);
        mLetter.setColor(ContextCompat.getColor(context, R.color.projects_gold_300));
        mLetter.setTextAlign(Paint.Align.CENTER);
        Typeface figtree = null;
        try {
            figtree = ResourcesCompat.getFont(context, R.font.figtree_semibold);
        } catch (RuntimeException ignored) {
            // The platform font then.
        }
        if (figtree != null) mLetter.setTypeface(figtree);
        mSurface = ContextCompat.getColor(context, R.color.projects_obsidian_900);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The colour behind the avatar (the dot's ring). */
    public void setSurfaceColor(int color) {
        mSurface = color;
        invalidate();
    }

    public void setProfile(@Nullable Profile profile) {
        if (profile == null) {
            mKey = null;
            mBitmap = null;
            mLetterText = "";
            invalidate();
            return;
        }
        String key = profile.id + "|" + profile.faceKey();
        String name = profile.name().trim();
        // Upper case in the app's language (Turkish "i" is "İ", English "i" is "I").
        mLetterText = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1))
                .toUpperCase(gg.projects.mobile.language.LanguageManager.effectiveLocale(getContext()));
        if (key.equals(mKey) && mBitmap != null) return;
        mKey = key;
        Bitmap cached = SocialFaces.cached(profile);
        mPixelArt = SocialFaces.pixelArt(profile);
        setBitmap(cached);
        if (cached == null) {
            final String wanted = key;
            SocialFaces.load(getContext(), profile, (bitmap, pixelArt) -> {
                if (!wanted.equals(mKey)) return;
                mPixelArt = pixelArt;
                setBitmap(bitmap);
            });
        }
    }

    private void setBitmap(@Nullable Bitmap bitmap) {
        mBitmap = bitmap;
        if (bitmap != null) {
            mFace.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            mFace.setFilterBitmap(!mPixelArt);
        } else {
            mFace.setShader(null);
        }
        invalidate();
    }

    public void setDot(Dot dot) {
        if (dot == mDotKind) return;
        mDotKind = dot;
        invalidate();
    }

    /** Offline faces dim (the desktop's 55 % and 45 % less colour). */
    public void setDimmed(boolean dimmed) {
        if (dimmed == mDimmed) return;
        mDimmed = dimmed;
        if (dimmed) {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0.55f);
            mFace.setColorFilter(new ColorMatrixColorFilter(matrix));
        } else {
            mFace.setColorFilter(null);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        // The dot hangs a third of itself over the corner: room for it is kept on the right and bottom.
        float size = (Math.min(w, h) - 2 * mDp) / 1.1f;
        if (size <= 0) return;
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float radius = size * 0.2f;
        mRect.set(left, top, left + size, top + size);
        int alpha = mDimmed ? 140 : 255;
        if (mBitmap != null) {
            float scale = Math.max(size / mBitmap.getWidth(), size / mBitmap.getHeight());
            mMatrix.setScale(scale, scale);
            mMatrix.postTranslate(left + (size - mBitmap.getWidth() * scale) / 2f, top + (size - mBitmap.getHeight() * scale) / 2f);
            mFace.getShader().setLocalMatrix(mMatrix);
            mFace.setAlpha(alpha);
            canvas.drawRoundRect(mRect, radius, radius, mFace);
        } else {
            mTile.setAlpha(alpha);
            canvas.drawRoundRect(mRect, radius, radius, mTile);
            mLetter.setTextSize(size * 0.46f);
            mLetter.setAlpha(alpha);
            Paint.FontMetrics m = mLetter.getFontMetrics();
            canvas.drawText(mLetterText, mRect.centerX(), mRect.centerY() - (m.ascent + m.descent) / 2f, mLetter);
        }
        canvas.drawRoundRect(mRect, radius, radius, mRing);
        drawDot(canvas, left + size, top + size, size);
    }

    private void drawDot(Canvas canvas, float right, float bottom, float size) {
        if (mDotKind == Dot.NONE) return;
        float dot = Math.max(9 * mDp, size * 0.3f);
        float r = dot / 2f;
        // The desktop's place: the dot's box ends a third of the dot beyond the corner.
        float cx = right - dot / 6f;
        float cy = bottom - dot / 6f;
        // The ring in the colour behind, so the dot reads on any picture.
        mDot.setStyle(Paint.Style.FILL);
        mDot.setColor(mSurface);
        canvas.drawCircle(cx, cy, r + 2 * mDp, mDot);
        int jade = ContextCompat.getColor(getContext(), R.color.projects_jade_400);
        switch (mDotKind) {
            case PLAYING:
                mDot.setColor(jade);
                canvas.drawCircle(cx, cy, r, mDot);
                break;
            case LAUNCHER:
                mDot.setStyle(Paint.Style.STROKE);
                mDot.setStrokeWidth(2 * mDp);
                mDot.setColor(jade);
                canvas.drawCircle(cx, cy, r - mDp, mDot);
                break;
            case INVISIBLE:
                mDot.setStyle(Paint.Style.STROKE);
                mDot.setStrokeWidth(2 * mDp);
                mDot.setColor(ContextCompat.getColor(getContext(), R.color.projects_ash_400));
                canvas.drawCircle(cx, cy, r - mDp, mDot);
                break;
            case DND:
                mDot.setColor(ContextCompat.getColor(getContext(), R.color.projects_gold_400));
                canvas.drawCircle(cx, cy, r, mDot);
                mDot.setColor(ContextCompat.getColor(getContext(), R.color.projects_obsidian_950));
                float bar = Math.max(4 * mDp, r);
                canvas.drawRoundRect(cx - bar / 2f, cy - mDp, cx + bar / 2f, cy + mDp, mDp, mDp, mDot);
                break;
            default:
                break;
        }
    }
}
