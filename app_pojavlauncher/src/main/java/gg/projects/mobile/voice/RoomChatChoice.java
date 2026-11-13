package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Which chat a voice room shows (the lead's sqol.md §1.3, sticky per room like the desktop's; after 0.5.4): a friend
 * room that never grew past two, whose other person is a mutual friend, with chat on, shows that friend's messages,
 * until the room grows; then the room's own chat for good. A gap in the friends feature (a reconnect: no friend list,
 * no chat switch for a moment) never flips it. Pure Java (JVM-tested).
 */
public final class RoomChatChoice {
    private RoomChatChoice() {}

    public static final String ROOM = "room";
    public static final String NONE = "none";
    private static final String DM = "dm:";

    /** The choice kept for each room this run (rooms are short; a handful at most). */
    private static final Map<String, String> KEPT = new HashMap<>();

    /**
     * The choice now: {@code "dm:<friend>"}, {@link #ROOM} or {@link #NONE}.
     *
     * @param previous      the choice kept for the room (null: none yet)
     * @param other         the friend room's other person (the one called while nobody came), or null
     * @param friend        {@code other} is a mutual friend
     * @param chatAvailable the friends feature's chat is on
     * @param roomChat      the room offers its own chat
     * @param friendsReady  the friends feature is connected (its friend list and switches can be trusted)
     */
    public static String decide(@Nullable String previous, boolean crewRoom, boolean grew, @Nullable String other,
                                boolean friend, boolean chatAvailable, boolean roomChat, boolean friendsReady) {
        String room = roomChat ? ROOM : NONE;
        if (crewRoom || grew) return room;
        if (previous != null && previous.startsWith(DM)) {
            // The friend's messages until the room grows (or another person takes the friend's place).
            if (other == null || previous.equals(DM + other)) return previous;
        }
        if (other != null && friendsReady && friend && chatAvailable) return DM + other;
        if (ROOM.equals(previous)) return room;
        // The friends feature cannot say yet: no chat area rather than one that changes in a moment.
        if (other != null && !friendsReady) return NONE;
        return room;
    }

    /** The friend of a {@code "dm:<friend>"} choice, else null. */
    @Nullable
    public static String friendOf(String choice) {
        return choice.startsWith(DM) ? choice.substring(DM.length()) : null;
    }

    @Nullable
    public static synchronized String kept(@Nullable String room) {
        return room == null ? null : KEPT.get(room);
    }

    /** Keeps a choice the facts settled (a friend's messages, or the room's chat while the friends feature could say). */
    public static synchronized void keep(@Nullable String room, String choice, boolean settled) {
        if (room == null || NONE.equals(choice)) return;
        if (choice.startsWith(DM) || settled) KEPT.put(room, choice);
    }

    static synchronized void forgetAll() {
        KEPT.clear();
    }
}
