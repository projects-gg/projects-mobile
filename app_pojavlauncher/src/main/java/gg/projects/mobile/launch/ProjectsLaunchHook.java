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

    public static String[] applyAutoJoin(String[] gameArgs, JMinecraftVersionList.Version versionInfo) {
        try {
            String profileKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            if (!ProjectsProfile.KEY.equals(profileKey)) return gameArgs;
            Object[] game = versionInfo.arguments == null ? null : versionInfo.arguments.game;
            String[] joined = QuickPlay.withServer(gameArgs, QuickPlay.PROJECTS_SERVER, QuickPlay.supportsQuickPlay(game));
            Log.i(TAG, "auto-joining " + QuickPlay.PROJECTS_SERVER);
            return joined;
        } catch (RuntimeException e) {
            // Never block a launch because of the auto-join.
            Log.w(TAG, "auto-join skipped", e);
            return gameArgs;
        }
    }

    /**
     * Writes the first options into {@code gameDir} when it has none: the app's language and the
     * version's data number. Runs in the game activity before upstream creates an empty
     * {@code options.txt}. {@code versionId} is the launched version (a loader's own id is fine: the
     * vanilla version it inherits from decides the language code, and the downloader copies its jar).
     */
    public static void seedOptions(Context context, File gameDir, String versionId) {
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
            FirstOptions.Result result = FirstOptions.seed(gameDir, minecraft, jars, LanguageManager.isTurkish(context));
            if (result != FirstOptions.Result.EXISTS) Log.i(TAG, "first options for " + minecraft + ": " + result);
        } catch (Exception e) {
            // Never block a launch because of the first options; the game starts with its defaults.
            Log.w(TAG, "first options skipped", e);
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
