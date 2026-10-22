package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M20: answers recorded from a local copy of the friends service at projects-studio 2acc036 (SOC7:
 * several crews, calls from a server, chat, reports, limited accounts and moderation; m20
 * tools/capture.mjs, throwaway players): the phone reads every one of them as the README says.
 */
public class Soc7FixturesTest {
    private static final File DIR = new File("src/test/resources/social");
    private static final String A = "100000000000000011";
    private static final String B = "100000000000000012";
    private static final String YAYLA = "ba126fd6c93f8827";
    private static final String GECE = "371df93793fb9e86";
    private static final String EJDER = "833148b7764eafc5";

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File(DIR, name).toPath()), StandardCharsets.UTF_8);
    }

    private static SocialState state() throws Exception {
        SocialState state = new SocialState();
        state.setMe(SocialModel.Me.read(Json.parseObject(read("me-plain.json"))));
        state.applyFriends(SocialModel.FriendsData.read(Json.parseObject(read("friends-soc7.json"))));
        return state;
    }

    @Test
    public void theListHoldsEveryCrewInThePlayersOrder() throws Exception {
        SocialState state = state();
        assertEquals(Arrays.asList(YAYLA, GECE, EJDER), state.crewIds());
        assertEquals(YAYLA, state.team().id);
        assertEquals(5, state.crewLimit());
        assertFalse(state.atCrewLimit());
        assertTrue(state.crew(EJDER).mute.muted);
        assertTrue(state.crew(EJDER).invited.contains("100000000000000013"));
        assertEquals(1, state.teamInvites().size());
        SocialModel.Me me = state.me();
        assertTrue(me.moderation);
        assertNull(me.limited);
        assertTrue(me.notices.isEmpty());
        SocialGate gate = SocialGate.of(true, true, true, state.limits().teamMembers, state.teamFeatures(), state.features(), true,
                me.moderation);
        assertTrue(gate.multi && gate.callServer && gate.chat && gate.push && gate.reports && gate.mute);
        // GET /v1/teams says the same crews.
        SocialModel.FriendsData teams = SocialModel.FriendsData.read(Json.parseObject(read("teams-soc7.json")));
        assertEquals(3, teams.crews.size());
        assertEquals(5, teams.crewLimit);
    }

    @Test
    public void aNewDiscordAccountIsLimitedAndHasItsWarning() throws Exception {
        SocialModel.Me me = SocialModel.Me.read(Json.parseObject(read("me-limited.json")));
        assertNotNull(me.limited);
        assertTrue(me.limited.has(SocialModel.Limited.CREW_FOUND));
        assertEquals(5, me.limited.perDay(SocialModel.Limited.CREW_INVITES));
        assertEquals(10, me.limited.perDay(SocialModel.Limited.CODE_LOOKUPS));
        assertEquals(100, me.limited.perDay(SocialModel.Limited.CHAT_MESSAGES));
        assertEquals(1, me.notices.size());
        assertEquals("1792590982331964", me.notices.get(0).id);
        assertEquals(SocialModel.ModNotice.WARNING, me.notices.get(0).kind);
    }

    @Test
    public void theSocketsFramesReplayWhole() throws Exception {
        SocialState state = state();
        ChatBook book = new ChatBook(A);
        book.write(ChatConv.crew(YAYLA), "Geliyrum", "q8Zr3kT0aWbc", 1);
        List<SocialState.Notice> notices = new ArrayList<>();
        List<String> more = new ArrayList<>();
        long now = 1792590973L;
        for (String line : read("socket-soc7-a.jsonl").split("\n")) {
            if (line.trim().isEmpty()) continue;
            JsonObject frame = Json.parseObject(line);
            assertNotNull(line, frame);
            if (frame.has("_")) continue;
            String type = Json.str(frame, "t", "");
            if (type.startsWith("chat") && !type.equals("chat_state") && !type.equals("chat_synced")) {
                book.apply(frame);
                continue;
            }
            if (type.equals("chat_synced")) {
                more.add(book.synced(frame, 1792590980000L));
                continue;
            }
            if (type.equals("ack") && frame.has("msg")) {
                JsonObject msg = Json.obj(frame, "msg");
                // The send's answer, then the edit's (the app tells them apart by rid; here by editedAt).
                if (Json.has(msg, "editedAt")) book.edited(Json.str(msg, "conv"), Json.num(msg, "id", 0), msg);
                else if (book.outbox(Json.str(msg, "nonce", "")) != null) book.sent(Json.str(msg, "nonce"), msg);
                else {
                    JsonObject live = new JsonObject();
                    live.addProperty("t", "chat");
                    live.add("msg", msg);
                    book.apply(live);
                }
                continue;
            }
            notices.addAll(state.apply(frame, now));
        }
        // CALL1 + TEAM4: the call named two crews, in the player's order; it moved to another server, then ended ("server").
        List<SocialState.Notice> calls = new ArrayList<>();
        List<SocialState.Notice> ended = new ArrayList<>();
        for (SocialState.Notice n : notices) {
            if (n.kind == SocialState.Notice.Kind.CALL) calls.add(n);
            if (n.kind == SocialState.Notice.Kind.CALL_ENDED) ended.add(n);
        }
        assertEquals(2, calls.size());
        assertFalse(calls.get(0).moved);
        assertEquals(SocialModel.Call.Place.PROJECTS, calls.get(0).call.place());
        assertTrue(calls.get(1).moved);
        assertEquals("mc.example.com", calls.get(1).call.otherHost());
        assertEquals(1, ended.size());
        assertEquals("server", ended.get(0).reason);
        assertTrue(state.calls(now).isEmpty());
        // The order from the player's other session, and one crew muted there.
        assertEquals(Arrays.asList(GECE, YAYLA, EJDER), state.crewIds());
        assertTrue(state.crew(GECE).mute.mutedAt(now));
        // MOD1: the staff reset the crew's texts, a warning came live, then the account was suspended.
        assertEquals("Ekip 9E86", state.crew(GECE).team.name);
        assertTrue(state.crew(GECE).team.textLocked(now));
        boolean warned = false;
        boolean restricted = false;
        for (SocialState.Notice n : notices) {
            warned |= n.kind == SocialState.Notice.Kind.MOD_NOTICE;
            restricted |= n.kind == SocialState.Notice.Kind.RESTRICTED;
        }
        assertTrue(warned && restricted);
        assertEquals(Long.valueOf(1792677380L), state.restrictedUntil());
        // CHAT1: the second hello said the player was muted; the sync came in two pages.
        assertTrue(state.chat().mutedAt(1792590980L));
        assertEquals(Arrays.asList("d:100000000000000011:100000000000000016", null), more);
        List<ChatBook.Entry> crewChat = book.entries(ChatConv.crew(YAYLA));
        // B's edited message and the player's own (sent, then edited) — the waiting one became the service's.
        assertNull(book.outbox("q8Zr3kT0aWbc"));
        ChatBook.Entry mine = null;
        for (ChatBook.Entry e : crewChat) if (e.message != null && e.message.from.equals(A)) mine = e;
        assertNotNull(mine);
        assertEquals("Geliyorum", mine.text());
        assertTrue(mine.message.edited());
        assertEquals("n:q8Zr3kT0aWbc", mine.key());
        // B deleted the first direct message: it stays as B's tombstone (FIX2); the player's two stayed.
        List<ChatBook.Entry> direct = book.entries(ChatConv.direct(A, B));
        int buried = 0;
        for (ChatBook.Entry e : direct) {
            if (e.deleted()) {
                buried++;
                assertEquals(B, e.tombstone.from);
                assertTrue(e.tombstone.bySender());
                assertEquals("", e.text());
            } else {
                assertEquals(A, e.message.from);
            }
        }
        assertEquals(1, buried);
        assertEquals(3, direct.size());
        // Ten conversations came with the sync (crews and friends' friends' direct ones).
        assertTrue(book.summaries().size() >= 8);
    }

    @Test
    public void anOldSocketSeesTheCallsFirstMeanings() throws Exception {
        SocialState state = state();
        List<SocialModel.Call.Place> places = new ArrayList<>();
        for (String line : read("socket-old-a.jsonl").split("\n")) {
            JsonObject frame = Json.parseObject(line);
            if (frame == null || frame.has("_")) continue;
            for (SocialState.Notice n : state.apply(frame, 1792590973L)) if (n.call != null) places.add(n.call.place());
        }
        assertEquals(SocialModel.Call.Place.PROJECTS, places.get(0));
        // The move to another server reads as "Hazır mısın?" there: kind ready, no server.
        assertTrue(places.size() == 1 || places.get(1) == SocialModel.Call.Place.READY);
    }

    @Test
    public void theErrorsMeanWhatTheReadmeSays() throws Exception {
        JsonObject errors = Json.parseObject(read("errors-soc7.json"));
        Map<String, SocialProblem> problems = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : errors.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            int status = (int) Json.num(o, "status", 0);
            if (status >= 400) problems.put(e.getKey(), SocialProblem.of(status, o.get("body").toString(), null, false));
        }
        assertEquals(SocialProblem.Kind.CREWS_CHANGED, problems.get("crews_changed").kind);
        assertEquals(SocialProblem.Kind.TEXT_LOCKED, problems.get("team_text_locked").kind);
        assertEquals(Long.valueOf(1793195780L), problems.get("team_text_locked").until);
        // A suspended account's old session is simply signed out; the next sign-in says why and until when.
        assertEquals(SocialProblem.Kind.SIGNED_OUT, problems.get("account_restricted_request").kind);
        assertTrue(problems.get("account_restricted_signin").restricted());
        assertEquals(Long.valueOf(1792677380L), problems.get("account_restricted_signin").until);
        assertEquals(SocialProblem.Kind.CREW_LIMIT, problems.get("limit_crews").kind);
        assertEquals(SocialProblem.Kind.ALREADY_IN_CREW, problems.get("already_in_team_without_multi").kind);
        SocialProblem found = problems.get("account_limited_crew_found");
        assertEquals(SocialProblem.Kind.ACCOUNT_LIMITED, found.kind);
        assertEquals(SocialModel.Limited.CREW_FOUND, found.item);
        SocialProblem invites = problems.get("account_limited_crew_invites");
        assertEquals(SocialModel.Limited.CREW_INVITES, invites.item);
        assertEquals(25418, invites.retryAfter);
        assertEquals(SocialProblem.Kind.TARGET_GONE, problems.get("report_target_not_found").kind);
        assertEquals(SocialProblem.Kind.OWN_CODE, problems.get("report_self").kind);
        // At the limit a lookup with "multi" says so.
        JsonObject lookup = errors.getAsJsonObject("lookup_at_limit").getAsJsonObject("body");
        assertEquals("limit", Json.str(lookup, "relation"));
        assertTrue(Json.bool(errors.getAsJsonObject("report_duplicate").getAsJsonObject("body"), "duplicate", false));
    }

    @Test
    public void thePushesOfCallsAndMessagesRead() throws Exception {
        List<PushMessage> pushes = new ArrayList<>();
        for (String line : read("pushes-soc7.jsonl").split("\n")) {
            JsonObject o = Json.parseObject(line);
            if (o == null) continue;
            Map<String, String> data = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("data").entrySet()) data.put(e.getKey(), e.getValue().getAsString());
            PushMessage push = PushMessage.parse(data);
            assertNotNull(line, push);
            pushes.add(push);
        }
        assertEquals(PushMessage.CALL, pushes.get(0).type);
        assertEquals("projects", pushes.get(0).where);
        assertEquals(Arrays.asList(YAYLA, EJDER), pushes.get(0).crews);
        assertEquals(PushMessage.CALL_END, pushes.get(1).type);
        PushMessage message = pushes.get(2);
        assertEquals(PushMessage.MESSAGE, message.type);
        assertEquals(B, message.sender);
        assertEquals("Ece", message.name);
        assertEquals(PushMessage.Decision.Action.SHOW, message.decide(message.to, Long.parseLong("1792590980"),
                Collections.<String>emptySet(), Collections.singleton(PushMessage.MESSAGE), true).action);
    }
}
