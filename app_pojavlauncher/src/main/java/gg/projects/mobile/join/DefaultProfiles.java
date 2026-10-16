package gg.projects.mobile.join;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.DefaultVersion;
import gg.projects.mobile.content.LauncherConfig;
import gg.projects.mobile.content.Snapshot;

/**
 * Keeps upstream's default profile on the recommended Minecraft version ({@link DefaultVersion}).
 * Which profile the app manages and the version it gave it live in the app's own preferences, so
 * a version the player picks in the profile editor is recognised and left alone.
 */
public final class DefaultProfiles {
    private DefaultProfiles() {}

    private static final String TAG = "ProjectsProfiles";
    private static final String PREFS = "projects_default_profile";
    private static final String KEY_PROFILE = "profile";
    private static final String KEY_VERSION = "version";

    /** The recommended version of the content the app knows (the built-in default before any). */
    @MainThread
    public static String recommended() {
        Snapshot snapshot = ProjectsContent.current();
        return DefaultVersion.recommended(snapshot == null ? null : snapshot.config);
    }

    /**
     * Moves the default profile to {@code config}'s recommended version when it should. Returns
     * true when launcher_profiles.json changed (the version spinner then reloads).
     */
    @MainThread
    public static boolean apply(Context context, @Nullable LauncherConfig config) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tracked = prefs.getString(KEY_PROFILE, null);
        String applied = prefs.getString(KEY_VERSION, null);
        String recommended = DefaultVersion.recommended(config);
        try {
            LauncherProfiles.load();
            Map<String, MinecraftProfile> profiles = LauncherProfiles.mainProfileJson.profiles;
            List<String> keys = new ArrayList<>(profiles.keySet());
            Collections.sort(keys);
            boolean changed = false;
            for (String key : keys) {
                if (ProjectsProfile.KEY.equals(key)) continue; // follows its pack (ProjectsJoin)
                MinecraftProfile profile = profiles.get(key);
                if (profile == null) continue;
                String target = DefaultVersion.next(profile.name, profile.lastVersionId,
                        key.equals(tracked) ? applied : null, recommended);
                if (target == null) continue;
                profile.lastVersionId = target;
                tracked = key;
                applied = target;
                changed = true;
            }
            if (!changed) return false;
            LauncherProfiles.write();
            prefs.edit().putString(KEY_PROFILE, tracked).putString(KEY_VERSION, applied).apply();
            return true;
        } catch (RuntimeException e) {
            // An unreadable or unwritable profile file is upstream's to report; the menu stays usable.
            Log.w(TAG, "default profile left as it is", e);
            return false;
        }
    }
}
