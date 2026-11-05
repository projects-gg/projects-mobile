package gg.projects.mobile.voice;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.List;
import java.util.Map;

import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.SocialPrefs;

/**
 * Voice chat on the game's presence socket ({@code InGameSocial}, the game's process): it takes {@code voice1} while the
 * player keeps voice chat on, so friends can call them while they play, and a ring shows as a notification over the game
 * with Katıl (the stay starts at once: the app is in front with the game) and Reddet. The stay itself is the voice
 * process's, the same as from the launcher.
 */
public final class VoiceInGame {
    private VoiceInGame() {}

    @Nullable private static VoiceRings.Service sService;

    /** {@code voice1} while the last signed config has voice chat and the player keeps it on. */
    public static void addCaps(Context context, SocialPrefs prefs, List<String> caps) {
        if (prefs.lastConfig()[0] && prefs.lastVoiceConfig() && new VoicePrefs(context).enabled()) caps.add(VoiceRings.VOICE_CAP);
    }

    /** One frame of the game's socket; true when it was voice chat's. {@code names}: who is who, as the game knows. */
    public static boolean onFrame(Context context, JsonObject message, String me, Map<String, String> names, PendingIntent game) {
        String type = Json.str(message, "t", "");
        switch (type) {
            case "hello": {
                VoiceRings.Service service = VoiceRings.Service.read(Json.obj(message, "voice"));
                sService = service != null && service.on ? service : null;
                return false;
            }
            case "voice_state": {
                VoiceRings.Service service = VoiceRings.Service.read(Json.obj(message, "voice"));
                if (service != null) sService = service.on ? service : null;
                return true;
            }
            case "voice_ring": {
                VoiceRings.Ring ring = VoiceRings.Ring.read(Json.obj(message, "ring"));
                if (ring == null || sService == null || Json.bool(message, "quiet", false)) return true;
                // The room the player is in never rings (the hub rings no member of a room).
                long left = Math.max(0, ring.until - VoiceHub.unix()) * 1000;
                if (left <= 0 || ring.ticket == null) return true;
                Context c = LanguageManager.wrap(context);
                String name = names.get(ring.from);
                String who = name == null || name.isEmpty() ? c.getString(R.string.projects_voice_someone) : name;
                String title = VoiceWire.isFriendRoom(ring.room) ? who : c.getString(R.string.projects_voice_title);
                PendingIntent join = joinIntent(context, ring.room, ring.ticket, sService.forStay(), me, title);
                VoiceNotify.showRing(context, ring.room, who, null, left, join, VoiceActionReceiver.decline(context, ring.room), game);
                return true;
            }
            case "voice_ring_end": {
                String room = Json.str(message, "room");
                if (room != null) VoiceNotify.cancelRing(context, room);
                return true;
            }
            case "voice_rooms":
                return true;
            default:
                return false;
        }
    }

    /** "Katıl" over the game: the voice process starts the stay at once (the app is in front with its game). */
    private static PendingIntent joinIntent(Context context, String room, String ticket, VoiceMachine.Service service, String me,
                                            String title) {
        Intent intent = VoiceLink.joinIntent(context, room, ticket, null, false, service,
                new VoicePrefs(context).soundsSaved(), me, title);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        int code = ("game-join|" + room).hashCode();
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(context, code, intent, flags)
                : PendingIntent.getService(context, code, intent, flags);
    }
}
