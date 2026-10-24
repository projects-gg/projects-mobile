package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * MOB1 (TEAM6, README "Crew tags", "Hidden crews", "Join requests from a tag"): the tag's card on
 * profiles (friends, members, the player), its live changes, a hidden crew taking the player's tag
 * off with one notice, the "Hoş geldin" lines, the card's rules ("Katılma isteği gönder" only when it
 * may go), the refusal memory of the Turkey day, and an older service showing nothing of it.
 */
public class Team6Test {
    private static final String ME = "100000000000000011";
    private static final String B = "100000000000000012";
    private static final String C = "100000000000000013";
    private static final String GK = "0a1b2c3d4e5f6a7b";
    private static final String OTHER = "1a1b2c3d4e5f6a7b";
    private static final long NOW = 1_792_521_000L;

    private static JsonObject badge(String crew, String tag, String name, boolean requests) {
        JsonObject b = new JsonObject();
        b.addProperty("crew", crew);
        b.addProperty("tag", tag);
        b.addProperty("color", "navy");
        b.addProperty("frame", "shield");
        b.addProperty("emblem", "bolt");
        b.addProperty("name", name);
        b.addProperty("motto", "Gece bizimdir");
        b.addProperty("createdOn", "2026-09-20");
        b.addProperty("members", 12);
        b.addProperty("requests", requests);
        return b;
    }

    private static JsonObject profile(String id, String name) {
        JsonObject p = new JsonObject();
        p.addProperty("id", id);
        p.addProperty("username", name.toLowerCase());
        p.addProperty("displayName", name);
        p.addProperty("avatar", "discord");
        return p;
    }

    private static JsonObject member(String id, String name, boolean founder, JsonObject badge) {
        JsonObject m = profile(id, name);
        m.addProperty("role", founder ? "founder" : "member");
        m.addProperty("joinedAt", NOW - 1000);
        if (badge != null) m.add("badge", badge);
        return m;
    }

    private static JsonObject team(String id, String name, boolean hidden, JsonObject... members) {
        JsonObject t = new JsonObject();
        t.addProperty("id", id);
        t.addProperty("name", name);
        t.addProperty("tag", "GK");
        t.addProperty("emblem", "bolt");
        t.addProperty("code", "ABCD1234");
        t.addProperty("hidden", hidden);
        t.addProperty("badgeRequests", true);
        JsonArray list = new JsonArray();
        for (JsonObject m : members) list.add(m);
        t.add("members", list);
        return t;
    }

    private static JsonObject friendsWith(JsonObject friendBadge, JsonObject crewTeam, String... features) {
        JsonObject o = new JsonObject();
        JsonArray friends = new JsonArray();
        JsonObject b = profile(B, "Ayse");
        if (friendBadge != null) b.add("badge", friendBadge);
        friends.add(b);
        o.add("friends", friends);
        JsonObject limits = new JsonObject();
        limits.addProperty("teamMembers", 30);
        o.add("limits", limits);
        JsonArray list = new JsonArray();
        for (String f : features) list.add(f);
        o.add("teamFeatures", list);
        JsonArray crews = new JsonArray();
        if (crewTeam != null) {
            JsonObject crew = new JsonObject();
            crew.add("team", crewTeam);
            crews.add(crew);
        }
        o.add("crews", crews);
        o.addProperty("crewLimit", 5);
        return o;
    }

    private static final String[] TAG_FEATURES = {"look", "calls", "roles", "mute", "multi", "requests", "badges", "badgeRequests"};

    private static SocialState signedIn(JsonObject meBadge, JsonObject friends) {
        SocialState state = new SocialState();
        JsonObject me = profile(ME, "Mert");
        if (meBadge != null) me.add("badge", meBadge);
        JsonObject privacy = new JsonObject();
        privacy.addProperty("shareRead", true);
        privacy.addProperty("shareTyping", false);
        me.add("privacy", privacy);
        state.setMe(SocialModel.Me.read(me));
        state.applyFriends(SocialModel.FriendsData.read(friends));
        return state;
    }

    private static SocialState wearing() {
        return signedIn(badge(GK, "GK", "Gece Kartallari", true),
                friendsWith(badge(GK, "GK", "Gece Kartallari", true), team(GK, "Gece Kartallari", false,
                        member(C, "Can", true, null),
                        member(ME, "Mert", false, badge(GK, "GK", "Gece Kartallari", true))), TAG_FEATURES));
    }

    @Test
    public void profilesCarryTheTagCardOnlyWhenTheServiceSendsOne() {
        SocialModel.Badge b = SocialModel.Badge.read(badge(GK, "GK", "Gece Kartallari", true));
        assertNotNull(b);
        assertEquals("GK", b.tag);
        assertEquals(12, b.members);
        assertTrue(b.requests);
        assertEquals("2026-09-20", b.createdOn);
        assertNull(SocialModel.Badge.read(null));
        assertNull(SocialModel.Badge.read(badge(GK, " ", "x", true)));
        SocialState state = wearing();
        assertEquals("GK", state.friend(B).profile.badge.tag);
        assertEquals("GK", state.me().profile.badge.tag);
        assertEquals("GK", state.crew(GK).team.member(ME).profile.badge.tag);
        assertNull(state.crew(GK).team.member(C).profile.badge);
        assertEquals(1, state.crew(GK).team.wearers());
        assertFalse(state.crew(GK).team.hidden);
        assertTrue(state.crew(GK).team.badgeRequests);
        // The chat switches ride on me's privacy (on unless turned off).
        assertTrue(state.me().shareRead);
        assertFalse(state.me().shareTyping);
        assertTrue(state.me().chatPrivacy);
    }

    @Test
    public void aBadgeMessageReplacesTheTagWhereverThePlayerShows() {
        SocialState state = wearing();
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "badge");
        frame.addProperty("id", B);
        frame.add("badge", badge(OTHER, "ZK", "Zirve", false));
        state.apply(frame, NOW);
        assertEquals("ZK", state.friend(B).profile.badge.tag);
        assertEquals("ZK", state.badgeOf(B).tag);
        frame.add("badge", com.google.gson.JsonNull.INSTANCE);
        state.apply(frame, NOW);
        assertNull(state.friend(B).profile.badge);
        // The player's own, from another device: their crew entry too.
        JsonObject mine = new JsonObject();
        mine.addProperty("t", "badge");
        mine.addProperty("id", ME);
        state.apply(mine, NOW);
        assertNull(state.me().profile.badge);
        assertNull(state.crew(GK).team.member(ME).profile.badge);
    }

    @Test
    public void hidingTheCrewTakesThePlayersTagOffWithOneNotice() {
        SocialState state = wearing();
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "team");
        frame.addProperty("crew", GK);
        frame.addProperty("by", C);
        frame.add("team", team(GK, "Gece Kartallari", true, member(C, "Can", true, null), member(ME, "Mert", false, null)));
        List<SocialState.Notice> notices = state.apply(frame, NOW);
        assertEquals(1, notices.size());
        assertEquals(SocialState.Notice.Kind.TAG_OFF, notices.get(0).kind);
        assertNull(state.me().profile.badge);
        assertTrue(state.crew(GK).team.hidden);
        // The badge message after it says nothing more.
        JsonObject gone = new JsonObject();
        gone.addProperty("t", "badge");
        gone.addProperty("id", ME);
        assertTrue(state.apply(gone, NOW).isEmpty());
    }

    /** The player wears GK's tag and is in OTHER too (both lists carry their entry with the tag). */
    private static SocialState wearingInTwoCrews() {
        JsonObject gk = badge(GK, "GK", "Gece Kartallari", true);
        JsonObject friends = friendsWith(null, team(GK, "Gece Kartallari", false,
                member(C, "Can", true, null), member(ME, "Mert", false, gk)), TAG_FEATURES);
        JsonObject second = new JsonObject();
        second.add("team", team(OTHER, "Zirve", false, member(B, "Ayse", true, null), member(ME, "Mert", false, gk)));
        friends.getAsJsonArray("crews").add(second);
        return signedIn(gk, friends);
    }

    @Test
    public void theTagGoesFromThePlayersEntriesInTheirOtherCrewsToo() {
        // Leaving the crew: profile() reads the crews' lists before me, so every entry lets go of it.
        SocialState state = wearingInTwoCrews();
        assertEquals("GK", state.badgeOf(ME).tag);
        JsonObject left = new JsonObject();
        left.addProperty("t", "team");
        left.addProperty("crew", GK);
        state.apply(left, NOW);
        assertNull(state.crew(GK));
        assertNull(state.me().profile.badge);
        assertNull(state.badgeOf(ME));
        assertNull(state.crew(OTHER).team.member(ME).profile.badge);
        // Its leader hiding it: the same, with the one notice.
        state = wearingInTwoCrews();
        JsonObject hidden = new JsonObject();
        hidden.addProperty("t", "team");
        hidden.addProperty("crew", GK);
        hidden.addProperty("by", C);
        hidden.add("team", team(GK, "Gece Kartallari", true, member(C, "Can", true, null), member(ME, "Mert", false, null)));
        List<SocialState.Notice> notices = state.apply(hidden, NOW);
        assertEquals(1, notices.size());
        assertEquals(SocialState.Notice.Kind.TAG_OFF, notices.get(0).kind);
        assertNull(state.badgeOf(ME));
        assertNull(state.crew(OTHER).team.member(ME).profile.badge);
    }

    @Test
    public void theLeaderHidingTheirOwnCrewIsToldNothing() {
        SocialState state = signedIn(badge(GK, "GK", "Gece Kartallari", true),
                friendsWith(null, team(GK, "Gece Kartallari", false, member(ME, "Mert", true, badge(GK, "GK", "Gece Kartallari", true))),
                        TAG_FEATURES));
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "team");
        frame.addProperty("crew", GK);
        frame.add("team", team(GK, "Gece Kartallari", true, member(ME, "Mert", true, null)));
        assertTrue(state.apply(frame, NOW).isEmpty());
        assertNull(state.me().profile.badge);
    }

    @Test
    public void someoneJoiningLeavesAWelcomeLineWithSayHiForTenMinutes() {
        SocialState state = wearing();
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "team");
        frame.addProperty("crew", GK);
        // The service's form: a list of ids (admission.ts `joined: [user]`); a bare id was never sent.
        JsonArray joined = new JsonArray();
        joined.add(B);
        frame.add("joined", joined);
        frame.add("team", team(GK, "Gece Kartallari", false, member(C, "Can", true, null), member(ME, "Mert", false, null),
                member(B, "Ayse", false, null)));
        state.apply(frame, NOW);
        List<SocialState.CrewJoin> joins = state.joins(GK, NOW + 5);
        assertEquals(1, joins.size());
        assertEquals(B, joins.get(0).userId);
        assertTrue(BadgeRules.canGreet(joins.get(0), ME, NOW + 599));
        assertFalse(BadgeRules.canGreet(joins.get(0), ME, NOW + 600));
        assertEquals(NOW + 600, BadgeRules.nextGreetEnd(joins, ME, NOW + 5));
        // The player's own line has no "Selam ver"; a second word about the same join adds nothing.
        state.noteJoin(GK, ME, NOW + 10);
        state.noteJoin(GK, ME, NOW + 11);
        assertEquals(2, state.joins(GK, NOW + 12).size());
        assertFalse(BadgeRules.canGreet(state.joins(GK, NOW + 12).get(1), ME, NOW + 12));
        // Lines stay a day in memory.
        assertTrue(state.joins(GK, NOW + SocialState.CrewJoin.KEEP_SECONDS + 20).isEmpty());
    }

    @Test
    public void aJoinRequestFromATagNamesTheWearer() {
        JsonObject request = new JsonObject();
        request.add("from", profile(B, "Ayse"));
        request.addProperty("sentAt", NOW);
        request.addProperty("via", C);
        SocialModel.JoinRequest r = SocialModel.JoinRequest.read(request);
        assertEquals(C, r.via);
        request.addProperty("via", "nobody");
        assertNull(SocialModel.JoinRequest.read(request).via);
    }

    @Test
    public void theCardAsksToJoinOnlyWhenItMayGo() {
        BadgeRules.CardInput in = new BadgeRules.CardInput();
        in.crew = GK;
        in.requests = true;
        in.friend = true;
        in.crews = Collections.singletonList(OTHER);
        in.crewLimit = 5;
        in.multi = true;
        in.requestsOn = true;
        assertEquals(BadgeRules.Row.ASK, BadgeRules.cardRow(in));
        in.friend = false;
        assertEquals(BadgeRules.Row.NONE, BadgeRules.cardRow(in));
        in.friend = true;
        in.requests = false;
        assertEquals(BadgeRules.Row.NONE, BadgeRules.cardRow(in));
        in.requests = true;
        in.waiting = Arrays.asList("a", "b", "c");
        assertEquals(BadgeRules.Row.NONE, BadgeRules.cardRow(in));
        in.waiting = Collections.singletonList(GK);
        assertEquals(BadgeRules.Row.WAITING, BadgeRules.cardRow(in));
        in.waiting = Collections.emptyList();
        in.invites = Collections.singletonList(GK);
        assertEquals(BadgeRules.Row.INVITED, BadgeRules.cardRow(in));
        in.invites = Collections.emptyList();
        in.crews = Arrays.asList(OTHER, GK);
        assertEquals(BadgeRules.Row.MEMBER, BadgeRules.cardRow(in));
        assertTrue(BadgeRules.menuIsCrews(BadgeRules.Row.MEMBER));
        in.own = true;
        assertEquals(BadgeRules.Row.OWN, BadgeRules.cardRow(in));
        in.own = false;
        in.crews = Arrays.asList("1", "2", "3", "4", "5");
        assertEquals(BadgeRules.Row.NONE, BadgeRules.cardRow(in));
        in.crews = Collections.emptyList();
        in.refusedToday = true;
        assertEquals(BadgeRules.Row.NONE, BadgeRules.cardRow(in));
        assertFalse(BadgeRules.menuIsCrews(BadgeRules.Row.NONE));
    }

    @Test
    public void aRefusalKeepsTheButtonAwayUntilTheTurkeyDayEnds() {
        // 23 Oct 2026 12:00 in Turkey (09:00 UTC): the day ends at 21:00 UTC.
        long nowMs = 1_792_746_000_000L;
        long end = BadgeRules.turkeyDayEnd(nowMs);
        assertEquals(1_792_789_200L, end);
        Map<String, Long> memory = BadgeRules.withRefusal(BadgeRules.readMemory(null), GK, end, nowMs);
        assertTrue(BadgeRules.refusedNow(memory, GK, nowMs));
        assertFalse(BadgeRules.refusedNow(memory, GK, end * 1000));
        assertEquals(memory, BadgeRules.readMemory(BadgeRules.writeMemory(memory)));
        assertEquals(BadgeRules.Refusal.CLOSED, BadgeRules.refusalOf("badge_request_closed"));
        assertEquals(BadgeRules.Refusal.TODAY, BadgeRules.refusalOf("limit_badge_requests"));
        assertEquals(BadgeRules.Refusal.OWN_WAITING, BadgeRules.refusalOf("limit_outgoing_team_requests"));
        assertNull(BadgeRules.refusalOf("unavailable"));
    }

    @Test
    public void aHiddenOrLockedCrewsTagCannotBeWorn() {
        SocialState state = wearing();
        SocialModel.Team team = state.crew(GK).team;
        assertEquals(BadgeRules.Wear.WORN, BadgeRules.wearState(team, GK, NOW));
        assertEquals(BadgeRules.Wear.WEAR, BadgeRules.wearState(team, null, NOW));
        SocialModel.Team hidden = SocialModel.Team.read(team(GK, "Gece Kartallari", true, member(ME, "Mert", false, null)));
        assertEquals(BadgeRules.Wear.HIDDEN, BadgeRules.wearState(hidden, null, NOW));
        assertEquals(BadgeRules.Outcome.WORE, BadgeRules.wearOutcome(null, GK));
        assertEquals(BadgeRules.Outcome.SWITCHED, BadgeRules.wearOutcome(OTHER, GK));
        assertEquals(BadgeRules.Outcome.OFF, BadgeRules.wearOutcome(GK, null));
        assertNull(BadgeRules.wearOutcome(GK, GK));
    }

    @Test
    public void anOlderServiceShowsNothingOfTags() {
        SocialState state = signedIn(null, friendsWith(null, team(GK, "Gece Kartallari", false, member(ME, "Mert", true, null)),
                "look", "calls", "roles", "mute", "multi", "requests"));
        SocialGate gate = SocialGate.of(true, true, true, 30, state.teamFeatures(), new HashSet<String>(), false, true);
        assertFalse(gate.badges);
        assertFalse(gate.badgeRequests);
        SocialGate on = SocialGate.of(true, true, true, 30, new HashSet<>(Arrays.asList(TAG_FEATURES)), new HashSet<String>(), false, true);
        assertTrue(on.badges);
        assertTrue(on.badgeRequests);
        SocialModel.Badge shown = SocialModel.Badge.read(badge(GK, "GK", "x", true));
        assertNull(BadgeRules.shown(gate, shown));
        assertNotNull(BadgeRules.shown(on, shown));
        // A crew from an older service is visible and takes requests by default.
        assertFalse(state.crew(GK).team.hidden);
    }

    @Test
    public void aLeaderDoesNotReportTheirOwnCrew() {
        SocialMenus.CrewInput in = new SocialMenus.CrewInput();
        in.reports = true;
        in.leader = true;
        assertFalse(SocialMenus.crew(in).toString().contains("REPORT"));
        in.leader = false;
        assertTrue(SocialMenus.crew(in).toString().contains("REPORT"));
    }
}
