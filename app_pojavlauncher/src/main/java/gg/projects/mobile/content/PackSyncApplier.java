package gg.projects.mobile.content;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies a {@link PackSyncPlanner.Plan} (mirror of the desktop's apply_sync): downloads
 * (verified, atomic), then disabled item files renamed back, then deletions, then the new pack
 * state. Nothing is renamed or deleted and no state is written when a download fails, so the
 * next plan simply resumes. A rename or deletion that fails does not stop the sync: it is
 * reported and the state keeps the file's record, so the next sync tries again.
 */
public final class PackSyncApplier {
    private PackSyncApplier() {}

    public enum Phase { DOWNLOADING, REMOVING, FINISHED }

    public interface Progress {
        /** Downloading: bytes; removing: files. */
        void onProgress(Phase phase, long done, long total);
    }

    public static final class Report {
        public int downloaded;
        /** Disabled item files renamed back. */
        public int restored;
        public int deleted;
        public long bytes;
        /** Renames and deletions that failed (the file stays; the next sync tries again). */
        public final List<PackSyncPlanner.Issue> issues = new ArrayList<>();
    }

    public static Report apply(File gameDir, PackSyncPlanner.Plan plan, PackDownloader.Transport transport,
                               final Progress progress, int threads) throws IOException {
        // Re-validate every path: a forged or stale plan never touches anything outside the game dir.
        try {
            for (PackSyncPlanner.Download download : plan.downloads) ContentPaths.checkRelativePath(download.path);
            for (PackSyncPlanner.Restore restore : plan.restores) ContentPaths.checkRelativePath(restore.path);
            for (PackSyncPlanner.Delete delete : plan.deletes) ContentPaths.checkRelativePath(delete.path);
        } catch (ContentException e) {
            throw new IOException(e.getMessage(), e);
        }

        Report report = new Report();
        if (!plan.downloads.isEmpty()) {
            PackDownloader.downloadAll(gameDir, plan.downloads, transport, progress == null ? null : new PackDownloader.Progress() {
                @Override
                public void onProgress(long doneBytes, long totalBytes) {
                    progress.onProgress(Phase.DOWNLOADING, doneBytes, totalBytes);
                }
            }, threads);
            report.downloaded = plan.downloads.size();
            for (PackSyncPlanner.Download download : plan.downloads) report.bytes += download.size;
        }

        PackState state = plan.nextState.copy();
        for (PackSyncPlanner.Restore restore : plan.restores) {
            File target = new File(gameDir, restore.path);
            File disabled = new File(gameDir, restore.path + PackSyncPlanner.DISABLED_SUFFIX);
            if (!target.exists() && disabled.renameTo(target)) {
                report.restored++;
            } else {
                // Not recorded as installed: the next sync looks at it again.
                state.files.remove(restore.path);
                report.issues.add(new PackSyncPlanner.Issue(restore.path, PackSyncPlanner.IssueKind.BUSY, "could not rename " + disabled));
            }
        }

        int total = plan.deletes.size();
        for (int i = 0; i < total; i++) {
            PackSyncPlanner.Delete delete = plan.deletes.get(i);
            if (progress != null) progress.onProgress(Phase.REMOVING, i, total);
            File target = new File(gameDir, delete.path);
            if (!target.exists()) continue;
            if (target.isFile() && target.delete()) {
                report.deleted++;
            } else {
                if (delete.recordPath != null && delete.record != null && !state.files.containsKey(delete.recordPath)) {
                    state.files.put(delete.recordPath, delete.record.copy());
                }
                report.issues.add(new PackSyncPlanner.Issue(delete.path, PackSyncPlanner.IssueKind.BUSY, "could not delete " + target));
            }
        }

        state.save(gameDir);
        if (progress != null) progress.onProgress(Phase.FINISHED, total, total);
        return report;
    }
}
