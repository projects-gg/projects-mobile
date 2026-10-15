package gg.projects.mobile.content;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetches, verifies and caches Projects content (client rules of docs/content-format.md):
 *
 * <ol>
 * <li>{@code index.json} and {@code index.json.sig} come from the first mirror that serves a
 * validly signed index. On a signature mismatch both are fetched once more with {@code ?t=}
 * to get past edge caches, then the next mirror is tried.</li>
 * <li>The index schema must be supported and every document reference well formed.</li>
 * <li>Rollback protection: the sequence must grow; an equal sequence is accepted only with
 * identical bytes.</li>
 * <li>config and news are downloaded (preferring the same mirror) and verified by size and
 * SHA-256 before the new index replaces the cached one.</li>
 * </ol>
 *
 * When every mirror fails the last verified snapshot stays in use, flagged offline. Content is
 * never returned unless its signature verifies against a trusted key.
 */
public final class ContentRepository {
    public interface Clock {
        long now();
    }

    private final ContentStore store;
    private final HttpFetcher fetcher;
    private final IndexVerifier verifier;
    private final List<String> mirrors;
    private final Clock clock;

    public ContentRepository(ContentStore store, HttpFetcher fetcher, IndexVerifier verifier,
                             List<String> mirrors, Clock clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.verifier = verifier;
        this.mirrors = new ArrayList<>(mirrors);
        this.clock = clock;
    }

    private static final class Cached {
        final Snapshot snapshot;
        final byte[] indexBytes;

        Cached(Snapshot snapshot, byte[] indexBytes) {
            this.snapshot = snapshot;
            this.indexBytes = indexBytes;
        }
    }

    /** The cached snapshot, re-verified (flagged offline until a refresh succeeds), or an empty one. */
    public synchronized Snapshot loadCached() {
        Cached cached = readCached();
        return cached != null ? cached.snapshot : Snapshot.empty(null);
    }

    private Cached readCached() {
        ContentStore.Current current = store.readCurrent();
        if (current == null) return null;
        try {
            verifier.verify(current.index, current.signature);
            RootIndex index = RootIndex.parse(current.index);
            LauncherConfig config = null;
            NewsFeed news = null;
            byte[] configBytes = store.readDoc(index.config);
            if (configBytes != null) config = LauncherConfig.parse(configBytes);
            byte[] newsBytes = store.readDoc(index.news);
            if (newsBytes != null) news = NewsFeed.parse(newsBytes);
            Snapshot snapshot = new Snapshot(index, config, news, true, current.fetchedAt, current.mirror, null);
            return new Cached(snapshot, current.index);
        } catch (ContentException e) {
            // A cache that no longer verifies (e.g. a key was dropped) is as good as none.
            return null;
        }
    }

    /** Refreshes from the mirrors. Never throws: failures fall back to the cache, flagged offline. */
    public synchronized Snapshot refresh() {
        Cached cached = readCached();
        ContentException lastError = null;
        for (String mirror : mirrors) {
            try {
                return refreshFrom(mirror, cached);
            } catch (ContentException e) {
                lastError = e;
            }
        }
        if (cached != null) return cached.snapshot.asOffline(lastError);
        return Snapshot.empty(lastError);
    }

    private Snapshot refreshFrom(String mirror, Cached cached) throws ContentException {
        byte[] indexBytes = null;
        byte[] signatureBytes = null;
        RootIndex index = null;
        for (int attempt = 0; attempt < 2 && index == null; attempt++) {
            String query = attempt == 0 ? "" : "?t=" + clock.now();
            indexBytes = get(mirror + "index.json" + query, ContentConfig.MAX_INDEX_BYTES);
            signatureBytes = get(mirror + "index.json.sig" + query, ContentConfig.MAX_SIGNATURE_BYTES);
            try {
                verifier.verify(indexBytes, signatureBytes);
            } catch (ContentException e) {
                boolean signatureProblem = e.kind == ContentException.Kind.BAD_SIGNATURE
                        || e.kind == ContentException.Kind.UNTRUSTED_KEY;
                if (attempt == 0 && signatureProblem) continue; // stale edge cache: retry once
                throw e;
            }
            index = RootIndex.parse(indexBytes);
        }
        if (index == null) throw new ContentException(ContentException.Kind.BAD_SIGNATURE, "no verified index on " + mirror);

        if (cached != null) {
            long known = cached.snapshot.index.sequence;
            if (index.sequence < known) {
                throw new ContentException(ContentException.Kind.ROLLBACK,
                        "mirror " + mirror + " serves sequence " + index.sequence + " < " + known);
            }
            if (index.sequence == known && !Arrays.equals(indexBytes, cached.indexBytes)) {
                throw new ContentException(ContentException.Kind.ROLLBACK,
                        "mirror " + mirror + " serves different bytes for sequence " + known);
            }
        }

        byte[] configBytes = document(index.config, mirror);
        byte[] newsBytes = document(index.news, mirror);
        LauncherConfig config = LauncherConfig.parse(configBytes);
        NewsFeed news = NewsFeed.parse(newsBytes);

        long now = clock.now();
        try {
            store.writeDoc(index.config, configBytes);
            store.writeDoc(index.news, newsBytes);
            store.writeCurrent(new ContentStore.Current(indexBytes, signatureBytes, now, mirror));
        } catch (IOException e) {
            // Disk trouble does not make the content less valid; it just is not cached.
        }
        prune(index, news, cached);
        return new Snapshot(index, config, news, false, now, mirror, null);
    }

    /** Keeps documents and media referenced by the new or the previous snapshot. */
    private void prune(RootIndex index, NewsFeed news, Cached cached) {
        Set<String> keep = new HashSet<>();
        collect(keep, index, news);
        if (cached != null) collect(keep, cached.snapshot.index, cached.snapshot.news);
        store.prune(keep);
    }

    private static void collect(Set<String> keep, RootIndex index, NewsFeed news) {
        if (index != null) for (RootIndex.DocRef doc : index.documents()) keep.add(doc.sha256());
        if (news != null) for (MediaRef media : news.mediaRefs()) keep.add(Hashes.normalizeHex(media.sha256, 64));
    }

    /**
     * A document referenced by a verified index: from the cache, else from the mirrors
     * ({@code preferred} first), verified by size and SHA-256.
     */
    private byte[] document(RootIndex.DocRef ref, String preferred) throws ContentException {
        byte[] cachedBytes = store.readDoc(ref);
        if (cachedBytes != null) return cachedBytes;
        ContentException last = null;
        for (String mirror : ordered(preferred)) {
            try {
                byte[] bytes = get(mirror + ref.path, Math.min(ref.size, ContentConfig.MAX_DOCUMENT_BYTES));
                verify(bytes, ref.size, ref.sha256(), ref.path);
                return bytes;
            } catch (ContentException e) {
                last = e;
            }
        }
        throw last != null ? last : new ContentException(ContentException.Kind.NETWORK, "no mirror for " + ref.path);
    }

    /** The manifest of a pack listed by a verified snapshot (cached after the first download). */
    public PackManifest packManifest(Snapshot snapshot, RootIndex.PackRef pack) throws ContentException {
        if (snapshot.index == null || snapshot.index.pack(pack.id) != pack) {
            throw new ContentException(ContentException.Kind.INVALID, "pack is not part of this snapshot");
        }
        byte[] bytes;
        synchronized (this) {
            bytes = document(pack.manifest, snapshot.mirror);
            try {
                store.writeDoc(pack.manifest, bytes);
            } catch (IOException ignored) {
            }
        }
        return PackManifest.parse(bytes);
    }

    /** A cover image from a verified news feed: cached copy or a verified download. */
    public File media(Snapshot snapshot, MediaRef ref) throws ContentException {
        if (!ref.isValid()) throw new ContentException(ContentException.Kind.INVALID, "bad media reference");
        File cachedFile = store.mediaFile(ref);
        if (cachedFile != null) return cachedFile;
        ContentException last = null;
        for (String mirror : ordered(snapshot.mirror)) {
            try {
                byte[] bytes = get(mirror + ref.path, ref.size);
                verify(bytes, ref.size, Hashes.normalizeHex(ref.sha256, 64), ref.path);
                synchronized (this) {
                    return store.writeMedia(ref, bytes);
                }
            } catch (ContentException e) {
                last = e;
            } catch (IOException e) {
                throw new ContentException(ContentException.Kind.NETWORK, "cannot cache " + ref.path, e);
            }
        }
        throw last != null ? last : new ContentException(ContentException.Kind.NETWORK, "no mirror for " + ref.path);
    }

    private List<String> ordered(String preferred) {
        List<String> out = new ArrayList<>();
        if (preferred != null && mirrors.contains(preferred)) out.add(preferred);
        for (String mirror : mirrors) if (!out.contains(mirror)) out.add(mirror);
        return out;
    }

    private static void verify(byte[] bytes, long size, String sha256, String path) throws ContentException {
        if (bytes.length != size) {
            throw new ContentException(ContentException.Kind.HASH_MISMATCH,
                    path + ": expected " + size + " bytes, got " + bytes.length);
        }
        if (sha256 == null || !sha256.equals(Hashes.sha256Hex(bytes))) {
            throw new ContentException(ContentException.Kind.HASH_MISMATCH, path + ": SHA-256 mismatch");
        }
    }

    private byte[] get(String url, long maxBytes) throws ContentException {
        try {
            return fetcher.get(url, maxBytes);
        } catch (IOException e) {
            throw new ContentException(ContentException.Kind.NETWORK, "GET " + url + " failed: " + e.getMessage(), e);
        }
    }
}
