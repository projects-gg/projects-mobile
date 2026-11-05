package gg.projects.mobile.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Map;

import gg.projects.mobile.social.SessionStore;
import gg.projects.mobile.social.SocialDebug;
import gg.projects.mobile.social.SocialPrefs;
import gg.projects.mobile.social.SocialService;

/**
 * A voice chat ring that came as a push (MOB-VOICE; the friends service sends one to the phones of a player it rings
 * whose session has no open socket): {@code voice_ring} ({@code room}, {@code sender}, {@code name}, {@code at},
 * {@code until}; no ticket: "Katıl" asks the service for it) and {@code voice_ring_end} ({@code room}: answered or
 * declined elsewhere, or taken back). Dropped unless it is for the account signed in here, the friends feature and voice
 * chat are on in the last signed config and the player keeps voice chat on.
 */
public final class VoicePush {
    private VoicePush() {}

    private static final String TAG = "ProjectsVoice";
    /** The registration's kind ({@code PUT /v1/push/device}). */
    public static final String KIND = "voice";
    /** The service's {@code features} entry that says it knows the kind. */
    public static final String FEATURE = "voiceRing";
    static final String RING = "voice_ring";
    static final String RING_END = "voice_ring_end";

    /** Whether the push is one of voice chat's (then it was handled here). */
    public static boolean handle(Context context, Map<String, String> data) {
        String type = data.get("type");
        if (!RING.equals(type) && !RING_END.equals(type)) return false;
        final Context app = context.getApplicationContext();
        if (!"1".equals(data.get("v"))) return true;
        final String room = data.get("room");
        if (!VoiceWire.isRoomId(room)) return true;
        if (RING_END.equals(type)) {
            new Handler(Looper.getMainLooper()).post(() -> VoiceHub.pushedRingEnd(app, room));
            return true;
        }
        SocialDebug debug = SocialDebug.read(app);
        SessionStore.Saved saved = new SessionStore(app, debug.override ? "session-debug.json" : "session.json").load();
        SocialPrefs prefs = new SocialPrefs(app);
        String to = data.get("to");
        boolean mine = saved.token != null && to != null && (to.equals(saved.userId) || to.equals(prefs.pushTag(saved.token)));
        if (!mine) {
            Log.i(TAG, "voice push dropped: not this session's");
            return true;
        }
        if (!prefs.lastConfig()[0] || !prefs.lastVoiceConfig() || !new VoicePrefs(app).enabled()) {
            Log.i(TAG, "voice push dropped: voice chat is off here");
            return true;
        }
        final String from = data.get("sender");
        final String name = data.get("name");
        final long at = parse(data.get("at"));
        final long until = parse(data.get("until"));
        if (from == null || until <= VoiceHub.unix()) return true;
        new Handler(Looper.getMainLooper()).post(() -> {
            VoiceLink link = VoiceLink.get(app);
            VoiceState state = link.state();
            // Already in that room on this phone: nothing rings.
            if (state != null && room.equals(state.room)) return;
            VoiceHub.pushedRing(app, room, from, name, at, until);
            if (SocialService.exists()) SocialService.get(app).pushArrived(RING);
        });
        return true;
    }

    private static long parse(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
