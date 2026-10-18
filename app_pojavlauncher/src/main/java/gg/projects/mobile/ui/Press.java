package gg.projects.mobile.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * Press feedback for anything tappable (M14; the owner's mobile rule: clear feedback on press and
 * while held). A view's pressed state drives it, so it works next to any click or touch listener
 * the view already has, waits like the pressed state does when a finger starts a scroll, and stays
 * while the finger is held:
 * <ul>
 * <li>the view settles to a smaller scale with a short ease-out (100 ms) and comes back a little
 * slower (240 ms; buttons with a hint of spring), from wherever it is, so quick taps never jump;</li>
 * <li>{@link Haptics} plays on the way down and, for buttons, a lighter tick on the way up.</li>
 * </ul>
 * With animations turned off nothing moves; the haptics stay. Colour and ripple come from the
 * view's own background (the theme's gold ripple).
 */
public final class Press {
    private Press() {}

    public enum Style {
        /** Buttons and chips: 96%, a click down and a tick up. */
        BUTTON(0.96f, HapticMap.Kind.PRESS, true, true),
        /** Icon buttons: the glyph sinks further, it is small. */
        ICON(0.88f, HapticMap.Kind.PRESS, true, true),
        /** Full-width rows: barely moves, a light tick. */
        ROW(0.985f, HapticMap.Kind.ROW_PRESS, false, false),
        /** Cards and tiles inside a column. */
        CARD(0.975f, HapticMap.Kind.ROW_PRESS, false, false);

        final float scale;
        final HapticMap.Kind down;
        final boolean tickOnRelease;
        final boolean spring;

        Style(float scale, HapticMap.Kind down, boolean tickOnRelease, boolean spring) {
            this.scale = scale;
            this.down = down;
            this.tickOnRelease = tickOnRelease;
            this.spring = spring;
        }
    }

    static final long PRESS_MS = 100;
    static final long RELEASE_MS = 240;
    /** Fast start, soft landing (design.md's entrance family, shorter). */
    static final Interpolator PRESS_IN = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** Comes back with about 1% of overshoot on a 4% press: a button that springs, not wobbles. */
    static final Interpolator RELEASE_SPRING = new PathInterpolator(0.3f, 1.45f, 0.55f, 1f);

    /** Gives {@code view} the press feedback of {@code style} (once; a second call is ignored). */
    public static void attach(@Nullable final View view, final Style style) {
        if (view == null || view.getTag(R.id.projects_press_style) == style) return;
        view.setTag(R.id.projects_press_style, style);
        float pressedScale = Motion.enabled(view.getContext()) ? style.scale : 1f;
        final boolean[] down = {false};

        AnimatorSet in = scale(view, pressedScale, PRESS_MS, PRESS_IN);
        in.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (down[0]) return;
                down[0] = true;
                Haptics.perform(view, style.down);
            }
        });
        AnimatorSet out = scale(view, 1f, RELEASE_MS, style.spring ? RELEASE_SPRING : Motion.EASE_OUT);
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                // The first state a view gets (attached, not pressed) is no release.
                if (!down[0]) return;
                down[0] = false;
                if (style.tickOnRelease) Haptics.release(view);
            }
        });

        StateListAnimator states = new StateListAnimator();
        states.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled}, in);
        states.addState(new int[0], out);
        view.setStateListAnimator(states);
    }

    private static AnimatorSet scale(View view, float to, long duration, Interpolator interpolator) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, to), ObjectAnimator.ofFloat(view, View.SCALE_Y, to));
        set.setDuration(duration);
        set.setInterpolator(interpolator);
        return set;
    }
}
