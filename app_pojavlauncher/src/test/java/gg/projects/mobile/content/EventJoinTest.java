package gg.projects.mobile.content;

import static gg.projects.mobile.content.FeedFixtures.at;
import static gg.projects.mobile.content.FeedFixtures.event;
import static gg.projects.mobile.content.FeedFixtures.feed;
import static gg.projects.mobile.content.FeedFixtures.list;
import static gg.projects.mobile.content.FeedFixtures.utf8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * M17: "Katıl" on the event card (Q4's rule; content-format.md "Event card"): {@code join} on config
 * and feed events, a value that is not a boolean drops the event, and the card offers it only while
 * the event is live.
 */
public class EventJoinTest {
    @Test
    public void feedEventsCarryJoin() throws Exception {
        BotFeed feed = BotFeed.parse(utf8(feed(1, new ArrayList<String>(), list(
                event("discord-1", "2026-10-20T18:00:00Z", "2026-10-20T21:00:00Z", ",\"join\":true"),
                event("discord-2", "2026-10-20T18:00:00Z", "2026-10-20T21:00:00Z", ""),
                event("discord-3", "2026-10-20T18:00:00Z", "2026-10-20T21:00:00Z", ",\"join\":\"yes\""),
                event("discord-4", "2026-10-20T18:00:00Z", "2026-10-20T21:00:00Z", ",\"join\":1")))));
        List<String> ids = new ArrayList<>();
        for (BotFeed.Event event : feed.events) ids.add(event.id + "=" + event.join);
        assertEquals("a join that is not a boolean drops the event", java.util.Arrays.asList("discord-1=true", "discord-2=false"), ids);
        assertEquals(2, feed.problems.size());
    }

    @Test
    public void configEventsCarryJoin() throws Exception {
        LauncherConfig config = LauncherConfig.parse(utf8("{\"schema\":1,\"events\":["
                + "{\"id\":\"bedwars-cup\",\"title\":\"Bed Wars turnuvası\",\"startsAt\":\"2026-10-20T18:00:00Z\",\"endsAt\":\"2026-10-20T21:00:00Z\",\"join\":true},"
                + "{\"id\":\"quiet\",\"title\":\"Sessiz\",\"startsAt\":\"2026-10-20T18:00:00Z\",\"endsAt\":\"2026-10-20T21:00:00Z\"},"
                + "{\"id\":\"broken\",\"title\":\"Bozuk\",\"startsAt\":\"2026-10-20T18:00:00Z\",\"endsAt\":\"2026-10-20T21:00:00Z\",\"join\":null}]}"));
        assertEquals(2, config.events.size());
        assertTrue(config.events.get(0).join);
        assertFalse(config.events.get(1).join);
    }

    @Test
    public void theCardOffersJoinOnlyWhileTheEventIsLive() throws Exception {
        LauncherConfig config = LauncherConfig.parse(utf8("{\"schema\":1,\"events\":["
                + "{\"id\":\"bedwars-cup\",\"title\":\"Bed Wars turnuvası\",\"startsAt\":\"2026-10-20T18:00:00Z\",\"endsAt\":\"2026-10-20T21:00:00Z\",\"join\":true}]}"));
        BotFeed feed = BotFeed.parse(utf8(feed(1, new ArrayList<String>(), list(
                event("discord-9", "2026-10-20T19:00:00Z", null, ",\"join\":true")))));
        long before = at("2026-10-20T17:59:59Z");
        long during = at("2026-10-20T19:30:00Z");
        long after = at("2026-10-20T22:01:00Z");
        List<HomeContent.Event> events = HomeContent.mergeEvents(config.events, feed, new ArrayList<NewsFeed.NewsItem>(), before);
        assertEquals(2, events.size());
        for (HomeContent.Event event : events) {
            assertTrue(event.join);
            assertFalse("upcoming: the bell, never Katil", event.offersJoin(before));
            assertTrue(event.offersJoin(during));
            assertFalse(event.offersJoin(after));
        }
        // A feed event without an end is live for three hours, Katil included.
        HomeContent.Event open = events.get(1);
        assertTrue(open.openEnded);
        assertTrue(open.offersJoin(at("2026-10-20T21:59:00Z")));
        assertFalse(open.offersJoin(at("2026-10-20T22:00:00Z")));
        // Studio's copy wins with its own join.
        LauncherConfig copy = LauncherConfig.parse(utf8("{\"schema\":1,\"events\":["
                + "{\"id\":\"discord-9\",\"title\":\"Kopya\",\"startsAt\":\"2026-10-20T19:00:00Z\",\"endsAt\":\"2026-10-20T20:00:00Z\"}]}"));
        List<HomeContent.Event> merged = HomeContent.mergeEvents(copy.events, feed, new ArrayList<NewsFeed.NewsItem>(), during);
        assertEquals(1, merged.size());
        assertFalse(merged.get(0).offersJoin(during));
    }
}
