package gg.projects.mobile.controls;

import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.util.List;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * How a control drawer's buttons come and go (M15): they grow out of the drawer button (scaled
 * from its centre, 92 % to full size, faded in, 12 ms apart) and leave faster than they came.
 * Only scale and alpha move, so the buttons' positions (their translation) stay as the layout set
 * them; with animations off they simply appear and disappear.
 */
public final class DrawerMotion {
    private DrawerMotion() {}

    static final long ENTER_MS = 180;
    static final long STAGGER_MS = 12;
    static final long EXIT_MS = 110;
    static final float FROM_SCALE = 0.92f;
    private static final Interpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);

    /** The buttons of {@code drawer} appear; {@code opacity} gives each its own alpha. */
    public static void reveal(View drawer, List<? extends View> buttons, Opacity opacity) {
        boolean motion = Motion.enabled(drawer.getContext());
        float cx = drawer.getX() + drawer.getWidth() / 2f;
        float cy = drawer.getY() + drawer.getHeight() / 2f;
        for (int i = 0; i < buttons.size(); i++) {
            View button = buttons.get(i);
            float to = opacity.of(button);
            button.animate().cancel();
            button.setTag(R.id.projects_drawer_leaving, null);
            button.setVisibility(View.VISIBLE);
            if (!motion) {
                rest(button, to);
                continue;
            }
            button.setPivotX(cx - button.getX());
            button.setPivotY(cy - button.getY());
            button.setAlpha(0f);
            button.setScaleX(FROM_SCALE);
            button.setScaleY(FROM_SCALE);
            button.animate().alpha(to).scaleX(1f).scaleY(1f)
                    .setStartDelay(i * STAGGER_MS).setDuration(ENTER_MS).setInterpolator(EASE_OUT)
                    .withLayer();
        }
    }

    /** The buttons go; {@code stillClosed} is asked at the end, so a quick reopen keeps them. */
    public static void conceal(View drawer, List<? extends View> buttons, Opacity opacity, StillClosed stillClosed) {
        boolean motion = Motion.enabled(drawer.getContext());
        for (View button : buttons) {
            float to = opacity.of(button);
            button.animate().cancel();
            if (!motion || button.getVisibility() != View.VISIBLE) {
                button.setTag(R.id.projects_drawer_leaving, null);
                button.setVisibility(View.GONE);
                rest(button, to);
                continue;
            }
            // While it fades it takes no new touch: a tap meant for what appears under it (the other
            // panel's button in the same place) or for the game gets there.
            button.setTag(R.id.projects_drawer_leaving, Boolean.TRUE);
            button.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                    .setStartDelay(0).setDuration(EXIT_MS).setInterpolator(EASE_OUT).withLayer()
                    .withEndAction(() -> {
                        button.setTag(R.id.projects_drawer_leaving, null);
                        if (stillClosed.closed()) button.setVisibility(View.GONE);
                        rest(button, to);
                    });
        }
    }

    /** True while {@code button} fades out of a closing drawer. */
    public static boolean isLeaving(View button) {
        return Boolean.TRUE.equals(button.getTag(R.id.projects_drawer_leaving));
    }

    /** A button at rest: full size, its own alpha. */
    public static void rest(View button, float alpha) {
        button.setAlpha(alpha);
        button.setScaleX(1f);
        button.setScaleY(1f);
    }

    public interface Opacity {
        float of(View button);
    }

    public interface StillClosed {
        boolean closed();
    }
}
