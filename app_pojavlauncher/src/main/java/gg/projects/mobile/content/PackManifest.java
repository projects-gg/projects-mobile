package gg.projects.mobile.content;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code packs/<id>/<version>.json}: a Modrinth {@code .mrpack} index plus the {@code projects}
 * extension (mirror of pl-content's pack.rs). Unknown fields are ignored.
 */
@Keep
public final class PackManifest {
    public int formatVersion;
    public String game = "minecraft";
    public String versionId;
    public String name;
    public String summary;
    public List<PackFile> files;
    /** {@code minecraft} plus at most one of fabric-loader, quilt-loader, forge, neoforge. */
    public Map<String, String> dependencies;
    public Projects projects;

    @Keep
    public static final class PackFile {
        public String path;
        public FileHashes hashes;
        public Env env;
        public List<String> downloads;
        public long fileSize;

        /** Client requirement; {@code required} when {@code env} is absent or unknown. */
        public String clientSupport() {
            String client = env == null ? null : env.client;
            if ("optional".equals(client) || "unsupported".equals(client)) return client;
            return "required";
        }

        public String sha1() {
            return Hashes.normalizeHex(hashes.sha1, 40);
        }

        public String sha512() {
            return hashes.sha512 == null ? null : Hashes.normalizeHex(hashes.sha512, 128);
        }
    }

    @Keep
    public static final class FileHashes {
        public String sha1;
        public String sha512;
    }

    @Keep
    public static final class Env {
        public String client;
        public String server;
    }

    @Keep
    public static final class Projects {
        public String packId;
        /** The pack family; absent means {@code packId}. */
        public String family;
        public String changelog;
        public List<OptionalGroup> optionalGroups;
        /** The mod center's catalog: every item is optional. */
        public List<PackItem> items;
        public ConfigPolicy configPolicy;
        public List<PackServer> servers;
        public Integer recommendedMemoryMb;
        public String minLauncherVersion;

        /** The family id: {@code family}, else the pack id. */
        public String familyId() {
            if (family != null && !family.isEmpty()) return family;
            return packId == null ? "" : packId;
        }

        public PackItem item(String id) {
            if (id == null || items == null) return null;
            for (PackItem item : items) if (id.equals(item.id)) return item;
            return null;
        }

        /** The item that installs {@code path}, if any. */
        public PackItem itemForPath(String path) {
            if (path == null || items == null) return null;
            for (PackItem item : items) if (path.equals(item.path)) return item;
            return null;
        }
    }

    /** One entry of the mod center: a {@code files[]} entry the player can switch on or off. */
    @Keep
    public static final class PackItem {
        /** Stable id (Modrinth project id or a slug); choices are keyed by it. */
        public String id;
        /** The {@code files[].path} this item installs. */
        public String path;
        public String title;
        public String summary;
        /** Display section; see {@link #categoryOrDefault()}. */
        public String category;
        /** {@code https://} icon. */
        public String iconUrl;
        public ModrinthRef modrinth;
        /** Selected on a brand-new install only. */
        public boolean defaultEnabled = true;
        /** Ids of items installed together with this one. */
        public List<String> requires;
        /** Dependency only: on exactly when an enabled item requires it, never a choice. */
        public boolean library;

        /** The category, or {@code other} for a missing or unknown one. */
        public String categoryOrDefault() {
            if (category != null) {
                switch (category) {
                    case CATEGORY_PERFORMANCE:
                    case CATEGORY_VISUAL:
                    case CATEGORY_INTERFACE:
                    case CATEGORY_GAMEPLAY:
                    case CATEGORY_LIBRARY:
                        return category;
                    default:
                        break;
                }
            }
            return CATEGORY_OTHER;
        }

        public String fileName() {
            int slash = path == null ? -1 : path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    @Keep
    public static final class ModrinthRef {
        public String projectId;
        public String versionId;
    }

    @Keep
    public static final class OptionalGroup {
        public String id;
        public String name;
        public String description;
        public boolean defaultEnabled;
        public List<String> files;
    }

    @Keep
    public static final class ConfigPolicy {
        @SerializedName("default")
        public String defaultPolicy;
        public List<PolicyRule> rules;
    }

    @Keep
    public static final class PolicyRule {
        public String glob;
        public String policy;
    }

    @Keep
    public static final class PackServer {
        public String name;
        public String address;
    }

    public static final String POLICY_OVERWRITE = "overwrite";
    public static final String POLICY_IF_MISSING = "ifMissing";
    public static final String POLICY_PRESERVE = "preserveUserChanges";

    public static final String CATEGORY_PERFORMANCE = "performance";
    public static final String CATEGORY_VISUAL = "visual";
    public static final String CATEGORY_INTERFACE = "interface";
    public static final String CATEGORY_GAMEPLAY = "gameplay";
    public static final String CATEGORY_LIBRARY = "library";
    public static final String CATEGORY_OTHER = "other";

    public static PackManifest parse(byte[] bytes) throws ContentException {
        PackManifest manifest = ContentJson.parse(bytes, PackManifest.class, "pack manifest");
        manifest.validate();
        return manifest;
    }

    /**
     * Structural validation before anything touches the disk: format version, game, safe and
     * unique paths (case-insensitively, no file/folder clashes), hash formats, HTTP(S) download
     * URLs, unique group ids, the family id and the item catalog (see {@link #validateItems()}).
     * Idempotent: missing lists become empty ones.
     */
    public void validate() throws ContentException {
        if (formatVersion != ContentConfig.MRPACK_FORMAT_VERSION) {
            throw new ContentException(ContentException.Kind.UNSUPPORTED_SCHEMA, "pack formatVersion " + formatVersion);
        }
        if (!"minecraft".equals(game)) throw invalid("unsupported game " + game);
        if (versionId == null || versionId.isEmpty()) throw invalid("pack without versionId");
        if (dependencies == null || dependencies.get("minecraft") == null || dependencies.get("minecraft").isEmpty()) {
            throw invalid("pack without a Minecraft version");
        }
        if (files == null) files = new ArrayList<>();
        if (projects == null) projects = new Projects();
        if (projects.optionalGroups == null) projects.optionalGroups = new ArrayList<>();
        if (projects.items == null) projects.items = new ArrayList<>();
        if (projects.servers == null) projects.servers = new ArrayList<>();
        if (projects.configPolicy == null) projects.configPolicy = new ConfigPolicy();
        if (projects.configPolicy.rules == null) projects.configPolicy.rules = new ArrayList<>();

        Set<String> seen = new HashSet<>();
        for (PackFile file : files) {
            if (file == null || file.path == null || file.hashes == null) throw invalid("malformed pack file entry");
            ContentPaths.checkRelativePath(file.path);
            if (!seen.add(file.path.toLowerCase(Locale.ROOT))) throw invalid("duplicate pack path " + file.path);
            if (file.sha1() == null) throw invalid("bad sha1 for " + file.path);
            if (file.hashes.sha512 != null && file.sha512() == null) throw invalid("bad sha512 for " + file.path);
            if (file.downloads == null || file.downloads.isEmpty()) throw invalid("bad download URLs for " + file.path);
            for (String url : file.downloads) {
                if (!ContentPaths.isHttpUrl(url)) throw invalid("bad download URLs for " + file.path);
            }
            if (file.fileSize < 0) throw invalid("bad size for " + file.path);
        }
        for (String path : seen) {
            int slash = path.indexOf('/');
            while (slash >= 0) {
                if (seen.contains(path.substring(0, slash))) {
                    throw invalid(path.substring(0, slash) + " is both a file and a folder");
                }
                slash = path.indexOf('/', slash + 1);
            }
        }
        Set<String> groups = new HashSet<>();
        for (OptionalGroup group : projects.optionalGroups) {
            if (group == null || group.id == null || group.id.isEmpty() || !groups.add(group.id)) {
                throw invalid("bad optional group id");
            }
            if (group.files == null) group.files = new ArrayList<>();
        }
        if (projects.family != null && !ContentIds.isValid(projects.family)) {
            throw invalid("bad pack family id " + projects.family);
        }
        validateItems();
    }

    /**
     * Item rules (a manifest that breaks one is refused): valid and unique ids, each item points
     * at a different, client-side {@code files[]} entry, has a title and an {@code https://} icon
     * (if any), {@code requires} names existing items and has no cycles.
     */
    void validateItems() throws ContentException {
        List<PackItem> items = projects.items;
        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (PackItem item : items) {
            if (item == null) throw invalid("malformed item entry");
            if (item.requires == null) item.requires = new ArrayList<>();
            if (!ContentIds.isValid(item.id) || !ids.add(item.id)) throw invalid("bad or duplicate item id " + item.id);
            PackFile file = file(item.path);
            if (file == null) throw invalid("item " + item.id + " points at " + item.path + ", which is not a pack file");
            if (!paths.add(item.path)) throw invalid("two items install " + item.path);
            if ("unsupported".equals(file.clientSupport())) throw invalid("item " + item.id + " installs a server-only file");
            if (item.title == null || item.title.trim().isEmpty()) throw invalid("item " + item.id + " has no title");
            if (item.iconUrl != null && !ContentPaths.isHttpsUrl(item.iconUrl)) {
                throw invalid("item " + item.id + " has a non-HTTPS icon");
            }
        }
        for (PackItem item : items) {
            for (String required : item.requires) {
                if (required == null || !ids.contains(required)) {
                    throw invalid("item " + item.id + " requires the unknown item " + required);
                }
            }
        }
        String cycle = requiresCycle(items);
        if (cycle != null) throw invalid("item " + cycle + " is part of a requires cycle");
    }

    /** An item on a {@code requires} cycle (depth-first search), or null. */
    private static String requiresCycle(List<PackItem> items) {
        Map<String, PackItem> byId = new HashMap<>();
        for (PackItem item : items) byId.put(item.id, item);
        Set<String> done = new HashSet<>();
        for (PackItem start : items) {
            if (done.contains(start.id)) continue;
            // The current path: item ids and the index of the next requirement to visit.
            List<String> path = new ArrayList<>();
            List<Integer> next = new ArrayList<>();
            Set<String> onPath = new HashSet<>();
            path.add(start.id);
            next.add(0);
            onPath.add(start.id);
            while (!path.isEmpty()) {
                int top = path.size() - 1;
                String id = path.get(top);
                PackItem item = byId.get(id);
                List<String> requires = item == null ? new ArrayList<String>() : item.requires;
                int index = next.get(top);
                if (index >= requires.size()) {
                    path.remove(top);
                    next.remove(top);
                    onPath.remove(id);
                    done.add(id);
                    continue;
                }
                next.set(top, index + 1);
                String required = requires.get(index);
                if (onPath.contains(required)) return required;
                if (!done.contains(required) && byId.containsKey(required)) {
                    onPath.add(required);
                    path.add(required);
                    next.add(0);
                }
            }
        }
        return null;
    }

    public String minecraftVersion() {
        return dependencies.get("minecraft");
    }

    /** The {@code files[]} entry at {@code path}, or null. */
    public PackFile file(String path) {
        if (path == null || files == null) return null;
        for (PackFile file : files) if (path.equals(file.path)) return file;
        return null;
    }

    /** Policy for a path: rules top to bottom, first match wins, otherwise the default. */
    public String policyFor(String path) {
        ConfigPolicy policy = projects.configPolicy;
        for (PolicyRule rule : policy.rules) {
            if (rule != null && rule.glob != null && Glob.matches(rule.glob, path)) return normalizePolicy(rule.policy);
        }
        return policy.defaultPolicy == null ? POLICY_OVERWRITE : normalizePolicy(policy.defaultPolicy);
    }

    /** Unknown policies from a newer Studio behave like preserveUserChanges (the safest). */
    public static String normalizePolicy(String policy) {
        if (POLICY_OVERWRITE.equals(policy) || POLICY_IF_MISSING.equals(policy)) return policy;
        return POLICY_PRESERVE;
    }

    private static ContentException invalid(String message) {
        return new ContentException(ContentException.Kind.INVALID, message);
    }
}
