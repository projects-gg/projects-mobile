package gg.projects.mobile.launch;

import android.util.Log;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import gg.projects.mobile.join.ProjectsProfile;

/**
 * Called from Tools.launchMinecraft in the game process: the Projects profile always joins
 * play.projects.gg on start (the desktop launcher's "auto join" for its Projects instance).
 * Other profiles are left alone.
 */
public final class ProjectsLaunchHook {
    private ProjectsLaunchHook() {}

    public static String[] applyAutoJoin(String[] gameArgs, JMinecraftVersionList.Version versionInfo) {
        try {
            String profileKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            if (!ProjectsProfile.KEY.equals(profileKey)) return gameArgs;
            Object[] game = versionInfo.arguments == null ? null : versionInfo.arguments.game;
            String[] joined = QuickPlay.withServer(gameArgs, QuickPlay.PROJECTS_SERVER, QuickPlay.supportsQuickPlay(game));
            Log.i("ProjectsLaunchHook", "auto-joining " + QuickPlay.PROJECTS_SERVER);
            return joined;
        } catch (RuntimeException e) {
            // Never block a launch because of the auto-join.
            Log.w("ProjectsLaunchHook", "auto-join skipped", e);
            return gameArgs;
        }
    }
}
