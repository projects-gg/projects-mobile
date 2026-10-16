package gg.projects.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

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
import gg.projects.mobile.content.HttpFetcher;
import gg.projects.mobile.content.IndexVerifier;
import gg.projects.mobile.content.MediaRef;
import gg.projects.mobile.content.Snapshot;

/**
 * App-wide access to the signed Projects content: one repository, one background thread, and
 * the latest snapshot for the UI (cached copy first, then a refresh).
 */
public final class ProjectsContent {
    private ProjectsContent() {}

    private static final String TAG = "ProjectsContent";

    public interface Listener {
        /** Called on the main thread with the latest snapshot; {@code refreshing} while a refresh runs. */
        void onContent(@Nullable Snapshot snapshot, boolean refreshing);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService MEDIA_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final List<Listener> sListeners = new ArrayList<>();

    private static ContentRepository sRepository;
    /** Written on the main thread only; volatile so background work can read it. */
    private static volatile Snapshot sSnapshot;
    private static boolean sRefreshing;
    /** When the last refresh finished, successful or not (index files are cached for a minute). */
    private static volatile long sLastAttempt;

    public static synchronized ContentRepository repository(Context context) {
        if (sRepository == null) {
            Context app = context.getApplicationContext();
            ContentSources sources = sources(app);
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

    /**
     * The embedded mirrors and keys. Debug builds only: {@code projects-debug.json} in the app's
     * external files folder may name other mirrors and one throwaway test key (local end-to-end
     * tests, like the desktop's PROJECTS_CONTENT_MIRRORS and PROJECTS_CONTENT_TEST_KEY). Release
     * builds never read it.
     */
    private static ContentSources sources(Context app) {
        if (BuildConfig.DEBUG) {
            File dir = app.getExternalFilesDir(null);
            File file = dir == null ? null : new File(dir, DEBUG_OVERRIDE_FILE);
            if (file != null && file.isFile()) {
                try {
                    ContentSources sources = ContentSources.withDebugOverride(AtomicFiles.read(file, 64 * 1024));
                    Log.w(TAG, "debug content override in use: " + sources.mirrors);
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

    /**
     * Shows the cached snapshot right away (first call) and refreshes from the mirrors unless the
     * last refresh finished less than a minute ago. {@code force} ("Tekrar dene") skips that.
     */
    @MainThread
    public static void refresh(Context context, boolean force) {
        if (sRefreshing) return;
        boolean recent = sSnapshot != null && isRecent();
        if (recent && !force) return;
        final ContentRepository repository = repository(context);
        final boolean needCache = sSnapshot == null;
        sRefreshing = true;
        notifyListeners();
        EXECUTOR.execute(() -> {
            if (needCache) {
                final Snapshot cached = repository.loadCached();
                MAIN.post(() -> {
                    if (sSnapshot == null && cached.hasContent()) {
                        sSnapshot = cached;
                        notifyListeners();
                    }
                });
            }
            final Snapshot latest = repository.refresh();
            if (latest.lastError != null) Log.i(TAG, "content refresh failed: " + latest.lastError);
            sLastAttempt = System.currentTimeMillis();
            MAIN.post(() -> {
                sSnapshot = latest;
                sRefreshing = false;
                notifyListeners();
            });
        });
    }

    /**
     * Snapshot for decisions that may wait for the network (background only): the known one when
     * it is online or was refreshed less than a minute ago, otherwise a refresh.
     */
    public static Snapshot latestBlocking(Context context) {
        Snapshot known = sSnapshot;
        if (known != null && (!known.offline || isRecent())) return known;
        Snapshot refreshed = repository(context).refresh();
        sLastAttempt = System.currentTimeMillis();
        MAIN.post(() -> {
            if (!refreshed.offline || sSnapshot == null) {
                sSnapshot = refreshed;
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

    /**
     * Loads a verified cover image into {@code view}. The view's tag guards against recycled
     * views; on failure the view is hidden.
     */
    @MainThread
    public static void loadCover(Context context, final Snapshot snapshot, final MediaRef media, final ImageView view, final int targetWidthPx) {
        final MediaRef image = media == null ? null : media.displayImage();
        if (image == null || !image.isValid()) {
            view.setTag(null);
            view.setVisibility(android.view.View.GONE);
            return;
        }
        final String key = image.cacheFileName();
        view.setTag(key);
        final ContentRepository repository = repository(context);
        MEDIA_EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                File file = repository.media(snapshot, image);
                bitmap = decode(file, targetWidthPx);
            } catch (ContentException e) {
                Log.i(TAG, "cover unavailable: " + e);
            }
            final Bitmap result = bitmap;
            MAIN.post(() -> {
                if (!key.equals(view.getTag())) return;
                if (result == null) {
                    view.setVisibility(android.view.View.GONE);
                } else {
                    view.setImageBitmap(result);
                    view.setVisibility(android.view.View.VISIBLE);
                }
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
