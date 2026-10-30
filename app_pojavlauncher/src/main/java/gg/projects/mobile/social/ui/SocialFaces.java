package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.accounts.AccountHeads;
import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.social.SocialModel.Profile;

/**
 * Friends' pictures (the desktop's rule, CLAUDE.md "Arkadaşlar"): a picture bundled with the app
 * ({@code preset:<slug>}), the Minecraft head cut on the phone from the skin on Mojang's texture host
 * ({@code minecraft:<hash>}), or the Discord picture from Discord's CDN. No other host is ever asked;
 * a picture that cannot come shows the name's first letter. Kept in memory and in the app's cache
 * folder (Android may clear it), so a list scrolls without asking again.
 */
public final class SocialFaces {
    private SocialFaces() {}

    private static final String TAG = "ProjectsSocial";
    private static final int MAX_BYTES = 512 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(6 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Map<String, List<Callback>> WAITING = new HashMap<>();
    /** Pictures that could not come in this run (no network): not asked again until the app restarts. */
    private static final Map<String, Long> FAILED = new HashMap<>();

    public interface Callback {
        void onFace(@Nullable Bitmap bitmap, boolean pixelArt);
    }

    /** What a profile's picture is: the key it is cached under and whether it is pixel art. */
    static final class Source {
        final String key;
        final boolean pixelArt;
        @Nullable
        final String asset;
        @Nullable
        final String url;

        Source(String key, boolean pixelArt, @Nullable String asset, @Nullable String url) {
            this.key = key;
            this.pixelArt = pixelArt;
            this.asset = asset;
            this.url = url;
        }
    }

    static Source source(Profile profile) {
        String slug = profile.presetSlug();
        if (slug != null) return new Source("preset-" + slug, false, "avatars/" + slug + ".webp", null);
        String texture = profile.textureHash();
        if (texture != null) return new Source("mc-" + texture, true, null, "https://textures.minecraft.net/texture/" + texture);
        String url = profile.discordAvatarUrl(128);
        String hash = profile.avatarHash == null ? "default" : profile.avatarHash;
        return new Source("dc-" + profile.id + "-" + hash, false, null, url);
    }

    /** The picture when it is already in memory (a re-bound row shows it at once). */
    @MainThread
    @Nullable
    static Bitmap cached(Profile profile) {
        return MEMORY.get(source(profile).key);
    }

    static boolean pixelArt(Profile profile) {
        return source(profile).pixelArt;
    }

    @MainThread
    public static void load(Context context, final Profile profile, final Callback callback) {
        final Source source = source(profile);
        // No picture to fetch (a Discord account without one): the letter, at once and without a failure noted.
        if (source.asset == null && source.url == null) {
            callback.onFace(null, source.pixelArt);
            return;
        }
        Bitmap memory = MEMORY.get(source.key);
        if (memory != null) {
            callback.onFace(memory, source.pixelArt);
            return;
        }
        Long failed = FAILED.get(source.key);
        if (failed != null && System.currentTimeMillis() - failed < 10 * 60_000L) {
            callback.onFace(null, source.pixelArt);
            return;
        }
        List<Callback> waiting = WAITING.get(source.key);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(callback);
        WAITING.put(source.key, waiting);
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = fetch(app, source);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "face not drawn", e);
            }
            final Bitmap result = bitmap;
            MAIN.post(() -> {
                if (result != null) MEMORY.put(source.key, result);
                else FAILED.put(source.key, System.currentTimeMillis());
                List<Callback> all = WAITING.remove(source.key);
                if (all != null) for (Callback c : all) c.onFace(result, source.pixelArt);
            });
        });
    }

    @Nullable
    private static Bitmap fetch(Context app, Source source) {
        if (source.asset != null) {
            try (InputStream in = app.getAssets().open(source.asset)) {
                return BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                return null;
            }
        }
        if (source.url == null) return null;
        File dir = new File(app.getCacheDir(), "social-faces");
        File file = new File(dir, source.key.replaceAll("[^A-Za-z0-9_-]", "_") + ".png");
        if (!file.isFile()) {
            boolean ok = source.pixelArt ? AccountHeads.saveFromTexture(source.url, file) : download(source.url, file);
            if (!ok) return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    /** Discord's CDN only (an address the profile reader built). */
    private static boolean download(String url, File out) {
        if (!url.startsWith("https://cdn.discordapp.com/")) return false;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", ProjectsContent.userAgent());
            if (connection.getResponseCode() != 200) return false;
            byte[] png;
            try (InputStream in = connection.getInputStream()) {
                png = AtomicFiles.readBounded(in, MAX_BYTES);
            }
            Bitmap check = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (check == null) return false;
            check.recycle();
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            File temp = new File(out.getPath() + ".tmp");
            try (OutputStream stream = new FileOutputStream(temp)) {
                stream.write(png);
            }
            return temp.renameTo(out);
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "Discord picture not downloaded: " + e.getClass().getSimpleName());
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
