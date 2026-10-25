package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import gg.projects.mobile.social.Json;

/**
 * A 7/24 Bulut notice (services/cloud-afk README "AFK+ notifications"; services/social README "AFK notices"): from
 * the friends service's socket ({@code {"t":"afk_notice","notice":{…}}}) or a push ({@code type: "afk"}, every value a
 * string). Data only: the phone writes the words in its own language; no counterpart's name or message text ever
 * comes, only the bot account's own name, items and amounts. Pure, with tests.
 */
public final class CloudNotice {
    public static final String KIND_ALERT = "afk_alert";
    public static final String KIND_DEAL = "afk_deal";
    public static final String KIND_INFO = "afk_info";

    public final String id;
    public final String event;
    public final String kind;
    public final boolean high;
    public final long at;
    @Nullable
    public final String tag;
    /** The recipient's Discord id (pushes only). */
    @Nullable
    public final String to;
    /**
     * The player's quiet hours (sockets only): shown in the app's own line if it is on screen, never as a system
     * notification; one not seen comes back in the morning's digest ({@code night}).
     */
    public final boolean quiet;
    public final Map<String, String> data;

    private CloudNotice(String id, String event, String kind, boolean high, long at, @Nullable String tag, @Nullable String to,
                        boolean quiet, Map<String, String> data) {
        this.id = id;
        this.event = event;
        this.kind = kind;
        this.high = high;
        this.at = at;
        this.tag = tag;
        this.to = to;
        this.quiet = quiet;
        this.data = Collections.unmodifiableMap(data);
    }

    private static boolean known(String kind) {
        return KIND_ALERT.equals(kind) || KIND_DEAL.equals(kind) || KIND_INFO.equals(kind);
    }

    /** The socket's {@code afk_notice} message, or null when it is not one this app reads. */
    @Nullable
    public static CloudNotice fromSocket(@Nullable JsonObject message) {
        JsonObject n = Json.obj(message, "notice");
        if (n == null) return null;
        String id = Json.str(n, "id");
        String event = Json.str(n, "event");
        String kind = Json.str(n, "kind");
        if (id == null || event == null || kind == null || !known(kind)) return null;
        Map<String, String> data = new LinkedHashMap<>();
        JsonObject d = Json.obj(n, "data");
        if (d != null) {
            for (Map.Entry<String, JsonElement> e : d.entrySet()) {
                if (e.getValue().isJsonPrimitive()) data.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return new CloudNotice(id, event, kind, "high".equals(Json.str(n, "priority")), Json.num(n, "at", 0), Json.str(n, "tag"), null,
                Json.bool(n, "quiet", false), data);
    }

    /** A push's data ({@code v: "1"}, {@code type: "afk"}), or null when it is not one this app reads. */
    @Nullable
    public static CloudNotice fromPush(@Nullable Map<String, String> push) {
        if (push == null || !"1".equals(push.get("v")) || !"afk".equals(push.get("type"))) return null;
        String id = push.get("id");
        String event = push.get("event");
        String kind = push.get("kind");
        if (id == null || event == null) return null;
        // A notice the friends service made itself ("night") names its kind too; older ones may not.
        if (kind == null) kind = "night".equals(event) ? KIND_INFO : null;
        if (kind == null || !known(kind)) return null;
        Map<String, String> data = new HashMap<>(push);
        for (String own : new String[]{"v", "type", "event", "kind", "id", "at", "tag", "to"}) data.remove(own);
        long at;
        try {
            at = Long.parseLong(push.containsKey("at") ? push.get("at") : "0");
        } catch (NumberFormatException e) {
            at = 0;
        }
        String priority = push.get("priority");
        boolean high = "high".equals(priority) || "approval".equals(event) || "stopped".equals(event);
        // A push never comes in quiet hours (the friends service keeps those for the morning's digest).
        return new CloudNotice(id, event, kind, high, at, push.get("tag"), push.get("to"), false, data);
    }

    @Nullable
    public String get(String key) {
        return data.get(key);
    }

    public long number(String key, long fallback) {
        String v = data.get(key);
        if (v == null) return fallback;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The account the notice is about ({@code acct}), null for a player-wide one ({@code night}). */
    @Nullable
    public String account() {
        return data.get("acct");
    }

    /** The bot account's own in-game name. */
    @Nullable
    public String name() {
        return data.get("name");
    }

    /** {@code approval:<id>} for an approval and its end; the notification is replaced or removed by it. */
    @Nullable
    public String approvalId() {
        if ("approval".equals(event) || "approval_closed".equals(event)) {
            String id = data.get("approval");
            if (id != null) return id;
        }
        if (tag != null && tag.startsWith("approval:")) return tag.substring("approval:".length());
        return null;
    }

    /** What to do with it on this phone. */
    public enum Action { SHOW, CANCEL, DROP }

    public static final class Decision {
        public final Action action;
        public final String why;

        Decision(Action action, String why) {
            this.action = action;
            this.why = why;
        }
    }

    /**
     * {@code signedIn}: the Discord id of the account signed in on this phone (null: nobody). A push for another
     * account, an approval past its time or a notice of a kind the app does not show goes; {@code approval_closed}
     * takes the approval's notification away.
     */
    public Decision decide(@Nullable String signedIn, long now) {
        if (to != null && (signedIn == null || !to.equals(signedIn))) return new Decision(Action.DROP, "another account");
        if ("approval_closed".equals(event)) return new Decision(Action.CANCEL, "closed");
        if ("approval".equals(event)) {
            long expires = number("expires", 0);
            if (expires > 0 && expires <= now) return new Decision(Action.DROP, "expired");
        }
        if (!showable(event)) return new Decision(Action.DROP, "unknown event");
        return new Decision(Action.SHOW, "ok");
    }

    /** The events this app words; an unknown one is dropped (a newer service). */
    public static boolean showable(String event) {
        switch (event) {
            case "stopped":
            case "renewal":
            case "expired":
            case "found":
            case "approval":
            case "deal_done":
            case "deal_failed":
            case "quota":
            case "summary":
            case "paused":
            case "incident":
            case "message":
            case "weekly":
            case "night":
            case "moderation":
                return true;
            default:
                return false;
        }
    }

    /** {@code night}'s counts ({@code "deal_done=2,found=1"}), in their order. */
    public Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        String raw = data.get("counts");
        if (raw == null) return out;
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                int n = Integer.parseInt(pair.substring(eq + 1).trim());
                if (n > 0) out.put(pair.substring(0, eq).trim(), n);
            } catch (NumberFormatException ignored) {
                // A malformed pair is skipped.
            }
        }
        return out;
    }
}
