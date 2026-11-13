package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import gg.projects.mobile.voice.VoiceMachine.Effect;

/**
 * The 0.5.4 review's room chat findings (for 0.5.5): a blocked sender's messages go at once and stop counting; the
 * room's past never reads as new (seenAt for TalkBack); a lost answer never doubles a message; the player's own message
 * the room took never vanishes.
 */
public class RoomChatReviewTest {
    private static RoomChat.Line kept(String u, long at, String text) {
        return RoomChat.Line.read(JsonParser.parseString(RoomChatTest.line(u, at, text)).getAsJsonObject());
    }

    @Test
    public void aBlockedSendersMessagesGoAtOnceAndStayAway() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        chat.merge(new ArrayList<RoomChat.Line>());
        chat.add(kept("u1", 10, "a"));
        chat.add(kept("u2", 11, "b"));
        chat.add(kept("u1", 12, "c"));
        chat.add(kept("me", 13, "mine"));
        assertEquals(3, chat.unread());
        assertTrue(chat.dropSender("u1"));
        assertEquals(1, chat.unread());
        List<String> ids = new ArrayList<>();
        for (RoomChat.Line l : chat.lines()) ids.add(l.id);
        assertEquals(Arrays.asList("11", "13"), ids);
        // Later messages and logs of theirs stay away; the player is never dropped.
        assertFalse(chat.add(kept("u1", 14, "again")));
        // (A log replaces everything from its first moment on: the room's log holds the player's own message too.)
        chat.merge(Arrays.asList(kept("u2", 11, "b"), kept("me", 13, "mine"), kept("u1", 15, "log")));
        assertEquals(2, chat.lines().size());
        assertFalse(chat.dropSender("me"));
        assertFalse(chat.dropSender(null));
    }

    @Test
    public void theMachineDropsABlockedSenderInTheStay() {
        VoiceMachine machine = VoiceMachineTest.ready();
        long g = RoomChatTest.live(machine, RoomChatTest.hello(true, false, "l", "me", "", VoiceMachineTest.person("u1", 1),
                VoiceMachineTest.person("u2", 2)));
        machine.socketMessage(g, VoiceWire.parse("{\"t\":\"rchat_log\",\"ms\":[]}"), 1_400, RoomChatTest.UNIX);
        machine.socketMessage(g, VoiceWire.parse("{\"t\":\"rchat\",\"m\":" + RoomChatTest.line("u1", 20, "hey") + "}"), 1_500,
                RoomChatTest.UNIX);
        assertEquals(1, machine.view(1_600, RoomChatTest.UNIX).chatUnread);
        List<Effect> effects = machine.chatBlocked("u1");
        assertTrue(VoiceMachineTest.kind(effects, Effect.Kind.CHAT));
        assertEquals(0, machine.view(1_700, RoomChatTest.UNIX).chatUnread);
        assertNull(machine.chatView().line("20"));
        assertTrue(machine.chatBlocked("u1").isEmpty());
    }

    @Test
    public void theRoomsPastIsSeen() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        chat.merge(Arrays.asList(kept("u1", 10, "old"), kept("u2", 30, "older")));
        RoomChat.View view = chat.view(VoiceMachineTest.ROOM, u -> false);
        assertEquals(30, view.seenAt);
        chat.add(kept("u1", 40, "new"));
        view = chat.view(VoiceMachineTest.ROOM, u -> false);
        assertEquals(30, view.seenAt);
        assertEquals(1, view.unread);
    }

    @Test
    public void aLostAnswerNeverDoublesAMessage() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        chat.merge(Arrays.asList(kept("u1", 10, "hi")));
        chat.sending(1, "  selam  ", null);
        chat.sending(2, null, "pl1/gg");
        chat.sending(3, "kayıp", null);
        // The connection went before the answers.
        assertEquals(Arrays.asList(1, 2, 3), chat.failWaiting());
        // The room took the first two: they come with its next log; the third never reached it.
        RoomChat.Line sticker = RoomChat.Line.read(JsonParser.parseString(RoomChatTest.sticker("me", 21, "pl1/gg")).getAsJsonObject());
        chat.merge(Arrays.asList(kept("u1", 10, "hi"), kept("me", 20, "selam"), sticker));
        assertEquals(1, chat.outbox().size());
        assertEquals("kayıp", chat.outbox().get(0).text);
        assertEquals(3, chat.lines().size());
    }

    @Test
    public void thePlayersOwnMessageNeverVanishes() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        chat.merge(new ArrayList<RoomChat.Line>());
        for (int rid = 1; rid <= RoomChat.OUTBOX + 2; rid++) chat.sending(rid, "m" + rid, null);
        assertEquals(RoomChat.OUTBOX, chat.outbox().size());
        // The first send left the outbox's cap, its answer still comes: the message shows.
        assertTrue(chat.acked(1, kept("me", 50, "m1")));
        assertEquals("50", chat.lines().get(0).id);
        assertEquals("p1", chat.lines().get(0).was);
        // An answer without the message for a send nobody holds changes nothing.
        assertFalse(chat.acked(99, null));
    }
}
