package gg.projects.mobile.mods;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Motion of the Projects screens (design.md section 5): entrances ease out with
 * {@code cubic-bezier(0.16, 1, 0.3, 1)}, exits are about 30% faster. Every animation here is an
 * Animator, so the system's animator duration scale applies; with animations removed
 * (scale 0) nothing moves and views jump to their end state.
 */
public final class Motion {
    private Motion() {}

    public static final Interpolator EASE_OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    public static final Interpolator EASE_IN = new PathInterpolator(0.55f, 0f, 1f, 0.45f);

    public static final long ENTER_MS = 280;
    public static final long EXIT_MS = 190;

    /** False when the player turned animations off (reduced motion). */
    public static boolean enabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return ValueAnimator.areAnimatorsEnabled();
        try {
            return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
