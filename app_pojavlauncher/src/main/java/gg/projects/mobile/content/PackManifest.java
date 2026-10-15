package gg.projects.mobile.content;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code packs/<id>/<version>.json}: a Modrinth {@code .mrpack} index plus the {@code projects}
 * extension (mirror of pl-content's pack.rs).
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
        public String changelog;
        public List<OptionalGroup> optionalGroups;
        public ConfigPolicy configPolicy;
        public List<PackServer> servers;
        public Integer recommendedMemoryMb;
        public String minLauncherVersion;
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

    public static PackManifest parse(byte[] bytes) throws ContentException {
        PackManifest manifest = ContentJson.parse(bytes, PackManifest.class, "pack manifest");
        manifest.validate();
        return manifest;
    }

    /**
     * Structural validation before anything touches the disk: format version, game, safe and
     * unique paths (case-insensitively, no file/folder clashes), hash formats, HTTP(S) download
     * URLs and unique group ids.
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
    }

    public String minecraftVersion() {
        return dependencies.get("minecraft");
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
    static String normalizePolicy(String policy) {
        if (POLICY_OVERWRITE.equals(policy) || POLICY_IF_MISSING.equals(policy)) return policy;
        return POLICY_PRESERVE;
    }

    private static ContentException invalid(String message) {
        return new ContentException(ContentException.Kind.INVALID, message);
    }
}
