package gg.projects.mobile.ui;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/**
 * MOB-PARITY: what kind of device the app runs on, for the few places that differ on an Android TV or a
 * Chromebook without a touch screen (the game's on-screen keys, the launcher's first focus).
 */
public final class DeviceKind {
    private DeviceKind() {}

    /** True on phones, tablets and touch Chromebooks; false on TVs and Chromebooks driven by a mouse alone. */
    public static boolean hasTouchScreen(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    /** An Android TV (or another device in television mode): the remote's D-pad drives the launcher. */
    public static boolean isTv(Context context) {
        UiModeManager modes = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (modes != null && modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
