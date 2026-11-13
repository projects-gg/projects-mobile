package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import gg.projects.mobile.voice.VoiceMachine.Effect;
import gg.projects.mobile.voice.VoiceMachine.Start;
import gg.projects.mobile.voice.VoiceWire.Kind;
import gg.projects.mobile.voice.VoiceWire.Message;

/**
 * SQOL (phone 0.5.4): the voice room's text chat ({@code rchat1}) as SQOL-S's service speaks it (the lead's sqol.md §2.2,
 * sqol-s.md "Sözleşmeye notlar"): the frames, the log's merge rule, unread, the player's sends and their refusals, who
 * may delete, a share's state ({@code share2}'s {@code st}).
 */
public class RoomChatTest {
    static final String ROOM = VoiceMachineTest.ROOM;
    static final long UNIX = VoiceMachineTest.UNIX;

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static String line(String u, long at, String text) {
        return "{\"id\":\"" + at + "\",\"u\":\"" + u + "\",\"at\":" + at + ",\"text\":\"" + text + "\",\"seal\":\"s" + at + "\"}";
    }

    static String sticker(String u, long at, String st) {
        return "{\"id\":\"" + at + "\",\"u\":\"" + u + "\",\"at\":" + at + ",\"st\":\"" + st + "\",\"seal\":\"s" + at + "\"}";
    }

    static Message hello(boolean chat, boolean grew, String role, String leader, String mods, String... people) {
        return VoiceWire.parse("{\"t\":\"hello\",\"room\":{\"id\":\"" + ROOM + "\",\"kind\":\"f\",\"leader\":\"" + leader + "\",\"mods\":["
                + mods + "],\"max\":10" + (chat ? ",\"chat\":true" : "") + (grew ? ",\"grew\":true" : "") + "},\"me\":{\"role\":\""
                + role + "\"},\"people\":[" + String.join(",", people) + "],\"rings\":[],\"sdp\":\"v=0 answer\","
                + "\"resume\":\"v1.resume.mac\",\"now\":" + UNIX + "}");
    }

    /** A live stay in a friend room whose hello says {@code chat}; the generation of its socket. */
    static long live(VoiceMachine machine, Message hello) {
        List<Effect> effects = machine.join(new Start(ROOM, "v1.join.mac", null, false), VoiceMachineTest.SERVICE, 1_000, UNIX);
        long generation = VoiceMachineTest.connectGeneration(effects);
        machine.engineAttached();
        machine.socketOpen(generation, 1_100);
        machine.engineOffer("v=0 offer", "0");
        machine.socketMessage(generation, hello, 1_300, UNIX);
        machine.engineConnected();
        return generation;
    }

    private static List<String> frames(List<Effect> effects) {
        List<String> out = new ArrayList<>();
        for (Effect e : effects) if (e.kind == Effect.Kind.SEND) out.add(e.frame);
        return out;
    }

    private static int rid(String frame) {
        return json(frame).get("rid").getAsInt();
    }

    // ---- the wire ----

    @Test
    public void clientFramesAsTheContractWritesThem() {
        assertEquals(json("{\"t\":\"rchat\",\"rid\":12,\"text\":\"selam\"}"), json(VoiceWire.roomChat("selam", 12)));
        assertEquals(json("{\"t\":\"rchat\",\"rid\":13,\"st\":\"pl1/gg\"}"), json(VoiceWire.roomSticker("pl1/gg", 13)));
        assertEquals(json("{\"t\":\"rchat_del\",\"rid\":14,\"id\":\"1731500000123\"}"), json(VoiceWire.roomChatDelete("1731500000123", 14)));
    }

    @Test
    public void roomFramesReadAsTheServiceSendsThem() {
        Message m = VoiceWire.parse("{\"t\":\"rchat\",\"m\":" + line("u1", 1731500000123L, "selam") + "}");
        assertEquals(Kind.RCHAT, m.kind);
        assertEquals("1731500000123", m.line.id);
        assertEquals("u1", m.line.u);
        assertEquals("selam", m.line.text);
        assertNull(m.line.st);
        Message st = VoiceWire.parse("{\"t\":\"rchat\",\"m\":" + sticker("u1", 1731500000124L, "pl1/gg") + "}");
        assertEquals("pl1/gg", st.line.st);
        assertNull(st.line.text);
        // A message that does not read changes nothing: no seal, both bodies, neither, a bad id.
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("{\"t\":\"rchat\",\"m\":{\"id\":\"5\",\"u\":\"u1\",\"at\":5,\"text\":\"x\"}}").kind);
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("{\"t\":\"rchat\",\"m\":{\"id\":\"5\",\"u\":\"u1\",\"at\":5,\"text\":\"x\",\"st\":\"pl1/gg\",\"seal\":\"s\"}}").kind);
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("{\"t\":\"rchat\",\"m\":{\"id\":\"5\",\"u\":\"u1\",\"at\":5,\"seal\":\"s\"}}").kind);
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("{\"t\":\"rchat\",\"m\":{\"id\":\"05\",\"u\":\"u1\",\"at\":5,\"text\":\"x\",\"seal\":\"s\"}}").kind);

        Message log = VoiceWire.parse("{\"t\":\"rchat_log\",\"ms\":[" + line("u1", 10, "a") + ",{\"id\":\"x\"}," + line("u2", 20, "b") + "]}");
        assertEquals(Kind.RCHAT_LOG, log.kind);
        assertEquals(2, log.lines.size());
        assertEquals(Kind.RCHAT_LOG, VoiceWire.parse("{\"t\":\"rchat_log\",\"ms\":[]}").kind);

        Message deleted = VoiceWire.parse("{\"t\":\"rchat_deleted\",\"id\":\"10\",\"by\":\"mod\"}");
        assertEquals(Kind.RCHAT_DELETED, deleted.kind);
        assertEquals("10", deleted.chatId);
        assertEquals("mod", deleted.chatBy);
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("{\"t\":\"rchat_deleted\",\"id\":\"abc\",\"by\":\"self\"}").kind);

        Message ack = VoiceWire.parse("{\"t\":\"ack\",\"rid\":12,\"id\":\"30\",\"at\":30,\"m\":" + line("me", 30, "temiz") + "}");
        assertEquals(Integer.valueOf(12), ack.rid);
        assertEquals("temiz", ack.line.text);
        Message error = VoiceWire.parse("{\"t\":\"error\",\"rid\":12,\"code\":\"chat_rejected\",\"reason\":\"link\",\"rule\":\"shortener\"}");
        assertEquals("chat_rejected", error.code);
        assertEquals("link", error.reason);
        assertEquals("shortener", error.rule);
        Message muted = VoiceWire.parse("{\"t\":\"error\",\"rid\":13,\"code\":\"chat_muted\",\"until\":1800000600}");
        assertEquals(Long.valueOf(1_800_000_600L), muted.errorUntil);

        assertTrue(hello(true, true, "u", "x1", "").chat);
        assertTrue(hello(true, true, "u", "x1", "").grew);
        assertFalse(hello(false, false, "u", "x1", "").chat);
        // Every `room` to an rchat1 socket says `chat` while it is on; without it the chat is off.
        assertTrue(VoiceWire.parse("{\"t\":\"room\",\"name\":null,\"leader\":\"x1\",\"mods\":[],\"chat\":true}").chat);
        assertFalse(VoiceWire.parse("{\"t\":\"room\",\"name\":null,\"leader\":\"x1\",\"mods\":[]}").chat);
    }

    @Test
    public void aSharesStateIsHoldOrMinOrLive() {
        Message state = VoiceWire.parse("{\"t\":\"state\",\"u\":\"u1\",\"sh\":[{\"n\":1,\"src\":\"game\",\"w\":2,\"st\":\"hold\"},"
                + "{\"n\":2,\"src\":\"window\",\"st\":\"min\"},{\"n\":3,\"src\":\"screen\",\"st\":\"live\"}]}");
        assertEquals("hold", state.sh.get(0).st);
        assertEquals("min", state.sh.get(1).st);
        assertNull(state.sh.get(2).st);
        assertFalse(new VoiceWire.Share(1, "game", 0, 0, "hold").equals(new VoiceWire.Share(1, "game", 0, 0)));
        assertTrue(new VoiceWire.Share(1, "game", 0, 0, "other").equals(new VoiceWire.Share(1, "game", 0, 0)));
    }

    // ---- the chat ----

    private static RoomChat.Line l(String u, long at, String text) {
        return RoomChat.Line.read(json(line(u, at, text)));
    }

    @Test
    public void theLogMergesAsTheServiceSays() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        // The stay's first log is the room's past: read.
        assertTrue(chat.merge(Arrays.asList(l("u1", 10, "a"), l("u2", 20, "b"))));
        assertEquals(0, chat.unread());
        assertTrue(chat.add(l("u1", 30, "c")));
        assertFalse(chat.add(l("u1", 30, "c")));
        assertEquals(1, chat.unread());
        // A later log replaces what the player holds from its first moment on; older ones stay.
        assertTrue(chat.merge(Arrays.asList(l("u2", 20, "b"), l("u1", 40, "d"))));
        List<String> ids = new ArrayList<>();
        for (RoomChat.Line x : chat.lines()) ids.add(x.id);
        assertEquals(Arrays.asList("10", "20", "40"), ids);
        // An empty log keeps everything (the room forgot its past when all its sockets dropped).
        assertFalse(chat.merge(new ArrayList<RoomChat.Line>()));
        assertEquals(3, chat.lines().size());
        assertEquals(1, chat.unread());
        assertTrue(chat.seen(40));
        assertEquals(0, chat.unread());
        assertFalse(chat.seen(40));
    }

    @Test
    public void anEmptyFirstLogMakesEveryLaterMessageNew() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        assertFalse(chat.merge(new ArrayList<RoomChat.Line>()));
        chat.add(l("u1", 5, "a"));
        chat.add(l("me", 6, "mine"));
        assertEquals(1, chat.unread());
    }

    @Test
    public void deletedMessagesStayAsQuietLinesAndNeverCount() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        chat.merge(new ArrayList<RoomChat.Line>());
        chat.add(l("u1", 5, "a"));
        assertTrue(chat.deleted("5", "mod"));
        assertFalse(chat.deleted("5", "mod"));
        assertFalse(chat.deleted("99", "staff"));
        RoomChat.Line gone = chat.line("5");
        assertEquals(RoomChat.DELETED, gone.state);
        assertEquals("mod", gone.by);
        assertNull(gone.text);
        assertEquals(0, chat.unread());
        assertEquals("self", deletedBy(chat, "x"));
    }

    private static String deletedBy(RoomChat chat, String by) {
        chat.add(l("u2", 7, "b"));
        chat.deleted("7", by);
        return chat.line("7").by;
    }

    @Test
    public void offTakesTheMessagesAlongAndKeepIsBounded() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        chat.merge(new ArrayList<RoomChat.Line>());
        for (int i = 1; i <= RoomChat.KEEP + 5; i++) chat.add(l("u1", i, "m" + i));
        assertEquals(RoomChat.KEEP, chat.lines().size());
        assertEquals("6", chat.lines().get(0).id);
        RoomChat.Line last = chat.lines().get(chat.lines().size() - 1);
        assertEquals(RoomChat.REPORT_BEFORE, chat.before(last).size());
        assertTrue(chat.setOn(false));
        assertTrue(chat.lines().isEmpty());
        assertFalse(chat.setOn(false));
    }

    // ---- the stay ----

    @Test
    public void aSendShowsAtOnceAndTheRoomsCopyTakesItsPlace() {
        VoiceMachine machine = VoiceMachineTest.ready();
        long g = live(machine, hello(true, false, "l", "me", "", VoiceMachineTest.person("u1", 1)));
        machine.socketMessage(g, VoiceWire.parse("{\"t\":\"rchat_log\",\"ms\":[" + line("u1", 10, "eski") + "]}"), 1_400, UNIX);
        VoiceState state = machine.view(1_500, UNIX);
        assertTrue(state.chat);
        assertEquals(0, state.chatUnread);

        List<Effect> sent = machine.chatSend("selam", null);
        List<String> frames = frames(sent);
        assertEquals(1, frames.size());
        assertEquals("rchat", json(frames.get(0)).get("t").getAsString());
        assertEquals("selam", json(frames.get(0)).get("text").getAsString());
        RoomChat.View view = machine.chatView();
        RoomChat.Line pending = view.lines.get(view.lines.size() - 1);
        assertEquals(RoomChat.PENDING, pending.state);
        assertTrue(pending.mine);

        int rid = rid(frames.get(0));
        List<Effect> acked = machine.socketMessage(g, VoiceWire.parse("{\"t\":\"ack\",\"rid\":" + rid + ",\"id\":\"20\",\"at\":20,\"m\":"
                + line("me", 20, "selam") + "}"), 1_600, UNIX);
        assertTrue(VoiceMachineTest.kind(acked, Effect.Kind.CHAT));
        view = machine.chatView();
        assertEquals(2, view.lines.size());
        RoomChat.Line kept = view.line("20");
        assertNotNull(kept);
        assertTrue(kept.sent());
        assertTrue(kept.canDelete);
        // A message of someone else counts until seen.
        machine.socketMessage(g, VoiceWire.parse("{\"t\":\"rchat\",\"m\":" + line("u1", 30, "naber") + "}"), 1_700, UNIX);
        assertEquals(1, machine.view(1_800, UNIX).chatUnread);
        assertTrue(VoiceMachineTest.kind(machine.chatSeen(30), Effect.Kind.CHANGED));
        assertEquals(0, machine.view(1_800, UNIX).chatUnread);
        // A sticker goes as `st`.
        assertEquals("pl1/gg", json(frames(machine.chatSend(null, "pl1/gg")).get(0)).get("st").getAsString());
    }

    @Test
    public void aRefusalStaysOnTheMessageAndALostAnswerMayGoAgain() {
        VoiceMachine machine = VoiceMachineTest.ready();
        long g = live(machine, hello(true, false, "u", "x1", "", VoiceMachineTest.person("x1", 1)));
        int rid = rid(frames(machine.chatSend("bit.ly/x", null)).get(0));
        machine.socketMessage(g, VoiceWire.parse("{\"t\":\"error\",\"rid\":" + rid + ",\"code\":\"chat_rejected\",\"reason\":\"link\","
                + "\"rule\":\"shortener\"}"), 1_500, UNIX);
        RoomChat.Line refused = machine.chatView().lines.get(0);
        assertEquals(RoomChat.FAILED, refused.state);
        assertEquals("link", refused.reason);
        assertEquals("shortener", refused.rule);
        assertFalse(refused.retriable());
        assertTrue(VoiceMachineTest.kind(machine.chatDiscard(refused.rid), Effect.Kind.CHAT));
        assertTrue(machine.chatView().lines.isEmpty());

        // The connection goes before the answer: the send shows as not sent and may go again.
        machine.chatSend("hey", null);
        machine.socketClosed(g, 1006, 2_000);
        RoomChat.Line lost = machine.chatView().lines.get(0);
        assertEquals(RoomChat.FAILED, lost.state);
        assertTrue(lost.retriable());
        // While away nothing is sent: a retry shows as not sent again.
        assertTrue(frames(machine.chatRetry(lost.rid)).isEmpty());
        assertEquals(RoomChat.FAILED, machine.chatView().lines.get(0).state);
    }

    @Test
    public void whoMayDeleteFollowsTheRoomsRule() {
        // A moderator: a member's message yes, the leader's no.
        VoiceMachine machine = VoiceMachineTest.ready();
        long g = live(machine, hello(true, false, "m", "x1", "\"me\"", VoiceMachineTest.person("x1", 1), VoiceMachineTest.person("u2", 2)));
        machine.socketMessage(g, VoiceWire.parse("{\"t\":\"rchat_log\",\"ms\":[" + line("x1", 10, "lider") + "," + line("u2", 11, "üye")
                + "]}"), 1_400, UNIX);
        RoomChat.View view = machine.chatView();
        assertFalse(view.line("10").canDelete);
        assertTrue(view.line("11").canDelete);
        String frame = frames(machine.chatDelete("11").effects).get(0);
        assertEquals("rchat_del", json(frame).get("t").getAsString());
        assertEquals("11", json(frame).get("id").getAsString());
        assertNotNull(machine.chatDelete("99").refused);
        machine.socketMessage(g, VoiceWire.parse("{\"t\":\"rchat_deleted\",\"id\":\"11\",\"by\":\"mod\"}"), 1_500, UNIX);
        assertEquals(RoomChat.DELETED, machine.chatView().line("11").state);
        assertFalse(machine.chatView().line("11").canDelete);
    }

    @Test
    public void aFriendRoomGrowsForGoodAndStudioSwitchesTheChat() {
        VoiceMachine machine = VoiceMachineTest.ready();
        long g = live(machine, hello(true, false, "l", "me", "", VoiceMachineTest.person("u1", 1)));
        assertFalse(machine.view(1_400, UNIX).grew);
        machine.socketMessage(g, VoiceWire.parse("{\"t\":\"joined\",\"person\":" + VoiceMachineTest.person("u2", 2) + "}"), 1_500, UNIX);
        assertTrue(machine.view(1_600, UNIX).grew);
        machine.socketMessage(g, VoiceWire.parse("{\"t\":\"left\",\"u\":\"u2\",\"why\":\"left\"}"), 1_700, UNIX);
        assertTrue(machine.view(1_800, UNIX).grew);
        // Studio turns room chat off: `room` without `chat`.
        List<Effect> off = machine.socketMessage(g, VoiceWire.parse("{\"t\":\"room\",\"name\":null,\"leader\":\"me\",\"mods\":[]}"), 1_900, UNIX);
        assertTrue(VoiceMachineTest.kind(off, Effect.Kind.CHAT));
        assertFalse(machine.view(2_000, UNIX).chat);
        assertTrue(machine.chatSend("x", null).isEmpty());
        // A hello that says the room grew before the player came.
        VoiceMachine other = VoiceMachineTest.ready();
        live(other, hello(true, true, "u", "x1", "", VoiceMachineTest.person("x1", 1)));
        assertTrue(other.view(1_400, UNIX).grew);
    }

    @Test
    public void theChatGoesWithTheStay() {
        VoiceMachine machine = VoiceMachineTest.ready();
        live(machine, hello(true, false, "l", "me", "", VoiceMachineTest.person("u1", 1)));
        assertNotNull(machine.chatView());
        machine.leave(3_000);
        assertNull(machine.chatView());
        assertTrue(machine.chatSend("x", null).isEmpty());
    }

    @Test
    public void theViewCrossesTheProcessesWhole() {
        RoomChat chat = new RoomChat("me");
        chat.setOn(true);
        chat.merge(Arrays.asList(l("u1", 10, "a")));
        chat.sending(4, "b", null);
        RoomChat.View view = chat.view(ROOM, u -> false);
        RoomChat.View back = RoomChat.View.read(view.json());
        assertNotNull(back);
        assertEquals(ROOM, back.room);
        assertEquals(2, back.lines.size());
        assertEquals(RoomChat.PENDING, back.lines.get(1).state);
        assertEquals(10, back.newest().at);
        assertNull(RoomChat.View.read("{"));
        assertNull(RoomChat.View.read(null));
    }
}
