package gg.projects.mobile.content;

import static gg.projects.mobile.content.IndexVerifierTest.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parsing and validation of the content documents (docs/content-format.md, v1). */
public class ModelTest {
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The RootIndex example from content-format.md. */
    private static final String DOC_INDEX = "{\n"
            + "  \"schema\": 1,\n  \"sequence\": 1792065600,\n  \"generatedAt\": \"2026-10-15T12:00:00Z\",\n"
            + "  \"config\": {\"path\": \"config/launcher.b79606fb3afea5bd1609ed40b622142f1c98125abcfe89a76a661b0e8e343910.json\", \"sha256\": \"b79606fb3afea5bd1609ed40b622142f1c98125abcfe89a76a661b0e8e343910\", \"size\": 4182},\n"
            + "  \"news\": {\"path\": \"news/news.19fba0e995b9794fc2c26217bf3b725c2f0d9eeda16719fe75e3ba23ca73bfc4.json\", \"sha256\": \"19fba0e995b9794fc2c26217bf3b725c2f0d9eeda16719fe75e3ba23ca73bfc4\", \"size\": 9120},\n"
            + "  \"packs\": [{\"id\": \"projects\", \"name\": \"Projects\", \"latest\": \"2.3.0\", \"manifest\": {\"path\": \"packs/projects/2.3.0.json\", \"sha256\": \"4862f447f2c7f272fa2f4aaf89dadb3b1ac09105bd5864f8d1a0c9452bb0a226\", \"size\": 18342}}],\n"
            + "  \"afk\": {\"version\": \"1.4.0\", \"builds\": {\"windows-x86_64\": {\"path\": \"afk/1.4.0/windows-x86_64.zip\", \"sha256\": \"fb960cdfed351d8cf365d8d6afd56d042ac54c1bd9a1dd0b139691c5c3d074e0\", \"size\": 31457280}}}\n"
            + "}\n";

    @Test
    public void documentedRootIndexParses() throws Exception {
        RootIndex index = RootIndex.parse(utf8(DOC_INDEX));
        assertEquals(1792065600L, index.sequence);
        assertEquals(4182, index.config.size);
        assertEquals("projects", index.pack("projects").id);
        assertNull(index.pack("other"));
        assertEquals(3, index.documents().size());
    }

    @Test
    public void unsupportedSchemasAreRejected() {
        expect(ContentException.Kind.UNSUPPORTED_SCHEMA, () -> RootIndex.parse(utf8(DOC_INDEX.replace("\"schema\": 1", "\"schema\": 2"))));
        expect(ContentException.Kind.UNSUPPORTED_SCHEMA, () -> RootIndex.parse(utf8(DOC_INDEX.replace("\"schema\": 1", "\"schema\": 0"))));
        expect(ContentException.Kind.UNSUPPORTED_SCHEMA, () -> NewsFeed.parse(utf8("{\"schema\": 2, \"items\": []}")));
        expect(ContentException.Kind.UNSUPPORTED_SCHEMA, () -> LauncherConfig.parse(utf8("{\"schema\": 9}")));
    }

    @Test
    public void malformedReferencesAreRejected() {
        String good = "config/launcher.b79606fb3afea5bd1609ed40b622142f1c98125abcfe89a76a661b0e8e343910.json";
        for (String path : new String[]{"../etc/passwd", "/abs.json", "config\\\\x.json", "config/a b.json", ".projects/x.json", "C:/x.json"}) {
            expect(ContentException.Kind.INVALID, () -> RootIndex.parse(utf8(DOC_INDEX.replace(good, path))));
        }
        expect(ContentException.Kind.INVALID, () -> RootIndex.parse(utf8(
                DOC_INDEX.replace("\"sha256\": \"b79606fb", "\"sha256\": \"zz9606fb"))));
        expect(ContentException.Kind.INVALID, () -> RootIndex.parse(utf8(DOC_INDEX.replace("2026-10-15T12:00:00Z", "yesterday"))));
        expect(ContentException.Kind.INVALID, () -> RootIndex.parse(utf8("{\"schema\": 1, \"sequence\": 1, \"generatedAt\": \"2026-10-15T12:00:00Z\"}")));
        expect(ContentException.Kind.INVALID, () -> RootIndex.parse(utf8("[]")));
        expect(ContentException.Kind.INVALID, () -> RootIndex.parse(utf8("")));
        expect(ContentException.Kind.INVALID, () -> RootIndex.parse(new byte[]{'{', (byte) 0xC3, '}'}));
    }

    @Test
    public void uppercaseDigestsAreNormalized() throws Exception {
        RootIndex index = RootIndex.parse(utf8(DOC_INDEX.replace("b79606fb3afea5bd", "B79606FB3AFEA5BD")));
        assertEquals("b79606fb3afea5bd1609ed40b622142f1c98125abcfe89a76a661b0e8e343910", index.config.sha256());
    }

    @Test
    public void newsVisibilityAndOrder() throws Exception {
        NewsFeed feed = NewsFeed.parse(Fixtures.bytes("v1/" + Fixtures.NEWS_PATH));
        // "broken-date" is dropped, the rest are kept.
        assertEquals(4, feed.items.size());
        long now = Rfc3339.parse("2026-10-15T12:00:00Z");
        List<String> ids = new ArrayList<>();
        for (NewsFeed.NewsItem item : feed.visible(now)) ids.add(item.id);
        assertEquals("[patch-2-3, bedwars-cup]", ids.toString());
        assertEquals("announcement", feed.get("scheduled").categoryOrDefault());
        assertEquals("patchNotes", feed.get("patch-2-3").categoryOrDefault());
        assertFalse(feed.get("expired").isVisible(now));
    }

    @Test
    public void newsOrderMatchesTheDesktop() throws Exception {
        String feed = "{\"items\": ["
                + item("old", "2026-09-01T00:00:00Z", false) + ","
                + item("new", "2026-10-14T00:00:00Z", false) + ","
                + item("pinned", "2026-08-01T00:00:00Z", true) + ","
                + item("same-b", "2026-10-02T00:00:00Z", false) + ","
                + item("same-a", "2026-10-02T00:00:00Z", false) + "]}";
        List<String> ids = new ArrayList<>();
        for (NewsFeed.NewsItem i : NewsFeed.parse(utf8(feed)).visible(Rfc3339.parse("2026-10-15T12:00:00Z"))) ids.add(i.id);
        assertEquals("[pinned, new, same-a, same-b, old]", ids.toString());
    }

    private static String item(String id, String published, boolean pinned) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"publishedAt\":\"" + published + "\",\"pinned\":" + pinned + "}";
    }

    @Test
    public void emptyDocumentsUseDefaults() throws Exception {
        NewsFeed feed = NewsFeed.parse(utf8("{}"));
        assertTrue(feed.items.isEmpty());
        LauncherConfig config = LauncherConfig.parse(utf8("{}"));
        assertTrue(config.servers.isEmpty());
        assertNull(config.discordUrl());
        LauncherConfig fixture = LauncherConfig.parse(Fixtures.bytes("v1/" + Fixtures.CONFIG_PATH));
        assertEquals("https://discord.gg/projects", fixture.discordUrl());
        assertEquals("projects", fixture.recommendedPack);
    }

    @Test
    public void maintenanceWindow() throws Exception {
        LauncherConfig config = LauncherConfig.parse(utf8("{\"maintenance\": {\"enabled\": true, \"until\": \"2026-10-15T14:00:00Z\"}}"));
        assertTrue(config.maintenance.isActive(Rfc3339.parse("2026-10-15T13:59:59Z")));
        assertFalse(config.maintenance.isActive(Rfc3339.parse("2026-10-15T14:00:00Z")));
        assertFalse(LauncherConfig.parse(utf8("{\"maintenance\": {\"enabled\": false}}")).maintenance.isActive(0));
    }

    @Test
    public void mediaReferences() throws Exception {
        MediaRef media = ContentJson.parse(utf8("{\"path\": \"media/x.WEBP\", \"sha256\": \"" + repeat("ab", 32) + "\", \"size\": 10, \"kind\": \"hologram\","
                + " \"poster\": {\"path\": \"media/p.webp\", \"sha256\": \"" + repeat("cd", 32) + "\", \"size\": 5}}"), MediaRef.class, "media");
        assertEquals("webp", media.extension());
        assertEquals(repeat("ab", 32) + ".webp", media.cacheFileName());
        assertEquals("media/p.webp", media.displayImage().path);
        media.path = "media/x";
        assertEquals("bin", media.extension());
        media.path = "../x.webp";
        assertFalse(media.isValid());
    }

    @Test
    public void rfc3339() {
        assertEquals(Long.valueOf(0L), Rfc3339.parse("1970-01-01T00:00:00Z"));
        assertEquals(Long.valueOf(1792065600000L), Rfc3339.parse("2026-10-15T12:00:00Z"));
        assertEquals(Rfc3339.parse("2026-10-15T12:00:00Z"), Rfc3339.parse("2026-10-15T15:00:00+03:00"));
        assertEquals(Long.valueOf(1792065600123L), Rfc3339.parse("2026-10-15T12:00:00.123456Z"));
        assertEquals(Long.valueOf(951782400000L), Rfc3339.parse("2000-02-29T00:00:00Z"));
        assertNull(Rfc3339.parse("2026-02-29T00:00:00Z"));
        assertNull(Rfc3339.parse("2026-10-15 12:00"));
        assertNull(Rfc3339.parse("2026-13-01T00:00:00Z"));
        assertNull(Rfc3339.parse(null));
        assertNotNull(Rfc3339.parse("2026-10-15t12:00:00z"));
    }

    static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(s);
        return out.toString();
    }
}
