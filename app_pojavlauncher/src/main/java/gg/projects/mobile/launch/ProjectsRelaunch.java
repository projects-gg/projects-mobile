package gg.projects.mobile.launch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.TestStorageActivity;

/**
 * "Tekrar başlat" after Sodium kept the game from starting: the launcher opens and starts the same
 * profile again (the Projects profile through "Projects'e katıl", so the sync takes Sodium out
 * first).
 *
 * <p>FIX2 ("Bağlan" while the game ran): the game closed to connect somewhere else; the launcher opens
 * and starts into that server ({@link ConnectTarget}: Projects through its join, another server through
 * the profile Play would start).
 */
public final class ProjectsRelaunch {
    private ProjectsRelaunch() {}

    public static final String EXTRA = "gg.projects.mobile.RELAUNCH";
    /** FIX2: where the relaunch connects ({@link ConnectTarget#wire()}). */
    public static final String EXTRA_CONNECT = "gg.projects.mobile.RELAUNCH_CONNECT";

    /** Opens the launcher with the request to start again. */
    public static void start(Context context) {
        Intent intent = new Intent(context, TestStorageActivity.class)
                .putExtra(EXTRA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    /** FIX2: opens the launcher with the request to start into {@code target}. */
    public static void startConnect(Context context, ConnectTarget target) {
        Intent intent = new Intent(context, TestStorageActivity.class)
                .putExtra(EXTRA, true)
                .putExtra(EXTRA_CONNECT, target.wire())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    /** Carries the request from the splash screen's intent to the launcher's. */
    public static void forward(Intent from, Intent to) {
        if (from != null && from.getBooleanExtra(EXTRA, false)) {
            to.putExtra(EXTRA, true);
            String connect = from.getStringExtra(EXTRA_CONNECT);
            if (connect != null) to.putExtra(EXTRA_CONNECT, connect);
        }
    }

    /**
     * True once for an activity started with the request to start again (it is removed, so a rotation does
     * not start again). A request to connect ({@link #takeConnect}) is not one: it is taken separately.
     */
    public static boolean take(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null || !intent.getBooleanExtra(EXTRA, false) || intent.getStringExtra(EXTRA_CONNECT) != null) return false;
        intent.removeExtra(EXTRA);
        return true;
    }

    /** FIX2: the server a relaunch connects to, once (removed like {@link #take}); null when it is not one. */
    @Nullable
    public static ConnectTarget takeConnect(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null || !intent.getBooleanExtra(EXTRA, false)) return null;
        ConnectTarget target = ConnectTarget.read(intent.getStringExtra(EXTRA_CONNECT));
        if (target == null) return null;
        intent.removeExtra(EXTRA);
        intent.removeExtra(EXTRA_CONNECT);
        return target;
    }
}
