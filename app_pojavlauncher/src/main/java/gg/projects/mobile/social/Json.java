package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lenient reading of the friends service's JSON: a missing field or one of another type reads as
 * the default, unknown fields are ignored, so a newer service never breaks the app (and an older one
 * simply lacks what it does not have).
 */
public final class Json {
    private Json() {}

    @Nullable
    public static JsonObject parseObject(@Nullable String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Any JSON value, or null when the text is not JSON. */
    @Nullable
    public static JsonElement parse(@Nullable String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    public static JsonObject obj(@Nullable JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** The array under {@code key}, or an empty one. */
    public static JsonArray arr(@Nullable JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public static boolean has(@Nullable JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull();
    }

    @Nullable
    public static String str(@Nullable JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isString() ? p.getAsString() : null;
    }

    public static String str(@Nullable JsonObject o, String key, String fallback) {
        String value = str(o, key);
        return value == null ? fallback : value;
    }

    public static boolean bool(@Nullable JsonObject o, String key, boolean fallback) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) return fallback;
        return e.getAsBoolean();
    }

    public static long num(@Nullable JsonObject o, String key, long fallback) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        try {
            double d = e.getAsDouble();
            if (Double.isNaN(d) || Double.isInfinite(d)) return fallback;
            return (long) d;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    /** A nullable number: null when missing, null or not a number. */
    @Nullable
    public static Long numOrNull(@Nullable JsonObject o, String key) {
        if (!has(o, key)) return null;
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        return num(o, key, 0);
    }

    /** The strings of an array (other entries skipped); empty when it is missing. */
    public static List<String> strings(@Nullable JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonArray()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) out.add(item.getAsString());
        }
        return out;
    }

    /** The objects of an array (other entries skipped). */
    public static List<JsonObject> objects(@Nullable JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement item : arr(o, key)) {
            if (item.isJsonObject()) out.add(item.getAsJsonObject());
        }
        return out;
    }
}
