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
 * wraps, the drawing moves to sit 4 dp after the end of the text's last line, mirrored right to left.
 */
public final class MarkDrawable extends Drawable {
    private final MarkGeometry.Drawing mDrawing;
    private final Path mOutline;
    private final Path mGlyph;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mSize;
    private int mAlpha = 255;
    @Nullable
    private WeakReference<TextView> mAnchor;

    private MarkDrawable(MarkGeometry.Drawing drawing, int color, int sizePx) {
        mDrawing = drawing;
        mOutline = path(drawing.outline);
        mGlyph = path(drawing.glyph);
        mSize = sizePx;
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
        return new MarkDrawable(drawing, def.argb(), Math.round(dp * density));
    }

    /** The badge sits after the end of {@code name}'s text (its end compound drawable). */
    void anchor(@Nullable TextView name) {
        mAnchor = name == null ? null : new WeakReference<>(name);
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
        int save = canvas.save();
        TextView anchor = mAnchor == null ? null : mAnchor.get();
        if (anchor != null) shiftToText(canvas, anchor, b);
        canvas.translate(b.left, b.top);
        float scale = b.width() / (float) mDrawing.grid;
        canvas.scale(scale, b.height() / (float) mDrawing.grid);
        canvas.drawPath(mOutline, mFill);
        canvas.drawPath(mGlyph, mGlyphPaint);
        canvas.restoreToCount(save);
    }

    /**
     * Moves the end drawable from where TextView puts it (the box's end, centred on the whole text) to 4 dp after the end
     * of the last line, centred on that line; never past the box's end.
     */
    private void shiftToText(Canvas canvas, TextView view, Rect b) {
        Layout layout = view.getLayout();
        if (layout == null || layout.getLineCount() == 0) return;
        int last = layout.getLineCount() - 1;
        int gap = view.getCompoundDrawablePadding();
        int textLeft = view.getCompoundPaddingLeft();
        float dx;
        if (view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            // The end drawable is on the left: its right edge goes to the line's left end, less the gap.
            float natural = view.getPaddingLeft();
            float wanted = textLeft + layout.getLineLeft(last) - gap - b.width();
            dx = Math.max(0f, wanted - natural);
        } else {
            float natural = view.getWidth() - view.getPaddingRight() - b.width();
            float wanted = textLeft + layout.getLineRight(last) + gap;
            dx = Math.min(0f, wanted - natural);
        }
        float dy = 0f;
        if (last > 0) {
            int top = view.getCompoundPaddingTop();
            int vspace = view.getHeight() - view.getCompoundPaddingBottom() - top;
            float natural = top + vspace / 2f;
            float wanted = view.getTotalPaddingTop() + (layout.getLineTop(last) + layout.getLineBottom(last)) / 2f;
            dy = wanted - natural;
        }
        if (dx != 0f || dy != 0f) canvas.translate(dx, dy);
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
        return mSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSize;
    }
}
