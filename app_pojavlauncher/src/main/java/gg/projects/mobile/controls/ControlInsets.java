package gg.projects.mobile.controls;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * How far the on-screen controls keep from the left and right edges beyond their own margin
 * (M15): the display cutout's width when Settings lets the game draw under it ("ignore notch"),
 * otherwise nothing, because then Android keeps the window clear of the cutout. Layouts use it as
 * {@code ${inset}} (ControlData).
 */
public final class ControlInsets {
    private ControlInsets() {}

    /** Pixels, from LauncherPreferences' cutout measurement (computeNotchSize). */
    public static int horizontal() {
        return horizontal(LauncherPreferences.PREF_IGNORE_NOTCH, LauncherPreferences.PREF_NOTCH_SIZE);
    }

    static int horizontal(boolean drawsUnderCutout, int notchSize) {
        return drawsUnderCutout && notchSize > 0 ? notchSize : 0;
    }
}
