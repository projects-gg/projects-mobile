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
            "muj3CSu3m9jpB7fUP5TbkRSnISGEH13XbTt6agmIK68=", // keyId 5125c0d365b019ce
            "qTmm+6djdFYCmoHKop6DuvVMgUsHk1EzdZZ9wDhXdOM=" // offline backup, keyId b63fd046213136eb
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

    /**
     * The Minecraft version launchers recommend while the config names none (or an invalid one):
     * desktop 2.0's built-in default ({@code pl_content::DEFAULT_RECOMMENDED_MINECRAFT_VERSION}).
     */
    public static final String DEFAULT_RECOMMENDED_MINECRAFT_VERSION = "26.1.2";
    /** Longest Minecraft version id the content format carries, in UTF-8 bytes. */
    public static final int MAX_MINECRAFT_VERSION_BYTES = 32;

    // The Discord bot's news feed (content-format.md, "News feed (Discord bot)").

    /** Highest {@code feed.json} schema this build understands. */
    public static final int FEED_SCHEMA = 1;
    public static final long FEED_MAX_BYTES = 512L * 1024;
    public static final long FEED_MAX_SIGNATURE_BYTES = 16L * 1024;
    public static final int FEED_MAX_ITEMS = 50;
    public static final int FEED_MAX_EVENTS = 20;
    /** Largest cover a feed item may reference. */
    public static final long FEED_MAX_MEDIA_BYTES = 2L * 1024 * 1024;
    /** Longest title, summary and body, in Unicode scalar values. */
    public static final int FEED_MAX_TITLE_CHARS = 120;
    public static final int FEED_MAX_SUMMARY_CHARS = 300;
    public static final int FEED_MAX_BODY_CHARS = 8_000;
    /** Most mirrors and keys {@code newsFeed} may list (clients use the first valid ones). */
    public static final int FEED_MAX_MIRRORS = 4;
    public static final int FEED_MAX_KEYS = 4;
    /** {@code newsFeed.maxAgeDays} when it is missing or outside 1..365. */
    public static final int FEED_DEFAULT_MAX_AGE_DAYS = 60;
    /** How long a feed event without {@code endsAt} counts as live. */
    public static final long OPEN_EVENT_MS = 3L * 60 * 60 * 1000;
    /** M17: most entries the feed's {@code minecraft[]} may hold (more: the array is ignored). */
    public static final int FEED_MAX_MINECRAFT = 10;
    /** M17: longest a Minecraft entry may stay ({@code expiresAt - publishedAt}). */
    public static final long MINECRAFT_MAX_LIFETIME_MS = 90L * 24 * 60 * 60 * 1000;
    /** M17: longest Minecraft version id an entry may name. */
    public static final int MINECRAFT_MAX_VERSION_CHARS = 40;
}
