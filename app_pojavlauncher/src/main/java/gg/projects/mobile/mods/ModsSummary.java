package gg.projects.mobile.mods;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ItemResolver;
import gg.projects.mobile.content.PackChoice;
import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.PackState;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.content.SodiumSupport;
import gg.projects.mobile.join.ProjectsProfile;

/** The Projects card's "Modlar" row: how many mods are on and whether there is something new. */
public final class ModsSummary {
    private static final String TAG = "ProjectsMods";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** False when the pack has nothing to choose (the row is hidden). */
    public final boolean hasItems;
    /** Mods that are on (libraries not counted); -1 when unknown. */
    public final int enabled;
    public final int newItems;
    /** Sodium is offered to this set ({@link ModsModel#sodiumOffer}): the row says "Yeni" too. */
    public final boolean sodiumOffer;
    /** The followed pack's name, version and changelog (the patch notes box without patch notes), or null. */
    @Nullable
    public final String packName;
    @Nullable
    public final String packVersion;
    @Nullable
    public final String changelog;

    private ModsSummary(boolean hasItems, int enabled, int newItems) {
        this(hasItems, enabled, newItems, false, null);
    }

    private ModsSummary(boolean hasItems, int enabled, int newItems, boolean sodiumOffer, @Nullable PackManifest manifest) {
        this.hasItems = hasItems;
        this.enabled = enabled;
        this.newItems = newItems;
        this.sodiumOffer = sodiumOffer;
        String changelog = manifest == null || manifest.projects == null ? null : manifest.projects.changelog;
        boolean notes = changelog != null && !changelog.trim().isEmpty();
        this.packName = notes ? manifest.name : null;
        this.packVersion = notes ? manifest.versionId : null;
        this.changelog = notes ? changelog.trim() : null;
    }

    /** The row's "Yeni" pill: new recommendations, or Sodium offered again. */
    public boolean hasNews() {
        return newItems > 0 || sodiumOffer;
    }

    public interface Callback {
        void onSummary(@Nullable ModsSummary summary);
    }

    /** Computes the summary in the background; the callback runs on the main thread. */
    @MainThread
    public static void load(Context context, final Snapshot snapshot, final Callback callback) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            ModsSummary summary = compute(app, snapshot);
            MAIN.post(() -> callback.onSummary(summary));
        });
    }

    @Nullable
    private static ModsSummary compute(Context app, Snapshot snapshot) {
        try {
            File gameDir = ProjectsProfile.knownGameDir();
            PackState state = PackState.load(gameDir);
            PackChoice choice = snapshot.packChoice(state);
            if (choice.kind == PackChoice.Kind.NONE) return new ModsSummary(false, 0, 0);
            if (!choice.syncs()) return new ModsSummary(true, -1, 0);
            PackManifest manifest = ProjectsContent.repository(app).packManifest(snapshot, choice.pack);
            SodiumSupport.Block block = SodiumState.block(app);
            ItemResolver.Resolution resolution = ItemResolver.resolve(manifest, state, null,
                    Collections.<String, Boolean>emptyMap(), ItemResolver.MANIFEST_DEFAULTS, SodiumSupport.heldOff(manifest, block));
            int choices = 0;
            int enabled = 0;
            int fresh = 0;
            for (ItemResolver.ItemStatus status : resolution.items) {
                if (status.library) continue;
                choices++;
                if (status.effectivelyEnabled) enabled++;
                if (status.isNew) fresh++;
            }
            boolean offer = ModsModel.offerPending(app, manifest, state, resolution.items, block);
            return new ModsSummary(choices > 0, enabled, fresh, offer, manifest);
        } catch (ContentException | RuntimeException e) {
            Log.i(TAG, "mods summary unavailable: " + e);
            return null;
        }
    }
}
