package gg.projects.mobile.join;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.content.ItemResolver;
import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.PackSyncPlanner;

/**
 * What a sync before a launch has to tell the player without blocking (the desktop's
 * ModsNoticeView): changes adopted from the mods folder, a wiped mods folder that was
 * reinstalled, files that could not be read, or a Minecraft version the pack no longer offers.
 */
public final class SyncNotice {
    /** A manual change the sync adopted. */
    public static final class Change {
        public final String title;
        /** One of the {@code PackState.MANUAL_*} values. */
        public final String change;
        /** Titles of enabled mods that need it (why it came back). */
        public final List<String> requiredBy;

        Change(String title, String change, List<String> requiredBy) {
            this.title = title;
            this.change = change;
            this.requiredBy = Collections.unmodifiableList(requiredBy);
        }
    }

    /** Set when the family no longer offers the installed Minecraft version. */
    @Nullable
    public final String droppedMinecraft;
    public final boolean reset;
    public final List<Change> changes;
    public final int issues;

    private SyncNotice(@Nullable String droppedMinecraft, boolean reset, List<Change> changes, int issues) {
        this.droppedMinecraft = droppedMinecraft;
        this.reset = reset;
        this.changes = Collections.unmodifiableList(changes);
        this.issues = issues;
    }

    static SyncNotice dropped(@Nullable String minecraft) {
        return new SyncNotice(minecraft == null ? "" : minecraft, false, new ArrayList<Change>(), 0);
    }

    /** The notice for a sync, or null when there is nothing to say. {@code failed}: renames and deletions that failed. */
    @Nullable
    public static SyncNotice from(PackManifest manifest, PackSyncPlanner.Plan plan, int failed) {
        List<Change> changes = new ArrayList<>();
        for (PackSyncPlanner.Adopted adopted : plan.adopted) {
            PackManifest.PackItem item = manifest.projects.item(adopted.item);
            if (item == null) continue;
            List<String> requiredBy = new ArrayList<>();
            ItemResolver.ItemStatus status = plan.status(adopted.item);
            if (status != null && status.effectivelyEnabled && !status.selected) {
                for (String id : status.requiredBy) {
                    PackManifest.PackItem other = manifest.projects.item(id);
                    if (other != null) requiredBy.add(other.title);
                }
            }
            changes.add(new Change(item.title, adopted.change, requiredBy));
        }
        int issues = plan.issues.size() + failed;
        if (changes.isEmpty() && !plan.reset && issues == 0) return null;
        return new SyncNotice(null, plan.reset, changes, issues);
    }
}
