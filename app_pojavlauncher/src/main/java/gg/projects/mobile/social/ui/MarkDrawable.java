package gg.projects.mobile.social.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import gg.projects.mobile.social.MarkCatalog;
import gg.projects.mobile.social.MarkGeometry;

/**
 * BADGES (docs: badges.md "Görsel sistem", design.md "Profile badges"): one profile badge, drawn from the drawing made
 * for its pixel grid ({@link MarkGeometry}: 12, 14 or 16): the outline filled with the badge's colour, the glyph white
 * (filled, or the check stroked with round caps and joins). No motion of its own.
 *
 * <p>Right of a name it is the name's end compound drawable ({@link #anchor}): the TextView keeps its room, so the badge
 * stays whole while the name shortens, and when the name's box is wider than its text (a weighted width) or the name
 * wraps, the drawing moves to sit 4 dp after the end of the text's last line, mirrored right to left. An icon that
 * shared that place before (the chat list's muted bell, {@link #setTrailing}) stays at the box's end after it.
 */
public final class MarkDrawable extends Drawable {
    private final MarkGeometry.Drawing mDrawing;
    private final Path mOutline;
    private final Path mGlyph;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The badge's square, in pixels. */
    private final int mSize;
    /** Which badge it draws (key and look): a row bound again with the same one keeps it. */
    private final String mLook;
    private int mAlpha = 255;
    @Nullable
    private WeakReference<TextView> mAnchor;
    @Nullable
    private Drawable mTrailing;
    private int mTrailingGap;

    private MarkDrawable(MarkGeometry.Drawing drawing, int color, int sizePx, String look) {
        mDrawing = drawing;
        mOutline = path(drawing.outline);
        mGlyph = path(drawing.glyph);
        mSize = sizePx;
        mLook = look;
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor(color);
        mGlyphPaint.setColor(0xffffffff);
        if (drawing.stroke > 0) {
            mGlyphPaint.setStyle(Paint.Style.STROKE);
            mGlyphPaint.setStrokeWidth(drawing.stroke);
            mGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
            mGlyphPaint.setStrokeJoin(Paint.Join.ROUND);
        } else {
            mGlyphPaint.setStyle(Paint.Style.FILL);
        }
        setBounds(0, 0, sizePx, sizePx);
    }

    /** {@code def} drawn {@code dp} tall (12, 14 or 16); null when this version has no drawing for its look. */
    @Nullable
    public static MarkDrawable of(MarkCatalog.Def def, int dp, float density) {
        MarkGeometry.Drawing drawing = MarkGeometry.drawing(MarkGeometry.gridFor(dp), def.shape, def.glyph);
        if (drawing == null) return null;
        return new MarkDrawable(drawing, def.argb(), Math.round(dp * density), lookOf(def));
    }

    private static String lookOf(MarkCatalog.Def def) {
        return def.key + "/" + def.color + "/" + def.glyph + "/" + def.shape;
    }

    /** Whether this draws {@code def} {@code sizePx} tall (a row bound again keeps it). */
    boolean draws(MarkCatalog.Def def, int sizePx) {
        return mSize == sizePx && mLook.equals(lookOf(def));
    }

    /** The badge sits after the end of {@code name}'s text (its end compound drawable). */
    void anchor(@Nullable TextView name) {
        mAnchor = name == null ? null : new WeakReference<>(name);
    }

    /**
     * An icon kept at the end of the name's box, {@code gapPx} at least after the badge (null: none). True when the
     * drawable's width changed: the TextView must be given it again.
     */
    boolean setTrailing(@Nullable Drawable icon, int gapPx) {
        int gap = icon == null ? 0 : gapPx;
        if (icon == mTrailing && gap == mTrailingGap) return false;
        mTrailing = icon;
        mTrailingGap = gap;
        setBounds(0, 0, getIntrinsicWidth(), mSize);
        return true;
    }

    private static Path path(float[] commands) {
        Path path = new Path();
        int i = 0;
        while (i < commands.length) {
            int op = (int) commands[i++];
            switch (op) {
                case MarkGeometry.MOVE:
                    path.moveTo(commands[i], commands[i + 1]);
                    i += 2;
                    break;
                case MarkGeometry.LINE:
                    path.lineTo(commands[i], commands[i + 1]);
                    i += 2;
                    break;
                case MarkGeometry.QUAD:
                    path.quadTo(commands[i], commands[i + 1], commands[i + 2], commands[i + 3]);
                    i += 4;
                    break;
                case MarkGeometry.CUBIC:
                    path.cubicTo(commands[i], commands[i + 1], commands[i + 2], commands[i + 3], commands[i + 4], commands[i + 5]);
                    i += 6;
                    break;
                default:
                    path.close();
                    break;
            }
        }
        return path;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        TextView anchor = mAnchor == null ? null : mAnchor.get();
        boolean rtl = anchor != null && anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        // The badge takes the side nearest the text (the left, or the right right to left), the trailing icon the other.
        float badgeLeft = rtl ? b.right - mSize : b.left;
        float dx = 0f;
        float dy = 0f;
        if (anchor != null) {
            Layout layout = anchor.getLayout();
            if (layout != null && layout.getLineCount() > 0) {
                dx = shiftX(anchor, layout, badgeLeft - b.left, rtl);
                dy = shiftY(anchor, layout);
            }
        }
        int save = canvas.save();
        canvas.translate(badgeLeft + dx, b.top + dy);
        float scale = mSize / (float) mDrawing.grid;
        canvas.scale(scale, scale);
        canvas.drawPath(mOutline, mFill);
        canvas.drawPath(mGlyph, mGlyphPaint);
        canvas.restoreToCount(save);
        Drawable trailing = mTrailing;
        if (trailing != null) {
            Rect t = trailing.getBounds();
            int at = canvas.save();
            canvas.translate((rtl ? b.left : b.right - t.width()) - t.left, b.top + (mSize - t.height()) / 2f - t.top);
            trailing.draw(canvas);
            canvas.restoreToCount(at);
        }
    }

    /**
     * How far the badge moves from where TextView puts the end drawable ({@code inside}: the badge's offset in it) to 4 dp
     * after the end of the text's last line; never past the box's end.
     */
    private float shiftX(TextView view, Layout layout, float inside, boolean rtl) {
        int last = layout.getLineCount() - 1;
        int gap = view.getCompoundDrawablePadding();
        int textLeft = view.getCompoundPaddingLeft();
        if (rtl) {
            // The end drawable is on the left: the badge's right edge goes to the line's left end, less the gap.
            float natural = view.getPaddingLeft() + inside;
            float wanted = textLeft + layout.getLineLeft(last) - gap - mSize;
            return Math.max(0f, wanted - natural);
        }
        float natural = view.getWidth() - view.getPaddingRight() - getBounds().width() + inside;
        float wanted = textLeft + layout.getLineRight(last) + gap;
        return Math.min(0f, wanted - natural);
    }

    /** How far the badge moves from the middle of the whole text to the middle of its last line (a wrapped name). */
    private static float shiftY(TextView view, Layout layout) {
        int last = layout.getLineCount() - 1;
        if (last == 0) return 0f;
        int top = view.getCompoundPaddingTop();
        int vspace = view.getHeight() - view.getCompoundPaddingBottom() - top;
        float natural = top + vspace / 2f;
        float wanted = view.getTotalPaddingTop() + (layout.getLineTop(last) + layout.getLineBottom(last)) / 2f;
        return wanted - natural;
    }

    @Override
    public void setAlpha(int alpha) {
        if (alpha == mAlpha) return;
        mAlpha = alpha;
        mFill.setAlpha(alpha);
        // The glyph's white fades with the badge (it is drawn over the colour, not cut out of it).
        mGlyphPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mFill.setColorFilter(colorFilter);
        mGlyphPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        Drawable trailing = mTrailing;
        return trailing == null ? mSize : mSize + mTrailingGap + trailing.getBounds().width();
    }

    @Override
    public int getIntrinsicHeight() {
        return mSize;
    }
}
