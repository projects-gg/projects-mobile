package gg.projects.mobile.voice;

import static gg.projects.mobile.voice.VoiceMachineTest.UNIX;
import static gg.projects.mobile.voice.VoiceMachineTest.hello;
import static gg.projects.mobile.voice.VoiceMachineTest.liveCall;
import static gg.projects.mobile.voice.VoiceMachineTest.msg;
import static gg.projects.mobile.voice.VoiceMachineTest.person;
import static gg.projects.mobile.voice.VoiceMachineTest.ready;
import static gg.projects.mobile.voice.VoiceMachineTest.sent;
import static gg.projects.mobile.voice.VoiceMachineTest.sounds;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.voice.VoiceMachine.Effect;
import gg.projects.mobile.voice.VoiceMachine.Sound;
import gg.projects.mobile.voice.VoiceWire.Kind;
import gg.projects.mobile.voice.VoiceWire.Message;

/**
 * M051 (SHARE-LIVE on the phone): who shares their screen in the room ({@code share1}/{@code live1}), "YAYINDA" outside
 * it (the hub's crew rooms and a friend's presence), the sound when someone goes live, and a watch left from the desktop.
 */
public class VoiceShareLiveTest {
    private static final String SHARE = "\"sh\":[{\"n\":1,\"src\":\"game\",\"audio\":true,\"preset\":\"normal\",\"fps\":30,"
            + "\"w\":2,\"at\":1730800000,\"th\":1730800060}]";

    private static String sharing(String u, long j) {
        String p = person(u, j);
        return p.substring(0, p.length() - 1) + "," + SHARE + "}";
    }

    @Test
    public void shareFramesRead() {
        Message state = VoiceWire.parse("{\"t\":\"state\",\"u\":\"u1\"," + SHARE + "}");
        assertEquals(Kind.STATE, state.kind);
        assertNull(state.mute);
        assertEquals(1, state.sh.size());
        VoiceWire.Share s = state.sh.get(0);
        assertEquals(1, s.n);
        assertEquals("game", s.src);
        assertEquals(2, s.w);
        assertEquals(1730800060L, s.th);
        // An ended share: an empty list; a state without `sh` says nothing of shares.
        assertTrue(VoiceWire.parse("{\"t\":\"state\",\"u\":\"u1\",\"sh\":[]}").sh.isEmpty());
        assertNull(VoiceWire.parse("{\"t\":\"state\",\"u\":\"u1\",\"mute\":true}").sh);
        // A share that does not read is left out; the rest stays.
        Message odd = VoiceWire.parse("{\"t\":\"state\",\"u\":\"u1\",\"sh\":[{\"n\":\"x\"},{\"n\":2}]}");
        assertEquals(1, odd.sh.size());
        assertEquals("screen", odd.sh.get(0).src);
        assertEquals(0L, odd.sh.get(0).th);
        // The desktop's frames for publishers and watchers change nothing here.
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("{\"t\":\"woffer\",\"sdp\":\"v=0\",\"mids\":{}}").kind);
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("{\"t\":\"watchers\",\"n\":1,\"ids\":[]}").kind);
        assertEquals(Kind.UNKNOWN, VoiceWire.parse("{\"t\":\"share_closed\",\"n\":1,\"why\":\"idle\"}").kind);
        JsonObject unwatch = JsonParser.parseString(VoiceWire.unwatch("u1", 2)).getAsJsonObject();
        assertEquals(JsonParser.parseString("{\"t\":\"unwatch\",\"u\":\"u1\",\"n\":2}"), unwatch);
    }

    @Test
    public void helloShowsWhoSharesWithoutASound() {
        VoiceMachine machine = ready();
        long generation = liveCall(machine, person("me", 1), sharing("friend", 5));
        VoiceState view = machine.view(1_400, UNIX);
        VoiceState.Person friend = view.person("friend");
        assertTrue(friend.sharing);
        assertEquals(1, friend.shareN);
        assertEquals("game", friend.shareSrc);
        assertEquals(2, friend.shareWatchers);
        assertEquals(1730800060L, friend.shareTh);
        assertFalse(view.me.sharing);
        // A reconnect's hello with the same share plays nothing either.
        List<Effect> effects = machine.socketMessage(generation, hello(false, person("me", 1), sharing("friend", 5)), 5_000, UNIX);
        assertFalse(sounds(effects).contains(Sound.STREAM_START));
    }

    @Test
    public void someoneGoingLivePlaysOnceASecond() {
        VoiceMachine machine = ready();
        long generation = liveCall(machine, person("me", 1), person("friend", 5), person("third", 6));
        List<Effect> effects = machine.socketMessage(generation, msg("{\"t\":\"state\",\"u\":\"friend\"," + SHARE + "}"), 10_000, UNIX);
        assertEquals(Collections.singletonList(Sound.STREAM_START), sounds(effects));
        assertTrue(machine.view(10_000, UNIX).person("friend").sharing);
        // Within a second, a second sharer is shown but not heard.
        effects = machine.socketMessage(generation, msg("{\"t\":\"state\",\"u\":\"third\"," + SHARE + "}"), 10_500, UNIX);
        assertTrue(sounds(effects).isEmpty());
        assertTrue(machine.view(10_500, UNIX).person("third").sharing);
        // Watchers and a new picture of the same share: no sound.
        effects = machine.socketMessage(generation, msg("{\"t\":\"state\",\"u\":\"friend\",\"sh\":[{\"n\":1,\"src\":\"game\","
                + "\"w\":3,\"th\":1730800120}]}"), 20_000, UNIX);
        assertTrue(sounds(effects).isEmpty());
        assertEquals(3, machine.view(20_000, UNIX).person("friend").shareWatchers);
        // The share ends: the tag goes, no sound (the phone watches nothing).
        effects = machine.socketMessage(generation, msg("{\"t\":\"state\",\"u\":\"friend\",\"sh\":[]}"), 21_000, UNIX);
        assertTrue(sounds(effects).isEmpty());
        assertFalse(machine.view(21_000, UNIX).person("friend").sharing);
        // A second screen of theirs goes live: heard again.
        effects = machine.socketMessage(generation, msg("{\"t\":\"state\",\"u\":\"friend\"," + SHARE + "}"), 22_000, UNIX);
        assertEquals(Collections.singletonList(Sound.STREAM_START), sounds(effects));
    }

    @Test
    public void noShareSoundWithSoundsOff() {
        VoiceMachineTest.FakePrefs prefs = new VoiceMachineTest.FakePrefs();
        prefs.sounds = false;
        VoiceMachine machine = ready(prefs);
        long generation = liveCall(machine, person("me", 1), person("friend", 5));
        List<Effect> effects = machine.socketMessage(generation, msg("{\"t\":\"state\",\"u\":\"friend\"," + SHARE + "}"), 10_000, UNIX);
        assertTrue(sounds(effects).isEmpty());
        // Deafened, the engine keeps it quiet (someone else's stream is not the player's own state).
        assertFalse(VoiceSounds.playsWhileDeaf(Sound.STREAM_START));
        assertTrue(VoiceSounds.render(Sound.STREAM_START, 48_000).length > 0);
    }

    @Test
    public void aWatchLeftFromTheDesktopGoes() {
        VoiceMachine machine = ready();
        List<Effect> effects = machine.join(new VoiceMachine.Start(VoiceMachineTest.ROOM, "t", null, false),
                VoiceMachineTest.SERVICE, 1_000, UNIX);
        long generation = VoiceMachineTest.connectGeneration(effects);
        machine.engineAttached();
        machine.socketOpen(generation, 1_100);
        machine.engineOffer("v=0 offer", "0");
        Message hello = VoiceWire.parse("{\"t\":\"hello\",\"room\":{\"id\":\"" + VoiceMachineTest.ROOM + "\",\"kind\":\"f\","
                + "\"leader\":\"me\",\"mods\":[],\"max\":10,\"shareBy\":\"all\"},\"me\":{\"role\":\"l\",\"watch\":[{\"u\":\"friend\","
                + "\"n\":1,\"layer\":\"f\"},{\"u\":\"friend\",\"n\":2,\"layer\":\"q\"}]},\"people\":[" + person("me", 1) + ","
                + sharing("friend", 5) + "],\"rings\":[],\"sdp\":\"v=0 answer\",\"resume\":\"r\",\"now\":" + UNIX + "}");
        assertEquals(Arrays.asList(1, 2), hello.watch.get("friend"));
        effects = machine.socketMessage(generation, hello, 1_300, UNIX);
        assertEquals(Arrays.asList("set", "unwatch", "unwatch"), sent(effects));
    }

    @Test
    public void theHubSaysWhoIsLiveOutsideTheRoom() {
        VoiceRings rings = VoiceRingsTest.ready();
        assertFalse(rings.sharing("222222222222222222"));
        // A friend's presence: live only with `voice: in`.
        rings.onFrame(VoiceRingsTest.json("{\"t\":\"presence\",\"presence\":{\"id\":\"222222222222222222\",\"state\":\"launcher\","
                + "\"voice\":\"in\",\"live\":true}}"), 0, UNIX, new java.util.ArrayList<>());
        assertTrue(rings.sharing("222222222222222222"));
        rings.onFrame(VoiceRingsTest.json("{\"t\":\"presence\",\"presence\":{\"id\":\"222222222222222222\",\"state\":\"launcher\","
                + "\"voice\":\"in\"}}"), 0, UNIX, new java.util.ArrayList<>());
        assertFalse(rings.sharing("222222222222222222"));
        // A crew room's `live`, only of people its list shows.
        rings.onFrame(VoiceRingsTest.json("{\"t\":\"voice_rooms\",\"crew\":\"" + VoiceRingsTest.CREW + "\",\"rooms\":[{\"id\":\"g"
                + VoiceRingsTest.CREW + "\",\"name\":\"Genel\",\"ids\":[\"x\",\"y\"],\"live\":[\"y\",\"z\"]}]}"), 0, UNIX,
                new java.util.ArrayList<>());
        VoiceRings.CrewRoom general = rings.crewRooms(VoiceRingsTest.CREW).get(0);
        assertEquals(Collections.singletonList("y"), general.live);
        assertTrue(rings.sharing("y"));
        assertFalse(rings.sharing("x"));
        assertFalse(rings.sharing("z"));
        // An older hub (no `live`): nobody.
        rings.onFrame(VoiceRingsTest.json("{\"t\":\"voice_rooms\",\"crew\":\"" + VoiceRingsTest.CREW + "\",\"rooms\":[{\"id\":\"g"
                + VoiceRingsTest.CREW + "\",\"name\":\"Genel\",\"ids\":[\"x\",\"y\"]}]}"), 0, UNIX, new java.util.ArrayList<>());
        assertFalse(rings.sharing("y"));
        // The hub gone: nothing is known.
        rings.hubDown();
        assertFalse(rings.sharing("222222222222222222"));
    }
}
