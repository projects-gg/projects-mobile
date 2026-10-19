package gg.projects.mobile.content;

import static gg.projects.mobile.content.FeedFixtures.at;
import static gg.projects.mobile.content.FeedFixtures.event;
import static gg.projects.mobile.content.FeedFixtures.feed;
import static gg.projects.mobile.content.FeedFixtures.item;
import static gg.projects.mobile.content.FeedFixtures.list;
import static gg.projects.mobile.content.FeedFixtures.utf8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The feed's status notice (content-format.md "Status notice"), mirrors of pl-content's
 * model/feed.rs and feed/merge.rs status tests.
 */
public class FeedStatusTest {
    private static final String NOW = "2026-10-16T12:00:00Z";

    private static String status(String level) {
        return "{\"level\":\"" + level + "\",\"title\":\"Bazı hizmetlerde sorun var\","
                + "\"message\":\"Şu an erişilemiyor: Survival Zephyr\","
                + "\"services\":[{\"name\":\"Survival Zephyr\",\"state\":\"down\"}],"
                + "\"url\":\"https://status.projects.gg\","
                + "\"updatedAt\":\"2026-10-16T11:55:00Z\",\"expiresAt\":\"2026-10-16T12:10:00Z\"}";
    }

    /** A feed with one item, one event and {@code status} (raw JSON, or null for none). */
    private static BotFeed withStatus(String status) throws ContentException {
        String text = feed(1792130400L, list(item("discord-1", "2026-10-16T08:00:00Z", "")),
                list(event("discord-9", "2026-10-18T17:00:00Z", "2026-10-18T20:00:00Z", "")));
        if (status != null) text = text.substring(0, text.length() - 1) + ",\"status\":" + status + "}";
        return BotFeed.parse(utf8(text));
    }

    /** The feed's other entries survive whatever happens to the notice. */
    private static void restIntact(BotFeed feed) {
        assertEquals(1, feed.items.size());
        assertEquals(1, feed.events.size());
    }

    @Test
    public void aValidNoticeIsRead() throws Exception {
        BotFeed feed = withStatus(status("outage"));
        assertTrue(feed.problems.toString(), feed.problems.isEmpty());
        FeedStatus status = feed.status;
        assertNotNull(status);
        assertEquals(FeedStatus.Level.OUTAGE, status.level);
        assertEquals("Bazı hizmetlerde sorun var", status.title);
        assertEquals("Şu an erişilemiyor: Survival Zephyr", status.message);
        assertEquals(1, status.services.size());
        assertEquals("Survival Zephyr", status.services.get(0).name);
        assertEquals(FeedStatus.State.DOWN, status.services.get(0).state);
        assertEquals("https://status.projects.gg", status.url);
        assertEquals(at("2026-10-16T11:55:00Z"), status.updatedAt);
        assertEquals(at("2026-10-16T12:10:00Z"), status.expiresAt);
        assertEquals(FeedStatus.Level.DEGRADED, withStatus(status("degraded")).status.level);
        assertEquals(FeedStatus.Level.NOTICE, withStatus(status("notice")).status.level);
    }

    @Test
    public void noNoticeWhileEverythingWorks() throws Exception {
        BotFeed quiet = withStatus(null);
        assertNull(quiet.status);
        assertTrue(quiet.problems.isEmpty());
        BotFeed nullStatus = withStatus("null");
        assertNull(nullStatus.status);
        assertTrue(nullStatus.problems.isEmpty());
    }

    @Test
    public void shownOnlyUntilItExpires() throws Exception {
        BotFeed feed = withStatus(status("outage"));
        assertSame(feed.status, HomeContent.homeStatus(feed, false, at(NOW)));
        assertNotNull(HomeContent.homeStatus(feed, false, at("2026-10-16T12:09:59Z")));
        // An expired notice is never shown, a cached one included, so a stopped bot leaves nothing behind.
        assertNull(HomeContent.homeStatus(feed, false, at("2026-10-16T12:10:00Z")));
        assertFalse(feed.status.isActive(at("2026-10-16T12:10:00Z")));
        assertNull(HomeContent.homeStatus(null, false, at(NOW)));
        assertNull(HomeContent.homeStatus(withStatus(null), false, at(NOW)));
    }

    @Test
    public void studiosMaintenanceNoticeWins() throws Exception {
        BotFeed feed = withStatus(status("outage"));
        assertNull(HomeContent.homeStatus(feed, true, at(NOW)));
        FeedSnapshot snapshot = new FeedSnapshot(feed, null, null, 0, false, null);
        LauncherConfig maintenance = LauncherConfig.parse(utf8(
                "{\"schema\":1,\"maintenance\":{\"enabled\":true,\"title\":\"Bakım\"}}"));
        assertNull(HomeContent.of(null, maintenance, snapshot, at(NOW)).status);
        LauncherConfig over = LauncherConfig.parse(utf8(
                "{\"schema\":1,\"maintenance\":{\"enabled\":true,\"until\":\"2026-10-16T11:00:00Z\"}}"));
        assertSame(feed.status, HomeContent.of(null, over, snapshot, at(NOW)).status);
        assertSame(feed.status, HomeContent.of(null, null, snapshot, at(NOW)).status);
    }

    @Test
    public void aBrokenNoticeDropsOnlyItself() throws Exception {
        String[] broken = {
                status("outage").replace("\"title\":\"Bazı hizmetlerde sorun var\"", "\"title\":\"  \""),
                status("outage").replace("\"title\":\"Bazı hizmetlerde sorun var\",", ""),
                status("outage").replace("Bazı hizmetlerde sorun var", repeat("ş", 81)),
                status("outage").replace("Şu an erişilemiyor: Survival Zephyr", repeat("a", 201)),
                status("outage").replace("\"name\":\"Survival Zephyr\"", "\"name\":\"\""),
                status("outage").replace("\"name\":\"Survival Zephyr\"", "\"name\":\"" + repeat("x", 65) + "\""),
                status("outage").replace("[{\"name\":\"Survival Zephyr\",\"state\":\"down\"}]", services(21)),
                // expiresAt not after updatedAt, or more than 15 minutes after it
                status("outage").replace("2026-10-16T12:10:00Z", "2026-10-16T11:55:00Z"),
                status("outage").replace("2026-10-16T12:10:00Z", "2026-10-16T12:10:01Z"),
                // wrong types and missing timestamps
                status("outage").replace("\"level\":\"outage\"", "\"level\":3"),
                status("outage").replace("\"level\":\"outage\"", "\"level\":null"),
                status("outage").replace("\"message\":\"Şu an erişilemiyor: Survival Zephyr\"", "\"message\":null"),
                status("outage").replace("\"state\":\"down\"", "\"state\":1"),
                status("outage").replace("\"url\":\"https://status.projects.gg\"", "\"url\":5"),
                status("outage").replace(",\"expiresAt\":\"2026-10-16T12:10:00Z\"", ""),
                status("outage").replace("\"updatedAt\":\"2026-10-16T11:55:00Z\"", "\"updatedAt\":\"dün\""),
                "\"outage\"",
                "[]",
        };
        for (String status : broken) {
            BotFeed feed = withStatus(status);
            assertNull(status, feed.status);
            restIntact(feed);
            assertEquals(status, 1, feed.problems.size());
            assertTrue(feed.problems.get(0), feed.problems.get(0).startsWith("status dropped"));
        }
    }

    @Test
    public void limitsHoldAtTheirEdges() throws Exception {
        String edge = status("degraded")
                .replace("Bazı hizmetlerde sorun var", repeat("ş", 80))
                .replace("Şu an erişilemiyor: Survival Zephyr", repeat("ğ", 200))
                .replace("[{\"name\":\"Survival Zephyr\",\"state\":\"down\"}]", services(20));
        // ...and it expires exactly 15 minutes after its update (11:55 to 12:10).
        BotFeed feed = withStatus(edge);
        assertNotNull(feed.problems.toString(), feed.status);
        assertEquals(20, feed.status.services.size());
        // Characters are Unicode scalar values: 80 emoji (two UTF-16 units each) still fit.
        assertNotNull(withStatus(status("notice").replace("Bazı hizmetlerde sorun var", repeat("🔥", 80))).status);
        // A message may be empty or missing; services may be missing.
        BotFeed bare = withStatus("{\"title\":\"Durum sayfasında yeni bir duyuru var\","
                + "\"updatedAt\":\"2026-10-16T11:59:00Z\",\"expiresAt\":\"2026-10-16T12:00:30Z\"}");
        assertNotNull(bare.problems.toString(), bare.status);
        assertEquals(FeedStatus.Level.NOTICE, bare.status.level);
        assertEquals("", bare.status.message);
        assertTrue(bare.status.services.isEmpty());
        assertNull(bare.status.url);
    }

    @Test
    public void unknownValuesTakeSafeDefaults() throws Exception {
        BotFeed feed = withStatus(status("meteor").replace("\"state\":\"down\"", "\"state\":\"flooded\""));
        assertEquals(FeedStatus.Level.NOTICE, feed.status.level);
        assertEquals(FeedStatus.State.UNKNOWN, feed.status.services.get(0).state);
        assertTrue(feed.problems.isEmpty());
        BotFeed noState = withStatus(status("outage").replace(",\"state\":\"down\"", ""));
        assertEquals(FeedStatus.State.UNKNOWN, noState.status.services.get(0).state);
    }

    @Test
    public void fieldsANewerBotAddsAreIgnored() throws Exception {
        String text = feed(1792130400L, list(item("discord-1", "2026-10-16T08:00:00Z", ",\"reactions\":3")),
                // M17: "join" is a known field since Q4 (a non-boolean drops the event); "lobby" is not.
                list(event("discord-9", "2026-10-18T17:00:00Z", "2026-10-18T20:00:00Z", ",\"lobby\":{\"server\":\"x\"}")));
        String status = status("outage")
                .replace("\"level\":\"outage\"", "\"level\":\"outage\",\"incidentId\":\"abc\",\"severity\":{\"score\":9}")
                .replace("\"state\":\"down\"", "\"state\":\"down\",\"region\":\"eu\"");
        text = text.substring(0, text.length() - 1) + ",\"status\":" + status + ",\"banner\":{\"text\":\"x\"}}";
        BotFeed feed = BotFeed.parse(utf8(text));
        assertTrue(feed.problems.toString(), feed.problems.isEmpty());
        assertNotNull(feed.status);
        assertEquals("Survival Zephyr", feed.status.services.get(0).name);
        restIntact(feed);
    }

    @Test
    public void anUnsafeStatusPageLinkIsRemovedAndTheNoticeKept() throws Exception {
        String[] links = {"http://status.projects.gg", "javascript:alert(1)", "projects://news/1",
                "https://user:pass@status.projects.gg", "https://", " https://status.projects.gg"};
        for (String link : links) {
            BotFeed feed = withStatus(status("outage").replace("https://status.projects.gg", link));
            assertNotNull(link, feed.status);
            assertNull(link, feed.status.url);
            assertEquals("Bazı hizmetlerde sorun var", feed.status.title);
            assertEquals(link, 1, feed.problems.size());
            assertEquals("status: link removed", feed.problems.get(0));
        }
        assertNull(withStatus(status("outage").replace("\"url\":\"https://status.projects.gg\"", "\"url\":null")).status.url);
    }

    private static String services(int count) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(',');
            out.append("{\"name\":\"Sunucu ").append(i).append("\",\"state\":\"degraded\"}");
        }
        return out.append(']').toString();
    }

    private static String repeat(String text, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(text);
        return out.toString();
    }
}
