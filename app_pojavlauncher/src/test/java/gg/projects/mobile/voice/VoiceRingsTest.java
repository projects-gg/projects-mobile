package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.voice.VoiceRings.Effect;
import gg.projects.mobile.voice.VoiceRings.Ring;

/** Rings, missed calls and the crews' rooms (the desktop's machine_tests.rs ring scenarios). */
public class VoiceRingsTest {
    static final String ROOM = "f0123456789abcdef";
    static final String CREW = "0123456789abcdef";
    static final long UNIX = 1_800_000_000L;

    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static VoiceRings ready() {
        VoiceRings rings = new VoiceRings();
        rings.setFlag(true);
        rings.setMe("me");
        rings.hubHello(json("{\"t\":\"hello\",\"caps\":[\"crews\",\"voice1\"],\"voice\":{\"on\":true,\"friendMax\":10,"
                + "\"crewMax\":15,\"crewRooms\":3,\"ringSeconds\":30,\"idleMinutes\":30,\"kbps\":24},"
                + "\"friends\":[{\"id\":\"111111111111111111\",\"state\":\"launcher\",\"voice\":\"ready\"},"
                + "{\"id\":\"222222222222222222\",\"state\":\"launcher\",\"voice\":\"in\"},"
                + "{\"id\":\"333333333333333333\",\"state\":\"launcher\"}]}"));
        return rings;
    }

    static Ring ring(String from, String room) {
        return new Ring(room, from, UNIX, UNIX + 30, "v1.invite.mac", Collections.singletonList("ece"));
    }

    static boolean has(List<Effect> effects, Effect.Kind kind) {
        for (Effect e : effects) if (e.kind == kind) return true;
        return false;
    }

    /** Anything the player would see or hear of a ring. */
    static boolean told(List<Effect> effects) {
        return has(effects, Effect.Kind.RING) || has(effects, Effect.Kind.MISSED);
    }

    @Test
    public void nothingWithoutTheSwitches() {
        VoiceRings rings = new VoiceRings();
        assertFalse(rings.usable());
        assertTrue(rings.ring(ring("kaan", ROOM), false, 0, UNIX).isEmpty());
        assertTrue(ready().usable());
        // A socket without `voice1` (an older service) is no voice hub.
        VoiceRings old = new VoiceRings();
        old.setFlag(true);
        old.setMe("me");
        old.hubHello(json("{\"t\":\"hello\",\"caps\":[\"crews\"],\"voice\":{\"on\":true}}"));
        assertFalse(old.usable());
    }

    @Test
    public void aRingRingsThenIsMissed() {
        VoiceRings rings = ready();
        List<Effect> effects = rings.ring(ring("kaan", ROOM), false, 0, UNIX);
        assertTrue(has(effects, Effect.Kind.RING));
        assertEquals(1, rings.rings().size());
        assertEquals(Long.valueOf(30_000), rings.nextDeadline());
        effects = rings.tick(30_000);
        assertTrue(has(effects, Effect.Kind.RING_GONE));
        assertTrue(has(effects, Effect.Kind.MISSED));
        assertTrue(rings.rings().isEmpty());
        assertTrue(rings.missed().get(0).joinable(UNIX + 30));
        assertNull(rings.nextDeadline());
        assertEquals("v1.invite.mac", rings.ticketFor(ROOM, UNIX + 31));
        // The room ends: "Geri ara" instead of "Katıl".
        rings.ringEnd(ROOM, "ended");
        assertFalse(rings.missed().get(0).joinable(UNIX + 31));
        assertNull(rings.ticketFor(ROOM, UNIX + 31));
        // The ticket ages out after ten minutes too.
        VoiceRings again = ready();
        again.ring(ring("kaan", ROOM), true, 0, UNIX);
        assertFalse(again.missed().get(0).joinable(UNIX + VoiceRings.INVITE_TICKET_SECONDS));
    }

    @Test
    public void doNotDisturbOnlyLeavesAMissedCall() {
        VoiceRings rings = ready();
        List<Effect> effects = rings.ring(ring("kaan", ROOM), true, 0, UNIX);
        assertFalse(has(effects, Effect.Kind.RING));
        assertTrue(rings.rings().isEmpty());
        assertEquals(1, rings.missed().size());
    }

    @Test
    public void anIgnoredPersonsRingWaitsQuietlyInTheMissedList() {
        VoiceRings rings = ready();
        String tooLong = new String(new char[65]).replace('\0', 'k');
        assertTrue(rings.setIgnored(Arrays.asList("kaan", "kaan", "", tooLong)).isEmpty());
        List<Effect> effects = rings.ring(ring("kaan", ROOM), false, 0, UNIX);
        assertFalse(told(effects));
        assertTrue(rings.rings().isEmpty());
        assertTrue(rings.missed().get(0).joinable(UNIX));
        assertNull(rings.nextDeadline());
        assertTrue(has(rings.ring(ring("ece", "f00000000000000aa"), false, 0, UNIX), Effect.Kind.RING));
    }

    @Test
    public void ignoringSomeoneWhoseRingRingsQuietsItAtOnce() {
        VoiceRings rings = ready();
        rings.ring(ring("kaan", ROOM), false, 0, UNIX);
        List<Effect> effects = rings.setIgnored(Collections.singletonList("kaan"));
        assertTrue(has(effects, Effect.Kind.RING_GONE));
        assertFalse(told(effects));
        assertEquals(1, rings.missed().size());
        assertTrue(rings.setIgnored(Collections.singletonList("kaan")).isEmpty());
        rings.setIgnored(new ArrayList<String>());
        assertTrue(has(rings.ring(ring("kaan", "f00000000000000aa"), false, 0, UNIX), Effect.Kind.RING));
    }

    @Test
    public void decliningTellsTheHubAndStopsTheRing() {
        VoiceRings rings = ready();
        rings.ring(ring("kaan", ROOM), false, 0, UNIX);
        List<Effect> effects = rings.decline(ROOM);
        assertTrue(has(effects, Effect.Kind.HUB_DECLINE));
        assertTrue(has(effects, Effect.Kind.RING_GONE));
        assertTrue(rings.missed().isEmpty());
        // Joined elsewhere: no missed call either.
        rings.ring(ring("kaan", ROOM), false, 0, UNIX);
        effects = rings.ringEnd(ROOM, "joined");
        assertTrue(has(effects, Effect.Kind.RING_GONE));
        assertTrue(rings.rings().isEmpty() && rings.missed().isEmpty());
    }

    @Test
    public void joiningEndsTheRingAndCallingBackEndsTheMissedCalls() {
        VoiceRings rings = ready();
        rings.ring(ring("kaan", ROOM), true, 0, UNIX);
        rings.ring(ring("ece", "f00000000000000aa"), false, 0, UNIX);
        rings.joining("f00000000000000aa", null);
        assertTrue(rings.rings().isEmpty());
        assertEquals(1, rings.missed().size());
        // Calling Kaan back opens a new room: his missed call goes.
        rings.joining("f00000000000000bb", "kaan");
        assertTrue(rings.missed().isEmpty());
        // No ring for the room the player is in.
        assertTrue(rings.ring(ring("ece", "f00000000000000bb"), false, 0, UNIX).isEmpty());
    }

    @Test
    public void switchingVoiceChatOffEndsTheRings() {
        VoiceRings rings = ready();
        rings.ring(ring("kaan", ROOM), false, 0, UNIX);
        List<Effect> effects = rings.setEnabled(false);
        assertTrue(has(effects, Effect.Kind.HUB_SWITCH));
        assertTrue(has(effects, Effect.Kind.RING_GONE));
        assertFalse(rings.usable());
    }

    @Test
    public void presenceSaysWhoMayBeCalled() {
        VoiceRings rings = ready();
        assertEquals("ready", rings.presenceOf("111111111111111111"));
        assertTrue(rings.inVoice("222222222222222222"));
        assertNull(rings.presenceOf("333333333333333333"));
        List<Effect> out = new ArrayList<>();
        assertFalse(rings.onFrame(json("{\"t\":\"presence\",\"presence\":{\"id\":\"333333333333333333\",\"voice\":\"ready\"}}"),
                0, UNIX, out));
        assertEquals("ready", rings.presenceOf("333333333333333333"));
        rings.hubDown();
        assertNull(rings.presenceOf("111111111111111111"));
    }

    @Test
    public void crewRoomsListGenelFirstInTheCrewsOrder() {
        VoiceRings rings = ready();
        List<Effect> out = new ArrayList<>();
        assertTrue(rings.onFrame(json("{\"t\":\"voice_rooms\",\"crew\":\"" + CREW + "\",\"rooms\":["
                + "{\"id\":\"c00000000000000aa\",\"name\":\"Oda 2\",\"ids\":[\"x\"],\"max\":4,\"own\":true},"
                + "{\"id\":\"g" + CREW + "\",\"name\":\"Genel\",\"ids\":[\"y\",\"me\"]}]}"), 0, UNIX, out));
        List<VoiceRings.CrewRoom> list = rings.crewRooms(CREW);
        assertEquals("g" + CREW, list.get(0).id);
        assertEquals("c00000000000000aa", list.get(1).id);
        assertEquals(4, list.get(1).max);
        assertTrue(list.get(1).own);
        assertTrue(rings.canAddRoom(CREW));
        assertTrue(rings.inVoice("y"));
        assertFalse(rings.inVoice("me"));
        // A crew with nothing listed still has its empty "Genel".
        List<VoiceRings.CrewRoom> empty = rings.crewRooms("ffffffffffffffff");
        assertEquals(1, empty.size());
        assertTrue(empty.get(0).general());
        assertTrue(empty.get(0).ids.isEmpty());
        // A ring from the hub, read as it sends it.
        out.clear();
        rings.onFrame(json("{\"t\":\"voice_ring\",\"ring\":{\"room\":\"" + ROOM + "\",\"from\":\"kaan\",\"at\":" + UNIX
                + ",\"until\":" + (UNIX + 30) + ",\"ticket\":\"v1.t.m\",\"with\":[]},\"quiet\":false}"), 0, UNIX, out);
        assertTrue(has(out, Effect.Kind.RING));
    }
}
