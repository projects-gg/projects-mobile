package gg.projects.mobile.mods;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.SodiumSupport;
import gg.projects.mobile.join.ProjectsProfile;

/**
 * What this phone knows about Sodium ({@link SodiumSupport}): the renderers it kept the game from
 * starting with, whether the player switched it back on since, and whether the offer to sets made
 * before 0.1.5 was answered. Kept in the app's own preferences: it describes the phone, not the
 * pack, so the pack state stays in the desktop's format and the player's choices stay theirs.
 */
public final class SodiumState {
    private SodiumState() {}

    private static final String PREFS = "projects_sodium";
    private static final String KEY_CRASHED = "crashed_renderers";
    private static final String KEY_OVERRIDE = "override";
    private static final String KEY_OFFER = "offer_answered";

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Renderers Sodium kept the game from starting with on this phone. */
    public static Set<String> crashedRenderers(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_CRASHED, null);
        return stored == null ? Collections.<String>emptySet() : new HashSet<>(stored);
    }

    /** The player switched the family back on while it was held off. */
    public static boolean override(Context context) {
        return prefs(context).getBoolean(KEY_OVERRIDE, false);
    }

    public static void setOverride(Context context, boolean override) {
        prefs(context).edit().putBoolean(KEY_OVERRIDE, override).apply();
    }

    /**
     * Sodium kept the game from starting with {@code renderer}: hold it off with that renderer from
     * now on (until the player switches it back on). Written at once: the process may end soon.
     */
    public static void recordCrash(Context context, @Nullable String renderer) {
        Set<String> crashed = crashedRenderers(context);
        crashed.add(SodiumSupport.rendererOrDefault(renderer));
        prefs(context).edit().putStringSet(KEY_CRASHED, crashed).putBoolean(KEY_OVERRIDE, false).commit();
    }

    /** "Sodium artık telefonunda da çalışıyor" was answered (or a choice made it pointless). */
    public static boolean offerAnswered(Context context) {
        return prefs(context).getBoolean(KEY_OFFER, false);
    }

    public static void answerOffer(Context context) {
        prefs(context).edit().putBoolean(KEY_OFFER, true).apply();
    }

    /** Why the Projects profile holds the Sodium family off right now. */
    @WorkerThread
    public static SodiumSupport.Block block(Context context) {
        return SodiumSupport.block(ProjectsProfile.knownRenderer(), crashedRenderers(context), override(context));
    }

    /** The items the Projects profile's syncs hold off ({@link SodiumSupport#heldOff}). */
    @WorkerThread
    public static Set<String> heldOff(Context context, PackManifest manifest) {
        return SodiumSupport.heldOff(manifest, block(context));
    }

    /** Sodium kept the game from starting on this phone with the Projects profile's renderer. */
    @WorkerThread
    public static boolean crashedHere(Context context) {
        return crashedRenderers(context).contains(SodiumSupport.rendererOrDefault(ProjectsProfile.knownRenderer()));
    }
}
