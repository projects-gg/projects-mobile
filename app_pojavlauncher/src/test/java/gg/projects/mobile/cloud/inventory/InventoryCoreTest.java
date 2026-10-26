package gg.projects.mobile.cloud.inventory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import gg.projects.mobile.social.Json;

/** The inventory's pure core: frames, the game's click rules, the action sheet's rows, moves on their way, the layouts. */
public class InventoryCoreTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject vectors() {
        try (InputStream in = InventoryCoreTest.class.getClassLoader().getResourceAsStream("cloud/inv-op-v1.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            StringBuilder body = new StringBuilder();
            for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n")) if (!line.startsWith("//")) body.append(line).append('\n');
            return json(body.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InvItem item(int slot, String id, int count) {
        return InvItem.of(slot, "minecraft:" + id, count, id, id);
    }

    private static InvRules.State state(int held, InvItem cursor, InvItem... items) {
        Map<Integer, InvItem> slots = new TreeMap<>();
        for (InvItem i : items) slots.put(i.slot, i);
        return new InvRules.State(held, cursor, slots);
    }

    // ---- ops and frames ----

    @Test
    public void opsHaveTheContractsShapes() {
        List<String> valid = new ArrayList<>();
        for (JsonObject s : Json.objects(vectors(), "shape")) if (s.get("problem").isJsonNull()) valid.add(Json.obj(s, "op").toString());
        List<InvOp> ours = Arrays.asList(
                InvOp.click(InvLayout.Area.INV, 9, 0, "pickup", 0),
                InvOp.drop(InvLayout.Area.INV, 9, true, 0),
                InvOp.use(8),
                InvOp.hold(0),
                InvOp.close(7),
                InvOp.snapshot(),
                InvOp.click(InvLayout.Area.WIN, 53, 0, "pickup", 7),
                InvOp.click(InvLayout.Area.INV, 9, 1, "pickup", 0));
        for (InvOp op : ours) assertTrue(op.toString(), valid.contains(op.toJson().toString()));
        JsonObject frame = InvOp.snapshot().frame("a_7KQ2MZ3P9TW4XB1C", "m1");
        assertEquals("invop", Json.str(frame, "t"));
        assertTrue(frame.toString().length() < 1024);
        assertTrue(InvOp.validId("m1z"));
        assertFalse(InvOp.validId("bad id"));
        assertFalse(InvOp.validId("123456789012345678901234567890123"));
    }

    @Test
    public void framesReadLeniently() {
        InvFrames.Inv inv = InvFrames.Inv.read(json("{\"t\":\"inv\",\"acct\":\"a\",\"held\":2,\"cursor\":{\"id\":\"minecraft:diamond\",\"count\":3,"
                + "\"label\":{\"tr\":\"Elmas\",\"en\":\"Diamond\"}},\"items\":[{\"slot\":36,\"id\":\"minecraft:diamond_sword\",\"count\":1,"
                + "\"label\":{\"tr\":\"Elmas Kılıç\",\"en\":\"Diamond Sword\"},\"durability\":{\"left\":1500,\"max\":1561},"
                + "\"ench\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5,\"label\":{\"tr\":\"Keskinlik V\",\"en\":\"Sharpness V\"}}]},"
                + "{\"slot\":99,\"id\":\"minecraft:stone\",\"count\":1},{\"slot\":10,\"count\":2},{\"slot\":11,\"id\":\"minecraft:dirt\",\"count\":0}],"
                + "\"snap\":true}"));
        assertEquals(2, inv.held);
        assertNotNull(inv.cursor);
        assertEquals(3, inv.cursor.count);
        assertEquals(1, inv.items.size());
        InvItem sword = inv.items.get(0);
        assertTrue(sword.wears());
        assertTrue(sword.glints());
        assertEquals("Keskinlik V", sword.ench.get(0).label.tr);
        assertTrue(inv.snap);

        InvFrames.Win win = InvFrames.Win.read(json("{\"t\":\"win\",\"acct\":\"a\",\"wseq\":17,\"open\":{\"type\":\"generic_9x3\",\"title\":\"Kitler\",\"size\":27},"
                + "\"items\":[{\"slot\":0,\"id\":\"minecraft:chest\",\"count\":1},{\"slot\":27,\"id\":\"minecraft:dirt\",\"count\":1}]}"));
        assertFalse(win.closed);
        assertEquals(17, win.wseq);
        assertEquals(1, win.items.size());
        assertTrue(InvFrames.Win.read(json("{\"t\":\"win\",\"acct\":\"a\",\"wseq\":17,\"closed\":true}")).closed);

        assertEquals("service", InvFrames.Status.read(json("{\"state\":\"off\",\"reason\":\"something_new\"}")).reason);
        assertTrue(InvFrames.Status.read(json("{\"state\":\"ok\",\"lock\":\"trade\"}")).tradeLock());
        assertFalse(InvFrames.Status.read(json("{\"state\":\"weird\"}")).ok());
    }

    // ---- the game's rules on the player's screen ----

    @Test
    public void pickupTakesPlacesMergesAndSwaps() {
        InvRules.State s = state(0, null, item(9, "diamond", 10), item(10, "dirt", 5), item(11, "diamond", 60));
        InvRules.State half = InvRules.pickup(s, 9, 1);
        assertEquals(5, half.cursor.count);
        assertEquals(5, half.at(9).count);
        InvRules.State all = InvRules.pickup(s, 9, 0);
        assertEquals(10, all.cursor.count);
        assertNull(all.at(9));
        // Into a stack of the same: up to 64, the rest stays carried.
        InvRules.State merged = InvRules.pickup(all, 11, 0);
        assertEquals(64, merged.at(11).count);
        assertEquals(6, merged.cursor.count);
        // One by one with the right button.
        InvRules.State one = InvRules.pickup(all, 12, 1);
        assertEquals(1, one.at(12).count);
        assertEquals(9, one.cursor.count);
        // Another item: they trade places.
        InvRules.State swapped = InvRules.pickup(all, 10, 0);
        assertEquals("minecraft:dirt", swapped.cursor.id);
        assertEquals("minecraft:diamond", swapped.at(10).id);
    }

    @Test
    public void armourSlotsTakeOnlyTheirArmour() {
        InvRules.State s = state(0, item(-1, "diamond_helmet", 1), item(9, "dirt", 1));
        assertNull(InvRules.pickup(s, 6, 0).at(6));
        assertEquals("minecraft:diamond_helmet", InvRules.pickup(s, 5, 0).at(5).id);
        InvItem cursed = item(5, "iron_helmet", 1).withEnch(Collections.singletonList(
                new InvItem.Ench("minecraft:binding_curse", 1, new InvItem.Label("Bağlılık Laneti", "Curse of Binding"))));
        InvRules.State worn = state(0, null, cursed);
        assertNull(InvRules.pickup(worn, 5, 0).cursor);
        assertFalse(InvRules.mayPickup(5, cursed));
        assertEquals(1, InvRules.maxStack(item(9, "diamond_sword", 1)));
        assertEquals(16, InvRules.maxStack(item(9, "ender_pearl", 1)));
        assertEquals(64, InvRules.maxStack(item(9, "stone", 1)));
    }

    @Test
    public void quickMoveFollowsTheGame() {
        InvRules.State s = state(0, null, item(9, "stone", 20), item(36, "stone", 50), item(40, "dirt", 3), item(12, "iron_chestplate", 1));
        InvRules.State moved = InvRules.quickMove(s, 9);
        assertEquals(64, moved.at(36).count);
        assertEquals(6, moved.at(37).count);
        assertNull(moved.at(9));
        assertEquals(InvRules.Target.HOTBAR, InvRules.quickMoveTarget(s, 9));
        assertEquals(InvRules.Target.INVENTORY, InvRules.quickMoveTarget(s, 40));
        assertEquals(InvRules.Target.EQUIPMENT, InvRules.quickMoveTarget(s, 12));
        assertEquals("minecraft:iron_chestplate", InvRules.quickMove(s, 12).at(6).id);
    }

    @Test
    public void dropTakesOneOrTheStack() {
        InvRules.State s = state(0, null, item(9, "stone", 20));
        assertEquals(19, InvRules.drop(s, 9, false).at(9).count);
        assertNull(InvRules.drop(s, 9, true).at(9));
        // Never with a stack carried.
        assertEquals(20, InvRules.drop(state(0, item(-1, "dirt", 1), item(9, "stone", 20)), 9, true).at(9).count);
    }

    // ---- the action sheet ----

    private static InvPlan.State plan(InvRules.State inv, String type, Map<Integer, InvItem> win, long wseq) {
        return new InvPlan.State(InvLayout.screenFor(type, type == null ? 0 : 27), inv, win, wseq, null, "tr");
    }

    private static List<String> words(List<InvPlan.Row> rows) {
        List<String> out = new ArrayList<>();
        for (InvPlan.Row r : rows) out.add(r.word);
        return out;
    }

    @Test
    public void rowsOnThePlayersOwnScreen() {
        InvRules.State inv = state(0, null, item(9, "stone", 20), item(37, "bread", 1));
        InvPlan.State st = plan(inv, null, Collections.<Integer, InvItem>emptyMap(), 0);
        assertEquals(Arrays.asList("take", "takeHalf", "toHotbar", "dropOne", "dropStack"),
                words(InvPlan.rows(st, st.screen.spot(InvLayout.Area.INV, 9), true, false)));
        assertEquals(Arrays.asList("take", "toInventory", "hold", "use", "drop"),
                words(InvPlan.rows(st, st.screen.spot(InvLayout.Area.INV, 37), true, false)));
        // The empty held slot offers nothing; an empty hotbar slot offers to hold it.
        assertTrue(InvPlan.rows(st, st.screen.spot(InvLayout.Area.INV, 36), true, false).isEmpty());
        assertEquals(Collections.singletonList("hold"), words(InvPlan.rows(st, st.screen.spot(InvLayout.Area.INV, 38), true, false)));
        // Looking only, or a trade's lock: no rows at all.
        assertTrue(InvPlan.rows(st, st.screen.spot(InvLayout.Area.INV, 9), false, false).isEmpty());
        assertTrue(InvPlan.rows(st, st.screen.spot(InvLayout.Area.INV, 9), true, true).isEmpty());
        // With a stack carried: place (or swap), one, put back.
        InvRules.State carrying = state(0, item(-1, "stone", 5), item(9, "stone", 20), item(10, "dirt", 2));
        InvPlan.State c = plan(carrying, null, Collections.<Integer, InvItem>emptyMap(), 0);
        assertEquals(Arrays.asList("place", "placeOne", "putBack"), words(InvPlan.rows(c, c.screen.spot(InvLayout.Area.INV, 9), true, false)));
        assertEquals(Arrays.asList("swap", "putBack"), words(InvPlan.rows(c, c.screen.spot(InvLayout.Area.INV, 10), true, false)));
        assertEquals(Collections.singletonList("putBack"), words(InvPlan.rows(c, c.screen.spot(InvLayout.Area.INV, 5), true, false)));
    }

    @Test
    public void rowsInAServersMenuNameTheClicks() {
        Map<Integer, InvItem> win = new HashMap<>();
        win.put(0, item(0, "diamond", 1));
        InvPlan.State st = plan(state(0, null, item(9, "stone", 3)), "generic_9x3", win, 41);
        assertEquals(Arrays.asList("click", "rightClick", "shiftClick"), words(InvPlan.rows(st, st.screen.spot(InvLayout.Area.WIN, 0), true, false)));
        // The player's rows under a menu: the same clicks, dropping only from the player's own slots.
        assertEquals(Arrays.asList("click", "rightClick", "shiftClick", "dropOne", "dropStack"),
                words(InvPlan.rows(st, st.screen.spot(InvLayout.Area.INV, 9), true, false)));
        InvPlan.Step step = InvPlan.click(st, st.screen.spot(InvLayout.Area.WIN, 0), 0);
        assertNotNull(step);
        assertNull(step.predict);
        assertEquals(41, step.op.wseq);
        assertEquals("{\"action\":\"close\",\"wseq\":41}", InvPlan.close(st).op.toString());
    }

    @Test
    public void putBackGoesHomeFirst() {
        InvRules.State inv = state(0, item(-1, "stone", 5), item(9, "dirt", 1));
        InvLayout.Screen screen = InvLayout.screenFor(null, 0);
        InvPlan.State fromOrigin = new InvPlan.State(screen, inv, Collections.<Integer, InvItem>emptyMap(), 0, "inv:20", "tr");
        assertEquals(20, InvPlan.putBack(fromOrigin).op.slot);
        InvPlan.State noOrigin = new InvPlan.State(screen, inv, Collections.<Integer, InvItem>emptyMap(), 0, null, "tr");
        assertEquals(10, InvPlan.putBack(noOrigin).op.slot);
        InvPlan.State originTaken = new InvPlan.State(screen, inv, Collections.<Integer, InvItem>emptyMap(), 0, "inv:9", "tr");
        assertEquals(10, InvPlan.putBack(originTaken).op.slot);
    }

    // ---- moves on their way ----

    private static final class Clock implements InvQueue.Scheduler {
        long now = 1_000;
        final List<Object[]> tasks = new ArrayList<>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public Object post(Runnable task, long delayMs) {
            Object[] t = {now + delayMs, task};
            tasks.add(t);
            return t;
        }

        @Override
        public void cancel(Object token) {
            tasks.remove(token);
        }

        void advance(long ms) {
            now += ms;
            for (Object[] t : new ArrayList<>(tasks)) {
                if ((Long) t[0] <= now && tasks.remove(t)) ((Runnable) t[1]).run();
            }
        }
    }

    private static final class Recorder implements InvQueue.Hooks {
        final List<InvQueue.Answer> answers = new ArrayList<>();
        final List<String> said = new ArrayList<>();
        final List<String> origins = new ArrayList<>();

        @Override
        public void send(InvOp op, InvQueue.Answer answer) {
            answers.add(answer);
        }

        @Override
        public void changed() {}

        @Override
        public void message(String key) {
            said.add("message:" + key);
        }

        @Override
        public void speak(String word, String item) {
            said.add(word + ":" + item);
        }

        @Override
        public void origin(String key) {
            origins.add(key);
        }
    }

    @Test
    public void movesSettleOnAFrameOrAreRefused() {
        Clock clock = new Clock();
        Recorder rec = new Recorder();
        InvQueue queue = new InvQueue(rec, clock);
        InvRules.State inv = state(0, null, item(9, "stone", 20));
        InvPlan.State st = plan(inv, null, Collections.<Integer, InvItem>emptyMap(), 0);
        InvLayout.Spot spot = st.screen.spot(InvLayout.Area.INV, 9);
        assertTrue(queue.run(InvPlan.click(st, spot, 0)));
        assertEquals(Collections.singletonList("inv:9"), rec.origins);
        Set<String> keys = new LinkedHashSet<>();
        InvRules.State shown = queue.shown(inv, keys);
        assertNull(shown.at(9));
        assertEquals(20, shown.cursor.count);
        assertTrue(keys.contains("cursor"));
        rec.answers.get(0).answered(null);
        queue.frame();
        assertTrue(queue.idle());
        assertEquals(Collections.singletonList("pickedUp:stone"), rec.said);

        // An ok without any frame after it: the server changed nothing.
        queue.run(InvPlan.drop(st, spot, false));
        rec.answers.get(1).answered(null);
        clock.advance(InvQueue.SETTLE_MS);
        assertEquals("message:refused", rec.said.get(rec.said.size() - 1));

        // Too fast: the line, and the next tap waits out the machine's time.
        queue.run(InvPlan.drop(st, spot, false));
        rec.answers.get(2).answered(new InvFrames.Error("rate_limited", null, null, 2));
        assertEquals("message:slowDown", rec.said.get(rec.said.size() - 1));
        assertFalse(queue.run(InvPlan.drop(st, spot, false)));
        clock.advance(2_000);
        assertTrue(queue.run(InvPlan.drop(st, spot, false)));

        // No answer at all: out of reach.
        clock.advance(InvQueue.ANSWER_MS);
        assertEquals("message:unreachable", rec.said.get(rec.said.size() - 1));
        // A stale window says nothing (the view redraws).
        queue.run(InvPlan.drop(st, spot, false));
        int before = rec.said.size();
        rec.answers.get(rec.answers.size() - 1).answered(InvFrames.Error.of("stale"));
        assertEquals(before, rec.said.size());
        assertEquals("viewOnly", InvQueue.messageFor(new InvFrames.Error("inventory_off", "view", null, 0)));
        assertEquals("tradeLocked", InvQueue.messageFor(InvFrames.Error.of("busy_trade")));
    }

    // ---- layouts and words ----

    @Test
    public void screensFollowTheGame() {
        InvLayout.Screen player = InvLayout.screenFor(null, 0);
        assertEquals(41, player.slots.size());
        assertEquals(InvLayout.ScreenKind.PLAYER, player.kind);
        assertEquals(176, player.width);
        assertEquals(27 + 36, InvLayout.screenFor("generic_9x3", 27).slots.size());
        assertEquals(54 + 36, InvLayout.screenFor("generic_9x6", 54).slots.size());
        assertEquals(5 + 36, InvLayout.screenFor("hopper", 5).slots.size());
        assertEquals(9 + 36, InvLayout.screenFor("generic_3x3", 9).slots.size());
        assertEquals(27 + 36, InvLayout.screenFor("shulker_box", 27).slots.size());
        assertEquals(10 + 36, InvLayout.screenFor("beacon_like", 10).slots.size());
        assertEquals(InvLayout.ScreenKind.SINGLE, InvLayout.screenFor("lectern", 1).kind);
        InvLayout.Screen chest = InvLayout.screenFor("generic_9x3", 27);
        assertFalse(InvLayout.slotUsable(chest, chest.spot(InvLayout.Area.WIN, 0)) == false);
        assertNull(chest.spot(InvLayout.Area.INV, 5));
        InvLayout.Spot hit = player.at(8 + 18 * 2 + 3, 51 + 4);
        assertNotNull(hit);
        assertEquals(11, hit.slot);
        assertEquals(6, InvLayout.guiScale(1056));
        assertEquals(5, InvLayout.guiScale(1018));
        assertEquals(1, InvLayout.guiScale(100));
    }

    private static final InvText.Words WORDS = new InvText.Words() {
        @Override
        public String durability(int left, int max) {
            return "Dayanıklılık: " + left + " / " + max;
        }

        @Override
        public String insideStack(String item, int n) {
            return item + " x " + n;
        }

        @Override
        public String insideMore(int n) {
            return "ve " + n + " tane daha...";
        }

        @Override
        public String insideMoreUnknown() {
            return "ve daha fazlası...";
        }

        @Override
        public String partial() {
            return "Bazı özellikleri gösterilemiyor.";
        }

        @Override
        public String count(int n) {
            return n + " adet";
        }

        @Override
        public String durabilityPercent(int p) {
            return "dayanıklılık yüzde " + p;
        }

        @Override
        public String enchanted() {
            return "büyülü";
        }
    };

    @Test
    public void anyonesTextIsPlain() {
        assertEquals("Projects Güvenlik", InvText.cleanText("‮Projects\u0000 §cGüvenlik"));
        assertEquals("a b", InvText.cleanText("  a \t\n b  "));
        InvItem named = item(9, "diamond_sword", 1).withName("§6Efsane⁦ Kılıç");
        assertEquals("Efsane Kılıç", InvText.itemName(named, "tr"));
        assertEquals("stone", InvText.itemName(InvItem.of(9, "minecraft:stone", 1, "", ""), "tr"));
        assertEquals("Dirt", InvText.itemName(InvItem.of(9, "minecraft:dirt", 1, "", "Dirt"), "tr"));
    }

    @Test
    public void tooltipAndSpeechInTheGamesOrder() {
        InvItem sword = item(36, "diamond_sword", 1).withDurability(1200, 1561)
                .withEnch(Collections.singletonList(new InvItem.Ench("minecraft:sharpness", 5, new InvItem.Label("Keskinlik V", "Sharpness V"))))
                .withLore(Collections.singletonList("Satılık"));
        List<InvText.Line> lines = InvText.tooltip(sword, "tr", WORDS);
        assertEquals(4, lines.size());
        assertEquals(InvText.AQUA, lines.get(0).color);
        assertEquals("Keskinlik V", lines.get(1).text);
        assertEquals(InvText.LORE, lines.get(2).color);
        assertEquals("Dayanıklılık: 1200 / 1561", lines.get(3).text);
        assertEquals("diamond_sword, dayanıklılık yüzde 77, büyülü", InvText.speech(sword, "tr", WORDS));
        InvItem box = item(9, "shulker_box", 1).withInside(new InvItem.Inside(130, Arrays.asList(item(-1, "stone", 64), item(-1, "dirt", 2))));
        List<InvText.Line> boxLines = InvText.tooltip(box, "tr", WORDS);
        assertEquals("stone x 64", boxLines.get(1).text);
        assertEquals("ve daha fazlası...", boxLines.get(3).text);
        assertTrue(boxLines.get(3).italic);
        assertEquals(13, InvText.durabilityWidth(1561, 1561));
        assertEquals(0x00ff00, InvText.durabilityColor(1561, 1561) & 0x00ff00);
        assertEquals(0x3f3f3f, InvText.shadowOf(0xffffff));
    }
}
