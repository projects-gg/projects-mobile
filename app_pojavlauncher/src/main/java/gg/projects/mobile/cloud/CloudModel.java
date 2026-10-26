package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.social.Json;

/**
 * The objects of 7/24 Bulut's player API (services/cloud-afk README "Objects", "AFK+ tier", "AFK+ player API"), read
 * leniently: a missing field or one of another type reads as its default, unknown fields and unknown enumeration
 * values never break the app (an unknown state reads like the most generic one of its kind, the contract's rule).
 */
public final class CloudModel {
    private CloudModel() {}

    /** Studio's Turkish and English words; the other language when one is empty. */
    public static final class Localized {
        public final String tr;
        public final String en;

        Localized(String tr, String en) {
            this.tr = tr;
            this.en = en;
        }

        @Nullable
        static Localized read(@Nullable JsonObject o) {
            if (o == null) return null;
            String tr = Json.str(o, "tr", "").trim();
            String en = Json.str(o, "en", "").trim();
            return tr.isEmpty() && en.isEmpty() ? null : new Localized(tr, en);
        }

        public String in(String lang) {
            if ("en".equals(lang)) return en.isEmpty() ? tr : en;
            return tr.isEmpty() ? en : tr;
        }
    }

    public static final class Price {
        public final String plan;
        public final String priceTl;
        public final int days;

        Price(String plan, String priceTl, int days) {
            this.plan = plan;
            this.priceTl = priceTl;
            this.days = days;
        }

        static List<Price> list(@Nullable JsonObject o, String key) {
            List<Price> out = new ArrayList<>();
            for (JsonObject p : Json.objects(o, key)) {
                String plan = Json.str(p, "plan");
                String price = Json.str(p, "priceTl");
                if (plan != null && price != null) out.add(new Price(plan, price, (int) Json.num(p, "days", 0)));
            }
            return out;
        }
    }

    /** Studio's numbers in {@code /offer} (the contract's defaults when missing). */
    public static final class Limits {
        public final int perPlayerCap;
        public final int serverChangesPerHour;
        public final int maxCommandRows;
        public final int maxRules;
        public final int minIntervalSec;
        public final int minRuleCooldownSec;
        public final int chatCooldownSec;
        public final int consoleLinesPerMinute;
        public final int minDiscordAgeDays;

        Limits(@Nullable JsonObject o) {
            perPlayerCap = (int) Json.num(o, "perPlayerCap", 10);
            serverChangesPerHour = (int) Json.num(o, "serverChangesPerHour", 6);
            maxCommandRows = (int) Json.num(o, "maxCommandRows", 20);
            maxRules = (int) Json.num(o, "maxRules", 10);
            minIntervalSec = (int) Json.num(o, "minIntervalSec", 60);
            minRuleCooldownSec = (int) Json.num(o, "minRuleCooldownSec", 10);
            chatCooldownSec = (int) Json.num(o, "chatCooldownSec", 0);
            consoleLinesPerMinute = (int) Json.num(o, "consoleLinesPerMinute", 20);
            minDiscordAgeDays = (int) Json.num(o, "minDiscordAgeDays", 0);
        }

        public static Limits defaults() {
            return new Limits(null);
        }
    }

    /** {@code GET /offer}: whether the service is on, what it sells, Studio's numbers and AFK+'s switches. */
    public static final class Offer {
        public final String mode;
        @Nullable
        public final Localized maintenance;
        public final boolean salesNew;
        public final boolean salesRenewals;
        public final List<Price> prices;
        public final boolean soldOut;
        @Nullable
        public final Long remaining;
        @Nullable
        public final String topUpUrl;
        public final int renewalWindowDays;
        public final int graceDays;
        @Nullable
        public final String recommendedVersion;
        public final Limits limits;
        /** Null while AFK+ is off ({@code plusMode: off}): then nothing of AFK+ shows. */
        @Nullable
        public final OfferPlus plus;
        /** CAFK-FEAT's switches (never null: an older service sends none, and everything is off). */
        public final OfferFeatures features;

        private Offer(JsonObject o) {
            mode = Json.str(o, "mode", "off");
            maintenance = Localized.read(Json.obj(o, "maintenanceMessage"));
            JsonObject sales = Json.obj(o, "sales");
            salesNew = Json.bool(sales, "newAccounts", false);
            salesRenewals = Json.bool(sales, "renewals", false);
            prices = Price.list(o, "prices");
            JsonObject stock = Json.obj(o, "stock");
            soldOut = Json.bool(stock, "soldOut", false);
            remaining = Json.numOrNull(stock, "remaining");
            topUpUrl = Json.str(o, "topUpUrl");
            renewalWindowDays = (int) Json.num(o, "renewalWindowDays", 0);
            graceDays = (int) Json.num(o, "graceDays", 0);
            recommendedVersion = Json.str(o, "recommendedVersion");
            limits = new Limits(Json.obj(o, "limits"));
            plus = OfferPlus.read(Json.obj(o, "plus"));
            features = OfferFeatures.read(Json.obj(o, "features"));
        }

        @Nullable
        public static Offer read(@Nullable JsonObject o) {
            return o == null ? null : new Offer(o);
        }

        /** The service shows at all: {@code on} or {@code paused} (maintenance), never {@code off}. */
        public boolean available() {
            return "on".equals(mode) || "paused".equals(mode);
        }

        public boolean paused() {
            return "paused".equals(mode);
        }

        @Nullable
        public Price month() {
            for (Price p : prices) if ("month".equals(p.plan)) return p;
            return prices.isEmpty() ? null : prices.get(0);
        }

        /** An AFK+ QoL feature Studio has on ({@code inbox}, {@code parse}, {@code schedule}, {@code report}, {@code history}, {@code saver}). */
        public boolean plusFeature(String name) {
            return plus != null && plus.features.contains(name);
        }
    }

    /** The default commands a reply with placeholders may start with (services/cloud-afk README "Rule placeholders"). */
    public static final List<String> DEFAULT_PLACEHOLDER_COMMANDS =
            Collections.unmodifiableList(java.util.Arrays.asList("msg", "m", "tell", "w", "whisper", "r", "reply", "pm"));

    /**
     * {@code Offer.features} (services/cloud-afk README "Features" → Feature switches): what Studio has switched on for
     * the service. Every switch is off by default; a client shows a feature only by the account's own
     * {@link AccountFeatures}, and these only where no account is at hand.
     */
    public static final class OfferFeatures {
        /** {@code off}, {@code view} or {@code full}. */
        public final String inventory;
        public final boolean consoleKinds;
        public final boolean placeholders;
        public final List<String> placeholderCommands;
        /**
         * The service names its features at all (a phase-1 Worker): bulk management, which has no switch of its own
         * ("nothing new on the Worker"), shows from then on (the launcher's rule, FEAT-L).
         */
        public final boolean present;

        OfferFeatures(String inventory, boolean consoleKinds, boolean placeholders, List<String> placeholderCommands, boolean present) {
            this.present = present;
            this.inventory = inventory;
            this.consoleKinds = consoleKinds;
            this.placeholders = placeholders;
            this.placeholderCommands = Collections.unmodifiableList(placeholderCommands);
        }

        static OfferFeatures read(@Nullable JsonObject o) {
            List<String> commands = new ArrayList<>();
            for (String c : Json.strings(o, "placeholderCommands")) {
                String name = c.trim().toLowerCase(java.util.Locale.ROOT);
                if (!name.isEmpty() && !commands.contains(name)) commands.add(name);
            }
            if (commands.isEmpty()) commands.addAll(DEFAULT_PLACEHOLDER_COMMANDS);
            return new OfferFeatures(inventoryMode(Json.str(o, "inventory", "off")), Json.bool(o, "consoleKinds", false),
                    Json.bool(o, "placeholders", false), commands, o != null);
        }

        public static OfferFeatures none() {
            return read(null);
        }
    }

    /** {@code off}, {@code view} or {@code full}; anything else (a newer mode) reads as {@code off}. */
    static String inventoryMode(@Nullable String mode) {
        return "view".equals(mode) || "full".equals(mode) ? mode : "off";
    }

    /**
     * {@code Account.features}: what this account may use now (the service's switch, its machine's capability, its own
     * setting). A client shows a feature's controls only by this object: with the inventory's mode {@code off} there is
     * no inventory at all; with {@code on} false it says it is off and offers to turn it on.
     */
    public static final class AccountFeatures {
        /** {@code off}, {@code view} (looking only) or {@code full} (moves too). */
        public final String inventoryMode;
        /** The account's own setting ({@code config.inventory.enabled}, else Studio's default). */
        public final boolean inventoryOn;
        public final boolean consoleKinds;
        public final boolean placeholders;
        /** Console kinds on, but this account's rules still match the old way until its settings are saved once. */
        public final boolean legacyRules;

        AccountFeatures(String inventoryMode, boolean inventoryOn, boolean consoleKinds, boolean placeholders, boolean legacyRules) {
            this.inventoryMode = inventoryMode;
            this.inventoryOn = inventoryOn;
            this.consoleKinds = consoleKinds;
            this.placeholders = placeholders;
            this.legacyRules = legacyRules;
        }

        static AccountFeatures read(@Nullable JsonObject o) {
            JsonObject inv = Json.obj(o, "inventory");
            return new AccountFeatures(inventoryMode(Json.str(inv, "mode", "off")), Json.bool(inv, "on", false),
                    Json.bool(o, "consoleKinds", false), Json.bool(o, "placeholders", false), Json.bool(o, "legacyRules", false));
        }

        public static AccountFeatures none() {
            return read(null);
        }

        /** The inventory may show at all (its row, its screen). */
        public boolean inventory() {
            return !"off".equals(inventoryMode);
        }

        /** Moves are allowed (not "looking only"). */
        public boolean inventoryMoves() {
            return "full".equals(inventoryMode);
        }
    }

    public static final class OfferPlus {
        public final String mode;
        public final boolean sales;
        public final List<Price> prices;
        public final boolean soldOut;
        public final int refundWindowHours;
        public final int termsVersion;
        /** The QoL features Studio has on. */
        public final Set<String> features;

        private OfferPlus(JsonObject o) {
            mode = Json.str(o, "mode", "on");
            sales = Json.bool(o, "sales", false);
            prices = Price.list(o, "prices");
            soldOut = Json.bool(Json.obj(o, "stock"), "soldOut", false);
            refundWindowHours = (int) Json.num(o, "refundWindowHours", 0);
            termsVersion = (int) Json.num(o, "termsVersion", 0);
            Set<String> on = new HashSet<>();
            JsonObject f = Json.obj(o, "features");
            if (f != null) {
                for (Map.Entry<String, JsonElement> e : f.entrySet()) {
                    if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isBoolean() && e.getValue().getAsBoolean()) {
                        on.add(e.getKey());
                    }
                }
            }
            features = Collections.unmodifiableSet(on);
        }

        @Nullable
        static OfferPlus read(@Nullable JsonObject o) {
            return o == null ? null : new OfferPlus(o);
        }
    }

    /** {@code GET /me}. */
    public static final class Me {
        public final String userId;
        public final String username;
        @Nullable
        public final String displayName;
        public final int accountsCount;
        public final int accountsCap;
        public final boolean canBuy;
        @Nullable
        public final String canBuyReason;
        /** Cloud AFK's own ban: until then (null: until lifted); {@link #restricted} false when none. */
        public final boolean restricted;
        @Nullable
        public final Long restrictedUntil;
        public final boolean linked;
        public final long now;

        private Me(JsonObject o) {
            JsonObject user = Json.obj(o, "user");
            userId = Json.str(user, "id", "");
            username = Json.str(user, "username", "");
            displayName = Json.str(user, "displayName");
            JsonObject accounts = Json.obj(o, "accounts");
            accountsCount = (int) Json.num(accounts, "count", 0);
            accountsCap = (int) Json.num(accounts, "cap", 0);
            JsonObject canBuyObj = Json.obj(o, "canBuy");
            canBuy = Json.bool(canBuyObj, "ok", false);
            canBuyReason = Json.str(canBuyObj, "reason");
            JsonObject r = Json.obj(o, "restricted");
            restricted = r != null;
            restrictedUntil = Json.numOrNull(r, "until");
            linked = Json.obj(o, "link") != null;
            now = Json.num(o, "now", 0);
        }

        @Nullable
        public static Me read(@Nullable JsonObject o) {
            if (o == null || Json.obj(o, "user") == null) return null;
            return new Me(o);
        }
    }

    /** Why an account does not run although it should ({@code hold}); the kinds in the contract's precedence. */
    public static final class Hold {
        public final String kind;
        @Nullable
        public final Long until;
        @Nullable
        public final String reason;
        public final long at;
        @Nullable
        public final String mode;
        @Nullable
        public final Localized blockedReason;
        @Nullable
        public final String cause;
        @Nullable
        public final String row;
        @Nullable
        public final String category;
        public final boolean auto;

        private Hold(JsonObject o) {
            kind = Json.str(o, "kind", "");
            until = Json.numOrNull(o, "until");
            JsonElement r = o.get("reason");
            reason = r != null && r.isJsonPrimitive() ? r.getAsString() : null;
            blockedReason = r != null && r.isJsonObject() ? Localized.read(r.getAsJsonObject()) : null;
            at = Json.num(o, "at", Json.num(o, "since", 0));
            mode = Json.str(o, "mode");
            cause = Json.str(o, "cause");
            row = Json.str(o, "row");
            category = Json.str(o, "category");
            auto = Json.bool(o, "auto", false);
        }

        @Nullable
        static Hold read(@Nullable JsonObject o) {
            return o == null || Json.str(o, "kind") == null ? null : new Hold(o);
        }
    }

    /** What the machine says now ({@code live}). */
    public static final class Live {
        public final String state;
        @Nullable
        public final Long since;
        @Nullable
        public final String reason;
        public final int attempt;
        @Nullable
        public final Long retryAt;

        Live(String state, @Nullable Long since, @Nullable String reason, int attempt, @Nullable Long retryAt) {
            this.state = state;
            this.since = since;
            this.reason = reason;
            this.attempt = attempt;
            this.retryAt = retryAt;
        }

        public static Live read(@Nullable JsonObject o) {
            if (o == null) return new Live("unknown", null, null, 0, null);
            return new Live(Json.str(o, "state", "unknown"), Json.numOrNull(o, "since"), Json.str(o, "reason"),
                    (int) Json.num(o, "attempt", 0), Json.numOrNull(o, "retryAt"));
        }
    }

    /** The two quota bars as an account carries them ({@code ai.quota}). */
    public static final class QuotaBrief {
        public final int pct5;
        public final int pctWeek;
        @Nullable
        public final Long w5hResetAt;
        public final long weekResetAt;
        @Nullable
        public final Long exhaustedUntil;
        public final long giftsLeft;

        private QuotaBrief(JsonObject o) {
            pct5 = (int) Json.num(o, "pct5", 0);
            pctWeek = (int) Json.num(o, "pctWeek", 0);
            w5hResetAt = Json.numOrNull(o, "w5hResetAt");
            weekResetAt = Json.num(o, "weekResetAt", 0);
            exhaustedUntil = Json.numOrNull(o, "exhaustedUntil");
            giftsLeft = Json.num(o, "giftsLeft", 0);
        }

        @Nullable
        static QuotaBrief read(@Nullable JsonObject o) {
            return o == null ? null : new QuotaBrief(o);
        }
    }

    /**
     * What the assistant has spent ({@code spent}, CAFK-W5), in the game mode's money like the limits: {@code total} since
     * the last {@code reset_spent} (against {@code totalBudget}), {@code today} from 04:00 Turkey time (against
     * {@code dailySpendMax}). Only live answers carry it: null when the machine did not answer in time or the account is
     * not AFK+ now, and then the screens show nothing about it.
     */
    public static final class Spent {
        public final long total;
        public final long today;

        public Spent(long total, long today) {
            this.total = Math.max(0, total);
            this.today = Math.max(0, today);
        }

        @Nullable
        public static Spent read(@Nullable JsonElement e) {
            if (e == null || !e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            Long total = Json.numOrNull(o, "total");
            Long today = Json.numOrNull(o, "today");
            if (total == null && today == null) return null;
            return new Spent(total == null ? 0 : total, today == null ? 0 : today);
        }
    }

    /** An AFK+ account's AI ({@code ai}). */
    public static final class AiView {
        /** {@code on}, {@code paused}, {@code off} or {@code unknown}. */
        public final String state;
        @Nullable
        public final String reason;
        @Nullable
        public final String staffReason;
        @Nullable
        public final Long until;
        public final boolean tradeOn;
        @Nullable
        public final String tradeOff;
        /** {@code on}, or {@code notify}: no spending authority yet (buy rows only report). */
        public final String buy;
        public final int pendingApprovals;
        @Nullable
        public final QuotaBrief quota;
        @Nullable
        public final Long asOf;
        /** Live answers only (CAFK-W5). */
        @Nullable
        public final Spent spent;

        private AiView(JsonObject o) {
            state = Json.str(o, "state", "unknown");
            reason = Json.str(o, "reason");
            staffReason = Json.str(o, "staffReason");
            until = Json.numOrNull(o, "until");
            JsonObject trade = Json.obj(o, "trade");
            tradeOn = Json.bool(trade, "on", false);
            tradeOff = Json.str(trade, "off");
            buy = Json.str(trade, "buy", "on");
            pendingApprovals = (int) Json.num(o, "pendingApprovals", 0);
            quota = QuotaBrief.read(Json.obj(o, "quota"));
            asOf = Json.numOrNull(o, "asOf");
            spent = Spent.read(o.get("spent"));
        }

        @Nullable
        public static AiView read(@Nullable JsonObject o) {
            return o == null ? null : new AiView(o);
        }
    }

    /** The account's case with the highest step in force ({@code moderation}). */
    public static final class Moderation {
        public final String caseId;
        public final String step;
        @Nullable
        public final String category;
        public final long at;
        @Nullable
        public final Long until;
        public final boolean appealCan;
        public final String appealState;

        private Moderation(JsonObject o) {
            caseId = Json.str(o, "case", "");
            step = Json.str(o, "step", "warn");
            category = Json.str(o, "category");
            at = Json.num(o, "at", 0);
            until = Json.numOrNull(o, "until");
            JsonObject appeal = Json.obj(o, "appeal");
            appealCan = Json.bool(appeal, "can", false);
            appealState = Json.str(appeal, "state", "none");
        }

        @Nullable
        static Moderation read(@Nullable JsonObject o) {
            return o == null ? null : new Moderation(o);
        }
    }

    /** A row's words as the account view gives them: plain text, or a secret's preview and fingerprint. */
    public static final class PayloadView {
        public final boolean secret;
        @Nullable
        public final String text;
        @Nullable
        public final String preview;
        @Nullable
        public final String fp;

        PayloadView(boolean secret, @Nullable String text, @Nullable String preview, @Nullable String fp) {
            this.secret = secret;
            this.text = text;
            this.preview = preview;
            this.fp = fp;
        }

        static PayloadView read(@Nullable JsonObject o) {
            boolean secret = Json.bool(o, "secret", false);
            return new PayloadView(secret, secret ? null : Json.str(o, "text", ""), Json.str(o, "preview"), Json.str(o, "fp"));
        }
    }

    public static final class CommandView {
        public final String id;
        public final boolean enabled;
        /** {@code join}, {@code start}, {@code reconnect} (with {@link #delay}), {@code interval} ({@link #everyMin}..{@link #everyMax}) or {@code daily} ({@link #at}). */
        public final String on;
        public final int delay;
        public final int everyMin;
        public final int everyMax;
        public final List<String> at;
        public final PayloadView payload;
        /** CAFK-MOD: paused by moderation ("Durduruldu"); its category, null when not paused. */
        @Nullable
        public final String pausedCategory;
        public final boolean paused;

        private CommandView(JsonObject o) {
            id = Json.str(o, "id", "");
            enabled = Json.bool(o, "enabled", true);
            on = Json.str(o, "on", "join");
            delay = (int) Json.num(o, "delay", 0);
            JsonObject every = Json.obj(o, "every");
            everyMin = (int) Json.num(every, "min", 0);
            everyMax = (int) Json.num(every, "max", 0);
            at = Json.strings(o, "at");
            payload = PayloadView.read(o);
            JsonObject p = Json.obj(o, "paused");
            paused = p != null;
            pausedCategory = Json.str(p, "category");
        }
    }

    public static final class RuleView {
        public final String id;
        public final boolean enabled;
        public final String match;
        public final boolean regex;
        public final String from;
        public final boolean ownerOnly;
        public final int cooldown;
        public final PayloadView reply;
        public final boolean paused;
        @Nullable
        public final String pausedCategory;

        private RuleView(JsonObject o) {
            id = Json.str(o, "id", "");
            enabled = Json.bool(o, "enabled", true);
            match = Json.str(o, "match", "");
            regex = Json.bool(o, "regex", false);
            from = Json.str(o, "from", "any");
            ownerOnly = Json.bool(o, "ownerOnly", false);
            cooldown = (int) Json.num(o, "cooldown", 60);
            reply = PayloadView.read(Json.obj(o, "reply"));
            JsonObject p = Json.obj(o, "paused");
            paused = p != null;
            pausedCategory = Json.str(p, "category");
        }
    }

    /** {@code CloudAccountConfig} v1 in its view form. */
    public static final class ConfigView {
        public final String owner;
        public final boolean antiAfk;
        public final int delayMin;
        public final int delayMax;
        public final List<CommandView> commands;
        public final List<RuleView> rules;
        /**
         * CAFK-FEAT: the player's own inventory choice ({@code inventory.enabled}); null when never chosen (then the
         * service's default applies, and a save leaves it out so it stays unchosen).
         */
        @Nullable
        public final Boolean inventory;

        private ConfigView(@Nullable JsonObject o) {
            JsonObject inv = Json.obj(o, "inventory");
            inventory = inv != null && inv.has("enabled") && inv.get("enabled").isJsonPrimitive()
                    && inv.getAsJsonPrimitive("enabled").isBoolean() ? inv.get("enabled").getAsBoolean() : null;
            owner = Json.str(o, "owner", "");
            JsonObject anti = Json.obj(o, "antiAfk");
            antiAfk = Json.bool(anti, "enabled", true);
            delayMin = (int) Json.num(anti, "delayMin", 60);
            delayMax = (int) Json.num(anti, "delayMax", 90);
            List<CommandView> c = new ArrayList<>();
            for (JsonObject row : Json.objects(o, "commands")) c.add(new CommandView(row));
            commands = Collections.unmodifiableList(c);
            List<RuleView> r = new ArrayList<>();
            for (JsonObject row : Json.objects(o, "rules")) r.add(new RuleView(row));
            rules = Collections.unmodifiableList(r);
        }

        static ConfigView read(@Nullable JsonObject o) {
            return new ConfigView(o);
        }

        /** The stored seals' fingerprints by row id (secret rows only). */
        public Map<String, String> fingerprints() {
            Map<String, String> out = new HashMap<>();
            for (CommandView c : commands) if (c.payload.secret && c.payload.fp != null) out.put(c.id, c.payload.fp);
            for (RuleView r : rules) if (r.reply.secret && r.reply.fp != null) out.put(r.id, r.reply.fp);
            return out;
        }
    }

    /** One cloud account ({@code Account}, with AFK+'s {@code tier}, {@code plusUntil} and {@code ai}). */
    public static final class Account {
        public final String id;
        public final String name;
        public final CloudServer server;
        public final String version;
        public final String lang;
        @Nullable
        public final String host;
        public final long createdAt;
        public final long expiresAt;
        public final boolean expired;
        public final long graceUntil;
        public final boolean renewalOpen;
        public final long renewalOpensAt;
        /** {@code run} or {@code stop}. */
        public final String desired;
        @Nullable
        public final Hold hold;
        public final Live live;
        public final boolean everJoined;
        public final ConfigView config;
        public final long rev;
        public final int serverChangesUsed;
        public final int serverChangesLimit;
        @Nullable
        public final Long serverChangesResetAt;
        public final boolean refundEligible;
        @Nullable
        public final String refundOrderId;
        /** {@code plain} or {@code plus}. */
        public final String tier;
        @Nullable
        public final Long plusUntil;
        @Nullable
        public final AiView ai;
        @Nullable
        public final Moderation moderation;
        /** CAFK-FEAT: what this account may use now (never null: an older service sends none, and all is off). */
        public final AccountFeatures features;
        /** The JSON it was read from (for the copies a socket message makes). */
        private final JsonObject mSource;

        private Account(JsonObject o) {
            mSource = o;
            id = Json.str(o, "id", "");
            name = Json.str(o, "name", "");
            JsonObject s = Json.obj(o, "server");
            server = new CloudServer(Json.str(s, "host", ""), (int) Json.num(s, "port", 0));
            version = Json.str(o, "version", "auto");
            lang = Json.str(o, "lang", "tr");
            host = Json.str(o, "host");
            createdAt = Json.num(o, "createdAt", 0);
            expiresAt = Json.num(o, "expiresAt", 0);
            expired = Json.bool(o, "expired", false);
            graceUntil = Json.num(o, "graceUntil", 0);
            JsonObject renewal = Json.obj(o, "renewal");
            renewalOpen = Json.bool(renewal, "open", false);
            renewalOpensAt = Json.num(renewal, "opensAt", 0);
            desired = Json.str(o, "desired", "stop");
            hold = Hold.read(Json.obj(o, "hold"));
            live = Live.read(Json.obj(o, "live"));
            everJoined = Json.bool(o, "everJoined", false);
            config = ConfigView.read(Json.obj(o, "config"));
            rev = Json.num(o, "rev", 0);
            JsonObject changes = Json.obj(o, "serverChanges");
            serverChangesUsed = (int) Json.num(changes, "used", 0);
            serverChangesLimit = (int) Json.num(changes, "limit", 0);
            serverChangesResetAt = Json.numOrNull(changes, "resetAt");
            JsonObject refund = Json.obj(o, "refund");
            refundEligible = Json.bool(refund, "eligible", false);
            refundOrderId = Json.str(refund, "orderId");
            tier = Json.str(o, "tier", "plain");
            plusUntil = Json.numOrNull(o, "plusUntil");
            ai = AiView.read(Json.obj(o, "ai"));
            moderation = Moderation.read(Json.obj(o, "moderation"));
            // An account without a machine has everything off (the contract), whatever an object might say.
            features = host == null ? AccountFeatures.none() : AccountFeatures.read(Json.obj(o, "features"));
        }

        @Nullable
        public static Account read(@Nullable JsonObject o) {
            if (o == null || Json.str(o, "id") == null) return null;
            return new Account(o);
        }

        public static List<Account> list(@Nullable JsonObject o, String key) {
            List<Account> out = new ArrayList<>();
            for (JsonObject a : Json.objects(o, key)) {
                Account account = read(a);
                if (account != null) out.add(account);
            }
            return out;
        }

        public boolean plus() {
            return "plus".equals(tier);
        }

        /** A copy with another live state (the console socket's {@code st}). */
        public Account withLive(JsonObject live) {
            JsonObject copy = mSource.deepCopy();
            copy.add("live", live);
            return new Account(copy);
        }

        /** A copy with another AI view (the socket's {@code aist}). */
        public Account withAi(JsonObject ai) {
            JsonObject copy = mSource.deepCopy();
            copy.add("ai", ai);
            return new Account(copy);
        }
    }

    public static final class Version {
        public final String value;
        public final String label;

        Version(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    /** {@code GET /versions}. */
    public static final class Versions {
        public final List<Version> versions;
        @Nullable
        public final String recommended;

        Versions(List<Version> versions, @Nullable String recommended) {
            this.versions = versions;
            this.recommended = recommended;
        }

        public static Versions read(@Nullable JsonObject o) {
            List<Version> list = new ArrayList<>();
            for (JsonObject v : Json.objects(o, "versions")) {
                String value = Json.str(v, "value");
                if (value != null) list.add(new Version(value, Json.str(v, "label", value)));
            }
            return new Versions(Collections.unmodifiableList(list), Json.str(o, "recommended"));
        }
    }

    /**
     * A console line, masked by the machine: {@code k} {@code n} normal, {@code e} the client's error, {@code s} a command
     * row; with console kinds (CAFK-FEAT) also {@code c} (chat, pm, sys, title, cli, err; null on older lines), the other
     * player {@code who} of a chat or private line, and {@code go} ("inventory": a machine line apps may link to a view).
     */
    public static final class ConsoleLine {
        public final long at;
        public final String text;
        public final String k;
        @Nullable
        public final String c;
        @Nullable
        public final String who;
        @Nullable
        public final String go;

        public ConsoleLine(long at, String text, String k) {
            this(at, text, k, null, null, null);
        }

        public ConsoleLine(long at, String text, String k, @Nullable String c, @Nullable String who, @Nullable String go) {
            this.at = at;
            this.text = text;
            this.k = k;
            this.c = c;
            this.who = who;
            this.go = go;
        }

        public static List<ConsoleLine> list(@Nullable JsonObject o, String key) {
            List<ConsoleLine> out = new ArrayList<>();
            for (JsonObject l : Json.objects(o, key)) {
                String c = Json.str(l, "c");
                String who = Json.str(l, "who");
                if (who != null && !who.matches("^[A-Za-z0-9_]{1,16}$")) who = null;
                String go = Json.str(l, "goto");
                out.add(new ConsoleLine(Json.num(l, "at", 0), Json.str(l, "text", ""), Json.str(l, "k", "n"), c == null || c.isEmpty() ? null : c,
                        who, "inventory".equals(go) ? go : null));
            }
            return out;
        }
    }

    public static final class LogPage {
        public final List<ConsoleLine> lines;
        @Nullable
        public final String next;

        LogPage(List<ConsoleLine> lines, @Nullable String next) {
            this.lines = lines;
            this.next = next;
        }

        public static LogPage read(@Nullable JsonObject o) {
            return new LogPage(ConsoleLine.list(o, "lines"), Json.str(o, "next"));
        }
    }

    // ---- AFK+ ----

    /** An approval the bot waits for ({@code Approval}). */
    public static final class Approval {
        public final String id;
        public final String acct;
        public final String side;
        public final String item;
        public final long qty;
        public final long price;
        public final String currency;
        public final String counterpart;
        public final List<String> why;
        public final long createdAt;
        public final long expiresAt;
        /** {@code pending}, {@code approved}, {@code rejected}, {@code expired} or {@code closed}. */
        public final String state;
        @Nullable
        public final Long maxPrice;
        @Nullable
        public final String closed;

        private Approval(JsonObject o) {
            id = Json.str(o, "id", "");
            acct = Json.str(o, "acct", "");
            JsonObject deal = Json.obj(o, "deal");
            side = Json.str(deal, "side", "buy");
            item = Json.str(deal, "item", "");
            qty = Json.num(deal, "qty", 1);
            price = Json.num(deal, "price", 0);
            currency = Json.str(deal, "currency", "");
            counterpart = Json.str(o, "counterpart", "");
            why = Json.strings(o, "why");
            createdAt = Json.num(o, "createdAt", 0);
            expiresAt = Json.num(o, "expiresAt", 0);
            state = Json.str(o, "state", "pending");
            maxPrice = Json.numOrNull(o, "maxPrice");
            closed = Json.str(o, "closed");
        }

        @Nullable
        public static Approval read(@Nullable JsonObject o) {
            return o == null || Json.str(o, "id") == null ? null : new Approval(o);
        }

        public static List<Approval> list(@Nullable JsonObject o, String key) {
            List<Approval> out = new ArrayList<>();
            for (JsonObject a : Json.objects(o, key)) {
                Approval approval = read(a);
                if (approval != null) out.add(approval);
            }
            return out;
        }

        public boolean pending(long now) {
            return "pending".equals(state) && expiresAt > now;
        }
    }

    /** A line of the live feed ({@code FeedItem}). */
    public static final class FeedItem {
        public final String id;
        public final long at;
        public final String kind;
        @Nullable
        public final String counterpart;
        @Nullable
        public final String text;
        @Nullable
        public final String item;
        @Nullable
        public final Long qty;
        @Nullable
        public final Long price;
        @Nullable
        public final String currency;
        @Nullable
        public final String reason;

        private FeedItem(JsonObject o) {
            id = Json.str(o, "id", "");
            at = Json.num(o, "at", 0);
            kind = Json.str(o, "kind", "");
            counterpart = Json.str(o, "counterpart");
            text = Json.str(o, "text");
            item = Json.str(o, "item");
            qty = Json.numOrNull(o, "qty");
            price = Json.numOrNull(o, "price");
            currency = Json.str(o, "currency");
            reason = Json.str(o, "reason");
        }

        public static List<FeedItem> list(@Nullable JsonObject o, String key) {
            List<FeedItem> out = new ArrayList<>();
            for (JsonObject f : Json.objects(o, key)) if (Json.str(f, "id") != null) out.add(new FeedItem(f));
            return out;
        }
    }

    /** A deal of the trade history ({@code Deal}). */
    public static final class Deal {
        public final String id;
        public final String side;
        public final String item;
        public final long qty;
        public final long price;
        public final String currency;
        public final String counterpart;
        public final String status;
        @Nullable
        public final String reason;
        public final String approval;
        public final long at;

        private Deal(JsonObject o) {
            id = Json.str(o, "id", "");
            side = Json.str(o, "side", "buy");
            item = Json.str(o, "item", "");
            qty = Json.num(o, "qty", 1);
            price = Json.num(o, "price", 0);
            currency = Json.str(o, "currency", "");
            counterpart = Json.str(o, "counterpart", "");
            status = Json.str(o, "status", "completed");
            reason = Json.str(o, "reason");
            approval = Json.str(o, "approval", "auto");
            at = Json.num(o, "at", 0);
        }

        public static List<Deal> list(@Nullable JsonObject o, String key) {
            List<Deal> out = new ArrayList<>();
            for (JsonObject d : Json.objects(o, key)) if (Json.str(d, "id") != null) out.add(new Deal(d));
            return out;
        }
    }

    /** {@code QuotaView}: the two windows, gifts, a promotion, "≈ N yanıt kaldı". */
    public static final class QuotaView {
        public final long w5hUsed;
        public final long w5hCap;
        @Nullable
        public final Long w5hResetAt;
        public final long weekUsed;
        public final long weekCap;
        public final long weekResetAt;
        public final List<long[]> gifts;
        @Nullable
        public final Localized promoLabel;
        public final int promoPct;
        public final boolean frozen;
        public final long estReplies;
        public final int pct5;
        public final int pctWeek;
        public final long asOf;

        private QuotaView(JsonObject o) {
            JsonObject w5h = Json.obj(o, "w5h");
            w5hUsed = Json.num(w5h, "used", 0);
            w5hCap = Json.num(w5h, "cap", 0);
            w5hResetAt = Json.numOrNull(w5h, "resetAt");
            JsonObject week = Json.obj(o, "week");
            weekUsed = Json.num(week, "used", 0);
            weekCap = Json.num(week, "cap", 0);
            weekResetAt = Json.num(week, "resetAt", 0);
            List<long[]> g = new ArrayList<>();
            for (JsonObject gift : Json.objects(o, "gifts")) {
                Long expires = Json.numOrNull(gift, "expiresAt");
                g.add(new long[]{Json.num(gift, "remaining", 0), expires == null ? 0 : expires});
            }
            gifts = Collections.unmodifiableList(g);
            JsonObject promo = Json.obj(o, "promo");
            promoLabel = Localized.read(Json.obj(promo, "label"));
            promoPct = (int) Json.num(promo, "pct", 0);
            frozen = Json.bool(o, "frozen", false);
            estReplies = Json.num(o, "estReplies", 0);
            pct5 = (int) Json.num(o, "pct5", w5hCap > 0 ? Math.min(100, 100 * w5hUsed / w5hCap) : 0);
            pctWeek = (int) Json.num(o, "pctWeek", weekCap > 0 ? Math.min(100, 100 * weekUsed / weekCap) : 0);
            asOf = Json.num(o, "asOf", 0);
        }

        @Nullable
        public static QuotaView read(@Nullable JsonObject o) {
            return o == null ? null : new QuotaView(o);
        }

        public long giftsLeft() {
            long sum = 0;
            for (long[] g : gifts) sum += g[0];
            return sum;
        }
    }

    /** A persona text's judgement ({@code judge.intro}, {@code judge.status}). */
    public static final class Judge {
        public final String state;
        @Nullable
        public final String reason;

        Judge(String state, @Nullable String reason) {
            this.state = state;
            this.reason = reason;
        }

        static Judge read(@Nullable JsonObject o) {
            return new Judge(Json.str(o, "state", "ok"), Json.str(o, "reason"));
        }
    }

    /**
     * {@code GET /accounts/:id/plus}: the AFK+ settings as stored (kept whole as JSON: a {@code PUT} writes the
     * document back with only the player's changes, so nothing the phone does not know is lost or refused), with
     * their revision, the persona texts' judgement and the game mode's profile.
     */
    public static final class PlusDoc {
        public final JsonObject config;
        public final long aiRev;
        public final Judge intro;
        public final Judge status;
        @Nullable
        public final String profileId;
        @Nullable
        public final String profileName;
        public final boolean profileTrade;
        /** What the assistant has spent, when the machine answered (CAFK-W5); null otherwise. */
        @Nullable
        public final Spent spent;

        private PlusDoc(JsonObject o) {
            JsonObject c = Json.obj(o, "config");
            config = c == null ? new JsonObject() : c;
            aiRev = Json.num(o, "aiRev", 0);
            spent = Spent.read(o.get("spent"));
            JsonObject judge = Json.obj(o, "judge");
            intro = Judge.read(Json.obj(judge, "intro"));
            status = Judge.read(Json.obj(judge, "status"));
            JsonObject profile = Json.obj(o, "profile");
            profileId = Json.str(profile, "id");
            JsonElement name = profile == null ? null : profile.get("name");
            profileName = name == null ? null : name.isJsonPrimitive() ? name.getAsString()
                    : name.isJsonObject() ? Json.str(name.getAsJsonObject(), "tr") : null;
            profileTrade = Json.bool(profile, "trade", false);
        }

        @Nullable
        public static PlusDoc read(@Nullable JsonObject o) {
            return o == null || Json.obj(o, "config") == null ? null : new PlusDoc(o);
        }
    }

    /** {@code GET /plus/catalog}: the items the bot trades in a mode, with Turkish and English names. */
    public static final class PlusCatalog {
        public static final class Item {
            public final String id;
            public final String tr;
            public final String en;
            public final String sprite;
            public final int stack;
            public final boolean damageable;

            Item(JsonObject o) {
                id = Json.str(o, "id", "");
                tr = Json.str(o, "tr", id);
                en = Json.str(o, "en", tr);
                sprite = Json.str(o, "sprite", "");
                stack = (int) Json.num(o, "stack", 64);
                damageable = Json.bool(o, "damageable", false);
            }

            public String name(String lang) {
                return "en".equals(lang) ? en : tr;
            }
        }

        public static final class Enchantment {
            public final String id;
            public final String tr;
            public final String en;
            public final int max;

            Enchantment(JsonObject o) {
                id = Json.str(o, "id", "");
                tr = Json.str(o, "tr", id);
                en = Json.str(o, "en", tr);
                max = (int) Json.num(o, "max", 1);
            }

            public String name(String lang) {
                return "en".equals(lang) ? en : tr;
            }
        }

        public final String mode;
        @Nullable
        public final Localized name;
        public final List<Item> items;
        public final List<Enchantment> enchantments;
        public final String currency;
        public final long maxDeal;
        public final int listMax;
        public final int approvalTimeoutMin;
        public final int approvalTimeoutMax;
        private final Map<String, Item> mById = new LinkedHashMap<>();

        private PlusCatalog(JsonObject o) {
            mode = Json.str(o, "mode", "");
            name = Localized.read(Json.obj(o, "name"));
            List<Item> list = new ArrayList<>();
            for (JsonObject i : Json.objects(o, "items")) {
                Item item = new Item(i);
                if (item.id.isEmpty()) continue;
                list.add(item);
                mById.put(item.id, item);
            }
            items = Collections.unmodifiableList(list);
            List<Enchantment> e = new ArrayList<>();
            for (JsonObject en : Json.objects(o, "enchantments")) e.add(new Enchantment(en));
            enchantments = Collections.unmodifiableList(e);
            JsonObject money = Json.obj(o, "money");
            currency = Json.str(money, "currency", "");
            maxDeal = Json.num(money, "maxDeal", 10_000_000L);
            JsonObject limits = Json.obj(o, "limits");
            listMax = (int) Json.num(limits, "listMax", 10);
            int lo = 1;
            int hi = 60;
            if (limits != null && limits.has("approvalTimeoutMin") && limits.get("approvalTimeoutMin").isJsonArray()
                    && limits.getAsJsonArray("approvalTimeoutMin").size() == 2) {
                lo = limits.getAsJsonArray("approvalTimeoutMin").get(0).getAsInt();
                hi = limits.getAsJsonArray("approvalTimeoutMin").get(1).getAsInt();
            }
            approvalTimeoutMin = lo;
            approvalTimeoutMax = hi;
        }

        @Nullable
        public static PlusCatalog read(@Nullable JsonObject o) {
            return o == null ? null : new PlusCatalog(o);
        }

        @Nullable
        public Item item(String id) {
            return mById.get(id);
        }

        /** The item's name in {@code lang}; an id the catalog lacks reads without its {@code minecraft:} part. */
        public String itemName(String id, String lang) {
            Item item = mById.get(id);
            return item != null ? item.name(lang) : bareName(id);
        }

        public static String bareName(String id) {
            int colon = id.lastIndexOf(':');
            String bare = colon >= 0 ? id.substring(colon + 1) : id;
            return bare.replace('_', ' ');
        }
    }

    /** {@code NotifyPrefs}: which events tell the player, and the quiet hours. */
    public static final class NotifyPrefs {
        public static final String[] EVENTS = {"stopped", "renewal", "expired", "found", "approval", "deal_done", "deal_failed",
                "quota_full", "quota_80", "gift_ending", "summary", "paused", "incident", "message", "weekly"};

        public final Map<String, Boolean> events;
        public final boolean quiet;
        public final String from;
        public final String to;

        public NotifyPrefs(Map<String, Boolean> events, boolean quiet, String from, String to) {
            this.events = Collections.unmodifiableMap(new LinkedHashMap<>(events));
            this.quiet = quiet;
            this.from = from;
            this.to = to;
        }

        public static NotifyPrefs read(@Nullable JsonObject o) {
            Map<String, Boolean> events = new LinkedHashMap<>();
            JsonObject e = Json.obj(o, "events");
            if (e != null) {
                for (Map.Entry<String, JsonElement> entry : e.entrySet()) {
                    JsonElement v = entry.getValue();
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) events.put(entry.getKey(), v.getAsBoolean());
                }
            }
            JsonObject q = Json.obj(o, "quiet");
            return new NotifyPrefs(events, Json.bool(q, "enabled", true), Json.str(q, "from", "23:00"), Json.str(q, "to", "08:00"));
        }

        public boolean on(String event) {
            Boolean v = events.get(event);
            return v != null && v;
        }

        public NotifyPrefs with(String event, boolean on) {
            Map<String, Boolean> next = new LinkedHashMap<>(events);
            next.put(event, on);
            return new NotifyPrefs(next, quiet, from, to);
        }

        public NotifyPrefs withQuiet(boolean on, String from, String to) {
            return new NotifyPrefs(events, on, from, to);
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            JsonObject e = new JsonObject();
            for (Map.Entry<String, Boolean> entry : events.entrySet()) e.addProperty(entry.getKey(), entry.getValue());
            o.add("events", e);
            JsonObject q = new JsonObject();
            q.addProperty("enabled", quiet);
            q.addProperty("from", from);
            q.addProperty("to", to);
            o.add("quiet", q);
            return o;
        }
    }

    /** A conversation of "Mesajlar" ({@code InboxThread}, QoL Q1). */
    public static final class InboxThread {
        public final String name;
        public final long lastAt;
        public final String lastFrom;
        public final String lastText;
        public final int unread;
        public final String state;
        @Nullable
        public final Long handoffUntil;

        private InboxThread(JsonObject o) {
            name = Json.str(o, "name", "");
            lastAt = Json.num(o, "lastAt", 0);
            JsonObject last = Json.obj(o, "last");
            lastFrom = Json.str(last, "from", "them");
            lastText = Json.str(last, "text", "");
            unread = (int) Json.num(o, "unread", 0);
            state = Json.str(o, "state", "active");
            handoffUntil = Json.numOrNull(o, "handoffUntil");
        }

        public static List<InboxThread> list(@Nullable JsonObject o, String key) {
            List<InboxThread> out = new ArrayList<>();
            for (JsonObject t : Json.objects(o, key)) if (Json.str(t, "name") != null) out.add(new InboxThread(t));
            return out;
        }
    }

    public static final class InboxLine {
        public final long at;
        /** {@code them}, {@code bot} or {@code you}. */
        public final String from;
        public final String text;
        /** Folded until "Göster": the agent marked it unsafe. */
        public final boolean hidden;

        public InboxLine(long at, String from, String text, boolean hidden) {
            this.at = at;
            this.from = from;
            this.text = text;
            this.hidden = hidden;
        }

        @Nullable
        public static InboxLine read(@Nullable JsonObject o) {
            if (o == null) return null;
            return new InboxLine(Json.num(o, "at", 0), Json.str(o, "from", "them"), Json.str(o, "text", ""), Json.str(o, "hidden") != null);
        }

        public static List<InboxLine> list(@Nullable JsonObject o, String key) {
            List<InboxLine> out = new ArrayList<>();
            for (JsonObject l : Json.objects(o, key)) out.add(read(l));
            return out;
        }
    }

    public static final class InboxPage {
        public final List<InboxLine> lines;
        @Nullable
        public final Long handoffUntil;
        public final boolean canReply;
        @Nullable
        public final String next;

        private InboxPage(JsonObject o) {
            lines = InboxLine.list(o, "lines");
            handoffUntil = Json.numOrNull(o, "handoffUntil");
            canReply = Json.bool(o, "canReply", false);
            next = Json.str(o, "next");
        }

        public static InboxPage read(@Nullable JsonObject o) {
            return new InboxPage(o == null ? new JsonObject() : o);
        }
    }

    /** {@code ParseDraft}: a rule's draft from one typed sentence (QoL Q2), never stored by itself. */
    public static final class ParseDraft {
        public final String side;
        @Nullable
        public final String item;
        public final List<String> candidates;
        public final long qty;
        public final String unit;
        @Nullable
        public final Long target;
        @Nullable
        public final Long max;
        @Nullable
        public final Long min;
        public final String style;
        public final String approval;
        public final List<String> issues;

        private ParseDraft(JsonObject draft, List<String> issues) {
            side = Json.str(draft, "side", "buy");
            item = Json.str(draft, "item");
            candidates = Json.strings(draft, "candidates");
            qty = Math.max(1, Json.num(draft, "qty", 1));
            unit = Json.str(draft, "unit", "total");
            target = Json.numOrNull(draft, "target");
            max = Json.numOrNull(draft, "max");
            min = Json.numOrNull(draft, "min");
            style = Json.str(draft, "style", "balanced");
            approval = Json.str(draft, "approval", "above");
            this.issues = issues;
        }

        @Nullable
        public static ParseDraft read(@Nullable JsonObject o) {
            JsonObject draft = Json.obj(o, "draft");
            return draft == null ? null : new ParseDraft(draft, Json.strings(o, "issues"));
        }
    }

    /** A saved revision of the AFK+ settings (QoL Q7). */
    public static final class HistoryEntry {
        public final long aiRev;
        public final long at;
        public final String by;
        public final JsonObject config;

        private HistoryEntry(JsonObject o) {
            aiRev = Json.num(o, "aiRev", 0);
            at = Json.num(o, "at", 0);
            by = Json.str(o, "by", "player");
            JsonObject c = Json.obj(o, "config");
            config = c == null ? new JsonObject() : c;
        }

        public static List<HistoryEntry> list(@Nullable JsonObject o, String key) {
            List<HistoryEntry> out = new ArrayList<>();
            for (JsonObject e : Json.objects(o, key)) out.add(new HistoryEntry(e));
            return out;
        }
    }

    /** One line of the trade summary (QoL Q4). */
    public static final class ItemSummary {
        public final String side;
        public final String item;
        public final String currency;
        public final int deals;
        public final long qty;
        public final long total;
        public final long avgUnit;
        public final long minUnit;
        public final long maxUnit;

        private ItemSummary(JsonObject o) {
            side = Json.str(o, "side", "buy");
            item = Json.str(o, "item", "");
            currency = Json.str(o, "currency", "");
            deals = (int) Json.num(o, "deals", 0);
            qty = Json.num(o, "qty", 0);
            total = Json.num(o, "total", 0);
            avgUnit = Json.num(o, "avgUnit", 0);
            minUnit = Json.num(o, "minUnit", 0);
            maxUnit = Json.num(o, "maxUnit", 0);
        }

        public static List<ItemSummary> list(@Nullable JsonObject o, String key) {
            List<ItemSummary> out = new ArrayList<>();
            for (JsonObject s : Json.objects(o, key)) out.add(new ItemSummary(s));
            return out;
        }
    }

    /** The weekly report of one AFK+ account (QoL Q4). */
    public static final class WeeklyAccount {
        public final String id;
        public final String name;
        public final long pms;
        public final long bought;
        public final long sold;
        public final long spent;
        public final long earned;
        public final long belowLimit;
        public final long aboveFloor;
        public final long blocked;
        public final int allowancePct;
        @Nullable
        public final String currency;

        private WeeklyAccount(JsonObject o) {
            id = Json.str(o, "id", "");
            name = Json.str(o, "name", "");
            pms = Json.num(o, "pms", 0);
            bought = Json.num(o, "bought", 0);
            sold = Json.num(o, "sold", 0);
            spent = Json.num(o, "spent", 0);
            earned = Json.num(o, "earned", 0);
            belowLimit = Json.num(o, "belowLimit", 0);
            aboveFloor = Json.num(o, "aboveFloor", 0);
            JsonObject b = Json.obj(o, "blocked");
            blocked = Json.num(b, "scam", 0) + Json.num(b, "rmt", 0) + Json.num(b, "unsafe", 0) + Json.num(b, "fake", 0);
            allowancePct = (int) Json.num(o, "allowancePct", 0);
            currency = Json.str(o, "currency");
        }

        public static List<WeeklyAccount> list(@Nullable JsonObject o) {
            List<WeeklyAccount> out = new ArrayList<>();
            for (JsonObject a : Json.objects(o, "accounts")) out.add(new WeeklyAccount(a));
            return out;
        }
    }

    /** {@code DowngradePreview}. */
    public static final class DowngradePreview {
        public final long plainGainSeconds;
        public final boolean allowed;
        public final long retryAfter;
        public final long afterExpiresAt;

        private DowngradePreview(JsonObject o) {
            plainGainSeconds = Json.num(o, "plainGainSeconds", 0);
            allowed = Json.bool(o, "allowed", false);
            retryAfter = Json.num(o, "retryAfter", 0);
            afterExpiresAt = Json.num(Json.obj(o, "after"), "expiresAt", 0);
        }

        @Nullable
        public static DowngradePreview read(@Nullable JsonObject o) {
            return o == null ? null : new DowngradePreview(o);
        }
    }
}
