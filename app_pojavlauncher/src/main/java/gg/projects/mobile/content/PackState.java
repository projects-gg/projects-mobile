package gg.projects.mobile.content;

import androidx.annotation.Keep;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the launcher installed into a game directory and the player's choices:
 * {@code <game dir>/.projects/pack-state.json}, the same file and shape the desktop launcher
 * writes ("Sync semantics and pack state" in content-format.md). Every field is optional.
 */
@Keep
public final class PackState {
    public static final String FILE = "pack-state.json";

    /** Manual changes a sync adopted ({@link #manual} values). */
    public static final String MANUAL_REMOVED = "removed";
    public static final String MANUAL_DISABLED = "disabled";
    public static final String MANUAL_ENABLED = "enabled";
    /** The player replaced an item's file (reported once, never stored in {@link #manual}). */
    public static final String MANUAL_REPLACED = "replaced";

    public String packId = "";
    public String versionId = "";
    /** Family of the installed pack ({@code projects.family}, else the pack id). */
    public String family = "";
    /** Minecraft version of the installed pack. */
    public String minecraft = "";
    /** Relative path to the file as we last installed it. */
    public Map<String, InstalledFile> files = new TreeMap<>();
    /** Legacy optional group id to enabled. */
    public Map<String, Boolean> groups = new TreeMap<>();
    /** Item id to the player's choice. */
    public Map<String, Boolean> items = new TreeMap<>();
    /** Item ids the player has been shown; manifest items missing here are new. */
    public Set<String> seenItems = new TreeSet<>();
    /** Item id to a manual change the player has not confirmed in the mod screen yet. */
    public Map<String, String> manual = new TreeMap<>();

    @Keep
    public static final class InstalledFile {
        public String sha1;
        public String policy;
        /** The item that installed the file (none for files outside the catalog). */
        public String item;
        /** The player switched the item off by renaming the file to {@code <path>.disabled}. */
        public boolean disabled;
        /** At the last sync the file on disk was not this one: the player replaced it. */
        public boolean modified;

        public InstalledFile() {}

        public InstalledFile(String sha1, String policy) {
            this(sha1, policy, null);
        }

        public InstalledFile(String sha1, String policy, String item) {
            this.sha1 = sha1;
            this.policy = policy;
            this.item = item;
        }

        public InstalledFile copy() {
            InstalledFile copy = new InstalledFile(sha1, policy, item);
            copy.disabled = disabled;
            copy.modified = modified;
            return copy;
        }
    }

    public static File path(File gameDir) {
        return new File(new File(gameDir, ContentPaths.LAUNCHER_DIR), FILE);
    }

    /** Parses a state; missing fields get their defaults, unknown ones are ignored. */
    public static PackState parse(byte[] bytes) throws ContentException {
        PackState state = ContentJson.parse(bytes, PackState.class, FILE);
        state.normalize();
        return state;
    }

    /** The recorded state, or null when there is none or it is corrupt (then nothing is deleted). */
    public static PackState load(File gameDir) {
        File file = path(gameDir);
        if (!file.isFile()) return null;
        try {
            return parse(AtomicFiles.read(file, 16L << 20));
        } catch (IOException | ContentException e) {
            return null;
        }
    }

    public void save(File gameDir) throws IOException {
        AtomicFiles.write(path(gameDir), toJson().getBytes(StandardCharsets.UTF_8));
    }

    /** Defaults for missing fields and sorted collections, like the desktop's serde defaults. */
    private void normalize() {
        if (packId == null) packId = "";
        if (versionId == null) versionId = "";
        if (family == null) family = "";
        if (minecraft == null) minecraft = "";
        Map<String, InstalledFile> sortedFiles = new TreeMap<>();
        if (files != null) {
            for (Map.Entry<String, InstalledFile> entry : files.entrySet()) {
                InstalledFile record = entry.getValue();
                if (entry.getKey() == null || record == null) continue;
                record.policy = record.policy == null ? PackManifest.POLICY_OVERWRITE : PackManifest.normalizePolicy(record.policy);
                if (record.sha1 == null) record.sha1 = "";
                sortedFiles.put(entry.getKey(), record);
            }
        }
        files = sortedFiles;
        groups = sortedBooleans(groups);
        items = sortedBooleans(items);
        Set<String> seen = new TreeSet<>();
        if (seenItems != null) for (String id : seenItems) if (id != null) seen.add(id);
        seenItems = seen;
        Map<String, String> changes = new TreeMap<>();
        if (manual != null) {
            for (Map.Entry<String, String> entry : manual.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) changes.put(entry.getKey(), entry.getValue());
            }
        }
        manual = changes;
    }

    private static Map<String, Boolean> sortedBooleans(Map<String, Boolean> map) {
        Map<String, Boolean> sorted = new TreeMap<>();
        if (map != null) {
            for (Map.Entry<String, Boolean> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) sorted.put(entry.getKey(), entry.getValue());
            }
        }
        return sorted;
    }

    /**
     * Pretty JSON in the desktop's field order; empty optional fields are left out, so a state
     * of a pack without items keeps its old shape.
     */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("packId", packId);
        root.addProperty("versionId", versionId);
        if (!family.isEmpty()) root.addProperty("family", family);
        if (!minecraft.isEmpty()) root.addProperty("minecraft", minecraft);
        JsonObject filesJson = new JsonObject();
        for (Map.Entry<String, InstalledFile> entry : new TreeMap<>(files).entrySet()) {
            InstalledFile record = entry.getValue();
            JsonObject file = new JsonObject();
            file.addProperty("sha1", record.sha1);
            file.addProperty("policy", record.policy == null ? PackManifest.POLICY_OVERWRITE : record.policy);
            if (record.item != null) file.addProperty("item", record.item);
            if (record.disabled) file.addProperty("disabled", true);
            if (record.modified) file.addProperty("modified", true);
            filesJson.add(entry.getKey(), file);
        }
        root.add("files", filesJson);
        root.add("groups", booleans(groups));
        if (!items.isEmpty()) root.add("items", booleans(items));
        if (!seenItems.isEmpty()) {
            JsonArray seen = new JsonArray();
            for (String id : new TreeSet<>(seenItems)) seen.add(id);
            root.add("seenItems", seen);
        }
        if (!manual.isEmpty()) {
            JsonObject changes = new JsonObject();
            for (Map.Entry<String, String> entry : new TreeMap<>(manual).entrySet()) {
                changes.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("manual", changes);
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    private static JsonObject booleans(Map<String, Boolean> map) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Boolean> entry : new TreeMap<>(map).entrySet()) out.addProperty(entry.getKey(), entry.getValue());
        return out;
    }

    public PackState copy() {
        PackState copy = new PackState();
        copy.packId = packId;
        copy.versionId = versionId;
        copy.family = family;
        copy.minecraft = minecraft;
        for (Map.Entry<String, InstalledFile> entry : files.entrySet()) copy.files.put(entry.getKey(), entry.getValue().copy());
        copy.groups.putAll(groups);
        copy.items.putAll(items);
        copy.seenItems.addAll(seenItems);
        copy.manual.putAll(manual);
        return copy;
    }

    /** Same content (what the file would contain). */
    public boolean sameAs(PackState other) {
        return other != null && toJson().equals(other.toJson());
    }

    /**
     * The player looked at the mod screen without applying anything: records the current
     * choices, marks every item of {@code manifest} as seen (no longer new) and acknowledges
     * adopted manual changes, without touching files. Save the state afterwards.
     */
    public void markItemsSeen(PackManifest manifest, ItemResolver.Defaults defaults) {
        ItemResolver.Resolution resolution = ItemResolver.resolve(manifest, this, new PackSelection(),
                new TreeMap<String, Boolean>(), defaults);
        items = new TreeMap<>(resolution.choices);
        seenItems = new TreeSet<>(resolution.seen);
        manual = new TreeMap<>();
    }
}
