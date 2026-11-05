package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import gg.projects.mobile.voice.VoiceMachine.Command;
import gg.projects.mobile.voice.VoiceMachine.Effect;
import gg.projects.mobile.voice.VoiceMachine.LeftReason;
import gg.projects.mobile.voice.VoiceMachine.Notice;
import gg.projects.mobile.voice.VoiceMachine.Sound;
import gg.projects.mobile.voice.VoiceMachine.Start;
import gg.projects.mobile.voice.VoiceWire.Message;
import gg.projects.mobile.voice.VoiceWire.ModAct;

/** The stay's scenarios, ported from the desktop's {@code machine_tests.rs} (design §3, §5, §6). */
public class VoiceMachineTest {
    static final String ROOM = "f0123456789abcdef";
    static final long UNIX = 1_800_000_000L;
    static final VoiceMachine.Service SERVICE = VoiceMachine.Service.DEFAULT;

    static final class FakePrefs implements VoiceMachine.Prefs {
        final Map<String, Integer> volumes = new HashMap<>();
        final Map<String, Boolean> muted = new HashMap<>();
        boolean sounds = true;

        @Override
        public int volume(String person) {
            Integer v = volumes.get(person);
            return v == null ? 100 : v;
        }

        @Override
        public boolean mutedForMe(String person) {
            return Boolean.TRUE.equals(muted.get(person));
        }

        @Override
        public void setVolume(String person, int percent) {
            volumes.put(person, percent);
        }

        @Override
        public void setMutedForMe(String person, boolean on) {
            muted.put(person, on);
        }

        @Override
        public boolean sounds() {
            return sounds;
        }
    }

    static VoiceMachine ready() {
        return ready(new FakePrefs());
    }

    static VoiceMachine ready(FakePrefs prefs) {
        VoiceMachine machine = new VoiceMachine(prefs, new Random(1));
        machine.setBackoff(new VoiceMachine.Backoff(1_000, 1_000, 0));
        machine.setMe("me");
        return machine;
    }

    static String person(String u, long j) {
        return "{\"u\":\"" + u + "\",\"j\":" + j + ",\"mute\":false,\"deaf\":false,\"sm\":false,\"q\":0}";
    }

    static Message hello(boolean sdp, String... people) {
        return VoiceWire.parse("{\"t\":\"hello\",\"room\":{\"id\":\"" + ROOM + "\",\"kind\":\"f\",\"leader\":\"me\",\"mods\":[],"
                + "\"max\":10},\"me\":{\"role\":\"l\"},\"people\":[" + String.join(",", people) + "],\"rings\":[]"
                + (sdp ? ",\"sdp\":\"v=0 answer\"" : "") + ",\"resume\":\"v1.resume.mac\",\"now\":" + UNIX + "}");
    }

    static Message msg(String text) {
        return VoiceWire.parse(text);
    }

    static long connectGeneration(List<Effect> effects) {
        for (Effect e : effects) if (e.kind == Effect.Kind.CONNECT) return e.generation;
        fail("no connect in " + effects);
        return 0;
    }

    static List<String> sent(List<Effect> effects) {
        List<String> out = new ArrayList<>();
        for (Effect e : effects) {
            if (e.kind != Effect.Kind.SEND) continue;
            out.add(VoiceWireTestSupport.type(e.frame));
        }
        return out;
    }

    static List<Command> engine(List<Effect> effects) {
        List<Command> out = new ArrayList<>();
        for (Effect e : effects) if (e.kind == Effect.Kind.ENGINE) out.add(e.command);
        return out;
    }

    static boolean has(List<Effect> effects, Command.Kind kind) {
        for (Command c : engine(effects)) if (c.kind == kind) return true;
        return false;
    }

    static boolean talk(List<Effect> effects, boolean open) {
        for (Command c : engine(effects)) if (c.kind == Command.Kind.TALK && c.on == open) return true;
        return false;
    }

    static List<Sound> sounds(List<Effect> effects) {
        List<Sound> out = new ArrayList<>();
        for (Command c : engine(effects)) if (c.kind == Command.Kind.SOUND) out.add(c.sound);
        return out;
    }

    static List<Notice> notices(List<Effect> effects) {
        List<Notice> out = new ArrayList<>();
        for (Effect e : effects) if (e.kind == Effect.Kind.NOTICE) out.add(e.notice);
        return out;
    }

    @Nullable
    static VoiceAnswer answer(List<Effect> effects, int rid) {
        for (Effect e : effects) if (e.kind == Effect.Kind.ANSWER && e.rid == rid) return e.answer;
        return null;
    }

    static boolean kind(List<Effect> effects, Effect.Kind kind) {
        for (Effect e : effects) if (e.kind == kind) return true;
        return false;
    }

    /** A friend call that is live: joined, the engine attached, its offer sent, hello in. */
    static long liveCall(VoiceMachine machine, String... people) {
        List<Effect> effects = machine.join(new Start(ROOM, "v1.join.mac", null, false), SERVICE, 1_000, UNIX);
        long generation = connectGeneration(effects);
        machine.engineAttached();
        machine.socketOpen(generation, 1_100);
        machine.engineOffer("v=0 offer", "0");
        machine.socketMessage(generation, hello(true, people), 1_300, UNIX);
        machine.engineConnected();
        return generation;
    }

    @Test
    public void nothingWithoutASignedInPlayer() {
        VoiceMachine machine = new VoiceMachine(new FakePrefs(), new Random(1));
        List<Effect> effects = machine.join(new Start(ROOM, "t", null, false), SERVICE, 0, UNIX);
        assertEquals(VoiceAnswer.refused(VoiceAnswer.Refusal.OFF), answer(effects, VoiceMachine.JOIN_RID));
        assertFalse(machine.inRoom());
        assertNull(machine.nextDeadline(0));
        assertNull(ready().nextDeadline(0));
    }

    @Test
    public void aCallJoinsAndGoesLive() {
        VoiceMachine machine = ready();
        List<Effect> effects = machine.join(new Start(ROOM, "v1.join.mac", "friend", false), SERVICE, 1_000, UNIX);
        long generation = connectGeneration(effects);
        assertTrue(kind(effects, Effect.Kind.ENGINE_OPEN));
        VoiceState view = machine.view(1_000, UNIX);
        assertEquals(VoiceState.JOINING, view.phase);
        assertEquals("friend", view.calling);
        // The engine attaches: configured and asked for an offer.
        effects = machine.engineAttached();
        assertEquals(Command.Kind.CONFIGURE, engine(effects).get(0).kind);
        assertEquals(24, engine(effects).get(0).kbps);
        assertTrue(has(effects, Command.Kind.JOIN));
        // The socket opens before the offer: the join's caller hears it worked.
        effects = machine.socketOpen(generation, 1_100);
        assertEquals(VoiceAnswer.OK, answer(effects, VoiceMachine.JOIN_RID));
        assertTrue(sent(effects).isEmpty());
        // The offer goes out as `join`.
        effects = machine.engineOffer("v=0 offer", "0");
        assertEquals(Collections.singletonList("join"), sent(effects));
        // hello brings the SFU's answer; the state goes out; the caller waits alone.
        effects = machine.socketMessage(generation, hello(true, person("me", 1)), 1_300, UNIX);
        assertTrue(has(effects, Command.Kind.ANSWER));
        assertTrue(sounds(effects).contains(Sound.WAITING));
        assertEquals(Collections.singletonList("set"), sent(effects));
        // Alone, nothing goes out.
        assertTrue(talk(effects, false));
        view = machine.view(1_300, UNIX);
        assertEquals(VoiceState.LIVE, view.phase);
        assertEquals(VoiceState.LEADER, view.me.role);
        // The friend comes: the call is answered, the gate opens.
        effects = machine.socketMessage(generation, msg("{\"t\":\"joined\",\"person\":" + person("friend", 5) + "}"), 2_000, UNIX);
        assertTrue(sounds(effects).contains(Sound.STOP));
        assertTrue(sounds(effects).contains(Sound.CONNECTED));
        assertTrue(talk(effects, true));
        boolean people = false;
        for (Command c : engine(effects)) if (c.kind == Command.Kind.PEOPLE && c.people.size() == 1) people = true;
        assertTrue(people);
        view = machine.view(2_000, UNIX);
        assertNull(view.calling);
        assertEquals("friend", view.people.get(0).id);
        assertEquals(Long.valueOf(UNIX), view.togetherSince);
    }

    @Test
    public void aDroppedSocketComesBackWithSync() {
        VoiceMachine machine = ready();
        long generation = liveCall(machine, person("me", 1), person("friend", 2));
        List<Effect> effects = machine.socketClosed(generation, 0, 10_000);
        assertTrue(kind(effects, Effect.Kind.DISCONNECT));
        Long back = machine.nextDeadline(10_000);
        assertNotNull(back);
        assertTrue(String.valueOf(back), back >= 10_500 && back <= 11_000);
        // Not "Yeniden bağlanıyor…" yet.
        assertEquals(VoiceState.LIVE, machine.view(10_400, UNIX).phase);
        effects = machine.tick(back, UNIX, back);
        long again = connectGeneration(effects);
        assertNotEquals(generation, again);
        for (Effect e : effects) if (e.kind == Effect.Kind.CONNECT) assertEquals("v1.resume.mac", e.ticket);
        // The audio still flows: sync, no new offer.
        effects = machine.socketOpen(again, 11_500);
        assertEquals(Collections.singletonList("sync"), sent(effects));
        // Old sockets' words change nothing.
        assertTrue(machine.socketClosed(generation, 0, 11_600).isEmpty());
        effects = machine.tick(12_000, UNIX, 12_000);
        assertTrue(kind(effects, Effect.Kind.CHANGED));
        assertTrue(sounds(effects).contains(Sound.LOST));
        assertEquals(VoiceState.RECONNECTING, machine.view(12_000, UNIX).phase);
        effects = machine.socketMessage(again, hello(false, person("me", 1), person("friend", 2)), 12_100, UNIX);
        assertEquals(Collections.singletonList("set"), sent(effects));
        assertTrue(sounds(effects).contains(Sound.CONNECTED));
        assertEquals(VoiceState.LIVE, machine.view(12_100, UNIX).phase);
    }

    @Test
    public void aConnectionThatDoesNotComeBackGivesUpAndMayRetry() {
        VoiceMachine machine = ready();
        long generation = liveCall(machine, person("me", 1), person("friend", 2));
        machine.socketClosed(generation, 0, 10_000);
        long now = 10_000;
        while (true) {
            Long at = machine.nextDeadline(now);
            assertNotNull("no deadline while away", at);
            assertTrue("a past deadline would spin", at >= now);
            now = at;
            List<Effect> effects = machine.tick(now, UNIX, now);
            for (Effect e : effects) {
                if (e.kind == Effect.Kind.CONNECT) machine.socketRefused(e.generation, 0, null, now + 100);
            }
            if (!machine.inRoom()) {
                assertTrue(notices(effects).contains(Notice.left(LeftReason.FAILED, null, null)));
                break;
            }
            assertTrue(now <= 10_000 + VoiceMachine.GIVE_UP_MS);
        }
        assertNotNull(machine.retryTicket());
    }

    @Test
    public void closeCodes() {
        VoiceMachine machine = ready();
        long generation = liveCall(machine, person("me", 1));
        List<Effect> effects = machine.socketClosed(generation, 4001, 5_000);
        assertFalse(machine.inRoom());
        assertTrue(notices(effects).contains(Notice.left(LeftReason.UNAVAILABLE, null, null)));

        machine = ready();
        generation = liveCall(machine, person("me", 1));
        machine.socketClosed(generation, 4008, 5_000);
        assertTrue(machine.inRoom());
        // "Yeniden bağlanıyor…" after 2 s, but no new socket for a minute.
        long shown = machine.nextDeadline(5_000);
        assertEquals(7_000, shown);
        effects = machine.tick(shown, UNIX, shown);
        assertFalse(kind(effects, Effect.Kind.CONNECT));
        assertTrue(machine.nextDeadline(shown) >= 5_000 + VoiceMachine.RATE_LIMITED_WAIT_MS);

        machine = ready();
        generation = liveCall(machine, person("me", 1));
        machine.socketClosed(generation, 4003, 5_000);
        assertFalse(machine.inRoom());
    }

    @Test
    public void theRoomsEndingsTellThePlayer() {
        VoiceMachine machine = ready();
        List<Effect> effects = machine.join(new Start(ROOM, "t", "friend", false), SERVICE, 0, UNIX);
        long generation = connectGeneration(effects);
        machine.socketOpen(generation, 10);
        machine.socketMessage(generation, hello(false, person("me", 1)), 20, UNIX);
        effects = machine.socketMessage(generation, msg("{\"t\":\"closed\",\"why\":\"no_answer\"}"), 30_000, UNIX);
        assertTrue(notices(effects).contains(Notice.left(LeftReason.NO_ANSWER, "friend", null)));
        assertFalse(machine.inRoom());

        machine = ready();
        generation = liveCall(machine, person("me", 1), person("x", 2));
        effects = machine.socketMessage(generation, msg("{\"t\":\"kicked\"}"), 5_000, UNIX);
        Notice kicked = notices(effects).get(0);
        assertEquals(LeftReason.KICKED, kicked.reason);
        assertTrue(kicked.system());
        assertTrue(sounds(effects).contains(Sound.ENDED));
        // Leaving lets the engine linger a minute, then it closes and nothing wakes.
        assertEquals(Long.valueOf(5_000 + VoiceMachine.LINGER_MS), machine.nextDeadline(5_000));
        effects = machine.tick(5_000 + VoiceMachine.LINGER_MS, UNIX, 0);
        assertTrue(kind(effects, Effect.Kind.ENGINE_CLOSE));
        assertNull(machine.nextDeadline(5_000 + VoiceMachine.LINGER_MS));
        assertFalse(machine.engineOpen());
    }

    @Test
    public void aVoiceChatBanTakesThePlayerOutUntilItsEnd() {
        VoiceMachine machine = ready();
        long generation = liveCall(machine, person("me", 1), person("x", 2));
        List<Effect> effects = machine.socketMessage(generation,
                msg("{\"t\":\"closed\",\"why\":\"muted\",\"until\":" + (UNIX + 3_600) + "}"), 5_000, UNIX);
        assertFalse(machine.inRoom());
        assertTrue(notices(effects).contains(Notice.left(LeftReason.RESTRICTED, null, UNIX + 3_600)));

        // Coming back meets the ban: no "Tekrar dene".
        machine = ready();
        generation = liveCall(machine, person("me", 1), person("x", 2));
        machine.socketClosed(generation, 0, 5_000);
        long now = 5_000;
        long again = -1;
        while (again < 0) {
            now = machine.nextDeadline(now);
            assertTrue(now <= 5_000 + VoiceMachine.GIVE_UP_MS);
            for (Effect e : machine.tick(now, UNIX, now)) if (e.kind == Effect.Kind.CONNECT) again = e.generation;
        }
        effects = machine.socketRefused(again, 403, "voice_muted", now + 100);
        assertFalse(machine.inRoom());
        assertTrue(notices(effects).contains(Notice.left(LeftReason.RESTRICTED, null, null)));
        assertNull(machine.retryTicket());
    }

    @Test
    public void aMoveJoinsTheOtherRoom() {
        VoiceMachine machine = ready();
        long generation = liveCall(machine, person("me", 1));
        List<Effect> effects = machine.socketMessage(generation,
                msg("{\"t\":\"moved\",\"room\":\"c00000000000000aa\",\"ticket\":\"v1.move.mac\"}"), 5_000, UNIX);
        long moved = connectGeneration(effects);
        assertNotEquals(generation, moved);
        assertTrue(kind(effects, Effect.Kind.DISCONNECT));
        assertTrue(notices(effects).contains(Notice.moved("c00000000000000aa")));
        // Nobody waits for that join's answer, and a move is no ending.
        assertNull(answer(effects, VoiceMachine.JOIN_RID));
        assertFalse(sounds(effects).contains(Sound.ENDED));
        assertEquals("c00000000000000aa", machine.roomId());
    }

    @Test
    public void muteAndDeafen() {
        VoiceMachine machine = ready();
        long generation = liveCall(machine, person("me", 1), person("friend", 2));
        List<Effect> effects = machine.setMute(true);
        assertEquals(Collections.singletonList("set"), sent(effects));
        assertTrue(talk(effects, false));
        assertTrue(sounds(effects).contains(Sound.MIC_OFF));
        effects = machine.setDeaf(true);
        boolean deaf = false;
        for (Command c : engine(effects)) if (c.kind == Command.Kind.DEAF && c.on) deaf = true;
        assertTrue(deaf);
        assertTrue(sounds(effects).contains(Sound.DEAF_ON));
        // Opening the microphone ends deafness too, and says the sound is back.
        effects = machine.toggleMic();
        assertTrue(talk(effects, true));
        assertTrue(sounds(effects).contains(Sound.DEAF_OFF));
        VoiceState view = machine.view(0, UNIX);
        assertFalse(view.me.mute || view.me.deaf);
        // Muted for everyone by a moderator: closed whatever the player does.
        effects = machine.socketMessage(generation, msg("{\"t\":\"state\",\"u\":\"me\",\"sm\":true}"), 10, UNIX);
        assertTrue(notices(effects).contains(Notice.mutedByModerator()));
        assertTrue(talk(effects, false));
        assertTrue(sounds(effects).contains(Sound.MIC_OFF));
    }

    @Test
    public void powersFollowTheRoles() {
        VoiceMachine machine = ready();
        List<Effect> effects = machine.join(new Start("g0123456789abcdef", "t", null, false), SERVICE, 0, UNIX);
        long generation = connectGeneration(effects);
        machine.socketOpen(generation, 10);
        machine.socketMessage(generation, msg("{\"t\":\"hello\",\"room\":{\"id\":\"g0123456789abcdef\",\"kind\":\"c\","
                + "\"crew\":\"0123456789abcdef\",\"name\":\"Genel\",\"leader\":\"boss\",\"mods\":[\"me\",\"m2\"],\"max\":15},"
                + "\"me\":{\"role\":\"m\"},\"people\":[" + person("me", 1) + "," + person("boss", 2) + "," + person("m2", 3) + ","
                + person("u1", 4) + "]}"), 20, UNIX);
        VoiceState view = machine.view(20, UNIX);
        // A moderator acts on members only; never renames or closes "Genel".
        assertFalse(view.person("boss").canMute);
        assertFalse(view.person("m2").canKick);
        assertTrue(view.person("u1").canMute && view.person("u1").canKick && view.person("u1").canMove);
        assertFalse(view.person("u1").canModerator);
        assertFalse(view.canRename);
        assertFalse(view.canClose);
        assertEquals(VoiceState.LEADER, view.person("boss").role);
    }

    @Test
    public void requestsAreAnsweredByRid() {
        VoiceMachine machine = ready();
        // Not in a room: nothing to ask on.
        assertEquals(VoiceAnswer.refused(VoiceAnswer.Refusal.NOTHING), machine.invite("x").refused);
        long generation = liveCall(machine, person("me", 1), person("friend", 2));
        VoiceMachine.Request invite = machine.invite("ece");
        assertNull(invite.refused);
        assertEquals(Collections.singletonList("invite"), sent(invite.effects));
        List<Effect> effects = machine.socketMessage(generation, msg("{\"t\":\"ack\",\"rid\":" + invite.rid + ",\"until\":130}"), 10, UNIX);
        assertEquals(VoiceAnswer.OK, answer(effects, invite.rid));
        VoiceMachine.Request kick = machine.moderate(ModAct.KICK, "friend", null, null);
        effects = machine.socketMessage(generation,
                msg("{\"t\":\"error\",\"rid\":" + kick.rid + ",\"code\":\"not_allowed\"}"), 20, UNIX);
        assertEquals(VoiceAnswer.refused(VoiceAnswer.Refusal.NOT_ALLOWED), answer(effects, kick.rid));
        // A name of the wrong length never goes out; a move to no room neither.
        assertEquals(VoiceAnswer.Refusal.INVALID_NAME, machine.moderate(ModAct.NAME, null, null, " ab ").refused.refused);
        assertEquals(VoiceAnswer.Refusal.ROOM_GONE, machine.moderate(ModAct.MOVE, "friend", "nope", null).refused.refused);
        // Leaving answers whatever still waits.
        VoiceMachine.Request close = machine.moderate(ModAct.CLOSE, null, null, null);
        effects = machine.leave(30);
        assertEquals(VoiceAnswer.refused(VoiceAnswer.Refusal.NOTHING), answer(effects, close.rid));
        assertTrue(sent(effects).contains("leave"));
    }

    @Test
    public void turnCredentialsReachTheEngineAndARefusalIsALostConnection() {
        VoiceMachine machine = ready();
        long generation = liveCall(machine, person("me", 1), person("friend", 2));
        List<Effect> effects = machine.engineNeedTurn(5_000);
        assertEquals(Collections.singletonList("turn"), sent(effects));
        int rid = VoiceWireTestSupport.rid(effects);
        effects = machine.socketMessage(generation, msg("{\"t\":\"ack\",\"rid\":" + rid
                + ",\"ice\":{\"urls\":[\"turn:a\"],\"username\":\"u\",\"credential\":\"c\"}}"), 5_100, UNIX);
        assertTrue(has(effects, Command.Kind.ICE));
        // Refused the next time: as lost, and the engine joins anew at once.
        effects = machine.engineNeedTurn(6_000);
        rid = VoiceWireTestSupport.rid(effects);
        effects = machine.socketMessage(generation, msg("{\"t\":\"error\",\"rid\":" + rid + ",\"code\":\"turn_unavailable\"}"),
                6_100, UNIX);
        assertTrue(has(effects, Command.Kind.JOIN));
    }

    @Test
    public void rejoinsThatNeverConnectGiveUpWithoutSpinning() {
        assertEquals(0, VoiceMachine.rejoinWait(0));
        assertEquals(1_000, VoiceMachine.rejoinWait(1));
        assertEquals(2_000, VoiceMachine.rejoinWait(2));
        assertEquals(4_000, VoiceMachine.rejoinWait(3));
        assertEquals(8_000, VoiceMachine.rejoinWait(4));
        assertEquals(8_000, VoiceMachine.rejoinWait(40));
        VoiceMachine machine = ready();
        liveCall(machine, person("me", 1), person("friend", 2));
        long now = 10_000;
        assertTrue(has(machine.engineLost(now), Command.Kind.JOIN));
        machine.engineOffer("v=0 again", "0");
        // Lost again before it connected: the rejoin waits a second.
        assertFalse(has(machine.engineLost(now + 100), Command.Kind.JOIN));
        assertEquals(Long.valueOf(now + 1_100), machine.nextDeadline(now + 100));
        while (machine.inRoom()) {
            Long at = machine.nextDeadline(now);
            assertNotNull(at);
            assertTrue(at >= now);
            now = at;
            for (Effect e : machine.tick(now, UNIX, now)) {
                if (e.kind == Effect.Kind.ENGINE && e.command.kind == Command.Kind.JOIN) machine.engineLost(now);
            }
        }
        assertTrue(now <= 10_000 + VoiceMachine.GIVE_UP_MS);
    }

    @Test
    public void theInactivityRuleEndsAQuietStay() {
        VoiceMachine machine = ready();
        liveCall(machine, person("me", 1), person("friend", 2));
        long minute = 60_000;
        // The phone touched at 10 minutes, the player spoke at 20: 30 minutes after that.
        machine.engineSpeaking(Collections.<String>emptyList(), true, 20 * minute);
        assertEquals(Long.valueOf(50 * minute), machine.nextDeadline(10 * minute));
        List<Effect> effects = machine.tick(50 * minute, UNIX, 10 * minute);
        assertTrue(notices(effects).contains(Notice.left(LeftReason.IDLE, null, null)));
    }

    @Test
    public void speakingGoesOutOnlyOnChange() {
        VoiceMachine machine = ready();
        liveCall(machine, person("me", 1), person("friend", 2));
        List<Effect> effects = machine.engineSpeaking(Arrays.asList("friend", "stranger"), false, 10);
        assertEquals(Collections.singletonList("friend"), effects.get(0).speaking);
        assertTrue(machine.engineSpeaking(Collections.singletonList("friend"), false, 20).isEmpty());
        assertTrue(machine.view(20, UNIX).person("friend").speaking);
    }

    @Test
    public void perPersonChoicesReachTheEngine() {
        FakePrefs prefs = new FakePrefs();
        VoiceMachine machine = ready(prefs);
        liveCall(machine, person("me", 1), person("friend", 2));
        List<Effect> effects = machine.setVolume("friend", 250);
        assertEquals(VoiceMachine.VOLUME_MAX, prefs.volume("friend"));
        assertEquals(VoiceMachine.VOLUME_MAX, engine(effects).get(0).people.get(0).volume);
        effects = machine.setMutedForMe("friend", true);
        assertTrue(engine(effects).get(0).people.get(0).silent);
        assertTrue(machine.setMutedForMe("friend", true).isEmpty());
        assertTrue(machine.view(0, UNIX).person("friend").mutedForMe);
    }

    @Test
    public void voiceChatSoundsOffKeepsOnlyTheCallersTone() {
        FakePrefs prefs = new FakePrefs();
        prefs.sounds = false;
        VoiceMachine machine = ready(prefs);
        List<Effect> effects = machine.join(new Start(ROOM, "t", "friend", false), SERVICE, 0, UNIX);
        long generation = connectGeneration(effects);
        machine.engineAttached();
        machine.socketOpen(generation, 10);
        effects = machine.socketMessage(generation, hello(true, person("me", 1)), 20, UNIX);
        assertEquals(Collections.singletonList(Sound.WAITING), sounds(effects));
        effects = machine.socketMessage(generation, msg("{\"t\":\"joined\",\"person\":" + person("friend", 5) + "}"), 30, UNIX);
        assertEquals(Collections.singletonList(Sound.STOP), sounds(effects));
    }

    @Test
    public void noMicrophoneListensOnly() {
        VoiceMachine machine = ready();
        liveCall(machine, person("me", 1), person("friend", 2));
        List<Effect> effects = machine.engineMic(VoiceMachine.Mic.DENIED);
        assertTrue(notices(effects).contains(Notice.mic(VoiceMachine.Mic.DENIED)));
        assertTrue(talk(effects, false));
        assertEquals("denied", machine.view(0, UNIX).mic);
    }

    @Test
    public void blockingTheOnlyOtherPersonEndsTheCall() {
        VoiceMachine machine = ready();
        liveCall(machine, person("me", 1), person("friend", 2));
        machine.blocked("someone", 10);
        assertTrue(machine.inRoom());
        machine.blocked("friend", 10);
        assertFalse(machine.inRoom());
    }
}
