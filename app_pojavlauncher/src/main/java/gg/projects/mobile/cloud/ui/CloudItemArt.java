package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.cloud.items.ItemArt;
import gg.projects.mobile.cloud.items.ItemAtlases;

/**
 * An item's picture in AFK+'s lists and approvals: the game's own icon from the atlas drawn from the player's own client
 * jar (ItemAtlases, the one item art of the inventory too), on our dark tile with the edge dark icons get there; a quiet
 * stand-in (block, apple, book, else a tied sack) until the atlas is there or when no game is installed.
 */
public final class CloudItemArt {
    private CloudItemArt() {}

    /** A {@code sizeDp} tile for {@code itemId} (a game id such as {@code minecraft:elytra}). */
    public static View tile(Context c, String itemId, float sizeDp) {
        return tile(c, itemId, null, sizeDp);
    }

    /**
     * A tile for an item reference: a game item by its own icon; a custom item ({@code custom:<k>}) by its material once
     * seen ({@code base}), else (a typed one waiting for its first sight) a dashed cube outline.
     */
    public static View tile(Context c, String ref, @Nullable String base, float sizeDp) {
        String drawn = ref.startsWith("custom:") ? base : ref;
        Tile tile = new Tile(c, drawn, CloudUi.dp(c, sizeDp));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(CloudUi.dp(c, 8));
        bg.setColor(CloudUi.color(c, R.color.projects_obsidian_800));
        tile.setBackground(bg);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.setContentDescription(null);
        return tile;
    }

    private static final class Tile extends View {
        @Nullable
        private final String mId;
        private final int mIcon;
        private ItemArt mArt;
        private final Paint mDash = new Paint(Paint.ANTI_ALIAS_FLAG);

        Tile(Context c, @Nullable String id, int sizePx) {
            super(c);
            mId = id;
            // Whole pixels per texel: the largest multiple of 16 that leaves a quarter of the tile as margin.
            mIcon = Math.max(16, (int) (sizePx * 0.75f) / 16 * 16);
            mArt = ItemAtlases.art(c, null, mIcon, art -> {
                mArt = art;
                invalidate();
            });
            mDash.setStyle(Paint.Style.STROKE);
            mDash.setStrokeWidth(Math.max(1, CloudUi.dp(c, 1.5f)));
            mDash.setColor(CloudUi.color(c, R.color.projects_ash_400));
            mDash.setPathEffect(new DashPathEffect(new float[]{CloudUi.dp(c, 3), CloudUi.dp(c, 3)}, 0));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int x = (getWidth() - mIcon) / 2;
            int y = (getHeight() - mIcon) / 2;
            if (mId == null || mId.isEmpty()) {
                // Not seen yet: the outline of a cube, quiet (the launcher's dashed cube).
                float inset = mIcon * 0.18f;
                canvas.drawRect(x + inset, y + inset, x + mIcon - inset, y + mIcon - inset, mDash);
                return;
            }
            mArt.draw(canvas, mId, null, null, false, x, y, mIcon, true);
        }
    }
}
