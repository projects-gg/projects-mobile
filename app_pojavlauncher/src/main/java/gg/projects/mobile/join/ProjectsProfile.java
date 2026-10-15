package gg.projects.mobile.join;

import androidx.annotation.WorkerThread;

import net.kdt.pojavlaunch.Tools;
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
}
