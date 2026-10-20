package gg.projects.mobile.join;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.WorkerThread;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The "Projects" profile in launcher_profiles.json. Its key is a fixed UUID so the launcher can
 * recognise it (profile keys must be UUIDs, see LauncherProfiles.normalizeProfileIds) and the
 * game process can auto-join the server when it is launched.
 */
public final class ProjectsProfile {
    private ProjectsProfile() {}

    public static final String KEY = UUID.nameUUIDFromBytes(
            "gg.projects.mobile/profile/projects".getBytes(StandardCharsets.UTF_8)).toString();
    public static final String NAME = "Projects";
    /** Static icon name resolved by ProfileIconCache. */
    public static final String ICON = "projects";
    public static final String GAME_DIR = "./custom_instances/projects";
    private static final String TAG = "ProjectsProfile";

    @WorkerThread
    public static MinecraftProfile find() {
        LauncherProfiles.load();
        return LauncherProfiles.mainProfileJson.profiles.get(KEY);
    }

    /**
     * Creates the profile, or moves it to {@code versionId} when one is given. A profile that
     * already exists keeps its version when {@code versionId} is null (vanilla stays pinned).
     */
    @WorkerThread
    public static MinecraftProfile ensure(String versionId) {
        LauncherProfiles.load();
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(KEY);
        boolean changed = false;
        if (profile == null) {
            profile = new MinecraftProfile();
            profile.name = NAME;
            profile.icon = ICON;
            profile.gameDir = GAME_DIR;
            profile.lastVersionId = versionId != null ? versionId : MinecraftProfile.LATEST_RELEASE;
            LauncherProfiles.mainProfileJson.profiles.put(KEY, profile);
            changed = true;
        } else if (versionId != null && !versionId.equals(profile.lastVersionId)) {
            profile.lastVersionId = versionId;
            changed = true;
        }
        if (profile.gameDir == null) {
            profile.gameDir = GAME_DIR;
            changed = true;
        }
        if (changed) LauncherProfiles.write();
        return profile;
    }

    public static File gameDir(MinecraftProfile profile) {
        return Tools.getGameDirPath(profile);
    }

    /** M18: the app's preference that {@link #seedOnce} ran on this install. */
    private static final String KEY_SEEDED = "projects_profile_seeded";

    /**
     * M18: OYNA plays Projects from the app's first start. Before, a new install had only upstream's
     * default profile, which Play started as plain vanilla (no mods, no server); the Projects profile
     * appeared with the first "Projects'e katıl". Once per install: when the Projects profile does not
     * exist yet it is created on {@code version} (the recommended Minecraft version; its first join moves
     * it to the pack's loader version) and selected, unless the player chose a profile of their own
     * ({@link PlayChoice#selectProjectsAtFirstStart}). An install that has the profile keeps its selection.
     * Returns true when the selection changed (the play bar shows it).
     */
    public static boolean seedOnce(SharedPreferences prefs, String version) {
        if (prefs.getBoolean(KEY_SEEDED, false)) return false;
        boolean select = false;
        try {
            LauncherProfiles.load();
            boolean existed = LauncherProfiles.mainProfileJson.profiles.containsKey(KEY);
            if (!existed) {
                String current = prefs.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                MinecraftProfile chosen = LauncherProfiles.mainProfileJson.profiles.get(current);
                select = PlayChoice.selectProjectsAtFirstStart(false, chosen != null, chosen == null ? null : chosen.name);
                MinecraftProfile profile = ensure(version);
                File dir = gameDir(profile);
                if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "cannot create " + dir);
                Log.i(TAG, "Projects profile created for Play" + (select ? " and selected" : ""));
            }
        } catch (RuntimeException e) {
            // An unreadable profile file is upstream's to report; Play keeps what it had.
            Log.w(TAG, "Projects profile not created", e);
            return false;
        }
        SharedPreferences.Editor edit = prefs.edit().putBoolean(KEY_SEEDED, true);
        if (select) edit.putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, KEY);
        // The game runs in another process and reads the selection from disk.
        edit.commit();
        return select;
    }

    /** The profile's game directory, also before the profile exists (where it will be created). */
    @WorkerThread
    public static File gameDir() {
        return gameDirOf(find());
    }

    /**
     * {@link #gameDir()} from the profiles already in memory (loaded when the launcher started),
     * for frequent background reads that should not reload or rewrite launcher_profiles.json.
     */
    @WorkerThread
    public static File knownGameDir() {
        if (LauncherProfiles.mainProfileJson == null || LauncherProfiles.mainProfileJson.profiles == null) return gameDir();
        return gameDirOf(LauncherProfiles.mainProfileJson.profiles.get(KEY));
    }

    /**
     * The renderer the profile runs with ({@code pojavRendererName}; null for upstream's default),
     * from the profiles already in memory like {@link #knownGameDir()}.
     */
    @WorkerThread
    public static String knownRenderer() {
        if (LauncherProfiles.mainProfileJson == null || LauncherProfiles.mainProfileJson.profiles == null) {
            MinecraftProfile profile = find();
            return profile == null ? null : profile.pojavRendererName;
        }
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(KEY);
        return profile == null ? null : profile.pojavRendererName;
    }

    private static File gameDirOf(MinecraftProfile profile) {
        if (profile != null) return gameDir(profile);
        MinecraftProfile template = new MinecraftProfile();
        template.gameDir = GAME_DIR;
        return gameDir(template);
    }
}
