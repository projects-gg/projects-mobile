package gg.projects.mobile.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Field readers with the strictness of the desktop's serde models, for entries that are read one
 * by one (a broken entry is dropped on its own instead of failing the document): a required field
 * must be present with the right type, a field with a default may be missing but not
 * {@code null}, an optional field may be missing or {@code null}, and integers must be integer
 * literals in range.
 */
final class JsonFields {
    private JsonFields() {}

    /** The entry does not have the expected shape. */
    static final class Unreadable extends Exception {
        Unreadable(String what) {
            super(what);
        }
    }

    private static final Pattern INTEGER = Pattern.compile("^-?[0-9]+$");

    static JsonObject object(JsonElement value, String what) throws Unreadable {
        if (value == null || !value.isJsonObject()) throw new Unreadable(what + " is not an object");
        return value.getAsJsonObject();
    }

    /** Present and not null, or null when the field is missing or null. */
    private static JsonElement present(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value;
    }

    private static boolean has(JsonObject object, String key) {
        return object.has(key);
    }

    private static String string(JsonElement value, String key) throws Unreadable {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return value.getAsString();
        throw new Unreadable(key + " is not a string");
    }

    static String requiredString(JsonObject object, String key) throws Unreadable {
        JsonElement value = present(object, key);
        if (value == null) throw new Unreadable(key + " is missing");
        return string(value, key);
    }

    /** Missing: {@code fallback}; null or another type: unreadable. */
    static String defaultString(JsonObject object, String key, String fallback) throws Unreadable {
        if (!has(object, key)) return fallback;
        JsonElement value = object.get(key);
        if (value.isJsonNull()) throw new Unreadable(key + " is null");
        return string(value, key);
    }

    static String optionalString(JsonObject object, String key) throws Unreadable {
        JsonElement value = present(object, key);
        return value == null ? null : string(value, key);
    }

    static long requiredTimestamp(JsonObject object, String key) throws Unreadable {
        Long millis = Rfc3339.parse(requiredString(object, key));
        if (millis == null) throw new Unreadable(key + " is not a timestamp");
        return millis;
    }

    static Long optionalTimestamp(JsonObject object, String key) throws Unreadable {
        String text = optionalString(object, key);
        if (text == null) return null;
        Long millis = Rfc3339.parse(text);
        if (millis == null) throw new Unreadable(key + " is not a timestamp");
        return millis;
    }

    static boolean defaultBoolean(JsonObject object, String key, boolean fallback) throws Unreadable {
        if (!has(object, key)) return fallback;
        JsonElement value = object.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        throw new Unreadable(key + " is not a boolean");
    }

    /** An integer literal (no fraction or exponent) within {@code min..max}. */
    static long integer(JsonElement value, String key, long min, long max) throws Unreadable {
        if (value == null || !value.isJsonPrimitive()) throw new Unreadable(key + " is not an integer");
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) throw new Unreadable(key + " is not an integer");
        String literal = primitive.getAsNumber().toString();
        if (!INTEGER.matcher(literal).matches()) throw new Unreadable(key + " is not an integer");
        BigInteger number = new BigInteger(literal);
        if (number.compareTo(BigInteger.valueOf(min)) < 0 || number.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new Unreadable(key + " is out of range");
        }
        return number.longValue();
    }

    static long requiredInteger(JsonObject object, String key, long min, long max) throws Unreadable {
        JsonElement value = present(object, key);
        if (value == null) throw new Unreadable(key + " is missing");
        return integer(value, key, min, max);
    }

    static long defaultInteger(JsonObject object, String key, long fallback, long min, long max) throws Unreadable {
        if (!has(object, key)) return fallback;
        return integer(object.get(key), key, min, max);
    }

    static Long optionalInteger(JsonObject object, String key, long min, long max) throws Unreadable {
        JsonElement value = present(object, key);
        return value == null ? null : integer(value, key, min, max);
    }

    /** Missing: empty; null or not an array: unreadable. */
    static JsonArray defaultArray(JsonObject object, String key) throws Unreadable {
        if (!has(object, key)) return new JsonArray();
        JsonElement value = object.get(key);
        if (!value.isJsonArray()) throw new Unreadable(key + " is not an array");
        return value.getAsJsonArray();
    }

    static List<String> defaultStringList(JsonObject object, String key) throws Unreadable {
        List<String> out = new ArrayList<>();
        for (JsonElement element : defaultArray(object, key)) out.add(string(element, key));
        return out;
    }

    /** Missing or null: null; otherwise it must be an object. */
    static JsonObject optionalObject(JsonObject object, String key) throws Unreadable {
        JsonElement value = present(object, key);
        return value == null ? null : object(value, key);
    }
}
