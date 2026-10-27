package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** AFK+ settings edited in place, the Worker's checks, amounts, the assistant's state and the history's changes. */
public class PlusTest {
    private static final long NOW = 1792846800L;

    private static CloudModel.PlusDoc doc() {
        return CloudModel.PlusDoc.read(Vectors.json(Vectors.resource("cloud/fixtures/plus.json")));
    }

    private static CloudModel.PlusCatalog catalog() {
        return CloudModel.PlusCatalog.read(Vectors.json(Vectors.resource("cloud/fixtures/catalog.json")));
    }

    private static String problems(List<PlusConfig.Problem> problems) {
        List<String> out = new ArrayList<>();
        for (PlusConfig.Problem p : problems) out.add(p.toString());
        return out.toString();
    }

    @Test
    public void readsAndChangesInPlace() {
        CloudModel.PlusDoc doc = doc();
        PlusConfig config = new PlusConfig(doc.config);
        assertTrue(config.enabled());
        assertEquals("back_evening", config.statusPreset());
        assertEquals(Integer.valueOf(80), config.saveAt());
        assertTrue(config.canSpend());
        assertEquals(2, config.windows().size());
        assertEquals(Arrays.asList(6, 7), config.windows().get(1).days);
        PlusConfig.Row elytra = config.rows(true).get(0);
        assertEquals(40000, elytra.bound());
        assertEquals(90, elytra.minDurabilityPct());
        elytra.setBound(45000);
        config.setStatus("Akşam 8'de dönerim");
        assertNull(config.statusPreset());
        config.setSaveAt(null);
        assertNull(config.saveAt());
        JsonObject sent = config.toJson();
        assertEquals(45000, sent.getAsJsonArray("buy").get(0).getAsJsonObject().get("max").getAsInt());
        assertTrue(sent.getAsJsonObject("replies").get("saveAt").isJsonNull());
        // The stored document is untouched.
        assertEquals(40000, doc.config.getAsJsonArray("buy").get(0).getAsJsonObject().get("max").getAsInt());
        assertTrue(problems(config.check(catalog(), NOW)), config.check(catalog(), NOW).isEmpty());
    }

    @Test
    public void newRowsAreInTheContractsShape() {
        PlusConfig config = new PlusConfig(doc().config);
        String id = config.newRowId(new java.util.Random(3));
        assertTrue(id.matches("^[a-z0-9]{1,8}$"));
        config.addRow(true, PlusConfig.newRow(true, id, "minecraft:totem_of_undying", 1, "total", 20000, 25000, "balanced", "above"));
        config.addRow(false, PlusConfig.newRow(false, config.newRowId(new java.util.Random(4)), "minecraft:netherite_ingot", 8, "each", 9000, 8000,
                "patient", "auto"));
        assertTrue(problems(config.check(catalog(), NOW)), config.check(catalog(), NOW).isEmpty());
        JsonObject added = config.toJson().getAsJsonArray("buy").get(1).getAsJsonObject();
        assertTrue(added.has("req"));
        assertTrue(added.get("notify").getAsBoolean());
        JsonObject sell = config.toJson().getAsJsonArray("sell").get(1).getAsJsonObject();
        assertFalse(sell.has("req"));
        assertFalse(sell.has("notify"));
        assertEquals(8000, sell.get("min").getAsInt());
        config.removeRow(true, id);
        assertEquals(1, config.rows(true).size());
    }

    @Test
    public void theWorkersChecks() {
        PlusConfig config = new PlusConfig(doc().config);
        PlusConfig.Row elytra = config.rows(true).get(0);
        elytra.setTarget(50000);
        PlusConfig.Row diamond = config.rows(false).get(0);
        diamond.setBound(130);
        config.addRow(true, PlusConfig.newRow(true, "b1", "minecraft:dirt", 0, "total", 5, 5, "quick", "auto"));
        config.setLimit("approvalAbove", 60000L);
        config.setBlocked(Arrays.asList("SomePlayer", "someplayer", "no"));
        config.setScheduleOn(true);
        config.setWindows(Arrays.asList(new PlusConfig.Window(Arrays.asList(1, 8), "16:00", "25:00")));
        config.setPauseUntil(NOW + 100L * 86_400);
        String found = problems(config.check(catalog(), NOW));
        assertEquals("[buy[0].max:order, buy[1].id:id, buy[1].item:item, buy[1].qty:range, sell[0].min:order, "
                + "limits.approvalAbove:limits, blocked[1]:name, blocked[2]:name, schedule.windows[0].days:range, schedule.windows[0]:range, "
                + "pauseUntil:range]", found);
        PlusConfig.Problem p = config.check(catalog(), NOW).get(0);
        assertEquals("buy", p.list());
        assertEquals(0, p.index());
    }

    @Test
    public void amountsAndTypedNumbers() {
        assertEquals("37.500", PlusFormat.money(37500, "tr"));
        assertEquals("37,500", PlusFormat.money(37500, "en"));
        assertEquals("120", PlusFormat.money(120, "tr"));
        assertEquals("1.000.000", PlusFormat.money(1_000_000, "tr"));
        assertEquals(Long.valueOf(40000), PlusConfig.parseAmount("40k"));
        assertEquals(Long.valueOf(37500), PlusConfig.parseAmount("37.500"));
        assertEquals(Long.valueOf(37500), PlusConfig.parseAmount("37,5k"));
        assertEquals(Long.valueOf(2_000_000), PlusConfig.parseAmount("2m"));
        assertNull(PlusConfig.parseAmount("kırk"));
        assertNull(PlusConfig.parseAmount("0"));
        assertEquals(32000, PlusConfig.suggestedTarget(40000));
        assertEquals(400, PlusConfig.suggestedTarget(500));
    }

    @Test
    public void theAssistantsState() {
        assertEquals(PlusFormat.Ai.ON, PlusFormat.ai(CloudModel.AiView.read(Vectors.json("{\"state\":\"on\",\"trade\":{\"on\":true,\"buy\":\"on\"}}"))));
        assertEquals(PlusFormat.Ai.ON_NOTIFY_ONLY, PlusFormat.ai(CloudModel.AiView.read(Vectors.json("{\"state\":\"on\",\"trade\":{\"buy\":\"notify\"}}"))));
        assertEquals(PlusFormat.Ai.PAUSED_BREAK, PlusFormat.ai(CloudModel.AiView.read(Vectors.json("{\"state\":\"paused\",\"reason\":\"break\"}"))));
        assertEquals(PlusFormat.Ai.OFF_SERVER, PlusFormat.ai(CloudModel.AiView.read(Vectors.json("{\"state\":\"off\",\"reason\":\"server\"}"))));
        assertEquals(PlusFormat.Ai.OFF, PlusFormat.ai(CloudModel.AiView.read(Vectors.json("{\"state\":\"off\",\"reason\":\"newer\"}"))));
        assertEquals(PlusFormat.Ai.UNKNOWN, PlusFormat.ai(null));
        assertTrue(PlusFormat.playerCanResume(PlusFormat.Ai.OFF_PLAYER));
        assertFalse(PlusFormat.playerCanResume(PlusFormat.Ai.OFF_STAFF));
    }

    @Test
    public void theHistorysChanges() {
        JsonObject before = doc().config;
        PlusConfig next = new PlusConfig(before);
        next.rows(true).get(0).setBound(45000);
        next.addRow(true, PlusConfig.newRow(true, "t1", "minecraft:totem_of_undying", 1, "total", 20000, 25000, "balanced", "above"));
        next.removeRow(false, "s1");
        next.setLimit("perDealMax", 60000L);
        next.setScheduleOn(false);
        next.setEnabled(false);
        List<PlusFormat.Change> changes = PlusFormat.diff(before, next.toJson());
        assertEquals("[AI_OFF, ROW_BOUND minecraft:elytra max 40000→45000, ROW_ADDED minecraft:totem_of_undying, "
                + "ROW_REMOVED minecraft:diamond, LIMIT perDealMax 50000→60000, SCHEDULE_OFF]", changes.toString());
        assertTrue(PlusFormat.diff(before, before).isEmpty());
    }

    @Test
    public void turkishEndingsAfterNumbersAndTimes() {
        assertEquals("37.500'e", TrGrammar.dative("37.500", 37500));
        assertEquals("40.000'e", TrGrammar.dative("40.000", 40000));
        assertEquals("60'a", TrGrammar.dative("60", 60));
        assertEquals("120'ye", TrGrammar.dative("120", 120));
        assertEquals("6'ya", TrGrammar.dative("6", 6));
        assertEquals("4'e", TrGrammar.dative("4", 4));
        assertEquals("90'a", TrGrammar.dative("90", 90));
        assertEquals("100'e", TrGrammar.dative("100", 100));
        assertEquals("1.000.000'a", TrGrammar.dative("1.000.000", 1_000_000));
        assertEquals("14:35'te", TrGrammar.locativeTime("14:35"));
        assertEquals("14:30'da", TrGrammar.locativeTime("14:30"));
        assertEquals("14:00'te", TrGrammar.locativeTime("14:00"));
        assertEquals("21:20'de", TrGrammar.locativeTime("21:20"));
        assertEquals("09:10'da", TrGrammar.locativeTime("09:10"));
        assertEquals("16:40'ta", TrGrammar.locativeTime("16:40"));
        assertEquals("20:00'de", TrGrammar.locativeTime("20:00"));
        assertEquals("26 Ekim'de", TrGrammar.locativeDate(26, 10));
        assertEquals("3 Ocak'ta", TrGrammar.locativeDate(3, 1));
        assertEquals("30 Ekim'e", TrGrammar.dativeDate(30, 10));
        assertEquals("1 Haziran'a", TrGrammar.dativeDate(1, 6));
        assertEquals("12 Kasım'dan", TrGrammar.ablativeDate(12, 11));
        assertEquals("5 Mart'tan", TrGrammar.ablativeDate(5, 3));
        assertEquals("19 Eylül'den", TrGrammar.ablativeDate(19, 9));
        assertEquals("3 Ocak 2027'de", TrGrammar.locativeDate(3, 1, 2027));
        assertEquals("3 Ocak 2030'da", TrGrammar.locativeDate(3, 1, 2030));
        assertEquals("3 Ocak 2027'den", TrGrammar.ablativeDate(3, 1, 2027));
        assertEquals("1 Mayıs 2040'tan", TrGrammar.ablativeDate(1, 5, 2040));
        assertEquals("14:35'e", TrGrammar.dativeTime("14:35"));
        assertEquals("14:30'a", TrGrammar.dativeTime("14:30"));
    }

    @Test
    public void amountsInWords() {
        assertEquals("kırk bin", TrGrammar.words(40000, "tr"));
        assertEquals("otuz yedi bin beş yüz", TrGrammar.words(37500, "tr"));
        assertEquals("bin iki yüz elli", TrGrammar.words(1250, "tr"));
        assertEquals("yüz bir bin", TrGrammar.words(101000, "tr"));
        assertEquals("bir milyon", TrGrammar.words(1_000_000, "tr"));
        assertEquals("iki milyon üç yüz bin", TrGrammar.words(2_300_000, "tr"));
        assertEquals("sıfır", TrGrammar.words(0, "tr"));
        assertEquals("forty thousand", TrGrammar.words(40000, "en"));
        assertEquals("thirty-seven thousand five hundred", TrGrammar.words(37500, "en"));
        assertEquals("one thousand two hundred and fifty", TrGrammar.words(1250, "en"));
        assertEquals("one thousand and five", TrGrammar.words(1005, "en"));
        assertEquals("one million", TrGrammar.words(1_000_000, "en"));
    }
}
