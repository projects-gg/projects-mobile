package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import net.kdt.pojavlaunch.R;

/**
 * An item's picture in AFK+'s lists and approvals. One function on purpose: the shared pixel item sprites (ITEMART,
 * the desktop's 16 × 16 set, generated as {@code ItemSpriteData} for Android) take this place when they land. Until
 * then a neutral tile with Phosphor's package (no item is drawn here, so nothing reads as a wrong item).
 */
public final class CloudItemArt {
    private CloudItemArt() {}

    /** A {@code sizeDp} tile for {@code itemId} (a catalog id such as {@code minecraft:elytra}). */
    public static View tile(Context c, String itemId, float sizeDp) {
        FrameLayout tile = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(CloudUi.dp(c, 8));
        bg.setColor(CloudUi.color(c, R.color.projects_obsidian_800));
        tile.setBackground(bg);
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(R.drawable.projects_ic_chat2_package);
        glyph.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_400)));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int inset = CloudUi.dp(c, sizeDp * 0.25f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(CloudUi.dp(c, sizeDp) - 2 * inset, CloudUi.dp(c, sizeDp) - 2 * inset);
        lp.gravity = android.view.Gravity.CENTER;
        tile.addView(glyph, lp);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.setContentDescription(null);
        return tile;
    }
}
