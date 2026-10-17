package gg.projects.mobile.launch;

import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.join.ProjectsProfile;

/**
 * Called from the game process before a launch: the Projects profile always joins
 * play.projects.gg on start (the desktop launcher's "auto join" for its Projects instance), and a
 * new game folder starts in Turkish ({@link FirstOptions}). Other profiles keep their own settings.
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
     * Writes the first options into {@code gameDir} when it has none: Turkish and the version's
     * data number. Runs in the game activity before upstream creates an empty {@code options.txt}.
     * {@code versionId} is the launched version (a loader's own id is fine: the vanilla version it
     * inherits from decides the language code, and the downloader copies its jar).
     */
    public static void seedOptions(File gameDir, String versionId) {
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
            FirstOptions.Result result = FirstOptions.seed(gameDir, minecraft, jars);
            if (result != FirstOptions.Result.EXISTS) Log.i(TAG, "first options for " + minecraft + ": " + result);
        } catch (Exception e) {
            // Never block a launch because of the first options; the game starts with its defaults.
            Log.w(TAG, "first options skipped", e);
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
