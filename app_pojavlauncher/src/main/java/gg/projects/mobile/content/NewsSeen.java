package gg.projects.mobile.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which news the player has already seen, for the small gold dot on new ones (the desktop's
 * {@code stores/news-seen.ts}): a watermark (news published at or before it are not new) and the
 * articles opened since. Kept on the phone. Immutable: every change returns a new value.
 */
public final class NewsSeen {
    private static final long DAY = 86_400_000L;
    /** News older than this is never new. */
    public static final long NEW_FOR_MS = 14 * DAY;
    /** Opened articles are remembered this long: longer than an article can be new. */
    public static final long READ_KEPT_MS = 30 * DAY;

    /** News published at or before this moment count as seen; null until the first start. */
    public final Long watermark;
    /** Opened articles: news id → when. */
    public final Map<String, Long> read;

    public NewsSeen(Long watermark, Map<String, Long> read) {
        this.watermark = watermark;
        this.read = Collections.unmodifiableMap(new LinkedHashMap<>(read));
    }

    public static final NewsSeen EMPTY = new NewsSeen(null, Collections.<String, Long>emptyMap());

    /**
     * New: published after the watermark, less than 14 days ago, and not opened. Pinned news
     * follows the same rule: an old pinned article is not new.
     */
    public boolean isNew(NewsFeed.NewsItem item, long now) {
        // M17: Minecraft's own updates never carry the dot (content-format.md "Minecraft news").
        if (item.isMinecraft()) return false;
        return isNew(item.id, item.publishedAtMillis(), now);
    }

    public boolean isNew(String id, long publishedAt, long now) {
        if (watermark == null || read.containsKey(id)) return false;
        return publishedAt > watermark && publishedAt > now - NEW_FOR_MS;
    }

    public boolean isRead(String id) {
        return read.containsKey(id);
    }

    /** The newest publishing moment in {@code items} (0 when empty). */
    public static long newestPublished(List<NewsFeed.NewsItem> items) {
        long newest = 0;
        for (NewsFeed.NewsItem item : items) newest = Math.max(newest, item.publishedAtMillis());
        return newest;
    }

    /** The article was opened. */
    public NewsSeen markRead(String id, long now) {
        if (read.containsKey(id)) return this;
        Map<String, Long> next = withoutOld(read, now);
        next.put(id, now);
        return new NewsSeen(watermark, next);
    }

    /** The news list showed everything published up to {@code to}: that counts as seen now. */
    public NewsSeen advance(long to, long now) {
        if (watermark != null && to <= watermark) return this;
        return new NewsSeen(to, withoutOld(read, now));
    }

    /** First start: news published so far is not new (a fresh install does not light up old news). */
    public NewsSeen start(long now) {
        return watermark == null ? new NewsSeen(now, read) : this;
    }

    private static Map<String, Long> withoutOld(Map<String, Long> read, long now) {
        Map<String, Long> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : read.entrySet()) {
            if (entry.getValue() > now - READ_KEPT_MS) kept.put(entry.getKey(), entry.getValue());
        }
        return kept;
    }

    // ---- storage ----

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        if (watermark != null) root.addProperty("watermark", watermark);
        JsonObject opened = new JsonObject();
        for (Map.Entry<String, Long> entry : read.entrySet()) opened.addProperty(entry.getKey(), entry.getValue());
        root.add("read", opened);
        return root.toString();
    }

    /** Read back from {@link #toJson}; anything unreadable starts over as {@link #EMPTY}. */
    public static NewsSeen fromJson(String json) {
        if (json == null || json.isEmpty()) return EMPTY;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement mark = root.get("watermark");
            Map<String, Long> read = new LinkedHashMap<>();
            JsonElement opened = root.get("read");
            if (opened != null && opened.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : opened.getAsJsonObject().entrySet()) {
                    read.put(entry.getKey(), entry.getValue().getAsLong());
                }
            }
            return new NewsSeen(mark == null || mark.isJsonNull() ? null : mark.getAsLong(), read);
        } catch (RuntimeException e) {
            return EMPTY;
        }
    }
}
