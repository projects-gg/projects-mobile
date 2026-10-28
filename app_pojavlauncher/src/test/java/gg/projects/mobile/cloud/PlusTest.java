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

/**
 * AFK+ settings ({@code AfkPlusConfig} v2) edited in place, the checks of the fields the settings screens edit, amounts,
 * the assistant's state and the history's changes.
 */
public class PlusTest {
    private static final long NOW = 1792846800L;

    private static CloudModel.PlusDoc doc() {
        return CloudModel.PlusDoc.read(Vectors.json(Vectors.resource("cloud/fixtures/plus.json")));
    }

    private static String problems(List<PlusConfig.Problem> problems) {
        List<String> out = new ArrayList<>();
        for (PlusConfig.Problem p : problems) out.add(p.toString());
        return out.toString();
    }

    private static List<PlusConfig.Problem> check(PlusConfig config) {
        return config.check(10_000_000L, 1, 60, NOW);
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
        assertEquals("ask", config.authority());
        assertTrue(config.allows("buy"));
        assertEquals("money", config.pay());
        assertEquals("all", config.whoMode());
        assertFalse(config.smallTalk());
        assertFalse(config.playerTalk());
        assertFalse(config.tagOn());
        assertEquals("[AFK+]", config.tagText());
        assertEquals(4, config.orders().size());
        assertEquals("Yakut", config.customItem("c1yk").get("name").getAsString());
        config.setStatus("Akşam 8'de dönerim");
        assertNull(config.statusPreset());
        config.setSaveAt(null);
        assertNull(config.saveAt());
        config.setAuthority("deal");
        config.setAllows("sell", false);
        config.setWho("friends", Arrays.asList("lavci_42"));
        config.setPlayerTalk(true);
        config.setTag(true, "[gece]");
        JsonObject sent = config.settingsJson();
        assertTrue(sent.getAsJsonObject("replies").get("saveAt").isJsonNull());
        assertEquals("deal", sent.get("authority").getAsString());
        assertFalse(sent.getAsJsonObject("allow").get("sell").getAsBoolean());
        assertEquals("friends", sent.getAsJsonObject("who").get("mode").getAsString());
        assertTrue(sent.getAsJsonObject("persona").get("playerTalk").getAsBoolean());
        assertEquals("[gece]", sent.getAsJsonObject("persona").getAsJsonObject("prefix").get("text").getAsString());
        // A settings write never sends the orders or the custom items back (they have their own routes).
        assertFalse(sent.has("orders"));
        assertFalse(sent.has("customItems"));
        // The stored document is untouched.
        assertEquals("ask", doc.config.get("authority").getAsString());
        assertTrue(problems(check(config)), check(config).isEmpty());
    }

    /**
     * 28 Oct 2026: the money's name, the ads' interval and the first start go back as they were read and are never made;
     * settings that lack the talk style and "Oyuncu gibi konuşsun" read as "Kibar" with it on, a stored value stays.
     */
    @Test
    public void moneyNameAdsAndTheFirstStart() {
        JsonObject bare = Vectors.json("{\"v\":2,\"enabled\":false,\"ready\":true,\"persona\":{},\"trade\":{\"returnRow\":null},"
                + "\"discovery\":{\"ads\":false}}");
        PlusConfig fresh = new PlusConfig(bare);
        assertTrue(fresh.ready());
        assertEquals("polite", fresh.preset());
        assertTrue(fresh.playerTalk());
        assertNull(fresh.currency());
        assertNull(fresh.adEveryMin());
        JsonObject untouched = fresh.settingsJson();
        // Read, never made: no currency, no interval; the Worker's ready goes back as it came.
        assertFalse(untouched.getAsJsonObject("trade").has("currency"));
        assertFalse(untouched.getAsJsonObject("discovery").has("adEveryMin"));
        assertFalse(untouched.getAsJsonObject("persona").has("preset"));
        assertTrue(untouched.get("ready").getAsBoolean());
        fresh.setCurrency("ripple");
        fresh.setAdEveryMin(45);
        fresh.setAds(true);
        JsonObject sent = fresh.settingsJson();
        assertEquals("ripple", sent.getAsJsonObject("trade").get("currency").getAsString());
        assertEquals(45, sent.getAsJsonObject("discovery").get("adEveryMin").getAsInt());
        assertTrue(sent.getAsJsonObject("discovery").get("ads").getAsBoolean());
        assertTrue(problems(check(fresh)), check(fresh).isEmpty());
        // Clearing writes null (the stored name goes); a name with a digit and an odd interval are the Worker's refusals.
        fresh.setCurrency(null);
        assertTrue(fresh.settingsJson().getAsJsonObject("trade").get("currency").isJsonNull());
        assertNull(fresh.currency());
        fresh.setCurrency("coin2");
        fresh.setAdEveryMin(50);
        assertEquals("[trade.currency:text, discovery.adEveryMin:range]", problems(check(fresh)));
        // A stored value stays whatever it is.
        PlusConfig stored = new PlusConfig(Vectors.json("{\"v\":2,\"persona\":{\"preset\":\"friendly\",\"playerTalk\":false},"
                + "\"trade\":{\"currency\":\"TL\"},\"discovery\":{\"adEveryMin\":120}}"));
        assertEquals("friendly", stored.preset());
        assertFalse(stored.playerTalk());
        assertEquals("TL", stored.currency());
        assertEquals(Integer.valueOf(120), stored.adEveryMin());
        assertFalse(stored.ready());
        assertEquals("TL", stored.settingsJson().getAsJsonObject("trade").get("currency").getAsString());
    }

    @Test
    public void sameIgnoresOrdersAndCustomItems() {
        PlusConfig a = new PlusConfig(doc().config);
        JsonObject other = doc().config.deepCopy();
        other.remove("orders");
        assertTrue(a.same(new PlusConfig(other)));
        other.addProperty("pay", "both");
        assertFalse(a.same(new PlusConfig(other)));
    }

    @Test
    public void theWorkersChecks() {
        PlusConfig config = new PlusConfig(doc().config);
        config.setLimit("approvalAbove", 60000L);
        config.setLimit("dailySpendMax", 20_000_000L);
        config.setBlocked(Arrays.asList("spamci_99", "SPAMCI_99", "no"));
        config.setWho("friends", Arrays.asList("lavci_42", "spamci_99", "x"));
        config.setTag(true, "   ");
        config.setScheduleOn(true);
        config.setWindows(Arrays.asList(new PlusConfig.Window(Arrays.asList(1, 8), "16:00", "25:00")));
        config.setPauseUntil(NOW + 100L * 86_400);
        assertEquals("[limits.dailySpendMax:range, limits.approvalAbove:limits, blocked[1]:name, blocked[2]:name, "
                + "who.friends[1]:blocked, who.friends[2]:name, persona.prefix.text:empty, schedule.windows[0].days:range, "
                + "schedule.windows[0]:range, pauseUntil:range]", problems(check(config)));
        config.setTag(false, "[Ejderin Botu Burada]");
        assertTrue(problems(check(config)).contains("persona.prefix.text:length"));
    }

    @Test
    public void amountsAndTypedNumbers() {
        assertEquals("37.500", PlusFormat.money(37500, "tr"));
        assertEquals("37,500", PlusFormat.money(37500, "en"));
        assertEquals("120", PlusFormat.money(120, "tr"));
        assertEquals("1.000.000", PlusFormat.money(1_000_000, "tr"));
        assertEquals(Long.valueOf(40000), PlusConfig.parseAmount("40k"));
        assertEquals(Long.valueOf(40000), PlusConfig.parseAmount("40 bin"));
        assertEquals(Long.valueOf(2_000_000), PlusConfig.parseAmount("2 milyon"));
        assertEquals(Long.valueOf(37500), PlusConfig.parseAmount("37.500"));
        assertEquals(Long.valueOf(37500), PlusConfig.parseAmount("37,5k"));
        assertEquals(Long.valueOf(2_000_000), PlusConfig.parseAmount("2m"));
        assertNull(PlusConfig.parseAmount("kırk"));
        assertNull(PlusConfig.parseAmount("0"));
        // Twelve digits at most, also with a factor (the words under a field read up to 999 billion).
        assertEquals(Long.valueOf(999_999_000_000L), PlusConfig.parseAmount("999999m"));
        assertNull(PlusConfig.parseAmount("1000000m"));
        assertNull(PlusConfig.parseAmount("1000000000k"));
        assertNull(PlusConfig.parseAmount("1000000 milyon"));
        assertEquals("dokuz yüz doksan dokuz milyar dokuz yüz doksan dokuz milyon", TrGrammar.words(999_999_000_000L, "tr"));
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
        CloudModel.AiView owner = CloudModel.AiView.read(Vectors.json("{\"state\":\"on\",\"trade\":{\"on\":true,\"by\":\"owner\",\"buy\":\"on\"}}"));
        assertEquals("owner", owner.tradeBy);
        assertEquals("bot", CloudModel.AiView.read(Vectors.json("{\"state\":\"on\",\"trade\":{\"by\":\"bot\"}}")).tradeBy);
    }

    @Test
    public void theHistorysChanges() {
        JsonObject before = doc().config;
        JsonObject after = before.deepCopy();
        JsonObject elytra = after.getAsJsonArray("orders").get(0).getAsJsonObject();
        elytra.addProperty("limit", 45000);
        after.getAsJsonArray("orders").get(1).getAsJsonObject().addProperty("on", false);
        after.getAsJsonArray("orders").remove(3);
        JsonObject added = elytra.deepCopy();
        added.addProperty("id", "n1");
        added.addProperty("item", "minecraft:netherite_ingot");
        after.getAsJsonArray("orders").add(added);
        PlusConfig next = new PlusConfig(after);
        next.setLimit("perDealMax", 60000L);
        next.setScheduleOn(false);
        next.setEnabled(false);
        next.setAuthority("deal");
        List<PlusFormat.Change> changes = PlusFormat.diff(before, next.toJson());
        assertEquals("[AI_OFF, ROW_BOUND minecraft:elytra limit 40000→45000, ROW_OFF minecraft:diamond, ROW_ADDED minecraft:netherite_ingot, "
                + "ROW_REMOVED minecraft:totem_of_undying, FINE, LIMIT perDealMax 50000→60000, SCHEDULE_OFF]", changes.toString());
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
