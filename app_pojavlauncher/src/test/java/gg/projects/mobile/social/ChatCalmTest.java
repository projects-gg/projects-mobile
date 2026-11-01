package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * CHAT-CALM on the phone (docs: chat-calm.md "Telefon"; services/social README "Chat" → "Limits", "Crowded crews"):
 * a crew's slow mode in the model and the light list, who waits, when the wait ends (the player's newest message, sent
 * or on its way; a failed one not; the hub's clock with a margin; this phone's own answer), the composer's seconds and
 * ticks, the hub's {@code item: "slow"} as its own failure that never goes again by itself, a retry held until the
 * wait ends, the gate, and the crowded crew's one notice.
 */
public class ChatCalmTest {
    private static final String ME = "100000000000000021";
    private static final String MOD = "100000000000000022";
    private static final String MATE = "100000000000000023";
    private static final String LEAD = "100000000000000024";
    private static final String CREW = "3c4d5e6f7a8b9c0d";
    private static final String CHAT = ChatConv.crew(CREW);
    private static final long T = 1_792_521_000_000L;
    private static final long NOW = 1_792_521_000L;
    private static final String MAC = "AbCdEfGhIjKlMnOpQrStUv";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String member(String id, String name, String role, boolean moderator) {
        return "{\"id\":\"" + id + "\",\"username\":\"" + name.toLowerCase() + "\",\"displayName\":\"" + name + "\",\"avatar\":\"discord\","
                + "\"role\":\"" + role + "\",\"moderator\":" + moderator + ",\"joinedAt\":1}";
    }

    /** The crew with {@code slow} ("" leaves the field out, as a service before CHAT-CALM). */
    private static String team(String slow) {
        return "{\"id\":\"" + CREW + "\",\"name\":\"Gece Kartalları\",\"tag\":\"GK\",\"emblem\":\"flame\",\"color\":\"teal\","
                + "\"frame\":\"shield\",\"motto\":\"\",\"code\":\"TKM-AAAA-BBBB\",\"members\":["
                + member(LEAD, "KorAtesi", "founder", false) + "," + member(MOD, "Golge_Avci", "member", true) + ","
                + member(ME, "x7Ender", "member", false) + "],\"permissions\":{\"invite\":\"members\",\"call\":\"members\","
                + "\"kick\":\"moderators\",\"edit\":\"leader\"},\"textLockedUntil\":null,\"rev\":4"
                + (slow.isEmpty() ? "" : ",\"slow\":" + slow) + "}";
    }

    private static SocialModel.Team crew(String slow) {
        return SocialModel.Team.read(Json.parseObject(team(slow)));
    }

    private static JsonObject msg(long id, String from, String text) {
        JsonObject m = new JsonObject();
        m.addProperty("conv", CHAT);
        m.addProperty("id", id);
        m.addProperty("from", from);
        m.addProperty("text", text);
        m.addProperty("mac", MAC.substring(0, 21) + (char) ('a' + (int) (id % 20)));
        return m;
    }

    private static JsonObject frame(String type, JsonObject msg) {
        JsonObject f = new JsonObject();
        f.addProperty("t", type);
        f.add("msg", msg);
        return f;
    }

    // ---- the model ----

    @Test
    public void aCrewReadsItsSlowMode() {
        assertEquals(10, crew("10").slow);
        assertEquals("a service before CHAT-CALM: off", 0, crew("").slow);
        assertEquals(0, crew("-5").slow);
        assertEquals(0, crew("\"30\"").slow);
        SocialModel.Team team = crew("30");
        assertEquals("copies keep it", 30, team.withLink("ABCD-EFGH-JKLM").slow);
        assertEquals(30, team.withMemberBadge(MOD, null).slow);
        assertEquals(30, team.withMemberMarks(ME, Collections.singletonList("verified")).slow);
    }

    @Test
    public void onlyMembersWhoNeitherLeadNorModerateWait() {
        SocialModel.Team team = crew("10");
        assertEquals(10, SlowMode.forPlayer(team, ME));
        assertEquals("the leader", 0, SlowMode.forPlayer(team, LEAD));
        assertEquals("a moderator", 0, SlowMode.forPlayer(team, MOD));
        assertEquals("not in the crew", 0, SlowMode.forPlayer(team, MATE));
        assertEquals("off", 0, SlowMode.forPlayer(crew("0"), ME));
        assertEquals(0, SlowMode.forPlayer(null, ME));
        assertEquals(0, SlowMode.forPlayer(team, null));
    }

    @Test
    public void theLeadersStepsAreTheServices() {
        assertEquals(Arrays.asList(0, 5, 10, 30), Arrays.asList(SlowMode.STEPS[0], SlowMode.STEPS[1], SlowMode.STEPS[2], SlowMode.STEPS[3]));
        assertEquals(10, SlowMode.step(10));
        assertEquals("not a step: off", 0, SlowMode.step(7));
        assertEquals(3, SlowMode.index(30));
        assertEquals(0, SlowMode.index(60));
    }

    @Test
    public void theLightListsSlowModeLiesOverAnOlderCopy() throws Exception {
        CrewCache cache = new CrewCache(tmp.getRoot(), ME);
        cache.store(CREW, "\"4-20755\"", 4, 20755, Json.parseObject(team("0")));
        JsonObject friends = Json.parseObject("{\"friends\":[],\"incoming\":[],\"outgoing\":[],\"blocked\":[],"
                + "\"limits\":{\"friends\":200,\"teamMembers\":30},\"crews\":[{\"team\":{\"id\":\"" + CREW + "\",\"name\":\"Gece Kartalları\","
                + "\"tag\":\"GK\",\"emblem\":\"flame\",\"color\":\"teal\",\"frame\":\"shield\",\"hidden\":false,\"members\":3,\"rev\":5,"
                + "\"slow\":10},\"invited\":[],\"notifications\":{\"calls\":true,\"muted\":false,\"muteUntil\":null}}],"
                + "\"crewLimit\":5,\"teamInvites\":[],\"teamFeatures\":[\"multi\",\"details\",\"slow\"]}");
        CrewDetails.resolve(friends, cache, (crewId, etag) -> {
            throw SocialProblem.unavailable("offline in the test");
        }, 20756);
        SocialModel.Crew read = SocialModel.FriendsData.read(friends).crews.get(0);
        assertEquals("the copy's members", 3, read.team.members.size());
        assertEquals("the list's slow mode", 10, read.team.slow);
    }

    @Test
    public void theGateNeedsCrewChatAndTheServicesWord() {
        HashSet<String> features = new HashSet<>(Arrays.asList("push", "chat"));
        assertTrue(SocialGate.of(true, true, true, 30, new HashSet<>(Collections.singletonList("slow")), features, true, true).slow);
        assertFalse("without crew chat", SocialGate.of(true, true, false, 30, new HashSet<>(Collections.singletonList("slow")), features,
                true, true).slow);
        assertFalse("an older service", SocialGate.of(true, true, true, 30, new HashSet<>(Collections.singletonList("multi")), features,
                true, true).slow);
        assertFalse(SocialGate.OFF.slow);
    }

    // ---- when the wait ends ----

    @Test
    public void theWaitCountsFromThePlayersNewestMessage() {
        ChatBook book = new ChatBook(ME);
        // The hub's clock is 5 s ahead of the phone's: the hub took the player's message at the phone's T - 3 s.
        long offset = 5_000;
        book.apply(frame("chat", msg(T - 10_000, MATE, "Selam")));
        book.apply(frame("chat", msg(T + 2_000, ME, "Geliyorum")));
        book.apply(frame("chat", msg(T + 9_000, LEAD, "Hadi")));
        List<ChatBook.Entry> entries = book.entries(CHAT);
        long now = T + 1_000;
        // The hub's time in the phone's clock, a second later (its clock comes in whole seconds).
        long converted = T + 2_000 - offset + SlowMode.CLOCK_MARGIN_MS;
        assertEquals(converted + 10_000, SlowMode.until(entries, ME, 10, offset, 0, 0, 0, now));
        assertEquals("nothing waits without a slow mode", 0, SlowMode.until(entries, ME, 0, offset, 0, 0, 0, now));
        assertEquals("nor for someone with no message here", 0, SlowMode.until(entries, MOD, 10, offset, 0, 0, 0, now));
        // The message this phone sent counts from when its answer came (200 ms after the hub took it).
        assertEquals(T - 2_800 + 10_000, SlowMode.until(entries, ME, 10, offset, T + 2_000, T - 2_800, 0, now));
        // A resend answered with a message taken long before counts from the message, not from the late answer.
        assertEquals(converted + 10_000, SlowMode.until(entries, ME, 10, offset, T + 2_000, T + 40_000, 0, T + 41_000));
        // An answer without its message counts from when it came.
        assertEquals(T + 20_000 + 10_000, SlowMode.until(entries, ME, 10, offset, 0, 0, T + 20_000, T + 21_000));
    }

    @Test
    public void aClockBehindTheHubsNeverMakesTheWaitLonger() {
        ChatBook book = new ChatBook(ME);
        // Before the first hello the offset is 0, while the hub is five minutes ahead: the message cannot be newer than now.
        book.apply(frame("chat", msg(T + 300_000, ME, "Selam")));
        assertEquals(T + 1_000 + 10_000, SlowMode.until(book.entries(CHAT), ME, 10, 0, 0, 0, 0, T + 1_000));
    }

    @Test
    public void aMessageNotGoneYetHoldsTheWaitAFailedOneDoesNot() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", msg(T, ME, "Bir")));
        ChatBook.Outbox waiting = book.write(CHAT, "İki", "n1", T + 30_000);
        assertEquals(T + 30_000 + 5_000, SlowMode.until(book.entries(CHAT), ME, 5, 0, 0, 0, 0, T + 30_000));
        // Written offline, it has not gone a minute later: the wait does not run down before it goes.
        assertEquals(T + 90_000 + 5_000, SlowMode.until(book.entries(CHAT), ME, 5, 0, 0, 0, 0, T + 90_000));
        book.sending(waiting.nonce, T + 90_000);
        assertEquals("on its way: the same", T + 90_500 + 5_000, SlowMode.until(book.entries(CHAT), ME, 5, 0, 0, 0, 0, T + 90_500));
        book.notSent(waiting.nonce, "chat_rejected", "words", 0, T + 90_600);
        assertEquals("the failed one does not count", T + SlowMode.CLOCK_MARGIN_MS + 5_000,
                SlowMode.until(book.entries(CHAT), ME, 5, 0, 0, 0, 0, T + 90_600));
        // Held back until a moment (a short rate limit, a retry during the wait): it goes then, the wait counts from there.
        ChatBook.Outbox held = book.write(CHAT, "Üç", "n2", T + 100_000);
        book.sending(held.nonce, T + 100_000);
        book.notSent(held.nonce, "rate_limited", null, 3, T + 100_000);
        assertEquals(T + 103_000 + 5_000, SlowMode.until(book.entries(CHAT), ME, 5, 0, 0, 0, 0, T + 100_100));
        // Taken by the hub without its message (it comes with the next sync): from when it was written, at most now.
        ChatBook.Outbox taken = book.write(CHAT, "Dört", "n3", T + 110_000);
        book.sending(taken.nonce, T + 110_000);
        book.sent(taken.nonce, null);
        book.discard("n2");
        assertEquals(T + 110_000 + 5_000, SlowMode.until(book.entries(CHAT), ME, 5, 0, 0, 0, 0, T + 120_000));
    }

    @Test
    public void aDeletedMessageOfThePlayersStillCounts() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", msg(T, ME, "Yanlış")));
        JsonObject gone = new JsonObject();
        gone.addProperty("t", "chat_deleted");
        gone.addProperty("conv", CHAT);
        gone.addProperty("id", T);
        gone.addProperty("by", ME);
        book.apply(gone);
        assertNotNull(book.entries(CHAT).get(0).tombstone);
        assertEquals("the hub still remembers it", T + SlowMode.CLOCK_MARGIN_MS + 30_000,
                SlowMode.until(book.entries(CHAT), ME, 30, 0, 0, 0, 0, T + 5_000));
    }

    @Test
    public void theSecondsRoundUpAndTheClockTicksWhereTheyChange() {
        assertEquals(10, SlowMode.left(10_000, 0));
        assertEquals(10, SlowMode.left(10_000, 1));
        assertEquals(1, SlowMode.left(10_000, 9_001));
        assertEquals(0, SlowMode.left(10_000, 10_000));
        assertEquals(0, SlowMode.left(0, 5));
        assertEquals(1_000, SlowMode.nextTick(10_000, 0));
        assertEquals(999, SlowMode.nextTick(10_000, 1));
        assertEquals(500, SlowMode.nextTick(10_000, 8_500));
        assertEquals("the last tick lands on the end", 300, SlowMode.nextTick(10_000, 9_700));
        assertEquals("nothing waits: no clock", -1, SlowMode.nextTick(10_000, 10_000));
    }

    // ---- the hub's refusal ----

    @Test
    public void theHubsSlowRefusalIsItsOwnFailureAndNeverGoesAgainByItself() {
        assertEquals(ChatBook.Failure.SLOW, ChatBook.failure("rate_limited", null, 12, "slow"));
        assertEquals("an item this phone does not know keeps the old rule", ChatBook.Failure.RATE,
                ChatBook.failure("rate_limited", null, 12, "something"));
        ChatBook book = new ChatBook(ME);
        book.write(CHAT, "Selam", "s1", T);
        book.sending("s1", T);
        book.notSent("s1", "rate_limited", null, 12, T + 100, "slow", null);
        assertEquals(ChatBook.Outbox.State.FAILED, book.outbox("s1").state);
        assertEquals(ChatBook.Failure.SLOW, book.outbox("s1").failure);
        assertTrue(book.due(T + 60_000).isEmpty());
        // Without an item the short rate limit still waits and goes by itself.
        book.write(CHAT, "Yine", "s2", T);
        book.sending("s2", T);
        book.notSent("s2", "rate_limited", null, 2, T + 100);
        assertEquals(ChatBook.Outbox.State.WAITING, book.outbox("s2").state);
    }

    @Test
    public void aRetryDuringTheWaitGoesOnceItIsOver() {
        ChatBook book = new ChatBook(ME);
        book.write(CHAT, "Selam", "r1", T);
        book.sending("r1", T);
        book.notSent("r1", "rate_limited", null, 12, T + 100, "slow", null);
        book.retry("r1", "fresh1", T + 1_000, T + 12_100);
        assertEquals(ChatBook.Outbox.State.WAITING, book.outbox("r1").state);
        assertTrue(book.due(T + 12_000).isEmpty());
        assertEquals(T + 12_100, book.nextDue(T + 12_000));
        assertEquals(1, book.due(T + 12_100).size());
        // Without a wait it goes at once, as before.
        book.write(CHAT, "Tekrar", "r2", T);
        book.sending("r2", T);
        book.notSent("r2", "chat_rejected", "phone", 0, T + 100);
        book.retry("r2", "fresh2", T + 200);
        assertEquals(0, book.outbox("r2").notBefore);
    }

    // ---- the crowded crew ----

    private static SocialState state() {
        SocialState s = new SocialState();
        s.setMe(SocialModel.Me.read(Json.parseObject("{\"id\":\"" + ME + "\",\"username\":\"x7ender\",\"displayName\":\"x7Ender\","
                + "\"avatar\":\"discord\",\"friendCode\":\"PRJ-AAAA-BBBB\",\"status\":\"online\",\"privacy\":{},\"notices\":[]}")));
        s.applyFriends(SocialModel.FriendsData.read(Json.parseObject("{\"friends\":[],\"incoming\":[],\"outgoing\":[],\"blocked\":[],"
                + "\"limits\":{\"friends\":200,\"teamMembers\":30,\"teamModerators\":4},\"teamInvited\":[],"
                + "\"crews\":[{\"team\":" + team("5") + ",\"invited\":[],\"notifications\":{\"muted\":false,\"muteUntil\":null}}],"
                + "\"crewLimit\":5,\"teamInvites\":[],\"teamFeatures\":[\"mute\",\"multi\",\"slow\"],"
                + "\"teamNotifications\":{\"calls\":true,\"muted\":false,\"muteUntil\":null},\"features\":[\"push\",\"chat\"]}")));
        return s;
    }

    private static JsonObject crowded(String crew, boolean muted, boolean flag) {
        return Json.parseObject("{\"t\":\"crew_notifications\",\"crew\":\"" + crew + "\",\"notifications\":{\"muted\":" + muted
                + ",\"muteUntil\":null}" + (flag ? ",\"crowded\":true" : "") + "}");
    }

    @Test
    public void aCrowdedCrewMutedOnJoiningSaysSoOnce() {
        SocialState s = state();
        List<SocialState.Notice> notices = s.apply(crowded(CREW, true, true), NOW);
        assertEquals(1, notices.size());
        assertEquals(SocialState.Notice.Kind.CROWDED, notices.get(0).kind);
        assertEquals(CREW, notices.get(0).crewId);
        assertEquals("Gece Kartalları", notices.get(0).teamName);
        assertTrue("the mute is the player's own, as any", s.crew(CREW).mute.mutedAt(NOW));
        assertTrue("a mute from another device says nothing", s.apply(crowded(CREW, true, false), NOW).isEmpty());
        assertTrue("an unmute says nothing", s.apply(crowded(CREW, false, true), NOW).isEmpty());
        assertTrue("a crew not in the list yet says nothing", s.apply(crowded("4d5e6f7a8b9c0d1e", true, true), NOW).isEmpty());
        assertEquals(5, s.crew(CREW).team.slow);
    }
}
