package gg.projects.mobile.controls;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.utils.MCOptionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import gg.projects.mobile.content.AtomicFiles;

/**
 * The MODLAR keys' view of the running game (M16), in the game process: the key bindings of the
 * game folder's {@code options.txt} ({@link ModKeys}) and the mods the game loaded
 * ({@link LoadedMods}, from its log). The file is read when the game activity starts, again when
 * the game saves it (upstream's {@link MCOptionUtils} watches it, so a key changed in the game's
 * Controls reaches the buttons) and when the panel opens if it changed since. Nothing polls; the
 * log is read only until the mods are known. Before {@link #attach} (the controls editor) nothing
 * is known and the buttons send their own keys.
 */
public final class ModKeysLive {
    private ModKeysLive() {}

    private static final String TAG = "ProjectsModKeys";
    private static final long MAX_OPTIONS_BYTES = 1L << 20;
    /** The game writes options.txt in pieces: read it once it has been quiet this long. */
    private static final long SETTLE_MS = 250;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile File sOptions;
    private static volatile Map<String, String> sBindings = Collections.emptyMap();
    private static volatile Set<String> sMods;
    private static long sReadLength = -1, sReadModified = -1;
    private static final List<Runnable> sListeners = new CopyOnWriteArrayList<>();
    private static volatile LoadedMods sParser;
    private static final Object LOG_LOCK = new Object();
    /** The log was read to its answer once in this process (the mods, or that they cannot be told). */
    private static volatile boolean sModsSettled;

    private static final Runnable READ = () -> PojavApplication.sExecutorService.execute(() -> read(false));
    // Strong references: MCOptionUtils and Logger keep only what they are given.
    private static final MCOptionUtils.MCOptionListener OPTIONS_CHANGED = () -> {
        MAIN.removeCallbacks(READ);
        MAIN.postDelayed(READ, SETTLE_MS);
    };
    private static final Logger.eventLogListener LOG = ModKeysLive::onLog;

    /** The game activity starts the profile whose folder is {@code gameDir}. */
    @MainThread
    public static void attach(File gameDir) {
        sOptions = new File(gameDir, "options.txt");
        synchronized (ModKeysLive.class) {
            sReadLength = -1;
            sReadModified = -1;
        }
        PojavApplication.sExecutorService.execute(() -> read(true));
        MCOptionUtils.addMCOptionListener(OPTIONS_CHANGED);
        // The mods are known once per game process: an activity created again keeps the answer.
        if (!sModsSettled && sParser == null) {
            sParser = new LoadedMods();
            Logger.addLogListener(LOG);
        }
    }

    /**
     * How {@code action} is bound in the running game, or null outside the game (the editor), where a
     * button sends the keys it has.
     */
    @Nullable
    public static ModKeys.Binding binding(ModKeys.Action action) {
        if (sOptions == null) return null;
        return ModKeys.resolve(action, sBindings, sMods);
    }

    /** The MODLAR panel opens: reads options.txt again if it changed (a stat, nothing else otherwise). */
    public static void refresh() {
        if (sOptions == null) return;
        PojavApplication.sExecutorService.execute(() -> read(false));
    }

    /** {@code listener} runs on the main thread whenever a binding or the mods change. */
    public static void addListener(Runnable listener) {
        sListeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        sListeners.remove(listener);
    }

    private static void read(boolean force) {
        File file = sOptions;
        if (file == null) return;
        // One read at a time, and the bindings set inside: a slower read of an older file never wins.
        synchronized (ModKeysLive.class) {
            long length = file.length();
            long modified = file.lastModified();
            if (!force && length == sReadLength && modified == sReadModified) return;
            sReadLength = length;
            sReadModified = modified;
            List<String> lines;
            if (!file.isFile()) {
                lines = Collections.emptyList();
            } else {
                try {
                    String text = new String(AtomicFiles.read(file, MAX_OPTIONS_BYTES), StandardCharsets.UTF_8);
                    lines = Arrays.asList(text.split("\r?\n"));
                } catch (IOException | RuntimeException e) {
                    // Unreadable now (the game may be writing it): the next save or panel opening reads it.
                    Log.w(TAG, "options.txt not read", e);
                    sReadLength = -1;
                    return;
                }
            }
            Map<String, String> next = ModKeys.keyBindings(lines);
            if (next.equals(sBindings)) return;
            sBindings = Collections.unmodifiableMap(next);
        }
        logBindings();
        notifyChanged();
    }

    /** The game's log, from the native logger's thread and from whoever appends to it: one at a time. */
    private static void onLog(String chunk) {
        synchronized (LOG_LOCK) {
            LoadedMods parser = sParser;
            if (parser == null || !parser.feed(chunk)) return;
            sMods = parser.mods();
            sModsSettled = true;
            sParser = null;
        }
        // Removing a listener while the logger iterates is safe (a copy-on-write list).
        Logger.removeLogListener(LOG);
        Log.i(TAG, sMods == null ? "mods: unknown" : "mods: " + sMods.size() + " loaded");
        logBindings();
        notifyChanged();
    }

    private static void logBindings() {
        StringBuilder out = new StringBuilder("keys:");
        for (ModKeys.Action action : ModKeys.Action.values()) {
            out.append(' ').append(ModKeys.resolve(action, sBindings, sMods));
        }
        Log.i(TAG, out.toString());
    }

    private static void notifyChanged() {
        MAIN.post(() -> {
            for (Runnable listener : sListeners) listener.run();
        });
    }
}
