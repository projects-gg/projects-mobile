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
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.cloud.CloudNotice;

/**
 * SOC9 (soc9.md §1-2): the Minecraft version friends see ("Projects'te · 1.21.4"), sent by the game only while playing
 * and never while invisible; the push tag that a push's {@code to} carries instead of the Discord id, kept per session.
 */
public class Soc9Test {
    private static final String ME = SocialStateTest.ME;
    private static final String FRIEND = SocialStateTest.AYSE;
    private static final String OTHER = SocialStateTest.KAAN;
    private static final long NOW = SocialStateTest.NOW;
    private static final String TAG = "q7Lm2Xc_9-RtYwB3nKpZ0a";
    private static final String OTHER_TAG = "Zz9yXw8vUt7sRq6pOn5m-_";
    private static final String SESSION = "pls_test_session_one";
    private static final Set<String> NONE = Collections.emptySet();
    private static final Set<String> ALL = new HashSet<>(Arrays.asList("request", "invite", "call", "message"));

    private static Map<String, String> data(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    // ---- the version ----

    @Test
    public void versionsAreVanillaIdsTheServiceTakes() {
        assertEquals("1.21.4", GameVersion.wire("1.21.4"));
        assertEquals("26.2", GameVersion.wire("26.2"));
        assertEquals("24w14a", GameVersion.wire("24w14a"));
        assertEquals("1.21-pre1", GameVersion.wire("1.21-pre1"));
        assertNull("a space", GameVersion.wire("Fabric 1.21"));
        assertNull("25 characters", GameVersion.wire("1234567890123456789012345"));
        assertNull(GameVersion.wire(""));
        assertNull(GameVersion.wire(null));
        assertEquals("a loader version runs its vanilla id", "1.21.4", GameVersion.vanilla("fabric-loader-0.16.10-1.21.4", "1.21.4"));
        assertEquals("1.21.4", GameVersion.vanilla("1.21.4", null));
        assertEquals("1.21.4", GameVersion.vanilla("1.21.4", ""));
        assertNull("a loader's own id never goes", GameVersion.vanilla("fabric-loader-0.16.10-1.21.4-and-more", null));
        assertNull(GameVersion.vanilla(null, null));
    }

    @Test
    public void friendsSeeTheVersionAfterThePlace() {
        SocialState state = SocialStateTest.signedIn(true);
        state.apply(SocialStateTest.json("{\"t\":\"hello\",\"v\":1,\"status\":\"online\",\"friends\":[{\"id\":\"" + FRIEND
                + "\",\"state\":\"playing\",\"status\":\"online\",\"game\":{\"where\":\"projects\",\"ver\":\"1.21.4\"}}]"
                + ",\"team\":[],\"invites\":[],\"calls\":[],\"now\":" + NOW + "}"), NOW);
        SocialModel.Presence presence = state.presence(FRIEND);
        assertTrue(presence.onProjects());
        assertEquals("1.21.4", presence.ver);
        assertEquals("Projects'te · 1.21.4", presence.withVersion("Projects'te"));

        state.apply(SocialStateTest.json("{\"t\":\"presence\",\"presence\":{\"id\":\"" + FRIEND + "\",\"state\":\"playing\""
                + ",\"status\":\"online\",\"game\":{\"where\":\"other\",\"host\":\"mc.example.net\",\"ver\":\"26.2\"}}}"), NOW);
        presence = state.presence(FRIEND);
        assertEquals("mc.example.net", presence.host);
        assertEquals("mc.example.net sunucusunda · 26.2", presence.withVersion("mc.example.net sunucusunda"));

        state.apply(SocialStateTest.json("{\"t\":\"presence\",\"presence\":{\"id\":\"" + FRIEND + "\",\"state\":\"playing\""
                + ",\"status\":\"online\",\"game\":{\"where\":\"singleplayer\"}}}"), NOW);
        assertNull("no version said: the place alone", state.presence(FRIEND).ver);
        assertEquals("Tek oyunculu", state.presence(FRIEND).withVersion("Tek oyunculu"));

        state.apply(SocialStateTest.json("{\"t\":\"presence\",\"presence\":{\"id\":\"" + FRIEND + "\",\"state\":\"playing\""
                + ",\"status\":\"online\",\"game\":{\"ver\":\"1.21 <b>\"}}}"), NOW);
        assertNull("a version the service would not take is not shown", state.presence(FRIEND).ver);

        state.apply(SocialStateTest.json("{\"t\":\"presence\",\"presence\":{\"id\":\"" + FRIEND + "\",\"state\":\"launcher\""
                + ",\"status\":\"online\",\"game\":{\"ver\":\"1.21.4\"}}}"), NOW);
        assertNull("only while playing", state.presence(FRIEND).ver);
        assertEquals("Launcher'da", state.presence(FRIEND).withVersion("Launcher'da"));
    }

    @Test
    public void theGameSendsItsVersionOnlyWhenVisible() {
        GameActivity.Status server = GameActivity.Status.server("mc.example.net");
        JsonObject sent = InGameRules.statusMessage(server, "1.21.4", false);
        assertEquals("status", Json.str(sent, "t"));
        assertEquals("playing", Json.str(sent, "state"));
        assertEquals("mc.example.net", Json.str(sent, "host"));
        assertEquals("1.21.4", Json.str(sent, "ver"));

        JsonObject hidden = InGameRules.statusMessage(server, "1.21.4", true);
        assertFalse("invisible: never the version", hidden.has("ver"));
        assertEquals("mc.example.net", Json.str(hidden, "host"));

        JsonObject single = InGameRules.statusMessage(GameActivity.Status.SINGLEPLAYER, "26.2", false);
        assertTrue(Json.bool(single, "singleplayer", false));
        assertEquals("26.2", Json.str(single, "ver"));

        assertFalse(InGameRules.statusMessage(GameActivity.Status.PLAYING, null, false).has("ver"));
        assertFalse(InGameRules.statusMessage(GameActivity.Status.PLAYING, "Fabric 1.21", false).has("ver"));
        assertFalse(InGameRules.statusMessage(GameActivity.Status.PLAYING, "1.21.4", false).has("host"));
    }

    // ---- the push tag ----

    @Test
    public void theTagIsKeptForItsSessionOnly() {
        assertTrue(PushTag.valid(TAG));
        assertFalse("21 characters", PushTag.valid(TAG.substring(1)));
        assertFalse("23 characters", PushTag.valid(TAG + "a"));
        assertFalse("not base64url", PushTag.valid("q7Lm2Xc+9/RtYwB3nKpZ0a"));
        assertFalse(PushTag.valid(null));
        assertFalse("an id is no tag", PushTag.valid(ME));

        String record = PushTag.record(SESSION, TAG);
        assertFalse("the session token is never kept beside it", record.contains(SESSION));
        assertEquals(TAG, PushTag.tagFor(record, SESSION));
        assertNull("another sign-in: not this tag", PushTag.tagFor(record, "pls_test_session_two"));
        assertNull(PushTag.tagFor(record, null));
        assertEquals("an answer without a tag clears it", "", PushTag.record(SESSION, null));
        assertEquals("", PushTag.record(SESSION, "not a tag"));
        assertEquals("", PushTag.record(null, TAG));
        assertNull(PushTag.tagFor("", SESSION));
        assertNull(PushTag.tagFor("damaged", SESSION));
        assertNull(PushTag.tagFor(PushTag.fingerprint(SESSION) + "|short", SESSION));
    }

    @Test
    public void aPushIsOursByTheSessionsTagOrTheId() {
        assertTrue(PushTag.addressedTo(ME, ME, null));
        assertTrue(PushTag.addressedTo(ME, ME, TAG));
        assertTrue(PushTag.addressedTo(TAG, ME, TAG));
        assertFalse("no tag kept yet", PushTag.addressedTo(TAG, ME, null));
        assertFalse("another session's tag", PushTag.addressedTo(OTHER_TAG, ME, TAG));
        assertFalse("another account", PushTag.addressedTo(OTHER, ME, TAG));
        assertFalse("signed out", PushTag.addressedTo(TAG, null, TAG));
        assertFalse(PushTag.addressedTo(null, ME, TAG));
    }

    @Test
    public void everyKindOfPushTakesTheTag() {
        PushMessage request = PushMessage.parse(data("v", "1", "type", "request", "sender", FRIEND, "name", "Blaze_Runner",
                "at", String.valueOf(NOW), "to", TAG));
        assertNotNull(request);
        assertEquals(PushMessage.Decision.Action.SHOW, request.decide(ME, TAG, NOW, NONE, ALL, true, NONE).action);
        assertEquals(PushMessage.Decision.Action.DROP, request.decide(ME, null, NOW, NONE, ALL, true, NONE).action);
        assertEquals(PushMessage.Decision.Action.DROP, request.decide(ME, OTHER_TAG, NOW, NONE, ALL, true, NONE).action);
        assertEquals(PushMessage.Decision.Action.DROP, request.decide(null, TAG, NOW, NONE, ALL, true, NONE).action);
        assertEquals("the old form still drops it", PushMessage.Decision.Action.DROP,
                request.decide(ME, NOW, NONE, ALL, true).action);

        PushMessage end = PushMessage.parse(data("v", "1", "type", "call_end", "call", "3f9c0a1b2c3d4e5f", "to", TAG));
        assertNotNull(end);
        assertEquals(PushMessage.Decision.Action.CANCEL, end.decide(ME, TAG, NOW, NONE, ALL, true, NONE).action);
        assertEquals(PushMessage.Decision.Action.DROP, end.decide(ME, OTHER_TAG, NOW, NONE, ALL, true, NONE).action);

        assertNull("neither an id nor a tag", PushMessage.parse(data("v", "1", "type", "request", "sender", FRIEND, "to", "x")));
    }

    @Test
    public void aTaggedMessageMayLeaveItsConversationOut() {
        String conv = ChatConv.direct(ME, FRIEND);
        PushMessage named = PushMessage.parse(data("v", "1", "type", "message", "sender", FRIEND, "name", "Blaze_Runner",
                "conv", conv, "at", String.valueOf(NOW), "to", TAG));
        assertNotNull(named);
        assertEquals(PushMessage.Decision.Action.SHOW, named.decide(ME, TAG, NOW, NONE, ALL, true, NONE).action);

        PushMessage bare = PushMessage.parse(data("v", "1", "type", "message", "sender", FRIEND, "name", "Blaze_Runner",
                "at", String.valueOf(NOW), "to", TAG));
        assertNotNull(bare);
        assertNull(bare.conv);
        assertEquals("made from the signed-in id and the sender", conv, bare.conversation(ME));
        assertEquals(PushMessage.Decision.Action.SHOW, bare.decide(ME, TAG, NOW, NONE, ALL, true, NONE).action);
        assertEquals("muted by the conversation it makes", PushMessage.Decision.Action.DROP,
                bare.decide(ME, TAG, NOW, NONE, ALL, true, Collections.singleton(conv)).action);

        PushMessage elsewhere = PushMessage.parse(data("v", "1", "type", "message", "sender", FRIEND,
                "conv", ChatConv.direct(FRIEND, OTHER), "at", String.valueOf(NOW), "to", TAG));
        assertNotNull("the sender is in it", elsewhere);
        assertEquals("not the signed-in player's conversation", PushMessage.Decision.Action.DROP,
                elsewhere.decide(ME, TAG, NOW, NONE, ALL, true, NONE).action);
        assertNull("the sender is not in it", PushMessage.parse(data("v", "1", "type", "message", "sender", FRIEND,
                "conv", ChatConv.direct(ME, OTHER), "to", TAG)));

        PushMessage own = PushMessage.parse(data("v", "1", "type", "message", "sender", ME, "at", String.valueOf(NOW), "to", TAG));
        assertNotNull(own);
        assertNull(own.conversation(ME));
        assertEquals(PushMessage.Decision.Action.DROP, own.decide(ME, TAG, NOW, NONE, ALL, true, NONE).action);

        PushMessage byId = PushMessage.parse(data("v", "1", "type", "message", "sender", FRIEND, "at", String.valueOf(NOW), "to", ME));
        assertNotNull(byId);
        assertEquals("an id's push without conv gets it at once", conv, byId.conv);
        assertEquals(PushMessage.Decision.Action.SHOW, byId.decide(ME, null, NOW, NONE, ALL, true, NONE).action);
    }

    @Test
    public void cloudNoticesTakeTheTagToo() {
        CloudNotice notice = CloudNotice.fromPush(data("v", "1", "type", "afk", "id", "n1", "event", "found", "kind", "afk_info",
                "acct", "a_3MN7PQ4R8ST2VW5X", "name", "Blaze_Runner", "at", String.valueOf(NOW), "to", TAG));
        assertNotNull(notice);
        assertEquals(CloudNotice.Action.SHOW, notice.decide(ME, TAG, NOW).action);
        assertEquals(CloudNotice.Action.DROP, notice.decide(ME, null, NOW).action);
        assertEquals(CloudNotice.Action.DROP, notice.decide(ME, OTHER_TAG, NOW).action);
        assertEquals(CloudNotice.Action.DROP, notice.decide(null, TAG, NOW).action);
        assertEquals(CloudNotice.Action.DROP, notice.decide(ME, NOW).action);

        CloudNotice byId = CloudNotice.fromPush(data("v", "1", "type", "afk", "id", "n2", "event", "found", "kind", "afk_info",
                "at", String.valueOf(NOW), "to", ME));
        assertNotNull(byId);
        assertEquals(CloudNotice.Action.SHOW, byId.decide(ME, TAG, NOW).action);
    }
}
