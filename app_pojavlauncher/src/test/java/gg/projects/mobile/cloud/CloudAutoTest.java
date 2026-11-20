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

import gg.projects.mobile.cloud.auto.AutoFrames;
import gg.projects.mobile.cloud.auto.AutoOp;
import gg.projects.mobile.cloud.auto.AutoSettings;
import gg.projects.mobile.social.Json;

/**
 * Auto-fishing and auto-mining on the phone (services/cloud-afk README "Auto-fishing and auto-mining"): the settings'
 * defaults and the fixed block list held to {@code feat/settings-v1.json}, the settings' shapes and checks, a mode's
 * setting sent only whole, only as changed and only to a service that names it, the account's switches, the operations
 * the phone builds and the stream's frames. The contract's shared {@code auto-op-v1.json} replaces the own cases here
 * once the Worker's generator has written it.
 */
public class CloudAutoTest {
    private static final JsonObject SETTINGS = Vectors.load("cloud/feat-settings-v1.json");
    private static final String ACCOUNT = "{\"id\":\"a_7KQ2MZ3P9TW4XB1C\",\"name\":\"AfkBot_1\",\"host\":\"kz1\","
            + "\"server\":{\"host\":\"mc.example.net\",\"port\":0},\"rev\":4,\"config\":{\"v\":1,\"owner\":\"Kaan_07\","
            + "\"antiAfk\":{\"enabled\":true,\"delayMin\":60,\"delayMax\":90},\"commands\":[],\"rules\":[]%CONFIG%},"
            + "\"features\":{\"inventory\":{\"mode\":\"full\",\"on\":true}%FEAT%}}";
    private static final String FISHING = "{\"enabled\":true,\"rod\":{\"switch\":false,\"keep\":12},"
            + "\"full\":{\"do\":\"command\",\"command\":\"/sell fish\"},\"look\":true}";
    private static final String MINING = "{\"enabled\":false,\"blocks\":[\"minecraft:cobblestone\",\"minecraft:diamond_ore\"],"
            + "\"tool\":{\"switch\":true,\"keep\":5},\"full\":{\"do\":\"keep\"},"
            + "\"idle\":{\"look\":true,\"sneak\":false,\"jump\":true,\"step\":false,\"every\":{\"min\":30,\"max\":90}}}";
    private static final String BOTH_ON = ",\"fishing\":{\"mode\":\"on\",\"on\":true,\"slot\":null,\"limited\":null},"
            + "\"mining\":{\"mode\":\"on\",\"on\":false,\"slot\":null,\"limited\":null}";

    private static CloudModel.Account account(String config, String features) {
        return CloudModel.Account.read(Vectors.json(ACCOUNT.replace("%CONFIG%", config).replace("%FEAT%", features)));
    }

    private static CloudEdit.Checks checks(CloudModel.Account a) {
        return new CloudEdit.Checks(false, CloudModel.DEFAULT_PLACEHOLDER_COMMANDS, a.features.fishing != null, a.features.mining != null);
    }

    private static JsonObject build(CloudEdit.Config config, CloudEdit.Checks checks) throws Exception {
        CloudEdit.Built built = CloudEdit.build(config, CloudModel.Limits.defaults(), checks, false, (text, row) -> "sealed",
                Collections.<String, String>emptyMap());
        assertTrue(String.valueOf(built.issues), built.issues.isEmpty());
        return built.config;
    }

    // ---- settings ----

    @Test
    public void theBlockListIsTheContracts() {
        List<String> fixed = Json.strings(SETTINGS.getAsJsonObject("fixed"), "miningBlocks");
        assertEquals(33, fixed.size());
        assertEquals(fixed, AutoSettings.MINING_BLOCKS);
    }

    @Test
    public void defaultsAreTheContracts() {
        AutoSettings.Fishing f = AutoSettings.Fishing.defaults();
        assertEquals("{\"enabled\":false,\"rod\":{\"switch\":true,\"keep\":5},\"full\":{\"do\":\"wait\"},\"look\":false}", f.json().toString());
        AutoSettings.Mining m = AutoSettings.Mining.defaults();
        assertEquals("{\"enabled\":false,\"blocks\":null,\"tool\":{\"switch\":true,\"keep\":5},\"full\":{\"do\":\"wait\"},"
                + "\"idle\":{\"look\":false,\"sneak\":false,\"jump\":false,\"step\":false,\"every\":{\"min\":60,\"max\":180}}}", m.json().toString());
        assertEquals(33, m.count());
        assertTrue(m.breaks("minecraft:ancient_debris"));
        assertFalse(m.breaks("minecraft:chest"));
        // Missing parts read as their defaults (the view carries every part; an older copy may not).
        AutoSettings.Fishing bare = AutoSettings.Fishing.read(Vectors.json("{\"enabled\":true}"));
        assertNotNull(bare);
        assertTrue(bare.enabled);
        assertTrue(bare.samePartsAs(AutoSettings.Fishing.defaults()));
        assertNull(AutoSettings.Fishing.read(null));
        assertNull(AutoSettings.Mining.read(null));
    }

    @Test
    public void storedSettingsComeBackWhole() {
        AutoSettings.Fishing f = AutoSettings.Fishing.read(Vectors.json(FISHING));
        assertEquals(FISHING, f.json().toString());
        AutoSettings.Mining m = AutoSettings.Mining.read(Vectors.json(MINING));
        assertEquals(MINING, m.json().toString());
        assertEquals(2, m.count());
        assertTrue(f.same(f.copy()));
        assertTrue(m.same(m.copy()));
        AutoSettings.Fishing other = f.copy();
        other.enabled = false;
        assertFalse(f.same(other));
        assertTrue(f.samePartsAs(other));
        other.rod.keep = 13;
        assertFalse(f.samePartsAs(other));
    }

    @Test
    public void aCommandGoesOnlyWithCommand() {
        AutoSettings.Fishing f = AutoSettings.Fishing.read(Vectors.json(FISHING));
        f.full.what = AutoSettings.FULL_WAIT;
        assertEquals("{\"do\":\"wait\"}", f.json().getAsJsonObject("full").toString());
        // A command typed before switching away does not make the setting differ.
        AutoSettings.Fishing typed = f.copy();
        typed.full.command = "/other";
        assertTrue(f.same(typed));
        assertTrue(f.problems().isEmpty());
    }

    @Test
    public void blocksAreKnownDistinctAndInTheListsOrder() {
        AutoSettings.Mining m = AutoSettings.Mining.read(Vectors.json("{\"enabled\":true,\"blocks\":[\"minecraft:stone\","
                + "\"minecraft:cobblestone\",\"minecraft:chest\",\"minecraft:stone\",7]}"));
        assertEquals(Arrays.asList("minecraft:stone", "minecraft:cobblestone"), m.blocks);
        assertEquals("[\"minecraft:cobblestone\",\"minecraft:stone\"]", m.json().get("blocks").toString());
        assertNull(AutoSettings.Mining.read(Vectors.json("{\"enabled\":true,\"blocks\":null}")).blocks);
        assertNull(AutoSettings.Mining.read(Vectors.json("{\"enabled\":true}")).blocks);
        // Every block picked one by one stays a list (a block the list gains later is not the player's choice).
        m.blocks = new ArrayList<>(AutoSettings.MINING_BLOCKS);
        assertEquals(33, m.json().getAsJsonArray("blocks").size());
        m.blocks = new ArrayList<>();
        assertEquals("[mining.blocks:range]", String.valueOf(m.problems()));
        // The same choice in another order is the same setting.
        AutoSettings.Mining a = AutoSettings.Mining.defaults();
        AutoSettings.Mining b = AutoSettings.Mining.defaults();
        a.blocks = Arrays.asList("minecraft:stone", "minecraft:cobblestone");
        b.blocks = Arrays.asList("minecraft:cobblestone", "minecraft:stone");
        assertTrue(a.same(b));
    }

    @Test
    public void checksAreTheServices() {
        AutoSettings.Fishing f = AutoSettings.Fishing.defaults();
        f.rod.keep = 0;
        assertEquals("[fishing.keep:range]", String.valueOf(f.problems()));
        f.rod.keep = 101;
        assertEquals("[fishing.keep:range]", String.valueOf(f.problems()));
        f.rod.keep = 100;
        f.full.what = AutoSettings.FULL_COMMAND;
        f.full.command = "";
        assertEquals("[fishing.command:empty]", String.valueOf(f.problems()));
        f.full.command = "/login hunter2";
        assertEquals("[fishing.command:secret]", String.valueOf(f.problems()));
        f.full.command = "/sell all";
        assertTrue(f.problems().isEmpty());

        AutoSettings.Mining m = AutoSettings.Mining.defaults();
        m.idle.min = 19;
        assertEquals("[mining.every:range]", String.valueOf(m.problems()));
        m.idle.min = 200;
        m.idle.max = 100;
        assertEquals("[mining.every:range]", String.valueOf(m.problems()));
        m.idle.min = 20;
        m.idle.max = 3601;
        assertEquals("[mining.every:range]", String.valueOf(m.problems()));
        m.idle.max = 3600;
        m.tool.keep = 0;
        assertEquals("[mining.keep:range]", String.valueOf(m.problems()));
    }

    // ---- what a save sends ----

    @Test
    public void aStoredModeIsNeverEchoed() throws Exception {
        CloudModel.Account a = account(",\"fishing\":" + FISHING + ",\"mining\":" + MINING, BOTH_ON);
        assertNotNull(a.config.fishing);
        assertNotNull(a.config.mining);
        CloudEdit.Config config = CloudEdit.from(a.config);
        assertNull(config.fishing);
        assertNull(config.mining);
        // The inventory's "Aç" and a new server's seals build from the view: neither mode goes.
        config.inventory = false;
        JsonObject sent = build(config, checks(a));
        assertFalse(sent.has("fishing"));
        assertFalse(sent.has("mining"));
        // A bulk copy never carries one either.
        CloudEdit.Config copied = CloudBulk.copied(a.config, Collections.<String, String>emptyMap());
        assertNull(copied.fishing);
        assertNull(copied.mining);
        assertFalse(build(copied, checks(a)).has("fishing"));
    }

    @Test
    public void aChangedModeGoesWholeAndOnlyToAServiceThatNamesIt() throws Exception {
        CloudModel.Account a = account(",\"fishing\":" + FISHING, BOTH_ON);
        CloudEdit.Config original = CloudEdit.from(a.config);
        CloudEdit.Config config = original.copy();
        AutoSettings.Fishing changed = a.config.fishing.copy();
        changed.look = false;
        config.fishing = changed;
        assertFalse(config.same(original));
        JsonObject sent = build(config, checks(a));
        assertEquals("{\"enabled\":true,\"rod\":{\"switch\":false,\"keep\":12},\"full\":{\"do\":\"command\",\"command\":\"/sell fish\"},"
                + "\"look\":false}", sent.get("fishing").toString());
        assertFalse(sent.has("mining"));
        // A service whose account features do not name the mode never gets its setting.
        CloudModel.Account older = account(",\"fishing\":" + FISHING, "");
        assertNull(older.features.fishing);
        assertFalse(build(config, checks(older)).has("fishing"));
        assertFalse(build(config, CloudEdit.Checks.NONE).has("fishing"));
        // A refused part is found before anything goes.
        config.fishing.rod.keep = 0;
        CloudEdit.Built refused = CloudEdit.build(config, CloudModel.Limits.defaults(), checks(a), false, (text, row) -> "sealed",
                Collections.<String, String>emptyMap());
        assertNull(refused.config);
        assertEquals("[general.fishing.keep:range]", String.valueOf(refused.issues));
    }

    @Test
    public void onlyAModesPartsRestartNothing() {
        CloudModel.Account a = account(",\"fishing\":" + FISHING, BOTH_ON);
        CloudEdit.Config original = CloudEdit.from(a.config);
        CloudEdit.Config parts = original.copy();
        parts.fishing = a.config.fishing.copy();
        parts.fishing.look = false;
        assertTrue(parts.onlyAutoPartsDiffer(original, a.config));
        CloudEdit.Config off = original.copy();
        off.fishing = a.config.fishing.copy();
        off.fishing.enabled = false;
        assertFalse(off.onlyAutoPartsDiffer(original, a.config));
        // Mining never stored: turning it on is a change of "enabled".
        CloudEdit.Config mining = original.copy();
        mining.mining = AutoSettings.Mining.defaults();
        mining.mining.enabled = true;
        assertFalse(mining.onlyAutoPartsDiffer(original, a.config));
        mining.mining.enabled = false;
        mining.mining.idle.jump = true;
        assertTrue(mining.onlyAutoPartsDiffer(original, a.config));
        // Another change beside it restarts as before.
        parts.antiAfk = false;
        assertFalse(parts.onlyAutoPartsDiffer(original, a.config));
        assertFalse(original.copy().onlyAutoPartsDiffer(original, a.config));
    }

    @Test
    public void refusalsLandOnTheirPart() {
        assertEquals("[general.fishing:player/2]", String.valueOf(CloudEdit.fromRefusal(CloudProblem.of(422,
                "{\"error\":{\"code\":\"limit_fishing\",\"scope\":\"player\",\"max\":2}}", null))));
        assertEquals("[general.mining:host]", String.valueOf(CloudEdit.fromRefusal(CloudProblem.of(422,
                "{\"error\":{\"code\":\"limit_mining\",\"scope\":\"host\",\"max\":10}}", null))));
        assertEquals("[general.fishing:limited]", String.valueOf(CloudEdit.fromRefusal(CloudProblem.of(400,
                "{\"error\":{\"code\":\"invalid_config\",\"field\":\"fishing.enabled\",\"reason\":\"limited\"}}", null))));
        assertEquals("[general.mining:off]", String.valueOf(CloudEdit.fromRefusal(CloudProblem.of(400,
                "{\"error\":{\"code\":\"invalid_config\",\"field\":\"mining.enabled\",\"reason\":\"off\"}}", null))));
        // One mode at a time per account: the other one is on.
        assertEquals("[general.mining:other]", String.valueOf(CloudEdit.fromRefusal(CloudProblem.of(400,
                "{\"error\":{\"code\":\"invalid_config\",\"field\":\"mining.enabled\",\"reason\":\"other\"}}", null))));
        assertEquals("[general.fishing.command:secret]", String.valueOf(CloudEdit.fromRefusal(CloudProblem.of(400,
                "{\"error\":{\"code\":\"invalid_command\",\"field\":\"fishing.full.command\",\"reason\":\"secret\"}}", null))));
        assertEquals("[general.mining.every:range]", String.valueOf(CloudEdit.fromRefusal(CloudProblem.of(400,
                "{\"error\":{\"code\":\"invalid_config\",\"field\":\"mining.idle.every.max\",\"reason\":\"range\"}}", null))));
        assertEquals("[general.mining.blocks:range]", String.valueOf(CloudEdit.fromRefusal(CloudProblem.of(400,
                "{\"error\":{\"code\":\"invalid_config\",\"field\":\"mining.blocks\",\"reason\":\"range\"}}", null))));
        assertEquals("[general.fishing.keep:range]", String.valueOf(CloudEdit.fromRefusal(CloudProblem.of(400,
                "{\"error\":{\"code\":\"invalid_config\",\"field\":\"fishing.rod.keep\",\"reason\":\"range\"}}", null))));
    }

    // ---- the account's switches ----

    @Test
    public void theAccountsSwitchesRead() {
        CloudModel.Account a = account("", ",\"fishing\":{\"mode\":\"on\",\"on\":true,\"slot\":null,"
                + "\"limited\":{\"tr\":\"Projects sunucularında otomatik balık tutma kapalı.\",\"en\":\"Auto-fishing is off on Projects servers.\"}},"
                + "\"mining\":{\"mode\":\"on\",\"on\":false,\"slot\":\"player\",\"limited\":null}");
        CloudModel.AutoFeature f = a.features.fishing;
        assertTrue(f.mode);
        assertTrue(f.on);
        assertNull(f.slot);
        assertEquals("Auto-fishing is off on Projects servers.", f.limited.en);
        assertTrue(f.blocked());
        assertTrue(a.features.autoSwitch("fishing"));
        assertTrue(a.features.autoPanel("fishing"));
        CloudModel.AutoFeature m = a.features.mining;
        assertEquals("player", m.slot);
        assertTrue(m.blocked());
        assertTrue(a.features.autoSwitch("mining"));
        assertFalse(a.features.autoPanel("mining"));
        assertFalse(a.features.autoSwitch("dancing"));

        // Off for the service: nothing of the mode shows, whatever else the object says.
        CloudModel.Account off = account("", ",\"fishing\":{\"mode\":\"off\",\"on\":true,\"slot\":\"host\","
                + "\"limited\":{\"tr\":\"x\",\"en\":\"y\"}}");
        assertFalse(off.features.autoSwitch("fishing"));
        assertFalse(off.features.autoPanel("fishing"));
        assertNull(off.features.fishing.slot);
        assertNull(off.features.fishing.limited);
        // A service without the modes, and an account without a machine: none.
        assertNull(account("", "").features.fishing);
        CloudModel.Account noMachine = CloudModel.Account.read(Vectors.json(ACCOUNT.replace("\"host\":\"kz1\",", "")
                .replace("%CONFIG%", "").replace("%FEAT%", BOTH_ON)));
        assertNull(noMachine.features.fishing);
        // The offer's switches.
        assertFalse(CloudModel.Offer.read(Vectors.json("{\"mode\":\"on\",\"features\":{}}")).features.fishing);
        CloudModel.OfferFeatures both = CloudModel.Offer.read(Vectors.json("{\"mode\":\"on\",\"features\":{\"fishing\":true,\"mining\":true}}")).features;
        assertTrue(both.fishing);
        assertTrue(both.mining);
    }

    // ---- operations ----

    @Test
    public void everyOperationThePhoneBuildsIsWellFormed() {
        List<AutoOp> ops = Arrays.asList(AutoOp.pause("fishing"), AutoOp.resume("fishing"), AutoOp.pause("mining"),
                AutoOp.resume("mining"), AutoOp.aim(0, 0), AutoOp.aim(-180, 90), AutoOp.aim(180, -90), AutoOp.aim(90, 30));
        for (AutoOp op : ops) assertNull(op.op.toString(), AutoOp.problem(op.op));
        // Out of range clamps to the edge rather than going out refused.
        assertEquals("{\"action\":\"aim\",\"yaw\":180,\"pitch\":-90}", AutoOp.aim(400, -200).op.toString());
        JsonObject frame = AutoOp.pause("mining").frame("a_7KQ2MZ3P9TW4XB1C", "f3");
        assertEquals("{\"t\":\"auto\",\"acct\":\"a_7KQ2MZ3P9TW4XB1C\",\"id\":\"f3\",\"op\":{\"mode\":\"mining\",\"action\":\"pause\"}}",
                frame.toString());
        assertEquals("aim", AutoOp.aim(1, 2).action());
    }

    @Test
    public void shapeProblemsAreTheServices() {
        String[][] cases = {
                {"[]", "type"},
                {"{\"mode\":\"fishing\"}", "action"},
                {"{\"action\":\"dance\"}", "action"},
                {"{\"action\":\"pause\"}", "mode"},
                {"{\"mode\":\"farming\",\"action\":\"pause\"}", "mode"},
                {"{\"mode\":\"mining\",\"action\":\"resume\",\"yaw\":1}", "field:yaw"},
                {"{\"action\":\"aim\",\"mode\":\"fishing\",\"yaw\":0,\"pitch\":0}", "field:mode"},
                {"{\"action\":\"aim\",\"yaw\":0}", "pitch"},
                {"{\"action\":\"aim\",\"yaw\":181,\"pitch\":0}", "yaw"},
                {"{\"action\":\"aim\",\"yaw\":0,\"pitch\":-90.5}", "pitch"},
                {"{\"action\":\"aim\",\"yaw\":\"90\",\"pitch\":0}", "yaw"},
                {"{\"action\":\"aim\",\"yaw\":12.5,\"pitch\":-45.25}", null},
        };
        for (String[] c : cases) {
            AutoOp.Problem p = AutoOp.problem(Json.parse(c[0]));
            assertEquals(c[0], c[1], p == null ? null : p.toString());
        }
        assertEquals("type", AutoOp.problem(null).reason);
    }

    // ---- the stream ----

    @Test
    public void framesRead() {
        final List<String> seen = new ArrayList<>();
        final List<AutoFrames.Update> updates = new ArrayList<>();
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
            public void autoState(String acct, AutoFrames.Update update) {
                seen.add("autost " + acct + " " + (update.fishing != null) + " " + (update.mining != null));
                updates.add(update);
            }

            @Override
            public void autoResult(String id, AutoFrames.Error error) {
                seen.add("autores " + id + " " + (error == null ? "ok" : error.code + " " + error.reason + " " + error.retryAfter));
            }
        };
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"autost\",\"acct\":\"a\",\"fishing\":{\"state\":\"on\",\"doing\":\"wait\","
                + "\"since\":1795000000,\"rod\":{\"item\":\"minecraft:fishing_rod\",\"dur\":40,\"max\":64},\"today\":{\"catches\":21,\"fish\":17,"
                + "\"treasure\":1,\"junk\":3,\"perHour\":32.5,\"items\":[{\"id\":\"minecraft:cod\",\"n\":11},{\"id\":\"minecraft:salmon\",\"n\":6}],"
                + "\"last\":[{\"id\":\"minecraft:enchanted_book\",\"cat\":\"treasure\",\"ench\":true,\"at\":1795003600},"
                + "{\"id\":\"minecraft:cod\",\"cat\":\"fish\",\"at\":1795003500},{\"id\":\"minecraft:bowl\",\"cat\":\"rubbish\",\"at\":1795003400}]}},"
                + "\"mining\":{\"state\":\"waiting\",\"reason\":\"no_target\",\"target\":null,\"tool\":null,\"today\":{\"blocks\":0,\"items\":[]}}}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"autost\",\"acct\":\"a\",\"mining\":{\"state\":\"on\","
                + "\"target\":{\"id\":\"minecraft:cobblestone\",\"x\":12,\"y\":64,\"z\":-40},"
                + "\"tool\":{\"item\":\"minecraft:diamond_pickaxe\",\"dur\":1500,\"max\":1561},\"today\":{\"blocks\":846,\"perMinute\":11.8,"
                + "\"items\":[{\"id\":\"minecraft:cobblestone\",\"n\":846}]}}}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"autost\",\"acct\":\"a\",\"fishing\":{\"state\":\"dancing\",\"reason\":\"x\"}}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"autores\",\"id\":\"f1\",\"ok\":true}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"autores\",\"id\":\"f2\",\"error\":{\"code\":\"auto_off\",\"reason\":\"limited\"}}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"autores\",\"id\":\"f3\",\"error\":{\"code\":\"rate_limited\",\"retryAfter\":2}}"), l));
        assertFalse(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"autost\"}"), l));
        assertFalse(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"autores\",\"ok\":true}"), l));
        assertEquals(Arrays.asList(
                "autost a true true",
                "autost a false true",
                "autost a true false",
                "autores f1 ok",
                "autores f2 auto_off limited 0",
                "autores f3 rate_limited null 2"), seen);

        AutoFrames.Fish fish = updates.get(0).fishing;
        assertTrue(fish.on());
        assertNull(fish.reason);
        assertEquals("wait", fish.doing);
        assertEquals(1795000000L, fish.since);
        assertEquals("minecraft:fishing_rod", fish.rod.item);
        assertEquals(40, fish.rod.dur);
        assertEquals(0.625f, fish.rod.left(), 0.0001f);
        assertEquals(21, fish.catches);
        assertEquals(17, fish.fish);
        assertEquals(1, fish.treasure);
        assertEquals(3, fish.junk);
        assertEquals(32.5, fish.perHour, 0.0001);
        assertEquals(2, fish.items.size());
        assertEquals("minecraft:cod", fish.items.get(0).id);
        assertEquals(11, fish.items.get(0).n);
        assertEquals(3, fish.last.size());
        assertTrue(fish.last.get(0).treasure());
        assertTrue(fish.last.get(0).ench);
        assertEquals("fish", fish.last.get(1).cat);
        // An unknown category reads as the rest: junk.
        assertEquals("junk", fish.last.get(2).cat);
        assertTrue(fish.pausable());

        AutoFrames.Mine waiting = updates.get(0).mining;
        assertTrue(waiting.waiting());
        assertEquals("no_target", waiting.reason);
        assertNull(waiting.target);
        assertNull(waiting.tool);
        assertNull(waiting.perMinute);
        assertTrue(waiting.pausable());

        AutoFrames.Mine mine = updates.get(1).mining;
        assertEquals("minecraft:cobblestone", mine.target.id);
        assertEquals(-40, mine.target.z);
        assertEquals(1561, mine.tool.max);
        assertEquals(846, mine.blocks);
        assertEquals(11.8, mine.perMinute, 0.0001);
        assertNull(updates.get(1).fishing);

        // An unknown state reads as off (no controls), its reason kept for the words' fallback.
        AutoFrames.Fish odd = updates.get(2).fishing;
        assertTrue(odd.off());
        assertNull(odd.doing);
        assertEquals(0, odd.catches);
        assertTrue(odd.items.isEmpty());
        assertFalse(odd.pausable());

        assertTrue(AutoFrames.Error.of("invalid").redrawOnly());
        assertFalse(AutoFrames.Error.of("busy_trade").redrawOnly());
    }

    @Test
    public void listsKeepTheContractsMost() {
        StringBuilder items = new StringBuilder();
        StringBuilder last = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                items.append(',');
                last.append(',');
            }
            items.append("{\"id\":\"minecraft:cod\",\"n\":").append(i).append('}');
            last.append("{\"id\":\"minecraft:cod\",\"cat\":\"fish\",\"at\":").append(i).append('}');
        }
        AutoFrames.Fish fish = AutoFrames.Update.read(Vectors.json("{\"fishing\":{\"state\":\"on\",\"today\":{\"items\":[" + items
                + "],\"last\":[" + last + "]}}}")).fishing;
        assertEquals(AutoFrames.ITEMS_MAX, fish.items.size());
        assertEquals(AutoFrames.LAST_MAX, fish.last.size());
    }

    @Test
    public void pausedStatesSayWhoGoesOn() {
        assertTrue(state("{\"state\":\"paused\",\"reason\":\"player\"}").waitsForPlayer());
        assertTrue(state("{\"state\":\"paused\",\"reason\":\"moved\"}").waitsForPlayer());
        assertFalse(state("{\"state\":\"paused\",\"reason\":\"window\"}").waitsForPlayer());
        assertTrue(state("{\"state\":\"paused\",\"reason\":\"trade\"}").pausable());
        assertFalse(state("{\"state\":\"paused\",\"reason\":\"player\"}").pausable());
        assertFalse(state("{\"state\":\"off\",\"reason\":\"service\"}").pausable());
        assertNull(state("{\"state\":\"on\",\"reason\":\"full\"}").reason);
    }

    private static AutoFrames.Fish state(String json) {
        return AutoFrames.Update.read(Vectors.json("{\"fishing\":" + json + "}")).fishing;
    }

    @Test
    public void blockChoicesAreTheSitesAndCoverTheListOnce() {
        int stone = 0;
        int ore = 0;
        List<String> seen = new ArrayList<>();
        for (AutoSettings.Choice choice : AutoSettings.MINING_CHOICES) {
            if ("stone".equals(choice.group)) stone++;
            else if ("ore".equals(choice.group)) ore++;
            for (String id : choice.ids) {
                assertTrue(id, AutoSettings.MINING_BLOCKS.contains(id));
                assertFalse(id, seen.contains(id));
                seen.add(id);
            }
        }
        assertEquals(14, stone);
        assertEquals(11, ore);
        assertEquals(AutoSettings.MINING_BLOCKS.size(), seen.size());
        // An ore and its deepslate twin are one choice.
        AutoSettings.Choice coal = AutoSettings.MINING_CHOICES.get(14);
        assertEquals(Arrays.asList("minecraft:coal_ore", "minecraft:deepslate_coal_ore"), coal.ids);
        assertTrue(coal.in(Collections.singletonList("minecraft:deepslate_coal_ore")));
        assertFalse(coal.in(null));
    }

    @Test
    public void slotsCountAsPlayersWrite() {
        assertEquals("72", gg.projects.mobile.cloud.auto.Auto.slotCount(72, true));
        assertEquals("999", gg.projects.mobile.cloud.auto.Auto.slotCount(999, true));
        assertEquals("1k", gg.projects.mobile.cloud.auto.Auto.slotCount(1000, true));
        assertEquals("1,1k", gg.projects.mobile.cloud.auto.Auto.slotCount(1150, true));
        assertEquals("1.1k", gg.projects.mobile.cloud.auto.Auto.slotCount(1150, false));
        assertEquals("12,3k", gg.projects.mobile.cloud.auto.Auto.slotCount(12345, true));
        assertEquals("123k", gg.projects.mobile.cloud.auto.Auto.slotCount(123456, true));
    }

    @Test
    public void aPausedCommandIsReadAndNeverWritten() {
        AutoSettings.Fishing f = AutoSettings.Fishing.read(Vectors.json("{\"enabled\":true,\"full\":{\"do\":\"command\",\"command\":\"/sell all\","
                + "\"paused\":{\"case\":\"mc_01M7V41F00ABCDEFGHJKMNPQRS\",\"category\":\"advertising\"}}}"));
        assertTrue(f.full.paused);
        assertEquals("{\"do\":\"command\",\"command\":\"/sell all\"}", f.json().getAsJsonObject("full").toString());
        assertTrue(f.copy().full.paused);
        assertFalse(AutoSettings.Fishing.read(Vectors.json(FISHING)).full.paused);
    }

    @Test
    public void watchNamesTheAutoStream() {
        assertEquals("{\"t\":\"watch\",\"acct\":\"a\",\"auto\":true}", CloudLiveFrames.watch("a", false, null, null, true).toString());
        assertEquals("{\"t\":\"watch\",\"acct\":\"a\",\"move\":true,\"auto\":false}", CloudLiveFrames.watch("a", false, null, true, false).toString());
        // Left out when never asked: an older service never sees the field.
        assertEquals("{\"t\":\"watch\",\"acct\":\"a\",\"inv\":true}", CloudLiveFrames.watch("a", false, true, null).toString());
    }

    @Test
    public void elementsOfTheViewStayLenient() {
        for (JsonElement e : Json.parse("[{\"enabled\":\"yes\"},{\"rod\":{\"keep\":\"5\"}},{\"full\":{\"do\":\"sing\"}},{\"rod\":{\"keep\":500}}]")
                .getAsJsonArray()) {
            AutoSettings.Fishing f = AutoSettings.Fishing.read(e.getAsJsonObject());
            assertNotNull(f);
            assertTrue(f.problems().isEmpty());
        }
    }
}
