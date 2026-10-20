package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * M19: answers recorded from local copies of the friends service (m19 tools/capture.mjs, throwaway
 * players): the newest service (projects-studio 4376a67: looks, calls, roles, mute, push) and the code
 * that is deployed (6c1e68c: friends, plain crews). The phone reads both.
 */
public class ServiceFixturesTest {
    private static final File DIR = new File("src/test/resources/social");
    private static final String A = "100000000000000011";
    private static final String B = "100000000000000012";

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File(DIR, name).toPath()), StandardCharsets.UTF_8);
    }

    private static SocialState state(String tag) throws Exception {
        SocialState state = new SocialState();
        state.setMe(new SocialModel.Me(new SocialModel.Profile(A, "ayse", "Ayse", "discord", null), "PRJ-AAAA-BBBB",
                "online", false, true, null));
        state.applyFriends(SocialModel.FriendsData.read(Json.parseObject(read("friends-" + tag + ".json"))));
        return state;
    }

    private static List<SocialState.Notice> replay(SocialState state, String tag) throws Exception {
        List<SocialState.Notice> notices = new ArrayList<>();
        for (String line : read("socket-" + tag + ".jsonl").split("\n")) {
            if (line.trim().isEmpty()) continue;
            JsonObject message = Json.parseObject(line);
            assertNotNull(line, message);
            long now = Json.num(Json.obj(message, "call"), "at", 0);
            notices.addAll(state.apply(message, now > 0 ? now : 1792536700L));
        }
        return notices;
    }

    @Test
    public void theNewestServiceReadsWhole() throws Exception {
        SocialState state = state("newest");
        assertEquals(2, state.friends().size());
        assertEquals(1, state.incoming().size());
        assertNotNull(state.team());
        assertEquals("teal", state.team().color);
        assertEquals("shield", state.team().frame);
        assertEquals("Gece bizimdir", state.team().motto);
        assertEquals(3, state.team().members.size());
        assertTrue(state.team().member(B).founder);
        assertTrue(state.serviceHasTeams());
        SocialGate gate = SocialGate.of(true, true, state.limits().teamMembers, state.teamFeatures(), state.features(), true);
        assertTrue(gate.teams && gate.look && gate.calls && gate.roles && gate.mute && gate.push);
        assertTrue(state.teamNotifications().muted);
    }

    @Test
    public void theDeployedServiceReadsWithoutTheNewParts() throws Exception {
        SocialState state = state("deployed");
        assertEquals(2, state.friends().size());
        assertNotNull(state.team());
        assertEquals("today's look", "gold", state.team().color);
        assertEquals("cut", state.team().frame);
        assertNull(state.team().motto);
        assertFalse(state.team().member(A).moderator);
        assertNull(state.features());
        SocialGate gate = SocialGate.of(true, true, state.limits().teamMembers, state.teamFeatures(), state.features(), true);
        assertTrue(gate.friends && gate.teams);
        assertFalse(gate.look || gate.calls || gate.roles || gate.mute || gate.push);
    }

    @Test
    public void theNewestSocketTellsInviteCallAndItsEnd() throws Exception {
        SocialState state = state("newest");
        List<SocialState.Notice> notices = replay(state, "newest");
        List<SocialState.Notice.Kind> kinds = new ArrayList<>();
        for (SocialState.Notice n : notices) kinds.add(n.kind);
        assertTrue(kinds.toString(), kinds.contains(SocialState.Notice.Kind.INVITE));
        assertTrue(kinds.toString(), kinds.contains(SocialState.Notice.Kind.CALL));
        assertTrue(kinds.toString(), kinds.contains(SocialState.Notice.Kind.CALL_ENDED));
        assertFalse("the request was in the list already", kinds.contains(SocialState.Notice.Kind.REQUEST));
        assertTrue(state.presence(B).onProjects());
        assertTrue(state.calls(1792536800L).isEmpty());
        assertTrue(state.teamNotifications().muted);
        SocialModel.Invite invite = state.invites(1792536800L).get(0);
        assertTrue(invite.onProjects());
        assertEquals("play.projects.gg", invite.host);
    }

    @Test
    public void theDeployedSocketHasNoCallsAndStillTellsTheInvite() throws Exception {
        SocialState state = state("deployed");
        List<SocialState.Notice> notices = replay(state, "deployed");
        assertEquals(SocialState.Notice.Kind.INVITE, notices.get(0).kind);
        assertTrue(state.live());
        assertTrue(state.calls(1792536800L).isEmpty());
        assertTrue(state.presence(B).onProjects());
    }
}
