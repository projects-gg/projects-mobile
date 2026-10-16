package gg.projects.mobile.content;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** {@code news/news.<sha256>.json}. */
@Keep
public final class NewsFeed {
    public int schema = 1;
    public List<NewsItem> items;

    /** Ids of every item Studio publishes, shown or not ("Studio wins" over the bot's feed). */
    transient Set<String> allIds = new HashSet<>();

    @Keep
    public static final class NewsItem {
        /** Stable id (CTA targets, deep links). */
        public String id;
        public String title;
        public String summary;
        /** Markdown, rendered without HTML; links open in the browser. */
        public String body;
        public MediaRef cover;
        /** {@code update | event | announcement | patchNotes | community}; unknown reads as announcement. */
        public String category;
        public List<String> tags;
        public String publishedAt;
        public String expiresAt;
        public boolean pinned;
        /**
         * Position among the pinned items (1 first); meaningless on items that are not pinned.
         * Pinned items without one follow those with one.
         */
        public Long pinOrder;
        public boolean featured;
        /**
         * Never shown. Studio publishes hidden items only to take an item of the Discord bot's feed
         * off the home screen (they also expire when they are published).
         */
        public boolean hidden;
        public Cta cta;
        public String author;

        transient long publishedAtMillis;
        transient Long expiresAtMillis;
        /** From the Discord bot's feed: its cover comes from the feed mirrors. */
        transient boolean fromFeed;
        /** The Discord message a feed item summarizes ({@code https://} only), for "Discord'da aç". */
        transient String sourceUrl;

        public long publishedAtMillis() {
            return publishedAtMillis;
        }

        public boolean fromFeed() {
            return fromFeed;
        }

        public String sourceUrl() {
            return sourceUrl;
        }

        /** Not hidden, published by {@code now} and not expired yet. */
        public boolean isVisible(long now) {
            return !hidden && publishedAtMillis <= now && (expiresAtMillis == null || now < expiresAtMillis);
        }

        public String categoryOrDefault() {
            if (category == null) return "announcement";
            switch (category) {
                case "update": case "event": case "announcement": case "patchNotes": case "community":
                    return category;
                default:
                    return "announcement";
            }
        }

        public boolean isPatchNotes() {
            return "patchNotes".equals(category);
        }
    }

    /** Call to action: opens {@code url} in the browser or the article {@code newsId}. */
    @Keep
    public static final class Cta {
        public String label;
        public String url;
        public String newsId;
    }

    /**
     * Display order (pl-content's {@code compare_news}): pinned items first, those with a
     * {@code pinOrder} by it (1 first) before those without, then newest {@code publishedAt}, then
     * id.
     */
    public static final Comparator<NewsItem> ORDER = new Comparator<NewsItem>() {
        @Override
        public int compare(NewsItem a, NewsItem b) {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            if (a.pinned) {
                boolean aOrdered = a.pinOrder != null;
                boolean bOrdered = b.pinOrder != null;
                if (aOrdered != bOrdered) return aOrdered ? -1 : 1;
                if (aOrdered) {
                    int byOrder = Long.compare(a.pinOrder, b.pinOrder);
                    if (byOrder != 0) return byOrder;
                }
            }
            int byDate = Long.compare(b.publishedAtMillis, a.publishedAtMillis);
            if (byDate != 0) return byDate;
            return ContentText.compare(a.id, b.id);
        }
    };

    /**
     * Parses a feed. Items without an id, a title or a valid {@code publishedAt} are dropped
     * instead of failing the whole feed; their ids still count as published by Studio.
     */
    public static NewsFeed parse(byte[] bytes) throws ContentException {
        NewsFeed feed = ContentJson.parse(bytes, NewsFeed.class, "news feed");
        ContentJson.checkSchema("news feed", feed.schema, ContentConfig.NEWS_FEED_SCHEMA);
        List<NewsItem> valid = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (feed.items != null) {
            for (NewsItem item : feed.items) {
                if (item == null || item.id == null || item.id.isEmpty()) continue;
                ids.add(item.id);
                if (item.title == null) continue;
                Long published = Rfc3339.parse(item.publishedAt);
                if (published == null) continue;
                item.publishedAtMillis = published;
                item.expiresAtMillis = item.expiresAt == null ? null : Rfc3339.parse(item.expiresAt);
                if (item.expiresAt != null && item.expiresAtMillis == null) continue;
                if (item.summary == null) item.summary = "";
                if (item.body == null) item.body = "";
                if (item.tags == null) item.tags = new ArrayList<>();
                if (item.cover != null && !item.cover.isValid()) item.cover = null;
                // pinOrder is a u32 on the desktop; anything else means "no position".
                if (item.pinOrder != null && (item.pinOrder < 0 || item.pinOrder > 0xFFFFFFFFL)) item.pinOrder = null;
                item.fromFeed = false;
                item.sourceUrl = null;
                valid.add(item);
            }
        }
        feed.items = valid;
        feed.allIds = ids;
        return feed;
    }

    /** Items visible at {@code now}, in display order. */
    public List<NewsItem> visible(long now) {
        List<NewsItem> out = new ArrayList<>();
        for (NewsItem item : items) if (item.isVisible(now)) out.add(item);
        Collections.sort(out, ORDER);
        return out;
    }

    public NewsItem get(String id) {
        for (NewsItem item : items) if (item.id.equals(id)) return item;
        return null;
    }

    /** True when Studio publishes an item with this id, visible or not. */
    public boolean publishes(String id) {
        return allIds.contains(id);
    }

    /** Every cover (and poster) the feed references. */
    public List<MediaRef> mediaRefs() {
        List<MediaRef> refs = new ArrayList<>();
        for (NewsItem item : items) {
            MediaRef current = item.cover;
            for (int depth = 0; depth < 4 && current != null; depth++) {
                refs.add(current);
                current = current.poster;
            }
        }
        return refs;
    }
}
