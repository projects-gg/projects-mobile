package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The 0.5.4 review (for 0.5.5): which chat a voice room shows, sticky per room like the desktop's. */
public class RoomChatChoiceTest {
    private static final String ROOM = RoomChatChoice.ROOM;
    private static final String NONE = RoomChatChoice.NONE;

    @Test
    public void aFriendRoomOfTwoShowsTheFriendsMessagesUntilItGrows() {
        // A mutual friend, chat on, the friends feature connected: the friend's messages.
        String dm = RoomChatChoice.decide(null, false, false, "u1", true, true, true, true);
        assertEquals("dm:u1", dm);
        assertEquals("u1", RoomChatChoice.friendOf(dm));
        // A reconnect gap (no friend list, no chat switch for a moment) keeps it.
        assertEquals(dm, RoomChatChoice.decide(dm, false, false, "u1", false, false, true, false));
        // The friend left for a moment (nobody else there): still theirs.
        assertEquals(dm, RoomChatChoice.decide(dm, false, false, null, false, true, true, true));
        // The room grew to three: the room's chat for good (or none while the room offers none).
        assertEquals(ROOM, RoomChatChoice.decide(dm, false, true, "u1", true, true, true, true));
        assertEquals(NONE, RoomChatChoice.decide(dm, false, true, "u1", true, true, false, true));
    }

    @Test
    public void otherwiseTheRoomsChatAndNothingThatFlipsInAMoment() {
        // Not a friend, or chat off: the room's chat; a crew room always.
        assertEquals(ROOM, RoomChatChoice.decide(null, false, false, "u9", false, true, true, true));
        assertEquals(ROOM, RoomChatChoice.decide(null, false, false, "u1", true, false, true, true));
        assertEquals(ROOM, RoomChatChoice.decide("dm:u1", true, false, "u1", true, true, true, true));
        assertEquals(NONE, RoomChatChoice.decide(null, true, false, null, false, true, false, true));
        // The friends feature cannot say yet: no chat area rather than one that changes in a moment.
        assertEquals(NONE, RoomChatChoice.decide(null, false, false, "u1", false, false, true, false));
        // A room chat chosen while the friends feature could say stays one.
        assertEquals(ROOM, RoomChatChoice.decide(ROOM, false, false, "u1", false, false, true, false));
        assertNull(RoomChatChoice.friendOf(ROOM));
    }

    @Test
    public void onlySettledChoicesAreKept() {
        RoomChatChoice.forgetAll();
        String room = "f0123456789abcdef";
        RoomChatChoice.keep(room, NONE, true);
        assertNull(RoomChatChoice.kept(room));
        RoomChatChoice.keep(room, ROOM, false);
        assertNull(RoomChatChoice.kept(room));
        RoomChatChoice.keep(room, "dm:u1", false);
        assertEquals("dm:u1", RoomChatChoice.kept(room));
        RoomChatChoice.keep(room, ROOM, true);
        assertEquals(ROOM, RoomChatChoice.kept(room));
        RoomChatChoice.forgetAll();
    }
}
