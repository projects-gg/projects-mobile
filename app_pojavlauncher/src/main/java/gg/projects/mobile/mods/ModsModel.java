package gg.projects.mobile.mods;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.AndroidSupport;
import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ItemResolver;
import gg.projects.mobile.content.PackChoice;
import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.PackSelection;
import gg.projects.mobile.content.PackState;
import gg.projects.mobile.content.PackSyncPlanner;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.join.ProjectsPacks;
import gg.projects.mobile.join.ProjectsProfile;
import gg.projects.mobile.ui.ProjectsUi;

/**
 * What the mod screen shows: the pack the Projects profile follows, its items as the next sync
 * would install them (a dry run, so adopted changes show up before the next launch) and why the
 * list may be missing. Loaded on a worker thread.
 */
public final class ModsModel {
    private static final String TAG = "ProjectsMods";

    public enum Status {
        READY,
        /** No pack is published (or the pack has no items). */
        EMPTY,
        /** The pack could not be loaded (no network and nothing cached). */
        UNAVAILABLE,
        /** The family no longer offers the installed Minecraft version. */
        DROPPED
    }

    public final Status status;
    @Nullable public final PackManifest manifest;
    /** The background plan (no selection): the items with the player's current choices. */
    @Nullable public final PackSyncPlanner.Plan plan;
    public final Set<String> unsupported;
    public final File gameDir;
    @Nullable public final String recommendedMinecraft;
    @Nullable public final Throwable error;

    private ModsModel(Status status, @Nullable PackManifest manifest, @Nullable PackSyncPlanner.Plan plan, File gameDir,
                      @Nullable String recommendedMinecraft, @Nullable Throwable error) {
        this.status = status;
        this.manifest = manifest;
        this.plan = plan;
        this.unsupported = manifest == null ? Collections.<String>emptySet() : AndroidSupport.unsupportedItems(manifest);
        this.gameDir = gameDir;
        this.recommendedMinecraft = recommendedMinecraft;
        this.error = error;
    }

    @WorkerThread
    public static ModsModel load(Context context) {
        File gameDir = ProjectsProfile.knownGameDir();
        try {
            Snapshot snapshot = ProjectsContent.latestBlocking(context);
            PackChoice choice = snapshot.packChoice(PackState.load(gameDir));
            if (choice.kind == PackChoice.Kind.NONE) {
                boolean published = snapshot.hasContent() || ProjectsUi.nothingPublished(snapshot);
                return published ? new ModsModel(Status.EMPTY, null, null, gameDir, null, null)
                        : new ModsModel(Status.UNAVAILABLE, null, null, gameDir, null, snapshot.lastError);
            }
            if (choice.kind == PackChoice.Kind.DROPPED) {
                String recommended = choice.recommended == null ? null : choice.recommended.minecraft();
                return new ModsModel(Status.DROPPED, null, null, gameDir, recommended, null);
            }
            PackManifest manifest = ProjectsContent.repository(context).packManifest(snapshot, choice.pack);
            PackSyncPlanner.Plan plan = PackSyncPlanner.plan(manifest, PackSyncPlanner.directoryProbe(gameDir),
                    PackState.load(gameDir), null, ProjectsPacks.syncOptions(manifest));
            boolean anyChoice = false;
            for (ItemResolver.ItemStatus status : plan.items) anyChoice |= !status.library;
            if (!anyChoice) return new ModsModel(Status.EMPTY, manifest, plan, gameDir, null, null);
            return new ModsModel(Status.READY, manifest, plan, gameDir, null, null);
        } catch (ContentException | RuntimeException e) {
            Log.w(TAG, "mods unavailable", e);
            return new ModsModel(Status.UNAVAILABLE, null, null, gameDir, null, e);
        }
    }

    /** What applying {@code selection} would do (nothing changes on disk). */
    @WorkerThread
    public PackSyncPlanner.Plan preview(PackSelection selection) throws ContentException {
        if (manifest == null) throw new IllegalStateException("no pack");
        return PackSyncPlanner.plan(manifest, PackSyncPlanner.directoryProbe(gameDir), PackState.load(gameDir), selection,
                ProjectsPacks.syncOptions(manifest));
    }

    /**
     * The player saw the new recommendations: they are no longer flagged and adopted changes are
     * acknowledged (no files change). Without a pack state (nothing installed yet) nothing is new.
     */
    @WorkerThread
    public void markSeen() throws IOException {
        if (manifest == null) return;
        PackState state = PackState.load(gameDir);
        if (state == null) return;
        PackState before = state.copy();
        state.markItemsSeen(manifest, AndroidSupport.defaults(manifest));
        if (!state.sameAs(before)) state.save(gameDir);
    }

    public PackManifest.PackItem item(String id) {
        return manifest == null ? null : manifest.projects.item(id);
    }
}
