package gg.projects.mobile.controls;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
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
import gg.projects.mobile.join.ProjectsProfile;

/**
 * The MODLAR keys' view of the running game (M16), in the game process: the key bindings of the
 * game folder's {@code options.txt} ({@link ModKeys}) and the mods the game loaded
 * ({@link LoadedMods}, from its log). The file is read when the game activity starts, again when
 * the game saves it (upstream's {@link MCOptionUtils} watches it, so a key changed in the game's
 * Controls reaches the buttons) and when the panel opens if it changed since. Nothing polls; the
 * log is read only until the mods are known. Before {@link #attach} (the controls editor) nothing
 * is known and the buttons send their own keys.
 *
 * <p>M17: in the Projects profile a clash the one-tap key fix solves ({@link KeyFix}) offers the fix
 * on the key's reason line; asked for there, it waits until the game is closed ({@link KeyFixer}).
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
    /** M17: the game runs the Projects profile (the one-tap key fix is for its pack). */
    private static volatile boolean sProjects;
    /** M17: a key fix of this game folder waits for the game to close. */
    private static volatile boolean sFixQueued;

    /** M17: what the reason line of a clashing MODLAR key offers. */
    public enum Fix {
        /** Nothing: the fix does not solve this key's clash (the player's own keys, another profile). */
        NONE,
        /** "Tuş çakışmalarını düzelt". */
        OFFER,
        /** Asked for: it is made once the game is closed ("Geri al" takes it back). */
        QUEUED,
    }

    private static final Runnable READ = () -> PojavApplication.sExecutorService.execute(() -> read(false));
    // Strong references: MCOptionUtils and Logger keep only what they are given.
    private static final MCOptionUtils.MCOptionListener OPTIONS_CHANGED = () -> {
        MAIN.removeCallbacks(READ);
        MAIN.postDelayed(READ, SETTLE_MS);
    };
    private static final Logger.eventLogListener LOG = ModKeysLive::onLog;

    /** The game activity starts the profile whose folder is {@code gameDir}. */
    @MainThread
    public static void attach(Context context, File gameDir) {
        sOptions = new File(gameDir, "options.txt");
        synchronized (ModKeysLive.class) {
            sReadLength = -1;
            sReadModified = -1;
        }
        sProjects = ProjectsProfile.KEY.equals(
                LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""));
        final Context app = context.getApplicationContext();
        PojavApplication.sExecutorService.execute(() -> read(true));
        // M17: the fix's table and whether a fix waits (asked for earlier while this game ran).
        KeyFixer.run(() -> {
            KeyFixer.pairs(app);
            sFixQueued = sProjects && KeyFixer.queued(app, gameDir);
        });
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

    /**
     * M17: whether the one-tap key fix solves {@code action}'s clash in the running game's options.txt
     * (the Projects profile only), and whether the player already asked for it.
     */
    public static Fix fix(Context context, ModKeys.Action action) {
        if (sOptions == null || !sProjects) return Fix.NONE;
        if (!KeyFix.covers(KeyFixer.plan(context, sBindings), action.mapping)) return Fix.NONE;
        return sFixQueued ? Fix.QUEUED : Fix.OFFER;
    }

    /**
     * M17: the player asked for the fix on a reason line ({@code queue}), or took it back. The game
     * keeps its keys until it closes, so the fix waits for that ({@link KeyFixer#queue}).
     */
    public static void queueFix(Context context, boolean queue) {
        File options = sOptions;
        if (options == null || !sProjects) return;
        final File gameDir = options.getParentFile();
        final Context app = context.getApplicationContext();
        // The line answers at once; a write that fails puts the state back and says so. The key fix's
        // own thread keeps "düzelt" and a quick "Geri al" in order.
        sFixQueued = queue;
        KeyFixer.run(() -> {
            try {
                if (queue) KeyFixer.queue(app, gameDir);
                else KeyFixer.unqueue(app);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "key fix not queued", e);
                MAIN.post(() -> {
                    sFixQueued = !queue;
                    android.widget.Toast.makeText(app, net.kdt.pojavlaunch.R.string.projects_keyfix_failed,
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }
        });
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
