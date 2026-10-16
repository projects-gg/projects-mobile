package gg.projects.mobile.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The home screen's news, patch notes and events: Studio's signed content merged with the Discord
 * bot's feed (pl-content's {@code merge_news}, {@code newest_patch_notes}, {@code merge_events} and
 * {@code card_order}). Studio always wins: a feed item or event whose id Studio also publishes is
 * ignored, even while Studio's own entry is not shown (hidden, scheduled or expired). That is how
 * Studio edits a feed entry (its copy with the same id shows instead) and hides one.
 */
public final class HomeContent {
    /** Merged news in display order. */
    public final List<NewsFeed.NewsItem> news;
    /** The newest visible patch notes from either source, or null. */
    public final NewsFeed.NewsItem patchNotes;
    /** Events that have not ended at {@link #now}, in event card order (the card shows the first). */
    public final List<Event> events;
    public final long now;

    private HomeContent(List<NewsFeed.NewsItem> news, NewsFeed.NewsItem patchNotes, List<Event> events, long now) {
        this.news = Collections.unmodifiableList(news);
        this.patchNotes = patchNotes;
        this.events = Collections.unmodifiableList(events);
        this.now = now;
    }

    public enum Phase { UPCOMING, LIVE, ENDED }

    /** An event of the home screen, from the config or the feed. */
    public static final class Event {
        public final String id;
        public final String title;
        public final long startsAt;
        /** {@code endsAt}, or three hours after the start for a feed event that did not say. */
        public final long endsAt;
        /** The feed did not say when it ends: shown as started, without time left. */
        public final boolean openEnded;
        public final String url;
        /** The news item about the event, kept only while it is on the home screen. */
        public final String newsId;
        public final int priority;
        public final boolean fromFeed;

        public Event(String id, String title, long startsAt, long endsAt, boolean openEnded, String url, String newsId,
                     int priority, boolean fromFeed) {
            this.id = id;
            this.title = title;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
            this.openEnded = openEnded;
            this.url = url;
            this.newsId = newsId;
            this.priority = priority;
            this.fromFeed = fromFeed;
        }

        public Phase phase(long now) {
            if (now < startsAt) return Phase.UPCOMING;
            if (now < endsAt) return Phase.LIVE;
            return Phase.ENDED;
        }

        public boolean isLive(long now) {
            return phase(now) == Phase.LIVE;
        }
    }

    /** Everything the home screen shows at {@code now}; {@code feed} may be null or empty. */
    public static HomeContent of(NewsFeed studio, LauncherConfig config, FeedSnapshot feed, long now) {
        BotFeed bot = feed == null ? null : feed.feed;
        int maxAge = feed == null || feed.settings == null ? ContentConfig.FEED_DEFAULT_MAX_AGE_DAYS : feed.settings.maxAgeDays;
        List<NewsFeed.NewsItem> news = mergeNews(studio, bot, maxAge, now);
        List<LauncherConfig.EventCountdown> configEvents = config == null
                ? Collections.<LauncherConfig.EventCountdown>emptyList() : config.events;
        return new HomeContent(news, newestPatchNotes(news), mergeEvents(configEvents, bot, news, now), now);
    }

    /** The item with {@code id} while it is in the merged news, else null. */
    public NewsFeed.NewsItem find(String id) {
        if (id == null) return null;
        for (NewsFeed.NewsItem item : news) if (item.id.equals(id)) return item;
        return null;
    }

    public boolean isEmpty() {
        return news.isEmpty() && events.isEmpty();
    }

    /**
     * The news column: Studio's visible news plus the feed's visible items published at most
     * {@code maxAgeDays} ago whose id Studio does not publish, in {@link NewsFeed#ORDER}.
     */
    public static List<NewsFeed.NewsItem> mergeNews(NewsFeed studio, BotFeed feed, int maxAgeDays, long now) {
        List<NewsFeed.NewsItem> merged = new ArrayList<>();
        if (studio != null) {
            for (NewsFeed.NewsItem item : studio.items) if (item.isVisible(now)) merged.add(item);
        }
        if (feed != null) {
            long floor = now - maxAgeDays * 86_400_000L;
            for (NewsFeed.NewsItem item : feed.items) {
                if (studio != null && studio.publishes(item.id)) continue;
                if (item.isVisible(now) && item.publishedAtMillis() >= floor) merged.add(item);
            }
        }
        Collections.sort(merged, NewsFeed.ORDER);
        return merged;
    }

    /** The newest item with category {@code patchNotes} (by {@code publishedAt}, then id); pinning does not count. */
    public static NewsFeed.NewsItem newestPatchNotes(List<NewsFeed.NewsItem> news) {
        NewsFeed.NewsItem best = null;
        for (NewsFeed.NewsItem item : news) {
            if (!item.isPatchNotes()) continue;
            if (best == null || item.publishedAtMillis() > best.publishedAtMillis()
                    || (item.publishedAtMillis() == best.publishedAtMillis() && ContentText.compare(item.id, best.id) < 0)) {
                best = item;
            }
        }
        return best;
    }

    /**
     * Config and feed events that have not ended, in card order. Hidden config events are left
     * out, and a feed event whose id a config event uses (hidden or not) is ignored. An event's
     * {@code newsId} is kept only when that item is in {@code news}.
     */
    public static List<Event> mergeEvents(List<LauncherConfig.EventCountdown> config, BotFeed feed,
                                          List<NewsFeed.NewsItem> news, long now) {
        Set<String> newsIds = new HashSet<>();
        for (NewsFeed.NewsItem item : news) newsIds.add(item.id);
        List<Event> events = new ArrayList<>();
        Set<String> configIds = new HashSet<>();
        for (LauncherConfig.EventCountdown event : config) {
            configIds.add(event.id);
            if (event.hidden) continue;
            events.add(new Event(event.id, event.title, event.startsAt, event.endsAt, false, event.url,
                    newsIds.contains(event.newsId) ? event.newsId : null, event.priority, false));
        }
        if (feed != null) {
            for (BotFeed.Event event : feed.events) {
                if (configIds.contains(event.id)) continue;
                events.add(new Event(event.id, event.title, event.startsAt, event.end(), event.endsAt == null, event.url,
                        newsIds.contains(event.newsId) ? event.newsId : null, event.priority, true));
            }
        }
        return cardEvents(events, now);
    }

    /** The events that have not ended at {@code now}, in card order (the card shows the first). */
    public static List<Event> cardEvents(List<Event> events, final long now) {
        List<Event> out = new ArrayList<>();
        for (Event event : events) if (event.phase(now) != Phase.ENDED) out.add(event);
        Collections.sort(out, new Comparator<Event>() {
            @Override
            public int compare(Event a, Event b) {
                return cardOrder(a, b, now);
            }
        });
        return out;
    }

    /**
     * Event card order at {@code now}: live events before upcoming ones; live ones by the higher
     * {@code priority}, then the later start (a short event overlays a long one while it runs and
     * the long one comes back when it ends); upcoming ones by the sooner start, then the higher
     * priority; ties: config before feed, then the smaller id.
     */
    public static int cardOrder(Event a, Event b, long now) {
        boolean aLive = a.isLive(now);
        boolean bLive = b.isLive(now);
        if (aLive != bLive) return aLive ? -1 : 1;
        int order;
        if (aLive) {
            order = Integer.compare(b.priority, a.priority);
            if (order == 0) order = Long.compare(b.startsAt, a.startsAt);
        } else {
            order = Long.compare(a.startsAt, b.startsAt);
            if (order == 0) order = Integer.compare(b.priority, a.priority);
        }
        if (order != 0) return order;
        if (a.fromFeed != b.fromFeed) return a.fromFeed ? 1 : -1;
        return ContentText.compare(a.id, b.id);
    }

    /** The next moment the card may change (the soonest start or end after {@code now}), or -1. */
    public static long nextBoundary(List<Event> events, long now) {
        long next = -1;
        for (Event event : events) {
            for (long t : new long[]{event.startsAt, event.endsAt}) {
                if (t > now && (next < 0 || t < next)) next = t;
            }
        }
        return next;
    }
}
