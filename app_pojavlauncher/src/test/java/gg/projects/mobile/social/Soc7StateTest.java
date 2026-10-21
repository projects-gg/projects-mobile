package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * M20 (SOC7): several crews (TEAM4), calls naming their crews and carrying their server (CALL1), the
 * chat state, moderators' notes and a restricted account (MOD1), a limited account (SPAM1), the new
 * error codes and pushes, the home line's message, and the gates.
 */
public class Soc7StateTest {
    private static final String A = "100000000000000011";
    private static final String B = "100000000000000012";
    private static final String C = "100000000000000013";
    private static final String K1 = "0a1b2c3d4e5f6a7b";
    private static final String K2 = "1b2c3d4e5f6a7b8c";
    private static final String K3 = "2c3d4e5f6a7b8c9d";
    private static final long NOW = 1_792_521_000L;

    private static String member(String id, String name, String role) {
        return "{\"id\":\"" + id + "\",\"username\":\"" + name + "\",\"displayName\":null,\"avatar\":\"discord\",\"role\":\"" + role
                + "\",\"moderator\":false,\"joinedAt\":1}";
    }

    private static String team(String id, String name, String tag, String founder, String... others) {
        StringBuilder members = new StringBuilder(member(founder, "u" + founder.substring(16), "founder"));
        for (String o : others) members.append(',').append(member(o, "u" + o.substring(16), "member"));
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"tag\":\"" + tag + "\",\"emblem\":\"sword\",\"color\":\"teal\","
                + "\"frame\":\"shield\",\"motto\":\"\",\"code\":\"TKM-AAAA-BBBB\",\"members\":[" + members + "],"
                + "\"permissions\":{\"invite\":\"members\",\"call\":\"members\",\"kick\":\"moderators\",\"edit\":\"leader\"},"
                + "\"textLockedUntil\":null}";
    }

    private static String friends() {
        return "{\"friends\":[{\"id\":\"" + B + "\",\"username\":\"ece\",\"avatar\":\"discord\",\"since\":\"2026-10-01\"},"
                + "{\"id\":\"" + C + "\",\"username\":\"kaan\",\"avatar\":\"discord\",\"since\":\"2026-10-02\"}],"
                + "\"incoming\":[],\"outgoing\":[],\"blocked\":[],\"limits\":{\"friends\":200,\"teamMembers\":30,\"teamModerators\":4},"
                + "\"team\":" + team(K2, "Gece", "GC", B, A) + ",\"teamInvited\":[],"
                + "\"crews\":[{\"team\":" + team(K2, "Gece", "GC", B, A) + ",\"invited\":[],\"notifications\":{\"muted\":false,\"muteUntil\":null}},"
                + "{\"team\":" + team(K1, "Kale", "KL", A, C) + ",\"invited\":[\"" + B + "\"],\"notifications\":{\"muted\":true,\"muteUntil\":" + (NOW + 3600) + "}},"
                + "{\"team\":" + team(K3, "Ece", "EC", B, A, C) + ",\"invited\":[],\"notifications\":{\"muted\":false,\"muteUntil\":null}}],"
                + "\"crewLimit\":3,\"teamInvites\":[],\"teamFeatures\":[\"look\",\"calls\",\"roles\",\"mute\",\"multi\",\"callServer\"],"
                + "\"teamNotifications\":{\"calls\":true,\"muted\":false,\"muteUntil\":null},\"features\":[\"push\",\"chat\"]}";
    }

    private static SocialState state() {
        SocialState s = new SocialState();
        s.setMe(SocialModel.Me.read(Json.parseObject("{\"id\":\"" + A + "\",\"username\":\"ayse\",\"displayName\":\"Ayşe\","
                + "\"avatar\":\"discord\",\"friendCode\":\"PRJ-AAAA-BBBB\",\"status\":\"online\",\"privacy\":{},"
                + "\"limited\":null,\"notices\":[]}")));
        s.applyFriends(SocialModel.FriendsData.read(Json.parseObject(friends())));
        return s;
    }

    @Test
    public void severalCrewsReadInThePlayersOrder() {
        SocialState s = state();
        assertEquals(Arrays.asList(K2, K1, K3), s.crewIds());
        assertEquals(K2, s.team().id);
        assertEquals(3, s.crewLimit());
        assertTrue(s.atCrewLimit());
        assertTrue(s.crew(K1).mute.mutedAt(NOW));
        assertTrue(s.crew(K1).invited.contains(B));
        assertTrue(s.teamInvited().isEmpty());
        assertEquals(Arrays.asList(K2, K3), crewIds(s.crewsOf(B)));
        assertNotNull(s.profile(C));
        SocialGate gate = SocialGate.of(true, true, true, s.limits().teamMembers, s.teamFeatures(), s.features(), true, s.me().moderation);
        assertTrue(gate.multi && gate.callServer && gate.chat && gate.reports && gate.push);
        // Without Studio's chat switch the service's chat stays hidden.
        assertFalse(SocialGate.of(true, true, false, 30, s.teamFeatures(), s.features(), true, true).chat);
    }

    private static List<String> crewIds(List<SocialModel.Crew> crews) {
        List<String> out = new java.util.ArrayList<>();
        for (SocialModel.Crew c : crews) out.add(c.id());
        return out;
    }

    @Test
    public void crewMessagesTouchOnlyTheirCrew() {
        SocialState s = state();
        s.apply(Json.parseObject("{\"t\":\"crew_order\",\"order\":[\"" + K3 + "\",\"" + K2 + "\",\"" + K1 + "\"]}"), NOW);
        assertEquals(Arrays.asList(K3, K2, K1), s.crewIds());
        s.apply(Json.parseObject("{\"t\":\"crew_notifications\",\"crew\":\"" + K2 + "\",\"notifications\":{\"muted\":true,\"muteUntil\":null}}"), NOW);
        assertTrue(s.crew(K2).mute.mutedAt(NOW));
        // A crew's new card replaces only that crew.
        s.apply(Json.parseObject("{\"t\":\"team\",\"crew\":\"" + K1 + "\",\"team\":" + team(K1, "Kale 2", "KL", A) + ",\"by\":\"" + A + "\"}"), NOW);
        assertEquals("Kale 2", s.crew(K1).team.name);
        assertEquals("Ece", s.crew(K3).team.name);
        // Out of one crew: the others stay; the notice names the crew that went.
        List<SocialState.Notice> notices = s.apply(Json.parseObject("{\"t\":\"team\",\"crew\":\"" + K3
                + "\",\"team\":null,\"reason\":\"disbanded\",\"by\":\"staff\"}"), NOW);
        assertEquals(SocialState.Notice.Kind.TEAM_GONE, notices.get(0).kind);
        assertTrue(notices.get(0).byStaff);
        assertEquals("Ece", notices.get(0).teamName);
        assertEquals(Arrays.asList(K2, K1), s.crewIds());
        assertFalse(s.atCrewLimit());
    }

    @Test
    public void callsNameTheirCrewsAndCarryTheirServer() {
        SocialState s = state();
        s.apply(Json.parseObject("{\"t\":\"hello\",\"v\":1,\"status\":\"online\",\"friends\":[],\"team\":[],\"invites\":[],\"calls\":[],"
                + "\"caps\":[\"callServer\",\"crews\",\"chat\"],\"chat\":{\"on\":true,\"mailbox\":true,\"mailboxBack\":null,\"perDay\":500,"
                + "\"mutedUntil\":null},\"now\":" + NOW + "}"), NOW);
        assertTrue(s.caps().contains("chat"));
        assertEquals(500, s.chat().perDay);
        String call = "{\"id\":\"c1\",\"from\":\"" + B + "\",\"kind\":\"join\",\"at\":" + NOW + ",\"until\":" + (NOW + 1200)
                + ",\"answers\":{},\"crews\":[\"" + K3 + "\",\"" + K2 + "\"],\"server\":{\"where\":\"projects\",\"host\":\"play.projects.gg\"}}";
        List<SocialState.Notice> notices = s.apply(Json.parseObject("{\"t\":\"call\",\"call\":" + call + ",\"quiet\":false,\"now\":" + NOW + "}"), NOW);
        assertEquals(SocialState.Notice.Kind.CALL, notices.get(0).kind);
        assertFalse(notices.get(0).moved);
        SocialModel.Call c = s.call("c1");
        assertEquals(SocialModel.Call.Place.PROJECTS, c.place());
        // In the player's order, not the call's.
        assertEquals(Arrays.asList(K2, K3), s.callCrewIds(c));
        assertEquals(1, s.calls(NOW, K3).size());
        assertTrue(s.calls(NOW, K1).isEmpty());
        assertTrue(s.callAudible(c, NOW));
        // The call moves to another server: told again (not quiet), and "Geliyorum" goes there.
        String moved = call.replace("\"where\":\"projects\",\"host\":\"play.projects.gg\"", "\"where\":\"other\",\"host\":\"mc.example.com\"");
        notices = s.apply(Json.parseObject("{\"t\":\"call\",\"call\":" + moved + ",\"quiet\":false,\"now\":" + NOW + "}"), NOW);
        assertTrue(notices.get(0).moved);
        assertEquals("mc.example.com", s.call("c1").otherHost());
        assertFalse(s.call("c1").onProjects());
        // Muting every crew it names keeps it quiet.
        s.setCrewMute(K2, new SocialModel.TeamNotifications(true, true, null));
        s.setCrewMute(K3, new SocialModel.TeamNotifications(true, true, null));
        assertFalse(s.callAudible(s.call("c1"), NOW));
        // Leaving one of its crews keeps the call for the other; leaving both ends it here.
        s.removeCrew(K3);
        assertEquals(Collections.singletonList(K2), s.call("c1").crews);
        s.removeCrew(K2);
        assertNull(s.call("c1"));
        // An older service's call: join means projects.gg, ready is "Hazır mısın?".
        SocialModel.Call ready = SocialModel.Call.read(Json.parseObject("{\"id\":\"r\",\"from\":\"" + B + "\",\"kind\":\"ready\",\"at\":1,\"until\":2}"));
        assertEquals(SocialModel.Call.Place.READY, ready.place());
        assertNull(ready.otherHost());
    }

    @Test
    public void theAccountsLimitsNoticesAndRestriction() {
        SocialModel.Me me = SocialModel.Me.read(Json.parseObject("{\"id\":\"" + A + "\",\"username\":\"n\",\"avatar\":\"discord\","
                + "\"limited\":{\"until\":" + (NOW + 3 * 86400 - 60) + ",\"items\":[{\"key\":\"crew_found\"},{\"key\":\"crew_invites\",\"perDay\":5},"
                + "{\"key\":\"chat_messages\",\"perDay\":100},{\"key\":\"something_new\"}]},"
                + "\"notices\":[{\"id\":\"w3\",\"kind\":\"warning\",\"at\":" + NOW + ",\"reason\":\"harassment\"}]}"));
        assertTrue(me.moderation);
        assertTrue(me.limited.active(NOW));
        assertTrue(me.limited.has(SocialModel.Limited.CREW_FOUND));
        assertEquals(5, me.limited.perDay(SocialModel.Limited.CREW_INVITES));
        assertEquals(3, SocialModel.Limited.daysLeft(me.limited.until, NOW));
        assertEquals(1, SocialModel.Limited.daysLeft(NOW + 60, NOW));
        assertEquals(0, SocialModel.Limited.daysLeft(NOW, NOW));
        assertEquals("w3", me.notices.get(0).id);
        assertTrue(me.withoutNotices(new HashSet<>(Collections.singletonList("w3"))).notices.isEmpty());
        // An older service's me: no limits, no notices, no reports.
        SocialModel.Me old = SocialModel.Me.read(Json.parseObject("{\"id\":\"" + A + "\",\"username\":\"n\"}"));
        assertNull(old.limited);
        assertFalse(old.moderation);

        SocialState s = state();
        List<SocialState.Notice> notices = s.apply(Json.parseObject("{\"t\":\"notice\",\"notice\":{\"id\":\"p1\",\"kind\":\"profile_reset\",\"at\":" + NOW + "}}"), NOW);
        assertEquals(SocialState.Notice.Kind.MOD_NOTICE, notices.get(0).kind);
        assertEquals(1, s.me().notices.size());
        assertTrue(s.apply(Json.parseObject("{\"t\":\"notice\",\"notice\":{\"id\":\"p1\",\"kind\":\"profile_reset\",\"at\":" + NOW + "}}"), NOW).isEmpty());
        s.apply(Json.parseObject("{\"t\":\"chat_state\",\"chat\":{\"on\":true,\"mailbox\":false,\"mailboxBack\":" + (NOW + 600)
                + ",\"perDay\":100,\"mutedUntil\":" + (NOW + 3600) + "}}"), NOW);
        assertTrue(s.chat().mutedAt(NOW));
        assertTrue(s.chat().liveOnlyAt(NOW));
        assertFalse(s.chat().liveOnlyAt(NOW + 600));
        notices = s.apply(Json.parseObject("{\"t\":\"bye\",\"reason\":\"restricted\",\"until\":null}"), NOW);
        assertEquals(SocialState.Notice.Kind.RESTRICTED, notices.get(0).kind);
        assertEquals(Long.valueOf(0), s.restrictedUntil());
    }

    @Test
    public void theNewErrorsSayWhatHappened() {
        SocialProblem limited = SocialProblem.of(403, "{\"error\":{\"code\":\"account_limited\",\"message\":\"x\",\"item\":\"crew_found\",\"until\":"
                + NOW + "}}", null, false);
        assertEquals(SocialProblem.Kind.ACCOUNT_LIMITED, limited.kind);
        assertEquals("crew_found", limited.item);
        assertEquals(Long.valueOf(NOW), limited.until);
        SocialProblem banned = SocialProblem.of(403, "{\"error\":{\"code\":\"account_restricted\",\"message\":\"x\",\"until\":null}}", null, false);
        assertTrue(banned.restricted());
        assertNull(banned.until);
        assertEquals(SocialProblem.Kind.CREW_LIMIT, SocialProblem.of(422, "{\"error\":{\"code\":\"limit_crews\"}}", null, false).kind);
        assertEquals(SocialProblem.Kind.CREWS_CHANGED, SocialProblem.of(409, "{\"error\":{\"code\":\"crews_changed\"}}", null, false).kind);
        assertEquals(SocialProblem.Kind.TEXT_LOCKED, SocialProblem.of(403, "{\"error\":{\"code\":\"team_text_locked\",\"until\":5}}", null, false).kind);
        assertEquals(SocialProblem.Kind.TARGET_GONE, SocialProblem.of(404, "{\"error\":{\"code\":\"target_not_found\"}}", null, false).kind);
        assertEquals(SocialProblem.Kind.SIGNUPS_CLOSED, SocialProblem.of(403, "{\"error\":{\"code\":\"signups_closed\",\"retryAfter\":60}}", null, false).kind);
        SocialProblem rejected = SocialProblem.ofSocket(Json.parseObject("{\"t\":\"error\",\"rid\":4,\"code\":\"chat_rejected\",\"reason\":\"link\"}"));
        assertEquals(SocialProblem.Kind.CHAT_REJECTED, rejected.kind);
        assertEquals("link", rejected.reason);
        assertEquals(SocialProblem.Kind.CALL_NEEDS_SERVER, SocialProblem.ofSocket(Json.parseObject("{\"code\":\"call_needs_server\"}")).kind);
        assertEquals(SocialProblem.Kind.NOT_IN_CREW, SocialProblem.ofSocket(Json.parseObject("{\"code\":\"team_not_found\"}")).kind);
        SocialProblem muted = SocialProblem.ofSocket(Json.parseObject("{\"code\":\"chat_muted\",\"until\":" + NOW + "}"));
        assertEquals(Long.valueOf(NOW), muted.until);
    }

    @Test
    public void aDirectMessagePushNeverCarriesTheText() {
        Map<String, String> data = new HashMap<>();
        data.put("v", "1");
        data.put("type", "message");
        data.put("conv", ChatConv.direct(A, B));
        data.put("sender", B);
        data.put("name", "Ece");
        data.put("at", String.valueOf(NOW));
        data.put("to", A);
        PushMessage push = PushMessage.parse(data);
        assertNotNull(push);
        assertEquals("message:" + ChatConv.direct(A, B), push.tag());
        java.util.Set<String> wanted = new HashSet<>(Arrays.asList(PushMessage.MESSAGE));
        PushMessage.Decision show = push.decide(A, NOW + 5, Collections.<String>emptySet(), wanted, true);
        assertEquals(PushMessage.Decision.Action.SHOW, show.action);
        assertFalse(show.silent);
        assertTrue(push.decide(A, NOW + 5, Collections.<String>emptySet(), wanted, true,
                Collections.singleton(ChatConv.direct(A, B))).silent);
        assertEquals(PushMessage.Decision.Action.DROP, push.decide(A, NOW, Collections.<String>emptySet(),
                Collections.<String>emptySet(), true).action);
        assertEquals(PushMessage.Decision.Action.DROP, push.decide(B, NOW, Collections.<String>emptySet(), wanted, true).action);
        // A sender who is not the other player of the conversation is no message of this app's.
        data.put("sender", C);
        assertNull(PushMessage.parse(data));
        data.put("sender", B);
        data.put("conv", ChatConv.crew(K1));
        assertNull(PushMessage.parse(data));
        // A call push says where it is and which crews it names.
        Map<String, String> call = new HashMap<>();
        call.put("v", "1");
        call.put("type", "call");
        call.put("call", "3f9c0a1b2c3d4e5f");
        call.put("caller", B);
        call.put("name", "Ece");
        call.put("kind", "ready");
        call.put("where", "other");
        call.put("host", "mc.example.com");
        call.put("crews", K1 + "," + K2 + ",bad id");
        call.put("at", String.valueOf(NOW));
        call.put("until", String.valueOf(NOW + 1200));
        call.put("to", A);
        PushMessage c = PushMessage.parse(call);
        assertEquals("other", c.where);
        assertEquals("mc.example.com", c.host);
        assertEquals(Arrays.asList(K1, K2), c.crews);
    }

    @Test
    public void theHomeLineSaysAFriendWrote() {
        SocialState s = state();
        HomeLine.Unread unread = new HomeLine.Unread(ChatConv.direct(A, B), B, 2, 1);
        HomeLine line = HomeLine.of(true, false, s, Collections.<String>emptySet(), true, NOW, unread);
        assertEquals(HomeLine.Kind.MESSAGE, line.kind);
        assertEquals(B, line.faces.get(0).id);
        assertTrue(line.severalCrews);
        // A call the player has not answered comes first.
        s.apply(Json.parseObject("{\"t\":\"call\",\"call\":{\"id\":\"c2\",\"from\":\"" + B + "\",\"kind\":\"join\",\"at\":" + NOW
                + ",\"until\":" + (NOW + 600) + ",\"answers\":{},\"crews\":[\"" + K3 + "\"]},\"quiet\":true,\"now\":" + NOW + "}"), NOW);
        line = HomeLine.of(true, false, s, Collections.<String>emptySet(), true, NOW, unread);
        assertEquals(HomeLine.Kind.CALL, line.kind);
        assertEquals(K3, line.crew.id());
        // A message from someone who is no longer a friend is not offered.
        SocialState other = state();
        other.removeFriend(B);
        assertFalse(HomeLine.of(true, false, other, Collections.<String>emptySet(), true, NOW, unread).kind == HomeLine.Kind.MESSAGE);
    }
}
