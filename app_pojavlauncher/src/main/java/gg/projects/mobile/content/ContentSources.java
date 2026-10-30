package gg.projects.mobile.content;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where content comes from and which keys may sign it. Release builds always use
 * {@link #production()}. Debug builds may read a local override for end-to-end tests (the
 * mobile counterpart of the desktop's PROJECTS_CONTENT_MIRRORS, PROJECTS_CONTENT_TEST_KEY,
 * PROJECTS_FEED_MIRRORS and PROJECTS_FEED_TEST_KEY): other content mirrors plus one throwaway
 * public key, trusted in addition to the embedded keys, and for the Discord bot's feed other
 * mirrors (replacing the config's) plus one more trusted feed key. Content still has to carry a
 * valid signature, and the override never turns the feed on: only the signed config does.
 */
public final class ContentSources {
    public final List<String> mirrors;
    public final List<String> trustedKeys;
    /** Feed mirror bases that replace {@code newsFeed.mirrors} (debug only); empty: the config's. */
    public final List<String> feedMirrors;
    /** One more key (base64) trusted for the feed (debug only), or null. */
    public final String feedTestKey;
    /** True when a debug override is in use (the caches then live in their own folders). */
    public final boolean override;
    /** APK-UPD: mirror bases for the Android release channel (debug only); empty: the content mirrors. */
    public final List<String> updateMirrors;
    /** APK-UPD: one minisign public key trusted for the Android channel (debug only), or null. */
    public final String updateTestKey;

    private ContentSources(List<String> mirrors, List<String> trustedKeys, List<String> feedMirrors,
                           String feedTestKey, boolean override, List<String> updateMirrors, String updateTestKey) {
        this.mirrors = Collections.unmodifiableList(mirrors);
        this.trustedKeys = Collections.unmodifiableList(trustedKeys);
        this.feedMirrors = Collections.unmodifiableList(feedMirrors);
        this.feedTestKey = feedTestKey;
        this.override = override;
        this.updateMirrors = Collections.unmodifiableList(updateMirrors);
        this.updateTestKey = updateTestKey;
    }

    public static ContentSources production() {
        return new ContentSources(new ArrayList<>(ContentConfig.MIRRORS), new ArrayList<>(ContentConfig.TRUSTED_KEYS),
                new ArrayList<String>(), null, false, new ArrayList<String>(), null);
    }

    /**
     * {@code projects-debug.json}: {@code {"mirrors": ["http://10.0.2.2:8789/v1/"], "testKey": "<base64>",
     * "feedMirrors": ["http://10.0.2.2:8790/v1/"], "feedTestKey": "<base64>",
     * "updateMirrors": ["http://10.0.2.2:8791/v1/"], "updateTestKey": "<minisign key line>"}}; every field optional.
     */
    @Keep
    static final class DebugFile {
        List<String> mirrors;
        String testKey;
        List<String> feedMirrors;
        String feedTestKey;
        List<String> updateMirrors;
        String updateTestKey;
    }

    /** Production sources with a debug override applied; fails on a malformed override. */
    public static ContentSources withDebugOverride(byte[] json) throws ContentException {
        DebugFile file = ContentJson.parse(json, DebugFile.class, "projects-debug.json");
        List<String> mirrors = bases(file.mirrors);
        if (mirrors.isEmpty()) mirrors.addAll(ContentConfig.MIRRORS);
        List<String> keys = new ArrayList<>(ContentConfig.TRUSTED_KEYS);
        if (file.testKey != null && !file.testKey.trim().isEmpty()) {
            IndexVerifier.decodePublicKey(file.testKey.trim());
            keys.add(file.testKey.trim());
        }
        String feedKey = null;
        if (file.feedTestKey != null && !file.feedTestKey.trim().isEmpty()) {
            IndexVerifier.decodePublicKey(file.feedTestKey.trim());
            feedKey = file.feedTestKey.trim();
        }
        String updateKey = null;
        if (file.updateTestKey != null && !file.updateTestKey.trim().isEmpty()) {
            try {
                gg.projects.mobile.update.Minisign.publicKey(file.updateTestKey.trim());
            } catch (gg.projects.mobile.update.UpdateException e) {
                throw new ContentException(ContentException.Kind.INVALID, "updateTestKey is not a minisign public key");
            }
            updateKey = file.updateTestKey.trim();
        }
        return new ContentSources(mirrors, keys, bases(file.feedMirrors), feedKey, true, bases(file.updateMirrors), updateKey);
    }

    /** {@code http://} or {@code https://} bases, each ending in {@code /}. */
    private static List<String> bases(List<String> list) throws ContentException {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (String mirror : list) {
            if (!ContentPaths.isHttpUrl(mirror)) throw new ContentException(ContentException.Kind.INVALID, "bad mirror " + mirror);
            out.add(mirror.endsWith("/") ? mirror : mirror + "/");
        }
        return out;
    }
}
