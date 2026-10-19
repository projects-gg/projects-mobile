package gg.projects.mobile.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code feed.json}: the Discord bot's full snapshot of the news it publishes (pl-content's
 * {@code BotFeed}). A feed key can only add news: items are never pinned, links open only
 * {@code https://} (and {@code projects://}), covers are small images verified by size and SHA-256,
 * and one broken item never hides the others. The size, {@code schema}, {@code sequence},
 * {@code generatedAt} and the item and event counts are whole-feed rules; a broken item or event is
 * dropped or repaired on its own and reported in {@link #problems} (logs only).
 */
public final class BotFeed {
    public final int schema;
    /** Strictly increasing; clients refuse a lower one (equal only with identical bytes). */
    public final long sequence;
    public final long generatedAt;
    /** News items ({@code fromFeed}, never pinned), in feed order. */
    public final List<NewsFeed.NewsItem> items;
    public final List<Event> events;
    /** The status page's active problem, or null; check {@link FeedStatus#isActive} before showing it. */
    public final FeedStatus status;
    /**
     * M17: Minecraft's own updates ({@code minecraft[]}, content-format.md "Minecraft news"), in feed
     * order: entries the home screen shows only where Projects news leaves room.
     */
    public final List<NewsFeed.NewsItem> minecraft;
    /** What was dropped or repaired, for logs. */
    public final List<String> problems;

    private BotFeed(int schema, long sequence, long generatedAt, List<NewsFeed.NewsItem> items, List<Event> events,
                    FeedStatus status, List<NewsFeed.NewsItem> minecraft, List<String> problems) {
        this.schema = schema;
        this.sequence = sequence;
        this.generatedAt = generatedAt;
        this.items = Collections.unmodifiableList(items);
        this.events = Collections.unmodifiableList(events);
        this.status = status;
        this.minecraft = Collections.unmodifiableList(minecraft);
        this.problems = Collections.unmodifiableList(problems);
    }

    /** An event of the feed: the shape of the config's events with an optional end. */
    public static final class Event {
        public final String id;
        public final String title;
        public final long startsAt;
        /** Missing: live for three hours after the start, shown without time left. */
        public final Long endsAt;
        /** A safe link, or null. */
        public final String url;
        /** A feed item the event came from, or null. */
        public final String newsId;
        public final int priority;
        /** M17: the event takes place on the server: while it is live the card offers "Katıl". */
        public final boolean join;

        Event(String id, String title, long startsAt, Long endsAt, String url, String newsId, int priority, boolean join) {
            this.id = id;
            this.title = title;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
            this.url = url;
            this.newsId = newsId;
            this.priority = priority;
            this.join = join;
        }

        /** {@code endsAt}, or {@code startsAt} + three hours. */
        public long end() {
            return endsAt != null ? endsAt : startsAt + ContentConfig.OPEN_EVENT_MS;
        }
    }

    public NewsFeed.NewsItem item(String id) {
        for (NewsFeed.NewsItem item : items) if (item.id.equals(id)) return item;
        return null;
    }

    /** Every cover the feed references (Minecraft entries' too: they are feed covers). */
    public List<MediaRef> mediaRefs() {
        List<MediaRef> refs = new ArrayList<>();
        for (NewsFeed.NewsItem item : items) if (item.cover != null) refs.add(item.cover);
        for (NewsFeed.NewsItem entry : minecraft) if (entry.cover != null) refs.add(entry.cover);
        return refs;
    }

    /** Reads {@code feed.json} bytes whose signature already verified. */
    public static BotFeed parse(byte[] bytes) throws ContentException {
        if (bytes.length > ContentConfig.FEED_MAX_BYTES) {
            throw invalid("feed.json is larger than " + ContentConfig.FEED_MAX_BYTES + " bytes");
        }
        String text = ContentJson.utf8(bytes, "feed.json");
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new ContentException(ContentException.Kind.INVALID, "feed.json is not valid JSON: " + e.getMessage(), e);
        }
        int schema;
        long sequence;
        long generatedAt;
        JsonArray rawItems;
        JsonArray rawEvents;
        JsonElement rawStatus;
        JsonElement rawMinecraft;
        try {
            JsonObject object = JsonFields.object(root, "feed.json");
            schema = (int) JsonFields.defaultInteger(object, "schema", 1, 0, 0xFFFFFFFFL);
            sequence = JsonFields.requiredInteger(object, "sequence", 0, Long.MAX_VALUE);
            generatedAt = JsonFields.requiredTimestamp(object, "generatedAt");
            rawItems = JsonFields.defaultArray(object, "items");
            rawEvents = JsonFields.defaultArray(object, "events");
            rawStatus = object.get("status");
            rawMinecraft = object.get("minecraft");
        } catch (JsonFields.Unreadable e) {
            throw invalid("feed.json has an unexpected shape: " + e.getMessage());
        }
        ContentJson.checkSchema("news feed", schema, ContentConfig.FEED_SCHEMA);
        if (rawItems.size() > ContentConfig.FEED_MAX_ITEMS) {
            throw invalid("feed.json lists " + rawItems.size() + " items; at most " + ContentConfig.FEED_MAX_ITEMS + " are allowed");
        }
        if (rawEvents.size() > ContentConfig.FEED_MAX_EVENTS) {
            throw invalid("feed.json lists " + rawEvents.size() + " events; at most " + ContentConfig.FEED_MAX_EVENTS + " are allowed");
        }
        List<String> problems = new ArrayList<>();
        List<NewsFeed.NewsItem> items = readItems(rawItems, problems);
        List<Event> events = readEvents(rawEvents, items, problems);
        // A broken status notice drops only itself (content-format.md "Status notice").
        FeedStatus status = FeedStatus.read(rawStatus, problems);
        // M17: so does a broken Minecraft entry, or the whole array when it is not one (or too long).
        List<NewsFeed.NewsItem> minecraft = readMinecraft(rawMinecraft, problems);
        return new BotFeed(schema, sequence, generatedAt, items, events, status, minecraft, problems);
    }

    /**
     * M17: {@code minecraft[]} (content-format.md "Minecraft news"). Missing means none; not an array,
     * or more than {@link ContentConfig#FEED_MAX_MINECRAFT} entries, ignores it as a whole. An entry is
     * dropped when it does not parse, its id is not {@code minecraft-<version>} (or repeats), the
     * version, title, summary, body, dates or the minecraft.net link break their rules; a bad cover or
     * a bad English translation drops only that part. Entries carry no category, pin, button or
     * author: whatever the feed says there is ignored.
     */
    static List<NewsFeed.NewsItem> readMinecraft(JsonElement raw, List<String> problems) {
        List<NewsFeed.NewsItem> entries = new ArrayList<>();
        if (raw == null) return entries;
        if (!raw.isJsonArray()) {
            problems.add("minecraft ignored: not an array");
            return entries;
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() > ContentConfig.FEED_MAX_MINECRAFT) {
            problems.add("minecraft ignored: " + array.size() + " entries; at most " + ContentConfig.FEED_MAX_MINECRAFT);
            return entries;
        }
        Set<String> seen = new HashSet<>();
        for (JsonElement value : array) {
            NewsFeed.NewsItem entry;
            try {
                entry = readMinecraftEntry(value, problems);
            } catch (JsonFields.Unreadable e) {
                problems.add("minecraft " + rawId(value) + " dropped: unreadable (" + e.getMessage() + ")");
                continue;
            }
            if (entry == null) continue;
            if (!seen.add(entry.id)) {
                problems.add("minecraft " + entry.id + " dropped: duplicate id");
                continue;
            }
            entries.add(entry);
        }
        return entries;
    }

    /** One Minecraft entry, or null (with the reason in {@code problems}) when it breaks an entry rule. */
    private static NewsFeed.NewsItem readMinecraftEntry(JsonElement value, List<String> problems) throws JsonFields.Unreadable {
        JsonObject o = JsonFields.object(value, "minecraft entry");
        NewsFeed.NewsItem entry = new NewsFeed.NewsItem();
        entry.id = JsonFields.requiredString(o, "id");
        String version = JsonFields.requiredString(o, "version");
        entry.title = JsonFields.requiredString(o, "title");
        entry.summary = JsonFields.defaultString(o, "summary", "");
        entry.body = JsonFields.defaultString(o, "body", "");
        JsonObject cover = JsonFields.optionalObject(o, "cover");
        entry.publishedAt = JsonFields.requiredString(o, "publishedAt");
        entry.publishedAtMillis = JsonFields.requiredTimestamp(o, "publishedAt");
        entry.expiresAt = JsonFields.requiredString(o, "expiresAt");
        long expiresAt = JsonFields.requiredTimestamp(o, "expiresAt");
        entry.expiresAtMillis = expiresAt;
        JsonObject source = JsonFields.optionalObject(o, "source");
        String url = source == null ? null : JsonFields.optionalString(source, "url");

        String issue = textIssue(entry.id, entry.title, entry.summary, entry.body);
        if (issue == null && !isMinecraftVersion(version)) issue = "bad version";
        else if (issue == null && !entry.id.equals("minecraft-" + version)) issue = "id is not minecraft-<version>";
        else if (issue == null && expiresAt <= entry.publishedAtMillis) issue = "expires before it is published";
        else if (issue == null && expiresAt - entry.publishedAtMillis > ContentConfig.MINECRAFT_MAX_LIFETIME_MS) issue = "expires more than 90 days after it is published";
        else if (issue == null && !isMinecraftArticle(url)) issue = "no minecraft.net link";
        if (issue != null) {
            problems.add("minecraft " + entry.id + " dropped: " + issue);
            return null;
        }
        entry.minecraft = true;
        entry.minecraftVersion = version;
        entry.fromFeed = true;
        entry.sourceUrl = url;
        entry.category = null;
        entry.tags = new ArrayList<>();
        if (cover != null) {
            try {
                entry.cover = readMedia(cover, 0);
                entry.cover.poster = null;
                if (!isValidCover(entry.cover)) {
                    entry.cover = null;
                    problems.add("minecraft " + entry.id + ": cover removed");
                }
            } catch (JsonFields.Unreadable e) {
                entry.cover = null;
                problems.add("minecraft " + entry.id + ": cover removed");
            }
        }
        readEnglish(o, entry, problems);
        return entry;
    }

    /** {@code translations.en}: a text that breaks its limits, or a value that is not an object, is ignored. */
    private static void readEnglish(JsonObject o, NewsFeed.NewsItem entry, List<String> problems) {
        JsonElement translations = o.get("translations");
        if (translations == null || translations.isJsonNull()) return;
        if (!translations.isJsonObject()) {
            problems.add("minecraft " + entry.id + ": translations ignored");
            return;
        }
        JsonElement en = translations.getAsJsonObject().get("en");
        if (en == null || en.isJsonNull()) return;
        try {
            JsonObject english = JsonFields.object(en, "translation");
            String title = JsonFields.requiredString(english, "title");
            String summary = JsonFields.optionalString(english, "summary");
            String body = JsonFields.optionalString(english, "body");
            if (ContentText.isBlank(title) || ContentText.longerThan(title, ContentConfig.FEED_MAX_TITLE_CHARS)
                    || ContentText.longerThan(summary, ContentConfig.FEED_MAX_SUMMARY_CHARS)
                    || ContentText.longerThan(body, ContentConfig.FEED_MAX_BODY_CHARS)) {
                problems.add("minecraft " + entry.id + ": English text ignored");
                return;
            }
            entry.enTitle = title;
            entry.enSummary = summary;
            entry.enBody = body;
        } catch (JsonFields.Unreadable e) {
            problems.add("minecraft " + entry.id + ": English text ignored");
        }
    }

    /** Mojang's version id: 1 to 40 characters from {@code [A-Za-z0-9._-]}. */
    static boolean isMinecraftVersion(String version) {
        if (version == null || version.isEmpty() || version.length() > ContentConfig.MINECRAFT_MAX_VERSION_CHARS) return false;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** The attribution link: an {@code https://} URL on minecraft.net or www.minecraft.net, without credentials. */
    static boolean isMinecraftArticle(String url) {
        if (!ContentText.isHttpsLink(url)) return false;
        try {
            String host = new java.net.URI(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("minecraft.net") || host.equals("www.minecraft.net");
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static ContentException invalid(String message) {
        return new ContentException(ContentException.Kind.INVALID, message);
    }

    /** A cover the app may download: a safe path, a SHA-256, an image of 1 byte to 2 MB. */
    public static boolean isValidCover(MediaRef media) {
        return media != null && ContentPaths.isMirrorPath(media.path) && Hashes.normalizeHex(media.sha256, 64) != null
                && "image".equals(media.kind) && media.size >= 1 && media.size <= ContentConfig.FEED_MAX_MEDIA_BYTES;
    }

    /** A usable call to action: a label and exactly one target, a safe link or a news id. */
    static boolean isValidCta(NewsFeed.Cta cta) {
        boolean label = !ContentText.isBlank(cta.label);
        if (cta.url != null && cta.newsId == null) return label && ContentText.isSafeLink(cta.url);
        if (cta.url == null && cta.newsId != null) return label && ContentIds.isValid(cta.newsId);
        return false;
    }

    private static String rawId(JsonElement value) {
        if (value == null || !value.isJsonObject()) return "";
        JsonElement id = value.getAsJsonObject().get("id");
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) return "";
        String text = id.getAsString();
        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    /** Why an item's own text makes it unusable, or null. */
    private static String textIssue(String id, String title, String summary, String body) {
        if (!ContentIds.isValid(id)) return "bad id";
        if (ContentText.isBlank(title)) return "empty title";
        if (ContentText.longerThan(title, ContentConfig.FEED_MAX_TITLE_CHARS)) return "title too long";
        if (ContentText.longerThan(summary, ContentConfig.FEED_MAX_SUMMARY_CHARS)) return "summary too long";
        if (ContentText.longerThan(body, ContentConfig.FEED_MAX_BODY_CHARS)) return "body too long";
        return null;
    }

    private static List<NewsFeed.NewsItem> readItems(JsonArray raw, List<String> problems) {
        Set<String> seen = new HashSet<>();
        List<NewsFeed.NewsItem> items = new ArrayList<>();
        for (JsonElement value : raw) {
            NewsFeed.NewsItem item;
            try {
                item = readItem(value);
            } catch (JsonFields.Unreadable e) {
                problems.add("item " + rawId(value) + " dropped: unreadable (" + e.getMessage() + ")");
                continue;
            }
            String issue = textIssue(item.id, item.title, item.summary, item.body);
            if (issue != null) {
                problems.add("item " + item.id + " dropped: " + issue);
                continue;
            }
            if (!seen.add(item.id)) {
                problems.add("item " + item.id + " dropped: duplicate id");
                continue;
            }
            item.pinned = false;
            item.pinOrder = null;
            item.hidden = false;
            if (item.cover != null) {
                item.cover.poster = null;
                if (!isValidCover(item.cover)) {
                    item.cover = null;
                    problems.add("item " + item.id + ": cover removed");
                }
            }
            if (item.cta != null && !isValidCta(item.cta)) {
                item.cta = null;
                problems.add("item " + item.id + ": button removed");
            }
            if (item.sourceUrl != null && !ContentText.isHttpsLink(item.sourceUrl)) {
                item.sourceUrl = null;
                problems.add("item " + item.id + ": source link removed");
            }
            items.add(item);
        }
        return items;
    }

    private static NewsFeed.NewsItem readItem(JsonElement value) throws JsonFields.Unreadable {
        JsonObject o = JsonFields.object(value, "item");
        NewsFeed.NewsItem item = new NewsFeed.NewsItem();
        item.id = JsonFields.requiredString(o, "id");
        item.title = JsonFields.requiredString(o, "title");
        item.summary = JsonFields.defaultString(o, "summary", "");
        item.body = JsonFields.defaultString(o, "body", "");
        JsonObject cover = JsonFields.optionalObject(o, "cover");
        item.cover = cover == null ? null : readMedia(cover, 0);
        item.category = JsonFields.defaultString(o, "category", "announcement");
        item.tags = JsonFields.defaultStringList(o, "tags");
        item.publishedAt = JsonFields.requiredString(o, "publishedAt");
        item.publishedAtMillis = JsonFields.requiredTimestamp(o, "publishedAt");
        item.expiresAt = JsonFields.optionalString(o, "expiresAt");
        item.expiresAtMillis = JsonFields.optionalTimestamp(o, "expiresAt");
        item.pinned = JsonFields.defaultBoolean(o, "pinned", false);
        item.featured = JsonFields.defaultBoolean(o, "featured", false);
        JsonObject cta = JsonFields.optionalObject(o, "cta");
        if (cta != null) {
            item.cta = new NewsFeed.Cta();
            item.cta.label = JsonFields.requiredString(cta, "label");
            item.cta.url = JsonFields.optionalString(cta, "url");
            item.cta.newsId = JsonFields.optionalString(cta, "newsId");
        }
        item.author = JsonFields.optionalString(o, "author");
        item.sourceUrl = readSourceUrl(o);
        item.fromFeed = true;
        return item;
    }

    /** {@code source: { kind, channel?, url? }} (type-checked like the desktop); returns the url. */
    private static String readSourceUrl(JsonObject o) throws JsonFields.Unreadable {
        JsonObject source = JsonFields.optionalObject(o, "source");
        if (source == null) return null;
        JsonFields.defaultString(source, "kind", "unknown");
        JsonFields.optionalString(source, "channel");
        return JsonFields.optionalString(source, "url");
    }

    private static MediaRef readMedia(JsonObject o, int depth) throws JsonFields.Unreadable {
        if (depth > 8) throw new JsonFields.Unreadable("poster chain is too deep");
        MediaRef media = new MediaRef();
        media.path = JsonFields.requiredString(o, "path");
        media.sha256 = JsonFields.requiredString(o, "sha256");
        media.size = JsonFields.requiredInteger(o, "size", 0, Long.MAX_VALUE);
        media.kind = JsonFields.defaultString(o, "kind", "image");
        Long width = JsonFields.optionalInteger(o, "width", 0, 0xFFFFFFFFL);
        Long height = JsonFields.optionalInteger(o, "height", 0, 0xFFFFFFFFL);
        media.width = width == null ? null : (int) Math.min(width, Integer.MAX_VALUE);
        media.height = height == null ? null : (int) Math.min(height, Integer.MAX_VALUE);
        media.blurhash = JsonFields.optionalString(o, "blurhash");
        JsonObject poster = JsonFields.optionalObject(o, "poster");
        media.poster = poster == null ? null : readMedia(poster, depth + 1);
        media.alt = JsonFields.optionalString(o, "alt");
        return media;
    }

    private static List<Event> readEvents(JsonArray raw, List<NewsFeed.NewsItem> items, List<String> problems) {
        Set<String> itemIds = new HashSet<>();
        for (NewsFeed.NewsItem item : items) itemIds.add(item.id);
        Set<String> seen = new HashSet<>();
        List<Event> events = new ArrayList<>();
        for (JsonElement value : raw) {
            String id;
            String title;
            long startsAt;
            Long endsAt;
            String url;
            String newsId;
            int priority;
            boolean join;
            try {
                JsonObject o = JsonFields.object(value, "event");
                id = JsonFields.requiredString(o, "id");
                title = JsonFields.requiredString(o, "title");
                startsAt = JsonFields.requiredTimestamp(o, "startsAt");
                endsAt = JsonFields.optionalTimestamp(o, "endsAt");
                url = JsonFields.optionalString(o, "url");
                newsId = JsonFields.optionalString(o, "newsId");
                priority = (int) JsonFields.defaultInteger(o, "priority", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
                // M17: a "join" that is not a boolean makes the event unreadable (content-format.md rule 4).
                join = JsonFields.defaultBoolean(o, "join", false);
                readSourceUrl(o);
            } catch (JsonFields.Unreadable e) {
                problems.add("event " + rawId(value) + " dropped: unreadable (" + e.getMessage() + ")");
                continue;
            }
            String issue = null;
            if (!ContentIds.isValid(id)) issue = "bad id";
            else if (ContentText.isBlank(title)) issue = "empty title";
            else if (ContentText.longerThan(title, ContentConfig.FEED_MAX_TITLE_CHARS)) issue = "title too long";
            else if (endsAt != null && endsAt <= startsAt) issue = "ends before it starts";
            if (issue != null) {
                problems.add("event " + id + " dropped: " + issue);
                continue;
            }
            if (!seen.add(id)) {
                problems.add("event " + id + " dropped: duplicate id");
                continue;
            }
            if (url != null && !ContentText.isSafeLink(url)) {
                url = null;
                problems.add("event " + id + ": link removed");
            }
            if (newsId != null && !itemIds.contains(newsId)) {
                newsId = null;
                problems.add("event " + id + ": unknown news item");
            }
            events.add(new Event(id, title, startsAt, endsAt, url, newsId, priority, join));
        }
        return events;
    }
}
