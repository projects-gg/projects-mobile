package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.cloud.movement.MoveFrames;
import gg.projects.mobile.cloud.movement.MoveOp;
import gg.projects.mobile.social.Json;

/**
 * Movement on the phone (services/cloud-afk README "Movement"): the operations it sends held to the contract's shape
 * vectors ({@code move-op-v1.json}), the stream's frames, the account's switch and its setting.
 */
public class CloudMovementTest {
    private static final JsonObject VECTORS = Vectors.load("cloud/move-op-v1.json");
    private static final String ACCOUNT = "{\"id\":\"a_7KQ2MZ3P9TW4XB1C\",\"name\":\"AfkBot_1\",\"host\":\"kz1\","
            + "\"server\":{\"host\":\"play.projects.gg\",\"port\":0},\"config\":{\"v\":1,\"owner\":\"Kaan_07\","
            + "\"antiAfk\":{\"enabled\":true,\"delayMin\":60,\"delayMax\":90},\"commands\":[],\"rules\":[]%MOVE%},"
            + "\"features\":{\"inventory\":{\"mode\":\"full\",\"on\":true}%FEAT%}}";

    private static CloudModel.Account account(String config, String features) {
        return CloudModel.Account.read(Vectors.json(ACCOUNT.replace("%MOVE%", config).replace("%FEAT%", features)));
    }

    @Test
    public void shapeMatchesTheVectors() {
        int n = 0;
        for (JsonElement e : VECTORS.getAsJsonArray("shape")) {
            JsonObject c = e.getAsJsonObject();
            MoveOp.Problem got = MoveOp.problem(c.get("op"));
            JsonElement want = c.get("problem");
            String name = Json.str(c, "name", "");
            if (want == null || want.isJsonNull()) {
                assertNull(name, got);
            } else {
                assertNotNull(name, got);
                assertEquals(name, Json.str(want.getAsJsonObject(), "reason"), got.reason);
                assertEquals(name, Json.str(want.getAsJsonObject(), "field"), got.field);
            }
            n++;
        }
        assertEquals(45, n);
    }

    @Test
    public void everyOperationThePhoneBuildsIsWellFormed() {
        List<MoveOp> ops = new ArrayList<>(Arrays.asList(MoveOp.step("forward", 1), MoveOp.step("left", MoveOp.STEP_MAX),
                MoveOp.step("back", 99), MoveOp.jump(), MoveOp.sneak(true), MoveOp.sneak(false), MoveOp.go(120, 64, -40),
                MoveOp.approach("Kaan_07"), MoveOp.follow("AfkBot_2"), MoveOp.stop()));
        for (MoveOp op : ops) assertNull(op.op.toString(), MoveOp.problem(op.op));
        // Out of the world's bounds: the sheet refuses it before it goes.
        assertEquals("y", MoveOp.problem(MoveOp.go(0, 4000, 0).op).reason);
        JsonObject frame = MoveOp.step("right", 1).frame("a_7KQ2MZ3P9TW4XB1C", "v1");
        assertEquals("move", Json.str(frame, "t"));
        assertEquals("a_7KQ2MZ3P9TW4XB1C", Json.str(frame, "acct"));
        assertEquals("v1", Json.str(frame, "id"));
        assertEquals("{\"action\":\"step\",\"dir\":\"right\",\"n\":1}", frame.get("op").toString());
    }

    @Test
    public void reachIsTheContractsDistance() {
        MoveFrames.Pos here = new MoveFrames.Pos(100, 64, -40, "minecraft:overworld", "south");
        assertTrue(MoveOp.withinReach(here, 356, 64, -40));
        assertFalse(MoveOp.withinReach(here, 357, 64, -40));
        assertFalse(MoveOp.withinReach(here, 280, 64, 150));
    }

    @Test
    public void typedCoordinatesKeepTheContractsRange() {
        assertEquals(Integer.valueOf(120), MoveOp.coordinate(" 120 ", false));
        assertEquals(Integer.valueOf(-40), MoveOp.coordinate("-40", false));
        assertEquals(Integer.valueOf(-40), MoveOp.coordinate("−40", false));
        assertEquals(Integer.valueOf(-40), MoveOp.coordinate("–40", false));
        assertEquals(Integer.valueOf(2048), MoveOp.coordinate("2048", true));
        assertEquals(Integer.valueOf(-2048), MoveOp.coordinate("-2048", true));
        assertNull(MoveOp.coordinate("2049", true));
        assertEquals(Integer.valueOf(30_000_000), MoveOp.coordinate("30000000", false));
        assertNull(MoveOp.coordinate("-30000001", false));
        assertNull(MoveOp.coordinate("123456789", false));
        assertNull(MoveOp.coordinate("-", false));
        assertNull(MoveOp.coordinate("", false));
        assertNull(MoveOp.coordinate("1.5", false));
        assertNull(MoveOp.coordinate("1e3", false));
        assertNull(MoveOp.coordinate(null, false));
    }

    @Test
    public void stopShowsWhileItMoves() {
        assertTrue(MoveFrames.State.read(Vectors.json("{\"state\":\"ok\",\"doing\":\"step\"}")).going());
        assertTrue(MoveFrames.State.read(Vectors.json("{\"state\":\"ok\",\"doing\":\"goto\",\"to\":{\"x\":1,\"y\":64,\"z\":2}}")).going());
        assertTrue(MoveFrames.State.read(Vectors.json("{\"state\":\"ok\",\"doing\":\"follow\",\"name\":\"Kaan_07\"}")).going());
        assertFalse(MoveFrames.State.read(Vectors.json("{\"state\":\"ok\",\"doing\":\"idle\",\"ended\":\"arrived\"}")).going());
        assertFalse(MoveFrames.State.read(Vectors.json("{\"state\":\"off\",\"reason\":\"restart\"}")).going());
    }

    @Test
    public void framesRead() {
        final List<String> seen = new ArrayList<>();
        CloudLiveFrames.Listener l = new CloudLiveFrames.Listener() {
            @Override public void hello(boolean hostOnline, List<String> accounts, long leaseSeconds, long now) {}
            @Override public void state(String acct, JsonObject live) {}
            @Override public void console(String acct, List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}
            @Override public void host(boolean online) {}
            @Override public void error(String code, String acct) {}
            @Override public void ai(String acct, JsonObject ai) {}
            @Override public void approval(String acct, CloudModel.Approval approval) {}
            @Override public void bye(String reason) {}

            @Override
            public void moveState(String acct, MoveFrames.State s) {
                seen.add("movst " + acct + " " + s.ok + " " + s.reason + " " + s.doing + " " + (s.to == null ? "-" : Arrays.toString(s.to))
                        + " " + s.name + " " + s.ended + " " + s.sneak + " " + s.tradeLock);
            }

            @Override
            public void position(String acct, MoveFrames.Pos p) {
                seen.add("pos " + p.x + " " + p.y + " " + p.z + " " + p.dim + " " + p.facing);
            }

            @Override
            public void moveResult(String id, MoveFrames.Error error) {
                seen.add("movres " + id + " " + (error == null ? "ok" : error.code + " " + error.reason + " " + error.retryAfter));
            }
        };
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"movst\",\"acct\":\"a\",\"state\":\"off\",\"reason\":\"restart\"}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"movst\",\"acct\":\"a\",\"state\":\"ok\",\"doing\":\"goto\","
                + "\"to\":{\"x\":120,\"y\":64,\"z\":-40},\"until\":1792846920,\"sneak\":true}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"movst\",\"acct\":\"a\",\"state\":\"ok\",\"doing\":\"idle\","
                + "\"name\":\"Kaan_07\",\"ended\":\"out_of_sight\",\"lock\":\"trade\"}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"movst\",\"acct\":\"a\",\"state\":\"ok\",\"doing\":\"dance\"}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"pos\",\"acct\":\"a\",\"x\":-7,\"y\":64,\"z\":12,"
                + "\"dim\":\"minecraft:the_nether\",\"facing\":\"west\"}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"movres\",\"id\":\"v1\",\"ok\":true}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"movres\",\"id\":\"v2\",\"error\":{\"code\":\"loop\",\"retryAfter\":120}}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"movres\",\"id\":\"v3\",\"error\":{\"code\":\"movement_off\",\"reason\":\"slots\"}}"), l));
        assertFalse(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"pos\"}"), l));
        assertEquals(Arrays.asList(
                "movst a false restart idle - null null false false",
                "movst a true null goto [120, 64, -40] null null true false",
                "movst a true null idle - Kaan_07 out_of_sight false true",
                "movst a true null idle - null null false false",
                "pos -7 64 12 minecraft:the_nether west",
                "movres v1 ok",
                "movres v2 loop null 120",
                "movres v3 movement_off slots 0"), seen);
        assertTrue(MoveFrames.Error.of("stale").redrawOnly());
        assertFalse(MoveFrames.Error.of("too_far").redrawOnly());
    }

    @Test
    public void watchNamesBothStreams() {
        assertEquals("{\"t\":\"watch\",\"acct\":\"a\",\"move\":true}", CloudLiveFrames.watch("a", false, null, true).toString());
        assertEquals("{\"t\":\"watch\",\"acct\":\"a\",\"inv\":true,\"move\":false}", CloudLiveFrames.watch("a", false, true, false).toString());
        assertEquals("{\"t\":\"watch\",\"acct\":\"a\",\"inv\":true}", CloudLiveFrames.watch("a", false, true).toString());
    }

    @Test
    public void accountFeaturesFollowTheViewVectors() {
        int n = 0;
        for (JsonElement e : VECTORS.getAsJsonArray("view")) {
            JsonObject c = e.getAsJsonObject();
            JsonObject expected = c.getAsJsonObject("expected");
            CloudModel.Account a = account("", ",\"movement\":" + expected);
            CloudModel.MovementFeatures m = a.features.movement;
            String name = Json.str(c, "name", "");
            assertNotNull(name, m);
            assertEquals(name, "on".equals(Json.str(expected, "mode")), m.mode);
            assertEquals(name, Json.bool(expected, "on", false), m.on);
            assertEquals(name, Json.str(expected, "slot"), m.slot);
            JsonObject follow = Json.obj(expected, "follow");
            assertEquals(name, Json.str(follow, "main"), m.main);
            assertEquals(name, Json.strings(follow, "own"), m.own);
            assertEquals(name, m.mode, a.features.movementSwitch());
            assertEquals(name, m.mode && m.on, a.features.movementPanel());
            n++;
        }
        assertEquals(9, n);
        // A service without movement: nothing of it, and the setting is never sent.
        CloudModel.Account older = account("", "");
        assertNull(older.features.movement);
        assertFalse(older.features.movementSwitch());
        assertFalse(older.features.movementPanel());
        assertFalse(CloudModel.Offer.read(Vectors.json("{\"mode\":\"on\",\"features\":{}}")).features.movement);
        assertTrue(CloudModel.Offer.read(Vectors.json("{\"mode\":\"on\",\"features\":{\"movement\":true}}")).features.movement);
        // A name that could not be an account's is never offered.
        CloudModel.Account odd = account("", ",\"movement\":{\"mode\":\"on\",\"on\":true,\"slot\":null,"
                + "\"follow\":{\"main\":\"Kaan 07\",\"own\":[\"AfkBot_2\",\"x\",\"AfkBot_2\"]}}");
        assertNull(odd.features.movement.main);
        assertEquals(Collections.singletonList("AfkBot_2"), odd.features.movement.own);
    }

    @Test
    public void theSettingGoesOnlyWhenKnownAndIsNeverCopied() throws Exception {
        CloudModel.Account never = account("", ",\"movement\":{\"mode\":\"on\",\"on\":false,\"slot\":null,\"follow\":{\"main\":null,\"own\":[]}}");
        assertNull(never.config.movement);
        CloudEdit.Config config = CloudEdit.from(never.config);
        CloudEdit.Built built = CloudEdit.build(config, CloudModel.Limits.defaults(), false, (text, row) -> "sealed", never.config.fingerprints());
        assertFalse(built.config.has("movement"));
        config.movement = true;
        CloudEdit.Built on = CloudEdit.build(config, CloudModel.Limits.defaults(), false, (text, row) -> "sealed", never.config.fingerprints());
        assertTrue(on.config.getAsJsonObject("movement").get("enabled").getAsBoolean());
        assertFalse(config.same(CloudEdit.from(never.config)));
        assertFalse(config.onlyInventoryDiffers(CloudEdit.from(never.config)));

        CloudModel.Account stored = account(",\"movement\":{\"enabled\":true}",
                ",\"movement\":{\"mode\":\"on\",\"on\":true,\"slot\":null,\"follow\":{\"main\":\"Kaan_07\",\"own\":[]}}");
        assertEquals(Boolean.TRUE, stored.config.movement);
        assertNull(CloudBulk.copied(stored.config, Collections.<String, String>emptyMap()).movement);
    }

    @Test
    public void noSlotReadsFromTheRefusal() {
        List<CloudEdit.Issue> player = CloudEdit.fromRefusal(CloudProblem.of(422,
                "{\"error\":{\"code\":\"limit_movement\",\"scope\":\"player\",\"max\":1}}", null));
        assertEquals("[general.movement:player/1]", String.valueOf(player));
        List<CloudEdit.Issue> host = CloudEdit.fromRefusal(CloudProblem.of(422,
                "{\"error\":{\"code\":\"limit_movement\",\"scope\":\"host\",\"max\":10}}", null));
        assertEquals("[general.movement:host]", String.valueOf(host));
        List<CloudEdit.Issue> off = CloudEdit.fromRefusal(CloudProblem.of(400,
                "{\"error\":{\"code\":\"invalid_config\",\"field\":\"movement.enabled\",\"reason\":\"off\"}}", null));
        assertEquals("[general.movement:off]", String.valueOf(off));
    }
}
