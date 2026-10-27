package gg.projects.mobile.join;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * MOB2: deleting a profile the way the desktop deletes an instance. Its entry goes to the
 * {@link ProfileTrash} (30 days) and out of launcher_profiles.json; its game folder stays as it is
 * (worlds, mods, the game's own settings); Play's choice moves to the list's first profile
 * ({@link ProfileOrder}); and the home screen the editor closes onto offers "Geri al"
 * ({@link #takeUndo}). "Geri al" ({@link #restore}), "Resmî paketi kur" and "Projects'e katıl"
 * ({@link ProjectsProfile#ensure}) bring a deleted profile back under its own key with the player's
 * own settings (renderer, Java, icon, controls).
 */
public final class ProfileRemoval {
    private ProfileRemoval() {}

    private static final String TAG = "ProjectsProfiles";
    /** "Geri al" is offered only on the home screen right after the delete (the editor closes onto it). */
    private static final long UNDO_PICKUP_MS = 15_000;
    /** Deleting and bringing back, here and in {@link ProjectsProfile#ensure}, one at a time. */
    static final Object LOCK = new Object();

    /** A delete the home screen offers to take back. */
    public static final class Undo {
        public final String key;
        /** The profile's name as the player reads it ("Varsayılan"). */
        public final String name;
        final long offeredAt;

        Undo(String key, String name, long offeredAt) {
            this.key = key;
            this.name = name;
            this.offeredAt = offeredAt;
        }
    }

    @Nullable
    private static Undo sPendingUndo;

    /** The trash beside launcher_profiles.json. */
    public static ProfileTrash trash() {
        return ProfileTrash.beside(new File(Tools.GAME_PROFILES_FILE));
    }

    /** Whether "Sil" deletes {@code key}: a stored profile, and not the only one (one must remain). */
    public static boolean canDelete(String key) {
        if (LauncherProfiles.mainProfileJson == null) LauncherProfiles.load();
        Map<String, MinecraftProfile> profiles = LauncherProfiles.mainProfileJson.profiles;
        return key != null && profiles.containsKey(key) && profiles.size() > 1;
    }

    /**
     * Moves {@code key}'s profile to the trash and out of launcher_profiles.json. When it was Play's
     * choice (it is: the editor opens the chosen profile), the list's first profile becomes the choice.
     * Returns the key Play now starts, or null when nothing was deleted (no such profile, or it is the
     * only one). Throws when the trash or the profile file cannot be written; nothing is lost then.
     */
    public static String delete(String key) throws IOException {
        synchronized (LOCK) {
            LauncherProfiles.load();
            Map<String, MinecraftProfile> profiles = LauncherProfiles.mainProfileJson.profiles;
            MinecraftProfile profile = profiles.get(key);
            if (profile == null || profiles.size() <= 1) return null;
            JsonElement stored = Tools.GLOBAL_GSON.toJsonTree(profile);
            // The way back first: when the trash cannot be written, the profile stays.
            trash().put(key, stored.getAsJsonObject(), System.currentTimeMillis());
            profiles.remove(key);
            try {
                LauncherProfiles.write();
            } catch (RuntimeException e) {
                profiles.put(key, profile);
                throw e;
            }
            String current = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            if (profiles.containsKey(current)) return current;
            String next = ProfileOrder.first(profiles, Locale.getDefault());
            choose(next);
            Log.i(TAG, "profile moved to the trash; Play's choice moved to the list's first profile");
            return next;
        }
    }

    /**
     * "Geri al": {@code key}'s profile back from the trash under its own key, and Play's choice again.
     * Returns false when the trash no longer has it (and it is not back already).
     */
    @WorkerThread
    public static boolean restore(String key) throws IOException {
        synchronized (LOCK) {
            LauncherProfiles.load();
            Map<String, MinecraftProfile> profiles = LauncherProfiles.mainProfileJson.profiles;
            if (!profiles.containsKey(key)) {
                MinecraftProfile profile = fromTrash(key);
                if (profile == null) return false;
                profiles.put(key, profile);
                try {
                    LauncherProfiles.write();
                } catch (RuntimeException e) {
                    profiles.remove(key);
                    throw e;
                }
            }
            forget(key);
            choose(key);
            return true;
        }
    }

    /** {@code key}'s profile as the trash keeps it, or null. The trash stays as it is. */
    @Nullable
    static MinecraftProfile fromTrash(String key) {
        ProfileTrash.Entry entry = trash().find(key, System.currentTimeMillis());
        if (entry == null) return null;
        try {
            return Tools.GLOBAL_GSON.fromJson(entry.profile, MinecraftProfile.class);
        } catch (JsonParseException e) {
            Log.w(TAG, "trash entry unreadable", e);
            return null;
        }
    }

    /** Takes {@code key} out of the trash once its profile is back (a failure leaves a stale entry, never a lost profile). */
    static void forget(String key) {
        try {
            trash().remove(key, System.currentTimeMillis());
        } catch (IOException e) {
            Log.w(TAG, "trash entry kept", e);
        }
    }

    /** Play's choice, written at once: the game runs in another process and reads it from disk. */
    private static void choose(@Nullable String key) {
        if (key == null) return;
        LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).commit();
    }

    /** After a delete: the home screen the editor closes onto offers "Geri al" for it. */
    @MainThread
    public static void offerUndo(String key, String name) {
        sPendingUndo = new Undo(key, name, SystemClock.elapsedRealtime());
    }

    /** Whether a delete waits for its "Geri al" (and is recent enough to offer it). */
    @MainThread
    public static boolean hasUndo() {
        Undo undo = sPendingUndo;
        if (undo == null) return false;
        if (SystemClock.elapsedRealtime() - undo.offeredAt <= UNDO_PICKUP_MS) return true;
        sPendingUndo = null;
        return false;
    }

    /** The delete to offer "Geri al" for, once; null when there is none or it is old. */
    @MainThread
    @Nullable
    public static Undo takeUndo() {
        if (!hasUndo()) return null;
        Undo undo = sPendingUndo;
        sPendingUndo = null;
        return undo;
    }
}
