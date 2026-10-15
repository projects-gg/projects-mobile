package gg.projects.mobile.content;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.List;

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

    /** A modpack and the manifest of its latest version. */
    @Keep
    public static final class PackRef {
        public String id;
        public String name;
        public String latest;
        public DocRef manifest;
    }

    /** Parses and validates index bytes (the signature must already be verified). */
    public static RootIndex parse(byte[] bytes) throws ContentException {
        RootIndex index = ContentJson.parse(bytes, RootIndex.class, "index.json");
        index.validate();
        return index;
    }

    /** Schema plus every document reference. */
    public void validate() throws ContentException {
        ContentJson.checkSchema("root index", schema, ContentConfig.ROOT_INDEX_SCHEMA);
        if (sequence < 0) throw new ContentException(ContentException.Kind.INVALID, "negative sequence");
        if (Rfc3339.parse(generatedAt) == null) {
            throw new ContentException(ContentException.Kind.INVALID, "bad generatedAt " + generatedAt);
        }
        if (config == null || news == null) {
            throw new ContentException(ContentException.Kind.INVALID, "index lacks config or news");
        }
        config.validate("config");
        news.validate("news");
        if (packs == null) packs = new ArrayList<>();
        for (PackRef pack : packs) {
            if (pack == null || pack.id == null || pack.id.isEmpty() || pack.manifest == null) {
                throw new ContentException(ContentException.Kind.INVALID, "malformed pack entry");
            }
            pack.manifest.validate("pack " + pack.id);
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
}
