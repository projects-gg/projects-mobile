package gg.projects.mobile.afk.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkSendResult;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

/**
 * PHONE-AFK: what the app's screens and the {@code :afk} service say to each other over a {@code Messenger} (message
 * codes, {@code Bundle} keys) and the JSON of the values (short keys; pure Java, JVM tested). Login commands never
 * travel: the service reads them from the store itself.
 */
public final class AfkIpc {
    private AfkIpc() {}

    // The screens to the service (Message.what).
    /** Watching starts ({@code replyTo}): the service answers {@link #STATUSES} and a {@link #BACKLOG} per account. */
    public static final int HELLO = 1;
    public static final int BYE = 2;
    /** {@link #KEY_ACCOUNT}, {@link #KEY_TEXT}; {@code arg1} is the request, answered by {@link #SEND_RESULT}. */
    public static final int SEND = 3;
    public static final int STOP = 4;
    public static final int STOP_ALL = 5;
    /** {@link #KEY_ACCOUNT}: drop the connection and connect again with the saved settings. */
    public static final int RECONNECT = 6;

    // The service to the screens.
    public static final int STATUSES = 101;
    public static final int STATUS = 102;
    public static final int BACKLOG = 103;
    public static final int LINE = 104;
    public static final int ALERT = 105;
    /** {@code arg1}: the request; {@link #KEY_TEXT}: the {@link AfkSendResult#key()}. */
    public static final int SEND_RESULT = 106;

    public static final String KEY_JSON = "j";
    public static final String KEY_ACCOUNT = "a";
    public static final String KEY_TEXT = "x";

    // ---- statuses ----

    public static JsonObject status(AfkStatus s) {
        JsonObject o = new JsonObject();
        o.addProperty("a", s.accountId);
        o.addProperty("n", s.name);
        o.addProperty("s", s.state.name().toLowerCase(Locale.ROOT));
        if (s.since != 0) o.addProperty("t", s.since);
        if (s.inGameSince != 0) o.addProperty("g", s.inGameSince);
        if (s.nextAttemptAt != 0) o.addProperty("x", s.nextAttemptAt);
        if (s.attempt != 0) o.addProperty("c", s.attempt);
        if (s.reason != null) o.addProperty("r", s.reason.key());
        if (s.serverMessage != null) o.addProperty("m", s.serverMessage);
        return o;
    }

    public static AfkStatus status(JsonObject o) {
        return new AfkStatus(str(o, "a"), str(o, "n"), state(str(o, "s")), num(o, "t"), num(o, "g"), num(o, "x"),
                (int) num(o, "c"), AfkReason.ofKey(strOrNull(o, "r")), strOrNull(o, "m"));
    }

    /** An unknown state reads as {@link AfkState#CONNECTING} (a newer service). */
    static AfkState state(String key) {
        for (AfkState s : AfkState.values()) {
            if (s.name().toLowerCase(Locale.ROOT).equals(key)) return s;
        }
        return AfkState.CONNECTING;
    }

    public static String statuses(List<AfkStatus> list) {
        JsonArray a = new JsonArray();
        for (AfkStatus s : list) a.add(status(s));
        return a.toString();
    }

    public static List<AfkStatus> statuses(String json) {
        List<AfkStatus> out = new ArrayList<>();
        for (JsonObject o : objects(json)) out.add(status(o));
        return out;
    }

    // ---- console lines ----

    public static JsonObject line(AfkLine l) {
        JsonObject o = new JsonObject();
        o.addProperty("a", l.accountId);
        o.addProperty("q", l.seq);
        o.addProperty("t", l.time);
        o.addProperty("k", l.kind.name().toLowerCase(Locale.ROOT));
        o.addProperty("x", l.text);
        if (!l.spans.isEmpty()) {
            JsonArray spans = new JsonArray();
            for (AfkLine.Span s : l.spans) {
                JsonArray one = new JsonArray();
                one.add(s.start);
                one.add(s.end);
                one.add(s.color);
                one.add(s.style);
                spans.add(one);
            }
            o.add("p", spans);
        }
        if (l.sender != null) o.addProperty("f", l.sender);
        if (l.mentioned) o.addProperty("m", true);
        return o;
    }

    public static AfkLine line(JsonObject o) {
        List<AfkLine.Span> spans = new ArrayList<>();
        JsonElement p = o.get("p");
        if (p != null && p.isJsonArray()) {
            for (JsonElement e : p.getAsJsonArray()) {
                if (!e.isJsonArray() || e.getAsJsonArray().size() != 4) continue;
                JsonArray one = e.getAsJsonArray();
                spans.add(new AfkLine.Span(one.get(0).getAsInt(), one.get(1).getAsInt(), one.get(2).getAsInt(),
                        one.get(3).getAsInt()));
            }
        }
        return new AfkLine(str(o, "a"), num(o, "q"), num(o, "t"), lineKind(str(o, "k")), str(o, "x"), spans,
                strOrNull(o, "f"), bool(o, "m"));
    }

    static AfkLine.Kind lineKind(String key) {
        for (AfkLine.Kind k : AfkLine.Kind.values()) {
            if (k.name().toLowerCase(Locale.ROOT).equals(key)) return k;
        }
        return AfkLine.Kind.SYSTEM;
    }

    public static String lines(List<AfkLine> list) {
        JsonArray a = new JsonArray();
        for (AfkLine l : list) a.add(line(l));
        return a.toString();
    }

    public static List<AfkLine> lines(String json) {
        List<AfkLine> out = new ArrayList<>();
        for (JsonObject o : objects(json)) out.add(line(o));
        return out;
    }

    // ---- alerts ----

    public static JsonObject alert(AfkAlert a) {
        JsonObject o = new JsonObject();
        o.addProperty("a", a.accountId);
        o.addProperty("n", a.name);
        o.addProperty("k", a.kind.name().toLowerCase(Locale.ROOT));
        if (!a.text.isEmpty()) o.addProperty("x", a.text);
        if (a.reason != null) o.addProperty("r", a.reason.key());
        o.addProperty("t", a.time);
        return o;
    }

    /** Null for a kind this app does not know (a newer service). */
    public static AfkAlert alert(JsonObject o) {
        AfkAlert.Kind kind = null;
        String key = str(o, "k");
        for (AfkAlert.Kind k : AfkAlert.Kind.values()) {
            if (k.name().toLowerCase(Locale.ROOT).equals(key)) kind = k;
        }
        if (kind == null) return null;
        return new AfkAlert(str(o, "a"), str(o, "n"), kind, str(o, "x"), AfkReason.ofKey(strOrNull(o, "r")), num(o, "t"));
    }

    // ---- helpers ----

    public static JsonObject object(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    static List<JsonObject> objects(String json) {
        List<JsonObject> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonArray()) return out;
            for (JsonElement one : e.getAsJsonArray()) {
                if (one.isJsonObject()) out.add(one.getAsJsonObject());
            }
        } catch (RuntimeException ignored) {
            // A broken message reads as nothing.
        }
        return out;
    }

    static String str(JsonObject o, String key) {
        String s = strOrNull(o, key);
        return s == null ? "" : s;
    }

    static String strOrNull(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    static long num(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return 0;
        return v.getAsLong();
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }
}
