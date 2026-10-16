package gg.projects.mobile.join;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.IOException;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.AndroidSupport;
import gg.projects.mobile.content.PackDownloader;
import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.PackSyncApplier;
import gg.projects.mobile.content.PackSyncPlanner;

/** Sync settings and applying a plan, shared by the join flow and the mod screen. */
public final class ProjectsPacks {
    private ProjectsPacks() {}

    /** Parallel downloads per sync. */
    private static final int THREADS = 4;

    /**
     * Android sync options: files whose size and recorded hash match are not hashed again, and
     * mods known not to work on Android start switched off on a new install.
     */
    public static PackSyncPlanner.Options syncOptions(PackManifest manifest) {
        return new PackSyncPlanner.Options().quick(true).defaults(AndroidSupport.defaults(manifest));
    }

    /**
     * Applies a plan. Download progress goes to upstream's progress bar (so Play waits for it) and,
     * when given, to {@code listener}.
     */
    @WorkerThread
    public static PackSyncApplier.Report apply(File gameDir, PackSyncPlanner.Plan plan, @StringRes final int downloadingText,
                                               @Nullable final PackSyncApplier.Progress listener) throws IOException {
        return PackSyncApplier.apply(gameDir, plan, PackDownloader.httpTransport(ProjectsContent.userAgent()),
                new PackSyncApplier.Progress() {
                    private long lastPercent = -1;

                    @Override
                    public synchronized void onProgress(PackSyncApplier.Phase phase, long done, long total) {
                        if (listener != null) listener.onProgress(phase, done, total);
                        if (phase != PackSyncApplier.Phase.DOWNLOADING) return;
                        long percent = total <= 0 ? 0 : Math.min(100, done * 100 / total);
                        if (percent == lastPercent) return;
                        lastPercent = percent;
                        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, (int) percent, downloadingText,
                                done / Tools.BYTE_TO_MB, total / Tools.BYTE_TO_MB);
                    }
                }, THREADS);
    }

    @WorkerThread
    public static PackSyncApplier.Report apply(File gameDir, PackSyncPlanner.Plan plan, @StringRes int downloadingText) throws IOException {
        return apply(gameDir, plan, downloadingText, null);
    }
}
