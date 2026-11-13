package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * After 0.5.4 (the lead, 13 Nov): a room chat send shows the moment it goes and keeps one row from the screen's echo
 * through the voice process's waiting send to the room's copy.
 */
public class RoomChatEchoesTest {
    private static RoomChat.View view(RoomChat chat) {
        return chat.view(VoiceMachineTest.ROOM, u -> false);
    }

    private static RoomChat.Line kept(String u, long at, String text) {
        return RoomChat.Line.read(com.google.gson.JsonParser.parseString(RoomChatTest.line(u, at, text)).getAsJsonObject());
    }

    @Test
    public void aSendIsOneRowFromItsEchoToTheRoomsCopy() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        chat.merge(Arrays.asList(kept("u1", 10, "selam")));
        RoomChatEchoes echoes = new RoomChatEchoes();
        // Before the voice process heard of it: the echo shows at once, last, waiting.
        RoomChat.Line echo = echoes.add("naber", null, "me", 1_000);
        List<RoomChat.Line> rows = echoes.rows(view(chat), 1_050);
        assertEquals(2, rows.size());
        assertEquals(echo.id, rows.get(1).id);
        assertEquals(RoomChat.PENDING, rows.get(1).state);
        assertTrue(rows.get(1).mine);
        String row = echoes.key(rows.get(1));

        // The voice process's waiting send takes the echo's row.
        chat.sending(7, "naber", null);
        rows = echoes.rows(view(chat), 1_200);
        assertEquals(2, rows.size());
        assertEquals("p7", rows.get(1).id);
        assertEquals(row, echoes.key(rows.get(1)));
        assertFalse(echoes.waiting());

        // The room's copy keeps it too.
        chat.acked(7, kept("me", 20, "naber"));
        rows = echoes.rows(view(chat), 1_400);
        assertEquals("20", rows.get(1).id);
        assertEquals("p7", rows.get(1).was);
        assertEquals(row, echoes.key(rows.get(1)));
        // Others' messages keep their own ids as rows.
        assertEquals("10", echoes.key(rows.get(0)));
    }

    @Test
    public void echoesMatchSendsInOrderAndAnUnansweredOneGoes() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        chat.merge(new ArrayList<RoomChat.Line>());
        RoomChatEchoes echoes = new RoomChatEchoes();
        RoomChat.Line first = echoes.add("a", null, "me", 0);
        RoomChat.Line second = echoes.add(null, "pl1/gg", "me", 10);
        String firstRow = echoes.key(first);
        String secondRow = echoes.key(second);
        chat.sending(1, "a", null);
        List<RoomChat.Line> rows = echoes.rows(view(chat), 100);
        assertEquals(Arrays.asList("p1", second.id), ids(rows));
        assertEquals(firstRow, echoes.key(rows.get(0)));
        // A refused send shows as the voice process says, in the same row.
        chat.sending(2, null, "pl1/gg");
        chat.failed(2, "chat_feature_off", null, null, null);
        rows = echoes.rows(view(chat), 200);
        assertEquals(Arrays.asList("p1", "p2"), ids(rows));
        assertEquals(secondRow, echoes.key(rows.get(1)));
        assertEquals(RoomChat.FAILED, rows.get(1).state);
        // An echo nothing answers goes after KEEP_MS.
        echoes.add("lost", null, "me", 1_000);
        assertEquals(3, echoes.rows(view(chat), 1_000 + RoomChatEchoes.KEEP_MS - 1).size());
        assertEquals(2, echoes.rows(view(chat), 1_000 + RoomChatEchoes.KEEP_MS).size());
        // A new room forgets the last one's.
        echoes.add("x", null, "me", 9_000);
        echoes.clear();
        assertFalse(echoes.waiting());
    }

    private static List<String> ids(List<RoomChat.Line> rows) {
        List<String> out = new ArrayList<>();
        for (RoomChat.Line l : rows) out.add(l.id);
        return out;
    }
}
