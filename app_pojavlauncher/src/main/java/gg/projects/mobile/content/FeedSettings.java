package gg.projects.mobile.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where to read the Discord bot's feed and whom to trust, from a signed config that enables it
 * (pl-content's {@code FeedSettings}). Debug builds may point it at local mirrors and one more
 * throwaway key ({@link ContentSources}); nothing but the config turns the feed on.
 */
public final class FeedSettings {
    /** Mirror bases ending in {@code /}, tried in order. */
    public final List<String> mirrors;
    /** Keys (base64 of 32 bytes) allowed to sign {@code feed.json}. */
    public final List<String> keys;
    /** Feed items published longer ago than this are hidden. */
    public final int maxAgeDays;

    FeedSettings(List<String> mirrors, List<String> keys, int maxAgeDays) {
        this.mirrors = Collections.unmodifiableList(new ArrayList<>(mirrors));
        this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        this.maxAgeDays = maxAgeDays;
    }

    /**
     * The settings while {@code config} enables the feed and leaves at least one mirror to ask and
     * one key to trust; null otherwise (then the feed is never requested and its cache is deleted).
     */
    public static FeedSettings of(LauncherConfig config, ContentSources sources) {
        NewsFeedConfig feed = config == null ? null : config.newsFeed;
        if (feed == null || !feed.enabled) return null;
        List<String> mirrors = feed.mirrorBases();
        List<String> keys = feed.trustedKeys();
        if (sources != null && !sources.feedMirrors.isEmpty()) mirrors = new ArrayList<>(sources.feedMirrors);
        if (sources != null && sources.feedTestKey != null && !keys.contains(sources.feedTestKey)) {
            keys = new ArrayList<>(keys);
            keys.add(sources.feedTestKey);
        }
        if (mirrors.isEmpty() || keys.isEmpty()) return null;
        return new FeedSettings(mirrors, keys, feed.effectiveMaxAgeDays());
    }

    /** Oldest {@code publishedAt} a feed item may have at {@code now} to be shown. */
    public long ageFloor(long now) {
        return now - maxAgeDays * 86_400_000L;
    }
}
