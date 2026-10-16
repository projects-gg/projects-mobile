package gg.projects.mobile.content;

import static gg.projects.mobile.content.FeedFixtures.MIRROR_A;
import static gg.projects.mobile.content.FeedFixtures.MIRROR_B;
import static gg.projects.mobile.content.FeedFixtures.feed;
import static gg.projects.mobile.content.FeedFixtures.item;
import static gg.projects.mobile.content.FeedFixtures.list;
import static gg.projects.mobile.content.FeedFixtures.utf8;
import static gg.projects.mobile.content.IndexVerifierTest.expect;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Fetching, verifying and caching the Discord feed (content-format.md "News feed", client rules). */
public class FeedRepositoryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private long now = FeedFixtures.at("2026-10-16T12:00:00Z");

    private FeedRepository repository(File root, HttpFetcher http) {
        return new FeedRepository(new FeedStore(root), http, () -> now);
    }

    private static FeedSettings settings(FeedFixtures.Key... keys) throws ContentException {
        return FeedSettings.of(FeedFixtures.config(Arrays.asList(MIRROR_A, MIRROR_B), Arrays.asList(keys), ""), null);
    }

    private static byte[] feedBytes(long sequence, String... items) {
        return utf8(feed(sequence, list(items), list()));
    }

    private static void publish(Fixtures.FakeHttp http, String mirror, FeedFixtures.Key key, byte[] feed) throws Exception {
        http.serve(mirror + "feed.json", feed);
        http.serve(mirror + "feed.json.sig", key.sign(feed));
    }

    @Test
    public void aSignedFeedIsVerifiedAndCached() throws Exception {
        FeedFixtures.Key key = new FeedFixtures.Key();
        Fixtures.FakeHttp http = new Fixtures.FakeHttp();
        byte[] bytes = feedBytes(10, item("discord-1", "2026-10-16T08:00:00Z", ""));
        publish(http, MIRROR_A, key, bytes);
        File root = tmp.newFolder();
        FeedSnapshot snapshot = repository(root, http).refresh(settings(key));
        assertNotNull(snapshot.feed);
        assertFalse(snapshot.fromCache);
        assertEquals(MIRROR_A, snapshot.mirror);
        assertEquals(10, snapshot.sequence());
        assertNull(snapshot.lastError);

        // A fresh repository (next app start) shows the cached feed without the network.
        FeedSnapshot cached = repository(root, new Fixtures.NoNetwork()).loadCached(settings(key));
        assertNotNull(cached);
        assertTrue(cached.fromCache);
        assertEquals("discord-1", cached.feed.items.get(0).id);
        // Offline: the refresh keeps the cached feed.
        FeedSnapshot offline = repository(root, new Fixtures.NoNetwork()).refresh(settings(key));
        assertEquals(10, offline.sequence());
        assertTrue(offline.fromCache);
        assertEquals(ContentException.Kind.NETWORK, offline.lastError.kind);
    }

    @Test
    public void tamperedFeedIsRetriedOnceThenTheNextMirrorServes() throws Exception {
        FeedFixtures.Key key = new FeedFixtures.Key();
        Fixtures.FakeHttp http = new Fixtures.FakeHttp();
        byte[] good = feedBytes(10, item("discord-1", "2026-10-16T08:00:00Z", ""));
        byte[] tampered = good.clone();
        tampered[tampered.length - 5] ^= 0x01;
        http.serve(MIRROR_A + "feed.json", tampered);
        http.serve(MIRROR_A + "feed.json.sig", key.sign(good));
        http.serve(MIRROR_A + "feed.json?t=" + now, tampered);
        http.serve(MIRROR_A + "feed.json.sig?t=" + now, key.sign(good));
        publish(http, MIRROR_B, key, good);
        FeedSnapshot snapshot = repository(tmp.newFolder(), http).refresh(settings(key));
        assertEquals(MIRROR_B, snapshot.mirror);
        assertArrayEquals(new String[]{"discord-1"}, new String[]{snapshot.feed.items.get(0).id});
        assertTrue(http.requests.contains(MIRROR_A + "feed.json?t=" + now));
        assertTrue(http.requests.contains(MIRROR_A + "feed.json.sig?t=" + now));
    }

    @Test
    public void staleEdgeCacheIsBypassed() throws Exception {
        FeedFixtures.Key key = new FeedFixtures.Key();
        Fixtures.FakeHttp http = new Fixtures.FakeHttp();
        byte[] older = feedBytes(9);
        byte[] newer = feedBytes(10, item("discord-2", "2026-10-16T08:00:00Z", ""));
        http.serve(MIRROR_A + "feed.json", newer);
        http.serve(MIRROR_A + "feed.json.sig", key.sign(older));
        http.serve(MIRROR_A + "feed.json?t=" + now, newer);
        http.serve(MIRROR_A + "feed.json.sig?t=" + now, key.sign(newer));
        FeedSnapshot snapshot = repository(tmp.newFolder(), http).refresh(settings(key));
        assertEquals(MIRROR_A, snapshot.mirror);
        assertEquals(10, snapshot.sequence());
    }

    @Test
    public void onlyDelegatedKeysAreTrusted() throws Exception {
        FeedFixtures.Key delegated = new FeedFixtures.Key();
        FeedFixtures.Key stranger = new FeedFixtures.Key();
        Fixtures.FakeHttp http = new Fixtures.FakeHttp();
        byte[] bytes = feedBytes(10, item("discord-1", "2026-10-16T08:00:00Z", ""));
        publish(http, MIRROR_A, stranger, bytes);
        http.serve(MIRROR_A + "feed.json?t=" + now, bytes);
        http.serve(MIRROR_A + "feed.json.sig?t=" + now, stranger.sign(bytes));
        publish(http, MIRROR_B, stranger, bytes);
        http.serve(MIRROR_B + "feed.json?t=" + now, bytes);
        http.serve(MIRROR_B + "feed.json.sig?t=" + now, stranger.sign(bytes));
        FeedSnapshot snapshot = repository(tmp.newFolder(), http).refresh(settings(delegated));
        assertNull(snapshot.feed);
        assertEquals(ContentException.Kind.UNTRUSTED_KEY, snapshot.lastError.kind);

        // Signed by the stranger but labelled with the delegated key's id: the signature fails.
        Fixtures.FakeHttp mislabelled = new Fixtures.FakeHttp();
        mislabelled.serve(MIRROR_A + "feed.json", bytes);
        mislabelled.serve(MIRROR_A + "feed.json.sig", stranger.signAs(delegated.keyId, bytes));
        FeedSnapshot refused = repository(tmp.newFolder(), mislabelled).refresh(settings(delegated));
        assertNull(refused.feed);
        assertEquals(ContentException.Kind.NETWORK, refused.lastError.kind); // mirror B has nothing
        assertTrue(mislabelled.requests.contains(MIRROR_A + "feed.json?t=" + now));
    }

    @Test
    public void rollbackIsRefusedAndTheCacheStays() throws Exception {
        FeedFixtures.Key key = new FeedFixtures.Key();
        File root = tmp.newFolder();
        Fixtures.FakeHttp first = new Fixtures.FakeHttp();
        byte[] ten = feedBytes(10, item("discord-1", "2026-10-16T08:00:00Z", ""));
        publish(first, MIRROR_A, key, ten);
        repository(root, first).refresh(settings(key));

        Fixtures.FakeHttp older = new Fixtures.FakeHttp();
        publish(older, MIRROR_A, key, feedBytes(9));
        FeedSnapshot kept = repository(root, older).refresh(settings(key));
        assertEquals(10, kept.sequence());
        assertTrue(kept.fromCache);
        assertEquals(ContentException.Kind.ROLLBACK, kept.lastError.kind);

        Fixtures.FakeHttp reused = new Fixtures.FakeHttp();
        publish(reused, MIRROR_A, key, feedBytes(10, item("discord-9", "2026-10-16T08:00:00Z", "")));
        FeedSnapshot same = repository(root, reused).refresh(settings(key));
        assertEquals("discord-1", same.feed.items.get(0).id);
        assertEquals(ContentException.Kind.ROLLBACK, same.lastError.kind);

        // The same bytes again: unchanged, without downloading the signature.
        Fixtures.FakeHttp unchanged = new Fixtures.FakeHttp();
        unchanged.serve(MIRROR_A + "feed.json", ten);
        FeedSnapshot again = repository(root, unchanged).refresh(settings(key));
        assertFalse(again.fromCache);
        assertEquals(10, again.sequence());
        assertEquals(Collections.singletonList(MIRROR_A + "feed.json"), unchanged.requests);

        Fixtures.FakeHttp newer = new Fixtures.FakeHttp();
        publish(newer, MIRROR_A, key, feedBytes(11));
        assertEquals(11, repository(root, newer).refresh(settings(key)).sequence());
    }

    @Test
    public void aRemovedKeyTakesItsFeedAndSequenceFloorAlong() throws Exception {
        FeedFixtures.Key old = new FeedFixtures.Key();
        FeedFixtures.Key rotated = new FeedFixtures.Key();
        File root = tmp.newFolder();
        Fixtures.FakeHttp http = new Fixtures.FakeHttp();
        publish(http, MIRROR_A, old, feedBytes(50, item("discord-1", "2026-10-16T08:00:00Z", "")));
        repository(root, http).refresh(settings(old));
        assertNotNull(repository(root, http).loadCached(settings(old, rotated)));

        // Studio drops the old key: its cached feed is gone and a lower sequence is fine.
        assertNull(repository(root, http).loadCached(settings(rotated)));
        Fixtures.FakeHttp next = new Fixtures.FakeHttp();
        publish(next, MIRROR_A, rotated, feedBytes(3, item("discord-2", "2026-10-16T08:00:00Z", "")));
        FeedSnapshot snapshot = repository(root, next).refresh(settings(rotated));
        assertEquals(3, snapshot.sequence());
        assertNull(snapshot.lastError);
    }

    @Test
    public void coversAreVerifiedBySizeAndHash() throws Exception {
        FeedFixtures.Key key = new FeedFixtures.Key();
        byte[] cover = utf8("a small webp");
        byte[] oldCover = utf8("an old cover");
        byte[] expiredCover = utf8("expired cover");
        String sha = Hashes.sha256Hex(cover);
        Fixtures.FakeHttp http = new Fixtures.FakeHttp();
        byte[] bytes = feedBytes(10,
                item("discord-1", "2026-10-16T08:00:00Z", FeedFixtures.cover(cover, "")),
                item("discord-2", "2026-08-01T08:00:00Z", FeedFixtures.cover(oldCover, "")),
                item("discord-3", "2026-10-16T08:00:00Z", ",\"expiresAt\":\"2026-10-16T10:00:00Z\"" + FeedFixtures.cover(expiredCover, "")));
        publish(http, MIRROR_A, key, bytes);
        File root = tmp.newFolder();
        FeedRepository repository = repository(root, http);
        FeedSnapshot snapshot = repository.refresh(settings(key));
        MediaRef ref = snapshot.feed.item("discord-1").cover;

        // Mirror A serves the wrong bytes, mirror B the right ones.
        http.serve(MIRROR_A + "media/" + sha + ".webp", utf8("a small webq"));
        http.serve(MIRROR_B + "media/" + sha + ".webp", cover);
        assertEquals(1, repository.prefetch(snapshot, now));
        File file = repository.cachedMedia(ref);
        assertNotNull(file);
        assertArrayEquals(cover, Files.readAllBytes(file.toPath()));
        // Only the visible items' covers: too old (60 days) and expired ones are not fetched.
        for (String url : http.requests) {
            assertFalse(url, url.contains(Hashes.sha256Hex(oldCover)));
            assertFalse(url, url.contains(Hashes.sha256Hex(expiredCover)));
        }
        // A cover that matches nowhere is not stored.
        Fixtures.FakeHttp wrong = new Fixtures.FakeHttp();
        wrong.serve(MIRROR_A + "media/" + sha + ".webp", utf8("a small webq"));
        FeedRepository other = repository(tmp.newFolder(), wrong);
        expect(ContentException.Kind.HASH_MISMATCH, () -> other.media(snapshot, ref));
        assertNull(other.cachedMedia(ref));
        // Longer than declared: refused while reading.
        Fixtures.FakeHttp longer = new Fixtures.FakeHttp();
        longer.serve(MIRROR_A + "media/" + sha + ".webp", utf8("a small webp and more"));
        expect(ContentException.Kind.NETWORK, () -> repository(tmp.newFolder(), longer).media(snapshot, ref));
    }

    @Test
    public void firstMirrorDownSecondServesAndNothingAnywhereIsNoFeed() throws Exception {
        FeedFixtures.Key key = new FeedFixtures.Key();
        Fixtures.FakeHttp http = new Fixtures.FakeHttp();
        http.failures.put(MIRROR_A + "feed.json", new UnknownHostException("projects.gg"));
        publish(http, MIRROR_B, key, feedBytes(10));
        assertEquals(MIRROR_B, repository(tmp.newFolder(), http).refresh(settings(key)).mirror);

        FeedSnapshot none = repository(tmp.newFolder(), new Fixtures.NoNetwork()).refresh(settings(key));
        assertNull(none.feed);
        assertEquals(-1, none.sequence());
        assertEquals(ContentException.Kind.NETWORK, none.lastError.kind);

        // A whole-feed rule broken on mirror A: mirror B is asked.
        Fixtures.FakeHttp broken = new Fixtures.FakeHttp();
        publish(broken, MIRROR_A, key, utf8("{\"schema\":2,\"sequence\":11,\"generatedAt\":\"2026-10-16T09:00:00Z\"}"));
        publish(broken, MIRROR_B, key, feedBytes(10));
        assertEquals(MIRROR_B, repository(tmp.newFolder(), broken).refresh(settings(key)).mirror);
    }

    @Test
    public void clearDeletesTheCache() throws Exception {
        FeedFixtures.Key key = new FeedFixtures.Key();
        Fixtures.FakeHttp http = new Fixtures.FakeHttp();
        publish(http, MIRROR_A, key, feedBytes(10));
        File root = new File(tmp.getRoot(), "projects-feed");
        FeedRepository repository = repository(root, http);
        repository.refresh(settings(key));
        assertTrue(repository.hasCache());
        repository.clear();
        assertFalse(repository.hasCache());
        assertFalse(root.exists());
        assertNull(repository.loadCached(settings(key)));
    }
}
