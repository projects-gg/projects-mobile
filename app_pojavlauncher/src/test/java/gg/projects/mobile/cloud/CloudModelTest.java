package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.List;

/** The player API's objects read leniently (fixtures in the contract's shapes, with fields this app does not know). */
public class CloudModelTest {
    /** 28 Oct 2026: the chat ads' preview, the money's fixed name, Studio's currency switch and the first start. */
    @Test
    public void adsPreviewMoneyAndTheFirstStart() {
        CloudModel.PlusDoc doc = CloudModel.PlusDoc.read(Vectors.json("{\"config\":{\"v\":2,\"enabled\":false,\"ready\":true},"
                + "\"aiRev\":0,\"server\":{\"kind\":\"verified\",\"mode\":\"projects-smp\",\"trade\":\"bot\","
                + "\"money\":{\"label\":{\"tr\":\"ripple\",\"en\":\"ripple\"},\"fixed\":true,\"maxDeal\":10000000,\"currency\":\"ripple\"}},"
                + "\"rules\":{\"features\":{\"itemPay\":true,\"currency\":true}},"
                + "\"adPreview\":{\"floorMin\":45,\"everyMin\":120,\"lines\":[\"Al\u0131yorum: 4 Elytra, PM at\",\"\",\"b\",\"c\",\"d\"],"
                + "\"nextAt\":1792850400,\"warning\":{\"at\":1792846000,\"everyMin\":120},\"stoppedToday\":false,\"namesPending\":false}}"));
        assertTrue(doc.waitsForStart());
        assertTrue(doc.server.moneyFixed);
        assertEquals("ripple", doc.server.moneyLabel.in("tr"));
        assertTrue(doc.rules.currency);
        PlusModel.AdPreview ads = doc.adPreview;
        assertEquals(45, ads.floorMin);
        assertEquals(120, ads.everyMin);
        // At most three lines, never an empty one.
        assertEquals(java.util.Arrays.asList("Al\u0131yorum: 4 Elytra, PM at", "b", "c"), ads.lines);
        assertEquals(Long.valueOf(1792850400L), ads.nextAt);
        assertEquals(Long.valueOf(1792846000L), ads.warningAt);
        assertFalse(ads.stoppedToday);
        // A Worker before them: no preview, no fixed name, the switch off, started.
        CloudModel.PlusDoc old = CloudModel.PlusDoc.read(Vectors.json("{\"config\":{\"v\":2},\"aiRev\":3,\"server\":{\"money\":{}},"
                + "\"rules\":{\"features\":{\"itemPay\":true}}}"));
        assertNull(old.adPreview);
        assertFalse(old.server.moneyFixed);
        assertFalse(old.rules.currency);
        assertFalse(old.waitsForStart());
        // A PUT's answer laid over the read document keeps the rules and takes the new preview and server.
        CloudModel.PlusDoc answer = CloudModel.PlusDoc.read(Vectors.json("{\"config\":{\"v\":2,\"trade\":{\"currency\":\"coin\"}},"
                + "\"aiRev\":4,\"server\":{\"money\":{\"label\":{\"tr\":\"coin\",\"en\":\"coin\"},\"fixed\":false}},"
                + "\"adPreview\":{\"floorMin\":30,\"everyMin\":60,\"lines\":[],\"nextAt\":null,\"warning\":null,"
                + "\"stoppedToday\":true,\"namesPending\":true}}"));
        CloudModel.PlusDoc merged = old.after(answer);
        assertEquals(4, merged.aiRev);
        assertTrue(merged.rules.itemPay);
        assertEquals("coin", merged.server.moneyLabel.in("en"));
        assertTrue(merged.adPreview.stoppedToday);
        assertTrue(merged.adPreview.namesPending);
        assertNull(merged.adPreview.warningAt);
        // The account's AI view: never started.
        CloudModel.AiView ai = CloudModel.AiView.read(Vectors.json("{\"state\":\"off\",\"reason\":\"ready\"}"));
        assertTrue(ai.waitsForStart());
        assertFalse(CloudModel.AiView.read(Vectors.json("{\"state\":\"off\",\"reason\":\"player\"}")).waitsForStart());
    }

    @Test
    public void theOfferAndItsAfkPlusSwitches() {
        CloudModel.Offer offer = CloudModel.Offer.read(Vectors.json(Vectors.resource("cloud/fixtures/offer.json")));
        assertNotNull(offer);
        assertTrue(offer.available());
        assertFalse(offer.paused());
        assertEquals("60.00", offer.month().priceTl);
        assertEquals(20, offer.limits.maxCommandRows);
        assertEquals(10, offer.limits.minRuleCooldownSec);
        assertNotNull(offer.plus);
        assertTrue(offer.plusFeature("inbox"));
        assertTrue(offer.plusFeature("parse"));
        assertFalse(offer.plusFeature("handoff"));
        // A switch that is not a boolean reads as off.
        assertFalse(offer.plusFeature("futureThing"));
        assertEquals(1, offer.plus.termsVersion);
    }

    @Test
    public void anOfferOffOrMissingHidesEverything() {
        CloudModel.Offer off = CloudModel.Offer.read(Vectors.json("{\"mode\":\"off\",\"plus\":null}"));
        assertNotNull(off);
        assertFalse(off.available());
        assertNull(off.plus);
        assertFalse(off.plusFeature("inbox"));
        CloudModel.Offer odd = CloudModel.Offer.read(Vectors.json("{\"mode\":\"later-mode\"}"));
        assertFalse(odd.available());
        assertEquals(10, odd.limits.perPlayerCap);
    }

    @Test
    public void accountsWithHoldsLiveStatesAndAi() {
        JsonObject o = Vectors.json(Vectors.resource("cloud/fixtures/accounts.json"));
        List<CloudModel.Account> accounts = CloudModel.Account.list(o, "accounts");
        assertEquals(3, accounts.size());
        CloudModel.Account plus = accounts.get(0);
        assertTrue(plus.plus());
        assertEquals("running", plus.live.state);
        assertEquals(Long.valueOf(1792831680L), plus.live.since);
        assertEquals(4, plus.config.commands.size());
        assertTrue(plus.config.commands.get(0).payload.secret);
        assertEquals("/login ******", plus.config.commands.get(0).payload.preview);
        assertNull(plus.config.commands.get(0).payload.text);
        assertEquals("u3Rk2sY0oQf9ZtWc", plus.config.fingerprints().get("c1"));
        assertEquals("daily", plus.config.commands.get(2).on);
        assertEquals(2, plus.config.commands.get(2).at.size());
        assertEquals(600, plus.config.commands.get(3).everyMin);
        assertEquals("private", plus.config.rules.get(0).from);
        assertNotNull(plus.ai);
        assertEquals(2, plus.ai.pendingApprovals);
        assertEquals(61, plus.ai.quota.pctWeek);
        CloudModel.Account halted = accounts.get(1);
        assertFalse(halted.plus());
        assertNotNull(halted.hold);
        assertEquals("cause", halted.hold.kind);
        assertEquals("wrongPassword", halted.hold.cause);
        assertEquals("c1", halted.hold.row);
        assertTrue(halted.renewalOpen);
        CloudModel.Account waiting = accounts.get(2);
        assertEquals(25570, waiting.server.port);
        assertEquals("mc.example.net:25570", waiting.server.label());
        assertEquals(3, waiting.live.attempt);
    }

    @Test
    public void aBlockedServerCarriesStudiosWords() {
        CloudModel.Account a = CloudModel.Account.read(Vectors.json("{\"id\":\"a_7KQ2MZ3P9TW4XB1C\",\"hold\":{\"kind\":\"server_blocked\","
                + "\"reason\":{\"tr\":\"Kurallar AFK'yi yasaklıyor\",\"en\":\"\"}},\"live\":{\"state\":\"stopped\"}}"));
        assertNotNull(a.hold.blockedReason);
        assertEquals("Kurallar AFK'yi yasaklıyor", a.hold.blockedReason.in("en"));
        assertNull(a.hold.reason);
    }

    @Test
    public void aSocketStateMakesACopy() {
        CloudModel.Account a = CloudModel.Account.list(Vectors.json(Vectors.resource("cloud/fixtures/accounts.json")), "accounts").get(2);
        CloudModel.Account next = a.withLive(Vectors.json("{\"state\":\"running\",\"since\":1792846830}"));
        assertEquals("running", next.live.state);
        assertEquals("waiting", a.live.state);
        assertEquals(a.rev, next.rev);
    }

    @Test
    public void meAndItsReasons() {
        CloudModel.Me me = CloudModel.Me.read(Vectors.json(Vectors.resource("cloud/fixtures/me.json")));
        assertEquals("123456789012345678", me.userId);
        assertEquals(3, me.accountsCount);
        assertTrue(me.canBuy);
        assertFalse(me.restricted);
        CloudModel.Me banned = CloudModel.Me.read(Vectors.json("{\"user\":{\"id\":\"1\"},\"canBuy\":{\"ok\":false,\"reason\":\"player_restricted\"},"
                + "\"restricted\":{\"until\":null,\"reason\":\"abuse\"}}"));
        assertTrue(banned.restricted);
        assertNull(banned.restrictedUntil);
        assertEquals("player_restricted", banned.canBuyReason);
    }

    @Test
    public void afkPlusObjects() {
        CloudModel.PlusDoc doc = CloudModel.PlusDoc.read(Vectors.json(Vectors.resource("cloud/fixtures/plus.json")));
        assertEquals(14, doc.aiRev);
        assertEquals("Survival", doc.profileName("tr"));
        assertTrue(doc.server.botTrades());
        assertFalse(doc.server.tradesItself("owner"));
        assertEquals("26.3", doc.server.mc);
        assertNull(doc.server.moneyLabel);
        assertEquals(10_000_000L, doc.server.maxDeal);
        assertEquals(10, doc.rules.orderMax);
        assertEquals(Integer.valueOf(17), doc.rules.checksLeft);
        assertFalse(doc.rules.itemPay);
        assertEquals(60, doc.rules.approvalTimeoutMax);
        assertEquals("your_trade", doc.orders.get("y1").live);
        assertEquals(20, doc.orders.get("s1").got);
        assertEquals("ok", doc.intro.state);
        assertEquals(132000, doc.spent.total);
        assertEquals(37500, doc.spent.today);
        // After a write: the new settings and revision; the views stay until the machine sends new ones.
        JsonObject written = doc.config.deepCopy();
        written.addProperty("pay", "both");
        CloudModel.PlusDoc next = doc.with(written, 15, null);
        assertEquals(15, next.aiRev);
        assertEquals("both", next.config.get("pay").getAsString());
        assertEquals(20, next.orders.get("s1").got);
        java.util.Map<String, PlusModel.OrderView> live = new java.util.HashMap<>();
        live.put("s1", new PlusModel.OrderView("looking", 40, 1792846000, "haggling"));
        assertEquals(40, next.withOrders(live).orders.get("s1").got);
        List<CloudModel.Approval> approvals = CloudModel.Approval.list(Vectors.json(Vectors.resource("cloud/fixtures/approvals.json")), "approvals");
        assertEquals(4, approvals.size());
        assertTrue(approvals.get(0).pending(1792846800));
        assertFalse(approvals.get(0).pending(1792847100));
        assertEquals(37500, approvals.get(0).price);
        assertEquals("lavci_42", approvals.get(0).counterpart);
        assertEquals("deal", approvals.get(0).kind);
        assertTrue(approvals.get(0).money());
        assertTrue(approvals.get(1).handoff());
        assertEquals("y1", approvals.get(1).order);
        assertTrue(approvals.get(2).confirmItem());
        assertEquals("c1yk", approvals.get(2).customKey);
        assertEquals("item", approvals.get(2).customField);
        assertEquals("minecraft:red_dye", approvals.get(2).seenId);
        assertEquals(Long.valueOf(1001), approvals.get(2).seenCmd);
        assertEquals("Nadir taş", approvals.get(2).seenLore.get(0));
        assertFalse(approvals.get(3).pending(1792846800));
        CloudModel.QuotaView quota = CloudModel.QuotaView.read(Vectors.json(Vectors.resource("cloud/fixtures/quota.json")));
        assertEquals(34, quota.pct5);
        assertEquals(1500, quota.giftsLeft());
        assertEquals(14500, quota.boughtRemaining);
        assertEquals("Holiday week", quota.promoLabel.in("en"));
        List<CloudModel.InboxThread> threads = CloudModel.InboxThread.list(Vectors.json(Vectors.resource("cloud/fixtures/inbox.json")), "threads");
        assertEquals(3, threads.size());
        assertEquals("handoff", threads.get(1).state);
        CloudModel.InboxPage page = CloudModel.InboxPage.read(Vectors.json(Vectors.resource("cloud/fixtures/thread.json")));
        assertTrue(page.canReply);
        assertTrue(page.lines.get(4).hidden);
        assertFalse(page.lines.get(0).hidden);
    }

    @Test
    public void spentOnlyFromLiveAnswers() {
        // CAFK-W5: null when the machine did not answer in time or the account is not AFK+ now; left out by older Workers.
        String config = "\"config\":{\"v\":1},\"aiRev\":3";
        assertNull(CloudModel.PlusDoc.read(Vectors.json("{" + config + "}")).spent);
        assertNull(CloudModel.PlusDoc.read(Vectors.json("{" + config + ",\"spent\":null}")).spent);
        CloudModel.Spent spent = CloudModel.PlusDoc.read(Vectors.json("{" + config + ",\"spent\":{\"total\":0,\"today\":0}}")).spent;
        assertEquals(0, spent.total);
        assertEquals(0, spent.today);
        CloudModel.Spent partial = CloudModel.Spent.read(Vectors.json("{\"today\":1200}"));
        assertEquals(0, partial.total);
        assertEquals(1200, partial.today);
        assertNull(CloudModel.Spent.read(Vectors.json("{}")));
        CloudModel.AiView live = CloudModel.AiView.read(Vectors.json("{\"state\":\"on\",\"spent\":{\"total\":90000,\"today\":15000}}"));
        assertEquals(90000, live.spent.total);
        CloudModel.AiView cached = CloudModel.AiView.read(Vectors.json("{\"state\":\"on\"}"));
        assertNull(cached.spent);
    }

    @Test
    public void notificationPreferencesRoundTrip() {
        CloudModel.NotifyPrefs prefs = CloudModel.NotifyPrefs.read(Vectors.json("{\"events\":{\"stopped\":true,\"summary\":false},"
                + "\"quiet\":{\"enabled\":true,\"from\":\"23:00\",\"to\":\"08:00\"}}"));
        assertTrue(prefs.on("stopped"));
        assertFalse(prefs.on("summary"));
        assertFalse(prefs.on("weekly"));
        CloudModel.NotifyPrefs next = prefs.with("summary", true).withQuiet(false, "22:30", "07:00");
        JsonObject json = next.toJson();
        assertTrue(json.getAsJsonObject("events").get("summary").getAsBoolean());
        assertEquals("22:30", json.getAsJsonObject("quiet").get("from").getAsString());
        assertFalse(json.getAsJsonObject("quiet").get("enabled").getAsBoolean());
    }
}
