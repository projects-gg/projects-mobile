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
import java.util.List;
import java.util.TimeZone;

/**
 * MOB-PARITY "Ekip bul": the listing and the leader's status in the service's format 1 (README "Crew discovery"; the
 * desktop's model.test.ts cases), searching and filtering, the kept copy, a card's action and the text rules.
 */
public class DiscoverTest {
    private static final String LISTING = "{\"v\":1,\"ver\":42,\"next\":1762441200,\"at\":1762354800,\"day\":20397,\"crews\":["
            + "{\"id\":\"0123456789abcdef\",\"name\":\"Gece Kartalları\",\"tag\":\"GKR\",\"emblem\":\"sword\",\"color\":\"gold\","
            + "\"frame\":\"cut\",\"motto\":\"\",\"text\":\"Akşamları SkyBlock oynuyoruz, sakin oyuncular arıyoruz.\","
            + "\"tags\":[\"skyblock\",\"newcomers\",\"unknown\"],\"join\":\"open\",\"members\":12,\"active\":true,\"extra\":1},"
            + "{\"id\":\"fedcba9876543210\",\"name\":\"Şimşek Takımı\",\"tag\":\"SMK\",\"emblem\":\"bolt\",\"color\":\"nope\","
            + "\"frame\":\"hex\",\"motto\":\"Hızlıyız\",\"text\":\"PvP ve HG.\",\"tags\":[\"pvp\",\"hg\"],\"join\":\"approval\","
            + "\"members\":4,\"active\":false},"
            + "{\"id\":\"bad id\",\"name\":\"x\"}]}";

    private static DiscoverModel.Listing listing() {
        DiscoverModel.Listing l = DiscoverModel.readListing(Json.parseObject(LISTING));
        assertNotNull(l);
        return l;
    }

    private static final DiscoverModel.TagWords WORDS = tag -> "build".equals(tag) ? "Yapı" : tag;

    @Test
    public void readsFormatOneAndDropsWhatItDoesNotKnow() {
        DiscoverModel.Listing l = listing();
        assertEquals(42, l.ver);
        assertEquals(1762441200L, l.next);
        assertEquals(2, l.crews.size());
        DiscoverModel.Crew first = l.crews.get(0);
        assertEquals(Arrays.asList("skyblock", "newcomers"), first.tags);
        assertTrue(first.open());
        DiscoverModel.Crew second = l.crews.get(1);
        assertEquals("gold", second.color);
        assertFalse(second.open());
        assertEquals("Hızlıyız", second.summary().motto);
        assertNull(DiscoverModel.readListing(Json.parseObject("{\"v\":2,\"crews\":[]}")));
        assertEquals(1, l.without("0123456789abcdef").crews.size());
    }

    @Test
    public void searchAndFiltersKeepTheDaysOrder() {
        List<DiscoverModel.Crew> crews = listing().crews;
        DiscoverModel.Filter none = DiscoverModel.Filter.NONE;
        assertEquals(2, DiscoverModel.filter(crews, none, WORDS).size());
        // Turkish folding: "kartal" finds "Kartalları", "simsek" finds "Şimşek".
        assertEquals("0123456789abcdef", DiscoverModel.filter(crews, none.withQuery("KARTAL"), WORDS).get(0).id);
        assertEquals("fedcba9876543210", DiscoverModel.filter(crews, none.withQuery("simsek"), WORDS).get(0).id);
        assertEquals(1, DiscoverModel.filter(crews, none.withQuery("gkr"), WORDS).size());
        assertEquals(1, DiscoverModel.filter(crews, none.withTag("pvp", true), WORDS).size());
        assertEquals(0, DiscoverModel.filter(crews, none.withTag("pvp", true).withTag("skyblock", true), WORDS).size());
        assertEquals(1, DiscoverModel.filter(crews, none.withOpen(true), WORDS).size());
        assertEquals(1, DiscoverModel.filter(crews, none.withActive(true), WORDS).size());
        assertEquals(1, DiscoverModel.filter(crews, none.withSize(DiscoverModel.Size.MID), WORDS).size());
        assertEquals(2, none.withOpen(true).withSize(DiscoverModel.Size.BIG).menuCount());
        assertFalse(none.filtering());
        assertTrue(none.withQuery(" x ").filtering());
        assertEquals(DiscoverModel.Size.SMALL, DiscoverModel.sizeOf(9));
        assertEquals(DiscoverModel.Size.MID, DiscoverModel.sizeOf(10));
        assertEquals(DiscoverModel.Size.BIG, DiscoverModel.sizeOf(20));
    }

    @Test
    public void keptCopyIsAskedAgainOnlyWhenDue() {
        DiscoverModel.Listing l = listing();
        long beforeNext = l.next * 1000L - 3_600_000L;
        assertEquals(DiscoverModel.Freshness.FETCH, DiscoverModel.freshness(null, 0, beforeNext));
        assertEquals(DiscoverModel.Freshness.KEEP, DiscoverModel.freshness(l, beforeNext - 60_000L, beforeNext));
        assertEquals(DiscoverModel.Freshness.REVALIDATE, DiscoverModel.freshness(l, beforeNext - 15 * 60_000L, beforeNext));
        assertEquals(DiscoverModel.Freshness.FETCH, DiscoverModel.freshness(l, l.next * 1000L - 1, l.next * 1000L));
    }

    @Test
    public void cardActions() {
        DiscoverModel.Crew open = listing().crews.get(0);
        DiscoverModel.Crew approval = listing().crews.get(1);
        assertEquals(DiscoverModel.Slot.MEMBER, DiscoverModel.slot(open, true, false, true));
        assertEquals(DiscoverModel.Slot.WAITING, DiscoverModel.slot(approval, false, true, true));
        assertEquals(DiscoverModel.Slot.LIMIT, DiscoverModel.slot(open, false, false, false));
        assertEquals(DiscoverModel.Slot.JOIN, DiscoverModel.slot(open, false, false, true));
        assertEquals(DiscoverModel.Slot.ASK, DiscoverModel.slot(approval, false, false, true));
    }

    @Test
    public void leaderTextRules() {
        assertEquals("a b", DiscoverModel.cleanText(" a \n  b "));
        assertFalse(DiscoverModel.textReady("kısa"));
        assertFalse(DiscoverModel.textReady("1234567890 !!"));
        assertTrue(DiscoverModel.textReady("SkyBlock oynuyoruz"));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 161; i++) longText.append('a');
        assertFalse(DiscoverModel.textReady(longText.toString()));
        JsonObject body = DiscoverModel.body("  Akşam  oynuyoruz ", Arrays.asList("pvp", "survival"));
        assertEquals("Akşam oynuyoruz", body.get("text").getAsString());
        // Tags go in the service's order.
        assertEquals("survival", body.getAsJsonArray("tags").get(0).getAsString());
    }

    @Test
    public void leaderStatusAndWhatMaySend() {
        DiscoverModel.Status s = DiscoverModel.readStatus(Json.parseObject("{\"discover\":{\"state\":\"listed\","
                + "\"listed\":{\"text\":\"SkyBlock oynuyoruz\",\"tags\":[\"skyblock\"]},\"draft\":{\"text\":\"SkyBlock oynuyoruz\","
                + "\"tags\":[\"skyblock\"]},\"changeWaits\":false,\"waveAt\":1762441200,\"retryAt\":null,\"checks\":{"
                + "\"members\":{\"need\":3,\"have\":12},\"crewDays\":{\"need\":3,\"have\":40},\"leaderDays\":{\"need\":14,\"have\":200},"
                + "\"visible\":true},\"editsLeft\":4,\"editsReset\":1762646400}}"));
        assertNotNull(s);
        assertTrue(s.on());
        assertTrue(s.checksHold());
        assertNull(s.retryAt);
        // The same text and tags again: nothing to send.
        assertFalse(DiscoverModel.maySend(s, "SkyBlock  oynuyoruz", Collections.singletonList("skyblock")));
        assertTrue(DiscoverModel.maySend(s, "SkyBlock oynuyoruz", Arrays.asList("skyblock", "pvp")));
        assertFalse(DiscoverModel.maySend(s, "SkyBlock oynuyoruz", Arrays.asList("skyblock", "pvp", "hg", "smp")));
        DiscoverModel.Status needs = DiscoverModel.readStatus(Json.parseObject("{\"discover\":{\"state\":\"off\",\"checks\":{"
                + "\"members\":{\"need\":3,\"have\":2},\"crewDays\":{\"need\":3,\"have\":4},\"leaderDays\":{\"need\":14,\"have\":20},"
                + "\"visible\":true},\"editsLeft\":5,\"waveAt\":0,\"editsReset\":0}}"));
        assertNotNull(needs);
        assertFalse(needs.on());
        assertFalse(needs.checksHold());
        assertFalse(needs.members.holds());
    }

    @Test
    public void waveDayAndDays() {
        TimeZone istanbul = TimeZone.getTimeZone("Europe/Istanbul");
        long wave = 1762441200L; // 6 Nov 2025 18:00 in Istanbul
        assertEquals(DiscoverModel.Day.TODAY, DiscoverModel.waveDay(wave, wave * 1000L - 3_600_000L, istanbul));
        assertEquals(DiscoverModel.Day.TOMORROW, DiscoverModel.waveDay(wave, wave * 1000L - 20 * 3_600_000L, istanbul));
        assertEquals(DiscoverModel.Day.LATER, DiscoverModel.waveDay(wave, wave * 1000L - 50 * 3_600_000L, istanbul));
        assertEquals(1, DiscoverModel.daysUntil(wave, wave * 1000L - 1000L));
        assertEquals(2, DiscoverModel.daysUntil(wave, wave * 1000L - 86_400_000L - 1000L));
        assertEquals(1, DiscoverModel.daysUntil(wave, wave * 1000L + 5000L));
    }
}
