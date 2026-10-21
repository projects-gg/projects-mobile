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
 * M21: answers and socket frames recorded from the local friends service at projects-studio 5fc6a82
 * (TEAM5: the join setting, join requests by link and by code, their answers, the invite link and its
 * renewal when the crew opens, plans with answers and refusals, a limited account; m21
 * tools/capture5.mjs, throwaway players): the phone reads every one of them as the README says.
 */
public class Team5FixturesTest {
    private static final File DIR = new File("src/test/resources/social");
    private static final String AYSE = "100000000000000011";
    private static final String KAAN = "100000000000000013";
    private static final String DENIZ = "100000000000000014";
    private static final String CREW = "4fdb29ce4949654f";

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File(DIR, name).toPath()), StandardCharsets.UTF_8);
    }

    private static JsonObject json(String name) throws Exception {
        return Json.parseObject(read(name));
    }

    private static List<JsonObject> frames(String name) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        for (String line : read(name).split("\n")) if (!line.trim().isEmpty()) out.add(Json.parseObject(line));
        return out;
    }

    private static SocialModel.Me me(String id, String name) {
        return SocialModel.Me.read(Json.parseObject("{\"id\":\"" + id + "\",\"username\":\"" + name + "\",\"avatar\":\"discord\","
                + "\"friendCode\":\"PRJ-AAAA-BBBB\",\"status\":\"online\",\"privacy\":{},\"limited\":null,\"notices\":[]}"));
    }

    private static SocialState leader() throws Exception {
        SocialState state = new SocialState();
        state.setMe(me(AYSE, "ayse"));
        state.applyFriends(SocialModel.FriendsData.read(json("friends-leader-team5.json")));
        return state;
    }

    @Test
    public void theLeadersListHoldsTheCrewsSettingItsLinkAndBothRequests() throws Exception {
        SocialState state = leader();
        SocialModel.Team team = state.crew(CREW).team;
        assertTrue(team.approval());
        assertEquals("VRCD-VG9V-C2JF", team.link);
        assertEquals("moderators", team.permissions.admit);
        assertTrue(state.mayAdmit(CREW));
        long sentAt = state.requests(CREW, 0).get(0).sentAt;
        List<SocialModel.JoinRequest> requests = state.requests(CREW, sentAt + 60);
        assertEquals(2, requests.size());
        assertEquals(KAAN, requests.get(0).from.id);
        assertEquals("Kaan", requests.get(0).from.name());
        assertEquals(DENIZ, requests.get(1).from.id);
        SocialGate gate = SocialGate.of(true, true, true, state.limits().teamMembers, state.teamFeatures(), state.features(), true, true);
        assertTrue(gate.requests && gate.link && gate.plans);
    }

    @Test
    public void theRequesterSeesTheLookupTheRequestAndTheirList() throws Exception {
        JsonObject lookup = json("lookup-link-team5.json");
        SocialModel.TeamSummary team = SocialModel.TeamSummary.read(Json.obj(lookup, "team"));
        assertTrue(team.approval());
        assertEquals(1, team.memberCount);
        assertEquals("none", Json.str(lookup, "relation"));
        assertNull(Json.obj(lookup, "request"));
        JsonObject pending = json("lookup-link-pending-team5.json");
        assertTrue(Json.num(Json.obj(pending, "request"), "sentAt", 0) > 0);
        JsonObject asked = Json.obj(json("join-request-team5.json"), "body");
        SocialModel.MyRequest mine = SocialModel.MyRequest.read(Json.obj(asked, "request"));
        assertNotNull(mine);
        assertEquals(CREW, mine.team.id);
        assertTrue(mine.team.approval());
        SocialState state = new SocialState();
        state.setMe(me(KAAN, "kaan"));
        state.applyFriends(SocialModel.FriendsData.read(json("friends-requester-team5.json")));
        assertEquals(1, state.myRequests(mine.sentAt + 60).size());
        assertEquals("Gece Kartalları", state.myRequests(mine.sentAt + 60).get(0).team.name);
    }

    @Test
    public void theLinkAnswerAndTheAcceptance() throws Exception {
        JsonObject link = json("link-team5.json");
        assertEquals("VRCD-VG9V-C2JF", SocialCodes.normalizeInviteLink(Json.str(link, "link")));
        // The answer's address is the service's; the phone builds its own from its service address.
        assertEquals(Json.str(link, "url"), SocialCodes.inviteLinkUrl("http://127.0.0.1:8837/", "VRCD-VG9V-C2JF"));
        SocialModel.Team accepted = SocialModel.Team.read(Json.obj(json("accept-team5.json"), "team"));
        assertEquals(2, accepted.members.size());
        assertNotNull(accepted.member(KAAN));
        SocialModel.Team approval = SocialModel.Team.read(Json.obj(json("team-approval-team5.json"), "team"));
        assertTrue(approval.approval());
    }

    @Test
    public void theRefusalsReadAsTheirKinds() throws Exception {
        JsonObject errors = json("errors-team5.json");
        JsonObject old = Json.obj(errors, "approval_required");
        assertEquals(SocialProblem.Kind.APPROVAL_REQUIRED,
                SocialProblem.of((int) Json.num(old, "status", 0), Json.obj(old, "body").toString(), null, false).kind);
        JsonObject gone = Json.obj(errors, "request_gone");
        assertEquals(SocialProblem.Kind.JOIN_REQUEST_GONE,
                SocialProblem.of((int) Json.num(gone, "status", 0), Json.obj(gone, "body").toString(), null, false).kind);
        SocialProblem exists = SocialProblem.ofSocket(Json.obj(errors, "plan_exists"));
        assertEquals(SocialProblem.Kind.PLAN_EXISTS, exists.kind);
        assertNotNull(exists.plan);
        assertEquals(SocialProblem.Kind.PLAN_TIME, SocialProblem.ofSocket(Json.obj(errors, "plan_time")).kind);
        JsonObject link = Json.obj(errors, "limited_link");
        SocialProblem limitedLink = SocialProblem.of((int) Json.num(link, "status", 0), Json.obj(link, "body").toString(), null, false);
        assertEquals(SocialProblem.Kind.ACCOUNT_LIMITED, limitedLink.kind);
        assertEquals(SocialModel.Limited.CREW_LINK, limitedLink.item);
        assertNotNull(limitedLink.until);
        SocialProblem limitedPlan = SocialProblem.ofSocket(Json.obj(errors, "limited_plan"));
        assertEquals(SocialProblem.Kind.ACCOUNT_LIMITED, limitedPlan.kind);
        assertEquals(SocialModel.Limited.CREW_PLANS, limitedPlan.item);
        SocialModel.Me limited = SocialModel.Me.read(json("me-limited-team5.json"));
        assertTrue(limited.limited.has(SocialModel.Limited.CREW_LINK));
        assertTrue(limited.limited.has(SocialModel.Limited.CREW_PLANS));
    }

    @Test
    public void theLeadersSocketAnnouncesEachNewRequestAndRenewsTheLinkWhenTheCrewOpens() throws Exception {
        SocialState state = leader();
        // As before the first request: the list empty, then the socket's frames in their order.
        state.setCrewRequests(CREW, new ArrayList<SocialModel.JoinRequest>());
        List<String> announced = new ArrayList<>();
        long now = 0;
        for (JsonObject frame : frames("socket-leader-team5.jsonl")) {
            if ("hello".equals(Json.str(frame, "t"))) now = Json.num(frame, "now", 0);
            for (SocialState.Notice n : state.apply(frame, now)) {
                if (n.kind == SocialState.Notice.Kind.JOIN_REQUEST) announced.add(n.who.id);
            }
        }
        assertEquals(java.util.Arrays.asList(KAAN, DENIZ), announced);
        SocialModel.Team team = state.crew(CREW).team;
        assertFalse("open again", team.approval());
        assertEquals("a crew that opens gets a new link", "HNCP-P414-C234", team.link);
        assertEquals(3, team.members.size());
        assertTrue(state.requests(CREW, now).isEmpty());
        assertTrue(state.plansKnown());
        assertEquals("the plans frame after the new member came", 1, state.plans(now, CREW).size());
    }

    @Test
    public void theMembersSocketBringsThePlanItsAnswersAndItsEnd() throws Exception {
        SocialState state = new SocialState();
        state.setMe(me(KAAN, "kaan"));
        state.applyFriends(SocialModel.FriendsData.read(json("friends-requester-team5.json")));
        state.upsertCrew(SocialModel.Team.read(Json.obj(json("accept-team5.json"), "team")), null);
        List<SocialState.Notice.Kind> kinds = new ArrayList<>();
        String planId = null;
        long now = 0;
        for (JsonObject frame : frames("socket-member-team5.jsonl")) {
            if ("hello".equals(Json.str(frame, "t"))) now = Json.num(frame, "now", 0);
            for (SocialState.Notice n : state.apply(frame, now)) {
                kinds.add(n.kind);
                if (n.kind == SocialState.Notice.Kind.PLAN) {
                    planId = n.plan.id;
                    assertEquals(AYSE, n.who.id);
                    assertEquals(CREW, n.crewId);
                }
                if (n.kind == SocialState.Notice.Kind.PLAN_REMOVED) assertEquals(planId, n.planId);
            }
            if ("plan".equals(Json.str(frame, "t")) && state.plan(planId) != null && state.myPlanAnswer(planId) != null) {
                assertEquals("maybe", state.myPlanAnswer(planId));
            }
        }
        assertTrue(kinds.contains(SocialState.Notice.Kind.PLAN));
        assertTrue(kinds.contains(SocialState.Notice.Kind.PLAN_REMOVED));
        assertEquals("announced once: the answer's copy is quiet", 1, java.util.Collections.frequency(kinds, SocialState.Notice.Kind.PLAN));
        assertNull("deleted", state.plan(planId));
        assertTrue(state.plans(now, CREW).isEmpty());
    }

    @Test
    public void aHelloCarriesThePlansAndTheirAnswers() throws Exception {
        SocialState state = new SocialState();
        state.setMe(me(KAAN, "kaan"));
        state.applyFriends(SocialModel.FriendsData.read(json("friends-requester-team5.json")));
        state.upsertCrew(SocialModel.Team.read(Json.obj(json("accept-team5.json"), "team")), null);
        JsonObject hello = json("hello-plans-team5.json");
        long now = Json.num(hello, "now", 0);
        state.apply(hello, now);
        assertTrue(state.plansKnown());
        List<SocialModel.Plan> plans = state.plans(now, CREW);
        assertEquals(1, plans.size());
        SocialModel.Plan plan = plans.get(0);
        assertEquals(AYSE, plan.by);
        assertEquals(0, plan.at % SocialModel.Plan.STEP_SECONDS);
        assertEquals(plan.at + SocialModel.Plan.AFTER_SECONDS, plan.until);
        assertEquals("maybe", state.myPlanAnswer(plan.id));
        assertEquals("yes", plan.answers.get(AYSE));
        assertTrue(plan.upcoming(now));
        assertEquals(1, state.upcomingPlans(now, CREW));
    }
}
