package gg.projects.mobile.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code LauncherConfig.newsFeed}: whether the app reads the Discord bot's feed, from where, and
 * which keys may sign it (Studio delegates the right to sign news through the signed config).
 * Read leniently like pl-content's {@code FeedSettings::from_config}: invalid mirrors and keys are
 * skipped, an out-of-range {@code maxAgeDays} falls back to the default.
 */
public final class NewsFeedConfig {
    public final boolean enabled;
    public final List<String> mirrors;
    public final List<Key> keys;
    /** As written; see {@link #effectiveMaxAgeDays()}. */
    public final long maxAgeDays;

    public static final class Key {
        public final String keyId;
        public final String publicKey;

        public Key(String keyId, String publicKey) {
            this.keyId = keyId;
            this.publicKey = publicKey;
        }
    }

    public NewsFeedConfig(boolean enabled, List<String> mirrors, List<Key> keys, long maxAgeDays) {
        this.enabled = enabled;
        this.mirrors = Collections.unmodifiableList(new ArrayList<>(mirrors));
        this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        this.maxAgeDays = maxAgeDays;
    }

    /** The object in the config, or null when it is missing or not an object. */
    static NewsFeedConfig read(JsonElement json) {
        if (json == null || !json.isJsonObject()) return null;
        JsonObject object = json.getAsJsonObject();
        JsonElement enabled = object.get("enabled");
        boolean on = enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean()
                && enabled.getAsBoolean();
        List<String> mirrors = new ArrayList<>();
        JsonElement mirrorList = object.get("mirrors");
        if (mirrorList != null && mirrorList.isJsonArray()) {
            for (JsonElement mirror : mirrorList.getAsJsonArray()) {
                if (mirror.isJsonPrimitive() && mirror.getAsJsonPrimitive().isString()) mirrors.add(mirror.getAsString());
            }
        }
        List<Key> keys = new ArrayList<>();
        JsonElement keyList = object.get("keys");
        if (keyList != null && keyList.isJsonArray()) {
            for (JsonElement key : keyList.getAsJsonArray()) {
                if (!key.isJsonObject()) continue;
                try {
                    keys.add(new Key(JsonFields.defaultString(key.getAsJsonObject(), "keyId", ""),
                            JsonFields.defaultString(key.getAsJsonObject(), "publicKey", "")));
                } catch (JsonFields.Unreadable ignored) {
                    // A key that is not a pair of strings is skipped.
                }
            }
        }
        long maxAge = ContentConfig.FEED_DEFAULT_MAX_AGE_DAYS;
        JsonElement age = object.get("maxAgeDays");
        if (age != null && !age.isJsonNull()) {
            try {
                maxAge = JsonFields.integer(age, "maxAgeDays", 0, 0xFFFFFFFFL);
            } catch (JsonFields.Unreadable ignored) {
                // Not a u32: the default applies.
            }
        }
        return new NewsFeedConfig(on, mirrors, keys, maxAge);
    }

    /** The valid mirror bases in order, without duplicates, at most four. */
    public List<String> mirrorBases() {
        List<String> bases = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String mirror : mirrors) {
            String normalized = normalizedMirror(mirror);
            if (normalized == null || seen.contains(normalized)) continue;
            if (bases.size() >= ContentConfig.FEED_MAX_MIRRORS) break;
            seen.add(normalized);
            bases.add(mirror);
        }
        return bases;
    }

    /**
     * The keys (base64) that decode to 32 bytes and whose {@code keyId} is exactly their id,
     * without duplicates, at most four.
     */
    public List<String> trustedKeys() {
        List<String> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Key key : keys) {
            byte[] raw;
            try {
                raw = IndexVerifier.decodePublicKey(key.publicKey);
            } catch (ContentException e) {
                continue;
            }
            String id = IndexVerifier.keyId(raw);
            if (!id.equals(key.keyId) || ids.contains(id)) continue;
            if (out.size() >= ContentConfig.FEED_MAX_KEYS) break;
            ids.add(id);
            out.add(key.publicKey.trim());
        }
        return out;
    }

    /** {@code maxAgeDays}, or the default (60) when it is outside 1..365. */
    public int effectiveMaxAgeDays() {
        return maxAgeDays >= 1 && maxAgeDays <= 365 ? (int) maxAgeDays : ContentConfig.FEED_DEFAULT_MAX_AGE_DAYS;
    }

    /**
     * A feed mirror base: {@code https://} with a host, no credentials, query or fragment, and a
     * path ending in {@code /}; surrounding whitespace is refused. Returns a normalized form for
     * comparisons, or null.
     */
    static String normalizedMirror(String text) {
        if (text == null || text.isEmpty() || !text.equals(ContentText.trim(text))) return null;
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getRawPath();
            boolean valid = scheme != null && scheme.equalsIgnoreCase("https")
                    && host != null && !host.isEmpty()
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && path != null && path.endsWith("/");
            if (!valid) return null;
            int port = uri.getPort() == 443 ? -1 : uri.getPort();
            return "https://" + host.toLowerCase(Locale.ROOT) + (port < 0 ? "" : ":" + port) + path;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
