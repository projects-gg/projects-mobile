package gg.projects.mobile.content;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where content comes from and which keys may sign it. Release builds always use
 * {@link #production()}. Debug builds may read a local override for end-to-end tests (the
 * mobile counterpart of the desktop's PROJECTS_CONTENT_MIRRORS and PROJECTS_CONTENT_TEST_KEY):
 * other mirrors plus one throwaway public key, trusted in addition to the embedded keys. Content
 * still has to carry a valid signature.
 */
public final class ContentSources {
    public final List<String> mirrors;
    public final List<String> trustedKeys;
    /** True when a debug override is in use (the cache then lives in its own folder). */
    public final boolean override;

    private ContentSources(List<String> mirrors, List<String> trustedKeys, boolean override) {
        this.mirrors = Collections.unmodifiableList(mirrors);
        this.trustedKeys = Collections.unmodifiableList(trustedKeys);
        this.override = override;
    }

    public static ContentSources production() {
        return new ContentSources(new ArrayList<>(ContentConfig.MIRRORS), new ArrayList<>(ContentConfig.TRUSTED_KEYS), false);
    }

    /** {@code projects-debug.json}: {@code {"mirrors": ["http://10.0.2.2:8789/v1/"], "testKey": "<base64>"}}. */
    @Keep
    static final class DebugFile {
        List<String> mirrors;
        String testKey;
    }

    /** Production sources with a debug override applied; fails on a malformed override. */
    public static ContentSources withDebugOverride(byte[] json) throws ContentException {
        DebugFile file = ContentJson.parse(json, DebugFile.class, "projects-debug.json");
        List<String> mirrors = new ArrayList<>();
        if (file.mirrors != null) {
            for (String mirror : file.mirrors) {
                if (!ContentPaths.isHttpUrl(mirror)) throw new ContentException(ContentException.Kind.INVALID, "bad mirror " + mirror);
                mirrors.add(mirror.endsWith("/") ? mirror : mirror + "/");
            }
        }
        if (mirrors.isEmpty()) mirrors.addAll(ContentConfig.MIRRORS);
        List<String> keys = new ArrayList<>(ContentConfig.TRUSTED_KEYS);
        if (file.testKey != null && !file.testKey.trim().isEmpty()) {
            IndexVerifier.decodePublicKey(file.testKey.trim());
            keys.add(file.testKey.trim());
        }
        return new ContentSources(mirrors, keys, true);
    }
}
