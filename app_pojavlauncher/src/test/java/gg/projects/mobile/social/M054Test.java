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

/**
 * M054 (0.5.3): the crew member cap as the launcher shows it (CREW-CAP: teams.ts {@code nearlyFull}, the discover card's
 * cap), and the friends' play time with its switch (CREW-PLUS: played.ts; CREW-PLUS-HIDE: {@code privacy.sharePlayTime}).
 */
public class M054Test {
    private static final String A = "100000000000000001";

    // ---- CREW-CAP ----

    @Test
    public void theCapShowsFromNinetyPercent() {
        assertFalse(CrewRules.nearlyFull(89, 100));
        assertTrue(CrewRules.nearlyFull(90, 100));
        assertTrue(CrewRules.nearlyFull(100, 100));
        assertTrue(CrewRules.nearlyFull(120, 100));
        assertFalse(CrewRules.nearlyFull(26, 30));
        assertTrue(CrewRules.nearlyFull(27, 30));
        assertFalse(CrewRules.nearlyFull(12, 0));
        assertTrue(CrewRules.nearlyFull(2, 2));
        assertFalse(CrewRules.nearlyFull(1, 2));
    }

    @Test
    public void anOwnCrewsCapIsItsOwnThenTheServicesThenThirty() {
        assertEquals(60, CrewRules.ownCap(60, 100));
        assertEquals(100, CrewRules.ownCap(0, 100));
        assertEquals(30, CrewRules.ownCap(0, 0));
        assertTrue(CrewRules.ownFull(60, 60, 100));
        assertFalse(CrewRules.ownFull(59, 60, 100));
        assertFalse(CrewRules.ownFull(31, 0, 100));
        // Before the friend list said any cap, nothing is called full (as before CREW-CAP).
        assertFalse(CrewRules.ownFull(40, 0, 0));
    }

    @Test
    public void summariesBadgesAndListingsCarryTheCap() {
        JsonObject team = new JsonObject();
        team.addProperty("id", "0123456789abcdef");
        team.addProperty("name", "Gece Kartalları");
        team.addProperty("members", 92);
        team.addProperty("maxMembers", 100);
        assertEquals(100, SocialModel.TeamSummary.read(team).maxMembers);
        team.remove("maxMembers");
        assertEquals(0, SocialModel.TeamSummary.read(team).maxMembers);
        team.addProperty("maxMembers", -5);
        assertEquals(0, SocialModel.TeamSummary.read(team).maxMembers);

        JsonObject badge = new JsonObject();
        badge.addProperty("crew", "0123456789abcdef");
        badge.addProperty("tag", "GKR");
        badge.addProperty("name", "Gece Kartalları");
        badge.addProperty("members", 28);
        badge.addProperty("maxMembers", 30);
        SocialModel.Badge read = SocialModel.Badge.read(badge);
        assertNotNull(read);
        assertEquals(30, read.maxMembers);

        String listing = "{\"v\":1,\"ver\":1,\"next\":2,\"at\":1,\"crews\":["
                + "{\"id\":\"0123456789abcdef\",\"name\":\"Gece Kartalları\",\"join\":\"open\",\"members\":28,\"maxMembers\":28},"
                + "{\"id\":\"fedcba9876543210\",\"name\":\"Şimşek Takımı\",\"join\":\"open\",\"members\":30}]}";
        DiscoverModel.Listing l = DiscoverModel.readListing(Json.parseObject(listing));
        assertNotNull(l);
        DiscoverModel.Crew full = l.crews.get(0);
        assertEquals(28, full.cap());
        assertEquals(DiscoverModel.Slot.FULL, DiscoverModel.slot(full, false, false, true));
        assertEquals(28, full.summary().maxMembers);
        // No cap said: the old 30.
        DiscoverModel.Crew older = l.crews.get(1);
        assertEquals(0, older.maxMembers);
        assertEquals(DiscoverModel.Slot.FULL, DiscoverModel.slot(older, false, false, true));
    }

    @Test
    public void aCrewKeepsItsCapThroughItsCopies() {
        JsonObject team = new JsonObject();
        team.addProperty("id", "0123456789abcdef");
        team.addProperty("name", "Gece Kartalları");
        team.addProperty("code", "TKM-AAAA-BBBB");
        team.addProperty("maxMembers", 64);
        SocialModel.Team read = SocialModel.Team.read(team);
        assertNotNull(read);
        assertEquals(64, read.maxMembers);
        assertEquals(64, read.withLink(null).maxMembers);
    }

    // ---- CREW-PLUS ----

    private static JsonObject presence(String state, long since) {
        JsonObject p = new JsonObject();
        p.addProperty("id", A);
        p.addProperty("state", state);
        p.addProperty("status", "online");
        if (since > 0) p.addProperty("playingSince", since);
        JsonObject game = new JsonObject();
        game.addProperty("where", "projects");
        p.add("game", game);
        return p;
    }

    @Test
    public void playingSinceOnlyWhilePlaying() {
        assertEquals(1792400100L, SocialModel.Presence.read(presence("playing", 1792400100L)).playingSince);
        assertEquals(0L, SocialModel.Presence.read(presence("launcher", 1792400100L)).playingSince);
        assertEquals(0L, SocialModel.Presence.read(presence("playing", 0)).playingSince);
        SocialModel.Presence playing = SocialModel.Presence.read(presence("playing", 1792400100L));
        assertEquals(1792400100L, playing.keepingLastSeen(SocialModel.Presence.offline(A, "today")).playingSince);
    }

    @Test
    public void playedIsCoarseAndRoundedDown() {
        long since = 1_000_000;
        assertNull(PlayedTime.of(since, since + 299));
        PlayedTime.Played five = PlayedTime.of(since, since + 300);
        assertEquals(PlayedTime.Unit.MINUTES, five.unit);
        assertEquals(5, five.value);
        assertEquals(25, PlayedTime.of(since, since + 25 * 60 + 299).value);
        assertEquals(55, PlayedTime.of(since, since + 3599).value);
        PlayedTime.Played hour = PlayedTime.of(since, since + 3600);
        assertEquals(PlayedTime.Unit.HOURS, hour.unit);
        assertEquals(1, hour.value);
        assertEquals(2, PlayedTime.of(since, since + 3 * 3600 - 1).value);
        assertNull(PlayedTime.of(0, since));
        assertNull(PlayedTime.of(since, since - 10));
        assertNull(PlayedTime.of(SocialModel.Presence.read(presence("launcher", since)), since + 4000));
        assertEquals(1, PlayedTime.of(SocialModel.Presence.read(presence("playing", since)), since + 4000).value);
    }

    @Test
    public void theScreenWakesAtTheNextChangeAtMostOnceAMinute() {
        long since = 1_000_000;
        assertEquals(300, PlayedTime.nextChange(since, since));
        assertEquals(1, PlayedTime.nextChange(since, since + 299));
        assertEquals(300, PlayedTime.nextChange(since, since + 300));
        assertEquals(1, PlayedTime.nextChange(since, since + 3599));
        assertEquals(3600, PlayedTime.nextChange(since, since + 3600));
        assertEquals(60, PlayedTime.nextTick(Collections.singletonList(since), since + 299));
        assertEquals(180, PlayedTime.nextTick(Arrays.asList(since, since - 180), since + 120));
        assertEquals(240, PlayedTime.nextTick(Arrays.asList(since - 3600, since - 3300), since + 60));
        assertEquals(-1, PlayedTime.nextTick(Collections.<Long>emptyList(), since));
    }

    @Test
    public void theSwitchShowsOnlyFromAServiceThatHasIt() {
        JsonObject me = new JsonObject();
        me.addProperty("id", A);
        me.addProperty("username", "korates");
        me.addProperty("displayName", "KorAtesi");
        me.addProperty("avatar", "discord");
        SocialModel.Me older = SocialModel.Me.read(me);
        assertNotNull(older);
        assertFalse(older.playTimeListed);
        assertTrue(older.sharePlayTime);
        JsonObject privacy = new JsonObject();
        privacy.addProperty("shareLastSeen", true);
        privacy.addProperty("sharePlayTime", false);
        me.add("privacy", privacy);
        SocialModel.Me hidden = SocialModel.Me.read(me);
        assertTrue(hidden.playTimeListed);
        assertFalse(hidden.sharePlayTime);
        // Copies keep it.
        assertFalse(hidden.withStatus("dnd").sharePlayTime);
        assertTrue(hidden.withStatus("dnd").playTimeListed);
    }
}
