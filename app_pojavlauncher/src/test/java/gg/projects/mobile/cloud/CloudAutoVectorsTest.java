package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.auto.AutoOp;
import gg.projects.mobile.cloud.auto.AutoSettings;
import gg.projects.mobile.social.Json;

/**
 * The phone held to the contract's shared auto vectors ({@code test-vectors/auto-op-v1.json}): the fixed rules and the
 * defaults, every stored form the editor writes back whole, every refusal the editor could make found before it sends,
 * the rest normalised away by the editor itself (it offers only the list's blocks in the list's order and whole numbers),
 * the operations' shapes and the account's switches from the view. The limit and slot cases are the Worker's.
 */
public class CloudAutoVectorsTest {
    private static final JsonObject VECTORS = Vectors.load("cloud/auto-op-v1.json");

    @Test
    public void fixedRulesAndDefaultsAreTheContracts() {
        JsonObject settings = VECTORS.getAsJsonObject("settings");
        JsonObject fixed = settings.getAsJsonObject("fixed");
        assertEquals(Json.strings(fixed, "miningBlocks"), AutoSettings.MINING_BLOCKS);
        assertEquals(AutoSettings.KEEP_MIN, Json.num(fixed, "keepMin", -1));
        assertEquals(AutoSettings.KEEP_MAX, Json.num(fixed, "keepMax", -1));
        assertEquals(AutoSettings.EVERY_MIN, Json.num(fixed, "everyMin", -1));
        assertEquals(AutoSettings.EVERY_MAX, Json.num(fixed, "everyMax", -1));
        assertEquals(AutoOp.YAW_MAX, Json.num(fixed, "yawMax", -1));
        assertEquals(AutoOp.PITCH_MAX, Json.num(fixed, "pitchMax", -1));
        JsonObject defaults = settings.getAsJsonObject("configDefaults");
        JsonObject mining = AutoSettings.Mining.defaults().json();
        assertEquals(defaults.get("tool"), mining.get("tool"));
        assertEquals(defaults.get("full"), mining.get("full"));
        assertEquals(defaults.get("idle"), mining.get("idle"));
        assertTrue(mining.get("blocks").isJsonNull());
        assertTrue(defaults.get("blocks").isJsonNull());
        JsonObject fishing = AutoSettings.Fishing.defaults().json();
        assertEquals(defaults.get("tool"), fishing.get("rod"));
        assertEquals(defaults.get("look"), fishing.get("look"));
    }

    @Test
    public void storedFormsComeBackWhole() {
        int n = 0;
        for (JsonElement e : VECTORS.getAsJsonArray("config")) {
            JsonObject c = e.getAsJsonObject();
            JsonObject stored = Json.obj(Json.obj(c, "expected"), "stored");
            if (stored == null) continue;
            String name = Json.str(c, "name", "");
            JsonObject value = c.getAsJsonObject("value");
            JsonObject written = "mining".equals(Json.str(c, "mode")) ? AutoSettings.Mining.read(value).json() : AutoSettings.Fishing.read(value).json();
            assertEquals(name, stored, written);
            n++;
        }
        // The view's moderation mark on a full inventory's command is read, never written back (the service drops it).
        assertEquals(15, n);
    }

    @Test
    public void everyRefusalTheEditorCouldMakeIsFoundBeforeItSends() {
        int caught = 0;
        int normalised = 0;
        int notFromTheEditor = 0;
        for (JsonElement e : VECTORS.getAsJsonArray("config")) {
            JsonObject c = e.getAsJsonObject();
            JsonObject error = Json.obj(Json.obj(c, "expected"), "error");
            if (error == null) continue;
            String name = Json.str(c, "name", "");
            String mode = Json.str(c, "mode", "fishing");
            String field = Json.str(error, "field", "");
            String reason = Json.str(error, "reason", "");
            JsonElement valueEl = c.get("value");
            if (valueEl == null || !valueEl.isJsonObject()) {
                // A value that is no object never leaves the editor; reading one leniently is "not stored".
                notFromTheEditor++;
                continue;
            }
            JsonObject value = valueEl.getAsJsonObject();
            boolean fishing = "fishing".equals(mode);
            AutoSettings.Fishing f = fishing ? AutoSettings.Fishing.read(value) : null;
            AutoSettings.Mining m = fishing ? null : AutoSettings.Mining.read(value);
            assertTrue(name, fishing ? f != null : m != null);
            AutoSettings.Tool tool = fishing ? f.rod : m.tool;
            AutoSettings.Full full = fishing ? f.full : m.full;
            String part = fishing ? "rod" : "tool";
            if (field.equals(mode + "." + part + ".keep") && "range".equals(reason)) {
                // The field is typed: the number as typed reaches the check.
                tool.keep = (int) Json.num(Json.obj(value, part), "keep", 0);
                assertEquals(name, "[" + mode + ".keep:range]", String.valueOf(problems(f, m)));
                caught++;
            } else if (field.equals(mode + ".full.command") && "invalid_command".equals(Json.str(error, "code"))) {
                full.what = AutoSettings.FULL_COMMAND;
                full.command = Json.str(Json.obj(value, "full"), "command", "");
                assertEquals(name, "[" + mode + ".command:" + reason + "]", String.valueOf(problems(f, m)));
                caught++;
            } else if (!fishing && field.startsWith("mining.idle.every.") && "range".equals(reason)) {
                JsonObject every = Json.obj(Json.obj(value, "idle"), "every");
                m.idle.min = (int) Json.num(every, "min", 0);
                m.idle.max = (int) Json.num(every, "max", 0);
                assertEquals(name, "[mining.every:range]", String.valueOf(problems(f, m)));
                caught++;
            } else if (!fishing && field.equals("mining.blocks") && "range".equals(reason) && strings(value.get("blocks"))) {
                // The editor offers only the list's blocks, once each, in the list's order: what it would send is either
                // refused here (nothing left) or the contract's form.
                List<String> sent = AutoSettings.ordered(m.blocks);
                if (sent == null || sent.isEmpty()) {
                    assertEquals(name, "[mining.blocks:range]", String.valueOf(problems(f, m)));
                    caught++;
                } else {
                    assertTrue(name, problems(f, m).isEmpty());
                    int last = -1;
                    for (String id : sent) {
                        int at = AutoSettings.MINING_BLOCKS.indexOf(id);
                        assertTrue(name, at > last);
                        last = at;
                    }
                    normalised++;
                }
            } else {
                // A type, a missing or an unknown part: the editor writes every part, of its own types, and nothing else.
                notFromTheEditor++;
            }
        }
        assertEquals(45, caught + normalised + notFromTheEditor);
        assertTrue("refusals caught: " + caught, caught >= 12);
    }

    private static List<AutoSettings.Problem> problems(AutoSettings.Fishing f, AutoSettings.Mining m) {
        return f != null ? f.problems() : m.problems();
    }

    private static boolean strings(JsonElement e) {
        if (e == null || !e.isJsonArray()) return false;
        JsonArray a = e.getAsJsonArray();
        for (JsonElement item : a) if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) return false;
        return true;
    }

    @Test
    public void shapeMatchesTheVectors() {
        int n = 0;
        for (JsonElement e : VECTORS.getAsJsonArray("shape")) {
            JsonObject c = e.getAsJsonObject();
            AutoOp.Problem got = AutoOp.problem(c.get("op"));
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
        assertEquals(31, n);
    }

    @Test
    public void accountSwitchesFollowTheViewVectors() {
        int n = 0;
        List<String> seen = new ArrayList<>();
        for (JsonElement e : VECTORS.getAsJsonArray("view")) {
            JsonObject c = e.getAsJsonObject();
            JsonObject expected = c.getAsJsonObject("expected");
            String mode = Json.str(Json.obj(c, "input"), "feature", "fishing");
            String name = Json.str(c, "name", "");
            CloudModel.Account a = CloudModel.Account.read(Vectors.json("{\"id\":\"a_7KQ2MZ3P9TW4XB1C\",\"name\":\"AfkBot_1\",\"host\":\"kz1\","
                    + "\"server\":{\"host\":\"mc.example.net\",\"port\":0},\"features\":{\"" + mode + "\":" + expected + "}}"));
            CloudModel.AutoFeature f = a.features.auto(mode);
            assertNotNull(name, f);
            assertEquals(name, "on".equals(Json.str(expected, "mode")), f.mode);
            assertEquals(name, Json.bool(expected, "on", false), f.on);
            assertEquals(name, Json.str(expected, "slot"), f.slot);
            JsonObject limited = Json.obj(expected, "limited");
            assertEquals(name, limited == null, f.limited == null);
            if (limited != null) {
                assertEquals(name, Json.str(limited, "tr"), f.limited.tr);
                assertEquals(name, Json.str(limited, "en"), f.limited.en);
            }
            assertEquals(name, f.mode, a.features.autoSwitch(mode));
            assertEquals(name, f.mode && f.on, a.features.autoPanel(mode));
            seen.add(mode);
            n++;
        }
        assertEquals(22, n);
        assertTrue(seen.contains("fishing"));
        assertTrue(seen.contains("mining"));
        // An account with a mode "off" shows nothing of it, the setting kept or not.
        assertFalse(CloudModel.Account.read(Vectors.json("{\"id\":\"a_1\",\"name\":\"AfkBot_1\",\"host\":\"kz1\",\"server\":{\"host\":\"x.net\","
                + "\"port\":0},\"features\":{\"fishing\":{\"mode\":\"off\",\"on\":true,\"slot\":null,\"limited\":null}}}")).features.autoPanel("fishing"));
    }
}
