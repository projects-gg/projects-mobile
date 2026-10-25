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
        assertEquals("Survival", doc.profileName);
        assertTrue(doc.profileTrade);
        assertEquals("ok", doc.intro.state);
        assertEquals(132000, doc.spent.total);
        assertEquals(37500, doc.spent.today);
        List<CloudModel.Approval> approvals = CloudModel.Approval.list(Vectors.json(Vectors.resource("cloud/fixtures/approvals.json")), "approvals");
        assertEquals(3, approvals.size());
        assertTrue(approvals.get(0).pending(1792846800));
        assertFalse(approvals.get(0).pending(1792847100));
        assertEquals(37500, approvals.get(0).price);
        assertEquals("Kaan_07", approvals.get(0).counterpart);
        assertFalse(approvals.get(2).pending(1792846800));
        CloudModel.QuotaView quota = CloudModel.QuotaView.read(Vectors.json(Vectors.resource("cloud/fixtures/quota.json")));
        assertEquals(34, quota.pct5);
        assertEquals(1500, quota.giftsLeft());
        assertEquals("Holiday week", quota.promoLabel.in("en"));
        CloudModel.PlusCatalog catalog = CloudModel.PlusCatalog.read(Vectors.json(Vectors.resource("cloud/fixtures/catalog.json")));
        assertEquals("Elmas", catalog.itemName("minecraft:diamond", "tr"));
        assertEquals("Diamond", catalog.itemName("minecraft:diamond", "en"));
        assertEquals("cobblestone", catalog.itemName("minecraft:cobblestone", "tr"));
        assertEquals(60, catalog.approvalTimeoutMax);
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
