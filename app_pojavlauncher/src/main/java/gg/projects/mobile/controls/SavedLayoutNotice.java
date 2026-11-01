package gg.projects.mobile.controls;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import net.kdt.pojavlaunch.R;

import java.io.IOException;
import java.lang.ref.WeakReference;

import gg.projects.mobile.social.ui.ActionToast;

/**
 * CTRL-SAVE (1 Nov 2026): "Kaydet ve çık" in the launcher's controls editor closes it, so the screen the player comes
 * back to (LauncherActivity: the home screen or Settings) says "Tuşlar kaydedildi." with "Geri al", in the launcher's
 * own toast. The game's editor shows the same line itself (ControlLayout.showSaved).
 */
public final class SavedLayoutNotice {
    private SavedLayoutNotice() {}

    private static volatile LayoutBackups.Saved sPending;
    /** APK-REL: when the pending save's line first showed (uptime ms), 0 while it has not. */
    private static volatile long sShownAt;
    /**
     * APK-REL (0.4.9's emulator run): the editor runs sideways, so on a phone the launcher comes back sideways for a
     * moment and Android relaunches it upright (it follows the screen's turn), sometimes twice. The line shown at once
     * went with the screen that was going: it flashed during the turn and its "Geri al" was lost. It now waits a moment
     * (the relaunch comes first), and a screen relaunched soon after it showed shows it again.
     */
    private static final long SETTLE_MS = 450;
    private static final long AGAIN_MS = 3000;

    /** The editor closed after a save: the next launcher screen to come up shows it. */
    public static void offerOnReturn(LayoutBackups.Saved saved) {
        sPending = saved;
        sShownAt = 0;
    }

    /** Shows a save the editor left behind, on the screen that stays. */
    public static void showPending(final Activity activity) {
        if (sPending == null) return;
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        content.postDelayed(() -> show(activity), SETTLE_MS);
    }

    private static void show(Activity activity) {
        final LayoutBackups.Saved saved = sPending;
        if (saved == null || activity.isFinishing() || activity.isDestroyed() || activity.isChangingConfigurations()) return;
        long now = SystemClock.uptimeMillis();
        if (!EditorRules.showSavedLine(sShownAt, now, AGAIN_MS)) {
            sPending = null;
            return;
        }
        if (sShownAt == 0) sShownAt = now;
        String text = activity.getString(R.string.projects_ctrl_saved);
        if (!saved.undoable()) {
            ActionToast.show(activity, text, null, null);
            return;
        }
        final WeakReference<Activity> screen = new WeakReference<>(activity);
        ActionToast.show(activity, text, activity.getString(R.string.projects_undo), () -> {
            sPending = null;
            undo(screen, saved);
        });
    }

    private static void undo(final WeakReference<Activity> screen, final LayoutBackups.Saved saved) {
        final Handler main = new Handler(Looper.getMainLooper());
        LayoutBackups.run(() -> {
            boolean undone;
            try {
                undone = LayoutBackups.undo(saved);
            } catch (IOException | RuntimeException e) {
                Log.w("SavedLayoutNotice", "save not taken back", e);
                undone = false;
            }
            final boolean done = undone;
            main.post(() -> {
                Activity activity = screen.get();
                if (activity == null || activity.isFinishing()) return;
                ActionToast.show(activity, activity.getString(done ? R.string.projects_ctrl_undone : R.string.projects_ctrl_undo_failed),
                        null, null);
            });
        });
    }
}
