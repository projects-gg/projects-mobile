package gg.projects.mobile.cloud.inventory;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.cloud.items.ItemArt;

/**
 * Game text and game slots outside the window (docs/design.md §6): an item's tooltip as the head of its action sheet
 * (the game's colours and drop shadows on the sheet's own surface, lines 10 GUI pixels apart, the name 2 more, wrapped at
 * spaces), and the hand slot that holds a stack carried under a finger. Whole device pixels per GUI pixel.
 */
public final class PixelLines {
    private PixelLines() {}

    /** Draws {@code text} from the pixel bitmaps at (x, y) device pixels, {@code unit} device pixels per GUI pixel. */
    static void draw(Canvas c, Paint p, String text, float x, float y, int unit, int rgb, int shadow, boolean italic) {
        if (shadow >= 0) {
            p.setColor(0xff000000 | shadow);
            PixelFont.runs(text, (rx, ry, rw) -> {
                float lean = italic ? Math.round((0.875f - 0.25f * ry) * unit) : 0;
                c.drawRect(x + (rx + 1) * unit + lean, y + (ry + 1) * unit, x + (rx + 1 + rw) * unit + lean, y + (ry + 2) * unit, p);
            });
        }
        p.setColor(0xff000000 | rgb);
        PixelFont.runs(text, (rx, ry, rw) -> {
            float lean = italic ? Math.round((0.875f - 0.25f * ry) * unit) : 0;
            c.drawRect(x + rx * unit + lean, y + ry * unit, x + (rx + rw) * unit + lean, y + (ry + 1) * unit, p);
        });
    }

    /** An item's tooltip lines, wrapped to the width it is given, drawn in the game's colours. */
    public static final class Tooltip extends View {
        private final Paint mPaint = new Paint();
        private final int mUnit;
        private List<InvText.Line> mLines = Collections.emptyList();
        private final List<String> mWrapped = new ArrayList<>();
        private final List<InvText.Line> mOf = new ArrayList<>();

        public Tooltip(Context context, int unit) {
            super(context);
            mUnit = Math.max(1, unit);
            mPaint.setAntiAlias(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        public void setLines(List<InvText.Line> lines) {
            mLines = lines;
            requestLayout();
            invalidate();
        }

        private void wrap(int widthPx) {
            mWrapped.clear();
            mOf.clear();
            int gui = Math.max(16, widthPx / mUnit);
            for (InvText.Line line : mLines) {
                for (String part : PixelFont.wrap(line.text, gui)) {
                    mWrapped.add(part);
                    mOf.add(line);
                }
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            wrap(width - getPaddingLeft() - getPaddingRight());
            int rows = mWrapped.size();
            // Lines 10 apart, the name 2 more; the last line is 8 tall.
            int gui = rows == 0 ? 0 : (rows - 1) * PixelFont.LINE_BOX + 8 + (rows > 1 ? 2 : 0) + 1;
            setMeasuredDimension(width, gui * mUnit + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float y = getPaddingTop();
            for (int i = 0; i < mWrapped.size(); i++) {
                InvText.Line line = mOf.get(i);
                PixelLines.draw(canvas, mPaint, mWrapped.get(i), getPaddingLeft(), y, mUnit, line.color, InvText.shadowOf(line.color), line.italic);
                y += PixelFont.LINE_BOX * mUnit;
                // The name's line stands 2 GUI pixels apart from the rest.
                if (line == mLines.get(0) && i + 1 < mOf.size() && mOf.get(i + 1) != line) y += 2 * mUnit;
            }
        }
    }

    /** One sunken slot of the game's, alone, with the carried stack: a tap puts it back. */
    public static final class HandSlot extends View {
        private final Paint mPaint = new Paint();
        private int mUnit = 1;
        private ItemArt mArt = ItemArt.standIns();
        private InvItem mItem;
        private boolean mWaiting;

        public HandSlot(Context context) {
            super(context);
            mPaint.setAntiAlias(false);
            setClickable(true);
            setFocusable(true);
        }

        public void show(InvItem item, ItemArt art, int unit, boolean waiting) {
            boolean resize = unit != mUnit;
            mItem = item;
            mArt = art;
            mUnit = Math.max(1, unit);
            mWaiting = waiting;
            if (resize) requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(18 * mUnit, 18 * mUnit);
        }

        private void rect(Canvas c, int color, int x, int y, int w, int h) {
            mPaint.setColor(color);
            c.drawRect(x * mUnit, y * mUnit, (x + w) * mUnit, (y + h) * mUnit, mPaint);
        }

        @Override
        protected void onDraw(Canvas c) {
            rect(c, 0xff8b8b8b, 0, 0, 18, 18);
            rect(c, 0xff373737, 0, 0, 17, 1);
            rect(c, 0xff373737, 0, 1, 1, 16);
            rect(c, 0xffffffff, 1, 17, 17, 1);
            rect(c, 0xffffffff, 17, 1, 1, 16);
            if (mItem == null) return;
            int save = mWaiting ? c.saveLayerAlpha(0, 0, getWidth(), getHeight(), 153) : -1;
            mArt.draw(c, mItem.id, mItem.color, mItem.potion, mItem.glints(), mUnit, mUnit, 16 * mUnit, false);
            if (mItem.count > 1) {
                String n = String.valueOf(mItem.count);
                PixelLines.draw(c, mPaint, n, (18 - PixelFont.width(n)) * mUnit, 10 * mUnit, mUnit, InvText.WHITE, 0x3f3f3f, false);
            }
            if (save >= 0) c.restoreToCount(save);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
