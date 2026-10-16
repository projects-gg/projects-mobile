package gg.projects.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.content.ContentConfig;
import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ContentRepository;
import gg.projects.mobile.content.ContentSources;
import gg.projects.mobile.content.ContentStore;
import gg.projects.mobile.content.FeedRepository;
import gg.projects.mobile.content.FeedSettings;
import gg.projects.mobile.content.FeedSnapshot;
import gg.projects.mobile.content.FeedStore;
import gg.projects.mobile.content.HttpFetcher;
import gg.projects.mobile.content.IndexVerifier;
import gg.projects.mobile.content.MediaRef;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.Snapshot;

/**
 * App-wide access to the signed Projects content: one repository, one background thread, and
 * the latest snapshot for the UI (cached copy first, then a refresh). The Discord bot's news feed
 * follows every content refresh (no polling of its own) while the signed config enables it; the
 * snapshot handed to the UI carries the feed verified against that config's keys.
 */
public final class ProjectsContent {
    private ProjectsContent() {}

    private static final String TAG = "ProjectsContent";

    public interface Listener {
        /** Called on the main thread with the latest snapshot; {@code refreshing} while a refresh runs. */
        void onContent(@Nullable Snapshot snapshot, boolean refreshing);
    }

    public interface CoverCallback {
        /** Called on the main thread; null when the cover is not available. */
        void onCover(@Nullable Bitmap bitmap);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService MEDIA_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final List<Listener> sListeners = new ArrayList<>();
    /** Decoded covers by cache name and width, so a re-bound card or row shows its picture at once. */
    private static final LruCache<String, Bitmap> COVERS = new LruCache<String, Bitmap>(24 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static ContentRepository sRepository;
    private static FeedRepository sFeedRepository;
    private static ContentSources sSources;
    /** Written on the main thread only; volatile so background work can read it. */
    private static volatile Snapshot sSnapshot;
    private static boolean sRefreshing;
    /** When the last refresh finished, successful or not (index files are cached for a minute). */
    private static volatile long sLastAttempt;

    public static synchronized ContentRepository repository(Context context) {
        if (sRepository == null) {
            Context app = context.getApplicationContext();
            ContentSources sources = sources(app);
            sSources = sources;
            IndexVerifier verifier;
            try {
                verifier = new IndexVerifier(sources.trustedKeys);
            } catch (ContentException e) {
                throw new IllegalStateException("content keys are invalid", e);
            }
            // Test content never mixes with the real cache.
            File root = new File(app.getFilesDir(), sources.override ? "projects-content-debug" : "projects-content");
            sRepository = new ContentRepository(
                    new ContentStore(root),
                    new HttpFetcher.UrlConnection(userAgent()),
                    verifier,
                    sources.mirrors,
                    System::currentTimeMillis);
        }
        return sRepository;
    }

    /** The Discord feed's repository (its own cache folder, the same HTTP client and timeouts). */
    public static synchronized FeedRepository feedRepository(Context context) {
        if (sFeedRepository == null) {
            repository(context);
            Context app = context.getApplicationContext();
            File root = new File(app.getFilesDir(), sSources.override ? "projects-feed-debug" : "projects-feed");
            sFeedRepository = new FeedRepository(new FeedStore(root), new HttpFetcher.UrlConnection(userAgent()),
                    System::currentTimeMillis);
        }
        return sFeedRepository;
    }

    /** The feed settings of {@code snapshot}'s config (with the debug override), or null while the feed is off. */
    @Nullable
    private static FeedSettings feedSettings(Snapshot snapshot) {
        return snapshot == null ? null : FeedSettings.of(snapshot.config, sSources);
    }

    /**
     * The embedded mirrors and keys. Debug builds only: {@code projects-debug.json} in the app's
     * external files folder may name other mirrors and one throwaway test key, for the content and
     * for the Discord feed (local end-to-end tests, like the desktop's PROJECTS_CONTENT_* and
     * PROJECTS_FEED_* variables). Release builds never read it.
     */
    private static ContentSources sources(Context app) {
        if (BuildConfig.DEBUG) {
            File dir = app.getExternalFilesDir(null);
            File file = dir == null ? null : new File(dir, DEBUG_OVERRIDE_FILE);
            if (file != null && file.isFile()) {
                try {
                    ContentSources sources = ContentSources.withDebugOverride(AtomicFiles.read(file, 64 * 1024));
                    Log.w(TAG, "debug content override in use: " + sources.mirrors + " feed " + sources.feedMirrors);
                    return sources;
                } catch (IOException | ContentException e) {
                    Log.w(TAG, "ignoring a broken " + DEBUG_OVERRIDE_FILE, e);
                }
            }
        }
        return ContentSources.production();
    }

    private static final String DEBUG_OVERRIDE_FILE = "projects-debug.json";

    public static String userAgent() {
        return "ProjectsLauncher-Android/" + BuildConfig.VERSION_NAME;
    }

    @MainThread
    public static void addListener(Listener listener) {
        sListeners.add(listener);
        listener.onContent(sSnapshot, sRefreshing);
    }

    @MainThread
    public static void removeListener(Listener listener) {
        sListeners.remove(listener);
    }

    @MainThread
    @Nullable
    public static Snapshot current() {
        return sSnapshot;
    }

    /** {@code snapshot} with its config's verified cached feed attached (background threads only). */
    private static Snapshot withCachedFeed(FeedRepository feeds, Snapshot snapshot) {
        FeedSettings settings = feedSettings(snapshot);
        FeedSnapshot feed = null;
        if (settings != null) {
            try {
                feed = feeds.loadCached(settings);
            } catch (RuntimeException e) {
                Log.w(TAG, "ignoring the news feed cache", e);
            }
        }
        return snapshot.withFeed(feed);
    }

    /**
     * Shows the cached snapshot right away (first call) and refreshes from the mirrors unless the
     * last refresh finished less than a minute ago. {@code force} ("Tekrar dene") skips that. The
     * news feed refresh follows a successful content refresh.
     */
    @MainThread
    public static void refresh(Context context, boolean force) {
        if (sRefreshing) return;
        boolean recent = sSnapshot != null && isRecent();
        if (recent && !force) return;
        final ContentRepository repository = repository(context);
        final FeedRepository feeds = feedRepository(context);
        final boolean needCache = sSnapshot == null;
        sRefreshing = true;
        notifyListeners();
        EXECUTOR.execute(() -> {
            if (needCache) {
                Snapshot loaded = repository.loadCached();
                final Snapshot cached = loaded.hasContent() ? withCachedFeed(feeds, loaded) : loaded;
                MAIN.post(() -> {
                    if (sSnapshot == null && cached.hasContent()) {
                        sSnapshot = cached;
                        notifyListeners();
                    }
                });
            }
            Snapshot refreshed = repository.refresh();
            if (refreshed.lastError != null) Log.i(TAG, "content refresh failed: " + refreshed.lastError);
            // Verified against the (possibly new) config's keys before it is shown.
            final Snapshot latest = withCachedFeed(feeds, refreshed);
            sLastAttempt = System.currentTimeMillis();
            MAIN.post(() -> {
                sSnapshot = latest;
                sRefreshing = false;
                notifyListeners();
            });
            if (!latest.offline) refreshFeed(feeds, latest);
        });
    }

    /**
     * Refreshes the Discord feed for {@code latest}'s config and downloads the covers of the
     * items it shows, then updates the UI when something changed. A feed the config disables is
     * never requested and its cache is deleted. Failures keep what is on screen.
     */
    private static void refreshFeed(FeedRepository feeds, final Snapshot latest) {
        try {
            FeedSettings settings = feedSettings(latest);
            if (settings == null) {
                if (feeds.hasCache()) feeds.clear();
                return;
            }
            FeedSnapshot fresh = feeds.refresh(settings);
            if (fresh.lastError != null) Log.i(TAG, "news feed refresh failed: " + fresh.lastError);
            if (fresh.feed != null && !fresh.feed.problems.isEmpty()) {
                Log.w(TAG, "news feed entries dropped or repaired: " + fresh.feed.problems);
            }
            int downloaded = feeds.prefetch(fresh, System.currentTimeMillis());
            long before = latest.feed == null ? -1 : latest.feed.sequence();
            if (fresh.sequence() == before && downloaded == 0) return;
            final FeedSnapshot shown = fresh.feed == null ? null : fresh;
            MAIN.post(() -> {
                // Only onto the snapshot this feed was verified for; a newer refresh brings its own.
                Snapshot current = sSnapshot;
                if (current != null && current.config == latest.config) {
                    sSnapshot = current.withFeed(shown);
                    notifyListeners();
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "news feed refresh failed", e);
        }
    }

    /**
     * Snapshot for decisions that may wait for the network (background only): the known one when
     * it is online or was refreshed less than a minute ago, otherwise a refresh.
     */
    public static Snapshot latestBlocking(Context context) {
        Snapshot known = sSnapshot;
        if (known != null && (!known.offline || isRecent())) return known;
        Snapshot refreshed = repository(context).refresh();
        final Snapshot shown = withCachedFeed(feedRepository(context), refreshed);
        sLastAttempt = System.currentTimeMillis();
        MAIN.post(() -> {
            if (!shown.offline || sSnapshot == null) {
                sSnapshot = shown;
                notifyListeners();
            }
        });
        return refreshed;
    }

    private static boolean isRecent() {
        return System.currentTimeMillis() - sLastAttempt < ContentConfig.MIN_REFRESH_INTERVAL_MS;
    }

    @MainThread
    private static void notifyListeners() {
        for (Listener listener : new ArrayList<>(sListeners)) listener.onContent(sSnapshot, sRefreshing);
    }

    private static String coverKey(String cacheName, int widthPx) {
        return cacheName + "@" + widthPx;
    }

    /** A cover already decoded for this width, or null. */
    @MainThread
    @Nullable
    public static Bitmap memoryCover(String cacheName, int widthPx) {
        return COVERS.get(coverKey(cacheName, widthPx));
    }

    /**
     * Loads the verified cover of {@code item} in the background: Studio covers from the content
     * mirrors, Discord feed covers from the feed mirrors (both by their SHA-256 and size).
     */
    @MainThread
    public static void loadNewsCover(Context context, final Snapshot snapshot, final NewsFeed.NewsItem item,
                                     final int widthPx, final CoverCallback callback) {
        final MediaRef image = item.cover == null ? null : item.cover.displayImage();
        if (image == null || !image.isValid()) {
            callback.onCover(null);
            return;
        }
        final ContentRepository repository = repository(context);
        final FeedRepository feeds = feedRepository(context);
        final FeedSnapshot feed = snapshot.feed;
        MEDIA_EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                File file;
                if (item.fromFeed()) {
                    if (feed == null || feed.feed == null) throw new ContentException(ContentException.Kind.INVALID, "no feed");
                    file = feeds.media(feed, image);
                } else {
                    file = repository.media(snapshot, image);
                }
                bitmap = decode(file, widthPx);
            } catch (ContentException e) {
                Log.i(TAG, "cover unavailable: " + e);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "cover could not be decoded", e);
            }
            final Bitmap result = bitmap;
            MAIN.post(() -> {
                if (result != null) COVERS.put(coverKey(image.cacheFileName(), widthPx), result);
                callback.onCover(result);
            });
        });
    }

    private static Bitmap decode(File file, int targetWidthPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= Math.max(1, targetWidthPx)) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }
}
