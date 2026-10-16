package gg.projects.mobile.content;

import static gg.projects.mobile.content.FeedFixtures.at;
import static gg.projects.mobile.content.FeedFixtures.event;
import static gg.projects.mobile.content.FeedFixtures.feed;
import static gg.projects.mobile.content.FeedFixtures.item;
import static gg.projects.mobile.content.FeedFixtures.list;
import static gg.projects.mobile.content.FeedFixtures.utf8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The home screen rules (content-format.md "Home screen" and "Event card"), mirrors of pl-content's merge.rs tests. */
public class HomeContentTest {
    private static final long NOW = at("2026-10-16T12:00:00Z");

    private static NewsFeed studio(String... items) throws ContentException {
        return NewsFeed.parse(utf8("{\"schema\":1,\"items\":[" + String.join(",", items) + "]}"));
    }

    private static String studioItem(String id, String published, String extra) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"publishedAt\":\"" + published + "\"" + extra + "}";
    }

    private static BotFeed bot(List<String> items, List<String> events) throws ContentException {
        return BotFeed.parse(utf8(feed(1, items, events)));
    }

    private static LauncherConfig config(String events) throws ContentException {
        return LauncherConfig.parse(utf8("{\"schema\":1,\"events\":[" + events + "]}"));
    }

    private static List<String> ids(List<NewsFeed.NewsItem> news) {
        List<String> out = new ArrayList<>();
        for (NewsFeed.NewsItem item : news) out.add(item.id);
        return out;
    }

    private static List<String> eventIds(List<HomeContent.Event> events) {
        List<String> out = new ArrayList<>();
        for (HomeContent.Event event : events) out.add(event.id);
        return out;
    }

    private static String onCard(LauncherConfig config, BotFeed feed, String at) {
        List<HomeContent.Event> events = HomeContent.mergeEvents(config.events, feed, new ArrayList<NewsFeed.NewsItem>(), at(at));
        return events.isEmpty() ? null : events.get(0).id;
    }

    @Test
    public void studioWinsAndTheOrderHolds() throws Exception {
        NewsFeed studio = studio(
                studioItem("kis-sezonu", "2026-10-14T09:00:00Z", ",\"pinned\":true,\"pinOrder\":1"),
                studioItem("discord-2", "2026-10-15T18:30:00Z", ",\"pinned\":true,\"pinOrder\":2,\"category\":\"patchNotes\""),
                studioItem("discord-3", "2026-10-16T07:00:00Z", ",\"hidden\":true,\"expiresAt\":\"2026-10-16T07:00:00Z\""),
                studioItem("discord-4", "2026-10-20T00:00:00Z", ""),
                studioItem("discord-5", "2026-10-01T00:00:00Z", ",\"expiresAt\":\"2026-10-02T00:00:00Z\""),
                studioItem("studio-old", "2026-10-10T00:00:00Z", ""));
        BotFeed feed = bot(list(
                item("discord-1", "2026-10-16T08:58:00Z", ",\"pinned\":true"),
                item("discord-2", "2026-10-15T18:30:00Z", ""),
                item("discord-3", "2026-10-16T07:00:00Z", ""),
                item("discord-4", "2026-10-16T06:00:00Z", ""),
                item("discord-5", "2026-10-16T05:00:00Z", ""),
                item("discord-6", "2026-10-11T00:00:00Z", "")), list());
        List<NewsFeed.NewsItem> news = HomeContent.mergeNews(studio, feed, 60, NOW);
        // Pinned Studio items in their pinOrder (including Studio's copy of a feed item), then the
        // rest newest first; feed items are never pinned; hidden, scheduled or expired Studio items
        // keep the feed items with their ids out.
        assertEquals(Arrays.asList("kis-sezonu", "discord-2", "discord-1", "discord-6", "studio-old"), ids(news));
        assertEquals("discord-2", news.get(1).id);
        assertTrue("Studio's copy is shown, not the feed's", !news.get(1).fromFeed());
        assertTrue(news.get(2).fromFeed());
    }

    @Test
    public void feedVisibilityAndTheAgeLimit() throws Exception {
        BotFeed feed = bot(list(
                item("fresh", "2026-10-16T11:00:00Z", ""),
                item("edge", "2026-08-17T12:00:00Z", ""),
                item("too-old", "2026-08-17T11:59:59Z", ""),
                item("scheduled", "2026-10-16T13:00:00Z", ""),
                item("expired", "2026-10-15T00:00:00Z", ",\"expiresAt\":\"2026-10-16T12:00:00Z\"")), list());
        assertEquals(Arrays.asList("fresh", "edge"), ids(HomeContent.mergeNews(studio(), feed, 60, NOW)));
        assertEquals(Arrays.asList("fresh"), ids(HomeContent.mergeNews(studio(), feed, 1, NOW)));
        // Studio items have no age limit.
        assertEquals(Arrays.asList("ancient"), ids(HomeContent.mergeNews(
                studio(studioItem("ancient", "2020-01-01T00:00:00Z", "")), null, 60, NOW)));
        // The config's maxAgeDays reaches the merge through the feed's settings.
        FeedFixtures.Key key = new FeedFixtures.Key();
        FeedSettings week = FeedSettings.of(FeedFixtures.config(list("https://feed.test/v1/"), Arrays.asList(key),
                ",\"maxAgeDays\":1"), null);
        HomeContent home = HomeContent.of(studio(), null, new FeedSnapshot(feed, week, null, 0, false, null), NOW);
        assertEquals(Arrays.asList("fresh"), ids(home.news));
    }

    @Test
    public void patchNotesPickTheNewestFromEitherSource() throws Exception {
        NewsFeed studio = studio(
                studioItem("studio-notes", "2026-10-15T00:00:00Z", ",\"category\":\"patchNotes\",\"pinned\":true,\"pinOrder\":1"),
                studioItem("studio-news", "2026-10-16T11:00:00Z", ""));
        BotFeed feed = bot(list(
                item("feed-notes-b", "2026-10-16T10:00:00Z", "").replace("announcement", "patchNotes"),
                item("feed-notes-a", "2026-10-16T10:00:00Z", "").replace("announcement", "patchNotes"),
                item("feed-poll", "2026-10-16T11:30:00Z", "").replace("announcement", "poll")), list());
        List<NewsFeed.NewsItem> news = HomeContent.mergeNews(studio, feed, 60, NOW);
        assertEquals("feed-notes-a", HomeContent.newestPatchNotes(news).id);
        assertEquals("studio-notes", HomeContent.newestPatchNotes(HomeContent.mergeNews(studio, null, 60, NOW)).id);
        assertNull(HomeContent.newestPatchNotes(HomeContent.mergeNews(studio(studioItem("x", "2026-10-01T00:00:00Z", "")), null, 60, NOW)));
    }

    @Test
    public void liveEventsLeadThenTheSoonestUpcoming() throws Exception {
        LauncherConfig config = config(String.join(",",
                "{\"id\":\"turnuva\",\"title\":\"Turnuva\",\"startsAt\":\"2026-10-20T18:00:00Z\",\"endsAt\":\"2026-10-20T21:00:00Z\"}",
                "{\"id\":\"gizli\",\"title\":\"Gizli\",\"startsAt\":\"2026-10-16T10:00:00Z\",\"endsAt\":\"2026-10-16T20:00:00Z\",\"hidden\":true}",
                "{\"id\":\"bozuk\",\"title\":\"Bozuk\",\"startsAt\":\"2026-10-16T10:00:00Z\",\"endsAt\":\"2026-10-16T20:00:00Z\",\"priority\":1.5}"));
        assertEquals("a broken event drops only itself", 2, config.events.size());
        BotFeed feed = bot(list(item("discord-1", "2026-10-16T08:00:00Z", "")), list(
                event("gizli", "2026-10-16T09:00:00Z", "2026-10-16T22:00:00Z", ""),
                event("kis", "2026-10-16T11:00:00Z", "2026-10-16T14:00:00Z", ",\"newsId\":\"discord-1\""),
                event("hafta", "2026-10-14T00:00:00Z", "2026-10-23T00:00:00Z", ",\"priority\":-1"),
                event("pvp", "2026-10-17T16:00:00Z", null, ""),
                event("bitti", "2026-10-15T00:00:00Z", "2026-10-15T03:00:00Z", "")));
        HomeContent home = HomeContent.of(studio(), config, new FeedSnapshot(feed, null, null, 0, false, null), NOW);
        assertEquals(Arrays.asList("kis", "hafta", "pvp", "turnuva"), eventIds(home.events));
        HomeContent.Event kis = home.events.get(0);
        assertTrue(kis.isLive(NOW));
        assertEquals("discord-1", kis.newsId);
        HomeContent.Event pvp = home.events.get(2);
        assertTrue(pvp.openEnded);
        assertEquals(at("2026-10-17T19:00:00Z"), pvp.endsAt);
        assertEquals(at("2026-10-16T14:00:00Z"), HomeContent.nextBoundary(home.events, NOW));
        // Without the news item on the home screen, the event keeps no newsId.
        assertNull(HomeContent.mergeEvents(config.events, feed, new ArrayList<NewsFeed.NewsItem>(), NOW).get(0).newsId);
    }

    @Test
    public void aShortEventOverlaysALongOneWhileItRuns() throws Exception {
        LauncherConfig config = config("");
        BotFeed feed = bot(list(), list(
                event("week", "2026-10-14T00:00:00Z", "2026-10-21T00:00:00Z", ""),
                event("evening", "2026-10-16T17:00:00Z", "2026-10-16T20:00:00Z", ""),
                event("open", "2026-10-18T10:00:00Z", null, "")));
        assertEquals("week", onCard(config, feed, "2026-10-16T16:59:59Z"));
        assertEquals("evening", onCard(config, feed, "2026-10-16T17:00:00Z"));
        assertEquals("evening", onCard(config, feed, "2026-10-16T19:59:59Z"));
        assertEquals("week", onCard(config, feed, "2026-10-16T20:00:00Z"));
        // The open-ended event is live for three hours, then gone.
        assertEquals("open", onCard(config, feed, "2026-10-18T12:59:59Z"));
        assertEquals("week", onCard(config, feed, "2026-10-18T13:00:00Z"));
        assertNull(onCard(config, feed, "2026-10-21T00:00:00Z"));

        // A higher priority keeps the long one in front; a lower one yields to everything live.
        BotFeed prioritized = bot(list(), list(
                event("week", "2026-10-14T00:00:00Z", "2026-10-21T00:00:00Z", ",\"priority\":1"),
                event("evening", "2026-10-16T17:00:00Z", "2026-10-16T20:00:00Z", "")));
        assertEquals("week", onCard(config, prioritized, "2026-10-16T18:00:00Z"));
        BotFeed yielding = bot(list(), list(
                event("evening", "2026-10-16T17:00:00Z", "2026-10-16T20:00:00Z", ",\"priority\":-1"),
                event("week", "2026-10-14T00:00:00Z", "2026-10-21T00:00:00Z", "")));
        assertEquals("week", onCard(config, yielding, "2026-10-16T18:00:00Z"));
    }

    @Test
    public void clockEdgesAndTies() throws Exception {
        // Same start: config before feed, then the smaller id; upcoming: sooner start, then priority.
        LauncherConfig config = config("{\"id\":\"z-config\",\"title\":\"Z\",\"startsAt\":\"2026-10-17T10:00:00Z\",\"endsAt\":\"2026-10-17T12:00:00Z\"}");
        BotFeed feed = bot(list(), list(
                event("a-feed", "2026-10-17T10:00:00Z", "2026-10-17T12:00:00Z", ""),
                event("b-feed", "2026-10-17T10:00:00Z", "2026-10-17T12:00:00Z", ",\"priority\":3"),
                event("c-feed", "2026-10-17T09:00:00Z", "2026-10-17T09:30:00Z", "")));
        List<HomeContent.Event> upcoming = HomeContent.mergeEvents(config.events, feed, new ArrayList<NewsFeed.NewsItem>(), NOW);
        assertEquals(Arrays.asList("c-feed", "b-feed", "z-config", "a-feed"), eventIds(upcoming));
        List<HomeContent.Event> live = HomeContent.cardEvents(upcoming, at("2026-10-17T10:00:00Z"));
        assertEquals(Arrays.asList("b-feed", "z-config", "a-feed"), eventIds(live));
        assertEquals(HomeContent.Phase.ENDED, upcoming.get(0).phase(at("2026-10-17T09:30:00Z")));
        assertEquals(HomeContent.Phase.LIVE, upcoming.get(0).phase(at("2026-10-17T09:00:00Z")));
        assertEquals(-1, HomeContent.nextBoundary(live, at("2026-10-17T12:00:00Z")));
    }
}
