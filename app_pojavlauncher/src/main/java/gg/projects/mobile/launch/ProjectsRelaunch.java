package gg.projects.mobile.launch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import net.kdt.pojavlaunch.TestStorageActivity;

/**
 * "Tekrar başlat" after Sodium kept the game from starting: the launcher opens and starts the same
 * profile again (the Projects profile through "Projects'e katıl", so the sync takes Sodium out
 * first).
 */
public final class ProjectsRelaunch {
    private ProjectsRelaunch() {}

    public static final String EXTRA = "gg.projects.mobile.RELAUNCH";

    /** Opens the launcher with the request to start again. */
    public static void start(Context context) {
        Intent intent = new Intent(context, TestStorageActivity.class)
                .putExtra(EXTRA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    /** Carries the request from the splash screen's intent to the launcher's. */
    public static void forward(Intent from, Intent to) {
        if (from != null && from.getBooleanExtra(EXTRA, false)) to.putExtra(EXTRA, true);
    }

    /** True once for an activity started with the request (it is removed, so a rotation does not start again). */
    public static boolean take(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null || !intent.getBooleanExtra(EXTRA, false)) return false;
        intent.removeExtra(EXTRA);
        return true;
    }
}
