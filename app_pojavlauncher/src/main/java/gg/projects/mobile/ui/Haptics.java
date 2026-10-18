package gg.projects.mobile.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.kdt.pojavlaunch.R;

/**
 * Touch feedback the player feels (M14). Every call goes through {@link View#performHapticFeedback},
 * so Android's own "touch feedback" setting applies; Settings → "Dokunma titreşimi" turns it off
 * for the app. Which effect each kind plays is {@link HapticMap}'s.
 */
public final class Haptics {
    private Haptics() {}

    /** Settings key (default on). */
    public static final String KEY = "projects_haptics";

    private static final long TICK_GAP_MS = 35;

    public static boolean enabled(@Nullable Context context) {
        if (context == null) return false;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            return prefs.getBoolean(KEY, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static void perform(@Nullable View view, HapticMap.Kind kind) {
        if (view == null || !view.isAttachedToWindow() || !enabled(view.getContext())) return;
        int constant = HapticMap.constant(kind, Build.VERSION.SDK_INT);
        if (constant == HapticMap.NONE) return;
        try {
            view.performHapticFeedback(constant);
        } catch (RuntimeException ignored) {
            // A vendor build without the effect: no feedback rather than a crash.
        }
    }

    public static void press(@Nullable View view) {
        perform(view, HapticMap.Kind.PRESS);
    }

    public static void release(@Nullable View view) {
        perform(view, HapticMap.Kind.RELEASE);
    }

    public static void rowPress(@Nullable View view) {
        perform(view, HapticMap.Kind.ROW_PRESS);
    }

    public static void toggle(@Nullable View view, boolean on) {
        perform(view, on ? HapticMap.Kind.TOGGLE_ON : HapticMap.Kind.TOGGLE_OFF);
    }

    public static void confirm(@Nullable View view) {
        perform(view, HapticMap.Kind.CONFIRM);
    }

    public static void reject(@Nullable View view) {
        perform(view, HapticMap.Kind.REJECT);
    }

    /** A slider step; steps closer together than a motor can follow are skipped. */
    public static void tick(@Nullable View view) {
        if (view == null) return;
        Object last = view.getTag(R.id.projects_haptic_tick);
        long now = SystemClock.uptimeMillis();
        if (!HapticMap.tickDue(last instanceof Long ? (Long) last : 0L, now, TICK_GAP_MS)) return;
        view.setTag(R.id.projects_haptic_tick, now);
        perform(view, HapticMap.Kind.TICK);
    }
}
