package gg.projects.mobile.cloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/**
 * DONUT-ORDER on the phone: "Sipariş menüsünü kontrol etsin" ({@code discovery.orderMenu}). On writes true; off writes false
 * only over a stored true, else the key stays out (a PUT without it keeps what the Worker has); Studio's switch is off by
 * default.
 */
public class OrderMenuTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject discovery(PlusConfig e) {
        return e.settingsJson().getAsJsonObject("discovery");
    }

    @Test
    public void onWritesTrue() {
        PlusConfig e = new PlusConfig(json("{\"discovery\":{\"ads\":false}}"));
        assertFalse(e.orderMenu());
        e.setOrderMenu(true, false);
        assertTrue(e.orderMenu());
        assertTrue(discovery(e).get("orderMenu").getAsBoolean());
    }

    @Test
    public void offOverAStoredTrueWritesFalse() {
        PlusConfig e = new PlusConfig(json("{\"discovery\":{\"orderMenu\":true}}"));
        assertTrue(e.orderMenu());
        e.setOrderMenu(false, true);
        assertFalse(e.orderMenu());
        assertTrue(discovery(e).has("orderMenu"));
        assertFalse(discovery(e).get("orderMenu").getAsBoolean());
    }

    @Test
    public void offWithNothingStoredLeavesTheKeyOut() {
        PlusConfig e = new PlusConfig(json("{\"discovery\":{\"ads\":true}}"));
        e.setOrderMenu(true, false);
        e.setOrderMenu(false, false);
        assertFalse(discovery(e).has("orderMenu"));
        assertTrue(discovery(e).get("ads").getAsBoolean());
        // A document without discovery at all stays without it.
        PlusConfig bare = new PlusConfig(json("{}"));
        bare.setOrderMenu(false, false);
        assertFalse(bare.settingsJson().has("discovery"));
    }

    @Test
    public void studiosSwitchIsOffByDefault() {
        assertFalse(PlusModel.Rules.read(json("{}")).orderMenu);
        assertFalse(PlusModel.Rules.read(json("{\"features\":{\"orders\":true}}")).orderMenu);
        assertTrue(PlusModel.Rules.read(json("{\"features\":{\"orderMenu\":true}}")).orderMenu);
    }
}
