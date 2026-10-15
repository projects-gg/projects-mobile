package gg.projects.mobile.content;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** {@code news/news.<sha256>.json}. */
@Keep
public final class NewsFeed {
    public int schema = 1;
    public List<NewsItem> items;

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
        public boolean featured;
        public Cta cta;
        public String author;

        transient long publishedAtMillis;
        transient Long expiresAtMillis;

        public long publishedAtMillis() {
            return publishedAtMillis;
        }

        public boolean isVisible(long now) {
            return publishedAtMillis <= now && (expiresAtMillis == null || now < expiresAtMillis);
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
    }

    /** Call to action: opens {@code url} in the browser or the article {@code newsId}. */
    @Keep
    public static final class Cta {
        public String label;
        public String url;
        public String newsId;
    }

    /** Display order: pinned first, then newest first, then by id. */
    public static final Comparator<NewsItem> ORDER = new Comparator<NewsItem>() {
        @Override
        public int compare(NewsItem a, NewsItem b) {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            int byDate = Long.compare(b.publishedAtMillis, a.publishedAtMillis);
            if (byDate != 0) return byDate;
            return a.id.compareTo(b.id);
        }
    };

    /**
     * Parses a feed. Items without an id, a title or a valid {@code publishedAt} are dropped
     * instead of failing the whole feed.
     */
    public static NewsFeed parse(byte[] bytes) throws ContentException {
        NewsFeed feed = ContentJson.parse(bytes, NewsFeed.class, "news feed");
        ContentJson.checkSchema("news feed", feed.schema, ContentConfig.NEWS_FEED_SCHEMA);
        List<NewsItem> valid = new ArrayList<>();
        if (feed.items != null) {
            for (NewsItem item : feed.items) {
                if (item == null || item.id == null || item.id.isEmpty() || item.title == null) continue;
                Long published = Rfc3339.parse(item.publishedAt);
                if (published == null) continue;
                item.publishedAtMillis = published;
                item.expiresAtMillis = item.expiresAt == null ? null : Rfc3339.parse(item.expiresAt);
                if (item.expiresAt != null && item.expiresAtMillis == null) continue;
                if (item.summary == null) item.summary = "";
                if (item.body == null) item.body = "";
                if (item.tags == null) item.tags = new ArrayList<>();
                if (item.cover != null && !item.cover.isValid()) item.cover = null;
                valid.add(item);
            }
        }
        feed.items = valid;
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
