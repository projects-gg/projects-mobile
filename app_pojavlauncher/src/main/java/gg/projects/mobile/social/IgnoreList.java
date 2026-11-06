package gg.projects.mobile.social;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * M053: "Yok say", the desktop's SOC-NEXT ignore list ({@code pl.socnext} {@code ignored}): people whose voice calls go
 * straight to missed (no card, tone or notification) and whose voice is silent for the player in a room; they never
 * know. Kept on this phone only (as on the desktop: per device, not on the service, and it stays across sign-outs),
 * oldest first, at most {@link #MAX}. Only while the signed config's {@code socNext} is on (off: nobody counts as
 * ignored, the list stays).
 *
 * <p>The launcher process owns it, in a file of its own ({@code projects_ignore}: the game process writes
 * {@code projects_social} from a stale copy, which would bring an old list back); the voice process and the game read the
 * effective list ({@link #KEY_VOICE}) from the file ({@link #quiet}, {@link #forVoice}); a running stay is told of changes
 * ({@code VoiceLink.setIgnored}) and reads it again when a screen binds. Besides voice, the phone's notifications from an
 * ignored person (friend requests, invites, direct messages, a call from them) stay quiet, as the desktop's notices.
 */
public final class IgnoreList {
    private IgnoreList() {}

    public static final int MAX = 200;
    private static final String FILE = "projects_ignore";
    private static final String KEY = "ignored";
    /** The list as it counts now (empty while {@code socNext} is off), for the voice process. */
    static final String KEY_VOICE = "voice_ignored";
    private static final String KEY_ON = "soc_next";

    public interface Listener {
        void onIgnoreList();
    }

    private static final List<Listener> sListeners = new ArrayList<>();

    public static void addListener(Listener listener) {
        if (!sListeners.contains(listener)) sListeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        sListeners.remove(listener);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Whether the signed config has {@code socNext} (its last known value). */
    public static boolean on(Context context) {
        return prefs(context).getBoolean(KEY_ON, false);
    }

    /** Everyone on the list, oldest first (also while it does not count). */
    public static List<String> all(Context context) {
        return parse(prefs(context).getString(KEY, null));
    }

    /** The list as it counts now: empty while {@code socNext} is off. */
    public static List<String> effective(Context context) {
        return on(context) ? all(context) : Collections.<String>emptyList();
    }

    public static boolean ignored(Context context, String id) {
        return on(context) && all(context).contains(id);
    }

    /** "Yok say" / "Yok saymayı bırak". Returns whether the list changed. */
    public static boolean set(Context context, String id, boolean ignore) {
        List<String> before = all(context);
        List<String> after = with(before, id, ignore);
        if (after.equals(before)) return false;
        prefs(context).edit().putString(KEY, toJson(after)).apply();
        changed(context);
        return true;
    }

    /** The signed config's {@code socNext} came (or changed). */
    public static void setOn(Context context, boolean on) {
        if (on(context) == on && prefs(context).contains(KEY_VOICE)) return;
        prefs(context).edit().putBoolean(KEY_ON, on).apply();
        changed(context);
    }

    private static void changed(Context context) {
        List<String> effective = effective(context);
        // Committed (not applied): the voice process may read it in a moment.
        prefs(context).edit().putString(KEY_VOICE, toJson(effective)).commit();
        gg.projects.mobile.voice.VoiceHub hub = gg.projects.mobile.voice.VoiceHub.instance();
        if (hub != null) hub.setIgnored(effective);
        gg.projects.mobile.voice.VoiceLink.get(context).setIgnored(effective);
        for (Listener l : new ArrayList<>(sListeners)) l.onIgnoreList();
    }

    /**
     * M053: the person is on the effective list, read from the file (any process: the game's, the voice's, a push's):
     * their notice stays quiet.
     */
    public static boolean quiet(Context context, @Nullable String id) {
        return id != null && forVoice(context).contains(id);
    }

    /**
     * The voice process's read of the effective list at a stay's start (another process wrote it: read from the file,
     * not a cached copy).
     */
    @SuppressWarnings("deprecation")
    public static List<String> forVoice(Context context) {
        SharedPreferences fresh = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS);
        return parse(fresh.getString(KEY_VOICE, null));
    }

    /** {@code list} with {@code id} added at the end (the oldest dropped past {@link #MAX}) or removed. */
    static List<String> with(List<String> list, String id, boolean ignore) {
        List<String> out = new ArrayList<>(list);
        if (id == null || id.isEmpty() || id.length() > 64) return out;
        if (ignore) {
            if (out.contains(id)) return out;
            out.add(id);
            while (out.size() > MAX) out.remove(0);
        } else {
            out.remove(id);
        }
        return out;
    }

    static List<String> parse(@Nullable String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return out;
            for (JsonElement e : root.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    String id = e.getAsString();
                    if (!id.isEmpty() && id.length() <= 64 && !out.contains(id)) out.add(id);
                }
            }
        } catch (RuntimeException ignored) {
            // A broken list reads as empty.
        }
        while (out.size() > MAX) out.remove(0);
        return out;
    }

    static String toJson(List<String> ids) {
        JsonArray array = new JsonArray();
        for (String id : ids) array.add(id);
        return array.toString();
    }
}
