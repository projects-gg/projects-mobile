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
import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ItemResolver;
import gg.projects.mobile.content.PackChoice;
import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.PackSelection;
import gg.projects.mobile.content.PackState;
import gg.projects.mobile.content.PackSyncPlanner;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.content.SodiumSupport;
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
    /** The Sodium family of the pack ({@link SodiumSupport#family}). */
    public final Set<String> sodiumFamily;
    /** Why the family is held off on this phone right now. */
    public final SodiumSupport.Block sodiumBlock;
    /** The Projects profile's renderer cannot run Sodium (marked even after the player overrides it). */
    public final boolean rendererBlocksSodium;
    /** Sodium kept the game from starting on this phone before (noted even after it is back on). */
    public final boolean sodiumCrashedHere;
    /** "Sodium artık telefonunda da çalışıyor": a set from before 0.1.5 without the recommended Sodium. */
    public final boolean sodiumOffer;
    public final File gameDir;
    @Nullable public final String recommendedMinecraft;
    @Nullable public final Throwable error;

    private ModsModel(Status status, @Nullable PackManifest manifest, @Nullable PackSyncPlanner.Plan plan, File gameDir,
                      @Nullable String recommendedMinecraft, @Nullable Throwable error, @Nullable Sodium sodium) {
        this.status = status;
        this.manifest = manifest;
        this.plan = plan;
        this.sodiumFamily = manifest == null ? Collections.<String>emptySet() : SodiumSupport.family(manifest);
        this.sodiumBlock = sodium == null ? SodiumSupport.Block.NONE : sodium.block;
        this.rendererBlocksSodium = sodium != null && sodium.rendererBlocks;
        this.sodiumCrashedHere = sodium != null && sodium.crashedHere;
        this.sodiumOffer = sodium != null && sodium.offer;
        this.gameDir = gameDir;
        this.recommendedMinecraft = recommendedMinecraft;
        this.error = error;
    }

    /** The phone's Sodium facts for one load. */
    private static final class Sodium {
        SodiumSupport.Block block = SodiumSupport.Block.NONE;
        boolean rendererBlocks;
        boolean crashedHere;
        boolean offer;
    }

    @WorkerThread
    public static ModsModel load(Context context) {
        File gameDir = ProjectsProfile.knownGameDir();
        try {
            Snapshot snapshot = ProjectsContent.latestBlocking(context);
            PackState state = PackState.load(gameDir);
            PackChoice choice = snapshot.packChoice(state);
            if (choice.kind == PackChoice.Kind.NONE) {
                boolean published = snapshot.hasContent() || ProjectsUi.nothingPublished(snapshot);
                return published ? new ModsModel(Status.EMPTY, null, null, gameDir, null, null, null)
                        : new ModsModel(Status.UNAVAILABLE, null, null, gameDir, null, snapshot.lastError, null);
            }
            if (choice.kind == PackChoice.Kind.DROPPED) {
                String recommended = choice.recommended == null ? null : choice.recommended.minecraft();
                return new ModsModel(Status.DROPPED, null, null, gameDir, recommended, null, null);
            }
            PackManifest manifest = ProjectsContent.repository(context).packManifest(snapshot, choice.pack);
            PackSyncPlanner.Plan plan = PackSyncPlanner.plan(manifest, PackSyncPlanner.directoryProbe(gameDir),
                    state, null, ProjectsPacks.syncOptions(context, manifest));
            Sodium sodium = sodium(context, manifest, state, plan);
            boolean anyChoice = false;
            for (ItemResolver.ItemStatus status : plan.items) anyChoice |= !status.library;
            if (!anyChoice) return new ModsModel(Status.EMPTY, manifest, plan, gameDir, null, null, sodium);
            return new ModsModel(Status.READY, manifest, plan, gameDir, null, null, sodium);
        } catch (ContentException | RuntimeException e) {
            Log.w(TAG, "mods unavailable", e);
            return new ModsModel(Status.UNAVAILABLE, null, null, gameDir, null, e, null);
        }
    }

    @WorkerThread
    private static Sodium sodium(Context context, PackManifest manifest, @Nullable PackState state, PackSyncPlanner.Plan plan) {
        Sodium sodium = new Sodium();
        sodium.block = SodiumState.block(context);
        sodium.rendererBlocks = !SodiumSupport.rendererRunsSodium(ProjectsProfile.knownRenderer());
        sodium.crashedHere = SodiumState.crashedHere(context);
        sodium.offer = offerPending(context, manifest, state, plan.items, sodium.block);
        return sodium;
    }

    /**
     * Sets made by 0.1.0 to 0.1.4 started the Sodium family switched off on Android, and their
     * recorded choices cannot tell that default from a player's own choice. So such a set is not
     * changed: it is offered Sodium once, while this phone can run it and nobody answered yet.
     */
    @WorkerThread
    static boolean offerPending(Context context, PackManifest manifest, @Nullable PackState state,
                                java.util.List<ItemResolver.ItemStatus> statuses, SodiumSupport.Block block) {
        if (state == null || block != SodiumSupport.Block.NONE || SodiumState.offerAnswered(context)) return false;
        return SodiumSupport.missesRecommendedSodium(manifest, statuses);
    }

    /**
     * What applying {@code selection} would do (nothing changes on disk); with {@code holdLifted}
     * the player switched a held-off Sodium item on, so the apply will not hold the family off.
     */
    @WorkerThread
    public PackSyncPlanner.Plan preview(Context context, PackSelection selection, boolean holdLifted) throws ContentException {
        if (manifest == null) throw new IllegalStateException("no pack");
        PackSyncPlanner.Options options = ProjectsPacks.syncOptions(context, manifest);
        if (holdLifted) options.heldOff(null);
        return PackSyncPlanner.plan(manifest, PackSyncPlanner.directoryProbe(gameDir), PackState.load(gameDir), selection, options);
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
        state.markItemsSeen(manifest, ItemResolver.MANIFEST_DEFAULTS);
        if (!state.sameAs(before)) state.save(gameDir);
    }

    public PackManifest.PackItem item(String id) {
        return manifest == null ? null : manifest.projects.item(id);
    }
}
