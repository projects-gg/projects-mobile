package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The settings editor: checks before sending, the change's shape, secrets sealed or kept, a new server's reseal. */
public class CloudEditTest {
    private static final CloudModel.Limits LIMITS = CloudModel.Limits.defaults();

    private static CloudModel.Account sample() {
        return CloudModel.Account.list(Vectors.json(Vectors.resource("cloud/fixtures/accounts.json")), "accounts").get(0);
    }

    /** A sealer that writes what it sealed (the real one is checked against the vectors). */
    private static final class FakeSealer implements CloudEdit.Sealer {
        final List<String> sealed = new ArrayList<>();

        @Override
        public String seal(String text, String rowId) {
            sealed.add(rowId + "=" + text);
            return "pl1.k1." + rowId;
        }
    }

    private static String issues(List<CloudEdit.Issue> issues) {
        List<String> out = new ArrayList<>();
        for (CloudEdit.Issue i : issues) out.add(i.toString());
        return out.toString();
    }

    @Test
    public void theViewBecomesAnEditorAndBackUnchanged() throws Exception {
        CloudModel.Account account = sample();
        CloudEdit.Config config = CloudEdit.from(account.config);
        assertEquals(CloudEdit.Words.Kind.SEALED, config.commands.get(0).words.kind);
        assertEquals("/login ******", config.commands.get(0).words.preview);
        assertTrue(config.same(CloudEdit.from(account.config)));
        FakeSealer sealer = new FakeSealer();
        CloudEdit.Built built = CloudEdit.build(config, LIMITS, false, sealer, account.config.fingerprints());
        assertTrue(issues(built.issues), built.issues.isEmpty());
        assertNotNull(built.config);
        JsonArray commands = built.config.getAsJsonArray("commands");
        JsonObject login = commands.get(0).getAsJsonObject();
        assertTrue(login.get("secret").getAsBoolean());
        assertTrue(login.get("keep").getAsBoolean());
        assertFalse(login.has("sealed"));
        assertEquals("join", login.get("on").getAsString());
        assertEquals(2, login.get("delay").getAsInt());
        JsonObject daily = commands.get(2).getAsJsonObject();
        assertEquals(Arrays.asList("08:00", "20:30"), Arrays.asList(daily.getAsJsonArray("at").get(0).getAsString(),
                daily.getAsJsonArray("at").get(1).getAsString()));
        assertFalse(daily.has("delay"));
        JsonObject interval = commands.get(3).getAsJsonObject();
        assertEquals(600, interval.getAsJsonObject("every").get("min").getAsInt());
        JsonObject rule = built.config.getAsJsonArray("rules").get(0).getAsJsonObject();
        assertEquals("selam", rule.get("match").getAsString());
        assertFalse(rule.getAsJsonObject("reply").get("secret").getAsBoolean());
        assertEquals(1, built.config.get("v").getAsInt());
        assertTrue(sealer.sealed.isEmpty());
    }

    @Test
    public void aPasswordCommandIsSealedEvenWhenTypedPlain() throws Exception {
        CloudEdit.Config config = new CloudEdit.Config("", true, 60, 90);
        config.commands.add(new CloudEdit.Command("c1", true, "join", 0, 0, 0, Collections.<String>emptyList(), CloudEdit.Words.plain("/login hunter2")));
        config.commands.add(new CloudEdit.Command("c2", true, "join", 0, 0, 0, Collections.<String>emptyList(), CloudEdit.Words.secret("gizli söz")));
        FakeSealer sealer = new FakeSealer();
        CloudEdit.Built built = CloudEdit.build(config, LIMITS, false, sealer, Collections.<String, String>emptyMap());
        assertEquals(Arrays.asList("c1=/login hunter2", "c2=gizli söz"), sealer.sealed);
        JsonObject c1 = built.config.getAsJsonArray("commands").get(0).getAsJsonObject();
        assertEquals("pl1.k1.c1", c1.get("sealed").getAsString());
        assertEquals("/login ******", c1.get("preview").getAsString());
        assertEquals("******", built.config.getAsJsonArray("commands").get(1).getAsJsonObject().get("preview").getAsString());
    }

    @Test
    public void aRevealedRowKeepsItsSealUntilItChanges() throws Exception {
        CloudModel.Account account = sample();
        CloudEdit.Config config = CloudEdit.from(account.config);
        config.commands.get(0).words = CloudEdit.Words.revealed("/login hunter2", "/login ******");
        FakeSealer sealer = new FakeSealer();
        Map<String, String> fps = account.config.fingerprints();
        JsonObject kept = CloudEdit.build(config, LIMITS, false, sealer, fps).config.getAsJsonArray("commands").get(0).getAsJsonObject();
        assertTrue(kept.get("keep").getAsBoolean());
        config.commands.get(0).words = config.commands.get(0).words.typed("/login yeni");
        JsonObject changed = CloudEdit.build(config, LIMITS, false, sealer, fps).config.getAsJsonArray("commands").get(0).getAsJsonObject();
        assertEquals("pl1.k1.c1", changed.get("sealed").getAsString());
    }

    @Test
    public void aNewServerNeedsUnrevealedSecretsTypedAgain() throws Exception {
        CloudModel.Account account = sample();
        CloudEdit.Config config = CloudEdit.from(account.config);
        CloudEdit.Built built = CloudEdit.build(config, LIMITS, true, new FakeSealer(), account.config.fingerprints());
        assertNull(built.config);
        assertEquals("[commands[0].text:reseal]", issues(built.issues));
        // Revealed words are known here: sealed again for the new server by themselves.
        config.commands.get(0).words = CloudEdit.Words.revealed("/login hunter2", "/login ******");
        FakeSealer sealer = new FakeSealer();
        built = CloudEdit.build(config, LIMITS, true, sealer, account.config.fingerprints());
        assertTrue(built.issues.isEmpty());
        assertEquals(Collections.singletonList("c1=/login hunter2"), sealer.sealed);
    }

    @Test
    public void everythingTheServiceWouldRefuse() {
        CloudEdit.Config config = new CloudEdit.Config("x", true, 5, 4000);
        config.commands.add(new CloudEdit.Command("c1", true, "join", 61, 0, 0, Collections.<String>emptyList(), CloudEdit.Words.plain(" bad")));
        config.commands.add(new CloudEdit.Command("c1", true, "interval", 0, 30, 20, Collections.<String>emptyList(), CloudEdit.Words.plain("/a")));
        config.commands.add(new CloudEdit.Command("Bad", true, "daily", 0, 0, 0, Arrays.asList("8:00"), CloudEdit.Words.plain("\\quit")));
        config.commands.add(new CloudEdit.Command("c4", true, "daily", 0, 0, 0, Arrays.asList("08:00", "08:00"), CloudEdit.Words.plain("%login%")));
        config.commands.add(new CloudEdit.Command("c5", true, "daily", 0, 0, 0, Collections.<String>emptyList(), CloudEdit.Words.plain("/b")));
        config.commands.add(new CloudEdit.Command("console", true, "sometimes", 0, 0, 0, Collections.<String>emptyList(), CloudEdit.Words.plain("")));
        config.rules.add(new CloudEdit.Rule("r1", true, " ", false, "player", true, 5, CloudEdit.Words.plain("ok")));
        config.rules.add(new CloudEdit.Rule("r2", true, "(a+)+", true, "server", true, 60, CloudEdit.Words.plain("ok")));
        config.rules.add(new CloudEdit.Rule("r3", true, "(?<=x)y", true, "everywhere", false, 60, CloudEdit.Words.plain("ok")));
        List<CloudEdit.Issue> issues = CloudEdit.validate(config, LIMITS);
        assertEquals("[general.owner:format, general.antiAfk:range, commands[0].delay:range, commands[0].text:space, "
                + "commands[1].id:duplicate, commands[1].every:order, commands[2].id:format, commands[2].at:format, commands[2].text:backslash, "
                + "commands[3].at:duplicate, commands[3].text:variable, commands[4].at:empty, commands[5].id:format, commands[5].on:format, "
                + "commands[5].text:empty, rules[0].match:empty, rules[0].cooldown:range, rules[1].match:tooComplex, "
                + "rules[1].ownerOnly:server, rules[2].match:invalid, rules[2].from:format]", issues(issues));
    }

    @Test
    public void patterns() {
        assertNull(CloudEdit.checkPattern("selam", false));
        assertNull(CloudEdit.checkPattern("^(selam|merhaba)\\s+\\w+", true));
        assertNull(CloudEdit.checkPattern("(?i)selam", true));
        assertEquals("invalid", CloudEdit.checkPattern("(a", true));
        assertEquals("invalid", CloudEdit.checkPattern("\\1", true));
        assertEquals("invalid", CloudEdit.checkPattern("(?=a)", true));
        assertEquals("tooComplex", CloudEdit.checkPattern("a{1001}", true));
        assertEquals("tooComplex", CloudEdit.checkPattern("(\\w+\\s?)*", true));
        assertNull(CloudEdit.checkPattern("(ab)?c+", true));
        StringBuilder long257 = new StringBuilder();
        for (int i = 0; i < 257; i++) long257.append('ğ');
        assertEquals("tooLong", CloudEdit.checkPattern(long257.toString(), false));
    }

    @Test
    public void refusalPathsPointAtTheField() {
        assertEquals("commands[2].every:range", CloudEdit.Issue.fromPath("commands[2].every.min", "range").toString());
        assertEquals("commands[1].text:sealed", CloudEdit.Issue.fromPath("commands[1].sealed", "sealed").toString());
        assertEquals("rules[0].reply:secret", CloudEdit.Issue.fromPath("rules[0].reply", "secret").toString());
        assertEquals("rules[3].match:invalid", CloudEdit.Issue.fromPath("rules[3].match", "invalid").toString());
        assertEquals("commands.count:limit", CloudEdit.Issue.fromPath("commands", "limit").toString());
        assertEquals("general.antiAfk:range", CloudEdit.Issue.fromPath("antiAfk.delayMin", "range").toString());
    }

    @Test
    public void thePatchCarriesOnlyWhatChanged() {
        CloudModel.Account account = sample();
        JsonObject none = CloudEdit.patch(account, account.name, account.server, account.version, account.lang, null);
        assertEquals(0, none.size());
        JsonObject some = CloudEdit.patch(account, "Ahmet_2", new CloudServer("mc.projects.gg", 0), "26.3", "en", null);
        assertEquals("Ahmet_2", some.get("name").getAsString());
        assertEquals("mc.projects.gg", some.getAsJsonObject("server").get("host").getAsString());
        assertFalse(some.has("version"));
        assertEquals("en", some.get("lang").getAsString());
    }

    @Test
    public void newRowIdsAreFreeAndOfTheContractsForm() {
        Map<String, String> unused = new HashMap<>();
        java.util.Set<String> taken = new java.util.HashSet<>(Arrays.asList("c1", "c2"));
        String id = CloudApi.newRowId(new java.util.Random(7), taken);
        assertTrue(CloudEdit.isRowId(id));
        assertFalse(taken.contains(id));
        assertTrue(unused.isEmpty());
    }
}
