package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

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

    /** A {@code sizeDp} tile for {@code itemId} (a catalog id such as {@code minecraft:elytra}). */
    public static View tile(Context c, String itemId, float sizeDp) {
        Tile tile = new Tile(c, itemId, CloudUi.dp(c, sizeDp));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(CloudUi.dp(c, 8));
        bg.setColor(CloudUi.color(c, R.color.projects_obsidian_800));
        tile.setBackground(bg);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.setContentDescription(null);
        return tile;
    }

    private static final class Tile extends View {
        private final String mId;
        private final int mIcon;
        private ItemArt mArt;

        Tile(Context c, String id, int sizePx) {
            super(c);
            mId = id;
            // Whole pixels per texel: the largest multiple of 16 that leaves a quarter of the tile as margin.
            mIcon = Math.max(16, (int) (sizePx * 0.75f) / 16 * 16);
            mArt = ItemAtlases.art(c, null, mIcon, art -> {
                mArt = art;
                invalidate();
            });
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int x = (getWidth() - mIcon) / 2;
            int y = (getHeight() - mIcon) / 2;
            mArt.draw(canvas, mId, null, null, false, x, y, mIcon, true);
        }
    }
}
