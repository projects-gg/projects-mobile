package gg.projects.mobile.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code feed.json} → {@code status} (content-format.md "Status notice"; pl-content's
 * {@code FeedStatus}): what players should know about the servers right now, from the server's
 * status page. Short-lived: it is shown only while {@code now < expiresAt} and the bot renews it
 * while the problem lasts, so a bot that stops leaves no stale notice, a cached copy included.
 * Purely informational; it never blocks playing.
 *
 * <p>Read on its own: a notice that breaks a rule is dropped without touching the rest of the
 * feed, a status page link that is not {@code https://} is removed and the notice kept, unknown
 * fields are ignored, an unknown {@code level} is the calmest one and an unknown service
 * {@code state} is {@link State#UNKNOWN}.
 */
public final class FeedStatus {
    public static final int MAX_TITLE_CHARS = 80;
    public static final int MAX_MESSAGE_CHARS = 200;
    public static final int MAX_SERVICES = 20;
    public static final int MAX_SERVICE_NAME_CHARS = 64;
    /** Latest {@code expiresAt} after its {@code updatedAt}. */
    public static final long MAX_LIFETIME_MS = 15 * 60_000L;

    /** How serious the notice is. */
    public enum Level {
        /** Services are down. */
        OUTAGE,
        /** Services are reachable only in part. */
        DEGRADED,
        /** A notice posted on the status page; also any level a newer bot adds. */
        NOTICE
    }

    /** What a listed service is going through. */
    public enum State { DOWN, DEGRADED, UNKNOWN }

    public static final class Service {
        public final String name;
        public final State state;

        Service(String name, State state) {
            this.name = name;
            this.state = state;
        }
    }

    public final Level level;
    /** 1 to 80 characters, not blank. */
    public final String title;
    /** At most 200 characters; may be empty. */
    public final String message;
    public final List<Service> services;
    /** The status page ({@code https://} only), or null. */
    public final String url;
    public final long updatedAt;
    public final long expiresAt;

    FeedStatus(Level level, String title, String message, List<Service> services, String url, long updatedAt, long expiresAt) {
        this.level = level;
        this.title = title;
        this.message = message;
        this.services = Collections.unmodifiableList(services);
        this.url = url;
        this.updatedAt = updatedAt;
        this.expiresAt = expiresAt;
    }

    /** Shown at {@code now}: it has not expired. */
    public boolean isActive(long now) {
        return now < expiresAt;
    }

    /**
     * The notice in {@code raw} when it follows the rules, else null (with the reason in
     * {@code problems}). Missing or {@code null}: no notice. Expiry against the clock is left to
     * {@link #isActive}: a cached feed is read again later.
     */
    static FeedStatus read(JsonElement raw, List<String> problems) {
        if (raw == null || raw.isJsonNull()) return null;
        FeedStatus status;
        try {
            status = parse(raw);
        } catch (JsonFields.Unreadable e) {
            problems.add("status dropped: unreadable (" + e.getMessage() + ")");
            return null;
        }
        String issue = issue(status);
        if (issue != null) {
            problems.add("status dropped: " + issue);
            return null;
        }
        if (status.url != null && !ContentText.isHttpsLink(status.url)) {
            problems.add("status: link removed");
            return new FeedStatus(status.level, status.title, status.message, status.services, null,
                    status.updatedAt, status.expiresAt);
        }
        return status;
    }

    private static FeedStatus parse(JsonElement raw) throws JsonFields.Unreadable {
        JsonObject o = JsonFields.object(raw, "status");
        Level level = level(JsonFields.defaultString(o, "level", "notice"));
        String title = JsonFields.requiredString(o, "title");
        String message = JsonFields.defaultString(o, "message", "");
        List<Service> services = new ArrayList<>();
        JsonArray rawServices = JsonFields.defaultArray(o, "services");
        for (JsonElement value : rawServices) {
            JsonObject service = JsonFields.object(value, "service");
            services.add(new Service(JsonFields.requiredString(service, "name"),
                    state(JsonFields.defaultString(service, "state", "unknown"))));
        }
        String url = JsonFields.optionalString(o, "url");
        long updatedAt = JsonFields.requiredTimestamp(o, "updatedAt");
        long expiresAt = JsonFields.requiredTimestamp(o, "expiresAt");
        return new FeedStatus(level, title, message, services, url, updatedAt, expiresAt);
    }

    /** Why the notice breaks a rule of the table, or null. */
    private static String issue(FeedStatus status) {
        if (ContentText.isBlank(status.title)) return "empty title";
        if (ContentText.longerThan(status.title, MAX_TITLE_CHARS)) return "title too long";
        if (ContentText.longerThan(status.message, MAX_MESSAGE_CHARS)) return "message too long";
        if (status.services.size() > MAX_SERVICES) return "too many services";
        for (Service service : status.services) {
            if (ContentText.isBlank(service.name) || ContentText.longerThan(service.name, MAX_SERVICE_NAME_CHARS)) {
                return "bad service name";
            }
        }
        if (status.expiresAt <= status.updatedAt || status.expiresAt > status.updatedAt + MAX_LIFETIME_MS) {
            return "bad expiry";
        }
        return null;
    }

    private static Level level(String text) {
        switch (text) {
            case "outage": return Level.OUTAGE;
            case "degraded": return Level.DEGRADED;
            default: return Level.NOTICE;
        }
    }

    private static State state(String text) {
        switch (text) {
            case "down": return State.DOWN;
            case "degraded": return State.DEGRADED;
            default: return State.UNKNOWN;
        }
    }
}
