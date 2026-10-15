package gg.projects.mobile.content;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Where Projects content lives and which keys may sign it (docs/content-format.md, v1). */
public final class ContentConfig {
    private ContentConfig() {}

    /** Mirror base URLs, tried in this order. */
    public static final List<String> MIRRORS = Collections.unmodifiableList(Arrays.asList(
            "https://cdn.projects.gg/v1/",
            "https://raw.githubusercontent.com/projects-gg/launcher-content/main/v1/"));

    /**
     * Trusted Ed25519 public keys, base64 of the 32 raw bytes. Key rotation: ship a build that
     * trusts old and new keys, wait for adoption, then sign with the new key.
     */
    public static final List<String> TRUSTED_KEYS = Collections.unmodifiableList(Arrays.asList(
            "muj3CSu3m9jpB7fUP5TbkRSnISGEH13XbTt6agmIK68=" // keyId 5125c0d365b019ce
    ));

    public static final int ROOT_INDEX_SCHEMA = 1;
    public static final int LAUNCHER_CONFIG_SCHEMA = 1;
    public static final int NEWS_FEED_SCHEMA = 1;
    public static final int MRPACK_FORMAT_VERSION = 1;

    public static final long MAX_INDEX_BYTES = 1L << 20;
    public static final long MAX_SIGNATURE_BYTES = 4096;
    public static final long MAX_DOCUMENT_BYTES = 8L << 20;
    public static final long MAX_MEDIA_BYTES = 16L << 20;

    /** Index files are served with max-age=60; refreshing more often only costs data. */
    public static final long MIN_REFRESH_INTERVAL_MS = 60_000L;
}
