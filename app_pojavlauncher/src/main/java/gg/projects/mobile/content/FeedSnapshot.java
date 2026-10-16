package gg.projects.mobile.content;

/** A verified Discord feed (or none) with where it came from, as the home screen sees it. */
public final class FeedSnapshot {
    /** The verified feed, or null when none is available. */
    public final BotFeed feed;
    /** The settings it was verified and fetched with (covers come from these mirrors). */
    public final FeedSettings settings;
    /** Mirror base that served the feed last; covers are tried there first. */
    public final String mirror;
    public final long fetchedAt;
    /** The refresh failed (or did not run) and this is the cached feed. */
    public final boolean fromCache;
    /** Why the last refresh failed, for logs only. */
    public final ContentException lastError;

    FeedSnapshot(BotFeed feed, FeedSettings settings, String mirror, long fetchedAt, boolean fromCache,
                 ContentException lastError) {
        this.feed = feed;
        this.settings = settings;
        this.mirror = mirror;
        this.fetchedAt = fetchedAt;
        this.fromCache = fromCache;
        this.lastError = lastError;
    }

    static FeedSnapshot none(FeedSettings settings, ContentException error) {
        return new FeedSnapshot(null, settings, null, 0, true, error);
    }

    /** The feed's sequence, or -1 without a feed (to tell whether a refresh brought something new). */
    public long sequence() {
        return feed == null ? -1 : feed.sequence;
    }
}
