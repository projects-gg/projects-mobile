package gg.projects.mobile.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** {@code hidden} and {@code pinOrder} (content-format.md "News"), mirrors of pl-content's news.rs tests. */
public class NewsOrderTest {
    private static final long NOW = Rfc3339.parse("2026-10-16T12:00:00Z");

    private static String item(String id, String published, String extra) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"publishedAt\":\"" + published + "\"" + extra + "}";
    }

    private static List<String> visibleIds(String... items) throws ContentException {
        String json = "{\"schema\":1,\"items\":[" + String.join(",", items) + "]}";
        List<String> ids = new ArrayList<>();
        for (NewsFeed.NewsItem i : NewsFeed.parse(json.getBytes(StandardCharsets.UTF_8)).visible(NOW)) ids.add(i.id);
        return ids;
    }

    @Test
    public void pinnedItemsFollowTheirOrder() throws Exception {
        List<String> ids = visibleIds(
                item("newest", "2026-10-16T09:00:00Z", ""),
                item("pin-unordered-new", "2026-10-15T00:00:00Z", ",\"pinned\":true"),
                item("pin-unordered-old", "2026-10-01T00:00:00Z", ",\"pinned\":true"),
                item("pin-2", "2026-10-14T00:00:00Z", ",\"pinned\":true,\"pinOrder\":2"),
                item("pin-1-old", "2026-09-01T00:00:00Z", ",\"pinned\":true,\"pinOrder\":1"),
                // pinOrder means nothing on an item that is not pinned.
                item("not-pinned", "2026-10-13T00:00:00Z", ",\"pinOrder\":1"),
                // Not a u32: the item stays, as if it had no position.
                item("pin-negative", "2026-10-12T00:00:00Z", ",\"pinned\":true,\"pinOrder\":-3"));
        assertEquals("[pin-1-old, pin-2, pin-unordered-new, pin-negative, pin-unordered-old, newest, not-pinned]",
                ids.toString());
    }

    @Test
    public void hiddenItemsAreNeverShownButStillPublished() throws Exception {
        String json = "{\"schema\":1,\"items\":["
                + item("shown", "2026-10-15T00:00:00Z", "") + ","
                + item("hidden", "2026-10-15T00:00:00Z", ",\"hidden\":true,\"expiresAt\":\"2026-10-15T00:00:00Z\"") + ","
                + item("hidden-pinned", "2026-10-15T00:00:00Z", ",\"hidden\":true,\"pinned\":true,\"pinOrder\":1") + ","
                + item("scheduled", "2026-10-20T00:00:00Z", "") + ","
                + "{\"id\":\"no-date\",\"title\":\"x\"}"
                + "]}";
        NewsFeed feed = NewsFeed.parse(json.getBytes(StandardCharsets.UTF_8));
        List<String> ids = new ArrayList<>();
        for (NewsFeed.NewsItem i : feed.visible(NOW)) ids.add(i.id);
        assertEquals("[shown]", ids.toString());
        assertFalse(feed.get("hidden-pinned").isVisible(NOW));
        // Studio publishes them all: a feed item with one of these ids stays off the home screen.
        for (String id : new String[]{"shown", "hidden", "hidden-pinned", "scheduled", "no-date"}) {
            assertTrue(id, feed.publishes(id));
        }
        assertFalse(feed.publishes("discord-1"));
        assertNull(feed.get("hidden").pinOrder);
    }

    @Test
    public void idsOrderByCodePointLikeTheDesktop() throws Exception {
        // U+FF5E sorts before U+1F600 by code point (and UTF-8 bytes), after it by UTF-16 units.
        String a = "a～";
        String b = "a😀";
        assertTrue(a.compareTo(b) > 0);
        assertTrue(ContentText.compare(a, b) < 0);
        assertTrue(ContentText.compare("a", "ab") < 0);
        assertEquals(0, ContentText.compare("same", "same"));
        assertEquals("[" + a + ", " + b + "]", visibleIds(
                item(b, "2026-10-15T00:00:00Z", ""), item(a, "2026-10-15T00:00:00Z", "")).toString());
    }

    @Test
    public void textRulesMatchRust() {
        assertEquals("başlık", ContentText.trim("   başlık 　\n"));
        assertTrue(ContentText.isBlank("   "));
        assertFalse(ContentText.longerThan("😀😀", 2));
        assertTrue(ContentText.longerThan("😀😀😀", 2));

        assertTrue(ContentText.isSafeLink("https://projects.gg/kis"));
        assertTrue(ContentText.isSafeLink("projects://pack/projects"));
        assertTrue(ContentText.isHttpsLink("https://discord.com/channels/1/2/3"));
        assertFalse(ContentText.isHttpsLink("projects://pack/projects"));
        for (String bad : new String[]{"http://projects.gg", "javascript:alert(1)", "mailto:a@b.c", " https://projects.gg",
                "https://user:pass@projects.gg/", "https:///nohost", "projects://", "projects://a b", "", null,
                "https://projects.gg/\u0007"}) {
            assertFalse(String.valueOf(bad), ContentText.isSafeLink(bad));
        }
    }
}
