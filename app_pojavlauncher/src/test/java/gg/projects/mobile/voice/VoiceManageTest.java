package gg.projects.mobile.voice;

import static gg.projects.mobile.voice.VoiceMachineTest.UNIX;
import static gg.projects.mobile.voice.VoiceMachineTest.liveCall;
import static gg.projects.mobile.voice.VoiceMachineTest.person;
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
import gg.projects.mobile.voice.VoiceWire.ModAct;

/**
 * M053: managing a crew's voice rooms on the phone (the desktop's roomPowers, limitChoices, roomNameFits), "Paylaşımı
 * durdur" ({@code mod unshare}) and the ignore list's mute in a room.
 */
public class VoiceManageTest {
    private static VoiceRings.CrewRoom room(String id, boolean own, String... people) {
        return new VoiceRings.CrewRoom(id, "Oda", Arrays.asList(people), 0, own);
    }

    @Test
    public void whoMayManageRooms() {
        VoiceRings.CrewRoom general = room("g-crew1", false);
        VoiceRings.CrewRoom other = room("c-crew1-2", false);
        VoiceRings.CrewRoom mine = room("c-crew1-3", true);
        // Studio's switch off: nobody; "Genel": never.
        assertFalse(VoiceRoomRules.powers(false, true, false, other).any());
        assertFalse(VoiceRoomRules.powers(true, true, false, general).any());
        // The leader and moderators: everything.
        VoiceRoomRules.Powers leader = VoiceRoomRules.powers(true, true, false, other);
        assertTrue(leader.edit && leader.order && leader.remove);
        VoiceRoomRules.Powers moderator = VoiceRoomRules.powers(true, false, true, other);
        assertTrue(moderator.edit && moderator.order && moderator.remove);
        // A member: only the room they opened, and no ordering.
        assertFalse(VoiceRoomRules.powers(true, false, false, other).any());
        VoiceRoomRules.Powers opener = VoiceRoomRules.powers(true, false, false, mine);
        assertTrue(opener.edit && opener.remove);
        assertFalse(opener.order);
    }

    @Test
    public void limitsAndNames() {
        assertEquals(Arrays.asList(2, 3, 4, 5), VoiceRoomRules.limitChoices(5));
        assertEquals(24, VoiceRoomRules.limitChoices(40).size());
        assertEquals(Integer.valueOf(4), VoiceRoomRules.limitToSend(4, 15));
        assertNull(VoiceRoomRules.limitToSend(15, 15));
        assertEquals(15, VoiceRoomRules.limitShown(0, 15));
        assertEquals(6, VoiceRoomRules.limitShown(6, 15));
        assertEquals(10, VoiceRoomRules.limitShown(20, 10));
        assertTrue(VoiceRoomRules.nameFits(" Gece "));
        assertFalse(VoiceRoomRules.nameFits("ab"));
        assertFalse(VoiceRoomRules.nameFits("abcdefghijklmnopqrstuvwxy"));
        assertTrue(VoiceRoomRules.nameFits("Şölen Odası"));
    }

    @Test
    public void roomsMoveAmongTheOthers() {
        List<VoiceRings.CrewRoom> rooms = Arrays.asList(room("g-crew1", false), room("c-crew1-2", false), room("c-crew1-3", false),
                room("c-crew1-4", false));
        assertEquals(Arrays.asList("c-crew1-3", "c-crew1-2", "c-crew1-4"), VoiceRoomRules.moved(rooms, "c-crew1-3", -1));
        assertEquals(Arrays.asList("c-crew1-2", "c-crew1-4", "c-crew1-3"), VoiceRoomRules.moved(rooms, "c-crew1-3", 1));
        // The first room cannot pass "Genel"; the last cannot go further; "Genel" never moves.
        assertNull(VoiceRoomRules.moved(rooms, "c-crew1-2", -1));
        assertNull(VoiceRoomRules.moved(rooms, "c-crew1-4", 1));
        assertNull(VoiceRoomRules.moved(rooms, "g-crew1", 1));
    }

    @Test
    public void unshareFrame() {
        assertEquals(JsonParser.parseString("{\"t\":\"mod\",\"act\":\"unshare\",\"to\":\"u1\",\"n\":3,\"rid\":7}"),
                JsonParser.parseString(VoiceWire.unshare("u1", 3, 7)));
        assertEquals("unshare", ModAct.UNSHARE.wire());
    }

    private static String sharing(String u, long j, int... ns) {
        StringBuilder shares = new StringBuilder();
        for (int n : ns) {
            if (shares.length() > 0) shares.append(',');
            shares.append("{\"n\":").append(n).append(",\"src\":\"game\",\"w\":0,\"th\":0}");
        }
        String p = person(u, j);
        return p.substring(0, p.length() - 1) + ",\"sh\":[" + shares + "]}";
    }

    private static List<String> frames(List<Effect> effects) {
        List<String> out = new ArrayList<>();
        for (Effect e : effects) if (e.kind == Effect.Kind.SEND) out.add(e.frame);
        return out;
    }

    @Test
    public void stopTheirShareSendsOneFramePerShare() {
        VoiceMachine machine = VoiceMachineTest.ready();
        liveCall(machine, person("me", 1), sharing("friend", 2, 1, 4));
        VoiceMachine.Request stop = machine.moderate(ModAct.UNSHARE, "friend", null, null);
        assertNull(stop.refused);
        List<String> sent = frames(stop.effects);
        assertEquals(2, sent.size());
        assertEquals("unshare", JsonParser.parseString(sent.get(0)).getAsJsonObject().get("act").getAsString());
        assertEquals(1, JsonParser.parseString(sent.get(0)).getAsJsonObject().get("n").getAsInt());
        assertEquals(stop.rid, JsonParser.parseString(sent.get(0)).getAsJsonObject().get("rid").getAsInt());
        assertEquals(4, JsonParser.parseString(sent.get(1)).getAsJsonObject().get("n").getAsInt());
        // Someone who does not share: nothing goes out.
        VoiceMachine other = VoiceMachineTest.ready();
        liveCall(other, person("me", 1), person("friend", 2));
        assertEquals(VoiceAnswer.Refusal.NOT_FOUND, other.moderate(ModAct.UNSHARE, "friend", null, null).refused.refused);
    }

    /** The test prefs with an ignore list. */
    private static final class IgnoringPrefs implements VoiceMachine.Prefs {
        final List<String> ignored = new ArrayList<>();

        @Override
        public int volume(String person) {
            return 100;
        }

        @Override
        public boolean mutedForMe(String person) {
            return false;
        }

        @Override
        public void setVolume(String person, int percent) {}

        @Override
        public void setMutedForMe(String person, boolean on) {}

        @Override
        public boolean sounds() {
            return false;
        }

        @Override
        public boolean ignored(String person) {
            return ignored.contains(person);
        }
    }

    private static boolean silent(List<Effect> effects, String person) {
        for (Effect e : effects) {
            if (e.kind != Effect.Kind.ENGINE || e.command == null || e.command.people == null) continue;
            for (VoiceMachine.EnginePerson p : e.command.people) if (p.id.equals(person)) return p.silent;
        }
        throw new AssertionError("no people command");
    }

    @Test
    public void anIgnoredPersonIsSilentWithoutTouchingTheirMuteChoice() {
        IgnoringPrefs prefs = new IgnoringPrefs();
        VoiceMachine machine = ready(prefs);
        liveCall(machine, person("me", 1), person("friend", 2));
        assertFalse(silent(machine.replay(), "friend"));
        prefs.ignored.add("friend");
        assertTrue(silent(machine.replay(), "friend"));
        // "Benim için sustur" still reads the player's own choice.
        assertFalse(machine.view(2_000, UNIX).person("friend").mutedForMe);
        prefs.ignored.clear();
        assertFalse(silent(machine.replay(), "friend"));
    }

    private static VoiceMachine ready(VoiceMachine.Prefs prefs) {
        VoiceMachine machine = new VoiceMachine(prefs, new java.util.Random(1));
        machine.setBackoff(new VoiceMachine.Backoff(1_000, 1_000, 0));
        machine.setMe("me");
        return machine;
    }
}
