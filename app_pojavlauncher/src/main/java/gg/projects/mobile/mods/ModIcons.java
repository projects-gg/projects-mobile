package gg.projects.mobile.mods;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.MainThread;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.content.ContentPaths;
import gg.projects.mobile.content.Hashes;

/**
 * Mod icons from the pack's {@code iconUrl} (https only): a small memory cache, a disk cache in
 * the app's cache folder, and a fade-in once loaded. Without an icon the row keeps its monogram.
 */
final class ModIcons {
    private ModIcons() {}

    private static final long MAX_ICON_BYTES = 1L << 20;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(4 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Shows the icon for {@code url} in {@code view}; {@code monogram} stays visible until it is ready. */
    @MainThread
    static void load(Context context, final String url, final ImageView view, final View monogram, final int sizePx, final boolean animate) {
        view.animate().cancel();
        if (url == null || !ContentPaths.isHttpsUrl(url)) {
            view.setTag(null);
            view.setImageDrawable(null);
            view.setVisibility(View.INVISIBLE);
            monogram.setVisibility(View.VISIBLE);
            return;
        }
        view.setTag(url);
        Bitmap cached = MEMORY.get(url);
        if (cached != null) {
            show(view, monogram, cached, false);
            return;
        }
        view.setImageDrawable(null);
        view.setVisibility(View.INVISIBLE);
        monogram.setVisibility(View.VISIBLE);
        final File cacheDir = new File(context.getApplicationContext().getCacheDir(), "projects-icons");
        EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = fetch(cacheDir, url, sizePx);
            } catch (IOException | RuntimeException ignored) {
                // No icon: the monogram stays.
            }
            final Bitmap result = bitmap;
            if (result != null) MEMORY.put(url, result);
            MAIN.post(() -> {
                if (result == null || !url.equals(view.getTag())) return;
                show(view, monogram, result, animate);
            });
        });
    }

    private static void show(ImageView view, View monogram, Bitmap bitmap, boolean animate) {
        view.setImageBitmap(bitmap);
        view.setVisibility(View.VISIBLE);
        if (animate && Motion.enabled(view.getContext())) {
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(180).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> monogram.setVisibility(View.INVISIBLE)).start();
        } else {
            view.setAlpha(1f);
            monogram.setVisibility(View.INVISIBLE);
        }
    }

    private static Bitmap fetch(File cacheDir, String url, int sizePx) throws IOException {
        File file = new File(cacheDir, Hashes.sha256Hex(url.getBytes(StandardCharsets.UTF_8)));
        byte[] bytes;
        if (file.isFile()) {
            bytes = AtomicFiles.read(file, MAX_ICON_BYTES);
        } else {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", ProjectsContent.userAgent());
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                // Redirects must stay on https.
                if (!ContentPaths.isHttpsUrl(connection.getURL().toString())) return null;
                try (InputStream in = connection.getInputStream()) {
                    bytes = AtomicFiles.readBounded(in, MAX_ICON_BYTES);
                }
            } finally {
                connection.disconnect();
            }
            if (decode(bytes, sizePx) == null) return null;
            AtomicFiles.write(file, bytes);
        }
        return decode(bytes, sizePx);
    }

    private static Bitmap decode(byte[] bytes, int sizePx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= Math.max(1, sizePx)) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
