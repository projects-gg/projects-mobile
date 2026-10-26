package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.social.Json;

/** The shared placeholder vectors (rule-placeholder-v1.json): the save checks, capture counts, the value class and runs. */
public class CloudPlaceholdersTest {
    private static final JsonObject V = Vectors.load("cloud/rule-placeholder-v1.json");

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e != null && e.isJsonPrimitive()) return e.getAsString();
        StringBuilder out = new StringBuilder();
        for (String unit : Json.strings(o, "utf16")) out.append((char) Integer.parseInt(unit, 16));
        return out.toString();
    }

    /** What a save answers for one template, in the service's order: the text rule, password commands, then placeholders. */
    private static String saveProblem(JsonObject t, List<String> defaults) {
        String text = text(t, "text");
        String kind = Json.str(t, "kind", "reply");
        CloudText.Problem textProblem = CloudText.check(text);
        if (textProblem != null) return textProblem.code;
        boolean secret = Json.bool(t, "secret", false);
        if (!secret && CloudText.isSensitive(text)) return "secret";
        if (secret || "console".equals(kind)) return null;
        if ("command".equals(kind)) {
            CloudPlaceholders.Problem p = CloudPlaceholders.checkCommand(text);
            return p == null ? null : p.reason;
        }
        JsonObject rule = Json.obj(t, "rule");
        List<String> commands = t.has("placeholderCommands") ? Json.strings(t, "placeholderCommands") : defaults;
        int captures = CloudPlaceholders.captureCount(Json.str(rule, "match", ""), Json.bool(rule, "regex", false));
        CloudPlaceholders.Problem p = CloudPlaceholders.checkReply(text, captures, Json.str(rule, "from", "any"), commands);
        return p == null ? null : p.toString();
    }

    @Test
    public void saveChecksFollowTheVectors() {
        List<String> defaults = Json.strings(V, "placeholderCommands");
        int n = 0;
        for (JsonObject t : Json.objects(V, "templates")) {
            JsonObject problem = Json.obj(t, "problem");
            String expected = null;
            if (problem != null) {
                expected = Json.str(problem, "reason");
                long part = Json.num(problem, "part", 0);
                if (part > 0) expected = expected + "/" + part;
            }
            assertEquals(Json.str(t, "name"), expected, saveProblem(t, defaults));
            if (t.has("captures")) {
                JsonObject rule = Json.obj(t, "rule");
                assertEquals(Json.str(t, "name"), Json.num(t, "captures", -1),
                        CloudPlaceholders.captureCount(Json.str(rule, "match", ""), Json.bool(rule, "regex", false)));
            }
            n++;
        }
        assertTrue(n >= 120);
    }

    @Test
    public void captureCountsAsRustNumbersThem() {
        int n = 0;
        for (JsonObject c : Json.objects(V, "captures")) {
            assertEquals(Json.str(c, "pattern"), Json.num(c, "count", -1), CloudPlaceholders.captureCount(Json.str(c, "pattern", ""), true));
            n++;
        }
        assertTrue(n >= 30);
        assertEquals(0, CloudPlaceholders.captureCount("(a)(b)", false));
    }

    @Test
    public void valuesFollowTheStrictClass() {
        for (JsonObject v : Json.objects(V, "values")) {
            assertEquals(Json.str(v, "name"), Json.bool(v, "ok", false), CloudPlaceholders.valueOk(text(v, "value")));
        }
        assertFalse(CloudPlaceholders.valueOk(""));
        assertFalse(CloudPlaceholders.valueOk(null));
    }

    @Test
    public void runsAnswerAsTheBotDoes() {
        String self = Json.str(V, "self");
        int n = 0;
        for (JsonObject r : Json.objects(V, "runs")) {
            JsonObject rule = Json.obj(r, "rule");
            JsonObject line = Json.obj(r, "line");
            String sender = Json.str(line, "sender", "");
            CloudPlaceholders.Run run = CloudPlaceholders.run(Json.str(rule, "match", ""), Json.bool(rule, "regex", false),
                    Json.str(rule, "from", "any"), Json.str(rule, "reply", ""), Json.str(line, "type", "public"),
                    sender.isEmpty() ? null : sender, Json.str(line, "message", ""), self);
            String name = Json.str(r, "name");
            assertEquals(name, Json.str(r, "reply"), run.reply);
            if (run.reply == null) assertEquals(name, Json.str(r, "why"), run.why);
            n++;
        }
        assertTrue(n >= 39);
    }

    @Test
    public void tokensAreExact() {
        assertEquals(2, CloudPlaceholders.tokens("/msg {u} {1} x").size());
        assertEquals(0, CloudPlaceholders.tokens("{0} {10} {U} { u } {u").size());
        assertEquals(1, CloudPlaceholders.tokens("{10}{9}").size());
        assertEquals(9, CloudPlaceholders.tokens("{10}{9}").get(0).part);
    }

    @Test
    public void chipsFollowThePattern() {
        assertEquals(Arrays.asList("{u}"), CloudPlaceholders.chips("selam", false, "any"));
        assertEquals(Arrays.asList("{u}", "{1}", "{2}"), CloudPlaceholders.chips("^(a) (?<b>c)$", true, "player"));
        assertEquals(Collections.singletonList("{1}"), CloudPlaceholders.chips("^Ödül: (.+)$", true, "server"));
    }

    @Test
    public void tryItFillsTheOwner() {
        CloudPlaceholders.Run run = CloudPlaceholders.tryIt("^!davet (\\w+)$", true, "any", "/msg {u} {1} eklendi.", "!davet Ali", "Kaan_07");
        assertEquals("/msg Kaan_07 Ali eklendi.", run.reply);
        assertEquals("nomatch", CloudPlaceholders.tryIt("selam", false, "any", "Selam {u}", "merhaba", "Oyuncu").why);
        assertEquals("value", CloudPlaceholders.tryIt("^x (.+)$", true, "any", "Tamam {1}", "x a.b", "Oyuncu").why);
        assertEquals("Tamam, Elmas.", CloudPlaceholders.tryIt("^Ödül: (.+)$", true, "server", "Tamam, {1}.", "Ödül: Elmas", "Oyuncu").reply);
    }

    @Test
    public void editorChecksOnlyWithTheSwitchOn() {
        CloudEdit.Config config = new CloudEdit.Config("", true, 60, 90);
        config.rules.add(new CloudEdit.Rule("r1", true, "selam", false, "any", false, 60, CloudEdit.Words.plain("{1} geldi")));
        config.commands.add(new CloudEdit.Command("c1", true, "join", 5, 300, 600, Collections.<String>emptyList(),
                CloudEdit.Words.plain("/spawn {u}")));
        assertTrue(CloudEdit.validate(config, CloudModel.Limits.defaults()).isEmpty());
        CloudEdit.Checks on = new CloudEdit.Checks(true, CloudModel.DEFAULT_PLACEHOLDER_COMMANDS);
        List<String> problems = new ArrayList<>();
        for (CloudEdit.Issue i : CloudEdit.validate(config, CloudModel.Limits.defaults(), on)) problems.add(i.toString());
        assertEquals(Arrays.asList("commands[0].text:placeholder_row", "rules[0].reply:placeholder_part/1"), problems);
        // A secret row is never read.
        config.commands.get(0).words = CloudEdit.Words.secret("/spawn {u}");
        config.rules.get(0).reply = CloudEdit.Words.plain("Selam {u}");
        assertTrue(CloudEdit.validate(config, CloudModel.Limits.defaults(), on).isEmpty());
        assertNull(CloudEdit.placeholderIssue(config.rules.get(0), 0, on));
    }
}
