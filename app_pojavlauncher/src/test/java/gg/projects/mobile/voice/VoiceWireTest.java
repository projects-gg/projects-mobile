package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import gg.projects.mobile.voice.VoiceWire.ClosedWhy;
import gg.projects.mobile.voice.VoiceWire.Kind;
import gg.projects.mobile.voice.VoiceWire.LeftWhy;
import gg.projects.mobile.voice.VoiceWire.Message;
import gg.projects.mobile.voice.VoiceWire.ModAct;
import gg.projects.mobile.voice.VoiceWire.Role;

/** The room socket's frames exactly as the README writes them (the desktop's wire.rs tests). */
public class VoiceWireTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    public void clientFramesAsTheContractWritesThem() {
        assertEquals(json("{\"t\":\"join\",\"sdp\":\"v=0\",\"mid\":\"0\"}"), json(VoiceWire.join("v=0", "0")));
        assertEquals(json("{\"t\":\"join\"}"), json(VoiceWire.join(null, null)));
        assertEquals(json("{\"t\":\"sync\"}"), json(VoiceWire.sync()));
        assertEquals(json("{\"t\":\"answer\",\"sdp\":\"v=0 a\"}"), json(VoiceWire.answer("v=0 a")));
        assertEquals(json("{\"t\":\"set\",\"mute\":true,\"deaf\":false}"), json(VoiceWire.set(true, false)));
        assertEquals(json("{\"t\":\"q\",\"v\":1}"), json(VoiceWire.qualityFrame(1)));
        assertEquals(json("{\"t\":\"q\",\"v\":2}"), json(VoiceWire.qualityFrame(9)));
        assertEquals(json("{\"t\":\"turn\",\"rid\":2}"), json(VoiceWire.turn(2)));
        assertEquals(json("{\"t\":\"invite\",\"to\":\"u1\",\"rid\":3}"), json(VoiceWire.invite("u1", 3)));
        assertEquals(json("{\"t\":\"cancel\",\"to\":\"u1\",\"rid\":4}"), json(VoiceWire.cancel("u1", 4)));
        assertEquals(json("{\"t\":\"mod\",\"act\":\"mute\",\"to\":\"u2\",\"rid\":5}"),
                json(VoiceWire.mod(ModAct.MUTE, "u2", null, null, 5)));
        assertEquals(json("{\"t\":\"mod\",\"act\":\"move\",\"to\":\"u2\",\"room\":\"c0123456789abcdef\",\"rid\":6}"),
                json(VoiceWire.mod(ModAct.MOVE, "u2", "c0123456789abcdef", null, 6)));
        assertEquals(json("{\"t\":\"mod\",\"act\":\"name\",\"name\":\"Gece\",\"rid\":7}"),
                json(VoiceWire.mod(ModAct.NAME, null, null, "Gece", 7)));
        assertEquals(json("{\"t\":\"mod\",\"act\":\"close\",\"rid\":8}"), json(VoiceWire.mod(ModAct.CLOSE, null, null, null, 8)));
        assertEquals(json("{\"t\":\"leave\"}"), json(VoiceWire.leave()));
    }

    @Test
    public void helloReadsAsTheRoomSendsIt() {
        Message m = VoiceWire.parse("{\"t\":\"hello\",\"room\":{\"id\":\"f0123456789abcdef\",\"kind\":\"f\",\"name\":null,"
                + "\"leader\":\"u1\",\"mods\":[\"u2\"],\"max\":10},\"me\":{\"role\":\"m\"},\"people\":["
                + "{\"u\":\"u1\",\"j\":100,\"mute\":false,\"deaf\":false,\"sm\":false,\"q\":0},"
                + "{\"u\":\"u2\",\"j\":101,\"mute\":true,\"deaf\":false,\"sm\":true,\"q\":2}],"
                + "\"rings\":[{\"to\":\"u3\",\"until\":130}],\"sdp\":\"v=0 answer\",\"resume\":\"v1.a.b\",\"now\":105}");
        assertEquals(Kind.HELLO, m.kind);
        assertEquals("f0123456789abcdef", m.roomId);
        assertFalse(m.crewRoom);
        assertNull(m.name);
        assertEquals("u1", m.leader);
        assertEquals(1, m.mods.size());
        assertEquals(10, m.max);
        assertEquals(Role.MODERATOR, m.role);
        assertEquals(2, m.people.size());
        assertTrue(m.people.get(1).sm && m.people.get(1).mute);
        assertEquals(2, m.people.get(1).q);
        assertEquals(Long.valueOf(130), m.rings.get("u3"));
        assertEquals("v=0 answer", m.sdp);
        assertEquals("v1.a.b", m.resume);
        assertEquals(Long.valueOf(105), m.now);
        // A crew room after `sync`: no SDP.
        Message crew = VoiceWire.parse("{\"t\":\"hello\",\"room\":{\"id\":\"gabc\",\"kind\":\"c\",\"crew\":\"abc\","
                + "\"name\":\"Genel\",\"leader\":null,\"mods\":[],\"max\":15},\"me\":{\"role\":\"u\"},\"people\":[],"
                + "\"rings\":[],\"resume\":\"v1.c.d\",\"now\":1}");
        assertTrue(crew.crewRoom);
        assertEquals("abc", crew.crew);
        assertNull(crew.leader);
        assertNull(crew.sdp);
        // The secrets never show in a log line.
        assertEquals("hello", m.toString());
    }

    @Test
    public void roomMessagesReadAsTheRoomSendsThem() {
        Message offer = VoiceWire.parse("{\"t\":\"offer\",\"sdp\":\"v=0 o\",\"mids\":{\"1\":\"u2\",\"2\":\"u3\"}}");
        assertEquals(Kind.OFFER, offer.kind);
        assertEquals("u3", offer.mids.get("2"));
        Message joined = VoiceWire.parse("{\"t\":\"joined\",\"person\":{\"u\":\"77\",\"j\":9,\"card\":{\"id\":\"77\","
                + "\"username\":\"kumfirtinasi\",\"displayName\":\"KumFirtinasi\",\"avatar\":\"discord\"}}}");
        assertEquals(Kind.JOINED, joined.kind);
        assertEquals("KumFirtinasi", joined.person.card.name);
        // A card that does not read costs nothing but the card.
        Message odd = VoiceWire.parse("{\"t\":\"joined\",\"person\":{\"u\":\"u5\",\"j\":9,\"card\":{\"id\":7}}}");
        assertEquals("u5", odd.person.u);
        assertNull(odd.person.card);
        assertEquals(LeftWhy.GONE, VoiceWire.parse("{\"t\":\"left\",\"u\":\"u4\",\"why\":\"gone\"}").leftWhy);
        assertEquals(LeftWhy.LEFT, VoiceWire.parse("{\"t\":\"left\",\"u\":\"u4\",\"why\":\"vanished\"}").leftWhy);
        Message state = VoiceWire.parse("{\"t\":\"state\",\"u\":\"u2\",\"sm\":true}");
        assertEquals(Boolean.TRUE, state.sm);
        assertNull(state.mute);
        assertEquals(Integer.valueOf(2), VoiceWire.parse("{\"t\":\"state\",\"u\":\"u2\",\"q\":7}").q);
        Message room = VoiceWire.parse("{\"t\":\"room\",\"name\":\"Gece\",\"leader\":\"u1\",\"mods\":[],\"max\":4}");
        assertEquals(4, room.max);
        assertEquals(-1, VoiceWire.parse("{\"t\":\"room\",\"name\":\"Gece\",\"leader\":null,\"mods\":[\"u2\"]}").max);
        assertEquals(Role.LEADER, VoiceWire.parse("{\"t\":\"me\",\"role\":\"l\"}").role);
        assertEquals(VoiceWire.RingEndWhy.CANCELLED, VoiceWire.parse("{\"t\":\"ring_end\",\"to\":\"u5\",\"why\":\"cancelled\"}").ringEndWhy);
        assertEquals(Kind.KICKED, VoiceWire.parse("{\"t\":\"kicked\"}").kind);
        Message moved = VoiceWire.parse("{\"t\":\"moved\",\"room\":\"c0123456789abcdef\",\"ticket\":\"v1.x.y\"}");
        assertEquals("v1.x.y", moved.ticket);
        String[][] whys = {{"leader", "LEADER"}, {"alone", "ALONE"}, {"no_answer", "NO_ANSWER"}, {"elsewhere", "ELSEWHERE"},
                {"crew", "CREW"}, {"off", "OFF"}, {"budget", "BUDGET"}, {"staff", "STAFF"}, {"account", "ACCOUNT"},
                {"muted", "MUTED"}, {"sunset", "OFF"}};
        for (String[] why : whys) {
            assertEquals(why[0], ClosedWhy.valueOf(why[1]), VoiceWire.parse("{\"t\":\"closed\",\"why\":\"" + why[0] + "\"}").closedWhy);
        }
        assertEquals(Long.valueOf(1_700_000_000L),
                VoiceWire.parse("{\"t\":\"closed\",\"why\":\"muted\",\"until\":1700000000}").closedUntil);
        Message ack = VoiceWire.parse("{\"t\":\"ack\",\"rid\":2,\"ice\":{\"urls\":[\"turn:turn.cloudflare.com:443?transport=tcp\"],"
                + "\"username\":\"x\",\"credential\":\"y\",\"ttl\":7200}}");
        assertEquals(Integer.valueOf(2), ack.rid);
        assertEquals(1, ack.ice.urls.size());
        assertFalse(ack.ice.toString().contains("y"));
        Message error = VoiceWire.parse("{\"t\":\"error\",\"rid\":3,\"code\":\"rate_limited\",\"retryAfter\":40}");
        assertEquals("rate_limited", error.code);
        assertEquals(Long.valueOf(40), error.retryAfter);
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("{\"t\":\"news\"}").kind);
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("pong").kind);
    }

    @Test
    public void roomIds() {
        assertTrue(VoiceWire.isRoomId("f0123456789abcdef"));
        assertTrue(VoiceWire.isRoomId("g0123456789abcdef"));
        assertTrue(VoiceWire.isRoomId("c0123456789abcdef"));
        assertFalse(VoiceWire.isRoomId("x0123456789abcdef"));
        assertFalse(VoiceWire.isRoomId("f0123456789ABCDEF"));
        assertFalse(VoiceWire.isRoomId("f0123"));
        assertFalse(VoiceWire.isRoomId(null));
        assertEquals("g0123456789abcdef", VoiceWire.generalRoomOf("0123456789abcdef"));
    }

    @Test
    public void refusalsReadAsTheDesktopReadsThem() {
        assertEquals(VoiceAnswer.Refusal.OFF, VoiceAnswer.fromCode("voice_off", null, null).refused);
        assertEquals(VoiceAnswer.Refusal.UNREACHABLE, VoiceAnswer.fromCode("friend_not_found", null, null).refused);
        assertEquals(VoiceAnswer.Refusal.ROOM_GONE, VoiceAnswer.fromCode("invalid_ticket", null, null).refused);
        assertEquals(VoiceAnswer.Refusal.STAFF_MUTED, VoiceAnswer.fromCode("voice_muted", null, 9L).refused);
        assertEquals(VoiceAnswer.Refusal.FAILED, VoiceAnswer.fromCode("whatever", null, null).refused);
    }
}
