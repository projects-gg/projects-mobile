package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

/** M19: the main menu's "Arkadaşlar" row says the most useful thing first. */
public class HomeLineTest {
    private static final Set<String> NONE = Collections.emptySet();
    private static final long NOW = SocialStateTest.NOW;

    @Test
    public void signedOutAndLoading() {
        SocialState state = new SocialState();
        assertEquals(HomeLine.Kind.SIGNED_OUT, HomeLine.of(false, false, state, NONE, true, NOW).kind);
        assertEquals(HomeLine.Kind.LOADING, HomeLine.of(true, false, state, NONE, true, NOW).kind);
        assertEquals(HomeLine.Kind.UNAVAILABLE, HomeLine.of(true, true, state, NONE, true, NOW).kind);
    }

    @Test
    public void aCallBeforeAnInviteBeforeTheCrewBeforeFriends() {
        SocialState state = SocialStateTest.signedIn(true);
        assertEquals(HomeLine.Kind.NOBODY_ONLINE, HomeLine.of(true, false, state, NONE, true, NOW).kind);
        state.apply(SocialStateTest.json("{\"t\":\"hello\",\"friends\":[{\"id\":\"" + SocialStateTest.AYSE
                + "\",\"state\":\"playing\",\"game\":{\"where\":\"projects\"}}],\"team\":[{\"id\":\"" + SocialStateTest.KAAN
                + "\",\"state\":\"playing\",\"game\":{\"where\":\"projects\"}}],\"invites\":[],\"calls\":[]}"), NOW);
        HomeLine crew = HomeLine.of(true, false, state, NONE, true, NOW);
        assertEquals("two crewmates on Projects", HomeLine.Kind.CREW_ON_PROJECTS, crew.kind);
        assertEquals(2, crew.count);
        assertEquals("without crews, friends", HomeLine.Kind.FRIENDS_ON_PROJECTS, HomeLine.of(true, false, state, NONE, false, NOW).kind);
        state.apply(SocialStateTest.json("{\"t\":\"invite\",\"invite\":{\"id\":\"60\",\"from\":\"" + SocialStateTest.AYSE
                + "\",\"at\":" + NOW + ",\"server\":{\"where\":\"projects\"},\"quiet\":false}}"), NOW);
        assertEquals(HomeLine.Kind.INVITE, HomeLine.of(true, false, state, NONE, true, NOW).kind);
        state.apply(SocialStateTest.json("{\"t\":\"call\",\"call\":{\"id\":\"c9\",\"from\":\"" + SocialStateTest.KAAN
                + "\",\"kind\":\"join\",\"at\":" + NOW + ",\"until\":" + (NOW + 600) + ",\"answers\":{}},\"quiet\":false,\"now\":" + NOW + "}"), NOW);
        HomeLine call = HomeLine.of(true, false, state, NONE, true, NOW);
        assertEquals(HomeLine.Kind.CALL, call.kind);
        assertEquals("Kaan", call.faces.get(0).name());
        state.answerLocally("c9", "soon");
        assertEquals("an answered call steps back", HomeLine.Kind.INVITE, HomeLine.of(true, false, state, NONE, true, NOW).kind);
        state.apply(SocialStateTest.json("{\"t\":\"request\",\"from\":" + SocialStateTest.profile("100000000000000005", "Can") + "}"), NOW);
        assertTrue("a request shows as the dot", HomeLine.of(true, false, state, NONE, true, NOW).pending);
    }

    @Test
    public void aRequestThenACrewInviteBeforeTheOnlineCount() {
        SocialState state = SocialStateTest.signedIn(true);
        assertEquals(HomeLine.Kind.NOBODY_ONLINE, HomeLine.of(true, false, state, NONE, true, NOW).kind);
        state.apply(SocialStateTest.json("{\"t\":\"team_invite\",\"invite\":{\"team\":{\"id\":\"9af2216431944e68\",\"name\":\"Gece\"},"
                + "\"from\":" + SocialStateTest.profile("100000000000000006", "Ece") + ",\"sentAt\":" + NOW + "}}"), NOW);
        HomeLine invite = HomeLine.of(true, false, state, NONE, true, NOW);
        assertEquals(HomeLine.Kind.CREW_INVITE, invite.kind);
        assertEquals("Gece", invite.teamInvite.team.name);
        assertEquals("Ece", invite.faces.get(0).name());
        assertEquals("without crews it does not show", HomeLine.Kind.NOBODY_ONLINE, HomeLine.of(true, false, state, NONE, false, NOW).kind);
        state.apply(SocialStateTest.json("{\"t\":\"request\",\"from\":" + SocialStateTest.profile("100000000000000005", "Can") + "}"), NOW);
        HomeLine request = HomeLine.of(true, false, state, NONE, true, NOW);
        assertEquals("a request comes first", HomeLine.Kind.REQUEST, request.kind);
        assertEquals("Can", request.faces.get(0).name());
        assertEquals(1, request.count);
    }

    @Test
    public void noFriendsYet() {
        SocialState state = new SocialState();
        state.applyFriends(SocialModel.FriendsData.read(SocialStateTest.json("{\"friends\":[],\"incoming\":[],\"outgoing\":[],\"blocked\":[],\"limits\":{}}")));
        assertEquals(HomeLine.Kind.NO_FRIENDS, HomeLine.of(true, false, state, NONE, true, NOW).kind);
    }
}
