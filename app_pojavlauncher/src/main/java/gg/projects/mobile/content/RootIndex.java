package gg.projects.mobile.content;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code v1/index.json}: the single signed root. Everything else is reached through it and
 * verified by the SHA-256 recorded here. Only what the mobile app uses is modelled; unknown
 * fields (AFK builds, future additions) are ignored.
 */
@Keep
public final class RootIndex {
    public int schema = 1;
    /** Strictly increasing publish counter (rollback protection). */
    public long sequence;
    public String generatedAt;
    public DocRef config;
    public DocRef news;
    public List<PackRef> packs;

    /** A JSON document referenced by the index, verified by size and SHA-256 after download. */
    @Keep
    public static final class DocRef {
        public String path;
        public String sha256;
        public long size;

        public void validate(String what) throws ContentException {
            if (path == null) throw new ContentException(ContentException.Kind.INVALID, what + " has no path");
            ContentPaths.checkMirrorPath(path);
            if (Hashes.normalizeHex(sha256, 64) == null) {
                throw new ContentException(ContentException.Kind.INVALID, "bad sha256 for " + path);
            }
            if (size < 0 || size > ContentConfig.MAX_DOCUMENT_BYTES) {
                throw new ContentException(ContentException.Kind.INVALID, "bad size for " + path);
            }
        }

        public String sha256() {
            return Hashes.normalizeHex(sha256, 64);
        }
    }

    /**
     * A modpack and the manifest of its latest version. Packs that share a {@code family} are one
     * pack published for several Minecraft versions ("Pack families" in content-format.md).
     */
    @Keep
    public static final class PackRef {
        public String id;
        public String name;
        public String latest;
        public DocRef manifest;
        /** Family id; absent means the pack is its own family. */
        public String family;
        /** {@code dependencies.minecraft} of the latest manifest. */
        public String minecraft;
        /** The family member new installs use (at most one per family). */
        @SerializedName("default")
        public boolean isDefault;

        /** The family id: {@code family}, else the pack id. */
        public String familyId() {
            return family != null && !family.isEmpty() ? family : id;
        }

        /** The Minecraft version, when the index records it; null otherwise. */
        public String minecraft() {
            return minecraft != null && !minecraft.isEmpty() ? minecraft : null;
        }
    }

    /** Parses and validates index bytes (the signature must already be verified). */
    public static RootIndex parse(byte[] bytes) throws ContentException {
        RootIndex index = ContentJson.parse(bytes, RootIndex.class, "index.json");
        index.validate();
        return index;
    }

    /**
     * Schema, every document reference and the family rules: valid family ids, at most one
     * default member per family, each Minecraft version at most once per family.
     */
    public void validate() throws ContentException {
        ContentJson.checkSchema("root index", schema, ContentConfig.ROOT_INDEX_SCHEMA);
        if (sequence < 0) throw invalid("negative sequence");
        if (Rfc3339.parse(generatedAt) == null) throw invalid("bad generatedAt " + generatedAt);
        if (config == null || news == null) throw invalid("index lacks config or news");
        config.validate("config");
        news.validate("news");
        if (packs == null) packs = new ArrayList<>();
        for (PackRef pack : packs) {
            if (pack == null || pack.id == null || pack.id.isEmpty() || pack.manifest == null) {
                throw invalid("malformed pack entry");
            }
            pack.manifest.validate("pack " + pack.id);
        }
        Map<String, String> defaults = new HashMap<>();
        Map<String, String> versions = new HashMap<>();
        for (PackRef pack : packs) {
            if (pack.family != null && !ContentIds.isValid(pack.family)) {
                throw invalid("pack " + pack.id + " has a bad family id");
            }
            String family = pack.familyId();
            if (pack.isDefault) {
                String other = defaults.put(family, pack.id);
                if (other != null) throw invalid("family " + family + " has two default packs (" + other + ", " + pack.id + ")");
            }
            String minecraft = pack.minecraft();
            if (minecraft != null) {
                String other = versions.put(family + '\n' + minecraft, pack.id);
                if (other != null) {
                    throw invalid("family " + family + " lists Minecraft " + minecraft + " twice (" + other + ", " + pack.id + ")");
                }
            }
        }
    }

    public PackRef pack(String id) {
        if (id == null || packs == null) return null;
        for (PackRef pack : packs) if (id.equals(pack.id)) return pack;
        return null;
    }

    /** Every document this index references (config, news, pack manifests). */
    public List<DocRef> documents() {
        List<DocRef> docs = new ArrayList<>();
        docs.add(config);
        docs.add(news);
        if (packs != null) for (PackRef pack : packs) docs.add(pack.manifest);
        return docs;
    }

    /** Distinct family ids, in index order. */
    public List<String> families() {
        List<String> families = new ArrayList<>();
        if (packs != null) {
            for (PackRef pack : packs) if (!families.contains(pack.familyId())) families.add(pack.familyId());
        }
        return families;
    }

    /**
     * The members of a family, newest first: by Minecraft version (members without one last),
     * then by {@code latest} (semver), then in index order.
     */
    public List<PackRef> familyMembers(String family) {
        List<PackRef> members = new ArrayList<>();
        if (packs == null || family == null) return members;
        for (PackRef pack : packs) if (family.equals(pack.familyId())) members.add(pack);
        // Collections.sort is stable: equal members keep their index order.
        Collections.sort(members, new Comparator<PackRef>() {
            @Override
            public int compare(PackRef a, PackRef b) {
                return compareMembers(b, a);
            }
        });
        return members;
    }

    /** The member a new install of the family uses: the one marked default, else the newest. */
    public PackRef familyDefault(String family) {
        List<PackRef> members = familyMembers(family);
        for (PackRef member : members) if (member.isDefault) return member;
        return members.isEmpty() ? null : members.get(0);
    }

    /** The member of {@code family} published for a Minecraft version, or null. */
    public PackRef familyMember(String family, String minecraft) {
        if (packs == null || family == null || minecraft == null) return null;
        for (PackRef pack : packs) {
            if (family.equals(pack.familyId()) && minecraft.equals(pack.minecraft())) return pack;
        }
        return null;
    }

    /**
     * The pack a new install follows. {@code recommendedPack} (launcher config) is a pack id:
     * that pack, else the default member of the first pack's family, else the first pack.
     * A family id in {@code recommendedPack} is not followed (Studio publishes pack ids).
     */
    public PackRef newInstallPack(String recommendedPack) {
        PackRef named = pack(recommendedPack);
        if (named != null) return named;
        if (packs == null || packs.isEmpty()) return null;
        PackRef fallback = familyDefault(packs.get(0).familyId());
        return fallback != null ? fallback : packs.get(0);
    }

    /** Older first: by Minecraft version, then by pack version. */
    private static int compareMembers(PackRef a, PackRef b) {
        String x = a.minecraft();
        String y = b.minecraft();
        int byMinecraft;
        if (x != null && y != null) byMinecraft = MinecraftVersions.compare(x, y);
        else if (x != null) byMinecraft = 1;
        else if (y != null) byMinecraft = -1;
        else byMinecraft = 0;
        if (byMinecraft != 0) return byMinecraft;
        return Semver.compare(a.latest, b.latest);
    }

    private static ContentException invalid(String message) {
        return new ContentException(ContentException.Kind.INVALID, message);
    }
}
