package gg.projects.mobile.afk.store;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.afk.api.AfkEnd;
import gg.projects.mobile.content.AtomicFiles;

/**
 * PHONE-AFK: AFK that ended while accounts were running and nobody stopped it ({@link AfkEnd}). The {@code :afk}
 * process left accounts in {@link AfkRuntime#running} and is gone: Android 11+ says why
 * ({@code getHistoricalProcessExitReasons}), older phones do not. Kept in {@code no_backup/afk/end.json} until the player
 * has seen it; the same process is never reported twice (its pid and start time are kept). Written by the screens, the
 * watchdog's receiver and a new {@code :afk} service (each write whole and atomic). MOB-NEXT: when AFK came back by
 * itself (Android restarted the service, or the watchdog did, and the accounts run again) that end is set aside
 * ({@link #cameBack}): the morning notice is for a phone that really stopped AFK.
 */
public final class AfkEnds {
    private AfkEnds() {}

    public static final String FILE = "end.json";
    private static final long MAX_BYTES = 16 * 1024;
    private static final Object LOCK = new Object();

    /** The end not yet dismissed, or null. */
    public static AfkEnd read(File afkDir) {
        File file = new File(afkDir, FILE);
        if (!file.isFile()) return null;
        try {
            JsonElement root = JsonParser.parseString(new String(AtomicFiles.read(file, MAX_BYTES), StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            if (AfkStore.bool(o, "dismissed", false)) return null;
            List<String> names = new ArrayList<>();
            JsonElement n = o.get("names");
            if (n != null && n.isJsonArray()) {
                for (JsonElement e : n.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) names.add(e.getAsString());
                }
            }
            return new AfkEnd(kindOfKey(AfkStore.string(o, "kind")), AfkStore.number(o, "at", 0), names);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static void dismiss(File afkDir) {
        synchronized (LOCK) {
            File file = new File(afkDir, FILE);
            if (!file.isFile()) return;
            try {
                JsonObject o = JsonParser.parseString(new String(AtomicFiles.read(file, MAX_BYTES), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                o.addProperty("dismissed", true);
                AtomicFiles.write(file, o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /**
     * MOB-NEXT: AFK came back by itself after the process {@code pid} (its service started at {@code startedAt}) was
     * closed: Android restarted the service, or the watchdog did, and its accounts run again. The end that process left
     * needs no notice ("Telefonun AFK'yı 06:13'te kapattı." showed in the morning although AFK had run on). Another
     * process's end (one the player has not seen) stays.
     */
    public static void cameBack(File afkDir, int pid, long startedAt) {
        if (pid == 0) return;
        synchronized (LOCK) {
            File file = new File(afkDir, FILE);
            if (!file.isFile()) return;
            try {
                JsonObject o = JsonParser.parseString(new String(AtomicFiles.read(file, MAX_BYTES), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (AfkStore.number(o, "pid", 0) != pid || AfkStore.number(o, "startedAt", 0) != startedAt) return;
                if (AfkStore.bool(o, "dismissed", false)) return;
                o.addProperty("dismissed", true);
                AtomicFiles.write(file, o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /**
     * Looks for a new end: {@code runtime} still lists running accounts, its process is gone ({@code afkAlive} false)
     * and it was not reported yet. Returns the new end (also written to {@code end.json}) or null.
     */
    public static AfkEnd detect(final Context context, File afkDir, AfkRuntime runtime, boolean afkAlive) {
        if (afkAlive) return null;
        return record(afkDir, runtime, pid -> {
            if (Build.VERSION.SDK_INT < 30) return null;
            ApplicationExitInfo info = exitInfo(context, pid);
            return info == null ? null : new Exit(kindOf(info.getReason()), info.getTimestamp());
        });
    }

    /** How a process ended, as Android 11+ says it. */
    public static final class Exit {
        final AfkEnd.Kind kind;
        final long at;

        public Exit(AfkEnd.Kind kind, long at) {
            this.kind = kind;
            this.at = at;
        }
    }

    /** Android's account of a gone process (asked only for an end not reported yet), or null when it does not say. */
    public interface Exits {
        Exit of(int pid);
    }

    /** {@link #detect}'s file side: the end {@code runtime}'s gone process left, unless already reported. */
    static AfkEnd record(File afkDir, AfkRuntime runtime, Exits exits) {
        if (runtime.running.isEmpty() || runtime.pid == 0) return null;
        synchronized (LOCK) {
            File file = new File(afkDir, FILE);
            if (file.isFile()) {
                try {
                    JsonObject o = JsonParser.parseString(new String(AtomicFiles.read(file, MAX_BYTES),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    if (AfkStore.number(o, "pid", 0) == runtime.pid && AfkStore.number(o, "startedAt", 0) == runtime.startedAt) {
                        return null;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Rewritten below.
                }
            }
            long at = runtime.updatedAt > 0 ? runtime.updatedAt : System.currentTimeMillis();
            AfkEnd.Kind kind = AfkEnd.Kind.UNKNOWN;
            Exit exit = exits.of(runtime.pid);
            if (exit != null) {
                kind = exit.kind;
                if (exit.at > 0) at = exit.at;
            }
            AfkEnd end = new AfkEnd(kind, at, new ArrayList<>(runtime.runningNames));
            JsonObject o = new JsonObject();
            o.addProperty("v", 1);
            o.addProperty("kind", keyOf(kind));
            o.addProperty("at", at);
            o.addProperty("pid", runtime.pid);
            o.addProperty("startedAt", runtime.startedAt);
            // The player's own stop from Android's settings needs no notice.
            o.addProperty("dismissed", kind == AfkEnd.Kind.STOPPED_BY_PLAYER);
            JsonArray names = new JsonArray();
            for (String name : runtime.runningNames) names.add(name);
            o.add("names", names);
            try {
                AtomicFiles.write(file, o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Reported once more next time at worst.
            }
            return end;
        }
    }

    private static ApplicationExitInfo exitInfo(Context context, int pid) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return null;
            List<ApplicationExitInfo> list = manager.getHistoricalProcessExitReasons(context.getPackageName(), pid, 1);
            return list == null || list.isEmpty() ? null : list.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Android's exit reason (ApplicationExitInfo.REASON_*) as the player's kind of end. */
    public static AfkEnd.Kind kindOf(int reason) {
        switch (reason) {
            case 10: // REASON_USER_REQUESTED: force stop, the task manager
            case 11: // REASON_USER_STOPPED (Android 14: "Stop" in the task manager)
                return AfkEnd.Kind.STOPPED_BY_PLAYER;
            case 3: // REASON_LOW_MEMORY
                return AfkEnd.Kind.LOW_MEMORY;
            case 4: // REASON_CRASH
            case 5: // REASON_CRASH_NATIVE
            case 6: // REASON_ANR
            case 7: // REASON_INITIALIZATION_FAILURE
                return AfkEnd.Kind.CRASHED;
            case 15: // REASON_PACKAGE_STATE_CHANGE
            case 16: // REASON_PACKAGE_UPDATED
                return AfkEnd.Kind.APP_UPDATED;
            case 0: // REASON_UNKNOWN
            case 1: // REASON_EXIT_SELF
                return AfkEnd.Kind.UNKNOWN;
            default:
                // SIGNALED (2), PERMISSION_CHANGE (8), EXCESSIVE_RESOURCE_USAGE (9), DEPENDENCY_DIED (12), OTHER (13),
                // FREEZER (14): the phone's (or its maker's) battery and memory managers.
                return AfkEnd.Kind.CLOSED_BY_PHONE;
        }
    }

    static String keyOf(AfkEnd.Kind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    static AfkEnd.Kind kindOfKey(String key) {
        for (AfkEnd.Kind k : AfkEnd.Kind.values()) {
            if (keyOf(k).equals(key)) return k;
        }
        return AfkEnd.Kind.UNKNOWN;
    }
}
