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
import gg.projects.mobile.content.AndroidSupport;
import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ItemResolver;
import gg.projects.mobile.content.PackChoice;
import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.PackState;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.join.ProjectsProfile;

/** The Projects card's "Modlar" row: how many mods are on and whether there are new ones. */
public final class ModsSummary {
    private static final String TAG = "ProjectsMods";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** False when the pack has nothing to choose (the row is hidden). */
    public final boolean hasItems;
    /** Mods that are on (libraries not counted); -1 when unknown. */
    public final int enabled;
    public final int newItems;

    private ModsSummary(boolean hasItems, int enabled, int newItems) {
        this.hasItems = hasItems;
        this.enabled = enabled;
        this.newItems = newItems;
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
            ItemResolver.Resolution resolution = ItemResolver.resolve(manifest, state, null,
                    Collections.<String, Boolean>emptyMap(), AndroidSupport.defaults(manifest));
            int choices = 0;
            int enabled = 0;
            int fresh = 0;
            for (ItemResolver.ItemStatus status : resolution.items) {
                if (status.library) continue;
                choices++;
                if (status.effectivelyEnabled) enabled++;
                if (status.isNew) fresh++;
            }
            return new ModsSummary(choices > 0, enabled, fresh);
        } catch (ContentException | RuntimeException e) {
            Log.i(TAG, "mods summary unavailable: " + e);
            return null;
        }
    }
}
