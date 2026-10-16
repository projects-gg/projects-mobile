package gg.projects.mobile.content;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetches, verifies and caches the Discord bot's news feed (client rules of content-format.md,
 * "News feed (Discord bot)"; pl-content's {@code FeedClient}), with the same mirror, timeout and
 * cache discipline as {@link ContentRepository}:
 *
 * <ol>
 * <li>{@code feed.json} comes from the first mirror that serves a valid one. When its bytes equal
 * the cached feed nothing else is downloaded. Otherwise {@code feed.json.sig} must name a key the
 * signed config delegates and verify (Ed25519, the {@code index.json.sig} envelope); on a mismatch
 * both files are fetched once more with {@code ?t=} to get past edge caches, then the next mirror
 * is tried.</li>
 * <li>The whole-feed rules of {@link BotFeed#parse} hold, and the sequence must grow (an equal
 * sequence is accepted only with identical bytes).</li>
 * <li>The cached feed is verified again on every read against the current keys: a key Studio
 * removes also drops what it signed, including its sequence floor.</li>
 * </ol>
 *
 * A failure never throws: the cached feed (or none) is returned with the error, so the home
 * screen keeps Studio's content.
 */
public final class FeedRepository {
    private final FeedStore store;
    private final HttpFetcher fetcher;
    private final ContentRepository.Clock clock;
    private final Object mediaLock = new Object();

    public FeedRepository(FeedStore store, HttpFetcher fetcher, ContentRepository.Clock clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    private static final class Cached {
        final FeedStore.Current current;
        final BotFeed feed;

        Cached(FeedStore.Current current, BotFeed feed) {
            this.current = current;
            this.feed = feed;
        }
    }

    private static IndexVerifier verifier(FeedSettings settings) throws ContentException {
        return new IndexVerifier(settings.keys);
    }

    private Cached readCached(FeedSettings settings) {
        FeedStore.Current current = store.readCurrent();
        if (current == null) return null;
        try {
            verifier(settings).verify(current.feed, current.signature);
            return new Cached(current, BotFeed.parse(current.feed));
        } catch (ContentException e) {
            // Signed by a key the config no longer lists, or unreadable: as good as none.
            return null;
        }
    }

    /** The cached feed when it still verifies against {@code settings}, without the network; else null. */
    public synchronized FeedSnapshot loadCached(FeedSettings settings) {
        Cached cached = readCached(settings);
        if (cached == null) return null;
        return new FeedSnapshot(cached.feed, settings, cached.current.mirror, cached.current.fetchedAt, true, null);
    }

    /** Fetches the newest feed from the mirrors. Never throws; see the class comment. */
    public synchronized FeedSnapshot refresh(FeedSettings settings) {
        Cached cached = readCached(settings);
        ContentException lastError = null;
        IndexVerifier verifier;
        try {
            verifier = verifier(settings);
        } catch (ContentException e) {
            return FeedSnapshot.none(settings, e);
        }
        for (String mirror : settings.mirrors) {
            try {
                return refreshFrom(mirror, settings, verifier, cached);
            } catch (ContentException e) {
                lastError = preferContentError(lastError, e);
            }
        }
        if (cached != null) {
            return new FeedSnapshot(cached.feed, settings, cached.current.mirror, cached.current.fetchedAt, true, lastError);
        }
        return FeedSnapshot.none(settings, lastError);
    }

    private FeedSnapshot refreshFrom(String mirror, FeedSettings settings, IndexVerifier verifier, Cached cached)
            throws ContentException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String query = attempt == 0 ? "" : "?t=" + clock.now();
            byte[] bytes = get(mirror + "feed.json" + query, ContentConfig.FEED_MAX_BYTES);
            if (cached != null && Arrays.equals(bytes, cached.current.feed)) {
                // The mirror serves exactly the cached feed: no signature download.
                long now = clock.now();
                save(new FeedStore.Current(cached.current.feed, cached.current.signature, mirror, now,
                        cached.current.previousMedia));
                return new FeedSnapshot(cached.feed, settings, mirror, now, false, null);
            }
            byte[] signature = get(mirror + "feed.json.sig" + query, ContentConfig.FEED_MAX_SIGNATURE_BYTES);
            try {
                verifier.verify(bytes, signature);
            } catch (ContentException e) {
                boolean signatureProblem = e.kind == ContentException.Kind.BAD_SIGNATURE
                        || e.kind == ContentException.Kind.UNTRUSTED_KEY;
                if (attempt == 0 && signatureProblem) continue; // stale edge cache: retry once
                throw e;
            }
            BotFeed feed = BotFeed.parse(bytes);
            if (cached != null) {
                long floor = cached.feed.sequence;
                if (feed.sequence < floor) {
                    throw new ContentException(ContentException.Kind.ROLLBACK,
                            "feed mirror " + mirror + " serves sequence " + feed.sequence + " < " + floor);
                }
                if (feed.sequence == floor) {
                    throw new ContentException(ContentException.Kind.ROLLBACK,
                            "feed mirror " + mirror + " serves different bytes for sequence " + floor);
                }
            }
            long now = clock.now();
            Set<String> previous = cached == null ? Collections.<String>emptySet() : mediaNames(cached.feed);
            save(new FeedStore.Current(bytes, signature, mirror, now, previous));
            Set<String> keep = new HashSet<>(mediaNames(feed));
            keep.addAll(previous);
            store.pruneMedia(keep);
            return new FeedSnapshot(feed, settings, mirror, now, false, null);
        }
        throw new ContentException(ContentException.Kind.BAD_SIGNATURE, "no verified feed on " + mirror);
    }

    /** The first error that is about the content wins over network errors (the more useful report). */
    private static ContentException preferContentError(ContentException kept, ContentException next) {
        if (kept == null) return next;
        if (kept.kind == ContentException.Kind.NETWORK && next.kind != ContentException.Kind.NETWORK) return next;
        return kept;
    }

    private void save(FeedStore.Current current) {
        try {
            store.writeCurrent(current);
        } catch (IOException e) {
            // Disk trouble does not make the feed less valid; it just is not cached.
        }
    }

    private static Set<String> mediaNames(BotFeed feed) {
        Set<String> names = new HashSet<>();
        for (MediaRef media : feed.mediaRefs()) {
            String sha = Hashes.normalizeHex(media.sha256, 64);
            if (sha != null) names.add(sha);
        }
        return names;
    }

    /** The cached copy of a cover (verified when it was downloaded), without the network; else null. */
    public File cachedMedia(MediaRef media) {
        return BotFeed.isValidCover(media) ? store.mediaFile(media) : null;
    }

    /**
     * A cover of {@code snapshot}'s feed: the cached copy, else a download from the feed mirrors
     * (the one that served the feed first), read up to the declared size and kept only when the
     * size and SHA-256 match.
     */
    public File media(FeedSnapshot snapshot, MediaRef media) throws ContentException {
        if (!BotFeed.isValidCover(media)) throw new ContentException(ContentException.Kind.INVALID, "not a feed cover");
        File cachedFile = store.mediaFile(media);
        if (cachedFile != null) return cachedFile;
        String sha256 = Hashes.normalizeHex(media.sha256, 64);
        ContentException last = null;
        for (String mirror : ordered(snapshot.settings.mirrors, snapshot.mirror)) {
            byte[] bytes;
            try {
                bytes = get(mirror + media.path, media.size);
            } catch (ContentException e) {
                last = preferContentError(last, e);
                continue;
            }
            if (bytes.length != media.size || !Hashes.sha256Hex(bytes).equals(sha256)) {
                last = preferContentError(last, new ContentException(ContentException.Kind.HASH_MISMATCH,
                        media.path + " does not match its size and SHA-256"));
                continue;
            }
            synchronized (mediaLock) {
                try {
                    return store.writeMedia(media, bytes);
                } catch (IOException e) {
                    throw new ContentException(ContentException.Kind.NETWORK, "cannot cache " + media.path, e);
                }
            }
        }
        throw last != null ? last : new ContentException(ContentException.Kind.NETWORK, "no mirror for " + media.path);
    }

    /**
     * Downloads the covers of the items shown at {@code now} that are not cached yet, so the home
     * screen never waits for them. Returns how many were downloaded; failures are skipped.
     */
    public int prefetch(FeedSnapshot snapshot, long now) {
        if (snapshot == null || snapshot.feed == null) return 0;
        long floor = snapshot.settings.ageFloor(now);
        int downloaded = 0;
        Set<String> seen = new HashSet<>();
        for (NewsFeed.NewsItem item : snapshot.feed.items) {
            if (item.cover == null || !item.isVisible(now) || item.publishedAtMillis() < floor) continue;
            if (!seen.add(Hashes.normalizeHex(item.cover.sha256, 64)) || cachedMedia(item.cover) != null) continue;
            try {
                media(snapshot, item.cover);
                downloaded++;
            } catch (ContentException ignored) {
                // The item shows its placeholder; the next refresh tries again.
            }
        }
        return downloaded;
    }

    /** Deletes the cache (the config turned the feed off). */
    public synchronized void clear() {
        store.clear();
    }

    public boolean hasCache() {
        return store.exists();
    }

    private static List<String> ordered(List<String> mirrors, String preferred) {
        List<String> out = new ArrayList<>();
        if (preferred != null && mirrors.contains(preferred)) out.add(preferred);
        for (String mirror : mirrors) if (!out.contains(mirror)) out.add(mirror);
        return out;
    }

    private byte[] get(String url, long maxBytes) throws ContentException {
        try {
            return fetcher.get(url, maxBytes);
        } catch (IOException e) {
            throw new ContentException(ContentException.Kind.NETWORK, "GET " + url + " failed: " + e.getMessage(), e);
        }
    }
}
