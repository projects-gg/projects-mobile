package gg.projects.mobile.content;

import static gg.projects.mobile.content.FeedFixtures.at;
import static gg.projects.mobile.content.FeedFixtures.utf8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * M17: Minecraft's own updates in the Discord feed (content-format.md "Minecraft news"; MCN1's
 * steps for Android): the array and entry rules, what the home screen shows and in which order,
 * Studio's override, the language, and that they never pass for Projects news.
 */
public class MinecraftNewsTest {
    private static final String PUBLISHED = "2026-09-15T11:23:02Z";
    private static final String EXPIRES = "2026-10-30T11:23:02Z";

    /** A valid entry for {@code version}; {@code extra} goes inside the object (start it with a comma). */
    private static String entry(String version, String published, String expires, String extra) {
        String id = "minecraft-" + version;
        return "{\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"kind\":\"release\","
                + "\"title\":\"Minecraft " + version + " çıktı\",\"summary\":\"Kısa özet.\","
                + "\"body\":\"## Öne çıkanlar\\n\\n- Dappled Forest\","
                + "\"publishedAt\":\"" + published + "\",\"expiresAt\":\"" + expires + "\","
                + "\"source\":{\"kind\":\"minecraft\",\"url\":\"https://www.minecraft.net/article/minecraft-java-edition-"
                + version.replace('.', '-') + "\"}" + extra + "}";
    }

    private static String entry(String version) {
        return entry(version, PUBLISHED, EXPIRES, "");
    }

    private static BotFeed feed(String minecraftJson) throws ContentException {
        return BotFeed.parse(utf8("{\"schema\":1,\"sequence\":1792310400,\"generatedAt\":\"2026-10-18T10:00:00Z\","
                + "\"items\":[],\"events\":[]" + (minecraftJson == null ? "" : ",\"minecraft\":" + minecraftJson) + "}"));
    }

    private static String array(String... entries) {
        return "[" + FeedFixtures.join(FeedFixtures.list(entries)) + "]";
    }

    private static List<String> ids(List<NewsFeed.NewsItem> items) {
        List<String> ids = new ArrayList<>();
        for (NewsFeed.NewsItem item : items) ids.add(item.id);
        return ids;
    }

    @Test
    public void aValidEntryIsReadAsMinecraftNewsNotProjectsNews() throws Exception {
        byte[] coverBytes = utf8("webp");
        String translation = ",\"translations\":{\"en\":{\"title\":\"Minecraft 26.3 is out\",\"summary\":\"Short summary.\"}}";
        BotFeed feed = feed(array(entry("26.3", PUBLISHED, EXPIRES, FeedFixtures.cover(coverBytes, "") + translation)));
        assertTrue(feed.problems.toString(), feed.problems.isEmpty());
        assertTrue("never in items", feed.items.isEmpty());
        NewsFeed.NewsItem entry = feed.minecraft.get(0);
        assertEquals("minecraft-26.3", entry.id);
        assertTrue(entry.isMinecraft());
        assertEquals("26.3", entry.minecraftVersion());
        assertTrue("covers come from the feed mirrors", entry.fromFeed());
        assertEquals("https://www.minecraft.net/article/minecraft-java-edition-26-3", entry.sourceUrl());
        assertEquals(at(PUBLISHED), entry.publishedAtMillis());
        assertFalse(entry.isVisible(at(EXPIRES)));
        assertTrue(entry.isVisible(at(EXPIRES) - 1));
        assertFalse("not patch notes, whatever it says", entry.isPatchNotes());
        assertNotNull(entry.cover);
        assertTrue("its cover is a feed cover", feed.mediaRefs().contains(entry.cover));
        // English: the translated title and summary; the body falls back to the base text.
        NewsFeed.NewsItem english = entry.inEnglish();
        assertEquals("Minecraft 26.3 is out", english.title);
        assertEquals("Short summary.", english.summary);
        assertEquals(entry.body, english.body);
        assertTrue(english.isMinecraft());
        assertEquals("Minecraft 26.3 çıktı", entry.title);
    }

    @Test
    public void theArrayIsOptionalAndIgnoredWholeWhenBroken() throws Exception {
        assertTrue(feed(null).minecraft.isEmpty());
        assertTrue(feed(null).problems.isEmpty());
        BotFeed notArray = feed("{\"id\":\"minecraft-26.3\"}");
        assertTrue(notArray.minecraft.isEmpty());
        assertEquals(1, notArray.problems.size());
        String[] eleven = new String[11];
        for (int i = 0; i < eleven.length; i++) eleven[i] = entry("26." + i);
        BotFeed tooMany = feed(array(eleven));
        assertTrue("more than 10: the whole array goes", tooMany.minecraft.isEmpty());
        String[] ten = new String[10];
        for (int i = 0; i < ten.length; i++) ten[i] = entry("26." + i);
        assertEquals(10, feed(array(ten)).minecraft.size());
    }

    @Test
    public void anEntryThatBreaksARuleDropsOnlyItself() throws Exception {
        String good = entry("26.3");
        String[] bad = {
                // The id must be minecraft-<version>.
                entry("26.2").replace("\"id\":\"minecraft-26.2\"", "\"id\":\"mc-26.2\""),
                // A version with a space.
                entry("26.1").replace("\"version\":\"26.1\"", "\"version\":\"26 1\""),
                // Not on minecraft.net, not https, credentials.
                entry("26.0").replace("https://www.minecraft.net/", "https://example.com/"),
                entry("25.9").replace("https://www.minecraft.net/", "http://www.minecraft.net/"),
                entry("25.8").replace("https://www.minecraft.net/", "https://user:pw@www.minecraft.net/"),
                // No source at all.
                entry("25.7").replaceAll(",\"source\":\\{[^}]*\\}", ""),
                // Expires before it is published, or more than 90 days after.
                entry("25.6", PUBLISHED, "2026-09-15T11:23:02Z", ""),
                entry("25.5", PUBLISHED, "2026-12-15T11:23:03Z", ""),
                // A blank title, a missing date.
                entry("25.4").replace("\"title\":\"Minecraft 25.4 çıktı\"", "\"title\":\"  \""),
                entry("25.3").replace(",\"expiresAt\":\"" + EXPIRES + "\"", ""),
                "\"not an object\"",
        };
        // Two feeds: an array may hold at most 10 entries.
        int problems = 0;
        for (int start = 0; start < bad.length; start += 6) {
            List<String> all = new ArrayList<>();
            all.add(good);
            all.addAll(java.util.Arrays.asList(bad).subList(start, Math.min(bad.length, start + 6)));
            BotFeed feed = feed("[" + FeedFixtures.join(all) + "]");
            assertEquals(Collections.singletonList("minecraft-26.3"), ids(feed.minecraft));
            problems += feed.problems.size();
        }
        assertEquals("one problem per dropped entry", bad.length, problems);
        // A repeated id: the first wins.
        BotFeed twice = feed(array(entry("26.3"), entry("26.3").replace("çıktı", "yine")));
        assertEquals(1, twice.minecraft.size());
        assertEquals("Minecraft 26.3 çıktı", twice.minecraft.get(0).title);
        // Exactly 90 days is fine; snapshot ids are versions too.
        assertEquals(1, feed(array(entry("26.4-snapshot-4", PUBLISHED, "2026-12-14T11:23:02Z", ""))).minecraft.size());
    }

    @Test
    public void aBadCoverOrTranslationDropsOnlyThatPart() throws Exception {
        String badCover = ",\"cover\":{\"path\":\"../x.webp\",\"sha256\":\"00\",\"size\":5,\"kind\":\"image\"}";
        String badEnglish = ",\"translations\":{\"en\":{\"title\":\"\"}}";
        BotFeed feed = feed(array(entry("26.3", PUBLISHED, EXPIRES, badCover + badEnglish),
                entry("26.2", PUBLISHED, EXPIRES, ",\"translations\":\"en\"")));
        assertEquals(2, feed.minecraft.size());
        NewsFeed.NewsItem entry = feed.minecraft.get(0);
        assertNull(entry.cover);
        assertSame("no English text: the base text everywhere", entry, entry.inEnglish());
        assertSame(feed.minecraft.get(1), feed.minecraft.get(1).inEnglish());
        assertEquals(3, feed.problems.size());
    }

    @Test
    public void whatTheFeedSaysAboutPinsAndCategoriesIsIgnored() throws Exception {
        BotFeed feed = feed(array(entry("26.3", PUBLISHED, EXPIRES,
                ",\"category\":\"patchNotes\",\"pinned\":true,\"featured\":true,\"author\":\"X\",\"cta\":{\"label\":\"a\",\"url\":\"https://a.b\"}")));
        NewsFeed.NewsItem entry = feed.minecraft.get(0);
        assertFalse(entry.pinned);
        assertFalse(entry.isPatchNotes());
        assertNull(entry.category);
        assertNull(entry.cta);
        assertNull(entry.author);
    }

    @Test
    public void theHomeShowsVisibleEntriesNewestFirstAndStudioHidesThem() throws Exception {
        BotFeed feed = feed(array(
                entry("26.1", "2026-09-01T00:00:00Z", "2026-11-01T00:00:00Z", ""),
                entry("26.3", "2026-09-15T00:00:00Z", "2026-11-01T00:00:00Z", ""),
                entry("26.2", "2026-09-15T00:00:00Z", "2026-11-01T00:00:00Z", ""),
                entry("26.4", "2026-10-25T00:00:00Z", "2026-11-01T00:00:00Z", ""),
                entry("25.9", "2026-09-01T00:00:00Z", "2026-10-10T00:00:00Z", "")));
        long now = at("2026-10-19T12:00:00Z");
        // Not published yet (26.4) and expired (25.9) are left out; same day: the smaller id first.
        assertEquals(java.util.Arrays.asList("minecraft-26.2", "minecraft-26.3", "minecraft-26.1"),
                ids(HomeContent.visibleMinecraft(null, feed, 60, now)));
        // The feed's maxAgeDays applies to them too.
        assertEquals(java.util.Arrays.asList("minecraft-26.2", "minecraft-26.3"),
                ids(HomeContent.visibleMinecraft(null, feed, 40, now)));
        // Studio hides one with its own item of the same id, even a hidden, expired one.
        NewsFeed studio = NewsFeed.parse(utf8("{\"schema\":1,\"items\":[{\"id\":\"minecraft-26.3\",\"title\":\"x\","
                + "\"category\":\"update\",\"publishedAt\":\"2026-09-15T00:00:00Z\",\"expiresAt\":\"2026-09-15T00:00:00Z\",\"hidden\":true}]}"));
        assertEquals(java.util.Arrays.asList("minecraft-26.2", "minecraft-26.1"),
                ids(HomeContent.visibleMinecraft(studio, feed, 60, now)));
        assertTrue(HomeContent.visibleMinecraft(studio, null, 60, now).isEmpty());
    }

    @Test
    public void theyNeverJoinTheProjectsNewsOrItsMarks() throws Exception {
        BotFeed feed = feed(array(entry("26.3", "2026-10-18T00:00:00Z", "2026-11-01T00:00:00Z", "")));
        FeedSnapshot snapshot = new FeedSnapshot(feed, null, null, 0, true, null);
        long now = at("2026-10-19T12:00:00Z");
        HomeContent home = HomeContent.of(null, null, snapshot, now);
        assertTrue("not in the news column", home.news.isEmpty());
        assertNull("never the patch notes tile", home.patchNotes);
        assertEquals(1, home.minecraft.size());
        // Published after the watermark and less than 14 days ago, yet never "new".
        NewsSeen seen = new NewsSeen(at("2026-10-01T00:00:00Z"), Collections.<String, Long>emptyMap());
        assertFalse(seen.isNew(home.minecraft.get(0), now));
    }
}
