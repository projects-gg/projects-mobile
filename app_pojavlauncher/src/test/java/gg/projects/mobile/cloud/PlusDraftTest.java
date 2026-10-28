package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import gg.projects.mobile.cloud.plus.ItemFacts;
import gg.projects.mobile.cloud.plus.OrderDraft;
import gg.projects.mobile.cloud.plus.OrderRules;
import gg.projects.mobile.social.Json;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The AFK+ order card in Java (FREE-M): OrderRules (the free rule check, the money reader) and OrderDraft (questions, chips,
 * orders, statuses) against the contract's shared vectors: afkplus-intake-v1.json ({@code rules}, {@code readers},
 * {@code drafts}, {@code answers}, with its synthetic item data) and afkplus-order-v1.json ({@code status}). The launcher's
 * own tests (order-rules.test.ts, order-draft.test.ts) run the same vectors with the same skips.
 */
public class PlusDraftTest {
    private static JsonObject intake;
    private static JsonObject orders;
    private static ItemFacts data;
    private static final Map<String, JsonObject> DRAFT_CASES = new LinkedHashMap<>();

    @BeforeClass
    public static void load() {
        intake = Vectors.load("cloud/afkplus-intake-v1.json");
        orders = Vectors.load("cloud/afkplus-order-v1.json");
        data = new VectorItems(Json.obj(intake, "data"));
        for (JsonObject c : Json.objects(intake, "drafts")) DRAFT_CASES.put(Json.str(c, "name"), c);
    }

    // ---- the vectors' synthetic item data (format 1) as ItemFacts ----

    /** FREE-ITEMS' format 1: items {@code [id, stack, maxDamage, tr, en, group, extras?]}, ench {@code [id, max, tr, en, extras?]}. */
    static final class VectorItems implements ItemFacts {
        private final Map<String, Item> items = new HashMap<>();
        private final Map<String, Enchantment> enchantments = new HashMap<>();
        final int itemCount;

        VectorItems(JsonObject file) {
            assertEquals(1, Json.num(file, "format", 0));
            JsonArray ench = Json.arr(file, "ench");
            List<String> ids = new ArrayList<>();
            for (JsonElement row : ench) ids.add(row.getAsJsonArray().get(0).getAsString());
            for (int i = 0; i < ench.size(); i++) {
                JsonArray row = ench.get(i).getAsJsonArray();
                JsonObject extra = row.size() > 4 ? row.get(4).getAsJsonObject() : null;
                List<String> excludes = new ArrayList<>();
                for (JsonElement index : Json.arr(extra, "x")) excludes.add(ids.get(index.getAsInt()));
                boolean curse = Json.num(extra, "c", 0) == 1;
                enchantments.put(ids.get(i), new Enchantment(ids.get(i), row.get(1).getAsInt(), curse, excludes));
            }
            JsonArray groups = Json.arr(file, "groups");
            JsonArray rows = Json.arr(file, "items");
            for (JsonElement element : rows) {
                JsonArray row = element.getAsJsonArray();
                int group = row.get(5).getAsInt();
                List<String> fits = new ArrayList<>();
                if (group != -1) {
                    for (JsonElement index : groups.get(group).getAsJsonArray()) fits.add(ids.get(index.getAsInt()));
                }
                JsonObject extra = row.size() > 6 ? row.get(6).getAsJsonObject() : null;
                String id = row.get(0).getAsString();
                items.put(id, new Item(id, row.get(1).getAsInt(), row.get(2).getAsInt(), fits, Json.num(extra, "o", 0) == 1));
            }
            itemCount = rows.size();
        }

        @Override
        public Item item(String id) {
            return items.get(id);
        }

        @Override
        public Enchantment enchantment(String id) {
            return enchantments.get(id);
        }
    }

    // ---- helpers ----

    /** Counts a vector group's cases and keeps every failure, so one run shows all of them. */
    private static final class Report {
        final String group;
        int passed;
        int skipped;
        int failed;
        final StringBuilder failures = new StringBuilder();
        /** Why cases are skipped, with how many (the reasons are named, never silent). */
        final Map<String, Integer> skipReasons = new LinkedHashMap<>();

        Report(String group) {
            this.group = group;
        }

        void check(String name, boolean ok, String detail) {
            if (ok) {
                passed++;
            } else {
                failed++;
                failures.append("\n  ").append(name).append(": ").append(detail);
            }
        }

        void skip(String why) {
            skipped++;
            Integer count = skipReasons.get(why);
            skipReasons.put(why, count == null ? 1 : count + 1);
        }

        void finish(int total) {
            StringBuilder why = new StringBuilder();
            for (Map.Entry<String, Integer> reason : skipReasons.entrySet()) {
                why.append("\n    ").append(reason.getValue()).append(" skipped: ").append(reason.getKey());
            }
            System.out.println("[PlusDraftTest] " + group + ": " + passed + " passed, " + skipped + " skipped, " + failed
                    + " failed of " + total + why);
            assertEquals(group + " failures:" + failures, 0, failed);
            assertEquals(group + ": every case of the vectors is run or skipped by name", total, passed + skipped);
        }
    }

    private static OrderRules.RuleContext ruleContext(JsonObject ctx) {
        OrderRules.RuleContext rc = new OrderRules.RuleContext();
        if (ctx.has("maxDeal")) rc.maxDeal(Json.num(ctx, "maxDeal", 0));
        if (ctx.has("active")) rc.active((int) Json.num(ctx, "active", 0));
        if (ctx.has("orderMax")) rc.orderMax((int) Json.num(ctx, "orderMax", 0));
        if (ctx.has("minute")) rc.minute((int) Json.num(ctx, "minute", 0));
        if (ctx.has("today")) rc.today((int) Json.num(ctx, "today", 0));
        if (ctx.has("perDay")) rc.perDay((int) Json.num(ctx, "perDay", 0));
        return rc;
    }

    private static OrderDraft.Ctx contextOf(JsonObject ctx) {
        JsonObject money = Json.obj(ctx, "money");
        JsonObject features = Json.obj(ctx, "features");
        JsonObject limits = Json.obj(ctx, "limits");
        JsonElement steps = money == null ? null : money.get("round");
        JsonArray round = steps != null && steps.isJsonArray() ? steps.getAsJsonArray() : null;
        return new OrderDraft.Ctx()
                .data(data)
                .custom(Json.objects(ctx, "custom"))
                .money(Json.num(money, "maxDeal", 0), round)
                .pay(Json.str(ctx, "pay", "money"))
                .features(Json.bool(features, "itemPay", true), Json.bool(features, "customItems", true))
                .limits(Json.numOrNull(limits, "perDealMax"), Json.numOrNull(limits, "totalBudget"))
                .trades(Json.str(ctx, "trades", "bot"));
    }

    private static JsonArray array(List<JsonObject> list) {
        JsonArray out = new JsonArray();
        for (JsonObject o : list) out.add(o);
        return out;
    }

    /** The vector's value, or null when it is missing or JSON null. */
    private static JsonObject objectOrNull(JsonObject o, String key) {
        return Json.obj(o, key);
    }

    private static JsonObject draftOf(String name) {
        JsonObject c = DRAFT_CASES.get(name);
        assertNotNull("draft case " + name, c);
        return c.get("draft").getAsJsonObject();
    }

    private static OrderDraft.Ctx ctxOf(String name) {
        return contextOf(DRAFT_CASES.get(name).get("ctx").getAsJsonObject());
    }

    private static boolean sameOrder(JsonObject expected, JsonObject actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    // ---- the vectors ----

    @Test
    public void theVectorsHoldWhatTheContractCounts() {
        JsonObject counts = Json.obj(intake, "counts");
        assertEquals(Json.num(counts, "rules", -1), Json.arr(intake, "rules").size());
        assertEquals(Json.num(counts, "readers", -1), Json.arr(intake, "readers").size());
        assertEquals(Json.num(counts, "drafts", -1), Json.arr(intake, "drafts").size());
        assertEquals(Json.num(counts, "answers", -1), Json.arr(intake, "answers").size());
        assertEquals(Json.num(Json.obj(orders, "counts"), "status", -1), Json.arr(orders, "status").size());
        assertEquals(Json.num(intake, "textMin", -1), OrderRules.TEXT_MIN);
        assertEquals(Json.num(intake, "textMax", -1), OrderRules.TEXT_MAX);
        assertEquals(Json.num(intake, "shownQuestions", -1), OrderDraft.SHOWN_QUESTIONS);
        assertEquals(Json.arr(Json.obj(intake, "data"), "items").size(), ((VectorItems) data).itemCount);
    }

    /**
     * Cases the friends service's word list decides: the app never carries the list (the service answers them on "Kontrol
     * et"), so the hint lets them through, as the launcher's does.
     */
    private static final Set<String> WORD_LIST = new HashSet<>(Arrays.asList("a listed word"));

    @Test
    public void rulesVectors() {
        Report report = new Report("rules");
        List<JsonObject> cases = Json.objects(intake, "rules");
        for (JsonObject c : cases) {
            String name = Json.str(c, "name");
            OrderRules.RuleCheck check = OrderRules.checkOrderText(Json.str(c, "text"), ruleContext(Json.obj(c, "ctx")));
            if (WORD_LIST.contains(name)) {
                // Not skipped: the app's answer for a text only the word list refuses is ok (the service says the rest).
                report.check(name, "ok".equals(check.result), "the word list is the service's: expected ok, got " + check);
                continue;
            }
            String expected = Json.str(c, "result");
            List<String> notes = Json.strings(c, "notes");
            report.check(name, expected.equals(check.result) && notes.equals(check.notes),
                    "text \"" + Json.str(c, "text") + "\" expected " + expected + " " + notes + ", got " + check);
        }
        report.finish(cases.size());
    }

    /**
     * The agent reads the model's quotes with word numbers ("kirk bin", "forty thousand", "a hundred"), "buçuk" (a half) and
     * "grand"; the field reader reads digits (and the multiplier words after them) only, as the launcher's does. Keys: the
     * quote and the cap the vector gives.
     */
    private static final Set<String> AGENT_ONLY_AMOUNTS = new HashSet<>(Arrays.asList(
            "kırk bin|10000000", "kırk bine|10000000", "otuz beş bin|10000000", "iki yüz elli bin|10000000",
            "bir buçuk milyon|10000000", "1 buçuk milyon|10000000", "yüz bin|10000000", "forty thousand|10000000",
            "a hundred|10000000", "40 grand|10000000", "on beş milyon|100000000"));

    @Test
    public void readersVectors() {
        Report report = new Report("readers");
        List<JsonObject> cases = Json.objects(intake, "readers");
        for (JsonObject c : cases) {
            String reader = Json.str(c, "reader");
            String text = Json.str(c, "text");
            String name = reader + " " + text;
            if (!"amount".equals(reader)) {
                report.skip("the agent's reader of the model's quotes (count, level, pct, days, quoted): the app has none");
                continue;
            }
            long maxDeal = Json.num(c, "arg", 0);
            if (AGENT_ONLY_AMOUNTS.contains(text + "|" + maxDeal)) {
                report.skip("amount quotes with a word number, buçuk or grand: the agent reads them, the field reader digits only");
                continue;
            }
            Long expected = Json.numOrNull(c, "value");
            Long actual = OrderRules.readAmount(text, maxDeal);
            report.check(name + " (" + maxDeal + ")", expected == null ? actual == null : expected.equals(actual),
                    "expected " + expected + ", got " + actual);
        }
        report.finish(cases.size());
        // Only the amount reader is run: the amounts the field reader answers (eleven of the twenty-two).
        assertEquals(22 - AGENT_ONLY_AMOUNTS.size(), report.passed);
    }

    @Test
    public void draftsVectors() {
        Report report = new Report("drafts");
        List<JsonObject> cases = Json.objects(intake, "drafts");
        for (JsonObject c : cases) {
            String name = Json.str(c, "name");
            if (!c.has("draft")) {
                report.skip("the agent's rejection of the model's answer (reject:...): no draft, so no card (the launcher skips them)");
                continue;
            }
            OrderDraft.Ctx ctx = contextOf(Json.obj(c, "ctx"));
            JsonObject draft = Json.obj(c, "draft");
            JsonObject before = draft.deepCopy();
            JsonArray questions = array(OrderDraft.questionsOf(draft, ctx));
            JsonObject order = OrderDraft.orderOf(draft, ctx);
            JsonObject expectedOrder = objectOrNull(c, "order");
            boolean ok = Json.arr(c, "questions").equals(questions) && sameOrder(expectedOrder, order) && before.equals(draft);
            report.check(name, ok, "questions expected " + Json.arr(c, "questions") + ", got " + questions + "; order expected "
                    + expectedOrder + ", got " + order + (before.equals(draft) ? "" : "; the draft was changed"));
        }
        report.finish(cases.size());
    }

    @Test
    public void answersVectors() {
        Report report = new Report("answers");
        List<JsonObject> cases = Json.objects(intake, "answers");
        for (JsonObject c : cases) {
            String name = Json.str(c, "name");
            JsonObject from = DRAFT_CASES.get(Json.str(c, "from"));
            assertNotNull(name + ": the draft case " + Json.str(c, "from"), from);
            OrderDraft.Ctx ctx = contextOf(Json.obj(from, "ctx"));
            JsonObject draft = Json.obj(from, "draft");
            JsonObject before = draft.deepCopy();
            OrderDraft.Answer result = OrderDraft.answer(draft, Json.obj(c, "question"), c.get("choice"), ctx, Json.str(c, "newKey"));
            if (c.has("form") || c.has("pick")) {
                OrderDraft.Answer.Kind kind = c.has("form") ? OrderDraft.Answer.Kind.FORM : OrderDraft.Answer.Kind.PICK;
                String field = c.has("form") ? Json.str(c, "form") : Json.str(c, "pick");
                report.check(name, result.kind == kind && field.equals(result.field) && result.draft == null,
                        "expected " + kind + " " + field + ", got " + result);
                continue;
            }
            JsonObject settings = Json.obj(c, "settings");
            boolean same = result.kind == OrderDraft.Answer.Kind.DRAFT && Json.obj(c, "draft").equals(result.draft)
                    && (settings == null ? result.settings == null : settings.equals(result.settings)) && before.equals(draft);
            if (!same) {
                report.check(name, false, "expected draft " + Json.obj(c, "draft") + " settings " + settings + ", got " + result);
                continue;
            }
            // The settings change counts for the questions after it.
            OrderDraft.Ctx after = ctx.withSettings(settings);
            JsonArray questions = array(OrderDraft.questionsOf(result.draft, after));
            JsonObject order = OrderDraft.orderOf(result.draft, after);
            JsonObject expectedOrder = objectOrNull(c, "order");
            report.check(name, Json.arr(c, "questions").equals(questions) && sameOrder(expectedOrder, order),
                    "questions expected " + Json.arr(c, "questions") + ", got " + questions + "; order expected " + expectedOrder
                            + ", got " + order);
        }
        report.finish(cases.size());
    }

    @Test
    public void statusVectors() {
        Report report = new Report("status");
        List<JsonObject> cases = Json.objects(orders, "status");
        for (JsonObject c : cases) {
            JsonObject state = Json.obj(c, "state");
            JsonObject allow = Json.obj(state, "allow");
            long now = state.has("now") ? Json.num(state, "now", 0) : Json.num(orders, "now", 0);
            OrderDraft.State s = new OrderDraft.State(now, Json.num(state, "got", 0), Json.str(state, "live"),
                    Json.bool(allow, "buy", true), Json.bool(allow, "sell", true));
            OrderDraft.Status status = OrderDraft.statusOf(Json.obj(c, "order"), s);
            report.check(Json.str(c, "name"), status.wire().equals(Json.str(c, "status")),
                    "expected " + Json.str(c, "status") + ", got " + status.wire());
        }
        report.finish(cases.size());
        // The statuses in the order they win.
        List<String> wires = new ArrayList<>();
        for (OrderDraft.Status status : OrderDraft.Status.values()) wires.add(status.wire());
        assertEquals(Json.strings(orders, "statuses"), wires);
        assertTrue(OrderDraft.isFinished(OrderDraft.Status.DONE));
        assertTrue(OrderDraft.isFinished(OrderDraft.Status.EXPIRED));
        assertFalse(OrderDraft.isFinished(OrderDraft.Status.PAUSED));
        assertEquals(OrderDraft.Status.SIDE_OFF, OrderDraft.Status.fromWire("side_off"));
        assertNull(OrderDraft.Status.fromWire("nope"));
    }

    // ---- what the launcher's own tests also check ----

    @Test
    public void namesTheReasonAndTheRuleForTheHint() {
        OrderRules.Hint prepay = OrderRules.ruleHint(OrderRules.checkOrderText("önce ben öderim elytra 40k"));
        assertEquals("loosen", prepay.reason);
        assertEquals("prepay", prepay.rule);
        assertNull(OrderRules.ruleHint(OrderRules.checkOrderText("elytra alırım 40k")));
        OrderRules.Hint length = OrderRules.ruleHint(OrderRules.checkOrderText("ab"));
        assertEquals("length", length.reason);
        assertNull(length.rule);
    }

    @Test
    public void readsAnAmountTypedInAField() {
        assertEquals(Long.valueOf(40_000), OrderRules.readAmount("40 bin"));
        assertEquals(Long.valueOf(40_000), OrderRules.readAmount("40k"));
        assertEquals(Long.valueOf(40_000), OrderRules.readAmount("40.000"));
        assertEquals(Long.valueOf(1_500_000), OrderRules.readAmount("1,5m"));
        assertNull(OrderRules.readAmount("1.5"));
        assertNull(OrderRules.readAmount("40 tl"));
        assertNull(OrderRules.readAmount("0"));
        assertNull(OrderRules.readAmount("12 13"));
        assertNull(OrderRules.readAmount("200m", 10_000_000L));
    }

    // ---- what the vectors do not say ----

    @Test
    public void aChipWorksOnACopyAndKeepsWhatItDoesNotKnow() {
        OrderDraft.Ctx ctx = ctxOf("a target only: the limit's chips");
        JsonObject draft = draftOf("a target only: the limit's chips").deepCopy();
        draft.add("seen", Json.parse("{\"by\":[1,2],\"fp\":null}"));
        JsonObject before = draft.deepCopy();
        JsonObject question = OrderDraft.questionsOf(draft, ctx).get(0);
        OrderDraft.Answer result = OrderDraft.answer(draft, question, OrderDraft.Choice.amount(19_000), ctx);
        assertEquals("the draft given is left alone", before, draft);
        assertEquals(OrderDraft.Answer.Kind.DRAFT, result.kind);
        assertEquals(before.get("seen"), result.draft.get("seen"));
        assertEquals(19_000, Json.num(result.draft, "limit", 0));
        JsonObject order = OrderDraft.orderOf(result.draft, ctx);
        assertNotNull(order);
        assertFalse("an order has no unknown fields", order.has("seen"));
        OrderDraft.fill(draft, ctx);
        assertEquals("fill keeps what it does not know", before.get("seen"), draft.get("seen"));
    }

    @Test
    public void withoutItemDataTheCardStillAsksWhatItCan() {
        OrderDraft.Ctx none = ctxOf("an exact name wins over a longer one").data(null);
        JsonObject draft = draftOf("an exact name wins over a longer one");
        // The stack size is unknown: no quantity question (the vectors ask [1, 32, 64] with the data).
        assertEquals(1, OrderDraft.questionsOf(draft, none).size());
        assertEquals("limit", Json.str(OrderDraft.questionsOf(draft, none).get(0), "kind"));
        // The durability stays as the draft has it, 90 when none; the conditions are not filtered.
        JsonObject card = draftOf("the card's own example");
        JsonObject order = OrderDraft.orderOf(card, ctxOf("the card's own example").data(null));
        assertEquals(objectOrNull(DRAFT_CASES.get("the card's own example"), "order"), order);
        assertNull(OrderDraft.receivedWears("minecraft:elytra", none));
        assertEquals(Boolean.TRUE, OrderDraft.receivedWears("minecraft:elytra", ctxOf("the card's own example")));
        assertEquals(Boolean.FALSE, OrderDraft.receivedWears("minecraft:diamond", ctxOf("the card's own example")));
        assertNull(OrderDraft.receivedWears("minecraft:not_an_item", ctxOf("the card's own example")));
        JsonObject ench = Json.obj(Json.obj(draftOf("enchantments: fitting kept, excluded, curses and too high dropped"), "req"), "ench");
        // Without the data (or for a custom item) the conditions stay as they are: the very same object.
        assertTrue(OrderDraft.fittingEnch(ench, "minecraft:diamond_sword", null) == ench);
        assertTrue(OrderDraft.fittingEnch(ench, "custom:c7k2", data) == ench);
        assertTrue(OrderDraft.fittingEnch(ench, null, data) == ench);
        assertTrue(OrderDraft.fittingEnch(ench, "minecraft:not_an_item", data) == ench);
    }

    @Test
    public void fittingEnchantmentsAreKeptWithinTheirMost() {
        JsonObject ench = Json.parse("{\"minecraft:sharpness\":9,\"minecraft:smite\":3,\"minecraft:unbreaking\":2,"
                + "\"minecraft:mending\":1,\"minecraft:vanishing_curse\":1,\"minecraft:efficiency\":4,"
                + "\"minecraft:not_there\":1}").getAsJsonObject();
        JsonObject kept = OrderDraft.fittingEnch(ench, "minecraft:diamond_sword", data);
        // Sharpness stays (to its most), smite is excluded by it, a curse, what is not in the sword's group and what the data
        // lacks are dropped.
        assertEquals(Json.parse("{\"minecraft:sharpness\":5,\"minecraft:unbreaking\":2,\"minecraft:mending\":1}"), kept);
        // Who comes first stays: smite before sharpness.
        JsonObject other = Json.parse("{\"minecraft:smite\":3,\"minecraft:sharpness\":4}").getAsJsonObject();
        assertEquals(Json.parse("{\"minecraft:smite\":3}"), OrderDraft.fittingEnch(other, "minecraft:diamond_sword", data));
        // An enchanted book takes any enchantment that is no curse.
        JsonObject book = OrderDraft.fittingEnch(ench, "minecraft:enchanted_book", data);
        assertEquals(Json.parse("{\"minecraft:sharpness\":5,\"minecraft:unbreaking\":2,\"minecraft:mending\":1,"
                + "\"minecraft:efficiency\":4}"), book);
    }

    @Test
    public void aCustomNameIsCutAtAWordOrAtThirtyTwoCodePoints() {
        assertEquals("Yakut Tasi", OrderDraft.cutName("Yakut\nTasi"));
        assertEquals("  Yakut  ".trim(), OrderDraft.cutName("  Yakut  "));
        String exactly32 = "abcdefgh abcdefgh abcdefgh abcdefgh".substring(0, 32);
        assertEquals(exactly32.trim(), OrderDraft.cutName(exactly32));
        assertEquals("abcdefghijklmnopqrstuvwxyzabcdef", OrderDraft.cutName("abcdefghijklmnopqrstuvwxyzabcdefghij"));
        // A word that ends right at the cut stays whole; one that runs past it goes.
        assertEquals("abcdefghijklmnopqrstuvwxyzabcdef", OrderDraft.cutName("abcdefghijklmnopqrstuvwxyzabcdef ghij"));
        assertEquals("abcd efgh", OrderDraft.cutName("abcd efgh ijklmnopqrstuvwxyzabcdefghijkl mnopq"));
        String emoji = new String(Character.toChars(0x1F600));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 40; i++) many.append(emoji);
        String cut = OrderDraft.cutName(many.toString());
        assertEquals(32, cut.codePointCount(0, cut.length()));
    }

    @Test
    public void settingsChangeTheContextForTheQuestionsAfterIt() {
        OrderDraft.Ctx ctx = ctxOf("over the single-buy cap");
        assertEquals(Long.valueOf(50_000), ctx.perDealMax());
        JsonObject settings = Json.parse("{\"limits\":{\"perDealMax\":80000}}").getAsJsonObject();
        OrderDraft.Ctx after = ctx.withSettings(settings);
        assertEquals(Long.valueOf(80_000), after.perDealMax());
        assertEquals(ctx.totalBudget(), after.totalBudget());
        assertEquals(Long.valueOf(50_000), ctx.perDealMax());
        assertEquals("both", ctx.withSettings(Json.parse("{\"pay\":\"both\"}").getAsJsonObject()).pay());
        assertEquals("money", ctx.pay());
    }

    @Test
    public void theSourceKeepsToTheAppsRules() throws Exception {
        // Android: no Turkish letters in the literals of the app's code, and the new files stay plain ASCII.
        for (String name : new String[] {"OrderDraft.java", "OrderRules.java"}) {
            java.io.File file = new java.io.File("src/main/java/gg/projects/mobile/cloud/plus/" + name);
            if (!file.isFile()) file = new java.io.File("app_pojavlauncher/src/main/java/gg/projects/mobile/cloud/plus/" + name);
            if (!file.isFile()) continue;
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            for (byte b : bytes) assertTrue(name + " holds a character outside ASCII", b >= 0);
            for (String line : new String(bytes, "UTF-8").split("\n")) assertTrue(name + " line over 140: " + line, line.length() <= 140);
        }
    }
}
