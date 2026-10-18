package gg.projects.mobile.content;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which items of a pack a player gets (mirror of pl-content's items.rs). Every item is
 * optional; the player's choices live in the pack state ({@code items}) together with the ids
 * already shown ({@code seenItems}):
 *
 * <ul>
 * <li>a fresh set (no choices and nothing seen yet) starts from each item's default; an item
 * whose file the pack already installed counts as selected, so older sets keep their mods;</li>
 * <li>once choices exist, items added to the pack are new: off, not recorded, and flagged until
 * the player confirms the mod screen (any explicit {@link PackSelection});</li>
 * <li>choices are keyed by item id, so a new file name for the same mod keeps the choice;</li>
 * <li>{@code requires} is closed over: an enabled item switches on what it needs, even items
 * the player switched off; libraries are on exactly when an enabled item needs them;</li>
 * <li>choices adopted from the game directory rank below an explicit selection and above the
 * recorded choices.</li>
 * </ul>
 */
public final class ItemResolver {
    private ItemResolver() {}

    /** The choice a fresh set makes for an item nobody chose yet. */
    public interface Defaults {
        boolean enabledByDefault(PackManifest.PackItem item);
    }

    /** {@code defaultEnabled} as the manifest says (the desktop rule). */
    public static final Defaults MANIFEST_DEFAULTS = new Defaults() {
        @Override
        public boolean enabledByDefault(PackManifest.PackItem item) {
            return item.defaultEnabled;
        }
    };

    /** One item as the mod screen shows it. */
    public static final class ItemStatus {
        public final String id;
        /** The {@code files[].path} the item installs. */
        public final String path;
        /** Dependency only: not a choice, on when an enabled item requires it. */
        public final boolean library;
        /** The player's choice (false for libraries and for new items nobody chose yet). */
        public boolean selected;
        /** Installed by the sync: selected, or required by another enabled item. */
        public boolean effectivelyEnabled;
        /** Added after the player's set was created and not confirmed yet. */
        public boolean isNew;
        /** Enabled items that require this one, in manifest order. */
        public final List<String> requiredBy = new ArrayList<>();
        /** The pack state records a file of this item. */
        public boolean installed;
        /** Installed, but the manifest now has another file for it (new hash or file name). */
        public boolean updateAvailable;
        /** Size of the item's file in the manifest, in bytes. */
        public long size;
        /** The manual change a sync adopted and the player has not confirmed yet (set by the planner). */
        public String manual;
        /** The player replaced the file, so it is kept as it is (set by the planner). */
        public boolean modified;
        /** Switched off and kept as {@code <path>.disabled} (set by the planner). */
        public boolean disabledCopy;
        /**
         * Held off on this phone (Android: the Sodium family on a renderer that cannot run it, see
         * {@link SodiumSupport}): not installed whatever {@link #selected} says.
         */
        public boolean heldOff;

        ItemStatus(String id, String path, boolean library) {
            this.id = id;
            this.path = path;
            this.library = library;
        }
    }

    public static final class Resolution {
        /** One entry per manifest item, in manifest order. */
        public final List<ItemStatus> items = new ArrayList<>();
        /** Item id to selected, as the next pack state records it. */
        public final Map<String, Boolean> choices = new TreeMap<>();
        /** Item ids the next pack state counts as seen. */
        public final Set<String> seen = new TreeSet<>();

        public ItemStatus status(String id) {
            for (ItemStatus status : items) if (status.id.equals(id)) return status;
            return null;
        }

        /** True when the item will be installed. */
        public boolean isEnabled(String id) {
            ItemStatus status = status(id);
            return status != null && status.effectivelyEnabled;
        }

        public List<ItemStatus> newItems() {
            List<ItemStatus> out = new ArrayList<>();
            for (ItemStatus status : items) if (status.isNew) out.add(status);
            return out;
        }
    }

    /** The player's item set from the manifest, the previous state and (when confirmed) their selection. */
    public static Resolution resolve(PackManifest manifest, PackState previous, PackSelection selection) {
        return resolve(manifest, previous, selection, Collections.<String, Boolean>emptyMap(), MANIFEST_DEFAULTS);
    }

    /**
     * {@link #resolve(PackManifest, PackState, PackSelection)} plus choices adopted from the game
     * directory ({@code adopted}: item id to selected), which win over recorded choices and fresh
     * defaults but not over an explicit selection.
     */
    public static Resolution resolve(PackManifest manifest, PackState previous, PackSelection selection,
                                     Map<String, Boolean> adopted, Defaults defaults) {
        return resolve(manifest, previous, selection, adopted, defaults, Collections.<String>emptySet());
    }

    /**
     * {@link #resolve(PackManifest, PackState, PackSelection, Map, Defaults)} with items held off on
     * this device ({@code heldOff}, closed under "requires": everything that needs one of them is in
     * it too). Held items are not installed and their recorded choices are kept as they are, so they
     * come back once nothing holds them; an explicit selection switching one on wins.
     */
    public static Resolution resolve(PackManifest manifest, PackState previous, PackSelection selection,
                                     Map<String, Boolean> adopted, Defaults defaults, Set<String> heldOff) {
        List<PackManifest.PackItem> items = manifest.projects.items;
        PackState prev = previous != null ? previous : new PackState();
        Defaults fallback = defaults != null ? defaults : MANIFEST_DEFAULTS;
        Set<String> held = heldOff != null ? heldOff : Collections.<String>emptySet();
        boolean fresh = prev.items.isEmpty() && prev.seenItems.isEmpty();

        Resolution resolution = new Resolution();
        resolution.choices.putAll(prev.items);
        Set<String> selected = new HashSet<>();
        Set<String> chosen = new HashSet<>();
        for (PackManifest.PackItem item : items) {
            if (item.library) continue;
            Boolean choice = null;
            boolean explicit = selection != null && selection.items.get(item.id) != null;
            if (explicit) choice = selection.items.get(item.id);
            else if (adopted.get(item.id) != null) choice = adopted.get(item.id);
            else if (prev.items.get(item.id) != null) choice = prev.items.get(item.id);
            else if (fresh) choice = installedRecord(prev, item) != null || fallback.enabledByDefault(item);
            if (choice != null) {
                resolution.choices.put(item.id, choice);
                if (choice) chosen.add(item.id);
                if (choice && (explicit || !held.contains(item.id))) selected.add(item.id);
            }
        }

        Map<String, PackManifest.PackItem> byId = new LinkedHashMap<>();
        for (PackManifest.PackItem item : items) byId.put(item.id, item);
        Set<String> enabled = new HashSet<>(selected);
        Deque<String> pending = new ArrayDeque<>(selected);
        while (!pending.isEmpty()) {
            PackManifest.PackItem item = byId.get(pending.pop());
            if (item == null) continue;
            for (String required : item.requires) {
                if (byId.containsKey(required) && enabled.add(required)) pending.push(required);
            }
        }

        resolution.seen.addAll(prev.seenItems);
        if (fresh || selection != null) for (PackManifest.PackItem item : items) resolution.seen.add(item.id);

        for (PackManifest.PackItem item : items) {
            PackManifest.PackFile file = manifest.file(item.path);
            String expected = file == null ? null : file.sha1();
            Map.Entry<String, PackState.InstalledFile> record = installedRecord(prev, item);
            ItemStatus status = new ItemStatus(item.id, item.path, item.library);
            status.selected = chosen.contains(item.id);
            status.effectivelyEnabled = enabled.contains(item.id);
            status.heldOff = held.contains(item.id) && !status.effectivelyEnabled;
            status.isNew = !item.library && !fresh && !prev.seenItems.contains(item.id) && !prev.items.containsKey(item.id);
            for (PackManifest.PackItem other : items) {
                if (!other.id.equals(item.id) && enabled.contains(other.id) && other.requires.contains(item.id)) {
                    status.requiredBy.add(other.id);
                }
            }
            status.installed = record != null;
            status.updateAvailable = record != null && (!record.getKey().equals(item.path) || expected == null
                    || record.getValue().sha1 == null || !expected.equals(record.getValue().sha1.toLowerCase(Locale.ROOT)));
            status.size = file == null ? 0 : file.fileSize;
            resolution.items.add(status);
        }
        return resolution;
    }

    /**
     * The record of the file the pack installed for {@code item}: tagged with its id (any path,
     * so a renamed jar is found), or for states written before items, untagged at its path.
     */
    public static Map.Entry<String, PackState.InstalledFile> installedRecord(PackState state, PackManifest.PackItem item) {
        PackState.InstalledFile atPath = item.path == null ? null : state.files.get(item.path);
        if (atPath != null && (atPath.item == null || atPath.item.equals(item.id))) {
            return new AbstractMap.SimpleImmutableEntry<>(item.path, atPath);
        }
        for (Map.Entry<String, PackState.InstalledFile> entry : state.files.entrySet()) {
            if (item.id.equals(entry.getValue().item)) return entry;
        }
        return null;
    }
}
