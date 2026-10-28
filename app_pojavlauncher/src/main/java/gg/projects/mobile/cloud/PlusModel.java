package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.social.Json;

/**
 * The catalog-free AFK+ objects of the player API (services/cloud-afk README "AFK+ trade orders", "AFK+ player API",
 * "AFK+ live socket", "AFK+ extra credits"): the account's server as AFK+ sees it, the limits a client checks by, each
 * order's live view, the audit log's entries, receipts, an inventory pick, "Kontrol et"'s answer, an order write's answer
 * and the extra credits' offer. Drafts and questions stay JSON ({@code gg.projects.mobile.cloud.plus.OrderDraft} works on
 * them as the service wrote them).
 */
public final class PlusModel {
    private PlusModel() {}

    /** The money's cap on a server without a Studio profile (the generic profile). */
    public static final long GENERIC_MAX_DEAL = 100_000_000L;

    /** {@code PlusServer}: how AFK+ works on the account's server ({@code GET /accounts/:id/plus} → {@code server}). */
    public static final class Server {
        /** {@code verified} (the bot trades), {@code profile} (a Studio profile without a trade part) or {@code generic}. */
        public final String kind;
        public final String mode;
        @Nullable
        public final CloudModel.Localized name;
        /** Who makes the trades of orders with {@code trade: "auto"}: {@code bot} or {@code owner}. */
        public final String trade;
        /**
         * The money's name to write after amounts ("Coin"): the profile's, else the player's {@code trade.currency} (both
         * languages alike); null: bare amounts and once "Oyundaki parayla".
         */
        @Nullable
        public final CloudModel.Localized moneyLabel;
        /** The name is the profile's: the "Para birimi" setting shows it and cannot change it (28 Oct 2026). */
        public final boolean moneyFixed;
        public final long maxDeal;
        public final String currency;
        /** The account's Minecraft version (its item data); null: the manifest's default. */
        @Nullable
        public final String mc;
        /** The mode's ad templates: id, Turkish and English text. */
        public final List<String[]> ads;
        /** False: AFK+ does not run on this server ({@code ai.reason: "server"}). */
        public final boolean on;

        private Server(JsonObject o) {
            kind = Json.str(o, "kind", "generic");
            mode = Json.str(o, "mode", "generic");
            name = CloudModel.Localized.read(Json.obj(o, "name"));
            trade = "bot".equals(Json.str(o, "trade")) ? "bot" : "owner";
            JsonObject money = Json.obj(o, "money");
            moneyLabel = CloudModel.Localized.read(Json.obj(money, "label"));
            moneyFixed = Json.bool(money, "fixed", false);
            maxDeal = Math.max(1, Json.num(money, "maxDeal", GENERIC_MAX_DEAL));
            currency = Json.str(money, "currency", "");
            mc = Json.str(o, "mc");
            List<String[]> list = new ArrayList<>();
            for (JsonObject ad : Json.objects(o, "ads")) {
                String id = Json.str(ad, "id");
                if (id != null) list.add(new String[]{id, Json.str(ad, "tr", id), Json.str(ad, "en", Json.str(ad, "tr", id))});
            }
            ads = Collections.unmodifiableList(list);
            on = Json.bool(o, "on", true);
        }

        public static Server read(@Nullable JsonObject o) {
            return new Server(o == null ? new JsonObject() : o);
        }

        /** Whether the bot makes this server's trades itself (a verified profile with a trade part). */
        public boolean botTrades() {
            return "bot".equals(trade);
        }

        /** Whether an order is traded by the bot: its {@code trade} is {@code auto} and the server lets the bot trade. */
        public boolean tradesItself(String orderTrade) {
            return botTrades() && !"owner".equals(orderTrade);
        }
    }

    /** {@code PlusRules}: the Studio limits a client checks before it sends. */
    public static final class Rules {
        public final int orderMax;
        public final int orderStore;
        public final int customItemsMax;
        public final int orderDaysDefault;
        public final int orderDaysMax;
        public final int buyStacksPerDay;
        public final int checksPerDay;
        /** The machine's count of the checks left today; null: not known now. */
        @Nullable
        public final Integer checksLeft;
        public final int friendsMax;
        public final int noteMax;
        public final int textMax;
        public final int approvalTimeoutMin;
        public final int approvalTimeoutMax;
        public final boolean orders;
        public final boolean audit;
        public final boolean customItems;
        public final boolean itemPay;
        /** Studio's {@code features.currency}: the player may name the money ({@code trade.currency}); off by default. */
        public final boolean currency;

        private Rules(JsonObject o) {
            orderMax = (int) Json.num(o, "orderMax", 10);
            orderStore = (int) Json.num(o, "orderStore", 20);
            customItemsMax = (int) Json.num(o, "customItemsMax", 20);
            orderDaysDefault = (int) Json.num(o, "orderDaysDefault", 14);
            orderDaysMax = (int) Json.num(o, "orderDaysMax", 30);
            buyStacksPerDay = (int) Json.num(o, "buyStacksPerDay", 1);
            checksPerDay = (int) Json.num(o, "checksPerDay", 20);
            Long left = Json.numOrNull(o, "checksLeft");
            checksLeft = left == null ? null : (int) (long) left;
            friendsMax = (int) Json.num(o, "friendsMax", 50);
            noteMax = (int) Json.num(o, "noteMax", 200);
            textMax = (int) Json.num(o, "textMax", 200);
            JsonArray timeout = Json.arr(o, "approvalTimeoutMin");
            int lo = 1;
            int hi = 60;
            if (timeout.size() == 2 && timeout.get(0).isJsonPrimitive() && timeout.get(1).isJsonPrimitive()) {
                lo = timeout.get(0).getAsInt();
                hi = timeout.get(1).getAsInt();
            }
            approvalTimeoutMin = lo;
            approvalTimeoutMax = hi;
            JsonObject f = Json.obj(o, "features");
            orders = Json.bool(f, "orders", true);
            audit = Json.bool(f, "audit", true);
            customItems = Json.bool(f, "customItems", true);
            itemPay = Json.bool(f, "itemPay", false);
            currency = Json.bool(f, "currency", false);
        }

        public static Rules read(@Nullable JsonObject o) {
            return new Rules(o == null ? new JsonObject() : o);
        }
    }

    /** {@code OrderView}: an order's status and progress as the machine knows them (never stored). */
    public static final class OrderView {
        /** {@code waiting_ok}, {@code your_trade}, {@code trading}, {@code done}, {@code expired}, {@code paused}, {@code side_off}, {@code haggling} or {@code looking}. */
        public final String status;
        public final long got;
        /** When {@link #got} last grew (Unix seconds; 0: never). */
        public final long at;
        /** The machine's live state of the order: {@code waiting_ok}, {@code your_trade}, {@code trading}, {@code haggling}; null: none. */
        @Nullable
        public final String live;

        public OrderView(String status, long got, long at, @Nullable String live) {
            this.status = status;
            this.got = Math.max(0, got);
            this.at = Math.max(0, at);
            this.live = live;
        }

        static OrderView read(JsonObject o) {
            return new OrderView(Json.str(o, "status", "looking"), Json.num(o, "got", 0), Json.num(o, "at", 0), Json.str(o, "live"));
        }

        /** The views of {@code GET /plus} → {@code orders} (or an {@code aiord} frame's, whose views carry no status). */
        public static Map<String, OrderView> map(@Nullable JsonObject o) {
            Map<String, OrderView> out = new LinkedHashMap<>();
            if (o == null) return out;
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getValue().isJsonObject()) out.put(e.getKey(), read(e.getValue().getAsJsonObject()));
            }
            return out;
        }
    }

    /** {@code AuditEntry}: one step the assistant took ("Akış"); clients write the line. */
    public static final class AuditEntry {
        /** The account's sequence: AFK+ day number × 1,000,000 + a counter. */
        public final long s;
        /** Unix ms. */
        public final long t;
        public final String k;
        @Nullable
        public final String w;
        @Nullable
        public final String o;
        @Nullable
        public final String i;
        @Nullable
        public final Long q;
        @Nullable
        public final Long p;
        /** What the price counts: {@code money} or the paying item. */
        @Nullable
        public final String u;
        @Nullable
        public final String r;
        /** Text, masked (pm_in, reply, owner_msg only). */
        @Nullable
        public final String x;
        /** Unsuitable: shown folded ("Göster"). */
        public final boolean h;
        @Nullable
        public final String a;
        /** The entry as it came (the device's cache keeps it so). */
        public final JsonObject json;

        private AuditEntry(JsonObject o) {
            json = o;
            s = Json.num(o, "s", 0);
            t = Json.num(o, "t", 0);
            k = Json.str(o, "k", "");
            w = Json.str(o, "w");
            this.o = Json.str(o, "o");
            i = Json.str(o, "i");
            q = Json.numOrNull(o, "q");
            p = Json.numOrNull(o, "p");
            u = Json.str(o, "u");
            r = Json.str(o, "r");
            x = Json.str(o, "x");
            h = Json.bool(o, "h", false);
            a = Json.str(o, "a");
        }

        @Nullable
        public static AuditEntry read(@Nullable JsonObject o) {
            if (o == null || Json.numOrNull(o, "s") == null || Json.str(o, "k") == null) return null;
            return new AuditEntry(o);
        }

        public static List<AuditEntry> list(@Nullable JsonObject o, String key) {
            List<AuditEntry> out = new ArrayList<>();
            for (JsonObject e : Json.objects(o, key)) {
                AuditEntry entry = read(e);
                if (entry != null) out.add(entry);
            }
            return out;
        }

        /** The AFK+ day the entry belongs to (its sequence's day number). */
        public long day() {
            return s / 1_000_000L;
        }

        /** Whether the entry is in {@code group} ({@code deals}, {@code messages}, {@code warnings}; null: all). */
        public boolean in(@Nullable String group) {
            if (group == null) return true;
            switch (group) {
                case "messages":
                    return k.equals("pm_in") || k.equals("reply") || k.equals("owner_msg");
                case "deals":
                    switch (k) {
                        case "offer": case "counter": case "agree": case "ask_owner": case "owner_ok": case "owner_no": case "owner_timeout":
                        case "trade_start": case "trade_done": case "trade_fail": case "handoff": case "handoff_done": case "handoff_no":
                        case "cancel": case "found": case "confirm_item": case "order_done": case "order_expired":
                            return true;
                        default:
                            return false;
                    }
                case "warnings":
                    switch (k) {
                        case "refuse": case "gate": case "quota": case "paused": case "resumed": case "cancel": case "trade_fail":
                            return true;
                        default:
                            return false;
                    }
                default:
                    return true;
            }
        }
    }

    /** One page of the audit log ({@code GET …/plus/audit}): newest first. */
    public static final class AuditPage {
        public final List<AuditEntry> items;
        public final boolean more;
        /** The oldest AFK+ day kept (7 days back). */
        public final long from;

        public AuditPage(List<AuditEntry> items, boolean more, long from) {
            this.items = items;
            this.more = more;
            this.from = from;
        }

        static AuditPage read(@Nullable JsonObject o) {
            return new AuditPage(AuditEntry.list(o, "items"), Json.bool(o, "more", false), Json.num(o, "from", 0));
        }
    }

    /** {@code Receipt}: a completed deal (and an unclear trade under review). */
    public static final class Receipt {
        /** Unix ms. */
        public final long at;
        @Nullable
        public final String order;
        public final String side;
        public final String item;
        public final long qty;
        public final long price;
        /** {@code money} or the paying item. */
        public final String pay;
        @Nullable
        public final String currency;
        public final String counterpart;
        /** {@code auto}, {@code approved} or {@code owner} (the player reported the trade). */
        public final String approval;
        public final boolean review;
        @Nullable
        public final Long limit;

        private Receipt(JsonObject o) {
            at = Json.num(o, "at", 0);
            order = Json.str(o, "order");
            side = "sell".equals(Json.str(o, "side")) ? "sell" : "buy";
            item = Json.str(o, "item", "");
            qty = Math.max(1, Json.num(o, "qty", 1));
            price = Math.max(0, Json.num(o, "price", 0));
            pay = Json.str(o, "pay", "money");
            currency = Json.str(o, "currency");
            counterpart = Json.str(o, "counterpart", "");
            approval = Json.str(o, "approval", "auto");
            review = Json.bool(o, "review", false);
            limit = Json.numOrNull(o, "limit");
        }

        public static List<Receipt> list(@Nullable JsonObject o, String key) {
            List<Receipt> out = new ArrayList<>();
            for (JsonObject r : Json.objects(o, key)) if (!Json.str(r, "item", "").isEmpty()) out.add(new Receipt(r));
            return out;
        }

        public boolean money() {
            return "money".equals(pay);
        }
    }

    /** A page of receipts ({@code GET …/plus/receipts}). */
    public static final class ReceiptPage {
        public final List<Receipt> receipts;
        @Nullable
        public final String next;

        ReceiptPage(List<Receipt> receipts, @Nullable String next) {
            this.receipts = receipts;
            this.next = next;
        }
    }

    /** {@code PickResult}: a plain vanilla item with its conditions, or a custom item with its fingerprint. */
    public static final class Pick {
        /** A plain vanilla item's id, or null for a custom item. */
        @Nullable
        public final String ref;
        /** The vanilla item's enchantments and durability as conditions ({@code Req}). */
        @Nullable
        public final JsonObject req;
        /** A custom item: {@code name} (may be null), {@code base}, {@code fp}, {@code look}. */
        @Nullable
        public final JsonObject custom;

        private Pick(@Nullable String ref, @Nullable JsonObject req, @Nullable JsonObject custom) {
            this.ref = ref;
            this.req = req;
            this.custom = custom;
        }

        @Nullable
        static Pick read(@Nullable JsonObject o) {
            if (o == null) return null;
            String ref = Json.str(o, "ref");
            JsonObject custom = Json.obj(o, "custom");
            if (ref == null && custom == null) return null;
            return new Pick(ref, Json.obj(o, "req"), custom);
        }

        /** A custom item's name as the server gave it (null: unnamed). */
        @Nullable
        public String customName() {
            return Json.str(custom, "name");
        }
    }

    /** "Kontrol et"'s answer ({@code POST …/plus/orders/check}): the draft and its questions as JSON. */
    public static final class Check {
        public final JsonObject draft;
        public final JsonArray questions;
        /** {@code approval_pref}, {@code named_player}, {@code notify_only}, {@code item_pay_off}. */
        public final List<String> notes;
        /** The checks left today. */
        public final int left;

        Check(JsonObject draft, JsonArray questions, List<String> notes, int left) {
            this.draft = draft;
            this.questions = questions;
            this.notes = notes;
            this.left = left;
        }

        @Nullable
        static Check read(@Nullable JsonObject o) {
            JsonObject draft = Json.obj(o, "draft");
            if (draft == null) return null;
            return new Check(draft, Json.arr(o, "questions"), Json.strings(o, "notes"), (int) Json.num(o, "left", 0));
        }
    }

    /** The answer of an order write: the new settings and revision, and the order ({@code POST}, {@code PATCH}). */
    public static final class Written {
        @Nullable
        public final JsonObject order;
        public final JsonObject config;
        public final long aiRev;
        /** {@code DELETE} while the order's trade runs: it goes when the trade ends ("Takas bitince silinir."). */
        public final boolean deferred;
        /** The chat ads' preview for the new orders ({@code adPreview}); null from a Worker before it. */
        @Nullable
        public final JsonObject adPreview;

        Written(@Nullable JsonObject order, JsonObject config, long aiRev, boolean deferred, @Nullable JsonObject adPreview) {
            this.order = order;
            this.config = config;
            this.aiRev = aiRev;
            this.deferred = deferred;
            this.adPreview = adPreview;
        }

        @Nullable
        static Written read(@Nullable JsonObject o) {
            JsonObject config = Json.obj(o, "config");
            if (config == null) return null;
            return new Written(Json.obj(o, "order"), config, Json.num(o, "aiRev", 0), Json.bool(o, "deferred", false),
                    Json.obj(o, "adPreview"));
        }
    }

    /**
     * {@code AdPreview} (README "AFK+ chat ads", 28 Oct 2026): the ads' interval and the next lines, in the answers of
     * {@code GET}, {@code PUT}, a restore and every order write. Null where a Worker sends none: the screens then show
     * the ads' switch alone.
     */
    public static final class AdPreview {
        /** The shortest interval this server allows: max(30, the profile's, Studio's). */
        public final int floorMin;
        /** The interval in use: max(the player's, the floor), doubled while a spam warning holds. */
        public final int everyMin;
        /** The next (at most) 3 lines the rotation says, message text only; empty without an active order or names. */
        public final List<String> lines;
        /** When the first of them goes while ads run (seconds); null otherwise. */
        @Nullable
        public final Long nextAt;
        /** The session's first spam warning (seconds), or null. */
        @Nullable
        public final Long warningAt;
        /** The interval while the warning holds. */
        public final int warningEveryMin;
        /** A second warning or a mute stopped the ads until the AFK+ day ends. */
        public final boolean stoppedToday;
        /** An active order's item has no name from the machine yet: no line (never an id in its place). */
        public final boolean namesPending;

        private AdPreview(JsonObject o) {
            floorMin = (int) Math.max(30, Json.num(o, "floorMin", 30));
            everyMin = (int) Math.max(floorMin, Json.num(o, "everyMin", floorMin));
            List<String> list = new ArrayList<>();
            for (String line : Json.strings(o, "lines")) {
                if (!line.isEmpty() && list.size() < 3) list.add(line);
            }
            lines = Collections.unmodifiableList(list);
            nextAt = Json.numOrNull(o, "nextAt");
            JsonObject warning = Json.obj(o, "warning");
            warningAt = warning == null ? null : Json.numOrNull(warning, "at");
            warningEveryMin = warning == null ? everyMin : (int) Json.num(warning, "everyMin", everyMin);
            stoppedToday = Json.bool(o, "stoppedToday", false);
            namesPending = Json.bool(o, "namesPending", false);
        }

        @Nullable
        public static AdPreview read(@Nullable JsonObject o) {
            return o == null ? null : new AdPreview(o);
        }
    }

    /** {@code OfferTopup}: AFK+ extra credits ("Ek kontör"); null in the offer while Studio's switch is off. */
    public static final class Topup {
        public final boolean sales;
        /** Ascending: the TL price ("10.00") and its credits. */
        public final List<String[]> packs;
        public final int refundWindowHours;
        public final long maxBalance;

        private Topup(JsonObject o) {
            sales = Json.bool(o, "sales", false);
            List<String[]> list = new ArrayList<>();
            for (JsonObject p : Json.objects(o, "packs")) {
                String price = Json.str(p, "priceTl");
                long credits = Json.num(p, "credits", 0);
                if (price != null && credits > 0) list.add(new String[]{price, Long.toString(credits)});
            }
            packs = Collections.unmodifiableList(list);
            refundWindowHours = (int) Json.num(o, "refundWindowHours", 336);
            maxBalance = Json.num(o, "maxBalance", 0);
        }

        @Nullable
        static Topup read(@Nullable JsonObject o) {
            return o == null ? null : new Topup(o);
        }

        /** Whether "Kontör al" can be offered (the site sells packs now). */
        public boolean selling() {
            return sales && !packs.isEmpty();
        }
    }
}
