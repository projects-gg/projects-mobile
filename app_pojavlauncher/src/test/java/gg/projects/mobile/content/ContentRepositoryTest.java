package gg.projects.mobile.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** The mirror, verification, rollback and cache rules of docs/content-format.md. */
public class ContentRepositoryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final List<String> MIRRORS = Arrays.asList(Fixtures.MIRROR_A, Fixtures.MIRROR_B);
    private long now = 1_800_000_000_000L;

    private ContentRepository repository(File root, HttpFetcher http) throws ContentException {
        return new ContentRepository(new ContentStore(root), http, Fixtures.verifier(), MIRRORS, () -> now);
    }

    @Test
    public void firstMirrorDownSecondServes() throws Exception {
        Fixtures.FakeHttp http = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_B);
        http.failures.put(Fixtures.MIRROR_A + "index.json", new UnknownHostException("cdn.projects.gg"));
        Snapshot snapshot = repository(tmp.newFolder(), http).refresh();
        assertTrue(snapshot.hasContent());
        assertFalse(snapshot.offline);
        assertEquals(Fixtures.MIRROR_B, snapshot.mirror);
        assertEquals(1792065600L, snapshot.index.sequence);
        assertEquals("2.3 yama notları", snapshot.news.visible(now).get(0).title);
        assertEquals("projects", snapshot.officialPack().id);
        assertEquals("https://discord.gg/projects", snapshot.config.discordUrl());
        assertNull(snapshot.lastError);
    }

    @Test
    public void badSignatureIsRetriedOnceWithACacheBusterThenTheNextMirror() throws Exception {
        Fixtures.FakeHttp http = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_B);
        // Mirror A keeps serving a signature from an untrusted key, with or without the cache buster.
        http.serve(Fixtures.MIRROR_A + "index.json", Fixtures.bytes("v1/index.json"));
        http.serve(Fixtures.MIRROR_A + "index.json.sig", Fixtures.bytes("index.other-key.sig"));
        http.serve(Fixtures.MIRROR_A + "index.json?t=" + now, Fixtures.bytes("v1/index.json"));
        http.serve(Fixtures.MIRROR_A + "index.json.sig?t=" + now, Fixtures.bytes("index.other-key.sig"));
        Snapshot snapshot = repository(tmp.newFolder(), http).refresh();
        assertEquals(Fixtures.MIRROR_B, snapshot.mirror);
        assertTrue(http.requests.contains(Fixtures.MIRROR_A + "index.json?t=" + now));
        assertTrue(http.requests.contains(Fixtures.MIRROR_A + "index.json.sig?t=" + now));
    }

    @Test
    public void staleEdgeCacheIsBypassed() throws Exception {
        // The edge still has the old signature; the cache-busted request gets the right one.
        Fixtures.FakeHttp http = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A);
        http.serve(Fixtures.MIRROR_A + "index.json.sig", Fixtures.bytes("index.newer.json.sig"));
        http.serve(Fixtures.MIRROR_A + "index.json?t=" + now, Fixtures.bytes("v1/index.json"));
        http.serve(Fixtures.MIRROR_A + "index.json.sig?t=" + now, Fixtures.bytes("v1/index.json.sig"));
        Snapshot snapshot = repository(tmp.newFolder(), http).refresh();
        assertFalse(snapshot.offline);
        assertEquals(Fixtures.MIRROR_A, snapshot.mirror);
    }

    @Test
    public void tamperedDocumentFallsBackToTheOtherMirror() throws Exception {
        Fixtures.FakeHttp http = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A).serveTree(Fixtures.MIRROR_B);
        byte[] news = Fixtures.bytes("v1/" + Fixtures.NEWS_PATH).clone();
        news[10] ^= 1;
        http.serve(Fixtures.MIRROR_A + Fixtures.NEWS_PATH, news);
        Snapshot snapshot = repository(tmp.newFolder(), http).refresh();
        assertFalse(snapshot.offline);
        assertEquals(Fixtures.MIRROR_A, snapshot.mirror);
        assertTrue(http.requests.contains(Fixtures.MIRROR_B + Fixtures.NEWS_PATH));
        assertEquals(2, snapshot.news.visible(now).size());
    }

    @Test
    public void nothingPublishedYieldsAnEmptyOfflineSnapshot() throws Exception {
        Snapshot snapshot = repository(tmp.newFolder(), new Fixtures.FakeHttp()).refresh();
        assertFalse(snapshot.hasContent());
        assertTrue(snapshot.offline);
        assertNotNull(snapshot.lastError);
        assertEquals(ContentException.Kind.NETWORK, snapshot.lastError.kind);
        assertTrue(snapshot.lastError.getCause() instanceof HttpStatusException);
        assertNull(snapshot.officialPack());
    }

    @Test
    public void cachedSnapshotIsUsedOfflineAndReverified() throws Exception {
        File root = tmp.newFolder();
        repository(root, new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A)).refresh();

        ContentRepository offline = repository(root, new Fixtures.NoNetwork());
        Snapshot cached = offline.loadCached();
        assertTrue(cached.hasContent());
        assertTrue(cached.offline);
        assertEquals(2, cached.news.visible(now).size());
        Snapshot refreshed = offline.refresh();
        assertTrue(refreshed.offline);
        assertTrue(refreshed.hasContent());
        assertEquals(ContentException.Kind.NETWORK, refreshed.lastError.kind);

        // A cache that no longer verifies is ignored.
        List<String> otherKey = Arrays.asList(Fixtures.otherPublicKey());
        ContentRepository rotated = new ContentRepository(new ContentStore(root), new Fixtures.NoNetwork(),
                new IndexVerifier(otherKey), MIRRORS, () -> now);
        assertFalse(rotated.loadCached().hasContent());
    }

    @Test
    public void tamperedCacheIsIgnored() throws Exception {
        File root = tmp.newFolder();
        repository(root, new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A)).refresh();
        File current = new File(root, "current.json");
        String text = new String(java.nio.file.Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8);
        // Swap the cached index for the signed-but-older one without its signature.
        String older = org.bouncycastle.util.encoders.Base64.toBase64String(Fixtures.bytes("index.older.json"));
        String tampered = text.replaceFirst("\"index\":\"[^\"]+\"", "\"index\":\"" + older + "\"");
        try (FileOutputStream out = new FileOutputStream(current)) {
            out.write(tampered.getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(repository(root, new Fixtures.NoNetwork()).loadCached().hasContent());
    }

    @Test
    public void tamperedCachedDocumentIsDropped() throws Exception {
        File root = tmp.newFolder();
        repository(root, new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A)).refresh();
        File doc = new File(new File(root, "docs"), Fixtures.NEWS_PATH.substring(10, 74) + ".json");
        assertTrue(doc.getPath(), doc.isFile());
        try (FileOutputStream out = new FileOutputStream(doc, true)) {
            out.write(' ');
        }
        Snapshot cached = repository(root, new Fixtures.NoNetwork()).loadCached();
        assertTrue(cached.hasContent());
        assertNull(cached.news);
        assertFalse(doc.exists());
    }

    @Test
    public void rollbackIsRefused() throws Exception {
        File root = tmp.newFolder();
        repository(root, new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A)).refresh();

        Fixtures.FakeHttp older = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A);
        older.serve(Fixtures.MIRROR_A + "index.json", Fixtures.bytes("index.older.json"));
        older.serve(Fixtures.MIRROR_A + "index.json.sig", Fixtures.bytes("index.older.json.sig"));
        Snapshot snapshot = repository(root, older).refresh();
        assertTrue(snapshot.offline);
        assertEquals(1792065600L, snapshot.index.sequence);
        assertEquals(ContentException.Kind.NETWORK, snapshot.lastError.kind); // mirror B is unreachable
        // With only mirror A answering, the rollback is the reason.
        ContentRepository onlyA = new ContentRepository(new ContentStore(root), older, Fixtures.verifier(),
                Arrays.asList(Fixtures.MIRROR_A), () -> now);
        assertEquals(ContentException.Kind.ROLLBACK, onlyA.refresh().lastError.kind);

        Fixtures.FakeHttp sameSequence = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A);
        sameSequence.serve(Fixtures.MIRROR_A + "index.json", Fixtures.bytes("index.same-sequence.json"));
        sameSequence.serve(Fixtures.MIRROR_A + "index.json.sig", Fixtures.bytes("index.same-sequence.json.sig"));
        ContentRepository same = new ContentRepository(new ContentStore(root), sameSequence, Fixtures.verifier(),
                Arrays.asList(Fixtures.MIRROR_A), () -> now);
        assertEquals(ContentException.Kind.ROLLBACK, same.refresh().lastError.kind);
    }

    @Test
    public void newerIndexReplacesTheCacheAndIdenticalRepublishIsAccepted() throws Exception {
        File root = tmp.newFolder();
        repository(root, new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A)).refresh();
        // Same bytes from the other mirror: accepted.
        Snapshot again = repository(root, new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_B)).refresh();
        assertFalse(again.offline);
        Fixtures.FakeHttp newer = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A);
        newer.serve(Fixtures.MIRROR_A + "index.json", Fixtures.bytes("index.newer.json"));
        newer.serve(Fixtures.MIRROR_A + "index.json.sig", Fixtures.bytes("index.newer.json.sig"));
        Snapshot snapshot = repository(root, newer).refresh();
        assertFalse(snapshot.offline);
        assertEquals(1792065700L, snapshot.index.sequence);
        assertEquals(1792065700L, repository(root, new Fixtures.NoNetwork()).loadCached().index.sequence);
    }

    @Test
    public void futureSchemaIsReported() throws Exception {
        Fixtures.FakeHttp http = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A);
        http.serve(Fixtures.MIRROR_A + "index.json", Fixtures.bytes("index.schema2.json"));
        http.serve(Fixtures.MIRROR_A + "index.json.sig", Fixtures.bytes("index.schema2.json.sig"));
        ContentRepository onlyA = new ContentRepository(new ContentStore(tmp.newFolder()), http, Fixtures.verifier(),
                Arrays.asList(Fixtures.MIRROR_A), () -> now);
        Snapshot snapshot = onlyA.refresh();
        assertFalse(snapshot.hasContent());
        assertEquals(ContentException.Kind.UNSUPPORTED_SCHEMA, snapshot.lastError.kind);
    }

    @Test
    public void packManifestIsVerifiedAndCached() throws Exception {
        File root = tmp.newFolder();
        Fixtures.FakeHttp http = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A);
        ContentRepository repository = repository(root, http);
        Snapshot snapshot = repository.refresh();
        PackManifest manifest = repository.packManifest(snapshot, snapshot.officialPack());
        assertEquals("2.3.0", manifest.versionId);

        // Offline afterwards: served from the verified cache.
        ContentRepository offline = repository(root, new Fixtures.NoNetwork());
        Snapshot cached = offline.loadCached();
        assertEquals("2.3.0", offline.packManifest(cached, cached.officialPack()).versionId);

        // A mirror serving different bytes for the pinned manifest is refused.
        Fixtures.FakeHttp evil = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A);
        evil.serve(Fixtures.MIRROR_A + Fixtures.PACK_PATH, Fixtures.text("v1/" + Fixtures.PACK_PATH)
                .replace("1.21.1", "1.21.2").getBytes(StandardCharsets.UTF_8));
        ContentRepository fresh = new ContentRepository(new ContentStore(tmp.newFolder()), evil, Fixtures.verifier(),
                Arrays.asList(Fixtures.MIRROR_A), () -> now);
        Snapshot s = fresh.refresh();
        try {
            fresh.packManifest(s, s.officialPack());
            throw new AssertionError("accepted a manifest that does not match the signed index");
        } catch (ContentException e) {
            assertEquals(ContentException.Kind.HASH_MISMATCH, e.kind);
        }
    }

    @Test
    public void oversizedResponsesAreRefused() throws Exception {
        Fixtures.FakeHttp http = new Fixtures.FakeHttp().serveTree(Fixtures.MIRROR_A);
        http.serve(Fixtures.MIRROR_A + "index.json", new byte[(int) ContentConfig.MAX_INDEX_BYTES + 1]);
        ContentRepository onlyA = new ContentRepository(new ContentStore(tmp.newFolder()), http, Fixtures.verifier(),
                Arrays.asList(Fixtures.MIRROR_A), () -> now);
        assertFalse(onlyA.refresh().hasContent());
    }

    @Test
    public void atomicWritesReplaceFiles() throws IOException {
        File target = new File(tmp.getRoot(), "x/y.json");
        AtomicFiles.write(target, "one".getBytes(StandardCharsets.UTF_8));
        AtomicFiles.write(target, "two".getBytes(StandardCharsets.UTF_8));
        assertEquals("two", new String(AtomicFiles.read(target, 10), StandardCharsets.UTF_8));
        assertFalse(new File(target.getPath() + ".tmp").exists());
    }
}
