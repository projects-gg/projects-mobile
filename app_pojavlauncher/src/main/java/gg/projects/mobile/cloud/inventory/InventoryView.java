package gg.projects.mobile.cloud.inventory;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.cloud.items.ItemArt;
import gg.projects.mobile.mods.Motion;

/**
 * The game's own inventory screen on whole device pixels (docs/design.md §6 "Cloud inventory", the launcher's
 * {@code InventoryView}): our drawing of the light grey window with its bevel and sunken slots in the game's layouts,
 * the items with their counts and wear bars, the held hotbar slot's frame, the game's slot highlight on a touched slot,
 * and every word of the game drawn from the pixel bitmaps with the game's shadow. A tap is a left click; a long press
 * (520 ms; scrolling cancels it) opens the slot's actions. Moves waiting for the machine show faint after 80 ms. Motion
 * is the held frame's glide and opacity only; nothing moves while nothing changes.
 */
public final class InventoryView extends View {
    public interface Listener {
        void tap(InvLayout.Spot spot);

        void longPress(InvLayout.Spot spot);
    }

    /** What a screen reader says for a slot (the screen words it in the app's language). */
    public interface Labels {
        String slot(InvLayout.Spot spot, @Nullable InvItem item, boolean held, boolean waiting);
    }

    private static final int OUTLINE = 0xff000000;
    private static final int FACE = 0xffc6c6c6;
    private static final int LIGHT = 0xffffffff;
    private static final int SHADE = 0xff555555;
    private static final int SLOT = 0xff8b8b8b;
    private static final int SLOT_DARK = 0xff373737;
    private static final int SLOT_LIGHT = 0xffffffff;
    private static final int GHOST = 0xff555555;
    private static final long LONG_PRESS_MS = 520;
    private static final long PENDING_SHOWS_MS = 80;
    private static final long LOCK_MS = 900;
    private static final long HELD_GLIDE_MS = 180;

    private InvLayout.Screen mScreen = InvLayout.screenFor(null, 0);
    private InvRules.State mInv = InvRules.State.empty();
    private Map<Integer, InvItem> mWin = Collections.emptyMap();
    private Set<String> mWaiting = Collections.emptySet();
    private long mWaitingSince;
    private String mTitle = "";
    private String mInventoryLabel = "";
    private ItemArt mArt = ItemArt.standIns();
    @Nullable
    private String mLit;
    private final Map<String, Long> mLocks = new HashMap<>();
    private int mScale = 1;
    private int mLeft;
    private boolean mInteractive = true;
    @Nullable
    private Listener mListener;
    @Nullable
    private Labels mLabels;

    private final Paint mFill = new Paint();
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mRect = new Rect();

    private float mHeldX = -1;
    private float mHeldY = -1;
    @Nullable
    private ValueAnimator mHeldGlide;

    // Touch.
    @Nullable
    private InvLayout.Spot mDown;
    private float mDownX;
    private float mDownY;
    private boolean mLongFired;
    private final int mSlop;
    @Nullable
    private String mHeldLit;
    private final Runnable mLongPress = this::longPress;
    private final Runnable mUnlight = () -> {
        mLit = mHeldLit;
        invalidate();
    };

    private final SlotsHelper mHelper;

    public InventoryView(Context context) {
        super(context);
        mFill.setAntiAlias(false);
        mText.setTypeface(Typeface.MONOSPACE);
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mHelper = new SlotsHelper(this);
        ViewCompat.setAccessibilityDelegate(this, mHelper);
        setClickable(true);
        setFocusable(true);
    }

    // ---- what it shows ----

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public void setLabels(@Nullable Labels labels) {
        mLabels = labels;
    }

    public void setArt(ItemArt art) {
        mArt = art;
        invalidate();
    }

    /** Whether taps and long presses do anything (a "looking only" view still reads out). */
    public void setInteractive(boolean interactive) {
        mInteractive = interactive;
    }

    /**
     * The screen and what is in it: the player inventory as shown (with moves on their way), the window's items, the
     * slots that wait, the title ("Ekipman" on the player's own screen, else the window's, plain) and the "Envanter" label.
     */
    public void show(InvLayout.Screen screen, InvRules.State inv, Map<Integer, InvItem> win, Set<String> waiting, String title,
                     String inventoryLabel) {
        boolean sizeChanged = screen.height != mScreen.height || screen.width != mScreen.width;
        mScreen = screen;
        if (mWaiting.isEmpty() && !waiting.isEmpty()) {
            mWaitingSince = SystemClock.uptimeMillis();
            postInvalidateDelayed(PENDING_SHOWS_MS + 4);
        }
        mWaiting = new HashSet<>(waiting);
        mWin = win;
        mTitle = title;
        mInventoryLabel = inventoryLabel;
        glideHeld(inv.held, mInv.held != inv.held && mHeldX >= 0);
        mInv = inv;
        if (sizeChanged) requestLayout();
        invalidate();
        mHelper.invalidateRoot();
    }

    public InvLayout.Screen screen() {
        return mScreen;
    }

    /** The slot shown lit (its actions are open); null for none. */
    public void setLit(@Nullable String key) {
        mHeldLit = key;
        mLit = key;
        removeCallbacks(mUnlight);
        invalidate();
    }

    /** A move tried where nothing moves (looking only, a trade's lock): a small lock on the slot for a moment. */
    public void showLock(String key) {
        mLocks.put(key, SystemClock.uptimeMillis());
        invalidate();
    }

    /** The slot's square on screen (for a sheet anchored there, or a test). */
    public Rect slotBounds(InvLayout.Spot spot) {
        return new Rect(mLeft + (spot.x - 1) * mScale, (spot.y - 1) * mScale, mLeft + (spot.x + 17) * mScale, (spot.y + 17) * mScale);
    }

    public int scale() {
        return mScale;
    }

    /**
     * How far the window's visible edge sits inside this view's sides (past the centring and the one-pixel outline that
     * melts into a dark page): where the line under the window starts and ends.
     */
    public int edge() {
        return Math.max(0, mLeft + mScale);
    }

    // ---- layout ----

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) width = InvLayout.PANEL_WIDTH * 4;
        mScale = InvLayout.guiScale(width);
        mLeft = (width - mScreen.width * mScale) / 2;
        setMeasuredDimension(width, mScreen.height * mScale);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mScale = InvLayout.guiScale(w);
        mLeft = (w - mScreen.width * mScale) / 2;
    }

    // ---- drawing ----

    private void rect(Canvas c, int color, int x, int y, int w, int h) {
        mFill.setColor(color);
        c.drawRect(mLeft + x * mScale, y * mScale, mLeft + (x + w) * mScale, (y + h) * mScale, mFill);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = mScreen.width;
        int h = mScreen.height;
        // The window: outline with two-pixel round corners, white bevel top-left, grey bevel bottom-right.
        rect(canvas, OUTLINE, 2, 0, w - 4, 1);
        rect(canvas, OUTLINE, 2, h - 1, w - 4, 1);
        rect(canvas, OUTLINE, 0, 2, 1, h - 4);
        rect(canvas, OUTLINE, w - 1, 2, 1, h - 4);
        rect(canvas, OUTLINE, 1, 1, 1, 1);
        rect(canvas, OUTLINE, w - 2, 1, 1, 1);
        rect(canvas, OUTLINE, 1, h - 2, 1, 1);
        rect(canvas, OUTLINE, w - 2, h - 2, 1, 1);
        rect(canvas, FACE, 2, 1, w - 4, h - 2);
        rect(canvas, FACE, 1, 2, w - 2, h - 4);
        rect(canvas, LIGHT, 2, 1, w - 5, 1);
        rect(canvas, LIGHT, 1, 2, w - 5, 1);
        rect(canvas, LIGHT, 1, 2, 1, h - 5);
        rect(canvas, LIGHT, 2, 1, 1, h - 5);
        rect(canvas, SHADE, w - 2, 3, 1, h - 5);
        rect(canvas, SHADE, w - 3, 4, 1, h - 6);
        rect(canvas, SHADE, 3, h - 2, w - 5, 1);
        rect(canvas, SHADE, 4, h - 3, w - 6, 1);
        for (InvLayout.Spot s : mScreen.slots) {
            rect(canvas, SLOT, s.x - 1, s.y - 1, 18, 18);
            rect(canvas, SLOT_DARK, s.x - 1, s.y - 1, 17, 1);
            rect(canvas, SLOT_DARK, s.x - 1, s.y, 1, 16);
            rect(canvas, SLOT_LIGHT, s.x, s.y + 16, 17, 1);
            rect(canvas, SLOT_LIGHT, s.x + 16, s.y, 1, 16);
            if (itemAt(s) == null) ghost(canvas, s);
        }
        if (mScreen.title != null && !mTitle.isEmpty()) {
            String title = PixelFont.clip(mTitle, w - 16);
            int x = mScreen.titleCentered ? (w - PixelFont.advance(title)) / 2 : mScreen.title[0];
            text(canvas, title, x, mScreen.title[1], InvText.TITLE, -1);
        }
        if (mScreen.inventoryLabel != null && !mInventoryLabel.isEmpty()) {
            text(canvas, mInventoryLabel, mScreen.inventoryLabel[0], mScreen.inventoryLabel[1], InvText.TITLE, -1);
        }
        long now = SystemClock.uptimeMillis();
        boolean pendingShows = !mWaiting.isEmpty() && now - mWaitingSince >= PENDING_SHOWS_MS;
        for (InvLayout.Spot s : mScreen.slots) {
            InvItem item = itemAt(s);
            boolean lit = s.key().equals(mLit);
            if (lit) highlight(canvas, s, 0x60ffffff);
            if (item != null) {
                int alpha = pendingShows && mWaiting.contains(s.key()) ? 153 : 255;
                drawItem(canvas, item, s.x, s.y, alpha);
            }
            if (lit) highlight(canvas, s, 0x20ffffff);
        }
        // The held hotbar slot: a light ring and a dark one, 1 and 2 pixels out; it glides to a newly held slot.
        InvLayout.Spot held = mScreen.spot(InvLayout.Area.INV, InvLayout.HOTBAR_FIRST + mInv.held);
        if (held != null) {
            if (mHeldX < 0) {
                mHeldX = held.x;
                mHeldY = held.y;
            }
            heldFrame(canvas, mHeldX, mHeldY);
        }
        boolean locksLeft = false;
        for (Map.Entry<String, Long> lock : new HashMap<>(mLocks).entrySet()) {
            long age = now - lock.getValue();
            if (age >= LOCK_MS) {
                mLocks.remove(lock.getKey());
                continue;
            }
            InvLayout.Spot s = mScreen.spot(lock.getKey());
            if (s == null) continue;
            float alpha = age < LOCK_MS * 0.55f ? 1f : 1f - (age - LOCK_MS * 0.55f) / (LOCK_MS * 0.45f);
            lockMark(canvas, s.x, s.y, alpha);
            locksLeft = true;
        }
        if (locksLeft) postInvalidateOnAnimation();
        if (!mWaiting.isEmpty() && !pendingShows) postInvalidateDelayed(PENDING_SHOWS_MS);
    }

    @Nullable
    private InvItem itemAt(InvLayout.Spot s) {
        return s.area == InvLayout.Area.INV ? mInv.at(s.slot) : mWin.get(s.slot);
    }

    private void highlight(Canvas c, InvLayout.Spot s, int color) {
        mFill.setColor(color);
        c.drawRect(mLeft + s.x * mScale, s.y * mScale, mLeft + (s.x + 16) * mScale, (s.y + 16) * mScale, mFill);
    }

    /** The item as the game draws it in a 16 × 16 square: picture, wear bar, count. */
    private void drawItem(Canvas c, InvItem item, int gx, int gy, int alpha) {
        int save = alpha < 255 ? c.saveLayerAlpha(mLeft + gx * mScale, gy * mScale, mLeft + (gx + 18) * mScale, (gy + 18) * mScale, alpha)
                : -1;
        mArt.draw(c, item.id, item.color, item.potion, item.glints(), mLeft + gx * mScale, gy * mScale, 16 * mScale, false);
        if (item.wears() && item.durabilityLeft < item.durabilityMax) {
            rect(c, 0xff000000, gx + 2, gy + 13, 13, 2);
            rect(c, 0xff000000 | InvText.durabilityColor(item.durabilityLeft, item.durabilityMax), gx + 2, gy + 13,
                    InvText.durabilityWidth(item.durabilityLeft, item.durabilityMax), 1);
        }
        if (item.count > 1) {
            String n = String.valueOf(item.count);
            // The game's place: x + 19 - 2 - font.width(n), where font.width counts each character's advance (its
            // last column of light is the item's last, its shadow on the slot's edge).
            text(c, n, gx + 17 - PixelFont.advance(n), gy + 9, InvText.WHITE, 0x3f3f3f);
        }
        if (save >= 0) c.restoreToCount(save);
    }

    /** Game text from the pixel bitmaps at (gx, gy) GUI pixels (the glyph cell's top); {@code shadow} -1 for none. */
    private void text(Canvas c, String text, int gx, int gy, int rgb, int shadow) {
        if (!PixelFont.canDraw(text)) {
            // A line with a character the bitmaps lack falls back to a font, as the launcher's does.
            mText.setTextSize(8 * mScale);
            if (shadow >= 0) {
                mText.setColor(0xff000000 | shadow);
                c.drawText(text, mLeft + (gx + 1) * mScale, (gy + 8) * mScale, mText);
            }
            mText.setColor(0xff000000 | rgb);
            c.drawText(text, mLeft + gx * mScale, (gy + 7) * mScale, mText);
            return;
        }
        if (shadow >= 0) {
            mFill.setColor(0xff000000 | shadow);
            PixelFont.runs(text, (x, y, w) -> c.drawRect(mLeft + (gx + x + 1) * mScale, (gy + y + 1) * mScale,
                    mLeft + (gx + x + 1 + w) * mScale, (gy + y + 2) * mScale, mFill));
        }
        mFill.setColor(0xff000000 | rgb);
        PixelFont.runs(text, (x, y, w) -> c.drawRect(mLeft + (gx + x) * mScale, (gy + y) * mScale, mLeft + (gx + x + w) * mScale,
                (gy + y + 1) * mScale, mFill));
    }

    private void heldFrame(Canvas c, float gx, float gy) {
        float left = mLeft + (gx - 3) * mScale;
        float top = (gy - 3) * mScale;
        float s = mScale;
        mFill.setColor(OUTLINE);
        c.drawRect(left, top, left + 22 * s, top + s, mFill);
        c.drawRect(left, top + 21 * s, left + 22 * s, top + 22 * s, mFill);
        c.drawRect(left, top + s, left + s, top + 21 * s, mFill);
        c.drawRect(left + 21 * s, top + s, left + 22 * s, top + 21 * s, mFill);
        mFill.setColor(LIGHT);
        c.drawRect(left + s, top + s, left + 21 * s, top + 2 * s, mFill);
        c.drawRect(left + s, top + 20 * s, left + 21 * s, top + 21 * s, mFill);
        c.drawRect(left + s, top + 2 * s, left + 2 * s, top + 20 * s, mFill);
        c.drawRect(left + 20 * s, top + 2 * s, left + 21 * s, top + 20 * s, mFill);
    }

    private void glideHeld(int held, boolean animate) {
        InvLayout.Spot to = mScreen.spot(InvLayout.Area.INV, InvLayout.HOTBAR_FIRST + held);
        if (to == null) return;
        if (mHeldGlide != null) mHeldGlide.cancel();
        if (!animate || !Motion.enabled(getContext()) || mHeldX < 0) {
            mHeldX = to.x;
            mHeldY = to.y;
            return;
        }
        final float fromX = mHeldX;
        final float fromY = mHeldY;
        final int toX = to.x;
        final int toY = to.y;
        ValueAnimator glide = ValueAnimator.ofFloat(0f, 1f);
        glide.setDuration(HELD_GLIDE_MS);
        glide.setInterpolator(Motion.EASE_OUT);
        glide.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Whole GUI pixels along the way: the frame never sits between two pixels.
            mHeldX = Math.round(fromX + (toX - fromX) * t);
            mHeldY = Math.round(fromY + (toY - fromY) * t);
            invalidate();
        });
        mHeldGlide = glide;
        glide.start();
    }

    /** Outlines for empty armour and off-hand slots (our own drawings, the launcher's panel-art). */
    private void ghost(Canvas c, InvLayout.Spot s) {
        String[] rows;
        switch (s.kind) {
            case HEAD: rows = HEAD; break;
            case CHEST: rows = CHEST; break;
            case LEGS: rows = LEGS; break;
            case FEET: rows = FEET; break;
            case OFFHAND: rows = OFFHAND; break;
            default: return;
        }
        for (int dy = 0; dy < 16; dy++) {
            String row = rows[dy];
            int start = -1;
            for (int dx = 0; dx <= 16; dx++) {
                boolean on = dx < 16 && row.charAt(dx) == '#';
                if (on && start < 0) start = dx;
                if (!on && start >= 0) {
                    rect(c, GHOST, s.x + start, s.y + dy, dx - start, 1);
                    start = -1;
                }
            }
        }
    }

    /** The lock: 7 × 8 with the game's drop shadow, at the item's top left. */
    private void lockMark(Canvas c, int gx, int gy, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int[][] parts = {{1, 0, 5, 1}, {1, 1, 1, 2}, {5, 1, 1, 2}, {0, 3, 7, 2}, {0, 5, 3, 2}, {4, 5, 3, 2}, {0, 7, 7, 1}};
        for (int[] p : parts) rect(c, (a << 24) | 0x3f3f3f, gx + p[0] + 1, gy + p[1] + 1, p[2], p[3]);
        for (int[] p : parts) rect(c, (a << 24) | 0xffffff, gx + p[0], gy + p[1], p[2], p[3]);
    }

    private static final String[] HEAD = {"................", "................", "................", "................",
            ".....######.....", "....########....", "...##########...", "...###....###...", "...##......##...", "...##......##...",
            "................", "................", "................", "................", "................", "................"};
    private static final String[] CHEST = {"................", "................", "...###....###...", "...####..####...",
            "...##########...", "...##########...", "....########....", "....########....", "....########....", "....########....",
            "....########....", "....########....", "................", "................", "................", "................"};
    private static final String[] LEGS = {"................", "................", "................", "....########....",
            "....########....", "....###..###....", "....###..###....", "....###..###....", "....###..###....", "....###..###....",
            "....###..###....", "....###..###....", "................", "................", "................", "................"};
    private static final String[] FEET = {"................", "................", "................", "................",
            "................", "................", "....###..###....", "....###..###....", "....###..###....", "...####..####...",
            "..#####..#####..", "................", "................", "................", "................", "................"};
    private static final String[] OFFHAND = {"................", "................", "....########....", "...##########...",
            "...##########...", "...##########...", "...##########...", "...##########...", "....########....", "....########....",
            ".....######.....", "......####......", ".......##.......", "................", "................", "................"};

    // ---- touch ----

    @Nullable
    private InvLayout.Spot spotAt(float x, float y) {
        if (mScale <= 0) return null;
        return mScreen.at((x - mLeft) / mScale, y / mScale);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDown = mInteractive ? spotAt(e.getX(), e.getY()) : null;
                if (mDown == null) return false;
                mDownX = e.getX();
                mDownY = e.getY();
                mLongFired = false;
                removeCallbacks(mUnlight);
                mLit = mDown.key();
                invalidate();
                postDelayed(mLongPress, LONG_PRESS_MS);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (mDown != null && (Math.abs(e.getX() - mDownX) > mSlop || Math.abs(e.getY() - mDownY) > mSlop)) cancelTouch();
                return mDown != null;
            case MotionEvent.ACTION_UP: {
                InvLayout.Spot down = mDown;
                removeCallbacks(mLongPress);
                mDown = null;
                if (down == null) return false;
                if (!mLongFired) {
                    postDelayed(mUnlight, 120);
                    InvLayout.Spot up = spotAt(e.getX(), e.getY());
                    if (up != null && up.key().equals(down.key())) {
                        performClick();
                        if (mListener != null) mListener.tap(down);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelTouch();
                return false;
            default:
                return mDown != null;
        }
    }

    private void cancelTouch() {
        removeCallbacks(mLongPress);
        mDown = null;
        mLit = mHeldLit;
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void longPress() {
        InvLayout.Spot spot = mDown;
        if (spot == null) return;
        mLongFired = true;
        if (mListener != null) mListener.longPress(spot);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mLongPress);
        removeCallbacks(mUnlight);
        if (mHeldGlide != null) mHeldGlide.cancel();
    }

    // ---- screen readers: every slot a virtual view ----

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        return mHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    private final class SlotsHelper extends ExploreByTouchHelper {
        SlotsHelper(View host) {
            super(host);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            InvLayout.Spot s = spotAt(x, y);
            return s == null ? HOST_ID : mScreen.slots.indexOf(s);
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> ids) {
            for (int i = 0; i < mScreen.slots.size(); i++) ids.add(i);
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node) {
            if (id < 0 || id >= mScreen.slots.size()) {
                node.setContentDescription("");
                node.setBoundsInParent(new Rect(0, 0, 1, 1));
                return;
            }
            InvLayout.Spot s = mScreen.slots.get(id);
            InvItem item = itemAt(s);
            boolean held = s.area == InvLayout.Area.INV && s.slot == InvLayout.HOTBAR_FIRST + mInv.held;
            node.setContentDescription(mLabels == null ? s.key() : mLabels.slot(s, item, held, mWaiting.contains(s.key())));
            node.setBoundsInParent(slotBounds(s));
            if (mInteractive) {
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
                node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
                node.setClickable(true);
                node.setLongClickable(true);
            }
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, @Nullable Bundle arguments) {
            if (id < 0 || id >= mScreen.slots.size() || !mInteractive || mListener == null) return false;
            InvLayout.Spot s = mScreen.slots.get(id);
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                mListener.tap(s);
                invalidateVirtualView(id);
                sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED);
                return true;
            }
            if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK) {
                mListener.longPress(s);
                return true;
            }
            return false;
        }
    }
}
