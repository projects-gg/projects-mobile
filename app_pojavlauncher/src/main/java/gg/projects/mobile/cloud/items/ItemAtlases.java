package gg.projects.mobile.cloud.items;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.social.Json;

/**
 * The phone's item atlases (CLAUDE.md "Envanter görselleri": the game's own textures from the player's own game files;
 * nothing of the game is in the app or the repository). An atlas is drawn once per Minecraft version, drawing size and
 * {@link PlItems#REVISION} by {@code pl-items} (the launcher's code, through {@link PlItems}) from the client jar the
 * player already has on this phone, kept in the app's cache, and read back on later visits. Until it is there, and
 * when no version is installed, items show their quiet stand-ins. One drawing at a time, in the background, only when a
 * screen asks: nothing runs while no inventory is open.
 */
public final class ItemAtlases {
    private static final String TAG = "ProjectsItemArt";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Map<String, ItemArt> LOADED = new HashMap<>();
    private static final Map<String, List<Ready>> WAITING = new HashMap<>();

    private ItemAtlases() {}

    public interface Ready {
        @MainThread
        void ready(ItemArt art);
    }

    /**
     * The art for items drawn {@code itemPx} device pixels wide (16 GUI pixels), from the jar of {@code version} (the
     * account's Minecraft version; "auto" or a version not installed takes the newest installed one). Returns what is
     * there now (stand-ins at first); {@code ready} gets the atlas when it has been drawn or read.
     */
    @MainThread
    public static ItemArt art(Context context, @Nullable String version, int itemPx, @Nullable Ready ready) {
        final Context app = context.getApplicationContext();
        final File jar = GameJars.find(app, version);
        if (jar == null || !PlItems.available()) return ItemArt.standIns();
        final int block = PlItems.blockSize(itemPx);
        final String key = jar.getAbsolutePath() + "|" + jar.lastModified() + "|" + block;
        ItemArt loaded = LOADED.get(key);
        if (loaded != null) return loaded;
        List<Ready> waiting = WAITING.get(key);
        if (waiting != null) {
            if (ready != null) waiting.add(ready);
            return ItemArt.standIns();
        }
        waiting = new ArrayList<>();
        if (ready != null) waiting.add(ready);
        WAITING.put(key, waiting);
        final File dir = cacheDir(app, jar, block);
        WORKER.execute(() -> {
            ItemArt art = load(dir);
            if (art == null) {
                long started = System.currentTimeMillis();
                boolean built = PlItems.build(jar, dir, GameJars.versionOf(jar), block);
                Log.i(TAG, "atlas " + (built ? "drawn" : "failed") + " in " + (System.currentTimeMillis() - started) + " ms (block " + block + ")");
                art = built ? load(dir) : null;
            }
            final ItemArt done = art == null ? ItemArt.standIns() : art;
            MAIN.post(() -> {
                if (done.hasAtlas()) LOADED.put(key, done);
                List<Ready> listeners = WAITING.remove(key);
                if (listeners != null) for (Ready r : listeners) r.ready(done);
            });
        });
        return ItemArt.standIns();
    }

    /** {@code cache/items/<version>-r<revision>-b<block>-<jar time>/}; an older drawing of the same version is removed. */
    static File cacheDir(Context app, File jar, int block) {
        File root = new File(app.getCacheDir(), "items");
        String version = GameJars.versionOf(jar);
        String name = version + "-r" + PlItems.REVISION + "-b" + block + "-" + jar.lastModified();
        File[] old = root.listFiles();
        if (old != null) {
            for (File f : old) if (f.getName().startsWith(version + "-") && !f.getName().equals(name)) deleteTree(f);
        }
        return new File(root, name);
    }

    @Nullable
    private static ItemArt load(File dir) {
        File index = new File(dir, "index.json");
        File image = new File(dir, "atlas.png");
        if (!index.isFile() || !image.isFile()) return null;
        try {
            ItemIndex parsed = ItemIndex.read(Json.parseObject(new String(AtomicFiles.read(index, 4 * 1024 * 1024), StandardCharsets.UTF_8)));
            if (parsed == null) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap atlas = BitmapFactory.decodeFile(image.getAbsolutePath(), options);
            return atlas == null ? null : new ItemArt(atlas, parsed);
        } catch (Exception e) {
            Log.w(TAG, "unreadable atlas", e);
            return null;
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
