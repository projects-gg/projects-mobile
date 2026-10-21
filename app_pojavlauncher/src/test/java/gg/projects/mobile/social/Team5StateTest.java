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
import java.util.HashSet;
import java.util.List;

/**
 * M21 (TEAM5): the join setting, join requests (the player's own and those the player may answer),
 * the invite link on the crew card, plans from the socket, their notices and errors, and the gates,
 * as services/social README "Joining a crew" and "Crew plans" describe them.
 */
public class Team5StateTest {
    static final String ME = "100000000000000011";
    static final String AYSE = "100000000000000012";
    static final String KAAN = "100000000000000013";
    static final String CAN = "100000000000000014";
    static final String GECE = "0a1b2c3d4e5f6a7b";
    static final String KALE = "1b2c3d4e5f6a7b8c";
    static final String YENI = "2c3d4e5f6a7b8c9d";
    static final long NOW = 1_792_521_000L;

    static JsonObject json(String text) {
        return Json.parseObject(text);
    }

    static String profile(String id, String name) {
        return "{\"id\":\"" + id + "\",\"username\":\"" + name.toLowerCase() + "\",\"displayName\":\"" + name + "\",\"avatar\":\"discord\"}";
    }

    private static String member(String id, String name, String role, boolean moderator) {
        return "{\"id\":\"" + id + "\",\"username\":\"" + name.toLowerCase() + "\",\"displayName\":\"" + name
                + "\",\"avatar\":\"discord\",\"role\":\"" + role + "\",\"moderator\":" + moderator + ",\"joinedAt\":1}";
    }

    /** Gece: Ayşe leads, the player is a moderator, Kaan a member; approval, a link, admit as given. */
    static String gece(String admit, String join, String link) {
        return "{\"id\":\"" + GECE + "\",\"name\":\"Gece Kartalları\",\"tag\":\"GK\",\"emblem\":\"moon\",\"color\":\"navy\","
                + "\"frame\":\"shield\",\"motto\":\"\",\"code\":\"TKM-AAAA-BBBB\",\"createdOn\":\"2026-10-01\",\"members\":["
                + member(AYSE, "Ayşe", "founder", false) + "," + member(ME, "Arda", "member", true) + ","
                + member(KAAN, "Kaan", "member", false) + "],"
                + "\"permissions\":{\"invite\":\"members\",\"call\":\"moderators\",\"kick\":\"moderators\",\"edit\":\"leader\","
                + "\"admit\":\"" + admit + "\"},\"textLockedUntil\":null,\"join\":\"" + join + "\",\"link\":"
                + (link == null ? "null" : "\"" + link + "\"") + "}";
    }

    /** Kale: the player leads it alone; open, no link. */
    static String kale() {
        return "{\"id\":\"" + KALE + "\",\"name\":\"Kale\",\"tag\":\"KL\",\"emblem\":\"castle\",\"color\":\"gold\","
                + "\"frame\":\"cut\",\"motto\":\"\",\"code\":\"TKM-CCCC-DDDD\",\"members\":[" + member(ME, "Arda", "founder", false) + "],"
                + "\"permissions\":{\"invite\":\"members\",\"call\":\"members\",\"kick\":\"moderators\",\"edit\":\"leader\","
                + "\"admit\":\"moderators\"},\"textLockedUntil\":null,\"join\":\"open\",\"link\":null}";
    }

    static String friends(String geceCrew, String requests, String teamRequests) {
        return "{\"friends\":[" + profile(AYSE, "Ayşe") + "],\"incoming\":[],\"outgoing\":[],\"blocked\":[],"
                + "\"limits\":{\"friends\":200,\"teamMembers\":30,\"teamModerators\":4},"
                + "\"team\":" + geceCrew + ",\"teamInvited\":[],"
                + "\"crews\":[{\"team\":" + geceCrew + ",\"invited\":[],\"notifications\":{\"muted\":false,\"muteUntil\":null},"
                + "\"requests\":" + requests + "},"
                + "{\"team\":" + kale() + ",\"invited\":[],\"notifications\":{\"muted\":true,\"muteUntil\":null},\"requests\":[]}],"
                + "\"crewLimit\":5,\"teamInvites\":[],\"teamRequests\":" + teamRequests + ","
                + "\"teamFeatures\":[\"look\",\"calls\",\"roles\",\"mute\",\"multi\",\"callServer\",\"requests\",\"link\",\"plans\"],"
                + "\"teamNotifications\":{\"calls\":true,\"muted\":false,\"muteUntil\":null},\"features\":[\"push\",\"chat\"]}";
    }

    static String request(String id, String name, long sentAt) {
        return "{\"from\":" + profile(id, name) + ",\"sentAt\":" + sentAt + "}";
    }

    static String myRequest(String teamId, String name, long sentAt) {
        return "{\"team\":{\"id\":\"" + teamId + "\",\"name\":\"" + name + "\",\"tag\":\"YN\",\"emblem\":\"star\",\"color\":\"teal\","
                + "\"frame\":\"hex\",\"motto\":\"\",\"members\":4,\"join\":\"approval\"},\"sentAt\":" + sentAt + "}";
    }

    static SocialState state(String admit) {
        SocialState s = new SocialState();
        s.setMe(SocialModel.Me.read(json("{\"id\":\"" + ME + "\",\"username\":\"arda\",\"displayName\":\"Arda\",\"avatar\":\"discord\","
                + "\"friendCode\":\"PRJ-AAAA-BBBB\",\"status\":\"online\",\"privacy\":{},\"limited\":null,\"notices\":[]}")));
        s.applyFriends(SocialModel.FriendsData.read(json(friends(gece(admit, "approval", "ABCD-EFGH-JKMN"),
                "[" + request(CAN, "Can", NOW - 600) + "]", "[" + myRequest(YENI, "Yeni Ufuklar", NOW - 3600) + "]"))));
        return s;
    }

    static String plan(String id, String crew, String by, long at, String answers) {
        return "{\"id\":\"" + id + "\",\"crew\":\"" + crew + "\",\"by\":\"" + by + "\",\"at\":" + at + ",\"until\":" + (at + 7200)
                + ",\"createdAt\":" + (NOW - 60) + ",\"answers\":" + answers + "}";
    }

    @Test
    public void theCrewCardCarriesTheJoinSettingTheLinkAndWhoAnswers() {
        SocialState s = state("moderators");
        SocialModel.Team gece = s.crew(GECE).team;
        assertTrue(gece.approval());
        assertEquals("ABCD-EFGH-JKMN", gece.link);
        assertEquals("moderators", gece.permissions.admit);
        SocialModel.Team kale = s.crew(KALE).team;
        assertFalse(kale.approval());
        assertNull(kale.link);
        assertTrue("a moderator answers while admit is moderators", s.mayAdmit(GECE));
        assertTrue("the leader always", s.mayAdmit(KALE));
        assertEquals(1, s.requests(GECE, NOW).size());
        assertEquals("Can", s.requests(GECE, NOW).get(0).from.name());
        assertEquals(1, s.requestCount(NOW));
        assertEquals(CAN, s.newestRequest(NOW).from.id);
        assertEquals(GECE, s.crewOfRequest(s.newestRequest(NOW), NOW).id());
        // A muted crew's requests wait on its page; the home line leaves them out (the desktop's friends button).
        s.setCrewMute(GECE, new SocialModel.TeamNotifications(true, true, null));
        assertEquals(0, s.requestCount(NOW));
        assertNull(s.newestRequest(NOW));
        assertEquals(1, s.requests(GECE, NOW).size());
        s.setCrewMute(GECE, SocialModel.TeamNotifications.DEFAULT);
        assertEquals(1, s.requestCount(NOW));
        // The player's own request to another crew.
        assertEquals(1, s.myRequests(NOW).size());
        assertEquals("Yeni Ufuklar", s.myRequest(YENI, NOW).team.name);
        assertTrue(s.myRequest(YENI, NOW).team.approval());
        // A week later both are gone (nothing is sent then).
        assertTrue(s.requests(GECE, NOW + 7 * 86_400).isEmpty());
        assertTrue(s.myRequests(NOW + 7 * 86_400).isEmpty());
    }

    @Test
    public void admitForTheLeaderAloneHidesTheRequestsFromModerators() {
        SocialState s = state("leader");
        assertFalse(s.mayAdmit(GECE));
        assertTrue(s.requests(GECE, NOW).isEmpty());
        assertEquals(0, s.requestCount(NOW));
        assertNull(s.newestRequest(NOW));
        // An unknown level reads as the leader's; "members" is never given (moderators then).
        SocialModel.Permissions odd = SocialModel.Permissions.read(json("{\"admit\":\"everyone\"}"));
        assertEquals("leader", odd.admit);
        assertEquals("moderators", SocialModel.Permissions.read(json("{\"admit\":\"members\"}")).admit);
        assertEquals("moderators", SocialModel.Permissions.read(json("{}")).admit);
    }

    @Test
    public void teamRequestsReplaceTheListAndANewOneIsAnnounced() {
        SocialState s = state("moderators");
        List<SocialState.Notice> notices = s.apply(json("{\"t\":\"team_requests\",\"crew\":\"" + GECE + "\",\"requests\":["
                + request(CAN, "Can", NOW - 600) + "," + request(KAAN.replace("13", "15"), "Deniz", NOW) + "],\"added\":\""
                + KAAN.replace("13", "15") + "\"}"), NOW);
        assertEquals(1, notices.size());
        assertEquals(SocialState.Notice.Kind.JOIN_REQUEST, notices.get(0).kind);
        assertEquals("Deniz", notices.get(0).who.name());
        assertEquals(GECE, notices.get(0).crewId);
        assertEquals("Gece Kartalları", notices.get(0).teamName);
        assertEquals(2, s.requests(GECE, NOW).size());
        // The same list again announces nothing; an empty one clears it.
        assertTrue(s.apply(json("{\"t\":\"team_requests\",\"crew\":\"" + GECE + "\",\"requests\":[" + request(CAN, "Can", NOW - 600)
                + "],\"added\":\"" + CAN + "\"}"), NOW).isEmpty());
        s.apply(json("{\"t\":\"team_requests\",\"crew\":\"" + GECE + "\",\"requests\":[]}"), NOW);
        assertTrue(s.requests(GECE, NOW).isEmpty());
    }

    @Test
    public void aCardThatTakesTheRightAwayDropsTheList() {
        SocialState s = state("moderators");
        assertEquals(1, s.requests(GECE, NOW).size());
        s.apply(json("{\"t\":\"team\",\"crew\":\"" + GECE + "\",\"team\":" + gece("leader", "approval", "ABCD-EFGH-JKMN") + ",\"by\":\"" + AYSE + "\"}"), NOW);
        assertTrue(s.crew(GECE).requests.isEmpty());
        assertFalse(s.mayAdmit(GECE));
    }

    @Test
    public void answeringOneTakesItAwayHere() {
        SocialState s = state("moderators");
        s.removeCrewRequest(GECE, CAN);
        assertTrue(s.requests(GECE, NOW).isEmpty());
        assertEquals(0, s.requestCount(NOW));
    }

    @Test
    public void aRequestThatIsAcceptedBringsItsCrewAndIsAnnounced() {
        SocialState s = state("moderators");
        String yeni = "{\"id\":\"" + YENI + "\",\"name\":\"Yeni Ufuklar\",\"tag\":\"YN\",\"emblem\":\"star\",\"color\":\"teal\",\"frame\":\"hex\","
                + "\"motto\":\"\",\"code\":\"TKM-EEEE-FFFF\",\"members\":[" + member(KAAN, "Kaan", "founder", false) + ","
                + member(ME, "Arda", "member", false) + "],\"permissions\":{},\"join\":\"approval\",\"link\":null}";
        List<SocialState.Notice> notices = s.apply(json("{\"t\":\"team\",\"crew\":\"" + YENI + "\",\"team\":" + yeni + ",\"by\":\"" + KAAN + "\"}"), NOW);
        assertEquals(1, notices.size());
        assertEquals(SocialState.Notice.Kind.REQUEST_ACCEPTED, notices.get(0).kind);
        assertEquals("Yeni Ufuklar", notices.get(0).teamName);
        assertNotNull(s.crew(YENI));
        assertEquals("the crew comes last", YENI, s.crewIds().get(2));
        assertTrue("the request is no longer waiting", s.myRequests(NOW).isEmpty());
    }

    @Test
    public void theOwnRequestsFollowTheAnswersHere() {
        SocialState s = state("moderators");
        s.addMyRequest(SocialModel.MyRequest.read(json(myRequest(KALE.replace('1', '3'), "Başka", NOW))));
        assertEquals("newest first", "Başka", s.myRequests(NOW).get(0).team.name);
        s.removeMyRequest(YENI);
        assertEquals(1, s.myRequests(NOW).size());
        // A join that answers 200 for a crew the player asked to join takes its request away.
        s.addMyRequest(SocialModel.MyRequest.read(json(myRequest(YENI, "Yeni Ufuklar", NOW))));
        s.upsertCrew(SocialModel.Team.read(json("{\"id\":\"" + YENI + "\",\"name\":\"Yeni Ufuklar\",\"members\":[" + member(ME, "Arda", "member", false) + "]}")), null);
        assertNull(s.myRequest(YENI, NOW));
    }

    @Test
    public void theLinkChangesWithItsOwnAnswers() {
        SocialState s = state("moderators");
        s.setCrewLink(GECE, "QQQQ-RRRR-SSSS");
        assertEquals("QQQQ-RRRR-SSSS", s.crew(GECE).team.link);
        assertEquals("the rest of the card stays", 3, s.crew(GECE).team.members.size());
        s.setCrewLink(GECE, null);
        assertNull(s.crew(GECE).team.link);
        // The service's link reads in any case and shape; anything else is no link.
        assertEquals("ABCD-EFGH-JKMN", SocialModel.Team.read(json(gece("moderators", "open", "abcdefghjkmn"))).link);
        assertNull(SocialModel.Team.read(json(gece("moderators", "open", "nope"))).link);
        // An unknown join value reads as approval (the careful side); none as open.
        assertTrue(SocialModel.TeamSummary.read(json("{\"id\":\"" + YENI + "\",\"name\":\"X\",\"join\":\"closed\"}")).approval());
        assertFalse(SocialModel.TeamSummary.read(json("{\"id\":\"" + YENI + "\",\"name\":\"X\"}")).approval());
    }

    @Test
    public void plansComeFromHelloAndFollowTheSocket() {
        SocialState s = state("moderators");
        assertFalse(s.plansKnown());
        long at = NOW + 3600;
        s.apply(json("{\"t\":\"hello\",\"now\":" + NOW + ",\"caps\":[\"crews\",\"plans\"],\"friends\":[],\"team\":[],\"invites\":[],\"calls\":[],"
                + "\"plans\":[" + plan("aaaaaaaaaaaaaaa1", GECE, AYSE, at, "{\"" + AYSE + "\":\"yes\",\"" + ME + "\":\"maybe\"}") + ","
                + plan("aaaaaaaaaaaaaaa2", GECE, KAAN, NOW - 1800, "{\"" + KAAN + "\":\"yes\"}") + "]}"), NOW);
        assertTrue(s.plansKnown());
        List<SocialModel.Plan> gece = s.plans(NOW, GECE);
        assertEquals("the one under way first", "aaaaaaaaaaaaaaa2", gece.get(0).id);
        assertEquals(2, gece.size());
        assertEquals("only the one ahead counts", 1, s.upcomingPlans(NOW, GECE));
        assertEquals("maybe", s.myPlanAnswer("aaaaaaaaaaaaaaa1"));
        assertTrue(gece.get(0).underWay(s.hubNow(NOW)));
        // A new plan, not quiet, from a crewmate: told; quiet or the player's own: not.
        List<SocialState.Notice> notices = s.apply(json("{\"t\":\"plan\",\"plan\":" + plan("aaaaaaaaaaaaaaa3", KALE, AYSE, at + 900, "{}")
                + ",\"quiet\":false}"), NOW);
        assertEquals(1, notices.size());
        assertEquals(SocialState.Notice.Kind.PLAN, notices.get(0).kind);
        assertEquals("Ayşe", notices.get(0).who.name());
        assertEquals("Kale", notices.get(0).teamName);
        assertTrue(s.apply(json("{\"t\":\"plan\",\"plan\":" + plan("aaaaaaaaaaaaaaa4", KALE, AYSE, at + 1800, "{}") + ",\"quiet\":true}"), NOW).isEmpty());
        assertTrue(s.apply(json("{\"t\":\"plan\",\"plan\":" + plan("aaaaaaaaaaaaaaa5", KALE, ME, at + 2700, "{}") + ",\"quiet\":false}"), NOW).isEmpty());
        // A changed one again (answers) replaces it quietly.
        assertTrue(s.apply(json("{\"t\":\"plan\",\"plan\":" + plan("aaaaaaaaaaaaaaa3", KALE, AYSE, at + 900, "{\"" + KAAN + "\":\"no\"}")
                + ",\"quiet\":false}"), NOW).isEmpty());
        assertEquals(1, s.plan("aaaaaaaaaaaaaaa3").count("no"));
        // "plans" replaces one crew's plans, also with none.
        s.apply(json("{\"t\":\"plans\",\"crew\":\"" + KALE + "\",\"plans\":[]}"), NOW);
        assertTrue(s.plans(NOW, KALE).isEmpty());
        assertEquals(2, s.plans(NOW, GECE).size());
        // "plan_removed": gone, and told (reminders follow).
        notices = s.apply(json("{\"t\":\"plan_removed\",\"plan\":\"aaaaaaaaaaaaaaa1\",\"crew\":\"" + GECE + "\",\"by\":\"" + AYSE + "\"}"), NOW);
        assertEquals(SocialState.Notice.Kind.PLAN_REMOVED, notices.get(0).kind);
        assertEquals("aaaaaaaaaaaaaaa1", notices.get(0).planId);
        assertNull(s.plan("aaaaaaaaaaaaaaa1"));
        // Over two hours after its time by the hub's clock: hidden, nothing came.
        assertTrue(s.plans(NOW + 5400 + 1, GECE).isEmpty());
        // Leaving the crew (team null) takes its plans.
        s.apply(json("{\"t\":\"team\",\"crew\":\"" + GECE + "\",\"team\":null,\"reason\":\"left\",\"by\":\"" + ME + "\"}"), NOW);
        assertNull(s.plan("aaaaaaaaaaaaaaa2"));
        // A hello without plans (a service without them) keeps none and is not known.
        s.apply(json("{\"t\":\"hello\",\"now\":" + NOW + ",\"caps\":[],\"friends\":[],\"team\":[],\"invites\":[],\"calls\":[]}"), NOW);
        assertFalse(s.plansKnown());
        assertTrue(s.plans(NOW).isEmpty());
    }

    @Test
    public void whoMayPlanAndCancel() {
        SocialState s = state("moderators");
        assertTrue("a moderator calls Gece (call: moderators)", s.mayPlan(GECE));
        assertTrue(s.mayPlan(KALE));
        assertFalse(s.mayPlan(YENI));
        SocialModel.Plan byKaan = SocialModel.Plan.read(json(plan("bbbbbbbbbbbbbbb1", GECE, KAAN, NOW + 900, "{}")));
        assertTrue("a moderator cancels anyone's", s.mayCancel(byKaan));
        SocialState member = state("moderators");
        member.applyFriends(SocialModel.FriendsData.read(json(friends(gece("moderators", "open", null).replace("\"moderator\":true", "\"moderator\":false"), "[]", "[]"))));
        assertFalse(member.mayPlan(GECE));
        assertFalse(member.mayCancel(byKaan));
        assertTrue("one's own", member.mayCancel(SocialModel.Plan.read(json(plan("bbbbbbbbbbbbbbb2", GECE, ME, NOW + 900, "{}")))));
    }

    @Test
    public void anAnswerShowsAtOnceAndComesBackWhenRefused() {
        SocialState s = state("moderators");
        s.apply(json("{\"t\":\"hello\",\"now\":" + NOW + ",\"caps\":[\"plans\"],\"friends\":[],\"team\":[],\"invites\":[],\"calls\":[],"
                + "\"plans\":[" + plan("ccccccccccccccc1", GECE, AYSE, NOW + 3600, "{\"" + AYSE + "\":\"yes\"}") + "]}"), NOW);
        assertNull(s.myPlanAnswer("ccccccccccccccc1"));
        s.answerPlanLocally("ccccccccccccccc1", "yes");
        assertEquals("yes", s.myPlanAnswer("ccccccccccccccc1"));
        assertEquals(2, s.plan("ccccccccccccccc1").count("yes"));
        s.restorePlanAnswer("ccccccccccccccc1", null);
        assertNull(s.myPlanAnswer("ccccccccccccccc1"));
        // The soon plan of the home line: only one the player is coming to (or maybe).
        assertNull(s.soonPlan(NOW, 900));
        s.answerPlanLocally("ccccccccccccccc1", "maybe");
        assertNull("an hour ahead is not soon", s.soonPlan(NOW, 900));
        assertEquals("ccccccccccccccc1", s.soonPlan(NOW + 2800, 900).id);
        assertEquals("under way", "ccccccccccccccc1", s.soonPlan(NOW + 4000, 900).id);
        s.answerPlanLocally("ccccccccccccccc1", "no");
        assertNull(s.soonPlan(NOW + 4000, 900));
    }

    @Test
    public void aPlanReadsLeniently() {
        assertNull(SocialModel.Plan.read(json("{\"id\":\"x\",\"crew\":\"" + GECE + "\",\"by\":\"nobody\",\"at\":1}")));
        SocialModel.Plan p = SocialModel.Plan.read(json("{\"id\":\"ddddddddddddddd1\",\"crew\":\"" + GECE + "\",\"by\":\"" + AYSE
                + "\",\"at\":" + NOW + ",\"answers\":{\"" + KAAN + "\":\"soon\",\"" + ME + "\":\"no\",\"x\":\"yes\"}}"));
        assertNotNull(p);
        assertEquals("until defaults to two hours after", NOW + 7200, p.until);
        assertEquals("only known answers of user ids", Collections.singletonMap(ME, "no"), p.answers);
    }

    @Test
    public void newErrorsReadAsTheirKinds() {
        assertEquals(SocialProblem.Kind.APPROVAL_REQUIRED, SocialProblem.of(409, "{\"error\":{\"code\":\"team_approval_required\"}}", null, false).kind);
        assertEquals(SocialProblem.Kind.JOIN_REQUEST_GONE, SocialProblem.of(404, "{\"error\":{\"code\":\"team_request_not_found\"}}", null, false).kind);
        assertEquals(SocialProblem.Kind.CREW_REQUESTS_FULL, SocialProblem.of(422, "{\"error\":{\"code\":\"limit_team_requests\"}}", null, false).kind);
        assertEquals(SocialProblem.Kind.TOO_MANY_JOIN_REQUESTS,
                SocialProblem.of(422, "{\"error\":{\"code\":\"limit_outgoing_team_requests\"}}", null, false).kind);
        SocialProblem link = SocialProblem.of(403, "{\"error\":{\"code\":\"account_limited\",\"item\":\"crew_link\",\"until\":" + (NOW + 86_400) + "}}", null, false);
        assertEquals(SocialProblem.Kind.ACCOUNT_LIMITED, link.kind);
        assertEquals(SocialModel.Limited.CREW_LINK, link.item);
        SocialProblem exists = SocialProblem.ofSocket(json("{\"t\":\"error\",\"rid\":3,\"code\":\"plan_exists\",\"plan\":\"eeeeeeeeeeeeeee1\"}"));
        assertEquals(SocialProblem.Kind.PLAN_EXISTS, exists.kind);
        assertEquals("eeeeeeeeeeeeeee1", exists.plan);
        SocialProblem limited = SocialProblem.ofSocket(json("{\"t\":\"error\",\"code\":\"account_limited\",\"item\":\"crew_plans\",\"until\":" + NOW + "}"));
        assertEquals(SocialProblem.Kind.ACCOUNT_LIMITED, limited.kind);
        assertEquals(SocialModel.Limited.CREW_PLANS, limited.item);
        assertEquals(Long.valueOf(NOW), limited.until);
        assertEquals(SocialProblem.Kind.PLAN_TIME, SocialProblem.ofSocket(json("{\"t\":\"error\",\"code\":\"plan_time\"}")).kind);
        assertEquals(SocialProblem.Kind.PLANS_FULL, SocialProblem.ofSocket(json("{\"t\":\"error\",\"code\":\"limit_team_plans\"}")).kind);
        assertEquals(SocialProblem.Kind.PLAN_GONE, SocialProblem.ofSocket(json("{\"t\":\"error\",\"code\":\"plan_not_found\"}")).kind);
        SocialProblem changes = SocialProblem.ofSocket(json("{\"t\":\"error\",\"code\":\"rate_limited\",\"retryAfter\":5000}"));
        assertEquals(SocialProblem.Kind.RATE_LIMITED, changes.kind);
        assertEquals(5000, changes.retryAfter);
    }

    @Test
    public void theGatesFollowTeamFeatures() {
        SocialState s = state("moderators");
        SocialGate gate = SocialGate.of(true, true, true, 30, s.teamFeatures(), s.features(), true, true);
        assertTrue(gate.requests && gate.link && gate.plans);
        SocialGate older = SocialGate.of(true, true, true, 30, new HashSet<>(Arrays.asList("look", "calls", "multi")), s.features(), true, true);
        assertFalse(older.requests || older.link || older.plans);
        SocialGate noCrews = SocialGate.of(true, false, true, 30, s.teamFeatures(), s.features(), true, true);
        assertFalse(noCrews.plans);
    }

    @Test
    public void aLimitedAccountsNewItemsRead() {
        SocialModel.Me me = SocialModel.Me.read(json("{\"id\":\"" + ME + "\",\"username\":\"arda\",\"avatar\":\"discord\",\"limited\":{\"until\":"
                + (NOW + 3 * 86_400) + ",\"items\":[{\"key\":\"crew_found\"},{\"key\":\"crew_link\"},{\"key\":\"crew_plans\"}]},\"notices\":[]}"));
        assertTrue(me.limited.has(SocialModel.Limited.CREW_LINK));
        assertTrue(me.limited.has(SocialModel.Limited.CREW_PLANS));
        assertEquals(3, SocialModel.Limited.daysLeft(me.limited.until, NOW));
    }

    @Test
    public void theHomeLineSaysAPlanSoonAndARequestToAnswer() {
        SocialState s = state("moderators");
        java.util.Set<String> none = Collections.emptySet();
        HomeLine request = HomeLine.of(true, false, s, none, true, NOW);
        assertEquals(HomeLine.Kind.JOIN_REQUEST, request.kind);
        assertEquals("Can", request.joinRequest.from.name());
        assertEquals(GECE, request.crew.id());
        assertTrue("a request to answer is the gold dot", request.pending);
        assertEquals("without crews it does not show", HomeLine.Kind.NOBODY_ONLINE, HomeLine.of(true, false, s, none, false, NOW).kind);
        s.apply(json("{\"t\":\"hello\",\"now\":" + NOW + ",\"caps\":[\"plans\"],\"friends\":[],\"team\":[],\"invites\":[],\"calls\":[],"
                + "\"plans\":[" + plan("ggggggggggggggg1", GECE, AYSE, NOW + 600, "{\"" + AYSE + "\":\"yes\",\"" + ME + "\":\"yes\",\""
                + KAAN + "\":\"maybe\"}") + "]}"), NOW);
        HomeLine plan = HomeLine.of(true, false, s, none, true, NOW);
        assertEquals(HomeLine.Kind.PLAN, plan.kind);
        assertEquals("ggggggggggggggg1", plan.plan.id);
        assertEquals("who is coming, the player left out", 1, plan.count);
        assertEquals("Ayşe", plan.faces.get(0).name());
        assertEquals(GECE, plan.crew.id());
        // A plan an hour away waits; under way it shows.
        s.answerPlanLocally("ggggggggggggggg1", "no");
        assertEquals(HomeLine.Kind.JOIN_REQUEST, HomeLine.of(true, false, s, none, true, NOW).kind);
    }

    @Test
    public void clearForgetsTeam5State() {
        SocialState s = state("moderators");
        s.apply(json("{\"t\":\"hello\",\"now\":" + NOW + ",\"caps\":[\"plans\"],\"friends\":[],\"team\":[],\"invites\":[],\"calls\":[],"
                + "\"plans\":[" + plan("fffffffffffffff1", GECE, AYSE, NOW + 3600, "{}") + "]}"), NOW);
        s.clear();
        assertFalse(s.plansKnown());
        assertTrue(s.plans(NOW).isEmpty());
        assertTrue(s.myRequests(NOW).isEmpty());
    }
}
