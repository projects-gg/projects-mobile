package gg.projects.mobile.mods;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.IOException;

import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.PackSelection;
import gg.projects.mobile.content.PackState;
import gg.projects.mobile.content.PackSyncApplier;
import gg.projects.mobile.content.PackSyncPlanner;
import gg.projects.mobile.join.ProjectsJoin;
import gg.projects.mobile.join.ProjectsPacks;

/**
 * Applies a mod selection in the background. It outlives the mod screen (closing the screen
 * does not stop it; upstream's progress bar keeps showing it and Play waits for it) and never
 * overlaps a join.
 */
public final class ModsApply {
    private ModsApply() {}

    private static final String TAG = "ProjectsMods";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public enum Outcome { DONE, NEEDS_ACCOUNT, FAILED }

    public static final class Result {
        public final Outcome outcome;
        public final int issues;
        public final boolean network;
        @Nullable public final String details;

        Result(Outcome outcome, int issues, boolean network, @Nullable String details) {
            this.outcome = outcome;
            this.issues = issues;
            this.network = network;
            this.details = details;
        }
    }

    public interface Listener {
        /** Main thread; {@code fraction} is 0..1, or negative while it is not known yet. */
        void onProgress(PackSyncApplier.Phase phase, float fraction);

        void onFinished(Result result);
    }

    private static boolean sRunning;
    private static PackSyncApplier.Phase sPhase;
    private static float sFraction = -1;
    @Nullable private static Listener sListener;

    @MainThread
    public static boolean isRunning() {
        return sRunning;
    }

    /** Attaches the visible mod screen (or detaches with null); it gets the current progress at once. */
    @MainThread
    public static void setListener(@Nullable Listener listener) {
        sListener = listener;
        if (listener != null && sRunning) listener.onProgress(sPhase, sFraction);
    }

    /** Starts applying; false when a join or another apply is running. */
    @MainThread
    public static boolean start(Context context, final PackManifest manifest, final File gameDir, final PackSelection selection) {
        if (sRunning || !ProjectsJoin.tryBeginWork()) return false;
        sRunning = true;
        sPhase = null;
        sFraction = -1;
        synchronized (ModsApply.class) {
            sPostedPhase = null;
            sPostedFraction = -2;
        }
        PojavApplication.sExecutorService.execute(() -> {
            Result result;
            try {
                ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.projects_join_preparing);
                // Plan again against the disk as it is now; the screen's preview may be stale.
                PackSyncPlanner.Plan plan = PackSyncPlanner.plan(manifest, PackSyncPlanner.directoryProbe(gameDir),
                        PackState.load(gameDir), selection, ProjectsPacks.syncOptions(manifest));
                if (!plan.downloads.isEmpty() && !Tools.hasOnlineProfile()) {
                    // Upstream's rule: downloading modpack files needs an online account.
                    result = new Result(Outcome.NEEDS_ACCOUNT, 0, false, null);
                } else {
                    if (!gameDir.isDirectory() && !gameDir.mkdirs()) throw new IOException("cannot create " + gameDir);
                    PackSyncApplier.Report report = ProjectsPacks.apply(gameDir, plan, R.string.projects_mods_downloading,
                            (phase, done, total) -> progress(phase, total > 0 ? (float) done / total : -1));
                    Log.i(TAG, "mod selection applied: " + plan.summary().describeTr() + ", " + report.issues.size() + " issues");
                    result = new Result(Outcome.DONE, report.issues.size(), false, null);
                }
            } catch (ContentException e) {
                Log.w(TAG, "applying mods failed", e);
                result = new Result(Outcome.FAILED, 0, e.kind == ContentException.Kind.NETWORK, String.valueOf(e));
            } catch (IOException e) {
                Log.w(TAG, "applying mods failed", e);
                result = new Result(Outcome.FAILED, 0, true, String.valueOf(e));
            } catch (RuntimeException e) {
                Log.e(TAG, "applying mods failed", e);
                result = new Result(Outcome.FAILED, 0, false, String.valueOf(e));
            }
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            ProjectsJoin.endWork();
            final Result finished = result;
            MAIN.post(() -> {
                sRunning = false;
                if (sListener != null) sListener.onFinished(finished);
            });
        });
        return true;
    }

    private static PackSyncApplier.Phase sPostedPhase;
    private static float sPostedFraction = -2;

    /** Download threads report every few kilobytes; the screen gets at most one update per half percent. */
    private static synchronized void progress(final PackSyncApplier.Phase phase, final float fraction) {
        if (phase == sPostedPhase && Math.abs(fraction - sPostedFraction) < 0.005f && fraction < 1f) return;
        sPostedPhase = phase;
        sPostedFraction = fraction;
        MAIN.post(() -> {
            if (!sRunning) return;
            sPhase = phase;
            sFraction = fraction;
            if (sListener != null) sListener.onProgress(phase, fraction);
        });
    }
}
