package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FIX2 (the owner's feedback of 22 October): a plan's note (its rules, the plan frames and the note's
 * change, who may write or clear it, the report from a plan card), the menus every player, crew and
 * plan row shares, and who answered a plan or a call, as services/social README "Crew plans",
 * "Crewmates may add each other" and "Reports" describe them.
 */
public class Fix2Test {
    private static final String ME = Team5StateTest.ME;
    private static final String AYSE = Team5StateTest.AYSE;
    private static final String KAAN = Team5StateTest.KAAN;
    private static final String GECE = Team5StateTest.GECE;
    private static final long NOW = Team5StateTest.NOW;

    // ---- the note's text ----

    @Test
    public void aNoteIsOneCleanLineOfAtMostEightyCharacters() {
        assertEquals("Bedwars, sesli gelin", PlanNote.clean("  Bedwars,\n  sesli\tgelin  "));
        assertEquals("", PlanNote.clean("   \n\t "));
        assertEquals("", PlanNote.clean(null));
        assertNull(PlanNote.of("   "));
        assertEquals("21:00 Bedwars", PlanNote.of(" 21:00   Bedwars "));
        assertEquals(PlanNote.Problem.NONE, PlanNote.check(""));
        assertEquals(PlanNote.Problem.NONE, PlanNote.check("play.projects.gg'de buluşalım"));
        assertEquals(PlanNote.Problem.LINK, PlanNote.check("gel discord.gg/abc"));
        assertEquals(PlanNote.Problem.LINK, PlanNote.check("mc.hypixel.net"));
        assertEquals(PlanNote.Problem.PHONE, PlanNote.check("ara 0532 123 45 67"));
        StringBuilder eighty = new StringBuilder();
        for (int i = 0; i < 80; i++) eighty.append('a');
        assertEquals(PlanNote.Problem.NONE, PlanNote.check(eighty.toString()));
        assertEquals(PlanNote.Problem.TOO_LONG, PlanNote.check(eighty + "b"));
        // Code points, as the service counts: 80 emoji are 80 characters.
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 80; i++) emoji.appendCodePoint(0x1F525);
        assertEquals(PlanNote.Problem.NONE, PlanNote.check(emoji.toString()));
        assertEquals(PlanNote.Problem.LINK, PlanNote.ofReason("link"));
        assertEquals(PlanNote.Problem.NONE, PlanNote.ofReason(null));
    }

    // ---- plans with notes ----

    private static String plan(String id, String by, long at, String answers, String note) {
        return "{\"id\":\"" + id + "\",\"crew\":\"" + GECE + "\",\"by\":\"" + by + "\",\"at\":" + at + ",\"until\":" + (at + 7200)
                + ",\"createdAt\":" + (NOW - 100) + ",\"answers\":" + answers + (note == null ? "" : ",\"note\":" + note) + "}";
    }

    private static SocialState state(String teamFeatures) {
        SocialState s = new SocialState();
        s.setMe(SocialModel.Me.read(Team5StateTest.json(Team5StateTest.profile(ME, "Arda"))));
        String friends = Team5StateTest.friends(Team5StateTest.gece("moderators", "open", null), "[]", "[]")
                .replace("\"plans\"]", teamFeatures);
        s.applyFriends(SocialModel.FriendsData.read(Team5StateTest.json(friends)));
        s.apply(Team5StateTest.json("{\"t\":\"hello\",\"now\":" + NOW + ",\"caps\":[\"plans\"],\"plans\":["
                + plan("aaaaaaaaaaaaaaaa", AYSE, NOW + 3600, "{\"" + AYSE + "\":\"yes\"}", "\"  Bedwars,  sesli gelin \"") + ","
                + plan("bbbbbbbbbbbbbbbb", ME, NOW + 7200, "{\"" + ME + "\":\"yes\"}", null) + "]}"), NOW);
        return s;
    }

    @Test
    public void plansCarryTheirNoteAndAChangeReplacesThem() {
        SocialState s = state("\"plans\",\"planNotes\"]");
        SocialModel.Plan a = s.plan("aaaaaaaaaaaaaaaa");
        assertEquals("Bedwars, sesli gelin", a.note);
        assertNull(s.plan("bbbbbbbbbbbbbbbb").note);
        // The note's change comes as the plan again (quiet): it replaces the one held, answers and all.
        s.apply(Team5StateTest.json("{\"t\":\"plan\",\"quiet\":true,\"plan\":" + plan("aaaaaaaaaaaaaaaa", AYSE, NOW + 3600,
                "{\"" + AYSE + "\":\"yes\",\"" + ME + "\":\"maybe\"}", "\"21:00'de lobi\"") + "}"), NOW);
        assertEquals("21:00'de lobi", s.plan("aaaaaaaaaaaaaaaa").note);
        assertEquals("maybe", s.myPlanAnswer("aaaaaaaaaaaaaaaa"));
        // Cleared: no note any more (absent).
        s.apply(Team5StateTest.json("{\"t\":\"plan\",\"quiet\":true,\"plan\":" + plan("aaaaaaaaaaaaaaaa", AYSE, NOW + 3600,
                "{}", null) + "}"), NOW);
        assertNull(s.plan("aaaaaaaaaaaaaaaa").note);
        // An answer given here keeps the note.
        SocialModel.Plan noted = s.plan("bbbbbbbbbbbbbbbb").withNote("Bedwars");
        assertEquals("Bedwars", noted.withAnswer(KAAN, "no").note);
        assertNull(noted.withNote("  ").note);
        // A note that is not text is no note.
        SocialModel.Plan odd = SocialModel.Plan.read(Team5StateTest.json(plan("cccccccccccccccc", AYSE, NOW + 900, "{}", "42")));
        assertNotNull(odd);
        assertNull(odd.note);
    }

    @Test
    public void theMakerWritesTheNoteAndTheCrewsLeadersOnlyClearIt() {
        SocialState s = state("\"plans\",\"planNotes\"]");
        SocialModel.Plan theirs = s.plan("aaaaaaaaaaaaaaaa");
        SocialModel.Plan mine = s.plan("bbbbbbbbbbbbbbbb");
        assertTrue(s.mayWriteNote(mine, NOW));
        assertFalse(s.mayWriteNote(theirs, NOW));
        // The player moderates Gece: someone else's note may only be cleared.
        assertTrue(s.mayClearNote(theirs, NOW));
        assertFalse(s.mayClearNote(mine, NOW));
        // Once over, nothing changes.
        assertFalse(s.mayWriteNote(mine, NOW + 7200 + 7200));
        assertFalse(s.mayClearNote(theirs, NOW + 3600 + 7200));
    }

    @Test
    public void theGateNamesNotesAndCrewmateRequests() {
        SocialGate old = SocialGate.of(true, true, true, 30, new HashSet<>(Arrays.asList("plans")), new HashSet<String>(), false, true);
        assertTrue(old.plans);
        assertFalse(old.planNotes);
        assertFalse(old.crewFriendRequests);
        SocialGate fix2 = SocialGate.of(true, true, true, 30, new HashSet<>(Arrays.asList("plans", "planNotes", "crewFriendRequests")),
                new HashSet<String>(), false, true);
        assertTrue(fix2.planNotes);
        assertTrue(fix2.crewFriendRequests);
        // Notes ride on plans: without plans there are none.
        assertFalse(SocialGate.of(true, true, true, 30, new HashSet<>(Arrays.asList("planNotes")), new HashSet<String>(), false, true).planNotes);
        assertFalse(SocialGate.OFF.planNotes);
    }

    @Test
    public void aRefusedNoteSaysWhy() {
        JsonObject error = Team5StateTest.json("{\"t\":\"error\",\"rid\":3,\"code\":\"invalid_plan_note\",\"reason\":\"words\"}");
        SocialProblem p = SocialProblem.ofSocket(error);
        assertEquals(SocialProblem.Kind.PLAN_NOTE, p.kind);
        assertEquals("words", p.reason);
        SocialProblem muted = SocialProblem.ofSocket(Team5StateTest.json("{\"t\":\"error\",\"code\":\"chat_muted\",\"until\":1792600000}"));
        assertEquals(SocialProblem.Kind.CHAT_MUTED, muted.kind);
        assertEquals(Long.valueOf(1792600000L), muted.until);
        // The crewmate's request answers as the code's: someone the player may not ask is "not found".
        assertEquals(SocialProblem.Kind.NO_ONE_WITH_CODE, SocialProblem.of(404, "{\"error\":{\"code\":\"user_not_found\"}}", null, false).kind);
    }

    @Test
    public void aPlanReportNamesItsMakerAndThePlan() {
        JsonObject body = Reports.planMaker(AYSE, GECE, "aaaaaaaaaaaaaaaa", Reports.INAPPROPRIATE, " kötü  not ", false);
        JsonObject target = body.getAsJsonObject("target");
        assertEquals("player", Json.str(target, "kind"));
        assertEquals(AYSE, Json.str(target, "id"));
        assertEquals(GECE, Json.str(target.getAsJsonObject("plan"), "crew"));
        assertEquals("aaaaaaaaaaaaaaaa", Json.str(target.getAsJsonObject("plan"), "id"));
        assertEquals("inappropriate", Json.str(body, "reason"));
        assertEquals("kötü not", Json.str(body, "note"));
        assertFalse(body.has("block"));
        // A plan's report offers the note's reason first, then a player's.
        List<String> reasons = Reports.reasons(Reports.Target.PLAN);
        assertEquals(Reports.INAPPROPRIATE, reasons.get(0));
        assertTrue(reasons.containsAll(Reports.reasons(Reports.Target.PLAYER)));
        assertTrue(Reports.offersBlock(Reports.Target.PLAN));
    }

    // ---- the menus ----

    private static List<String> names(List<? extends SocialMenus.Row<?>> rows) {
        List<String> out = new ArrayList<>();
        for (SocialMenus.Row<?> r : rows) out.add(r == null ? "|" : r.toString());
        return out;
    }

    @Test
    public void aFriendsMenuFollowsTheDesktopsOrder() {
        SocialMenus.PersonInput in = new SocialMenus.PersonInput();
        in.friend = true;
        in.crewmate = true;
        in.onProjects = true;
        in.chat = true;
        in.crewInvite = SocialMenus.Invite.OPEN;
        in.reports = true;
        in.reportable = true;
        assertEquals(Arrays.asList("JOIN", "MESSAGE", "DISCORD", "|", "FAVOURITE", "CREW_INVITE", "|", "REMOVE_FRIEND", "BLOCK", "REPORT"),
                names(SocialMenus.person(in)));
        // In that friend's own conversation there is no "Mesaj gönder"; a favourite says so; all crews invited: dim.
        in.inTheirConversation = true;
        in.favourite = true;
        in.crewInvite = SocialMenus.Invite.INVITED;
        in.onProjects = false;
        assertEquals(Arrays.asList("DISCORD", "|", "UNFAVOURITE", "CREW_INVITE(off)", "|", "REMOVE_FRIEND", "BLOCK", "REPORT"),
                names(SocialMenus.person(in)));
        // Full crews: dim too; in every crew already: no row.
        in.crewInvite = SocialMenus.Invite.FULL;
        assertEquals(Arrays.asList("DISCORD", "|", "UNFAVOURITE", "CREW_INVITE(off)", "|", "REMOVE_FRIEND", "BLOCK", "REPORT"),
                names(SocialMenus.person(in)));
        in.crewInvite = SocialMenus.Invite.NONE;
        assertEquals(Arrays.asList("DISCORD", "|", "UNFAVOURITE", "|", "REMOVE_FRIEND", "BLOCK", "REPORT"),
                names(SocialMenus.person(in)));
    }

    @Test
    public void aCrewmateWhoIsNotAFriendGetsAddFriendNotMessages() {
        SocialMenus.PersonInput in = new SocialMenus.PersonInput();
        in.crewmate = true;
        in.chat = true;
        in.crewFriendRequests = true;
        in.reports = true;
        in.reportable = true;
        assertEquals(Arrays.asList("DISCORD", "ADD_FRIEND", "|", "BLOCK", "REPORT"), names(SocialMenus.person(in)));
        // The request went: dim "İstek gönderildi"; they asked first: "İsteği kabul et".
        in.outgoing = true;
        assertEquals(Arrays.asList("DISCORD", "REQUEST_SENT(off)", "|", "BLOCK", "REPORT"), names(SocialMenus.person(in)));
        in.outgoing = false;
        in.incoming = true;
        assertEquals(Arrays.asList("DISCORD", "ACCEPT_REQUEST", "|", "BLOCK", "REPORT"), names(SocialMenus.person(in)));
        // An older service: no "Arkadaş ekle" at all.
        in.incoming = false;
        in.crewFriendRequests = false;
        assertEquals(Arrays.asList("DISCORD", "|", "BLOCK", "REPORT"), names(SocialMenus.person(in)));
        // In a crew the player leads: hand over and remove.
        in.leaderHere = true;
        in.removableHere = true;
        assertEquals(Arrays.asList("DISCORD", "|", "HAND_OVER", "REMOVE_FROM_CREW", "|", "BLOCK", "REPORT"), names(SocialMenus.person(in)));
    }

    @Test
    public void requestsAnswerFirstAndBlockedPlayersOfferLittle() {
        SocialMenus.PersonInput in = new SocialMenus.PersonInput();
        in.incoming = true;
        in.pending = SocialMenus.Pending.FRIEND_REQUEST;
        in.reports = true;
        in.reportable = true;
        assertEquals(Arrays.asList("ACCEPT", "DECLINE", "|", "BLOCK", "REPORT"), names(SocialMenus.person(in)));
        // A crewmate's friend request row does not offer "İsteği kabul et" twice.
        in.crewmate = true;
        in.crewFriendRequests = true;
        assertEquals(Arrays.asList("ACCEPT", "DECLINE", "|", "DISCORD", "|", "BLOCK", "REPORT"), names(SocialMenus.person(in)));
        SocialMenus.PersonInput out = new SocialMenus.PersonInput();
        out.outgoing = true;
        out.pending = SocialMenus.Pending.OUTGOING;
        assertEquals(Arrays.asList("CANCEL_REQUEST", "|", "BLOCK"), names(SocialMenus.person(out)));
        SocialMenus.PersonInput join = new SocialMenus.PersonInput();
        join.pending = SocialMenus.Pending.JOIN_REQUEST;
        join.reports = true;
        join.reportable = true;
        assertEquals(Arrays.asList("ACCEPT", "DECLINE", "|", "BLOCK", "REPORT"), names(SocialMenus.person(join)));
        SocialMenus.PersonInput blocked = new SocialMenus.PersonInput();
        blocked.blocked = true;
        blocked.crewmate = true;
        blocked.onProjects = true;
        blocked.reports = true;
        blocked.reportable = true;
        assertEquals(Arrays.asList("DISCORD", "|", "UNBLOCK", "REPORT"), names(SocialMenus.person(blocked)));
        SocialMenus.PersonInput self = new SocialMenus.PersonInput();
        self.self = true;
        self.crewmate = true;
        assertTrue(SocialMenus.person(self).isEmpty());
    }

    @Test
    public void aCrewsMenuKeepsTheDesktopsGroups() {
        SocialMenus.CrewInput in = new SocialMenus.CrewInput();
        in.chat = true;
        in.hasCode = true;
        in.mayInvite = true;
        in.first = false;
        in.last = true;
        in.mute = SocialMenus.Mute.UNMUTED;
        in.reports = true;
        assertEquals(Arrays.asList("OPEN", "|", "CHAT", "COPY_CODE", "INVITE", "|", "MAKE_MAIN", "MOVE_LEFT", "MOVE_RIGHT(off)", "|",
                "MUTE", "|", "REPORT", "LEAVE"), names(SocialMenus.crew(in)));
        // On its own page, the main crew, its leader alone in it, one crew, muted.
        SocialMenus.CrewInput page = new SocialMenus.CrewInput();
        page.onItsPage = true;
        page.leader = true;
        page.alone = true;
        page.mute = SocialMenus.Mute.MUTED;
        assertEquals(Arrays.asList("UNMUTE", "|", "CLOSE"), names(SocialMenus.crew(page)));
        page.alone = false;
        page.first = true;
        page.last = false;
        page.mayInvite = true;
        page.inviteDim = true;
        assertEquals(Arrays.asList("INVITE(off)", "|", "MOVE_LEFT(off)", "MOVE_RIGHT", "|", "UNMUTE", "|", "DISBAND"), names(SocialMenus.crew(page)));
    }

    @Test
    public void aPlansMenuOffersTheNoteToItsMakerBeforeItStarts() {
        SocialMenus.PlanInput in = new SocialMenus.PlanInput();
        in.notes = true;
        in.maker = true;
        in.upcoming = true;
        in.mayCancel = true;
        in.reports = true;
        assertEquals(Arrays.asList("ADD_NOTE", "|", "CANCEL"), names(SocialMenus.plan(in)));
        in.hasNote = true;
        assertEquals(Arrays.asList("EDIT_NOTE", "REMOVE_NOTE", "|", "CANCEL"), names(SocialMenus.plan(in)));
        // Under way: the note may still go, not change.
        in.upcoming = false;
        assertEquals(Arrays.asList("REMOVE_NOTE", "|", "CANCEL"), names(SocialMenus.plan(in)));
        // Someone else's plan: a moderator clears its note and may cancel it; anyone may report its maker.
        SocialMenus.PlanInput other = new SocialMenus.PlanInput();
        other.notes = true;
        other.upcoming = true;
        other.hasNote = true;
        other.crewMod = true;
        other.mayCancel = true;
        other.reports = true;
        assertEquals(Arrays.asList("REMOVE_NOTE", "|", "REPORT", "CANCEL"), names(SocialMenus.plan(other)));
        other.crewMod = false;
        other.mayCancel = false;
        assertEquals(Arrays.asList("REPORT"), names(SocialMenus.plan(other)));
        // A service without notes and without reports: only what was there before.
        SocialMenus.PlanInput old = new SocialMenus.PlanInput();
        old.maker = true;
        old.upcoming = true;
        old.mayCancel = true;
        assertEquals(Arrays.asList("CANCEL"), names(SocialMenus.plan(old)));
    }

    // ---- who answered ----

    @Test
    public void answersGroupInTheCardsOrderWithThePlayerFirst() {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("u5", "no");
        answers.put("u1", "yes");
        answers.put("u2", "maybe");
        answers.put(ME, "yes");
        answers.put("u3", "yes");
        answers.put("u9", "yes");
        final Map<String, String> names = new HashMap<>();
        names.put("u1", "zeynep");
        names.put("u3", "Ali");
        names.put("u2", "Can");
        names.put("u5", "Ece");
        List<AnswerPeople.Group> groups = AnswerPeople.of(answers, AnswerPeople.PLAN_ORDER, ME, names::get);
        assertEquals(3, groups.size());
        assertEquals("yes", groups.get(0).answer);
        // The player first, then by name (case aside), someone not known last.
        assertEquals(Arrays.asList(ME, "u3", "u1", "u9"), groups.get(0).ids);
        assertEquals("maybe", groups.get(1).answer);
        assertEquals("no", groups.get(2).answer);
        assertEquals(6, AnswerPeople.total(groups));
        assertFalse(groups.get(0).folds());
        // A group of more than five (the desktop's number) shows five until "N kişi daha".
        for (int i = 10; i < 20; i++) answers.put("u" + i, "maybe");
        AnswerPeople.Group maybe = AnswerPeople.of(answers, AnswerPeople.PLAN_ORDER, ME, names::get).get(1);
        assertEquals(11, maybe.count());
        assertTrue(maybe.folds());
        assertEquals(5, maybe.shown(false).size());
        assertEquals(6, maybe.hidden());
        assertEquals(11, maybe.shown(true).size());
        // Empty groups are left out; a call's answers have their own order.
        Map<String, String> call = new LinkedHashMap<>();
        call.put("u1", "soon");
        List<AnswerPeople.Group> calls = AnswerPeople.of(call, AnswerPeople.CALL_ORDER, ME, names::get);
        assertEquals(1, calls.size());
        assertEquals("soon", calls.get(0).answer);
        assertTrue(AnswerPeople.of(new HashMap<String, String>(), AnswerPeople.PLAN_ORDER, ME, names::get).isEmpty());
    }
}
