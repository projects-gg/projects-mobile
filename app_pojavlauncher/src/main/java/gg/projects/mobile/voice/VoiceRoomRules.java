package gg.projects.mobile.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * M053: who may manage a crew's voice rooms and how, the desktop's {@code roomPowers}, {@code limitChoices} and
 * {@code roomNameFits} (features/voice/rules.ts) over the service's own checks (voice-hub.ts {@code changeGate}): only
 * while Studio lets crews manage rooms ({@code hello.voice.manage}), never "Genel"; the leader and moderators edit,
 * order and delete every room, a room's opener edits and deletes their own. The crew's adjustable permissions play no
 * part. The service checks every change again.
 */
public final class VoiceRoomRules {
    private VoiceRoomRules() {}

    /** The crew room people limit's ceiling on any crew (Studio's range for {@code voiceCrewMax}). */
    public static final int LIMIT_CEILING = 25;
    public static final int NAME_MIN = 3;
    public static final int NAME_MAX = 24;
    /** The service's {@code /v1/voice/crew/order} takes at most ten room ids. */
    public static final int ORDER_MAX = 10;

    public static final class Powers {
        public final boolean edit;
        public final boolean order;
        public final boolean remove;

        Powers(boolean edit, boolean order, boolean remove) {
            this.edit = edit;
            this.order = order;
            this.remove = remove;
        }

        public boolean any() {
            return edit || order || remove;
        }
    }

    private static final Powers NONE = new Powers(false, false, false);

    public static Powers powers(boolean manage, boolean founder, boolean moderator, VoiceRings.CrewRoom room) {
        if (!manage || room.general()) return NONE;
        boolean mod = founder || moderator;
        return new Powers(mod || room.own, mod, mod || room.own);
    }

    /** The people limits a room can take: 2 up to the crew's maximum (the last one sends "none"). */
    public static List<Integer> limitChoices(int crewMax) {
        List<Integer> out = new ArrayList<>();
        int top = Math.min(LIMIT_CEILING, Math.max(2, crewMax));
        for (int n = 2; n <= top; n++) out.add(n);
        return out;
    }

    /** The limit to send: null (no own limit) at or over the crew's maximum. */
    public static Integer limitToSend(int chosen, int crewMax) {
        return chosen >= Math.min(LIMIT_CEILING, crewMax) ? null : chosen;
    }

    /** A room's shown limit for the editor: its own, else the crew's maximum. */
    public static int limitShown(int roomMax, int crewMax) {
        int top = Math.min(LIMIT_CEILING, Math.max(2, crewMax));
        return roomMax > 0 ? Math.max(2, Math.min(roomMax, top)) : top;
    }

    /** 3 to 24 characters once trimmed (the service checks the letters and the word filter). */
    public static boolean nameFits(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        return length >= NAME_MIN && length <= NAME_MAX;
    }

    /**
     * The crew's room order after moving {@code room} one place up ({@code delta} -1) or down (+1) among the rooms other
     * than "Genel", or null when it cannot move that way. {@code rooms} as the screen lists them.
     */
    public static List<String> moved(List<VoiceRings.CrewRoom> rooms, String room, int delta) {
        List<String> ids = new ArrayList<>();
        for (VoiceRings.CrewRoom r : rooms) if (!r.general()) ids.add(r.id);
        int at = ids.indexOf(room);
        int to = at + delta;
        if (at < 0 || to < 0 || to >= ids.size()) return null;
        ids.remove(at);
        ids.add(to, room);
        return ids.size() > ORDER_MAX ? new ArrayList<>(ids.subList(0, ORDER_MAX)) : ids;
    }
}
