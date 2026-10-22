package gg.projects.mobile.launch;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * FIX2 ("Bağlan"): a server a crew call, a "Birlikte oyna" invite or a plan leads to: Projects
 * ({@code play.projects.gg} with the Projects profile) or another server's shared address. It travels
 * in intents and in the launcher's preferences as one word: {@code projects} or the address. Pure Java
 * (the preferences are the platform's interface).
 */
public final class ConnectTarget {
    private static final String PROJECTS_WIRE = "projects";
    /** A pending join is used only this soon after it was asked for (a launch that never happened goes stale). */
    public static final long PENDING_MS = 15 * 60_000L;
    static final String KEY_PENDING = "projects_pending_join";
    static final String KEY_PENDING_AT = "projects_pending_join_at";

    /** Projects: its profile joins play.projects.gg. */
    public final boolean projects;
    /** {@code host} or {@code host:port}; {@link QuickPlay#PROJECTS_SERVER} for Projects. */
    public final String address;

    private ConnectTarget(boolean projects, String address) {
        this.projects = projects;
        this.address = address;
    }

    public static final ConnectTarget PROJECTS = new ConnectTarget(true, QuickPlay.PROJECTS_SERVER);

    /** Another server by the address a call or an invite carries; Projects' own hosts are Projects; null when not one. */
    @Nullable
    public static ConnectTarget server(@Nullable String address) {
        if (address == null) return null;
        String a = address.trim();
        while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        if (a.isEmpty() || !QuickPlay.isValidAddress(a)) return null;
        String host = a.lastIndexOf(':') >= 0 ? a.substring(0, a.lastIndexOf(':')) : a;
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("projects.gg") || lower.endsWith(".projects.gg")) return PROJECTS;
        return new ConnectTarget(false, a);
    }

    /** One word for an intent or the preferences. */
    public String wire() {
        return projects ? PROJECTS_WIRE : address;
    }

    @Nullable
    public static ConnectTarget read(@Nullable String wire) {
        if (wire == null || wire.isEmpty()) return null;
        if (PROJECTS_WIRE.equals(wire)) return PROJECTS;
        return server(wire);
    }

    /** What the player reads: "Projects" or the address. */
    public String shown() {
        return projects ? "Projects" : address;
    }

    // ---- the next launch's join (written by the launcher, read once by the game's process) ----

    /**
     * The next game start joins {@code target} (another server through the profile Play would start).
     * Written at once ({@code commit}): the game's process reads the file when it starts.
     */
    public static void setPending(SharedPreferences prefs, ConnectTarget target, long nowMs) {
        prefs.edit().putString(KEY_PENDING, target.address).putLong(KEY_PENDING_AT, nowMs).commit();
    }

    /** The address the starting game joins, once (it is cleared); null when none was asked for lately. */
    @Nullable
    public static String takePending(SharedPreferences prefs, long nowMs) {
        String address = prefs.getString(KEY_PENDING, null);
        if (address == null) return null;
        long at = prefs.getLong(KEY_PENDING_AT, 0);
        prefs.edit().remove(KEY_PENDING).remove(KEY_PENDING_AT).commit();
        if (at <= 0 || nowMs - at > PENDING_MS || nowMs < at - 60_000L) return null;
        return QuickPlay.isValidAddress(address) ? address : null;
    }

    /** A pending join that was never used goes (the player cancelled or the start failed). */
    public static void clearPending(SharedPreferences prefs) {
        prefs.edit().remove(KEY_PENDING).remove(KEY_PENDING_AT).apply();
    }
}
