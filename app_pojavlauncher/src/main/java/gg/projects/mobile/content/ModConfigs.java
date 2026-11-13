package gg.projects.mobile.content;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * M054 (LAUNCH-SPEED on the phone): the first settings file of a mod the official pack just put in place, from the
 * desktop's table (pl-mc {@code data/mod-configs.json}, {@code instance::mod_configs}). Sodium Extra Information's
 * library and XaeroLib (inside Xaero's Better PvP) call out while the game starts and the render thread waits for them:
 * their switches are off. A file is written only while nothing is at its path: the player's later changes and the mod's
 * own file always win. The mod keeps its own defaults for the rest and writes its full file itself.
 * <p>
 * Only these two of the desktop's five entries: the phone installs the official pack alone, and the other three
 * (Litematica, MaLiLib, Chest Tracker) are mods of ready-made sets.
 */
public final class ModConfigs {
    private ModConfigs() {}

    private static final String TAG = "ModConfigs";
    /** The folder every file of the table lives in. */
    private static final String CONFIG_DIR = "config";

    /** One settings file: the Modrinth projects (or item ids) it belongs to, its path under the game folder, its bytes. */
    public static final class Entry {
        public final List<String> projects;
        public final String mod;
        public final String path;
        private final byte[] contents;

        Entry(String mod, String path, String contents, String... projects) {
            this.projects = Collections.unmodifiableList(Arrays.asList(projects));
            this.mod = mod;
            this.path = path;
            this.contents = contents.getBytes(StandardCharsets.UTF_8);
        }

        public byte[] contents() {
            return contents.clone();
        }
    }

    /** The desktop's bytes exactly: its JSON pretty-printed with a final new line, its text as it is. */
    private static final List<Entry> TABLE = Collections.unmodifiableList(Arrays.asList(
            new Entry("Sodium Extra Information", "config/sodiumextrainformation/telemetry-config.json",
                    "{\n  \"enable_telemetry\": false\n}\n", "696WgnvT"),
            new Entry("XaeroLib (Better PvP)", "config/xaero/lib/common.cfg", "allow_internet_access = false\n", "better-pvp")));

    public static List<Entry> table() {
        return TABLE;
    }

    /** The table's files of {@code projects}, in the table's order. */
    public static List<Entry> configsFor(List<String> projects) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : TABLE) {
            for (String project : entry.projects) {
                if (projects.contains(project)) {
                    out.add(entry);
                    break;
                }
            }
        }
        return out;
    }

    /** Whether {@code path} is a plain path under {@code config/} ({@code /}-separated, no empty, dot or dot-dot part). */
    static boolean settingsPath(String path) {
        if (path == null || !path.startsWith(CONFIG_DIR + "/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) return false;
        String rest = path.substring(CONFIG_DIR.length() + 1);
        if (rest.isEmpty()) return false;
        for (String part : rest.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    /**
     * Writes the settings files of {@code projects} (the mods a sync just put in place, {@link PackSyncPlanner.Plan#placedMods})
     * that do not exist yet in {@code gameDir}, and returns the table paths it wrote. Whatever is already at a path (the
     * player's file, the mod's own, even a folder) is left alone. Called after the files are in place, before the game
     * starts (the game is not running while a sync applies).
     */
    public static List<String> seed(File gameDir, List<String> projects) throws IOException {
        List<String> written = new ArrayList<>();
        if (projects.isEmpty()) return written;
        for (Entry entry : configsFor(projects)) {
            if (!settingsPath(entry.path)) continue;
            File target = new File(gameDir, entry.path);
            if (somethingAt(target)) continue;
            AtomicFiles.write(target, entry.contents());
            written.add(entry.path);
        }
        return written;
    }

    /**
     * SQOL-M (m054 P3): whether anything is at {@code target}, a link included, the desktop's {@code symlink_metadata}.
     * {@link File#exists()} follows links, so a broken link read as missing and the seed replaced the player's link with a
     * file. No java.nio.file below Android 8: a broken link still shows in its folder's listing.
     */
    static boolean somethingAt(File target) {
        if (target.exists()) return true;
        File parent = target.getParentFile();
        String[] names = parent == null ? null : parent.list();
        if (names == null) return false;
        String name = target.getName();
        for (String entry : names) {
            if (entry.equals(name)) return true;
        }
        return false;
    }

    /** {@link #seed} that never fails a sync or a start: a failure is logged. */
    public static void seedQuietly(File gameDir, List<String> projects) {
        try {
            List<String> written = seed(gameDir, projects);
            if (!written.isEmpty()) Log.i(TAG, "wrote the first settings of newly placed mods: " + written);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not write a placed mod's first settings", e);
        }
    }
}
