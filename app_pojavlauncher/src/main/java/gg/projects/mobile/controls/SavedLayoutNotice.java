package gg.projects.mobile.controls;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

    /** The editor closed after a save: the next launcher screen to come up shows it. */
    public static void offerOnReturn(LayoutBackups.Saved saved) {
        sPending = saved;
    }

    /** Shows a save the editor left behind, once. */
    public static void showPending(Activity activity) {
        final LayoutBackups.Saved saved = sPending;
        sPending = null;
        if (saved == null) return;
        String text = activity.getString(R.string.projects_ctrl_saved);
        if (!saved.undoable()) {
            ActionToast.show(activity, text, null, null);
            return;
        }
        final WeakReference<Activity> screen = new WeakReference<>(activity);
        ActionToast.show(activity, text, activity.getString(R.string.projects_undo), () -> undo(screen, saved));
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
