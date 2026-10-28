package gg.projects.mobile.cloud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.cloud.console.Bars;
import gg.projects.mobile.cloud.console.ConsoleExport;
import gg.projects.mobile.cloud.console.ConsoleKinds;
import gg.projects.mobile.cloud.inventory.InvFrames;
import gg.projects.mobile.social.Json;

/** CAFK-FEAT's model on the phone: the switches, the account's features, kinds, the strip, downloads, bulk copies. */
public class CloudFeatTest {
    private static final String ACCOUNT = "{\"id\":\"a_7KQ2MZ3P9TW4XB1C\",\"name\":\"Ahmet_AFK\",\"host\":\"kz1\","
            + "\"server\":{\"host\":\"play.projects.gg\",\"port\":0},\"config\":{\"v\":1,\"owner\":\"Kaan_07\","
            + "\"antiAfk\":{\"enabled\":true,\"delayMin\":60,\"delayMax\":90},"
            + "\"commands\":[{\"id\":\"c1\",\"enabled\":true,\"on\":\"join\",\"delay\":3,\"secret\":true,\"preview\":\"/login ******\",\"fp\":\"abc\"}],"
            + "\"rules\":[{\"id\":\"r1\",\"enabled\":true,\"match\":\"selam\",\"regex\":false,\"from\":\"any\",\"ownerOnly\":false,\"cooldown\":60,"
            + "\"reply\":{\"secret\":false,\"text\":\"Selam {u}\"}}]%INV%},"
            + "\"features\":{\"inventory\":{\"mode\":\"full\",\"on\":true},\"consoleKinds\":true,\"placeholders\":true,\"legacyRules\":true}}";

    private static CloudModel.Account account(String inventory) {
        return CloudModel.Account.read(Vectors.json(ACCOUNT.replace("%INV%", inventory)));
    }

    @Test
    public void switchesAndFeaturesRead() {
        CloudModel.Offer old = CloudModel.Offer.read(Vectors.json("{\"mode\":\"on\"}"));
        assertEquals("off", old.features.inventory);
        assertFalse(old.features.consoleKinds);
        assertEquals(CloudModel.DEFAULT_PLACEHOLDER_COMMANDS, old.features.placeholderCommands);
        CloudModel.Offer on = CloudModel.Offer.read(Vectors.json("{\"mode\":\"on\",\"features\":{\"inventory\":\"view\",\"consoleKinds\":true,"
                + "\"placeholders\":true,\"placeholderCommands\":[\"MSG\",\"tell\",\"msg\"]}}"));
        assertEquals("view", on.features.inventory);
        assertEquals(Arrays.asList("msg", "tell"), on.features.placeholderCommands);
        assertEquals("off", CloudModel.Offer.read(Vectors.json("{\"features\":{\"inventory\":\"later\"}}")).features.inventory);

        CloudModel.Account a = account("");
        assertTrue(a.features.inventory());
        assertTrue(a.features.inventoryMoves());
        assertTrue(a.features.inventoryOn);
        assertTrue(a.features.legacyRules);
        assertNull(a.config.inventory);
        // No machine: everything off whatever the object says.
        CloudModel.Account noHost = CloudModel.Account.read(Vectors.json(ACCOUNT.replace("%INV%", "").replace("\"host\":\"kz1\",", "")));
        assertFalse(noHost.features.inventory());
        assertFalse(noHost.features.placeholders);
        // An older service sends no features: all off.
        CloudModel.Account older = CloudModel.Account.read(Vectors.json("{\"id\":\"a_7KQ2MZ3P9TW4XB1C\",\"host\":\"kz1\"}"));
        assertFalse(older.features.inventory());
        assertFalse(older.features.consoleKinds);
    }

    @Test
    public void theInventoryChoiceTravelsOnlyWhenKnown() throws Exception {
        CloudModel.Account never = account("");
        CloudEdit.Config config = CloudEdit.from(never.config);
        assertNull(config.inventory);
        CloudEdit.Built built = CloudEdit.build(config, CloudModel.Limits.defaults(), false, (text, row) -> "sealed", never.config.fingerprints());
        assertNotNull(built.config);
        assertFalse(built.config.has("inventory"));
        // The stored secret is kept, never re-sent.
        assertTrue(built.config.getAsJsonArray("commands").get(0).getAsJsonObject().get("keep").getAsBoolean());

        CloudModel.Account chosen = account(",\"inventory\":{\"enabled\":false}");
        CloudEdit.Config c2 = CloudEdit.from(chosen.config);
        assertEquals(Boolean.FALSE, c2.inventory);
        CloudEdit.Config turnedOn = c2.copy();
        turnedOn.inventory = true;
        assertTrue(turnedOn.onlyInventoryDiffers(c2));
        assertFalse(c2.same(turnedOn));
        CloudEdit.Built on = CloudEdit.build(turnedOn, CloudModel.Limits.defaults(), false, (text, row) -> "sealed", chosen.config.fingerprints());
        assertTrue(on.config.getAsJsonObject("inventory").get("enabled").getAsBoolean());
        turnedOn.owner = "Mert";
        assertFalse(turnedOn.onlyInventoryDiffers(c2));
    }

    @Test
    public void linesCarryTheirKind() {
        JsonObject batch = Vectors.json("{\"lines\":[{\"at\":1,\"text\":\"✉ »» Kaan_07: selam Ahmet_AFK\",\"k\":\"n\",\"c\":\"pm\",\"who\":\"Kaan_07\"},"
                + "{\"at\":2,\"text\":\"[Envanter] Menü açıldı: Kitler\",\"k\":\"n\",\"c\":\"cli\",\"goto\":\"inventory\"},"
                + "{\"at\":3,\"text\":\"/spawn\",\"k\":\"s\",\"c\":\"cli\"},{\"at\":4,\"text\":\"eski\",\"k\":\"e\"},"
                + "{\"at\":5,\"text\":\"x\",\"k\":\"n\",\"c\":\"new_kind\",\"who\":\"bad name!\",\"goto\":\"elsewhere\"}]}");
        List<CloudModel.ConsoleLine> lines = CloudModel.ConsoleLine.list(batch, "lines");
        assertEquals("pm", lines.get(0).c);
        assertEquals("Kaan_07", lines.get(0).who);
        assertEquals("inventory", lines.get(1).go);
        assertNull(lines.get(4).who);
        assertNull(lines.get(4).go);

        assertEquals(ConsoleKinds.Filter.PRIVATE, ConsoleKinds.category(lines.get(0)));
        assertEquals(ConsoleKinds.Filter.MINE, ConsoleKinds.category(lines.get(2)));
        assertNull(ConsoleKinds.category(lines.get(3)));
        assertEquals(ConsoleKinds.Filter.CONNECTION, ConsoleKinds.category(lines.get(4)));
        assertTrue(ConsoleKinds.shows(ConsoleKinds.Filter.ALL, lines.get(3)));
        assertFalse(ConsoleKinds.shows(ConsoleKinds.Filter.CONNECTION, lines.get(3)));
        assertTrue(ConsoleKinds.mentions(lines.get(0), "ahmet_afk"));
        assertFalse(ConsoleKinds.mentions(lines.get(0), "Kaan_07"));
        assertEquals(ConsoleKinds.Tone.ERROR, ConsoleKinds.tone(lines.get(3)));
        assertEquals(ConsoleKinds.Tone.MINE, ConsoleKinds.tone(lines.get(2)));
        assertTrue(ConsoleKinds.found(lines.get(1), "KİTLER"));
        assertTrue(ConsoleKinds.found(lines.get(1), "menü"));
    }

    private static final ConsoleExport.Names TURKISH = kind -> {
        switch (kind) {
            case CHAT: return "Sohbet";
            case COMMAND: return "Komut";
            default: return "Konsol";
        }
    };
    private static final ConsoleExport.Names ENGLISH = kind -> kind == ConsoleKinds.FileKind.COMMAND ? "Command" : kind.name();

    @Test
    public void downloadsFollowTheContract() {
        // 25 Oct 2026 23:59:58 and 26 Oct 00:00:01 in Turkey (UTC+3).
        long first = 1_792_961_998L;
        List<CloudModel.ConsoleLine> lines = Arrays.asList(
                new CloudModel.ConsoleLine(first, "Merhaba", "n", "chat", "Mert", null),
                new CloudModel.ConsoleLine(first + 3, "/spawn", "s", "cli", null, null),
                new CloudModel.ConsoleLine(first + 4, "eski\nsatır", "n"));
        String text = ConsoleExport.text(lines, TURKISH);
        String[] rows = text.split("\r\n");
        assertEquals("--- 25.10.2026 ---", rows[0]);
        assertEquals("[23:59:58] [Sohbet] Merhaba", rows[1]);
        assertEquals("--- 26.10.2026 ---", rows[2]);
        assertEquals("[00:00:01] [Komut] /spawn", rows[3]);
        assertEquals("[00:00:02] [Konsol] eski satır", rows[4]);
        assertTrue(text.endsWith("\r\n"));
        byte[] bytes = ConsoleExport.bytes(text);
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Arrays.copyOf(bytes, 3));
        assertEquals(text, new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
        assertEquals("Ahmet_AFK-konsol-2026-10-26.txt", ConsoleExport.fileName("Ahmet_AFK", "tr", first + 3));
        assertEquals("Ahmet_AFK-console-2026-10-25.txt", ConsoleExport.fileName("Ahmet_AFK", "en", first));
        assertEquals(first + 2, ConsoleExport.from(ConsoleExport.Range.TODAY, first + 3));
        assertEquals(first + 3 - 3600, ConsoleExport.from(ConsoleExport.Range.HOUR, first + 3));
        assertEquals("[00:00:01] [Command] /spawn", ConsoleExport.text(lines, ENGLISH).split("\r\n")[3]);
    }

    @Test
    public void theStripShowsWhatHolds() {
        Bars bars = Bars.read(Vectors.json("{\"t\":\"bars\",\"acct\":\"a\",\"actionbar\":{\"text\":\"Bakiye: 1.250\",\"untilMs\":5000},"
                + "\"title\":null,\"boss\":[{\"id\":\"b1\",\"text\":\"Etkinlik\",\"progress\":1.4,\"color\":\"purple\"},{\"id\":\"b2\",\"text\":\"‮X\",\"progress\":0.5}]}"));
        assertEquals("Bakiye: 1.250", bars.actionbarAt(4_999));
        assertNull(bars.actionbarAt(5_000));
        assertEquals(2, bars.boss.size());
        assertEquals(1f, bars.boss.get(0).progress, 0.0001f);
        assertEquals("X", bars.boss.get(1).text);
        assertEquals("white", bars.boss.get(1).color);
        assertFalse(bars.empty(6_000));
        assertEquals(5_000, bars.nextChange(1_000));
        assertTrue(Bars.none().empty(0));
    }

    @Test
    public void liveFramesReachTheirCalls() {
        final List<String> heard = new ArrayList<>();
        CloudLiveFrames.Listener l = new CloudLiveFrames.Listener() {
            @Override public void hello(boolean hostOnline, List<String> accounts, long leaseSeconds, long now) {}
            @Override public void state(String acct, JsonObject live) {}
            @Override public void console(String acct, List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}
            @Override public void host(boolean online) {}
            @Override public void error(String code, String acct) {}
            @Override public void ai(String acct, JsonObject ai) {}
            @Override public void approval(String acct, CloudModel.Approval approval) {}
            @Override public void bye(String reason) {}
            @Override public void invState(String acct, InvFrames.Status status) { heard.add("invst " + status.state + " " + status.reason); }
            @Override public void inventory(String acct, InvFrames.Inv frame) { heard.add("inv " + frame.items.size()); }
            @Override public void window(String acct, InvFrames.Win frame) { heard.add("win " + frame.wseq + " " + frame.closed); }
            @Override public void invResult(String id, InvFrames.Error error) { heard.add("invres " + id + " " + (error == null ? "ok" : error.code)); }
            @Override public void bars(String acct, Bars bars) { heard.add("bars " + bars.boss.size()); }
        };
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"invst\",\"acct\":\"a\",\"state\":\"off\",\"reason\":\"restart\"}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"inv\",\"acct\":\"a\",\"held\":0,\"cursor\":null,\"items\":[]}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"win\",\"acct\":\"a\",\"wseq\":5,\"closed\":true}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"invres\",\"id\":\"m1\",\"ok\":true}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"invres\",\"id\":\"m2\",\"error\":{\"code\":\"rate_limited\",\"retryAfter\":2}}"), l));
        assertTrue(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"bars\",\"acct\":\"a\",\"boss\":[]}"), l));
        assertFalse(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"inv\"}"), l));
        assertEquals(Arrays.asList("invst off restart", "inv 0", "win 5 true", "invres m1 ok", "invres m2 rate_limited", "bars 0"), heard);
        assertEquals("{\"t\":\"watch\",\"acct\":\"a\",\"inv\":true}", CloudLiveFrames.watch("a", false, true).toString());
        assertEquals("{\"t\":\"watch\",\"acct\":\"a\"}", CloudLiveFrames.watch("a", false).toString());
    }

    @Test
    public void bulkCopiesLeaveNamesAndTheInventoryAlone() {
        CloudModel.Account source = account(",\"inventory\":{\"enabled\":true}");
        Map<String, String> secrets = CloudBulk.secretRows(source.config);
        assertEquals(Collections.singletonMap("c1", "/login ******"), secrets);
        Map<String, String> typed = new HashMap<>();
        typed.put("c1", "/login gizli123");
        CloudEdit.Config copy = CloudBulk.copied(source.config, typed);
        assertNull(copy.inventory);
        assertEquals(CloudEdit.Words.Kind.SECRET, copy.commands.get(0).words.kind);
        assertEquals("/login gizli123", copy.commands.get(0).words.text);
        assertEquals("Kaan_07", copy.owner);
        CloudBulk.Outcome outcome = new CloudBulk.Outcome();
        outcome.ok();
        outcome.fail("account_expired");
        outcome.fail("server_full");
        assertEquals(1, outcome.done);
        assertEquals(2, outcome.failed);
        assertEquals(CloudBulk.Reason.EXPIRED, outcome.first);
        assertEquals(CloudBulk.Reason.STAFF, CloudBulk.reason("player_restricted"));
        assertEquals(CloudBulk.Reason.OTHER, CloudBulk.reason("teapot"));
        JsonObject patch = CloudBulk.patch(source, new JsonObject(), null, null);
        assertFalse(patch.has("name"));
        assertFalse(patch.has("lang"));
        assertTrue(patch.has("config"));
        assertEquals("a", Json.str(Vectors.json("{\"x\":\"a\"}"), "x"));
    }
}
