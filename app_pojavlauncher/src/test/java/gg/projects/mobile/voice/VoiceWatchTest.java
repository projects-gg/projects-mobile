package gg.projects.mobile.voice;

import static gg.projects.mobile.voice.VoiceMachineTest.ROOM;
import static gg.projects.mobile.voice.VoiceMachineTest.UNIX;
import static gg.projects.mobile.voice.VoiceMachineTest.hello;
import static gg.projects.mobile.voice.VoiceMachineTest.msg;
import static gg.projects.mobile.voice.VoiceMachineTest.person;
import static gg.projects.mobile.voice.VoiceMachineTest.sent;
import static gg.projects.mobile.voice.VoiceMachineTest.sounds;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import gg.projects.mobile.voice.VoiceMachine.Effect;
import gg.projects.mobile.voice.VoiceMachine.Sound;
import gg.projects.mobile.voice.VoiceWire.Kind;
import gg.projects.mobile.voice.VoiceWire.Message;

/**
 * MOB-WATCH: watching a screen share on the phone, the desktop's {@code share_tests.rs} watching scenarios: the room's
 * frames, one stream at a time, out of sight, "Hâlâ izliyor musun?", lost connections and TURN, a reconnect, the end.
 */
public class VoiceWatchTest {
    /** Each person's choices and each streamer's sound, as the voice process keeps them. */
    static final class Prefs implements VoiceMachine.Prefs {
        final Map<String, Integer> streamVolumes = new HashMap<>();
        final Map<String, Boolean> streamMuted = new HashMap<>();
        final Map<String, Boolean> mutedForMe = new HashMap<>();

        @Override
        public int volume(String person) {
            return 100;
        }

        @Override
        public boolean mutedForMe(String person) {
            return Boolean.TRUE.equals(mutedForMe.get(person));
        }

        @Override
        public void setVolume(String person, int percent) {}

        @Override
        public void setMutedForMe(String person, boolean on) {
            mutedForMe.put(person, on);
        }

        @Override
        public boolean sounds() {
            return true;
        }

        @Override
        public int streamVolume(String person) {
            Integer v = streamVolumes.get(person);
            return v == null ? 100 : v;
        }

        @Override
        public boolean streamMuted(String person) {
            return Boolean.TRUE.equals(streamMuted.get(person));
        }

        @Override
        public void setStream(String person, int volume, boolean muted) {
            streamVolumes.put(person, volume);
            streamMuted.put(person, muted);
        }
    }

    private static String sharing(String u, long j, int... ns) {
        StringBuilder sh = new StringBuilder();
        for (int n : ns) {
            if (sh.length() > 0) sh.append(',');
            sh.append("{\"n\":").append(n).append(",\"src\":\"game\",\"audio\":true,\"preset\":\"normal\",\"fps\":30,\"w\":1,"
                    + "\"at\":1730800000,\"th\":1730800060}");
        }
        String p = person(u, j);
        return p.substring(0, p.length() - 1) + ",\"sh\":[" + sh + "]}";
    }

    private Prefs prefs;

    private VoiceMachine machine() {
        prefs = new Prefs();
        VoiceMachine machine = new VoiceMachine(prefs, new Random(1));
        machine.setBackoff(new VoiceMachine.Backoff(1_000, 1_000, 0));
        machine.setMe("me");
        return machine;
    }

    private static List<VoiceWatch.Cmd> watchCmds(List<Effect> effects) {
        List<VoiceWatch.Cmd> out = new ArrayList<>();
        for (Effect e : effects) if (e.kind == Effect.Kind.WATCH) out.add(e.watch);
        return out;
    }

    private static JsonObject frame(List<Effect> effects, String type) {
        for (Effect e : effects) {
            if (e.kind == Effect.Kind.SEND && type.equals(VoiceWireTestSupport.type(e.frame))) return VoiceWireTestSupport.frame(e.frame);
        }
        throw new AssertionError("no " + type + " in " + effects);
    }

    private static Message woffer(boolean fresh, String u, int n) {
        return msg("{\"t\":\"woffer\",\"sdp\":\"v=0\\r\\na=ice-ufrag:abcd\\r\\n\",\"mids\":{\"0\":{\"u\":\"" + u + "\",\"n\":" + n
                + ",\"k\":\"v\"},\"1\":{\"u\":\"" + u + "\",\"n\":" + n + ",\"k\":\"a\"}}" + (fresh ? ",\"fresh\":true" : "") + "}");
    }

    /** A live call where "friend" shares 1 (and "third" shares 2), the player asked to watch friend's share. */
    private long watching(VoiceMachine machine) {
        long generation = VoiceMachineTest.liveCall(machine, person("me", 1), sharing("friend", 5, 1), sharing("third", 6, 2));
        List<Effect> effects = machine.watchStart("friend", 1, "f", 2_000);
        JsonObject watch = frame(effects, "watch");
        assertEquals("friend", watch.get("u").getAsString());
        assertEquals(1, watch.get("n").getAsInt());
        assertEquals("f", watch.get("layer").getAsString());
        int rid = watch.get("rid").getAsInt();
        // The room's ack, then its offer for a new watch session.
        assertTrue(machine.socketMessage(generation, msg("{\"t\":\"ack\",\"rid\":" + rid + "}"), 2_100, UNIX).isEmpty());
        effects = machine.socketMessage(generation, woffer(true, "friend", 1), 2_200, UNIX);
        List<VoiceWatch.Cmd> cmds = watchCmds(effects);
        assertEquals(1, cmds.size());
        assertEquals(VoiceWatch.Cmd.Kind.OFFER, cmds.get(0).kind);
        assertTrue(cmds.get(0).fresh);
        assertEquals(new VoiceWire.WatchMid("friend", 1, true), cmds.get(0).mids.get("0"));
        assertEquals(new VoiceWire.WatchMid("friend", 1, false), cmds.get(0).mids.get("1"));
        return generation;
    }

    @Test
    public void watchFramesReadAndWrite() {
        assertEquals(VoiceWireTestSupport.frame("{\"t\":\"watch\",\"u\":\"u1\",\"n\":2,\"layer\":\"q\",\"rid\":7}"),
                VoiceWireTestSupport.frame(VoiceWire.watch("u1", 2, "q", 7)));
        assertEquals(VoiceWireTestSupport.frame("{\"t\":\"layer\",\"u\":\"u1\",\"n\":2,\"layer\":\"f\"}"),
                VoiceWireTestSupport.frame(VoiceWire.layer("u1", 2, "anything")));
        assertEquals(VoiceWireTestSupport.frame("{\"t\":\"wanswer\",\"sdp\":\"v=0\"}"),
                VoiceWireTestSupport.frame(VoiceWire.wanswer("v=0")));
        Message offer = woffer(false, "u1", 2);
        assertEquals(Kind.WOFFER, offer.kind);
        assertFalse(offer.fresh);
        assertEquals(2, offer.watchMids.size());
        // An m-line that does not read is left out; an offer without SDP is no offer.
        Message odd = msg("{\"t\":\"woffer\",\"sdp\":\"v=0\",\"fresh\":true,\"mids\":{\"0\":{\"u\":\"u1\"},\"1\":5,\"2\":{\"u\":\"u1\",\"n\":1}}}");
        assertTrue(odd.fresh);
        assertEquals(Collections.singletonMap("2", new VoiceWire.WatchMid("u1", 1, true)), odd.watchMids);
        assertEquals(Kind.UNKNOWN, msg("{\"t\":\"woffer\",\"mids\":{}}").kind);
        assertEquals("abcd", VoiceWatch.ufragOf("v=0\r\na=ice-ufrag:abcd\r\na=x"));
        assertNull(VoiceWatch.ufragOf("v=0"));
    }

    @Test
    public void watchingAsksTheRoomAndAnswersItsOffer() {
        VoiceMachine machine = machine();
        long generation = watching(machine);
        VoiceState view = machine.view(2_300, UNIX);
        assertNotNull(view.watch);
        assertEquals("friend", view.watch.u);
        assertEquals(VoiceState.Watch.CONNECTING, view.watch.phase);
        assertEquals("game", view.watch.src);
        // The engine's answer goes to the room; connected, the watch is live.
        List<Effect> effects = machine.watchReport(VoiceWatch.Report.ANSWER, "v=0 answer", 2_400);
        assertEquals("v=0 answer", frame(effects, "wanswer").get("sdp").getAsString());
        assertFalse(machine.watchReport(VoiceWatch.Report.CONNECTED, null, 2_500).isEmpty());
        assertEquals(VoiceState.Watch.LIVE, machine.view(2_500, UNIX).watch.phase);
        // What the engine sees reaches the screen once per change.
        assertFalse(machine.watchFacts(false, 1280, 720, true).isEmpty());
        assertTrue(machine.watchFacts(false, 1280, 720, true).isEmpty());
        VoiceState.Watch w = machine.view(2_600, UNIX).watch;
        assertTrue(w.playing);
        assertEquals(1280, w.width);
        // A second offer of the same session: the same connection.
        effects = machine.socketMessage(generation, woffer(false, "friend", 1), 3_000, UNIX);
        assertFalse(watchCmds(effects).get(0).fresh);
        assertTrue(machine.watchLive());
        assertEquals(1.0, machine.streamGain(), 1e-9);
    }

    @Test
    public void anotherLayerIsAskedOnlyWhenItChanges() {
        VoiceMachine machine = machine();
        watching(machine);
        List<Effect> effects = machine.watchLayer("friend", 1, "q");
        assertEquals("q", frame(effects, "layer").get("layer").getAsString());
        assertTrue(machine.watchLayer("friend", 1, "q").isEmpty());
        assertEquals("q", machine.view(3_000, UNIX).watch.layer);
        // Another share's layer changes nothing.
        assertTrue(machine.watchLayer("third", 2, "f").isEmpty());
    }

    @Test
    public void thePhoneWatchesOneStreamAtATime() {
        VoiceMachine machine = machine();
        watching(machine);
        List<Effect> effects = machine.watchStart("third", 2, "f", 4_000);
        assertEquals(Arrays.asList("unwatch", "watch"), sent(effects));
        assertEquals("friend", frame(effects, "unwatch").get("u").getAsString());
        assertEquals(VoiceWatch.Cmd.Kind.RESET, watchCmds(effects).get(0).kind);
        assertEquals("third", machine.view(4_000, UNIX).watch.u);
        // The same share again changes nothing but the layer.
        assertTrue(sent(machine.watchStart("third", 2, "f", 4_100)).isEmpty());
    }

    @Test
    public void aShareTheRoomDoesNotListSaysItIsGone() {
        VoiceMachine machine = machine();
        VoiceMachineTest.liveCall(machine, person("me", 1), sharing("friend", 5, 1));
        int before = machine.view(1_500, UNIX).watchNoticeSeq;
        List<Effect> effects = machine.watchStart("friend", 3, "f", 2_000);
        assertTrue(sent(effects).isEmpty());
        VoiceState view = machine.view(2_000, UNIX);
        assertNull(view.watch);
        assertEquals("gone", view.watchNotice);
        assertEquals(before + 1, view.watchNoticeSeq);
        // The player's own share is never watched here.
        assertTrue(sent(machine.watchStart("me", 1, "f", 2_100)).isEmpty());
    }

    @Test
    public void theRoomsRefusalEndsTheWatchWithItsWord() {
        VoiceMachine machine = machine();
        long generation = VoiceMachineTest.liveCall(machine, person("me", 1), sharing("friend", 5, 1));
        int rid = frame(machine.watchStart("friend", 1, "f", 2_000), "watch").get("rid").getAsInt();
        List<Effect> effects = machine.socketMessage(generation, msg("{\"t\":\"error\",\"rid\":" + rid + ",\"code\":\"watch_full\"}"),
                2_100, UNIX);
        assertTrue(sent(effects).isEmpty());
        VoiceState view = machine.view(2_100, UNIX);
        assertNull(view.watch);
        assertEquals("watch_full", view.watchNotice);
    }

    @Test
    public void aStreamThatEndsSoundsAndSaysSo() {
        VoiceMachine machine = machine();
        long generation = watching(machine);
        List<Effect> effects = machine.socketMessage(generation, msg("{\"t\":\"state\",\"u\":\"friend\",\"sh\":[]}"), 5_000, UNIX);
        assertEquals(Collections.singletonList(Sound.STREAM_END), sounds(effects));
        assertEquals(VoiceWatch.Cmd.Kind.RESET, watchCmds(effects).get(0).kind);
        // The room closed it already: no unwatch.
        assertFalse(sent(effects).contains("unwatch"));
        VoiceState view = machine.view(5_000, UNIX);
        assertNull(view.watch);
        assertEquals("ended", view.watchNotice);
        assertFalse(machine.watchLive());
        assertEquals(0.0, machine.streamGain(), 1e-9);
        // The streamer leaving ends it the same way.
        machine.watchStart("third", 2, "f", 6_000);
        effects = machine.socketMessage(generation, msg("{\"t\":\"left\",\"u\":\"third\",\"why\":\"left\"}"), 6_500, UNIX);
        assertTrue(sounds(effects).contains(Sound.STREAM_END));
        assertNull(machine.view(6_500, UNIX).watch);
    }

    @Test
    public void outOfSightLetsGoAndComesBack() {
        VoiceMachine machine = machine();
        watching(machine);
        machine.watchReport(VoiceWatch.Report.CONNECTED, null, 2_500);
        assertTrue(machine.watchVisible(false, 10_000).isEmpty());
        assertEquals(Long.valueOf(10_000 + VoiceWatch.HIDDEN_GRACE_MS), machine.nextDeadline(10_000));
        // Back within 3 s: nothing happened.
        machine.watchVisible(true, 11_000);
        machine.watchVisible(false, 12_000);
        List<Effect> effects = machine.tick(12_000 + VoiceWatch.HIDDEN_GRACE_MS, UNIX, 12_000);
        assertEquals(Collections.singletonList("unwatch"), sent(effects));
        assertEquals(VoiceWatch.Cmd.Kind.RESET, watchCmds(effects).get(0).kind);
        VoiceState.Watch held = machine.view(15_000, UNIX).watch;
        assertEquals(VoiceState.Watch.HELD, held.phase);
        assertFalse(machine.watchLive());
        // No deadline of watching's own while held (the still question waits too).
        effects = machine.watchVisible(true, 60_000);
        assertEquals(Collections.singletonList("watch"), sent(effects));
        assertEquals(VoiceState.Watch.CONNECTING, machine.view(60_000, UNIX).watch.phase);
    }

    @Test
    public void halfAnHourWithoutATouchAsksAndAMinuteMoreStops() {
        VoiceMachine machine = machine();
        watching(machine);
        long asked = 2_000 + VoiceWatch.STILL_ASK_MS;
        // The answer to its offer settles the offer's own wait.
        List<Effect> effects = machine.tick(asked, UNIX, asked);
        assertTrue(machine.view(asked, UNIX).watch.asking);
        // A touch answers it.
        machine.watchStill(asked + 10_000);
        assertFalse(machine.view(asked + 10_000, UNIX).watch.asking);
        long again = asked + 10_000 + VoiceWatch.STILL_ASK_MS;
        machine.tick(again, UNIX, again);
        assertTrue(machine.view(again, UNIX).watch.asking);
        effects = machine.tick(again + VoiceWatch.STILL_ANSWER_MS, UNIX, again + VoiceWatch.STILL_ANSWER_MS);
        assertEquals(Collections.singletonList("unwatch"), sent(effects));
        VoiceState view = machine.view(again + VoiceWatch.STILL_ANSWER_MS, UNIX);
        assertNull(view.watch);
        assertEquals("stopped", view.watchNotice);
    }

    @Test
    public void anOfferThatNeverComesIsLostAndThreeLossesGiveUp() {
        VoiceMachine machine = machine();
        VoiceMachineTest.liveCall(machine, person("me", 1), sharing("friend", 5, 1));
        machine.watchStart("friend", 1, "f", 2_000);
        long t = 2_000 + VoiceWatch.OFFER_WAIT_MS;
        List<Effect> effects = machine.tick(t, UNIX, t);
        // Asked anew: the room lets the old go, the engine closes its connection.
        assertEquals(Arrays.asList("unwatch", "watch"), sent(effects));
        assertEquals(VoiceWatch.Cmd.Kind.RESET, watchCmds(effects).get(0).kind);
        effects = machine.watchReport(VoiceWatch.Report.LOST, null, t + 1_000);
        assertEquals(Arrays.asList("unwatch", "watch"), sent(effects));
        effects = machine.watchReport(VoiceWatch.Report.LOST, null, t + 2_000);
        assertEquals(Collections.singletonList("unwatch"), sent(effects));
        VoiceState view = machine.view(t + 2_000, UNIX);
        assertNull(view.watch);
        assertEquals("failed", view.watchNotice);
    }

    @Test
    public void turnIsAskedOnceAndItsCredentialsGoToTheEngine() {
        VoiceMachine machine = machine();
        long generation = watching(machine);
        int rid = frame(machine.watchReport(VoiceWatch.Report.NEED_TURN, null, 7_000), "turn").get("rid").getAsInt();
        List<Effect> effects = machine.socketMessage(generation, msg("{\"t\":\"ack\",\"rid\":" + rid + ",\"ice\":{\"urls\":"
                + "[\"turn:turn.example:3478\"],\"username\":\"u\",\"credential\":\"c\"}}"), 7_100, UNIX);
        List<VoiceWatch.Cmd> cmds = watchCmds(effects);
        assertEquals(VoiceWatch.Cmd.Kind.ICE, cmds.get(0).kind);
        assertEquals(Collections.singletonList("turn:turn.example:3478"), cmds.get(0).ice.urls);
        // The engine ends the connection that failed without TURN: the session is asked anew and TURN stays.
        effects = machine.watchReport(VoiceWatch.Report.LOST, null, 7_200);
        assertEquals(Arrays.asList("unwatch", "watch"), sent(effects));
        assertEquals(VoiceWatch.Cmd.Kind.RESET, watchCmds(effects).get(0).kind);
        assertTrue(watchCmds(effects).get(0).keepTurn);
        // No credentials: as a lost connection (asked anew).
        rid = frame(machine.watchReport(VoiceWatch.Report.NEED_TURN, null, 8_000), "turn").get("rid").getAsInt();
        effects = machine.socketMessage(generation, msg("{\"t\":\"error\",\"rid\":" + rid + ",\"code\":\"turn_unavailable\"}"), 8_100, UNIX);
        assertEquals(Arrays.asList("unwatch", "watch"), sent(effects));
    }

    @Test
    public void aReconnectKeepsWhatTheRoomStillCarries() {
        VoiceMachine machine = machine();
        long generation = watching(machine);
        // The socket drops and comes back; the room's hello says its watch session carries friend's share.
        machine.socketClosed(generation, 1006, 9_000);
        List<Effect> effects = machine.tick(10_000, UNIX, 10_000);
        long next = VoiceMachineTest.connectGeneration(effects);
        machine.socketOpen(next, 10_100);
        Message carried = VoiceWire.parse("{\"t\":\"hello\",\"room\":{\"id\":\"" + ROOM + "\",\"kind\":\"f\",\"leader\":\"me\",\"mods\":[],"
                + "\"max\":10},\"me\":{\"role\":\"l\",\"watch\":[{\"u\":\"friend\",\"n\":1}]},\"people\":[" + person("me", 1) + ","
                + sharing("friend", 5, 1) + "," + sharing("third", 6, 2) + "],\"rings\":[],\"resume\":\"r\",\"now\":" + UNIX + "}");
        effects = machine.socketMessage(next, carried, 10_200, UNIX);
        assertFalse(sent(effects).contains("watch"));
        assertFalse(sent(effects).contains("unwatch"));
        assertTrue(watchCmds(effects).isEmpty());
        // Its layer again (one chosen while the socket was away would be lost otherwise).
        assertEquals("f", frame(effects, "layer").get("layer").getAsString());
        // A hello whose session is new (nothing carried): the watch starts over there.
        effects = machine.socketMessage(next, hello(false, person("me", 1), sharing("friend", 5, 1)), 11_000, UNIX);
        assertEquals(Arrays.asList("set", "unwatch", "watch"), sent(effects));
        assertEquals(VoiceWatch.Cmd.Kind.RESET, watchCmds(effects).get(0).kind);
        // Stopping clears TURN with the connection.
        effects = machine.watchStop("friend", 1, 12_000);
        assertFalse(watchCmds(effects).get(0).keepTurn);
    }

    @Test
    public void aLayerChosenWhileTheSocketWasAwayReachesTheRoomAfterTheReconnect() {
        VoiceMachine machine = machine();
        long generation = watching(machine);
        machine.socketClosed(generation, 1006, 9_000);
        // The small picture asked for while there is no socket: nothing can go yet.
        assertTrue(sent(machine.watchLayer("friend", 1, "q")).isEmpty());
        long next = VoiceMachineTest.connectGeneration(machine.tick(10_000, UNIX, 10_000));
        machine.socketOpen(next, 10_100);
        Message kept = VoiceWire.parse("{\"t\":\"hello\",\"room\":{\"id\":\"" + ROOM + "\",\"kind\":\"f\",\"leader\":\"me\",\"mods\":[],"
                + "\"max\":10},\"me\":{\"role\":\"l\",\"watch\":[{\"u\":\"friend\",\"n\":1}]},\"people\":[" + person("me", 1) + ","
                + sharing("friend", 5, 1) + "],\"rings\":[],\"resume\":\"r\",\"now\":" + UNIX + "}");
        List<Effect> effects = machine.socketMessage(next, kept, 10_200, UNIX);
        JsonObject layer = frame(effects, "layer");
        assertEquals("friend", layer.get("u").getAsString());
        assertEquals("q", layer.get("layer").getAsString());
    }

    @Test
    public void joiningAnotherRoomWhileWatchingEndsTheWatchWithItsWord() {
        VoiceMachine machine = machine();
        watching(machine);
        int seq = machine.view(3_000, UNIX).watchNoticeSeq;
        // A move or a join elsewhere ends this stay and starts the next in one go: the stream screen gets its word.
        machine.join(new VoiceMachine.Start("g0123456789abcdef", "t", null, false), VoiceMachineTest.SERVICE, 4_000, UNIX);
        VoiceState view = machine.view(4_000, UNIX);
        assertNotNull(view);
        assertNull(view.watch);
        assertEquals(seq + 1, view.watchNoticeSeq);
        assertEquals(VoiceWatch.NOTICE_ENDED, view.watchNotice);
    }

    @Test
    public void aReconnectAsksAnewForAWatchWhoseOfferNeverCame() {
        VoiceMachine machine = machine();
        long generation = VoiceMachineTest.liveCall(machine, person("me", 1), sharing("friend", 5, 1));
        assertNotNull(frame(machine.watchStart("friend", 1, "f", 2_000), "watch"));
        // The socket drops before the room's offer arrives; the room's hello still lists the share in its session.
        machine.socketClosed(generation, 1006, 3_000);
        long next = VoiceMachineTest.connectGeneration(machine.tick(4_000, UNIX, 4_000));
        machine.socketOpen(next, 4_100);
        Message kept = VoiceWire.parse("{\"t\":\"hello\",\"room\":{\"id\":\"" + ROOM + "\",\"kind\":\"f\",\"leader\":\"me\",\"mods\":[],"
                + "\"max\":10},\"me\":{\"role\":\"l\",\"watch\":[{\"u\":\"friend\",\"n\":1}]},\"people\":[" + person("me", 1) + ","
                + sharing("friend", 5, 1) + "],\"rings\":[],\"resume\":\"r\",\"now\":" + UNIX + "}");
        List<Effect> effects = machine.socketMessage(next, kept, 4_200, UNIX);
        // The room lets the stale one go and offers it anew (never two unwatches, never waiting forever).
        List<String> frames = new ArrayList<>(sent(effects));
        frames.retainAll(Arrays.asList("watch", "unwatch"));
        assertEquals(Arrays.asList("unwatch", "watch"), frames);
        assertEquals(VoiceState.Watch.CONNECTING, machine.view(4_200, UNIX).watch.phase);
    }

    @Test
    public void theStreamsSoundFollowsItsVolumeMuteAndDeafen() {
        VoiceMachine machine = machine();
        watching(machine);
        assertFalse(machine.setStreamSound("friend", 150, false).isEmpty());
        assertEquals(1.5, machine.streamGain(), 1e-9);
        assertEquals(150, machine.view(3_000, UNIX).watch.volume);
        assertTrue(machine.setStreamSound("friend", 150, false).isEmpty());
        machine.setStreamSound("friend", 150, true);
        assertEquals(0.0, machine.streamGain(), 1e-9);
        assertTrue(machine.view(3_000, UNIX).watch.muted);
        machine.setStreamSound("friend", 400, false);
        assertEquals(2.0, machine.streamGain(), 1e-9);
        machine.setDeaf(true);
        assertEquals(0.0, machine.streamGain(), 1e-9);
        // Whom the player silenced never ducks the stream.
        prefs.mutedForMe.put("third", true);
        assertEquals(Collections.singletonList("third"), machine.silent());
    }

    @Test
    public void leavingOrTheScreensProcessEndingStopsWatching() {
        VoiceMachine machine = machine();
        watching(machine);
        // The stream screen's process went (the game took its place): the room lets go, nothing is said.
        int seq = machine.view(3_000, UNIX).watchNoticeSeq;
        List<Effect> effects = machine.watchGone(3_000);
        assertEquals(Collections.singletonList("unwatch"), sent(effects));
        assertNull(machine.view(3_000, UNIX).watch);
        assertEquals(seq, machine.view(3_000, UNIX).watchNoticeSeq);
        // An offer that crossed the unwatch changes nothing.
        machine.watchStart("friend", 1, "f", 4_000);
        effects = machine.leave(5_000);
        assertEquals(VoiceWatch.Cmd.Kind.RESET, watchCmds(effects).get(0).kind);
        assertFalse(machine.inRoom());
    }

    @Test
    public void anOfferAfterUnwatchIsIgnored() {
        VoiceMachine machine = machine();
        long generation = watching(machine);
        assertEquals(Collections.singletonList("unwatch"), sent(machine.watchStop("friend", 1, 3_000)));
        List<Effect> effects = machine.socketMessage(generation, woffer(false, "friend", 1), 3_100, UNIX);
        assertTrue(watchCmds(effects).isEmpty());
        assertNull(machine.view(3_100, UNIX).watch);
    }

    @Test
    public void theAnswerReceivesTheStreamInStereo() {
        String answer = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 H264/90000\r\n"
                + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\n";
        String stereo = VoiceSdp.stereoOpus(answer);
        assertTrue(stereo.contains("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1\r\n"));
        assertFalse(stereo.contains("a=fmtp:96"));
        assertTrue(stereo.endsWith("\r\n"));
    }

    @Test
    public void theStreamEndSoundIsSoftAndQuietWhileDeafened() {
        assertFalse(VoiceSounds.playsWhileDeaf(Sound.STREAM_END));
        assertEquals((int) Math.round(0.36 * 48_000), VoiceSounds.render(Sound.STREAM_END, 48_000).length);
    }
}
