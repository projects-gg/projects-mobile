package gg.projects.mobile.controls;

import android.view.View;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * MOB-NEXT: keys coming and going in the controls editor, when it switches between the game's two screens (a key and its
 * twin cross-fade in place) and when a drawer's keys come out of it or go back (they grow out of the drawer, as in the
 * game, {@link DrawerMotion}). Opacity and scale only, interruptible; with animations off they simply appear and
 * disappear. A key ends at the opacity it had in the editor (1, or its own opacity in the game's editor or after the
 * panel's slider), kept in a tag while it moves, so an interrupted fade never leaves it half visible.
 */
public final class EditorMotion {
    private EditorMotion() {}

    static final long ENTER_MS = 160;
    static final long EXIT_MS = 110;
    /** A drawer's keys come out one after another, as in the game. */
    public static final long STAGGER_MS = DrawerMotion.STAGGER_MS;

    /** The opacity a key rests at in the editor (its tag while it moves). */
    private static float restAlpha(View key) {
        Object rest = key.getTag(R.id.projects_editor_rest_alpha);
        return rest instanceof Float ? (Float) rest : key.getAlpha();
    }

    private static boolean moving(View key) {
        return key.getTag(R.id.projects_editor_rest_alpha) != null;
    }

    /**
     * Shows {@code key}. {@code from}: the drawer its keys grow out of (scaled from its centre, 92 % to full size,
     * {@code delayMs} after the previous key), else null for a key that fades in its place.
     */
    public static void show(View key, boolean motion, long delayMs, @Nullable View from) {
        if (key.getVisibility() == View.VISIBLE && !moving(key) && !DrawerMotion.isLeaving(key)) return;
        float rest = restAlpha(key);
        key.animate().cancel();
        key.setTag(R.id.projects_drawer_leaving, null);
        boolean wasHidden = key.getVisibility() != View.VISIBLE;
        key.setVisibility(View.VISIBLE);
        if (!motion) {
            settle(key, rest);
            return;
        }
        key.setTag(R.id.projects_editor_rest_alpha, rest);
        if (wasHidden) {
            key.setAlpha(0f);
            if (from != null) {
                key.setPivotX(from.getX() + from.getWidth() / 2f - key.getX());
                key.setPivotY(from.getY() + from.getHeight() / 2f - key.getY());
                key.setScaleX(DrawerMotion.FROM_SCALE);
                key.setScaleY(DrawerMotion.FROM_SCALE);
            }
        }
        key.animate().alpha(rest).scaleX(1f).scaleY(1f).setStartDelay(delayMs).setDuration(ENTER_MS)
                .setInterpolator(Motion.EASE_OUT).withLayer()
                .withEndAction(() -> settle(key, rest));
    }

    /** Hides {@code key}, faster than it came; while it fades it takes no new touch ({@link DrawerMotion#isLeaving}). */
    public static void hide(View key, boolean motion) {
        if (key.getVisibility() != View.VISIBLE) return;
        float rest = restAlpha(key);
        key.animate().cancel();
        if (!motion) {
            key.setVisibility(View.GONE);
            settle(key, rest);
            return;
        }
        key.setTag(R.id.projects_editor_rest_alpha, rest);
        key.setTag(R.id.projects_drawer_leaving, Boolean.TRUE);
        key.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(EXIT_MS)
                .setInterpolator(Motion.EASE_OUT).withLayer()
                .withEndAction(() -> {
                    key.setVisibility(View.GONE);
                    settle(key, rest);
                });
    }

    /** At rest: full size, its own opacity, nothing in flight. */
    private static void settle(View key, float rest) {
        key.setTag(R.id.projects_drawer_leaving, null);
        key.setTag(R.id.projects_editor_rest_alpha, null);
        key.setAlpha(rest);
        key.setScaleX(1f);
        key.setScaleY(1f);
    }
}
