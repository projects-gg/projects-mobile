package gg.projects.mobile.ui;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * MOB-PARITY: what kind of device the app runs on, for the place that differs on a Chromebook without a touch screen
 * (the game's on-screen keys).
 */
public final class DeviceKind {
    private DeviceKind() {}

    /** True on phones, tablets and touch Chromebooks; false on Chromebooks driven by a mouse and keyboard alone. */
    public static boolean hasTouchScreen(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }
}
