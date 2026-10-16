package gg.projects.mobile.content;

import static gg.projects.mobile.content.FeedFixtures.utf8;
import static gg.projects.mobile.content.IndexVerifierTest.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** {@code LauncherConfig.newsFeed} and {@code events} (content-format.md "Launcher config"). */
public class NewsFeedConfigTest {
    private static LauncherConfig parse(String json) throws ContentException {
        return LauncherConfig.parse(utf8(json));
    }

    private static String keyJson(String keyId, String publicKey) {
        return "{\"keyId\":\"" + keyId + "\",\"publicKey\":\"" + publicKey + "\"}";
    }

    @Test
    public void missingOrDisabledFeedIsOff() throws Exception {
        FeedFixtures.Key key = new FeedFixtures.Key();
        assertNull(parse("{}").newsFeed);
        assertNull(FeedSettings.of(parse("{}"), null));
        assertNull(FeedSettings.of(null, null));
        String keys = "[" + keyJson(key.keyId, key.publicKey) + "]";
        assertNull(FeedSettings.of(parse("{\"newsFeed\":{\"enabled\":false,\"mirrors\":[\"https://f.test/v1/\"],\"keys\":" + keys + "}}"), null));
        assertNull(FeedSettings.of(parse("{\"newsFeed\":{\"mirrors\":[\"https://f.test/v1/\"],\"keys\":" + keys + "}}"), null));
        // Enabled but nothing usable to ask or trust: off as well.
        assertNull(FeedSettings.of(parse("{\"newsFeed\":{\"enabled\":true,\"mirrors\":[\"http://f.test/v1/\"],\"keys\":" + keys + "}}"), null));
        assertNull(FeedSettings.of(parse("{\"newsFeed\":{\"enabled\":true,\"mirrors\":[\"https://f.test/v1/\"],\"keys\":[]}}"), null));
        // The debug override never turns the feed on.
        ContentSources debug = ContentSources.withDebugOverride(utf8("{\"feedMirrors\":[\"http://10.0.2.2:8790/v1\"],\"feedTestKey\":\"" + key.publicKey + "\"}"));
        assertNull(FeedSettings.of(parse("{}"), debug));
        assertNull(FeedSettings.of(parse("{\"newsFeed\":{\"enabled\":false}}"), debug));
    }

    @Test
    public void onlyValidMirrorsAndKeysAreUsed() throws Exception {
        FeedFixtures.Key a = new FeedFixtures.Key();
        FeedFixtures.Key b = new FeedFixtures.Key();
        // keyId must match exactly: an id with a letter in it shows the case rule.
        while (b.keyId.equals(b.keyId.toUpperCase(java.util.Locale.ROOT))) b = new FeedFixtures.Key();
        FeedFixtures.Key c = new FeedFixtures.Key();
        FeedFixtures.Key d = new FeedFixtures.Key();
        FeedFixtures.Key e = new FeedFixtures.Key();
        LauncherConfig config = parse("{\"newsFeed\":{\"enabled\":true,\"mirrors\":["
                + "\"https://feed.projects.gg/v1/\",\"http://projects.gg/feed/\",\"https://projects.gg/launcher-feed/v1\","
                + "\"https://projects.gg/f/?x=1\",\"https://user:pw@projects.gg/f/\",\" https://projects.gg/a/\","
                + "\"https://FEED.projects.gg/v1/\",\"https://projects.gg/launcher-feed/v1/\",\"https://c.test/\",\"https://d.test/\",42"
                + "],\"keys\":["
                + keyJson(a.keyId, a.publicKey) + "," + keyJson(b.keyId.toUpperCase(java.util.Locale.ROOT), b.publicKey) + ","
                + keyJson(c.keyId, "not base64") + "," + keyJson(a.keyId, a.publicKey) + ","
                + keyJson(c.keyId, c.publicKey) + "," + keyJson(d.keyId, d.publicKey) + "," + keyJson(b.keyId, b.publicKey) + ","
                + keyJson(e.keyId, e.publicKey) + ",7"
                + "],\"maxAgeDays\":30}}");
        NewsFeedConfig feed = config.newsFeed;
        assertNotNull(feed);
        assertTrue(feed.enabled);
        assertEquals(Arrays.asList("https://feed.projects.gg/v1/", "https://projects.gg/launcher-feed/v1/",
                "https://c.test/", "https://d.test/"), feed.mirrorBases());
        assertEquals(Arrays.asList(a.publicKey, c.publicKey, d.publicKey, b.publicKey), feed.trustedKeys());
        assertEquals(30, feed.effectiveMaxAgeDays());
        FeedSettings settings = FeedSettings.of(config, null);
        assertEquals(4, settings.mirrors.size());
        assertEquals(30, settings.maxAgeDays);
        assertEquals(FeedFixtures.at("2026-09-16T12:00:00Z"), settings.ageFloor(FeedFixtures.at("2026-10-16T12:00:00Z")));
    }

    @Test
    public void maxAgeDaysFallsBackToSixty() throws Exception {
        for (String value : new String[]{"0", "366", "-1", "1.5", "\"7\"", "null", "4294967296"}) {
            assertEquals(value, 60, parse("{\"newsFeed\":{\"maxAgeDays\":" + value + "}}").newsFeed.effectiveMaxAgeDays());
        }
        assertEquals(60, parse("{\"newsFeed\":{}}").newsFeed.effectiveMaxAgeDays());
        assertEquals(1, parse("{\"newsFeed\":{\"maxAgeDays\":1}}").newsFeed.effectiveMaxAgeDays());
        assertEquals(365, parse("{\"newsFeed\":{\"maxAgeDays\":365}}").newsFeed.effectiveMaxAgeDays());
        // A malformed newsFeed never breaks the rest of the config.
        LauncherConfig odd = parse("{\"newsFeed\":\"on\",\"maintenance\":{\"enabled\":true}}");
        assertNull(odd.newsFeed);
        assertTrue(odd.maintenance.isActive(0));
        assertFalse(parse("{\"newsFeed\":{\"enabled\":\"yes\"}}").newsFeed.enabled);
    }

    @Test
    public void debugOverrideReplacesFeedMirrorsAndAddsOneKey() throws Exception {
        FeedFixtures.Key key = new FeedFixtures.Key();
        FeedFixtures.Key test = new FeedFixtures.Key();
        LauncherConfig config = FeedFixtures.config(Collections.singletonList("https://projects.gg/launcher-feed/v1/"),
                Collections.singletonList(key), "");
        ContentSources debug = ContentSources.withDebugOverride(utf8(
                "{\"feedMirrors\":[\"http://10.0.2.2:8790/v1\"],\"feedTestKey\":\"" + test.publicKey + "\"}"));
        assertEquals(ContentConfig.MIRRORS, debug.mirrors);
        FeedSettings settings = FeedSettings.of(config, debug);
        assertEquals(Collections.singletonList("http://10.0.2.2:8790/v1/"), settings.mirrors);
        assertEquals(Arrays.asList(key.publicKey, test.publicKey), settings.keys);
        // Release sources change nothing.
        assertEquals(Collections.singletonList("https://projects.gg/launcher-feed/v1/"),
                FeedSettings.of(config, ContentSources.production()).mirrors);
        assertTrue(ContentSources.production().feedMirrors.isEmpty());
        assertNull(ContentSources.production().feedTestKey);
        expect(ContentException.Kind.INVALID, () -> ContentSources.withDebugOverride(utf8("{\"feedMirrors\":[\"ftp://x/\"]}")));
        expect(ContentException.Kind.INVALID, () -> ContentSources.withDebugOverride(utf8("{\"feedTestKey\":\"short\"}")));
    }

    @Test
    public void configEventsAreReadOneByOne() throws Exception {
        LauncherConfig config = parse("{\"events\":["
                + "{\"id\":\"cup\",\"title\":\"Bed Wars turnuvası\",\"startsAt\":\"2026-10-20T18:00:00Z\",\"endsAt\":\"2026-10-20T21:00:00Z\","
                + "\"url\":\"https://projects.gg/turnuva\",\"priority\":2,\"image\":{\"path\":\"media/x.webp\"}},"
                + "{\"id\":\"hidden\",\"title\":\"x\",\"startsAt\":\"2026-10-20T18:00:00Z\",\"endsAt\":\"2026-10-20T21:00:00Z\",\"hidden\":true},"
                + "{\"id\":\"no-end\",\"title\":\"x\",\"startsAt\":\"2026-10-20T18:00:00Z\"},"
                + "{\"id\":\"frac\",\"title\":\"x\",\"startsAt\":\"2026-10-20T18:00:00Z\",\"endsAt\":\"2026-10-20T21:00:00Z\",\"priority\":0.5},"
                + "{\"id\":\"bad-hidden\",\"title\":\"x\",\"startsAt\":\"2026-10-20T18:00:00Z\",\"endsAt\":\"2026-10-20T21:00:00Z\",\"hidden\":\"no\"},"
                + "7],\"maintenance\":{\"enabled\":true}}");
        assertEquals(2, config.events.size());
        LauncherConfig.EventCountdown cup = config.events.get(0);
        assertEquals("cup", cup.id);
        assertEquals(2, cup.priority);
        assertEquals("https://projects.gg/turnuva", cup.url);
        assertFalse(cup.hidden);
        assertTrue(config.events.get(1).hidden);
        assertTrue(config.maintenance.isActive(0));
        assertTrue(parse("{\"events\":{}}").events.isEmpty());
        assertTrue(parse("{}").events.isEmpty());
    }
}
