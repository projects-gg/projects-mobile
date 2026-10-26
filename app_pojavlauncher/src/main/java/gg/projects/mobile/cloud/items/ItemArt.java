package gg.projects.mobile.cloud.items;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.LruCache;

import androidx.annotation.Nullable;

/**
 * Draws an item's picture (docs/design.md §6 "Item sprites"): the game's own icon from the atlas the phone drew from the
 * player's client jar ({@link ItemAtlases}), its server colours multiplied in place, one still frame of the game's glint
 * on enchanted items; a quiet one-tone stand-in when there is no atlas or no icon. Pixels stay square: drawings are
 * scaled by whole numbers, nearest neighbour. Pictures are kept per size (a small cache), so a frame redraw is cheap.
 */
public final class ItemArt {
    /** The stand-in's tone on the game's grey inventory (a shade lighter than the slot, never the game's hint grey). */
    public static final int STANDIN_GREY = 0xffd8d8d8;
    /** The stand-in's tone on our dark tiles. */
    public static final int STANDIN_DARK = 0xff5a5462;
    /** The edge dark icons get on our dark tiles. */
    public static final int RIM = 0xff2c2733;

    @Nullable
    private final Bitmap mAtlas;
    @Nullable
    private final ItemIndex mIndex;
    private final Paint mPixels = new Paint();
    private final LruCache<String, Bitmap> mCache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public ItemArt(@Nullable Bitmap atlas, @Nullable ItemIndex index) {
        mAtlas = atlas;
        mIndex = atlas == null ? null : index;
        mPixels.setFilterBitmap(false);
        mPixels.setAntiAlias(false);
        mPixels.setDither(false);
    }

    /** Art without an atlas: every item shows its stand-in. */
    public static ItemArt standIns() {
        return new ItemArt(null, null);
    }

    public boolean hasAtlas() {
        return mIndex != null;
    }

    /**
     * Draws {@code id}'s picture into the {@code size}-pixel square at (x, y). {@code color}: the server's colour (dye or
     * potion), {@code potion}: the potion's id; {@code dark}: the tile is our dark obsidian (dark icons get their edge,
     * stand-ins their dark tone) rather than the game's grey.
     */
    public void draw(Canvas canvas, String id, @Nullable Integer color, @Nullable String potion, boolean enchanted, int x, int y, int size,
                     boolean dark) {
        if (size <= 0) return;
        String key = id + "|" + color + "|" + potion + "|" + enchanted + "|" + size + "|" + dark;
        Bitmap picture = mCache.get(key);
        if (picture == null) {
            picture = render(id, color, potion, enchanted, size, dark);
            mCache.put(key, picture);
        }
        canvas.drawBitmap(picture, x, y, mPixels);
    }

    private Bitmap render(String id, @Nullable Integer color, @Nullable String potion, boolean enchanted, int size, boolean dark) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        ItemIndex.Icon icon = mIndex == null ? null : mIndex.icon(id);
        if (icon == null || mAtlas == null) {
            drawStandIn(c, ItemIndex.category(id), size, dark ? STANDIN_DARK : STANDIN_GREY);
            return out;
        }
        Rect dst = new Rect(0, 0, size, size);
        Paint paint = new Paint(mPixels);
        for (ItemIndex.Layer layer : icon.layers) {
            Rect src = new Rect(layer.rect[0], layer.rect[1], layer.rect[0] + layer.rect[2], layer.rect[1] + layer.rect[3]);
            if (layer.tint != null) {
                int tint = ItemIndex.layerColor(layer, color, potion);
                if (tint < 0) continue;
                paint.setColorFilter(new PorterDuffColorFilter(0xff000000 | tint, PorterDuff.Mode.MULTIPLY));
            } else {
                paint.setColorFilter(null);
            }
            c.drawBitmap(mAtlas, src, dst, paint);
        }
        paint.setColorFilter(null);
        if (ItemIndex.glints(id, enchanted) && mIndex.glint != null) glint(out, size);
        if (dark && icon.dark) return rim(out, icon.flat() ? Math.max(1, size / 16) : Math.max(1, size / 32));
        return out;
    }

    /** The glint's still frame, screened over the icon through the icon's own pixels (never past its outline). */
    private void glint(Bitmap icon, int size) {
        int[] g = mIndex.glint;
        Bitmap shine = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(shine);
        Paint soft = new Paint();
        soft.setFilterBitmap(true);
        sc.drawBitmap(mAtlas, new Rect(g[0], g[1], g[0] + g[2], g[1] + g[3]), new Rect(0, 0, size, size), soft);
        Paint mask = new Paint();
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        sc.drawBitmap(icon, 0, 0, mask);
        Paint screen = new Paint();
        screen.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        new Canvas(icon).drawBitmap(shine, 0, 0, screen);
        shine.recycle();
    }

    /** A hard edge in {@link #RIM} around the icon's silhouette, {@code w} pixels wide (no glow, no blur). */
    private static Bitmap rim(Bitmap icon, int w) {
        int size = icon.getWidth();
        int[] px = new int[size * size];
        icon.getPixels(px, 0, size, 0, 0, size, size);
        int[] out = px.clone();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if ((px[y * size + x] >>> 24) != 0) continue;
                boolean near = false;
                for (int d = 1; d <= w && !near; d++) {
                    near = lit(px, size, x - d, y) || lit(px, size, x + d, y) || lit(px, size, x, y - d) || lit(px, size, x, y + d);
                }
                if (near) out[y * size + x] = RIM;
            }
        }
        Bitmap edged = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        edged.setPixels(out, 0, size, 0, 0, size, size);
        icon.recycle();
        return edged;
    }

    private static boolean lit(int[] px, int size, int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && (px[y * size + x] >>> 24) > 0x40;
    }

    private static void drawStandIn(Canvas c, ItemIndex.Category category, int size, int tone) {
        String[] rows = ItemIndex.standIn(category);
        Paint p = new Paint();
        p.setColor(tone);
        p.setAntiAlias(false);
        // Whole pixels per texel, centred: a stand-in never smears.
        int unit = Math.max(1, size / 16);
        int offset = (size - unit * 16) / 2;
        for (int y = 0; y < 16; y++) {
            String row = rows[y];
            int start = -1;
            for (int x = 0; x <= 16; x++) {
                boolean on = x < 16 && row.charAt(x) == '#';
                if (on && start < 0) start = x;
                if (!on && start >= 0) {
                    c.drawRect(offset + start * unit, offset + y * unit, offset + x * unit, offset + (y + 1) * unit, p);
                    start = -1;
                }
            }
        }
    }
}
