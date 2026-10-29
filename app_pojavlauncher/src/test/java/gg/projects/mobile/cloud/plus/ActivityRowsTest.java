package gg.projects.mobile.cloud.plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.PlusModel;

/** Etkinlik's rows (PLUS-ACTIVITY, the site's activity-groups.test.ts and more): days, players, how it went, filters, search. */
public class ActivityRowsTest {
    private static int counter;

    private static PlusModel.AuditEntry entry(String k, String w, long t, String item) {
        counter += 1;
        JsonObject o = JsonParser.parseString("{}").getAsJsonObject();
        o.addProperty("s", 20750_000_000L + counter);
        o.addProperty("t", t);
        o.addProperty("k", k);
        if (w != null) o.addProperty("w", w);
        if (item != null) o.addProperty("i", item);
        return PlusModel.AuditEntry.read(o);
    }

    private static PlusModel.AuditEntry entry(String k, String w) {
        return entry(k, w, 1_000, null);
    }

    private static final ActivityRows.Namer NAMES = ref -> "minecraft:elytra".equals(ref) ? "Elitra" : "Elmas";

    @Test
    public void keepAPlayersDayTogetherAndAnEventWithoutAPlayerAloneNewestFirst() {
        // Newest first, as the log reads them; a name in another case is the same player.
        PlusModel.AuditEntry done = entry("trade_done", "KaraKartal_07", 9, null);
        PlusModel.AuditEntry gate = entry("gate", null, 8, null);
        PlusModel.AuditEntry reply = entry("reply", "KaraKartal_07", 7, null);
        PlusModel.AuditEntry other = entry("pm_in", "TasUsta", 6, null);
        PlusModel.AuditEntry first = entry("pm_in", "karakartal_07", 5, null);
        List<ActivityRows.Group> rows = ActivityRows.groups(Arrays.asList(done, gate, reply, other, first));
        List<String> who = new ArrayList<>();
        for (ActivityRows.Group g : rows) who.add(g.who);
        assertEquals(Arrays.asList("KaraKartal_07", null, "TasUsta"), who);
        assertEquals(Arrays.asList(done, reply, first), rows.get(0).entries);
        assertEquals(Arrays.asList(gate), rows.get(1).entries);
        assertSame(done, rows.get(0).newest());
        assertEquals("w:karakartal_07", rows.get(0).key);
        assertTrue(rows.get(1).key.startsWith("s"));
    }

    @Test
    public void sayHowTheDayWentByItsMostTellingStepTheNewestOfThose() {
        List<ActivityRows.Group> rows = ActivityRows.groups(Arrays.asList(entry("reply", "KaraKartal_07"), entry("agree", "KaraKartal_07"),
                entry("offer", "KaraKartal_07"), entry("pm_in", "KaraKartal_07")));
        assertEquals("agree", ActivityRows.outcome(rows.get(0)).k);
        List<ActivityRows.Group> talk = ActivityRows.groups(Arrays.asList(entry("reply", "TasUsta"), entry("pm_in", "TasUsta")));
        assertEquals("reply", ActivityRows.outcome(talk.get(0)).k);
        // A done trade says more than the agreement before it, and of two the newest wins.
        PlusModel.AuditEntry newer = entry("trade_done", "gecekurdu_", 30, null);
        List<ActivityRows.Group> deal = ActivityRows.groups(Arrays.asList(newer, entry("agree", "gecekurdu_", 20, null),
                entry("trade_done", "gecekurdu_", 10, null)));
        assertSame(newer, ActivityRows.outcome(deal.get(0)));
        // An ask and an offer tie: the newest says it; the order menu's delivery is as telling as a done trade.
        PlusModel.AuditEntry ask = entry("counter", "lavci_42", 50, null);
        assertSame(ask, ActivityRows.outcome(ActivityRows.groups(Arrays.asList(ask, entry("offer", "lavci_42", 40, null))).get(0)));
        PlusModel.AuditEntry menu = entry("menu_done", "lavci_42", 60, null);
        assertSame(menu, ActivityRows.outcome(ActivityRows.groups(Arrays.asList(menu, entry("trade_done", "lavci_42", 55, null))).get(0)));
    }

    @Test
    public void filterAsTheSiteDoesARefusalBeingWhatWasTurnedDown() {
        assertTrue(ActivityRows.in(null, entry("paused", null)));
        assertTrue(ActivityRows.in(ActivityRows.MESSAGES, entry("owner_msg", null)));
        assertFalse(ActivityRows.in(ActivityRows.MESSAGES, entry("offer", "lavci_42")));
        assertTrue(ActivityRows.in(ActivityRows.DEALS, entry("handoff_done", "lavci_42")));
        // "cancel" is a deal and a refusal; a pause or the kontör running out is neither a refusal nor a deal.
        assertTrue(ActivityRows.in(ActivityRows.DEALS, entry("cancel", "lavci_42")));
        assertTrue(ActivityRows.in(ActivityRows.REFUSALS, entry("cancel", "lavci_42")));
        assertTrue(ActivityRows.in(ActivityRows.REFUSALS, entry("owner_timeout", "lavci_42")));
        assertTrue(ActivityRows.in(ActivityRows.REFUSALS, entry("handoff_no", "lavci_42")));
        assertFalse(ActivityRows.in(ActivityRows.REFUSALS, entry("paused", null)));
        assertFalse(ActivityRows.in(ActivityRows.REFUSALS, entry("quota", null)));
        assertFalse(ActivityRows.in(ActivityRows.REFUSALS, entry("trade_done", "lavci_42")));
        // The order menu (DONUT-ORDER): a delivery is a deal, one that did not happen a deal and a refusal.
        assertTrue(ActivityRows.in(ActivityRows.DEALS, entry("menu_done", null)));
        assertTrue(ActivityRows.in(ActivityRows.DEALS, entry("menu_fail", null)));
        assertTrue(ActivityRows.in(ActivityRows.REFUSALS, entry("menu_fail", null)));
        assertFalse(ActivityRows.in(ActivityRows.REFUSALS, entry("menu_done", null)));
    }

    @Test
    public void findARowByThePlayerOrAnItemInThePlayersLetters() {
        Locale tr = new Locale("tr", "TR");
        ActivityRows.Group kasirga = ActivityRows.groups(Arrays.asList(entry("agree", "KASIRGA_tr", 5, "minecraft:elytra"),
                entry("pm_in", "KASIRGA_tr", 4, null))).get(0);
        assertTrue(ActivityRows.found(kasirga, "", NAMES, tr));
        assertTrue(ActivityRows.found(kasirga, null, NAMES, tr));
        assertTrue(ActivityRows.found(kasirga, " kasirga ", NAMES, Locale.ROOT));
        // A Turkish dotted capital I typed by the player finds "Elitra".
        assertTrue(ActivityRows.found(kasirga, "EL" + (char) 0x130 + "T", NAMES, tr));
        assertTrue(ActivityRows.found(kasirga, "elitra", NAMES, tr));
        assertFalse(ActivityRows.found(kasirga, "elmas", NAMES, tr));
        ActivityRows.Group event = ActivityRows.groups(Arrays.asList(entry("paused", null))).get(0);
        assertNull(event.who);
        assertNull(event.item());
        assertFalse(ActivityRows.found(event, "kasirga", NAMES, tr));
        assertEquals("minecraft:elytra", kasirga.item());
    }

    @Test
    public void countTodaysDoneTradesOnly() {
        List<PlusModel.AuditEntry> log = Arrays.asList(entry("trade_done", "lavci_42", 5_000, null), entry("handoff_done", "lavci_42", 4_000, null),
                entry("trade_fail", "lavci_42", 3_500, null), entry("trade_done", "lavci_42", 900, null));
        assertEquals(2, ActivityRows.tradesSince(log, 1_000));
        assertEquals(0, ActivityRows.tradesSince(log, 9_000));
    }
}
