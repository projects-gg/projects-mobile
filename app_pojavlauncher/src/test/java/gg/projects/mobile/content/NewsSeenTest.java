package gg.projects.mobile.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Which news is new: the small dot on the card and the list (the desktop's stores/news-seen.test.ts). */
public class NewsSeenTest {
    private static final long DAY = 86_400_000L;
    /** 2026-10-17T12:00:00Z. */
    private static final long NOW = 1_792_238_400_000L;

    private static NewsFeed.NewsItem news(String id, long publishedAt, boolean pinned) {
        NewsFeed.NewsItem item = new NewsFeed.NewsItem();
        item.id = id;
        item.title = "Haber " + id;
        item.summary = "";
        item.body = "";
        item.publishedAt = Rfc3339Text.of(publishedAt);
        item.publishedAtMillis = publishedAt;
        item.pinned = pinned;
        return item;
    }

    /** RFC 3339 text for a moment, only so the item looks like parsed content. */
    private static final class Rfc3339Text {
        static String of(long millis) {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return format.format(new java.util.Date(millis));
        }
    }

    @Test
    public void newWhenPublishedAfterTheWatermarkRecentlyAndNotOpened() {
        NewsSeen seen = NewsSeen.EMPTY.start(NOW - DAY);
        NewsFeed.NewsItem fresh = news("discord-1", NOW - 2 * 3_600_000L, false);
        NewsFeed.NewsItem before = news("discord-2", NOW - 2 * DAY, false);
        assertTrue(seen.isNew(fresh, NOW));
        assertFalse("published before the watermark", seen.isNew(before, NOW));
        NewsSeen opened = seen.markRead("discord-1", NOW);
        assertFalse(opened.isNew(fresh, NOW));
        assertTrue(opened.isRead("discord-1"));
        // At the watermark itself: seen.
        assertFalse(seen.isNew(news("x", NOW - DAY, false), NOW));
    }

    @Test
    public void neverNewAfterFourteenDaysEvenPastAnOldWatermark() {
        NewsSeen seen = NewsSeen.EMPTY.start(NOW - 30 * DAY);
        assertTrue(seen.isNew(news("a", NOW - 13 * DAY, false), NOW));
        assertFalse(seen.isNew(news("b", NOW - 14 * DAY, false), NOW));
        assertFalse(seen.isNew(news("c", NOW - 20 * DAY, false), NOW));
    }

    @Test
    public void aPinnedArticleIsLikeAnyOther() {
        NewsSeen seen = NewsSeen.EMPTY.start(NOW - DAY);
        assertFalse(seen.isNew(news("old-pinned", NOW - 3 * DAY, true), NOW));
        assertTrue(seen.isNew(news("new-pinned", NOW - 3_600_000L, true), NOW));
    }

    @Test
    public void nothingIsNewBeforeTheFirstStartSetAWatermark() {
        assertNull(NewsSeen.EMPTY.watermark);
        assertFalse(NewsSeen.EMPTY.isNew(news("a", NOW, false), NOW));
    }

    @Test
    public void theFirstStartLightsUpNothingAndHappensOnce() {
        // A fresh install: everything published so far counts as seen.
        NewsSeen seen = NewsSeen.EMPTY.start(NOW);
        assertEquals(Long.valueOf(NOW), seen.watermark);
        assertFalse(seen.isNew(news("a", NOW - 3_600_000L, false), NOW));
        assertTrue(seen.isNew(news("b", NOW + 60_000L, false), NOW + 120_000L));
        assertSame(seen, seen.start(NOW + DAY));
    }

    @Test
    public void theWatermarkMovesForwardOnly() {
        NewsSeen seen = NewsSeen.EMPTY.start(NOW);
        NewsSeen later = seen.advance(NOW + DAY, NOW + DAY);
        assertEquals(Long.valueOf(NOW + DAY), later.watermark);
        assertSame(later, later.advance(NOW, NOW + DAY));
        assertSame(later, later.advance(NOW + DAY, NOW + DAY));
    }

    @Test
    public void openedArticlesAreForgottenAfterThirtyDays() {
        NewsSeen seen = NewsSeen.EMPTY.start(NOW).markRead("old", NOW).markRead("recent", NOW + 20 * DAY);
        assertSame(seen, seen.markRead("old", NOW + DAY));
        NewsSeen pruned = seen.markRead("newest", NOW + 31 * DAY);
        assertEquals(Arrays.asList("recent", "newest"), new ArrayList<>(pruned.read.keySet()));
        assertFalse(pruned.isRead("old"));
        NewsSeen moved = seen.advance(NOW + 40 * DAY, NOW + 51 * DAY);
        assertTrue(moved.read.isEmpty());
    }

    @Test
    public void findsTheNewestArticleAVisitShowed() {
        List<NewsFeed.NewsItem> items = Arrays.asList(news("pinned-old", NOW - 9 * DAY, true),
                news("newest", NOW - 3_600_000L, false), news("older", NOW - DAY, false));
        assertEquals(NOW - 3_600_000L, NewsSeen.newestPublished(items));
        assertEquals(0, NewsSeen.newestPublished(Collections.<NewsFeed.NewsItem>emptyList()));
    }

    @Test
    public void keepsTheWatermarkAndOpenedArticlesAcrossARestart() {
        NewsSeen seen = NewsSeen.EMPTY.start(NOW).markRead("discord-1", NOW + 1).markRead("kış-sezonu", NOW + 2);
        NewsSeen back = NewsSeen.fromJson(seen.toJson());
        assertEquals(seen.watermark, back.watermark);
        assertEquals(seen.read, back.read);
        assertEquals(Arrays.asList("discord-1", "kış-sezonu"), new ArrayList<>(back.read.keySet()));
        assertNull(NewsSeen.fromJson(NewsSeen.EMPTY.toJson()).watermark);
        assertSame(NewsSeen.EMPTY, NewsSeen.fromJson("{broken"));
        assertSame(NewsSeen.EMPTY, NewsSeen.fromJson(null));
        assertSame(NewsSeen.EMPTY, NewsSeen.fromJson("{\"watermark\":\"soon\"}"));
    }
}
