package gg.projects.mobile.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

/**
 * Where the phone shows a content sprite (ICON2, the desktop's {@code ContentIcon}): a tile or an
 * empty state that stands for a thing (a profile, friends, messages). Buttons, menus and rows keep
 * their Phosphor icons.
 */
public final class ContentTiles {
    private ContentTiles() {}

    /** A tile drawable with the sprite inside it, {@code insetDp} from each edge. */
    public static @NonNull Drawable withSprite(@NonNull Resources resources, @NonNull Drawable tile,
                                               @NonNull PixelSprites.Sprite sprite, boolean quiet, int insetDp) {
        LayerDrawable layers = new LayerDrawable(new Drawable[] {tile, new PixelSpriteDrawable(sprite, quiet)});
        int inset = Math.round(insetDp * resources.getDisplayMetrics().density);
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    /** An ImageView's sprite (its padding is the room around it); any tint is dropped. */
    public static void show(@NonNull ImageView view, @NonNull PixelSprites.Sprite sprite, boolean quiet) {
        view.setImageTintList(null);
        view.setImageDrawable(new PixelSpriteDrawable(sprite, quiet));
    }

    /** A TextView's top drawable: the sprite on the friends' icon tile, {@code sizeDp} square. */
    public static void onTileAbove(@NonNull TextView view, @NonNull PixelSprites.Sprite sprite, int sizeDp, int insetDp) {
        Context context = view.getContext();
        Drawable tile = ContextCompat.getDrawable(context, R.drawable.projects_social_icon_tile);
        if (tile == null) return;
        Drawable drawable = withSprite(context.getResources(), tile, sprite, false, insetDp);
        int size = Math.round(sizeDp * context.getResources().getDisplayMetrics().density);
        drawable.setBounds(0, 0, size, size);
        view.setCompoundDrawablesRelative(null, drawable, null, null);
    }

    /** A TextView's top drawable: the sprite alone, {@code sizeDp} square. */
    public static void above(@NonNull TextView view, @NonNull PixelSprites.Sprite sprite, boolean quiet, int sizeDp) {
        Drawable drawable = new PixelSpriteDrawable(sprite, quiet);
        int size = Math.round(sizeDp * view.getResources().getDisplayMetrics().density);
        drawable.setBounds(0, 0, size, size);
        view.setCompoundDrawablesRelative(null, drawable, null, null);
    }
}
