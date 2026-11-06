package gg.projects.mobile.afk.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The rules the phone shares with the desktop AFK (pl-afk): {@code afk/afk-rules.json} is a copy of ProLauncher2's
 * {@code crates/pl-afk/tests/shared/afk-rules.json}, which pl-afk's own test checks against its Rust code.
 */
public class AfkRulesTest {
    private static JsonObject rules;

    @BeforeClass
    public static void load() throws Exception {
        try (InputStream in = AfkRulesTest.class.getClassLoader().getResourceAsStream("afk/afk-rules.json")) {
            assertNotNull("afk/afk-rules.json", in);
            rules = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<JsonObject> cases(JsonObject from, String key) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : from.getAsJsonArray(key)) out.add(e.getAsJsonObject());
        return out;
    }

    private static AfkRestart.KickCause cause(JsonElement e) {
        return e == null || e.isJsonNull() ? null : AfkRestart.KickCause.ofKey(e.getAsString());
    }

    private static boolean kick(String reason) {
        return reason.equals("inGameKick") || reason.equals("loginRejected");
    }

    private static AfkDrop.Kind kind(String reason) {
        switch (reason) {
            case "inGameKick": return AfkDrop.Kind.KICKED;
            case "loginRejected": return AfkDrop.Kind.LOGIN_REJECTED;
            case "connectionLost": return AfkDrop.Kind.CONNECTION_LOST;
            case "unsupportedVersion": return AfkDrop.Kind.UNSUPPORTED_VERSION;
            default: throw new AssertionError(reason);
        }
    }

    @Test
    public void restartDelaysBackOffAlike() {
        for (JsonObject c : cases(rules, "delays")) {
            double min = c.get("min").getAsDouble();
            double max = c.get("max").getAsDouble();
            JsonArray seconds = c.getAsJsonArray("seconds");
            for (int i = 0; i < seconds.size(); i++) {
                assertEquals(c + " attempt " + (i + 1), seconds.get(i).getAsDouble(),
                        AfkRestart.delaySeconds(min, max, i + 1), 1e-9);
            }
        }
        for (JsonObject c : cases(rules, "delayAt")) {
            assertEquals(c.toString(), c.get("seconds").getAsDouble(), AfkRestart.delaySeconds(c.get("min").getAsDouble(),
                    c.get("max").getAsDouble(), c.get("attempt").getAsInt()), 1e-9);
        }
        for (JsonObject c : cases(rules, "spread")) {
            assertEquals(c.toString(), c.get("millis").getAsLong(), AfkRestart.spreadDelayMs(c.get("min").getAsDouble(),
                    c.get("max").getAsDouble(), c.get("attempt").getAsInt(), c.get("spread").getAsDouble()));
        }
        for (JsonObject c : cases(rules, "lasting")) {
            assertEquals(c.toString(), c.get("lasting").getAsBoolean(),
                    AfkRestart.lasting(c.get("inGameSeconds").getAsLong() * 1000L, cause(c.get("cause"))));
        }
    }

    @Test
    public void kickMessagesNameTheSameCauses() {
        for (JsonObject c : cases(rules, "kickCauses")) {
            String reason = c.get("reason").getAsString();
            assertEquals(c.toString(), cause(c.get("cause")), AfkRestart.kickCause(kick(reason), c.get("message").getAsString()));
        }
    }

    @Test
    public void dropsEndInTheSameDecisions() {
        for (JsonObject c : cases(rules, "decisions")) {
            String message = c.get("message").getAsString();
            AfkDrop drop = new AfkDrop(kind(c.get("reason").getAsString()), message, message, null);
            AfkRestart.Decision d = AfkRestart.decide(drop, c.get("attempts").getAsInt(), c.get("repeats").getAsInt());
            String got = !d.stops() ? "restart " + d.attempt
                    : d.cause == null ? "fail unsupportedVersion" : "refused " + d.cause.key;
            assertEquals(c.toString(), c.get("expect").getAsString(), got);
        }
    }

    @Test
    public void connectionsArePacedAlike() {
        for (JsonObject c : cases(rules, "pacing")) {
            AfkPacer pacer = new AfkPacer();
            long spacing = c.get("spacingMs").getAsLong();
            long start = 5_000_000L;
            for (JsonObject step : cases(c, "steps")) {
                long slot = pacer.reserve(step.get("destination").getAsString(), spacing, start + step.get("atMs").getAsLong());
                assertEquals(step.toString(), step.get("slotMs").getAsLong(), slot - start);
            }
        }
        for (JsonObject c : cases(rules, "destinations")) {
            assertEquals(c.get("destination").getAsString(), AfkPacer.destination(c.get("host").getAsString()));
        }
    }

    @Test
    public void secretsAreMaskedAlike() {
        for (JsonObject c : cases(rules, "sensitiveCommands")) {
            assertEquals(c.toString(), c.get("sensitive").getAsBoolean(),
                    AfkLineFilter.isSensitiveCommand(c.get("command").getAsString()));
        }
        for (JsonObject c : cases(rules, "masking")) {
            List<String> commands = new ArrayList<>();
            for (JsonElement e : c.getAsJsonArray("loginCommands")) commands.add(e.getAsString());
            AfkLineFilter filter = new AfkLineFilter(commands);
            for (JsonElement e : c.getAsJsonArray("userCommands")) filter.addUserCommand(e.getAsString());
            for (JsonObject line : cases(c, "lines")) {
                String raw = line.get("raw").getAsString();
                assertEquals(line.toString(), line.get("masked").getAsString(), filter.mask(raw));
                assertEquals(line.toString(), line.get("kind").getAsString(),
                        filter.classify(raw).name().toLowerCase(java.util.Locale.ROOT));
            }
        }
    }
}
