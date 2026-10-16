package gg.projects.mobile.content;

import androidx.annotation.Keep;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code config/launcher.<sha256>.json}, reduced to what the mobile app uses. Every field is
 * optional ({@code {}} is a valid config).
 */
@Keep
public final class LauncherConfig {
    public int schema = 1;
    public Maintenance maintenance;
    public List<ServerEntry> servers;
    public Links links;
    public String recommendedPack;

    /** Read entry by entry in {@link #parse}: one broken event never hides the rest of the config. */
    @SerializedName("events")
    JsonElement eventsJson;
    @SerializedName("newsFeed")
    JsonElement newsFeedJson;

    /** Studio's events, hidden ones included (they take a feed event with their id off the card). */
    public transient List<EventCountdown> events = Collections.emptyList();
    /** The Discord bot's feed settings; null when the config has none. */
    public transient NewsFeedConfig newsFeed;

    @Keep
    public static final class Maintenance {
        public boolean enabled;
        public String title;
        public String message;
        public String until;

        /** Active while enabled and ({@code until} missing or in the future). */
        public boolean isActive(long now) {
            if (!enabled) return false;
            if (until == null) return true;
            Long end = Rfc3339.parse(until);
            return end == null || now < end;
        }
    }

    @Keep
    public static final class ServerEntry {
        public String id;
        public String name;
        public String address;
        public Integer port;
        public boolean featured;
    }

    @Keep
    public static final class Links {
        public String website;
        public String discord;
        public String store;
        public String support;
    }

    /**
     * {@code events[]}: a countdown until {@code startsAt}, live until {@code endsAt}. Read with the
     * desktop's field rules; an event that breaks them is dropped on its own.
     */
    public static final class EventCountdown {
        public final String id;
        public final String title;
        public final long startsAt;
        public final long endsAt;
        public final String url;
        /** The article the card opens while that item is on the home screen. */
        public final String newsId;
        /** Among live events the highest leads the card (default 0). */
        public final int priority;
        /** Never shown; takes the feed event with this id off the card. */
        public final boolean hidden;

        public EventCountdown(String id, String title, long startsAt, long endsAt, String url, String newsId,
                              int priority, boolean hidden) {
            this.id = id;
            this.title = title;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
            this.url = url;
            this.newsId = newsId;
            this.priority = priority;
            this.hidden = hidden;
        }

        static EventCountdown read(JsonElement value) throws JsonFields.Unreadable {
            JsonObject o = JsonFields.object(value, "event");
            return new EventCountdown(
                    JsonFields.requiredString(o, "id"),
                    JsonFields.requiredString(o, "title"),
                    JsonFields.requiredTimestamp(o, "startsAt"),
                    JsonFields.requiredTimestamp(o, "endsAt"),
                    JsonFields.optionalString(o, "url"),
                    JsonFields.optionalString(o, "newsId"),
                    (int) JsonFields.defaultInteger(o, "priority", 0, Integer.MIN_VALUE, Integer.MAX_VALUE),
                    JsonFields.defaultBoolean(o, "hidden", false));
        }
    }

    public static LauncherConfig parse(byte[] bytes) throws ContentException {
        LauncherConfig config = ContentJson.parse(bytes, LauncherConfig.class, "launcher config");
        ContentJson.checkSchema("launcher config", config.schema, ContentConfig.LAUNCHER_CONFIG_SCHEMA);
        if (config.servers == null) config.servers = new ArrayList<>();
        List<EventCountdown> events = new ArrayList<>();
        if (config.eventsJson != null && config.eventsJson.isJsonArray()) {
            for (JsonElement value : config.eventsJson.getAsJsonArray()) {
                try {
                    events.add(EventCountdown.read(value));
                } catch (JsonFields.Unreadable ignored) {
                    // Studio never publishes such an event; skipping it keeps the rest usable.
                }
            }
        }
        config.events = Collections.unmodifiableList(events);
        config.newsFeed = NewsFeedConfig.read(config.newsFeedJson);
        config.eventsJson = null;
        config.newsFeedJson = null;
        return config;
    }

    /** The Discord invite to open, or null when the config has no usable one. */
    public String discordUrl() {
        return links != null && ContentPaths.isHttpsUrl(links.discord) ? links.discord : null;
    }
}
