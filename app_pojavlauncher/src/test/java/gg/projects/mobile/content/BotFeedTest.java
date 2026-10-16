package gg.projects.mobile.content;

import static gg.projects.mobile.content.FeedFixtures.event;
import static gg.projects.mobile.content.FeedFixtures.feed;
import static gg.projects.mobile.content.FeedFixtures.item;
import static gg.projects.mobile.content.FeedFixtures.list;
import static gg.projects.mobile.content.FeedFixtures.utf8;
import static gg.projects.mobile.content.IndexVerifierTest.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** {@code feed.json} rules (content-format.md "News feed"), mirrors of pl-content's model/feed.rs tests. */
public class BotFeedTest {
    private static final String T = "2026-10-16T08:00:00Z";

    private static BotFeed parse(List<String> items, List<String> events) throws ContentException {
        return BotFeed.parse(utf8(feed(1792130400L, items, events)));
    }

    private static List<String> itemIds(BotFeed feed) {
        List<String> ids = new ArrayList<>();
        for (NewsFeed.NewsItem item : feed.items) ids.add(item.id);
        return ids;
    }

    private static List<String> eventIds(BotFeed feed) {
        List<String> ids = new ArrayList<>();
        for (BotFeed.Event event : feed.events) ids.add(event.id);
        return ids;
    }

    private static String repeat(String text, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(text);
        return out.toString();
    }

    @Test
    public void aCleanFeedHasNoProblems() throws Exception {
        byte[] coverBytes = utf8("webp");
        BotFeed feed = parse(
                list(item("discord-1", T, FeedFixtures.cover(coverBytes, ",\"blurhash\":\"LKO2?U%2Tw=w]~RBVZRi};RPxuwH\"")),
                        item("discord-2", T, "").replace("\"category\":\"announcement\"", "\"category\":\"patchNotes\"")),
                list(event("discord-1", "2026-10-18T17:00:00Z", "2026-10-18T20:00:00Z", ",\"newsId\":\"discord-1\",\"priority\":2")));
        assertTrue(feed.problems.toString(), feed.problems.isEmpty());
        assertEquals(1792130400L, feed.sequence);
        assertEquals(FeedFixtures.at("2026-10-16T09:00:00Z"), feed.generatedAt);
        NewsFeed.NewsItem first = feed.items.get(0);
        assertTrue(first.fromFeed());
        assertEquals("https://discord.com/channels/1/2/1", first.sourceUrl());
        assertEquals("LKO2?U%2Tw=w]~RBVZRi};RPxuwH", first.cover.blurhash);
        assertEquals(Integer.valueOf(1280), first.cover.width);
        assertTrue(feed.items.get(1).isPatchNotes());
        BotFeed.Event event = feed.events.get(0);
        assertEquals("discord-1", event.newsId);
        assertEquals(2, event.priority);
        assertEquals(FeedFixtures.at("2026-10-18T20:00:00Z"), event.end());
    }

    @Test
    public void wholeFeedRulesFailTheFeed() {
        expect(ContentException.Kind.UNSUPPORTED_SCHEMA, () -> BotFeed.parse(utf8(
                "{\"schema\":2,\"sequence\":1,\"generatedAt\":\"2026-10-16T09:00:00Z\"}")));
        for (String bad : new String[]{
                "not json", "[]", "{\"generatedAt\":\"2026-10-16T09:00:00Z\"}",
                "{\"sequence\":-1,\"generatedAt\":\"2026-10-16T09:00:00Z\"}",
                "{\"sequence\":1.5,\"generatedAt\":\"2026-10-16T09:00:00Z\"}",
                "{\"sequence\":1,\"generatedAt\":\"yesterday\"}",
                "{\"sequence\":1,\"generatedAt\":\"2026-10-16T09:00:00Z\",\"items\":{}}",
                "{\"sequence\":1,\"generatedAt\":\"2026-10-16T09:00:00Z\",\"events\":null}",
                "{\"schema\":null,\"sequence\":1,\"generatedAt\":\"2026-10-16T09:00:00Z\"}"}) {
            expect(ContentException.Kind.INVALID, () -> BotFeed.parse(utf8(bad)));
        }
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) many.add(item("discord-" + i, T, ""));
        expect(ContentException.Kind.INVALID, () -> parse(many, list()));
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 21; i++) events.add(event("e-" + i, T, null, ""));
        expect(ContentException.Kind.INVALID, () -> parse(list(), events));
        byte[] large = utf8(feed(1, list(item("discord-1", T, ",\"padding\":\"" + repeat("x", 530_000) + "\"")), list()));
        expect(ContentException.Kind.INVALID, () -> BotFeed.parse(large));
        expect(ContentException.Kind.INVALID, () -> BotFeed.parse(new byte[]{(byte) 0xC3, 0x28}));
    }

    @Test
    public void brokenItemsDropOnlyThemselves() throws Exception {
        BotFeed feed = parse(list(
                item("ok-1", T, ""),
                "{\"id\":\"no-title\",\"publishedAt\":\"" + T + "\"}",
                item("bad-type", T, ",\"featured\":\"yes\""),
                item("null-summary", T, "").replace("\"summary\":\"Özet\"", "\"summary\":null"),
                item("bad id!", T, ""),
                item("..", T, ""),
                item(repeat("a", 65), T, ""),
                item("ok-1", T, "").replace("\"category\":\"announcement\"", "\"category\":\"patchNotes\""),
                item("blank", T, "").replace("\"title\":\"Haber blank\"", "\"title\":\" \\u2003 \""),
                item("long-title", T, "").replace("\"title\":\"Haber long-title\"", "\"title\":\"" + repeat("😀", 121) + "\""),
                item("ok-title-120", T, "").replace("\"title\":\"Haber ok-title-120\"", "\"title\":\"" + repeat("😀", 120) + "\""),
                item("long-summary", T, "").replace("\"summary\":\"Özet\"", "\"summary\":\"" + repeat("ş", 301) + "\""),
                item("long-body", T, "").replace("\"body\":\"- madde\"", "\"body\":\"" + repeat("b", 8001) + "\""),
                item("bad-date", "16 Ekim", ""),
                item("bad-expiry", T, ",\"expiresAt\":\"never\""),
                item("bad-tags", T, "").replace("\"tags\":[]", "\"tags\":[1]"),
                item("bad-cover", T, ",\"cover\":{\"path\":\"media/a.webp\",\"size\":\"big\"}"),
                item("ok-2", T, "")), list());
        assertEquals(Arrays.asList("ok-1", "ok-title-120", "ok-2"), itemIds(feed));
        // The first of two items with the same id wins.
        assertFalse(feed.item("ok-1").isPatchNotes());
        assertEquals(15, feed.problems.size());
    }

    @Test
    public void unsafePartsAreRemovedAndPinsIgnored() throws Exception {
        byte[] bytes = utf8("cover");
        String sha = Hashes.sha256Hex(bytes);
        String goodCover = FeedFixtures.cover(bytes, ",\"poster\":{\"path\":\"media/p.webp\",\"sha256\":\"" + sha + "\",\"size\":1}");
        BotFeed feed = parse(list(
                item("pinned", T, ",\"pinned\":true,\"pinOrder\":1,\"hidden\":true" + goodCover),
                item("video", T, goodCover.replace("\"kind\":\"image\"", "\"kind\":\"video\"")),
                item("empty-file", T, goodCover.replace("\"size\":5", "\"size\":0")),
                item("too-big", T, goodCover.replace("\"size\":5", "\"size\":2097153")),
                item("bad-path", T, goodCover.replace("media/" + sha, "../" + sha)),
                item("bad-sha", T, goodCover.replace("\"sha256\":\"" + sha, "\"sha256\":\"xyz")),
                item("http-button", T, ",\"cta\":{\"label\":\"Aç\",\"url\":\"http://projects.gg\"}"),
                item("two-targets", T, ",\"cta\":{\"label\":\"Aç\",\"url\":\"https://projects.gg\",\"newsId\":\"pinned\"}"),
                item("no-label", T, ",\"cta\":{\"label\":\" \",\"url\":\"https://projects.gg\"}"),
                item("news-button", T, ",\"cta\":{\"label\":\"Oku\",\"newsId\":\"pinned\"}"),
                item("deep-link", T, ",\"cta\":{\"label\":\"Paketi aç\",\"url\":\"projects://pack/projects\"}"),
                item("http-source", T, "").replace("https://discord.com", "http://discord.com"),
                item("deep-source", T, "").replace("https://discord.com/channels/1/2/", "projects://news/x")),
                list(event("e-1", T, null, ",\"url\":\"javascript:alert(1)\",\"newsId\":\"missing\""),
                        event("e-2", T, null, ",\"url\":\"https://projects.gg/kis\",\"newsId\":\"pinned\"")));
        NewsFeed.NewsItem pinned = feed.item("pinned");
        assertFalse(pinned.pinned);
        assertNull(pinned.pinOrder);
        assertFalse(pinned.hidden);
        assertTrue(pinned.isVisible(FeedFixtures.at(T)));
        assertNotNull(pinned.cover);
        assertNull("posters are ignored", pinned.cover.poster);
        for (String id : new String[]{"video", "empty-file", "too-big", "bad-path", "bad-sha"}) {
            assertNull(id, feed.item(id).cover);
        }
        for (String id : new String[]{"http-button", "two-targets", "no-label"}) assertNull(id, feed.item(id).cta);
        assertEquals("pinned", feed.item("news-button").cta.newsId);
        assertEquals("projects://pack/projects", feed.item("deep-link").cta.url);
        assertNull(feed.item("http-source").sourceUrl());
        assertNull(feed.item("deep-source").sourceUrl());
        assertEquals(Arrays.asList("e-1", "e-2"), eventIds(feed));
        assertNull(feed.events.get(0).url);
        assertNull(feed.events.get(0).newsId);
        assertEquals("https://projects.gg/kis", feed.events.get(1).url);
        assertEquals("pinned", feed.events.get(1).newsId);
    }

    @Test
    public void eventsAreCheckedOneByOne() throws Exception {
        BotFeed feed = parse(list(), list(
                event("open", "2026-10-18T17:00:00Z", null, ""),
                event("closed", "2026-10-18T17:00:00Z", "2026-10-18T19:00:00Z", ",\"priority\":-1"),
                event("ends-at-start", "2026-10-18T17:00:00Z", "2026-10-18T17:00:00Z", ""),
                event("ends-before", "2026-10-18T17:00:00Z", "2026-10-18T16:00:00Z", ""),
                event("fractional", "2026-10-18T17:00:00Z", null, ",\"priority\":1.5"),
                event("huge", "2026-10-18T17:00:00Z", null, ",\"priority\":2147483648"),
                event("null-priority", "2026-10-18T17:00:00Z", null, ",\"priority\":null"),
                event("open", "2026-10-19T17:00:00Z", null, ""),
                "{\"id\":\"no-start\",\"title\":\"x\"}",
                event("bad id", "2026-10-18T17:00:00Z", null, ""),
                event("blank", "2026-10-18T17:00:00Z", null, "").replace("\"title\":\"Etkinlik blank\"", "\"title\":\" \""),
                event("long", "2026-10-18T17:00:00Z", null, "").replace("\"title\":\"Etkinlik long\"", "\"title\":\"" + repeat("x", 121) + "\"")));
        assertEquals(Arrays.asList("open", "closed"), eventIds(feed));
        BotFeed.Event open = feed.events.get(0);
        assertNull(open.endsAt);
        assertEquals(FeedFixtures.at("2026-10-18T20:00:00Z"), open.end());
        assertEquals(FeedFixtures.at("2026-10-18T17:00:00Z"), open.startsAt);
        assertEquals(-1, feed.events.get(1).priority);
        assertEquals(10, feed.problems.size());
    }

    @Test
    public void unknownFieldsAndValuesAreTolerated() throws Exception {
        BotFeed feed = BotFeed.parse(utf8("{\"schema\":1,\"sequence\":5,\"generatedAt\":\"2026-10-16T09:00:00+03:00\","
                + "\"future\":{\"x\":1},\"items\":[" + item("discord-1", T, ",\"reactions\":3")
                .replace("\"category\":\"announcement\"", "\"category\":\"poll\"")
                .replace("\"kind\":\"discord\"", "\"kind\":\"matrix\"") + "]}"));
        assertTrue(feed.problems.isEmpty());
        assertEquals(FeedFixtures.at("2026-10-16T06:00:00Z"), feed.generatedAt);
        NewsFeed.NewsItem item = feed.items.get(0);
        assertEquals("announcement", item.categoryOrDefault());
        assertFalse(item.isPatchNotes());
        assertTrue(feed.events.isEmpty());
    }
}
