package gg.projects.mobile.content;

/** Verified content as the UI sees it. Everything may be absent; the UI shows empty states. */
public final class Snapshot {
    /** Verified root index, or null when no content was ever verified. */
    public final RootIndex index;
    public final LauncherConfig config;
    public final NewsFeed news;
    /** True when the mirrors could not be reached (or refused) and this is the cached copy. */
    public final boolean offline;
    /** When the index was fetched (epoch ms), 0 when there is no index. */
    public final long fetchedAt;
    /** Mirror base URL that served the index. */
    public final String mirror;
    /** Why the last refresh failed, for logs and "copy details". */
    public final ContentException lastError;

    Snapshot(RootIndex index, LauncherConfig config, NewsFeed news, boolean offline,
             long fetchedAt, String mirror, ContentException lastError) {
        this.index = index;
        this.config = config;
        this.news = news;
        this.offline = offline;
        this.fetchedAt = fetchedAt;
        this.mirror = mirror;
        this.lastError = lastError;
    }

    static Snapshot empty(ContentException lastError) {
        return new Snapshot(null, null, null, true, 0, null, lastError);
    }

    Snapshot asOffline(ContentException error) {
        return new Snapshot(index, config, news, true, fetchedAt, mirror, error);
    }

    public boolean hasContent() {
        return index != null;
    }

    /** The pack new installs should follow: {@code recommendedPack}, else the first published one. */
    public RootIndex.PackRef officialPack() {
        if (index == null) return null;
        RootIndex.PackRef pack = config == null ? null : index.pack(config.recommendedPack);
        if (pack == null && index.packs != null && !index.packs.isEmpty()) pack = index.packs.get(0);
        return pack;
    }
}
