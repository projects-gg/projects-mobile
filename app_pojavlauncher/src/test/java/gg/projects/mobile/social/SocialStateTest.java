package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M19: the friend list made live by the presence socket (README "Presence WebSocket"), and the
 * notices the app may announce. Messages here are the service's own shapes.
 */
public class SocialStateTest {
    static final String ME = "100000000000000001";
    static final String AYSE = "100000000000000002";
    static final String KAAN = "100000000000000003";
    static final String ECE = "100000000000000004";
    static final long NOW = 1792521000L;

    static JsonObject json(String text) {
        JsonObject o = Json.parseObject(text);
        assertNotNull(text, o);
        return o;
    }

    static String profile(String id, String name) {
        return "{\"id\":\"" + id + "\",\"username\":\"" + name.toLowerCase() + "\",\"displayName\":\"" + name
                + "\",\"avatar\":\"discord\",\"avatarHash\":null}";
    }

    /** {@code GET /v1/friends} of the newest service: two friends, a crew with Kaan (not a friend). */
    static String friendsJson(boolean newest) {
        String team = "{\"id\":\"0123456789abcdef\",\"name\":\"Gece Kurtları\",\"tag\":\"GKR\",\"emblem\":\"flame\""
                + (newest ? ",\"color\":\"teal\",\"frame\":\"shield\",\"motto\":\"Gece bizimdir\"" : "")
                + ",\"code\":\"TKM-ABCD-EFGH\",\"createdOn\":\"2026-09-20\",\"members\":["
                + "{\"id\":\"" + ME + "\",\"username\":\"deniz\",\"displayName\":\"Deniz\",\"avatar\":\"discord\",\"role\":\"founder\",\"moderator\":false,\"joinedAt\":1}"
                + ",{\"id\":\"" + KAAN + "\",\"username\":\"kaan\",\"displayName\":\"Kaan\",\"avatar\":\"preset:fox\",\"role\":\"member\",\"moderator\":true,\"joinedAt\":2}"
                + ",{\"id\":\"" + AYSE + "\",\"username\":\"ayse\",\"displayName\":\"Ayşe\",\"avatar\":\"discord\",\"role\":\"member\",\"moderator\":false,\"joinedAt\":3}"
                + "]" + (newest ? ",\"permissions\":{\"invite\":\"members\",\"call\":\"moderators\",\"kick\":\"moderators\",\"edit\":\"leader\"}" : "") + "}";
        return "{\"friends\":[" + profile(AYSE, "Ayşe").replace("}", ",\"since\":\"2026-09-12\",\"lastSeen\":\"today\"}")
                + "," + profile(ECE, "Ece").replace("}", ",\"since\":\"2026-09-13\",\"lastSeen\":\"week\"}") + "]"
                + ",\"incoming\":[],\"outgoing\":[],\"blocked\":[]"
                + ",\"limits\":{\"friends\":200,\"teamMembers\":30}"
                + ",\"team\":" + team + ",\"teamInvited\":[],\"teamInvites\":[]"
                + (newest ? ",\"teamFeatures\":[\"look\",\"calls\",\"roles\",\"mute\"],\"teamNotifications\":{\"calls\":true,\"muted\":false,\"muteUntil\":null},\"features\":[\"push\"]" : "")
                + "}";
    }

    static SocialState signedIn(boolean newest) {
        SocialState state = new SocialState();
        state.setMe(SocialModel.Me.read(json(profile(ME, "Deniz").replace("}", ",\"friendCode\":\"PRJ-AAAA-BBBB\",\"status\":\"online\"}"))));
        state.applyFriends(SocialModel.FriendsData.read(json(friendsJson(newest))));
        return state;
    }

    @Test
    public void theListReadsTheNewestAndTheDeployedService() {
        SocialState newest = signedIn(true);
        assertEquals(2, newest.friends().size());
        assertTrue(newest.serviceHasTeams());
        assertTrue(newest.hasTeamFeature("calls"));
        assertNotNull(newest.features());
        assertEquals("teal", newest.team().color);
        assertEquals("shield", newest.team().frame);
        assertEquals("Gece bizimdir", newest.team().motto);
        assertTrue(newest.team().member(KAAN).moderator);
        assertEquals("moderators", newest.team().permissions.call);
        assertFalse("a plain member may not call here", newest.team().allows(AYSE, newest.team().permissions.call));
        assertTrue(newest.team().allows(KAAN, newest.team().permissions.call));

        SocialState deployed = signedIn(false);
        assertTrue(deployed.serviceHasTeams());
        assertFalse(deployed.hasTeamFeature("calls"));
        assertNull("no features field: a service from before pushes", deployed.features());
        assertEquals("gold", deployed.team().color);
        assertEquals("cut", deployed.team().frame);
        assertNull(deployed.team().motto);
        assertEquals(SocialModel.Permissions.DEFAULTS.call, deployed.team().permissions.call);
        assertTrue(deployed.team().allows(AYSE, deployed.team().permissions.call));
        assertFalse(deployed.teamNotifications().muted);
    }

    @Test
    public void helloReplacesPresenceInvitesAndCalls() {
        SocialState state = signedIn(true);
        String hello = "{\"t\":\"hello\",\"v\":1,\"status\":\"dnd\",\"keepaliveSeconds\":45"
                + ",\"friends\":[{\"id\":\"" + AYSE + "\",\"state\":\"playing\",\"status\":\"online\",\"game\":{\"where\":\"projects\"}}]"
                + ",\"team\":[{\"id\":\"" + KAAN + "\",\"state\":\"launcher\",\"status\":\"online\"}]"
                + ",\"invites\":[{\"id\":\"57\",\"from\":\"" + AYSE + "\",\"at\":" + (NOW - 60) + ",\"server\":{\"where\":\"projects\",\"host\":\"projects.gg\"},\"quiet\":true}]"
                + ",\"calls\":[{\"id\":\"c1\",\"from\":\"" + KAAN + "\",\"kind\":\"ready\",\"at\":" + (NOW - 100) + ",\"until\":" + (NOW + 1100) + ",\"answers\":{}}]"
                + ",\"now\":" + (NOW + 5) + "}";
        List<SocialState.Notice> notices = state.apply(json(hello), NOW);
        assertTrue("hello is shown quietly", notices.isEmpty());
        assertTrue(state.live());
        assertEquals("dnd", state.me().status);
        assertTrue(state.presence(AYSE).onProjects());
        assertTrue(state.presence(KAAN).online());
        assertFalse(state.presence(ECE).online());
        assertEquals(1, state.invites(NOW).size());
        assertEquals(1, state.calls(NOW).size());
        assertEquals("the hub's clock", NOW + 5, state.hubNow(NOW));
        assertEquals(1095, state.secondsLeft(state.calls(NOW).get(0), NOW));
        assertEquals(KAAN, state.unansweredCall(NOW).from);

        // A second hello drops what it does not list.
        state.apply(json("{\"t\":\"hello\",\"friends\":[],\"team\":[],\"invites\":[],\"calls\":[],\"now\":" + NOW + "}"), NOW);
        assertFalse(state.presence(AYSE).online());
        assertTrue(state.invites(NOW).isEmpty());
        assertTrue(state.calls(NOW).isEmpty());
    }

    @Test
    public void offlineKeepsLastSeenAndThePictureFollows() {
        SocialState state = signedIn(true);
        state.apply(json("{\"t\":\"presence\",\"presence\":{\"id\":\"" + AYSE + "\",\"state\":\"launcher\",\"status\":\"online\",\"avatar\":\"preset:owl\"}}"), NOW);
        assertTrue(state.presence(AYSE).online());
        assertEquals("preset:owl", state.friend(AYSE).profile.avatar);
        state.apply(json("{\"t\":\"presence\",\"presence\":{\"id\":\"" + AYSE + "\",\"state\":\"offline\",\"lastSeen\":\"today\"}}"), NOW);
        assertFalse(state.presence(AYSE).online());
        assertEquals("today", state.presence(AYSE).lastSeen);
        state.setOffline();
        assertFalse(state.live());
    }

    @Test
    public void requestsAndFriendshipsAreToldOnce() {
        SocialState state = signedIn(true);
        String request = "{\"t\":\"request\",\"from\":" + profile("100000000000000005", "Can") + "}";
        assertEquals(SocialState.Notice.Kind.REQUEST, state.apply(json(request), NOW).get(0).kind);
        assertTrue("the same request again stays quiet", state.apply(json(request), NOW).isEmpty());
        assertEquals(1, state.incoming().size());
        state.apply(json("{\"t\":\"request_cancelled\",\"id\":\"100000000000000005\"}"), NOW);
        assertTrue(state.incoming().isEmpty());
        List<SocialState.Notice> added = state.apply(json("{\"t\":\"friend_added\",\"friend\":" + profile("100000000000000005", "Can")
                + ",\"presence\":{\"id\":\"100000000000000005\",\"state\":\"launcher\"}}"), NOW);
        assertEquals(SocialState.Notice.Kind.FRIEND_ADDED, added.get(0).kind);
        assertTrue(state.isFriend("100000000000000005"));
        state.apply(json("{\"t\":\"friend_removed\",\"id\":\"100000000000000005\"}"), NOW);
        assertFalse(state.isFriend("100000000000000005"));
    }

    @Test
    public void invitesAnnounceUnlessQuiet() {
        SocialState state = signedIn(true);
        String live = "{\"t\":\"invite\",\"invite\":{\"id\":\"58\",\"from\":\"" + AYSE + "\",\"at\":" + NOW + ",\"server\":{\"where\":\"projects\"},\"quiet\":false}}";
        List<SocialState.Notice> notices = state.apply(json(live), NOW);
        assertEquals(SocialState.Notice.Kind.INVITE, notices.get(0).kind);
        assertEquals("Ayşe", notices.get(0).who.name());
        assertTrue(notices.get(0).invite.onProjects());
        String quiet = "{\"t\":\"invite\",\"invite\":{\"id\":\"59\",\"from\":\"" + ECE + "\",\"at\":" + NOW + ",\"server\":null,\"quiet\":true}}";
        assertTrue(state.apply(json(quiet), NOW).isEmpty());
        assertEquals(2, state.invites(NOW).size());
        assertEquals("an invite lasts an hour", 0, state.invites(NOW + 3600).size());
        state.dismissInvite("58");
        assertEquals(1, state.invites(NOW).size());
    }

    @Test
    public void callsAnnounceWhenNewOrTurningJoinAndNeverTheirOwn() {
        SocialState state = signedIn(true);
        String ready = "{\"t\":\"call\",\"call\":{\"id\":\"c1\",\"from\":\"" + KAAN + "\",\"kind\":\"ready\",\"at\":" + NOW
                + ",\"until\":" + (NOW + 1200) + ",\"answers\":{}},\"quiet\":false,\"now\":" + NOW + "}";
        List<SocialState.Notice> first = state.apply(json(ready), NOW);
        assertEquals(SocialState.Notice.Kind.CALL, first.get(0).kind);
        assertEquals("c1", first.get(0).callId);
        assertTrue("the same call again is not new", state.apply(json(ready), NOW).isEmpty());
        String join = ready.replace("\"ready\"", "\"join\"");
        assertEquals("it turned join", 1, state.apply(json(join), NOW).size());
        String quiet = "{\"t\":\"call\",\"call\":{\"id\":\"c2\",\"from\":\"" + AYSE + "\",\"kind\":\"join\",\"at\":" + NOW
                + ",\"until\":" + (NOW + 1200) + ",\"answers\":{}},\"quiet\":true,\"now\":" + NOW + "}";
        assertTrue("muted or calls off: quiet", state.apply(json(quiet), NOW).isEmpty());
        String own = "{\"t\":\"call\",\"call\":{\"id\":\"c3\",\"from\":\"" + ME + "\",\"kind\":\"ready\",\"at\":" + NOW
                + ",\"until\":" + (NOW + 1200) + ",\"answers\":{}},\"quiet\":true,\"now\":" + NOW + "}";
        assertTrue(state.apply(json(own), NOW).isEmpty());
        assertEquals("c3", state.ownCall(NOW).id);

        state.apply(json("{\"t\":\"call_answer\",\"call\":\"c1\",\"by\":\"" + AYSE + "\",\"answer\":\"soon\"}"), NOW);
        assertEquals("soon", state.call("c1").answers.get(AYSE));
        state.answerLocally("c1", "yes");
        assertEquals("yes", state.call("c1").answers.get(ME));
        assertEquals("answered calls are not offered again", "c2", state.unansweredCall(NOW).id);
        List<SocialState.Notice> ended = state.apply(json("{\"t\":\"call_end\",\"call\":\"c1\",\"reason\":\"ended\"}"), NOW);
        assertEquals(SocialState.Notice.Kind.CALL_ENDED, ended.get(0).kind);
        assertEquals("c1", ended.get(0).callId);
        assertNull(state.call("c1"));
        assertEquals("a call past its time is gone without a message", 0, state.calls(NOW + 1200).size());
    }

    @Test
    public void leavingTheCrewIsToldUnlessTheirOwnDoing() {
        SocialState state = signedIn(true);
        List<SocialState.Notice> kicked = state.apply(json("{\"t\":\"team\",\"team\":null,\"reason\":\"kicked\",\"by\":\"" + KAAN + "\"}"), NOW);
        assertEquals(SocialState.Notice.Kind.TEAM_GONE, kicked.get(0).kind);
        assertEquals("kicked", kicked.get(0).reason);
        assertEquals("Gece Kurtları", kicked.get(0).teamName);
        assertFalse(kicked.get(0).byMe);
        assertNull(state.team());
        SocialState other = signedIn(true);
        List<SocialState.Notice> left = other.apply(json("{\"t\":\"team\",\"team\":null,\"reason\":\"left\",\"by\":\"" + ME + "\"}"), NOW);
        assertTrue(left.get(0).byMe);
    }

    @Test
    public void crewNotificationsFollowTheSocket() {
        SocialState state = signedIn(true);
        state.apply(json("{\"t\":\"team_notifications\",\"notifications\":{\"calls\":true,\"muted\":true,\"muteUntil\":" + (NOW + 3600) + "}}"), NOW);
        assertTrue(state.teamNotifications().mutedAt(NOW));
        assertFalse(state.teamNotifications().callsAudible(NOW));
        assertTrue("a timed mute ends by itself", state.teamNotifications().callsAudible(NOW + 3600));
        state.apply(json("{\"t\":\"team_notifications\",\"notifications\":{\"calls\":true,\"muted\":true,\"muteUntil\":null}}"), NOW);
        assertTrue("until unmuted", state.teamNotifications().mutedAt(NOW + 100 * 24 * 3600));
    }

    @Test
    public void sectionsPutFavouritesAndProjectsFirst() {
        SocialState state = signedIn(true);
        state.apply(json("{\"t\":\"hello\",\"friends\":["
                + "{\"id\":\"" + AYSE + "\",\"state\":\"playing\",\"game\":{\"where\":\"other\"}},"
                + "{\"id\":\"" + ECE + "\",\"state\":\"playing\",\"game\":{\"where\":\"projects\"}}],\"team\":[],\"invites\":[],\"calls\":[]}"), NOW);
        Set<String> none = Collections.emptySet();
        SocialState.Sections plain = state.sections(none);
        assertEquals("projects.gg first", ECE, plain.playing.get(0).profile.id);
        Set<String> favourites = new HashSet<>(Collections.singletonList(AYSE));
        assertEquals("favourites before everything", AYSE, state.sections(favourites).playing.get(0).profile.id);
        assertEquals(1, state.friendsOnProjects(none).size());
    }

    @Test
    public void rosterGroupsByPlaceWithTheLeaderFirst() {
        SocialState state = signedIn(true);
        state.apply(json("{\"t\":\"hello\",\"friends\":[{\"id\":\"" + AYSE + "\",\"state\":\"playing\",\"game\":{\"where\":\"projects\"}}]"
                + ",\"team\":[{\"id\":\"" + KAAN + "\",\"state\":\"playing\",\"game\":{\"where\":\"projects\"}}],\"invites\":[],\"calls\":[]}"), NOW);
        Map<SocialState.Place, List<SocialModel.Member>> roster = state.roster();
        assertEquals(2, roster.get(SocialState.Place.PROJECTS).size());
        assertEquals("the moderator before a plain member", KAAN, roster.get(SocialState.Place.PROJECTS).get(0).profile.id);
        assertEquals("the player looks at the app", ME, roster.get(SocialState.Place.LAUNCHER).get(0).profile.id);
        assertEquals(2, state.crewOnProjects().size());
        // A teammate who is not a friend leaves presence with the crew.
        state.setTeam(null, null);
        assertFalse(state.presence(KAAN).online());
        assertTrue(state.presence(AYSE).online());
    }
}
