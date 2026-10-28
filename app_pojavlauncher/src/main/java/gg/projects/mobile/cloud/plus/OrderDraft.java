package gg.projects.mobile.cloud.plus;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import gg.projects.mobile.social.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AFK+ order cards: what an order's draft still asks, a chip's effect on it, the order it saves as, and an order's status
 * (projects-studio services/cloud-afk/README.md "AFK+ trade orders": The card, Status and progress). The service reads the
 * player's words once ("Kontrol et") and answers with a draft; the questions are answered here, without a second model call.
 * A port of the launcher's order-draft.ts, which ports the contract's reference (test-vectors/afkplus-intake.mjs
 * {@code questionsOf}, {@code answer}, {@code orderOf}, {@code fill}; afkplus-order.mjs {@code statusOf}); the vectors'
 * {@code drafts}, {@code answers} and {@code status} run in PlusDraftTest. Pure Java.
 *
 * <p>Drafts, questions and orders are Gson {@link JsonObject}s in the contract's exact shapes, so a draft the service sent is
 * changed field by field and nothing unknown is lost or invented:
 * <ul>
 * <li>draft: {@code {side, item, qty, pay, unit, target, limit, style, req, trade, until, note, src, amounts, open, ok,
 * customItems?}}, {@code null} where a question is open ({@code open}: {@code {item?, pay?}}, each {@code {options}},
 * {@code {mention}} or {@code {either}});</li>
 * <li>question: {@code {kind: "which" | "side" | "custom" | "limit" | "pay" | "pay_off" | "per" | "qty" | "far" | "over_cap",
 * ...}} (the README's table);</li>
 * <li>order (what a finished draft saves as, without {@code id} and {@code at}): {@code {on, side, item, qty, req, pay, unit,
 * target, limit, style, trade, until, note, src}}.</li>
 * </ul>
 * Item references are {@code minecraft:...} ids or {@code custom:<k>}. Amounts are whole numbers (money or a paying item's
 * count). A draft is changed in place only by {@link #fill}; {@link #answer} works on a copy.
 */
public final class OrderDraft {
    private OrderDraft() {}

    /** The most a count (of a paying item) may be. */
    public static final int MAX_COUNT = 9_999;
    /** The longest custom item name, in code points. */
    public static final int CUSTOM_NAME_MAX = 32;
    /** The card shows this many questions at a time, the first ones. */
    public static final int SHOWN_QUESTIONS = 2;

    private static final String CUSTOM = "custom:";
    private static final String[] FIELDS = {"item", "pay"};

    // ---- the context ----

    /**
     * What the questions are asked against: the account's settings, its server and its item data (the TS
     * {@code DraftContext}). Build it with the setters, then treat it as read-only; {@link #withSettings} makes a changed copy.
     */
    public static final class Ctx {
        @Nullable private ItemFacts data;
        private List<JsonObject> custom = Collections.emptyList();
        private long maxDeal = OrderRules.GENERIC_MAX_DEAL;
        @Nullable private long[][] round;
        private String pay = "money";
        private boolean itemPay = true;
        private boolean customItems = true;
        @Nullable private Long perDealMax;
        @Nullable private Long totalBudget;
        private String trades = "bot";

        /** The account version's item data; null while it could not be loaded. */
        public Ctx data(@Nullable ItemFacts value) {
            data = value;
            return this;
        }

        /** The account's custom items, as the config holds them ({@code {k, name, base, fp, look, from, at}}). */
        public Ctx custom(@Nullable List<JsonObject> value) {
            custom = value == null ? Collections.<JsonObject>emptyList() : value;
            return this;
        }

        /**
         * The money's cap ({@code money.maxDeal}) and rounding steps ({@code money.round}: pairs {@code {from, step}}, the
         * largest {@code from} first; null: two significant digits).
         */
        public Ctx money(long maxDealValue, @Nullable long[][] roundSteps) {
            maxDeal = maxDealValue;
            round = roundSteps;
            return this;
        }

        /** {@link #money(long, long[][])} with the steps as the profile's JSON ({@code [[10000, 500], [0, 50]]}). */
        public Ctx money(long maxDealValue, @Nullable JsonArray roundSteps) {
            long[][] steps = null;
            if (roundSteps != null) {
                List<long[]> pairs = new ArrayList<>();
                for (JsonElement pair : roundSteps) {
                    if (pair.isJsonArray() && pair.getAsJsonArray().size() >= 2) {
                        JsonArray p = pair.getAsJsonArray();
                        pairs.add(new long[] {p.get(0).getAsLong(), p.get(1).getAsLong()});
                    }
                }
                steps = pairs.toArray(new long[0][]);
            }
            return money(maxDealValue, steps);
        }

        /** The account's payment setting: {@code money}, {@code items} or {@code both} (default {@code money}). */
        public Ctx pay(String value) {
            pay = value;
            return this;
        }

        /** Studio's features: paying with items, custom items (both default on). */
        public Ctx features(boolean itemPayOn, boolean customItemsOn) {
            itemPay = itemPayOn;
            customItems = customItemsOn;
            return this;
        }

        /** The account's limits: the single-buy cap and the total budget (null: none). */
        public Ctx limits(@Nullable Long perDealMaxValue, @Nullable Long totalBudgetValue) {
            perDealMax = perDealMaxValue;
            totalBudget = totalBudgetValue;
            return this;
        }

        /** Who makes the account's trades ({@code GET /plus} -> {@code server.trade}): {@code bot} (default) or {@code owner}. */
        public Ctx trades(String value) {
            trades = value;
            return this;
        }

        /** The item data, or null. */
        @Nullable
        public ItemFacts data() {
            return data;
        }

        /** The account's custom items. */
        public List<JsonObject> custom() {
            return custom;
        }

        /** The money's cap. */
        public long maxDeal() {
            return maxDeal;
        }

        /** The money's rounding steps ({@code {from, step}}), or null for two significant digits. */
        @Nullable
        public long[][] round() {
            return round;
        }

        /** The payment setting: {@code money}, {@code items} or {@code both}. */
        public String pay() {
            return pay;
        }

        /** Whether Studio allows paying with items. */
        public boolean itemPay() {
            return itemPay;
        }

        /** Whether Studio allows custom items. */
        public boolean customItems() {
            return customItems;
        }

        /** The single-buy cap, or null. */
        @Nullable
        public Long perDealMax() {
            return perDealMax;
        }

        /** The total budget, or null. */
        @Nullable
        public Long totalBudget() {
            return totalBudget;
        }

        /** Who makes the trades: {@code bot} or {@code owner}. */
        public String trades() {
            return trades;
        }

        /** A copy (the data, the custom items and the steps are shared: they are read-only). */
        public Ctx copy() {
            Ctx next = new Ctx();
            next.data = data;
            next.custom = custom;
            next.maxDeal = maxDeal;
            next.round = round;
            next.pay = pay;
            next.itemPay = itemPay;
            next.customItems = customItems;
            next.perDealMax = perDealMax;
            next.totalBudget = totalBudget;
            next.trades = trades;
            return next;
        }

        /**
         * A copy with a chip's settings change applied ({@code {pay: "both"}}, {@code {limits: {perDealMax}}}): the change is
         * saved with the order and counts for the questions after it.
         */
        public Ctx withSettings(@Nullable JsonObject settings) {
            Ctx next = copy();
            if (settings == null) return next;
            String payValue = Json.str(settings, "pay");
            if (payValue != null) next.pay = payValue;
            JsonObject limits = Json.obj(settings, "limits");
            if (limits != null) {
                if (limits.has("perDealMax")) next.perDealMax = Json.numOrNull(limits, "perDealMax");
                if (limits.has("totalBudget")) next.totalBudget = Json.numOrNull(limits, "totalBudget");
            }
            return next;
        }
    }

    // ---- small JSON helpers ----

    /** Absent or JSON null: how a draft says "open". */
    private static boolean isNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull();
    }

    @Nullable
    private static String str(@Nullable JsonObject o, String key) {
        return Json.str(o, key);
    }

    @Nullable
    private static Long num(@Nullable JsonObject o, String key) {
        return Json.numOrNull(o, key);
    }

    private static void setNull(JsonObject o, String key) {
        o.add(key, JsonNull.INSTANCE);
    }

    private static void setString(JsonObject o, String key, @Nullable String value) {
        o.add(key, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /** A number as the JSON keeps it: a whole one as such. */
    private static JsonPrimitive numberOf(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 9.0e15) return new JsonPrimitive((long) value);
        return new JsonPrimitive(value);
    }

    private static JsonObject question(String kind) {
        JsonObject q = new JsonObject();
        q.addProperty("kind", kind);
        return q;
    }

    private static JsonArray strings(String... values) {
        JsonArray out = new JsonArray();
        for (String value : values) out.add(new JsonPrimitive(value));
        return out;
    }

    private static boolean isField(@Nullable String field) {
        return "item".equals(field) || "pay".equals(field);
    }

    /** JavaScript's trim: white space and line terminators, not only what is at or below the space. */
    private static String jsTrim(String text) {
        int from = 0;
        int to = text.length();
        while (from < to && jsSpace(text.charAt(from))) from++;
        while (to > from && jsSpace(text.charAt(to - 1))) to--;
        return text.substring(from, to);
    }

    private static boolean jsSpace(char c) {
        return c == ' ' || (c >= 0x09 && c <= 0x0D) || c == 0x00A0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    // ---- item facts ----

    /** What the card needs of an item reference. */
    private static final class Facts {
        final int stack;
        final int maxDamage;
        /** The enchantments that fit, by id; null for a custom item (its data is the server's). */
        @Nullable final List<String> ench;

        Facts(int stack, int maxDamage, @Nullable List<String> ench) {
            this.stack = stack;
            this.maxDamage = maxDamage;
            this.ench = ench;
        }
    }

    @Nullable
    private static Facts itemFacts(@Nullable ItemFacts data, @Nullable String id) {
        if (data == null || id == null) return null;
        ItemFacts.Item item = data.item(id);
        if (item == null) return null;
        return new Facts(item.stack, item.maxDamage, item.enchantments);
    }

    @Nullable
    private static JsonObject findCustom(Ctx ctx, String key) {
        for (JsonObject item : ctx.custom) {
            if (key.equals(str(item, "k"))) return item;
        }
        return null;
    }

    /** The facts of an item reference: vanilla ones from the data, custom ones from their {@code base}. */
    @Nullable
    private static Facts refFacts(@Nullable String ref, Ctx ctx) {
        if (ref == null) return null;
        if (ref.startsWith(CUSTOM)) {
            JsonObject custom = findCustom(ctx, ref.substring(CUSTOM.length()));
            String baseId = custom == null ? null : str(custom, "base");
            Facts base = baseId != null && !baseId.isEmpty() ? itemFacts(ctx.data, baseId) : null;
            return new Facts(base == null ? 64 : base.stack, base == null ? 0 : base.maxDamage, null);
        }
        return itemFacts(ctx.data, ref);
    }

    // ---- amounts ----

    /** 10 to the power {@code n} (n of a few digits), exactly. */
    private static double pow10(int n) {
        double out = 1;
        for (int i = 0; i < n; i++) out *= 10;
        return out;
    }

    /** The step of 2 significant digits (1 below 100). The same log10 as the browsers' (fdlibm). */
    private static double significantStep(double value) {
        double digits = Math.floor(StrictMath.log10(Math.max(1, value)));
        return digits < 2 ? 1 : pow10((int) digits - 1);
    }

    /** The step to round {@code value} to: the profile's {@code money.round}, else 2 significant digits. */
    private static double stepOf(double value, @Nullable long[][] round) {
        if (round == null) return significantStep(value);
        for (long[] pair : round) {
            if (value >= pair[0]) return pair[1];
        }
        return 1;
    }

    /**
     * The target a limit gives: buying 80 % of it rounded down, selling 120 % rounded up, by the profile's rounding; counts of
     * a paying item to whole items. Never 0, never past {@code maxDeal} (for money).
     */
    public static long targetFromLimit(String side, long limit, Ctx ctx, boolean counts) {
        boolean buy = "buy".equals(side);
        if (counts) {
            return buy ? (long) Math.max(1, Math.floor(limit * 0.8)) : (long) Math.min(MAX_COUNT, Math.ceil(limit * 1.2));
        }
        if (buy) {
            double raw = Math.floor(limit * 0.8);
            double step = stepOf(raw, ctx.round);
            double rounded = Math.floor(raw / step) * step;
            return rounded >= 1 ? (long) rounded : (long) Math.max(1, raw);
        }
        double raw = Math.ceil(limit * 1.2);
        double step = stepOf(raw, ctx.round);
        return (long) Math.min(ctx.maxDeal, Math.ceil(raw / step) * step);
    }

    /** A chip amount: to 2 significant digits, the nearer way (counts below 100 stay whole). */
    private static long chipRound(double value) {
        double step = significantStep(value);
        return (long) Math.max(1, Math.round(value / step) * step);
    }

    /** A custom item's name from what the player wrote: at most 32 code points, cut at a word. */
    public static String cutName(String text) {
        String flat = text.replace('\n', ' ');
        if (flat.codePointCount(0, flat.length()) <= CUSTOM_NAME_MAX) return jsTrim(flat);
        int cutAt = flat.offsetByCodePoints(0, CUSTOM_NAME_MAX);
        String cut = flat.substring(0, cutAt);
        int space = cut.lastIndexOf(' ');
        if (flat.charAt(cutAt) != ' ' && space > 0) cut = cut.substring(0, space);
        return jsTrim(cut);
    }

    // ---- the draft ----

    /** The item whose conditions {@code req} holds: the item when buying, the paying item when selling; null for none. */
    @Nullable
    public static String receivedOf(JsonObject draft) {
        String side = str(draft, "side");
        if ("buy".equals(side)) return str(draft, "item");
        String pay = str(draft, "pay");
        if ("sell".equals(side) && pay != null && !pay.equals("money")) return pay;
        return null;
    }

    /**
     * The defaults a draft's known fields give (run after every change, in place; returns the draft): one of an item that
     * does not stack; {@code total} for one item and for every buy; the target from the limit; conditions that fit what the
     * bot receives (none when selling for money).
     */
    public static JsonObject fill(JsonObject draft, Ctx ctx) {
        Facts facts = refFacts(str(draft, "item"), ctx);
        String side = str(draft, "side");
        if (isNull(draft, "qty") && facts != null && facts.stack == 1) draft.addProperty("qty", 1);
        Long qty = num(draft, "qty");
        if (isNull(draft, "unit") && ((qty != null && qty == 1) || "buy".equals(side))) draft.addProperty("unit", "total");
        Long limit = num(draft, "limit");
        if (isNull(draft, "target") && limit != null && side != null) {
            draft.addProperty("target", targetFromLimit(side, limit, ctx, "count".equals(str(draft, "amounts"))));
        }
        JsonObject req = Json.obj(draft, "req");
        if (req != null) {
            String received = receivedOf(draft);
            if ("sell".equals(side) && "money".equals(str(draft, "pay"))) {
                setNull(draft, "req");
            } else if (received != null) {
                JsonObject ench = Json.obj(req, "ench");
                if (ench != null) req.add("ench", fittingEnch(ench, received, ctx.data));
            }
        }
        return draft;
    }

    /**
     * The enchantments that fit the item the bot receives: its group, no curse, none excluding one kept before it, each within
     * its most. A custom item's (the server's data) and any without the item data stay as they are (the same object).
     */
    public static JsonObject fittingEnch(JsonObject ench, @Nullable String received, @Nullable ItemFacts data) {
        if (received == null || received.startsWith(CUSTOM) || data == null) return ench;
        Facts facts = itemFacts(data, received);
        if (facts == null || facts.ench == null) return ench;
        JsonObject kept = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : ench.entrySet()) {
            String id = entry.getKey();
            ItemFacts.Enchantment info = data.enchantment(id);
            if (info == null || info.curse || !facts.ench.contains(id)) continue;
            boolean excluded = false;
            for (Map.Entry<String, JsonElement> other : kept.entrySet()) {
                if (info.excludes.contains(other.getKey())) excluded = true;
            }
            if (excluded) continue;
            JsonElement level = entry.getValue();
            boolean number = level.isJsonPrimitive() && level.getAsJsonPrimitive().isNumber();
            kept.add(id, number ? numberOf(Math.min(level.getAsDouble(), info.maxLevel)) : new JsonPrimitive(info.maxLevel));
        }
        return kept;
    }

    /**
     * Whether the item the bot receives wears out (a custom item by its material); null while that cannot be told (no item
     * data, an item it lacks, a custom item without a known material).
     */
    @Nullable
    public static Boolean receivedWears(@Nullable String ref, Ctx ctx) {
        if (ref == null || ctx.data == null) return null;
        String id = ref;
        if (ref.startsWith(CUSTOM)) {
            JsonObject custom = findCustom(ctx, ref.substring(CUSTOM.length()));
            id = custom == null ? null : str(custom, "base");
        }
        ItemFacts.Item item = id == null ? null : ctx.data.item(id);
        return item == null ? null : item.maxDamage > 0;
    }

    private static boolean ownerTrades(JsonObject draft, Ctx ctx) {
        return "owner".equals(ctx.trades) || "owner".equals(str(draft, "trade"));
    }

    private static JsonArray customChips(String side, String field, Ctx ctx) {
        if (!ctx.customItems) return strings("fix");
        boolean receives = ("item".equals(field) && "buy".equals(side)) || ("pay".equals(field) && "sell".equals(side));
        return receives ? strings("pick", "first_seen", "fix") : strings("pick", "fix");
    }

    private static JsonArray limitChips(JsonObject draft, Ctx ctx, boolean counts) {
        JsonArray out = new JsonArray();
        Long target = num(draft, "target");
        String side = str(draft, "side");
        if (target == null || side == null) return out;
        boolean buy = "buy".equals(side);
        double[] factors = buy ? new double[] {1.1, 1.25, 1.5} : new double[] {0.9, 0.8, 0.7};
        double top = counts ? MAX_COUNT : ctx.maxDeal;
        List<Long> seen = new ArrayList<>();
        for (double factor : factors) {
            double scaled = target * factor;
            long value = counts ? Math.round(scaled) : chipRound(scaled);
            boolean fits = buy ? value > target : value < target;
            if (fits && value >= 1 && value <= top && !seen.contains(value)) {
                seen.add(value);
                out.add(new JsonPrimitive(value));
            }
        }
        return out;
    }

    /** The questions a draft still has, in order (each a JSON object, the README's shapes); the card shows the first two. */
    public static List<JsonObject> questionsOf(JsonObject draft, Ctx ctx) {
        List<JsonObject> out = new ArrayList<>();
        JsonObject open = Json.obj(draft, "open");
        String side = str(draft, "side");
        for (String field : FIELDS) {
            JsonObject entry = Json.obj(open, field);
            if (isNull(draft, field) && entry != null && entry.has("options")) {
                JsonObject q = question("which");
                q.addProperty("field", field);
                q.add("options", entry.get("options").deepCopy());
                out.add(q);
            }
        }
        if (side == null) out.add(question("side"));
        if (side != null) {
            for (String field : FIELDS) {
                JsonObject entry = Json.obj(open, field);
                if (isNull(draft, field) && entry != null && entry.has("mention")) {
                    JsonObject q = question("custom");
                    q.addProperty("field", field);
                    q.add("mention", entry.get("mention").deepCopy());
                    q.add("chips", customChips(side, field, ctx));
                    out.add(q);
                }
            }
        }
        Long limit = num(draft, "limit");
        if (isNull(draft, "limit") && side != null) {
            JsonObject q = question("limit");
            q.addProperty("side", side);
            q.add("amounts", limitChips(draft, ctx, "count".equals(str(draft, "amounts"))));
            out.add(q);
        }
        JsonObject payOpen = Json.obj(open, "pay");
        JsonElement either = payOpen != null ? payOpen.get("either") : null;
        if (isNull(draft, "pay") && either != null) {
            JsonObject q = question("pay");
            q.add("item", either.deepCopy());
            out.add(q);
        }
        String pay = str(draft, "pay");
        if (pay != null && !pay.equals("money") && ctx.pay.equals("money")) {
            JsonObject q = question("pay_off");
            q.addProperty("want", "item");
            out.add(q);
        } else if ("money".equals(pay) && ctx.pay.equals("items")) {
            JsonObject q = question("pay_off");
            q.addProperty("want", "money");
            out.add(q);
        }
        Long target = num(draft, "target");
        Long price = target != null ? target : limit;
        Long qty = num(draft, "qty");
        if ("sell".equals(side) && qty != null && qty > 1 && isNull(draft, "unit") && price != null) {
            JsonObject q = question("per");
            q.addProperty("price", price);
            out.add(q);
        }
        Facts facts = refFacts(str(draft, "item"), ctx);
        if (isNull(draft, "qty") && facts != null && facts.stack > 1) {
            JsonArray amounts = new JsonArray();
            amounts.add(new JsonPrimitive(1));
            if (facts.stack / 2 != 1) amounts.add(new JsonPrimitive(facts.stack / 2));
            amounts.add(new JsonPrimitive(facts.stack));
            JsonObject q = question("qty");
            q.add("amounts", amounts);
            out.add(q);
        }
        if ("buy".equals(side) && target != null && limit != null && limit > 10 * target
                && !Json.strings(draft, "ok").contains("far")) {
            JsonObject q = question("far");
            q.addProperty("limit", limit);
            q.addProperty("target", target);
            out.add(q);
        }
        Long cap = ctx.perDealMax;
        if ("buy".equals(side) && "money".equals(pay) && cap != null && limit != null && limit > cap && !ownerTrades(draft, ctx)) {
            JsonObject q = question("over_cap");
            q.addProperty("limit", limit);
            q.addProperty("cap", cap);
            out.add(q);
        }
        return out;
    }

    // ---- the chips ----

    /** A chip's choice by the question's kind: build it with {@link #of}, {@link #option} or {@link #amount}. */
    public static final class Choice {
        private Choice() {}

        /**
         * A choice named by a word: {@code buy} or {@code sell} (side), {@code first_seen}, {@code pick} or {@code fix}
         * (custom), {@code money} or {@code item} (pay), {@code turn_on} or {@code switch} (pay_off), {@code each} or
         * {@code total} (per), {@code yes} or {@code fix} (far), {@code raise} or {@code use_cap} (over_cap).
         */
        public static JsonElement of(String word) {
            return new JsonPrimitive(word);
        }

        /** The option chosen for a {@code which} question (an item reference). */
        public static JsonElement option(String itemRef) {
            JsonObject choice = new JsonObject();
            choice.addProperty("option", itemRef);
            return choice;
        }

        /** The amount chosen for a {@code limit} or {@code qty} question. */
        public static JsonElement amount(long amount) {
            JsonObject choice = new JsonObject();
            choice.addProperty("amount", amount);
            return choice;
        }
    }

    /** What a chip did: the new draft, the form at a field, or the inventory picker. */
    public static final class Answer {
        /** {@code DRAFT}: a new draft; {@code FORM}: the form opens at {@link #field}; {@code PICK}: the inventory picker. */
        public enum Kind { DRAFT, FORM, PICK }

        public final Kind kind;
        /** DRAFT: the new draft, filled (a copy: the draft the chip was given stays as it was); else null. */
        @Nullable public final JsonObject draft;
        /** DRAFT: a settings change saved with the order in the same write ({@code {pay: "both"}} or {@code {limits:
         *  {perDealMax}}}), which counts for the questions after it ({@link Ctx#withSettings}); else null. */
        @Nullable public final JsonObject settings;
        /** FORM: {@code item}, {@code pay} or {@code limit}; PICK: {@code item} or {@code pay}; else null. */
        @Nullable public final String field;

        private Answer(Kind kind, @Nullable JsonObject draft, @Nullable JsonObject settings, @Nullable String field) {
            this.kind = kind;
            this.draft = draft;
            this.settings = settings;
            this.field = field;
        }

        @Override
        public String toString() {
            return kind == Kind.DRAFT ? "draft " + draft + (settings == null ? "" : " settings " + settings) : kind + " " + field;
        }
    }

    private static boolean isWord(JsonElement choice, String word) {
        return choice.isJsonPrimitive() && choice.getAsJsonPrimitive().isString() && word.equals(choice.getAsString());
    }

    @Nullable
    private static String wordOf(JsonElement choice) {
        return choice.isJsonPrimitive() && choice.getAsJsonPrimitive().isString() ? choice.getAsString() : null;
    }

    @Nullable
    private static String optionOf(JsonElement choice) {
        return choice.isJsonObject() ? str(choice.getAsJsonObject(), "option") : null;
    }

    @Nullable
    private static Long amountOf(JsonElement choice) {
        return choice.isJsonObject() ? num(choice.getAsJsonObject(), "amount") : null;
    }

    private static void closeOpen(JsonObject draft, String field) {
        JsonObject open = Json.obj(draft, "open");
        if (open != null) open.remove(field);
    }

    /** {@link #answer(JsonObject, JsonObject, JsonElement, Ctx, String)} with the key {@code c1} for a new custom item. */
    public static Answer answer(JsonObject draft, JsonObject question, JsonElement choice, Ctx ctx) {
        return answer(draft, question, choice, ctx, null);
    }

    /**
     * A chip's effect: the new draft (and a settings change saved with the order in the same write), or the form at a field,
     * or the inventory picker. The draft given stays as it was. {@code newKey}: the key for a new custom item (default
     * {@code c1}).
     */
    public static Answer answer(JsonObject draft, JsonObject question, @Nullable JsonElement chosen, Ctx ctx,
            @Nullable String newKey) {
        JsonElement choice = chosen == null ? JsonNull.INSTANCE : chosen;
        JsonObject next = draft.deepCopy();
        JsonObject settings = null;
        String kind = str(question, "kind");
        String field = str(question, "field");
        switch (kind == null ? "" : kind) {
            case "which": {
                String option = optionOf(choice);
                if (isField(field) && option != null) {
                    next.addProperty(field, option);
                    closeOpen(next, field);
                }
                break;
            }
            case "side": {
                String side = wordOf(choice);
                if (side != null) next.addProperty("side", side);
                break;
            }
            case "custom": {
                if (isWord(choice, "fix")) return new Answer(Answer.Kind.FORM, null, null, field);
                if (isWord(choice, "pick")) return new Answer(Answer.Kind.PICK, null, null, field);
                if (!isField(field)) break;
                String key = newKey == null ? "c1" : newKey;
                next.addProperty(field, CUSTOM + key);
                closeOpen(next, field);
                JsonElement known = next.get("customItems");
                JsonArray items = known != null && known.isJsonArray() ? known.getAsJsonArray() : new JsonArray();
                JsonObject item = new JsonObject();
                item.addProperty("k", key);
                item.addProperty("name", cutName(Json.str(question, "mention", "")));
                item.add("base", JsonNull.INSTANCE);
                item.add("fp", JsonNull.INSTANCE);
                item.add("look", JsonNull.INSTANCE);
                item.addProperty("from", "text");
                items.add(item);
                next.add("customItems", items);
                break;
            }
            case "limit": {
                Long amount = amountOf(choice);
                if (amount == null) break;
                next.addProperty("limit", amount);
                Long target = num(next, "target");
                String side = str(next, "side");
                if (target != null && (("buy".equals(side) && target > amount) || ("sell".equals(side) && target < amount))) {
                    setNull(next, "target");
                }
                break;
            }
            case "pay":
                setString(next, "pay", isWord(choice, "money") ? "money" : str(question, "item"));
                closeOpen(next, "pay");
                break;
            case "pay_off":
                if (isWord(choice, "turn_on")) {
                    settings = new JsonObject();
                    settings.addProperty("pay", "both");
                } else if ("item".equals(str(question, "want"))) {
                    next.addProperty("pay", "money");
                } else {
                    return new Answer(Answer.Kind.FORM, null, null, "pay");
                }
                break;
            case "per": {
                String unit = wordOf(choice);
                if (unit != null) next.addProperty("unit", unit);
                break;
            }
            case "qty": {
                Long amount = amountOf(choice);
                if (amount != null) next.addProperty("qty", amount);
                break;
            }
            case "far": {
                if (isWord(choice, "fix")) return new Answer(Answer.Kind.FORM, null, null, "limit");
                JsonElement known = next.get("ok");
                JsonArray ok = known != null && known.isJsonArray() ? known.getAsJsonArray() : new JsonArray();
                ok.add(new JsonPrimitive("far"));
                next.add("ok", ok);
                break;
            }
            case "over_cap":
                if (isWord(choice, "raise")) {
                    Long limit = num(question, "limit");
                    if (limit != null) {
                        JsonObject limits = new JsonObject();
                        limits.addProperty("perDealMax", limit);
                        settings = new JsonObject();
                        settings.add("limits", limits);
                    }
                } else {
                    Long cap = num(question, "cap");
                    if (cap != null) {
                        next.addProperty("limit", cap);
                        Long target = num(next, "target");
                        if (target != null && target > cap) next.addProperty("target", cap);
                    }
                }
                break;
            default:
                break;
        }
        String pay = str(next, "pay");
        if (pay != null) {
            String amounts = pay.equals("money") ? "money" : "count";
            if (!amounts.equals(str(next, "amounts"))) {
                // Money and a paying item's count are different units: the amounts start again.
                setNull(next, "target");
                setNull(next, "limit");
                next.addProperty("amounts", amounts);
            }
        }
        fill(next, settings == null ? ctx : ctx.withSettings(settings));
        return new Answer(Answer.Kind.DRAFT, next, settings, null);
    }

    // ---- the order ----

    /**
     * The order a finished draft saves as (without {@code id} and {@code at}), or null while questions remain.
     * {@code req.dur} null becomes the default: 90 for an item that wears out, else 0. Without the item data (or a custom
     * item's material) it stays as the draft has it, 90 when none: a durability lost on the way would buy a worn item, while
     * one that does not fit is dropped by the agent. The draft's {@code customItems} go in the write's own field, not here.
     */
    @Nullable
    public static JsonObject orderOf(JsonObject draft, Ctx ctx) {
        if (!questionsOf(draft, ctx).isEmpty()) return null;
        for (String key : new String[] {"side", "item", "qty", "pay", "unit", "target", "limit"}) {
            if (isNull(draft, key)) return null;
        }
        JsonObject order = new JsonObject();
        order.addProperty("on", true);
        copyField(draft, order, "side");
        copyField(draft, order, "item");
        copyField(draft, order, "qty");
        JsonObject req = Json.obj(draft, "req");
        if (req == null) {
            setNull(order, "req");
        } else {
            Boolean wears = receivedWears(receivedOf(draft), ctx);
            JsonObject conditions = new JsonObject();
            JsonObject ench = Json.obj(req, "ench");
            conditions.add("ench", ench == null ? new JsonObject() : ench.deepCopy());
            if (wears != null && !wears) conditions.addProperty("dur", 0);
            else if (isNull(req, "dur")) conditions.addProperty("dur", 90);
            else copyField(req, conditions, "dur");
            conditions.addProperty("named", Json.bool(req, "named", false));
            conditions.addProperty("cursed", Json.bool(req, "cursed", false));
            order.add("req", conditions);
        }
        for (String key : new String[] {"pay", "unit", "target", "limit", "style", "trade", "until", "note", "src"}) {
            copyField(draft, order, key);
        }
        return order;
    }

    private static void copyField(JsonObject from, JsonObject to, String key) {
        JsonElement value = from.get(key);
        if (value != null) to.add(key, value.deepCopy());
    }

    // ---- status ----

    /** An order's status (the README's table): the wire names are the contract's. */
    public enum Status {
        WAITING_OK("waiting_ok"),
        YOUR_TRADE("your_trade"),
        TRADING("trading"),
        DONE("done"),
        EXPIRED("expired"),
        PAUSED("paused"),
        SIDE_OFF("side_off"),
        HAGGLING("haggling"),
        LOOKING("looking");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        /** The contract's name ({@code waiting_ok}, ...). */
        public String wire() {
            return wire;
        }

        /** Finished orders go under "Biten". */
        public boolean finished() {
            return this == DONE || this == EXPIRED;
        }

        /** The status of a wire name, or null. */
        @Nullable
        public static Status fromWire(@Nullable String wire) {
            for (Status status : values()) {
                if (status.wire.equals(wire)) return status;
            }
            return null;
        }
    }

    /** What an order's status is read against: the time, the order's progress, the machine's live state and the sides. */
    public static final class State {
        /** Now, Unix seconds. */
        public final long now;
        /** The progress: the quantity received or sold so far. */
        public final long got;
        /** The machine's live state of the order ({@code waiting_ok}, {@code your_trade}, {@code trading}, {@code haggling}), or null. */
        @Nullable public final String live;
        /** The account's sides ({@code allow}). */
        public final boolean allowBuy;
        public final boolean allowSell;

        public State(long now, long got, @Nullable String live, boolean allowBuy, boolean allowSell) {
            this.now = now;
            this.got = got;
            this.live = live;
            this.allowBuy = allowBuy;
            this.allowSell = allowSell;
        }
    }

    /**
     * An order's status: what needs the player first (an approval or a first sight, a trade the owner makes), then a trade in
     * its window, then finished (done before expired), then stopped (paused, the side switched off), then the bargaining or
     * looking.
     */
    public static Status statusOf(String side, long qty, long until, boolean on, State state) {
        String live = state.live;
        if ("waiting_ok".equals(live)) return Status.WAITING_OK;
        if ("your_trade".equals(live)) return Status.YOUR_TRADE;
        if ("trading".equals(live)) return Status.TRADING;
        if (state.got >= qty) return Status.DONE;
        if (state.now >= until) return Status.EXPIRED;
        if (!on) return Status.PAUSED;
        boolean allowed = "buy".equals(side) ? state.allowBuy : "sell".equals(side) && state.allowSell;
        if (!allowed) return Status.SIDE_OFF;
        return "haggling".equals(live) ? Status.HAGGLING : Status.LOOKING;
    }

    /** {@link #statusOf(String, long, long, boolean, State)} of an order as the service sends it ({@code side, qty, until, on}). */
    public static Status statusOf(JsonObject order, State state) {
        return statusOf(Json.str(order, "side", ""), Json.num(order, "qty", Long.MAX_VALUE),
                Json.num(order, "until", Long.MAX_VALUE), Json.bool(order, "on", false), state);
    }

    /** Whether an order with this status goes under "Biten". */
    public static boolean isFinished(Status status) {
        return status.finished();
    }
}
