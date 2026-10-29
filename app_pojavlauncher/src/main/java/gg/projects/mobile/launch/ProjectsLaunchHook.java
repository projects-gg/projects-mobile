package gg.projects.mobile.launch;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.join.ProjectsProfile;
import gg.projects.mobile.language.LanguageManager;

/**
 * Called from the game process before a launch: the Projects profile always joins
 * play.projects.gg on start (the desktop launcher's "auto join" for its Projects instance), a new
 * game folder starts in the app's language ({@link FirstOptions}), and what was started is written
 * down for the launcher, which tells from it whether Sodium kept the game from starting
 * ({@link SodiumFallback}). Other profiles keep their own settings.
 */
public final class ProjectsLaunchHook {
    private ProjectsLaunchHook() {}

    private static final String TAG = "ProjectsLaunchHook";

    /** FIX2: the server this game's start joins by itself (its log may name the SRV target instead), or null. */
    @androidx.annotation.Nullable
    private static volatile String sJoinTarget;

    /** The server the start joins by itself ({@code host} or {@code host:port}), for the in-game status; null: none. */
    @androidx.annotation.Nullable
    public static String joinTarget() {
        return sJoinTarget;
    }

    public static String[] applyAutoJoin(String[] gameArgs, JMinecraftVersionList.Version versionInfo) {
        try {
            Object[] game = versionInfo.arguments == null ? null : versionInfo.arguments.game;
            boolean quickPlay = QuickPlay.supportsQuickPlay(game);
            // FIX2 ("Bağlan"): the server a crew call or an invite shares, written down by the launcher for this start.
            String pending = ConnectTarget.takePending(LauncherPreferences.DEFAULT_PREF, System.currentTimeMillis());
            if (pending != null) {
                String[] joined = QuickPlay.withServer(gameArgs, pending, quickPlay);
                if (joined != gameArgs) sJoinTarget = pending;
                Log.i(TAG, "joining the server a friend shared");
                return joined;
            }
            String profileKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            if (!ProjectsProfile.KEY.equals(profileKey)) return gameArgs;
            String[] joined = QuickPlay.withServer(gameArgs, QuickPlay.PROJECTS_SERVER, quickPlay);
            if (joined != gameArgs) sJoinTarget = QuickPlay.PROJECTS_SERVER;
            Log.i(TAG, "auto-joining " + QuickPlay.PROJECTS_SERVER);
            return joined;
        } catch (RuntimeException e) {
            // Never block a launch because of the auto-join.
            Log.w(TAG, "auto-join skipped", e);
            return gameArgs;
        }
    }

    /**
     * Writes the first options into {@code gameDir} when it has none: the app's language, the
     * version's data number, no keyboard tutorial and, for the Projects profile, the pack's key
     * bindings ({@link FirstOptions}). Runs in the game activity before upstream creates an empty
     * {@code options.txt}. {@code versionId} is the launched version (a loader's own id is fine: the
     * vanilla version it inherits from decides the language code, and the downloader copies its jar).
     */
    public static void seedOptions(Context context, File gameDir, String versionId) {
        // M17: a key fix the player asked for in the game is made before the game reads its keys again
        // (when the home screen did not make it first).
        applyQueuedKeyFix(context, gameDir);
        if (versionId == null || versionId.isEmpty()) return;
        try {
            String minecraft = versionId;
            List<File> jars = new ArrayList<>();
            jars.add(clientJar(versionId));
            String inherits = inheritsFrom(versionId);
            if (inherits != null) {
                minecraft = inherits;
                jars.add(clientJar(inherits));
            }
            final Context app = context.getApplicationContext();
            FirstOptions.Result result = FirstOptions.seed(gameDir, minecraft, jars, LanguageManager.isTurkish(context),
                    projectsKeys(context), PhoneGraphics.keys(minecraft), version -> phonePreset(app, version));
            if (result != FirstOptions.Result.EXISTS) Log.i(TAG, "first options for " + minecraft + ": " + result);
        } catch (Exception e) {
            // Never block a launch because of the first options; the game starts with its defaults.
            Log.w(TAG, "first options skipped", e);
        }
    }

    /** M17: the game process looked for a queued key fix (once: the game starts once per process). */
    private static boolean sQueuedKeyFixChecked;

    /**
     * M17: the queued one-tap key fix of the Projects profile ({@link gg.projects.mobile.controls.KeyFixer}),
     * made right before the game reads its options. Only at the game's start: an activity created again
     * while the game runs (a configuration change, "Don't keep activities") must not rewrite the file
     * under the running game, which keeps its keys in memory.
     */
    private static void applyQueuedKeyFix(Context context, File gameDir) {
        if (sQueuedKeyFixChecked) return;
        sQueuedKeyFixChecked = true;
        try {
            String profileKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            if (!ProjectsProfile.KEY.equals(profileKey)) return;
            gg.projects.mobile.controls.KeyFixer.applyQueued(context, gameDir);
        } catch (RuntimeException e) {
            Log.w(TAG, "queued key fix skipped", e);
        }
    }

    /**
     * M17: the graphics preset for this phone ({@link PhoneGraphics}): its memory, cores, Android's
     * low-RAM flag and the GPU's name (upstream asks the GPU once per game process anyway).
     */
    private static List<String> phonePreset(Context context, String minecraft) {
        long memory = 0;
        boolean lowRam = false;
        android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            memory = info.totalMem;
            lowRam = manager.isLowRamDevice();
        }
        int cores = Runtime.getRuntime().availableProcessors();
        String renderer = null;
        try {
            renderer = net.kdt.pojavlaunch.utils.GLInfoUtils.getGlInfo().renderer;
        } catch (RuntimeException e) {
            Log.w(TAG, "GPU not known", e);
        }
        PhoneGraphics.Tier tier = PhoneGraphics.tier(memory, lowRam, cores, renderer);
        Log.i(TAG, String.format(java.util.Locale.ROOT, "graphics preset %s (%.1f GB, %d cores, low RAM %s, GPU %s)",
                tier, memory / (1024.0 * 1024 * 1024), cores, lowRam, renderer));
        return PhoneGraphics.lines(minecraft, tier);
    }

    /**
     * The Projects profile's first options also bind the pack's mods to keys that clash with nothing
     * (the shared table {@code assets/key-bindings.json}; the on-screen MODLAR keys send the same
     * keys). Other profiles get none.
     */
    private static List<String> projectsKeys(Context context) {
        String profileKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        if (!ProjectsProfile.KEY.equals(profileKey)) return new ArrayList<>();
        try (InputStream in = context.getAssets().open("key-bindings.json")) {
            return FirstOptions.keyLines(new String(AtomicFiles.readBounded(in, 64 * 1024), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "key table unavailable", e);
            return new ArrayList<>();
        }
    }

    /**
     * Upstream copies {@code assets/options.txt} into the default game folder once, at the first
     * start; the copy speaks the app's language ({@link FirstOptions#withLanguage}). A file that is
     * already there is the game's or the player's and stays.
     */
    public static void copyDefaultOptions(Context context, File gameDir) {
        File file = new File(gameDir, FirstOptions.FILE_NAME);
        if (file.exists()) return;
        try {
            String options;
            try (InputStream in = context.getAssets().open(FirstOptions.FILE_NAME)) {
                options = new String(AtomicFiles.readBounded(in, 1L << 20), StandardCharsets.UTF_8);
            }
            if (!gameDir.isDirectory() && !gameDir.mkdirs()) throw new IOException("cannot create " + gameDir);
            AtomicFiles.write(file, FirstOptions.withLanguage(options, LanguageManager.isTurkish(context)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "default options not copied", e);
        }
    }

    /**
     * Notes what is being started (the profile, its folder and renderer, whether Sodium is in its
     * mods folder) for the launcher's look at a failed start. Runs in the game process right before
     * the game starts, after the renderer was picked.
     */
    public static void recordLaunch(Context context, MinecraftProfile profile, String versionId) {
        try {
            // SOC9: friends see the Minecraft version this game runs: a loader version's vanilla id, never its name.
            gg.projects.mobile.social.GameWhere.setVersion(
                    gg.projects.mobile.social.GameVersion.vanilla(versionId, versionId == null ? null : inheritsFrom(versionId)));
        } catch (RuntimeException e) {
            Log.w(TAG, "game version not noted", e);
        }
        try {
            String profileKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            File gameDir = Tools.getGameDirPath(profile);
            List<String> sodium = SodiumFallback.sodiumJarNames(new File(gameDir, "mods"));
            LaunchRecord record = new LaunchRecord(profileKey, gameDir.getAbsolutePath(), Tools.LOCAL_RENDERER,
                    versionId, sodium, System.currentTimeMillis());
            record.profileRenderer = profile.pojavRendererName;
            record.write(context);
            if (!sodium.isEmpty()) Log.i(TAG, "Sodium installed (" + sodium.size() + " jars), renderer " + Tools.LOCAL_RENDERER);
        } catch (RuntimeException | IOException e) {
            Log.w(TAG, "launch not recorded", e);
        }
    }

    private static File clientJar(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".jar");
    }

    /** The {@code inheritsFrom} of an installed version's JSON (a loader version), or null. */
    private static String inheritsFrom(String versionId) {
        File json = new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".json");
        try {
            JsonElement root = JsonParser.parseString(new String(AtomicFiles.read(json, 16L << 20), StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return null;
            JsonElement value = ((JsonObject) root).get("inheritsFrom");
            if (value == null || !value.isJsonPrimitive()) return null;
            String inherits = value.getAsString();
            return inherits.isEmpty() || inherits.equals(versionId) ? null : inherits;
        } catch (Exception e) {
            return null;
        }
    }
}
