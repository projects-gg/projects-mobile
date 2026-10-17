package gg.projects.mobile.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.MainThread;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.NewsSeen;

/**
 * {@link NewsSeen} kept in the app's preferences, with listeners for the dots on screen. The first
 * read sets the watermark, so a fresh install (or an update to the version that brought the dot)
 * lights up nothing that was already published. Main thread only.
 */
public final class NewsSeenStore {
    private NewsSeenStore() {}

    private static final String PREFS = "projects_news_seen";
    private static final String KEY = "state";
    private static final List<Runnable> sListeners = new ArrayList<>();
    private static NewsSeen sSeen;

    @MainThread
    public static NewsSeen get(Context context) {
        if (sSeen == null) {
            NewsSeen stored = NewsSeen.fromJson(prefs(context).getString(KEY, null));
            sSeen = stored.start(System.currentTimeMillis());
            if (sSeen != stored) save(context);
        }
        return sSeen;
    }

    /** The article was opened: its dot goes out. */
    @MainThread
    public static void markRead(Context context, NewsFeed.NewsItem item) {
        update(context, get(context).markRead(item.id, System.currentTimeMillis()));
    }

    /** The news list showed {@code items}: what they published counts as seen from now on. */
    @MainThread
    public static void advance(Context context, List<NewsFeed.NewsItem> items) {
        if (items.isEmpty()) return;
        update(context, get(context).advance(NewsSeen.newestPublished(items), System.currentTimeMillis()));
    }

    @MainThread
    public static void addListener(Runnable listener) {
        sListeners.add(listener);
    }

    @MainThread
    public static void removeListener(Runnable listener) {
        sListeners.remove(listener);
    }

    private static void update(Context context, NewsSeen next) {
        if (next == sSeen) return;
        sSeen = next;
        save(context);
        for (Runnable listener : new ArrayList<>(sListeners)) listener.run();
    }

    private static void save(Context context) {
        prefs(context).edit().putString(KEY, sSeen.toJson()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
