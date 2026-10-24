package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gg.projects.mobile.social.Json;

/**
 * An AFK+ account's settings ({@code AfkPlusConfig} v1, services/cloud-afk README) as the phone edits them: a copy of
 * the stored document changed in place, so a {@code PUT} writes back exactly what the service gave with only the
 * player's changes (fields the phone does not know stay, nothing unknown is ever added). Typed accessors, new rows in
 * the contract's shape, and the checks the Worker makes, before anything is sent ({@code invalid_plus_config}).
 */
public final class PlusConfig {
    public static final int LIST_MAX = 10;
    public static final int BLOCKED_MAX = 100;
    public static final int WINDOWS_MAX = 7;
    public static final int INTRO_MAX = 60;
    public static final int STATUS_MAX = 80;
    public static final int QTY_MAX = 9999;
    public static final long PAUSE_MAX_SECONDS = 90L * 86_400;
    public static final String[] PRESETS = {"friendly", "brief", "polite", "playful"};
    public static final String[] STATUS_PRESETS = {"back_soon", "back_evening", "back_tomorrow", "weekend", "busy"};
    public static final String[] STYLES = {"patient", "balanced", "quick"};
    public static final String[] APPROVALS = {"auto", "above", "always"};

    private final JsonObject mDoc;

    public PlusConfig(JsonObject stored) {
        mDoc = stored.deepCopy();
    }

    /** The document to send (the stored one with the changes). */
    public JsonObject toJson() {
        return mDoc.deepCopy();
    }

    public PlusConfig copy() {
        return new PlusConfig(mDoc);
    }

    public boolean same(PlusConfig other) {
        return mDoc.equals(other.mDoc);
    }

    private JsonObject obj(String key) {
        JsonObject o = Json.obj(mDoc, key);
        if (o == null) {
            o = new JsonObject();
            mDoc.add(key, o);
        }
        return o;
    }

    private static void setNullable(JsonObject o, String key, @Nullable Long value) {
        if (value == null) o.add(key, JsonNull.INSTANCE);
        else o.addProperty(key, value);
    }

    // ---- the assistant ----

    public boolean enabled() {
        return Json.bool(mDoc, "enabled", true);
    }

    public void setEnabled(boolean on) {
        mDoc.addProperty("enabled", on);
    }

    public String mode() {
        return Json.str(mDoc, "mode", "");
    }

    public String lang() {
        return Json.str(mDoc, "lang", "auto");
    }

    public void setLang(String lang) {
        mDoc.addProperty("lang", lang);
    }

    public String preset() {
        return Json.str(Json.obj(mDoc, "persona"), "preset", "friendly");
    }

    public void setPreset(String preset) {
        obj("persona").addProperty("preset", preset);
    }

    public String intro() {
        return Json.str(Json.obj(mDoc, "persona"), "intro", "");
    }

    public void setIntro(String intro) {
        obj("persona").addProperty("intro", intro);
    }

    public String status() {
        return Json.str(Json.obj(mDoc, "persona"), "status", "");
    }

    /** A text of the player's own ("Kendim yazacağım"): the ready status goes. */
    public void setStatus(String status) {
        JsonObject persona = obj("persona");
        persona.addProperty("status", status);
        persona.add("statusPreset", JsonNull.INSTANCE);
    }

    @Nullable
    public String statusPreset() {
        return Json.str(Json.obj(mDoc, "persona"), "statusPreset");
    }

    /** A ready status (QoL Q3): while one is set the stored text is empty. */
    public void setStatusPreset(@Nullable String preset) {
        JsonObject persona = obj("persona");
        if (preset == null) {
            persona.add("statusPreset", JsonNull.INSTANCE);
        } else {
            persona.addProperty("statusPreset", preset);
            persona.addProperty("status", "");
        }
    }

    public boolean smallTalk() {
        return Json.bool(Json.obj(mDoc, "persona"), "smallTalk", true);
    }

    public void setSmallTalk(boolean on) {
        obj("persona").addProperty("smallTalk", on);
    }

    /** {@code all}, {@code trade} or {@code off}. */
    public String scope() {
        return Json.str(Json.obj(mDoc, "replies"), "scope", "trade");
    }

    public void setScope(String scope) {
        obj("replies").addProperty("scope", scope);
    }

    public boolean away() {
        return Json.bool(Json.obj(mDoc, "replies"), "away", true);
    }

    public void setAway(boolean on) {
        obj("replies").addProperty("away", on);
    }

    /** "Hakkı koru" (QoL Q9): 50, 80 or null (off). */
    @Nullable
    public Integer saveAt() {
        JsonObject replies = Json.obj(mDoc, "replies");
        if (replies == null || !replies.has("saveAt")) return 80;
        Long v = Json.numOrNull(replies, "saveAt");
        return v == null ? null : v.intValue();
    }

    public void setSaveAt(@Nullable Integer pct) {
        JsonObject replies = obj("replies");
        if (pct == null) replies.add("saveAt", JsonNull.INSTANCE);
        else replies.addProperty("saveAt", pct);
    }

    public boolean scanChat() {
        return Json.bool(Json.obj(mDoc, "discovery"), "scanChat", true);
    }

    public void setScanChat(boolean on) {
        obj("discovery").addProperty("scanChat", on);
    }

    public boolean proactivePm() {
        return Json.bool(Json.obj(mDoc, "discovery"), "proactivePm", true);
    }

    public void setProactivePm(boolean on) {
        obj("discovery").addProperty("proactivePm", on);
    }

    public String summaryAt() {
        return Json.str(mDoc, "summaryAt", "21:00");
    }

    public void setSummaryAt(String time) {
        mDoc.addProperty("summaryAt", time);
    }

    @Nullable
    public String returnRow() {
        return Json.str(Json.obj(mDoc, "trade"), "returnRow");
    }

    public void setReturnRow(@Nullable String row) {
        JsonObject trade = obj("trade");
        if (row == null) trade.add("returnRow", JsonNull.INSTANCE);
        else trade.addProperty("returnRow", row);
    }

    // ---- blocked players ----

    public List<String> blocked() {
        return Json.strings(mDoc, "blocked");
    }

    public void setBlocked(List<String> names) {
        JsonArray a = new JsonArray();
        for (String n : names) a.add(n);
        mDoc.add("blocked", a);
    }

    // ---- limits ----

    @Nullable
    public Long limit(String key) {
        return Json.numOrNull(Json.obj(mDoc, "limits"), key);
    }

    public void setLimit(String key, @Nullable Long value) {
        setNullable(obj("limits"), key, value);
    }

    public long balanceFloor() {
        return Json.num(Json.obj(mDoc, "limits"), "balanceFloor", 0);
    }

    public int approvalTimeoutMin() {
        return (int) Json.num(Json.obj(mDoc, "limits"), "approvalTimeoutMin", 10);
    }

    /** Buy rows only report what they find until a budget and a per-deal ceiling are set. */
    public boolean canSpend() {
        return limit("totalBudget") != null && limit("perDealMax") != null;
    }

    // ---- schedule and break (QoL Q3) ----

    public boolean scheduleOn() {
        return Json.bool(Json.obj(mDoc, "schedule"), "on", false);
    }

    public void setScheduleOn(boolean on) {
        JsonObject schedule = obj("schedule");
        schedule.addProperty("on", on);
        if (!schedule.has("windows")) schedule.add("windows", new JsonArray());
    }

    public static final class Window {
        public final List<Integer> days;
        public final String from;
        public final String to;

        public Window(List<Integer> days, String from, String to) {
            this.days = days;
            this.from = from;
            this.to = to;
        }
    }

    public List<Window> windows() {
        List<Window> out = new ArrayList<>();
        for (JsonObject w : Json.objects(Json.obj(mDoc, "schedule"), "windows")) {
            List<Integer> days = new ArrayList<>();
            for (JsonElement d : Json.arr(w, "days")) if (d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber()) days.add(d.getAsInt());
            out.add(new Window(days, Json.str(w, "from", "18:00"), Json.str(w, "to", "23:00")));
        }
        return out;
    }

    public void setWindows(List<Window> windows) {
        JsonArray a = new JsonArray();
        for (Window w : windows) {
            JsonObject o = new JsonObject();
            JsonArray days = new JsonArray();
            List<Integer> sorted = new ArrayList<>(new java.util.TreeSet<>(w.days));
            for (int d : sorted) days.add(d);
            o.add("days", days);
            o.addProperty("from", w.from);
            o.addProperty("to", w.to);
            a.add(o);
        }
        JsonObject schedule = obj("schedule");
        if (!schedule.has("on")) schedule.addProperty("on", false);
        schedule.add("windows", a);
    }

    @Nullable
    public Long pauseUntil() {
        return Json.numOrNull(mDoc, "pauseUntil");
    }

    public void setPauseUntil(@Nullable Long until) {
        setNullable(mDoc, "pauseUntil", until);
    }

    // ---- buy and sell rows ----

    /** A buy or sell row (a view on the document: changes go into it). */
    public static final class Row {
        public final boolean buy;
        final JsonObject o;

        Row(boolean buy, JsonObject o) {
            this.buy = buy;
            this.o = o;
        }

        public String id() {
            return Json.str(o, "id", "");
        }

        public String item() {
            return Json.str(o, "item", "");
        }

        public void setItem(String item) {
            o.addProperty("item", item);
        }

        public long qty() {
            return Json.num(o, "qty", 1);
        }

        public void setQty(long qty) {
            o.addProperty("qty", qty);
        }

        /** {@code each} or {@code total}: whether the amounts are per item or for {@link #qty} items. */
        public String unit() {
            return Json.str(o, "unit", "total");
        }

        public void setUnit(String unit) {
            o.addProperty("unit", unit);
        }

        public long target() {
            return Json.num(o, "target", 0);
        }

        public void setTarget(long value) {
            o.addProperty("target", value);
        }

        /** Buy: the ceiling ({@code max}); sell: the floor ({@code min}). */
        public long bound() {
            return Json.num(o, buy ? "max" : "min", 0);
        }

        public void setBound(long value) {
            o.addProperty(buy ? "max" : "min", value);
        }

        public String style() {
            return Json.str(o, "style", "balanced");
        }

        public void setStyle(String style) {
            o.addProperty("style", style);
        }

        public String approval() {
            return Json.str(o, "approval", "above");
        }

        public void setApproval(String approval) {
            o.addProperty("approval", approval);
        }

        public long dailyQty() {
            return Json.num(o, "dailyQty", 1);
        }

        public void setDailyQty(long qty) {
            o.addProperty("dailyQty", qty);
        }

        public boolean notifyFound() {
            return Json.bool(o, "notify", true);
        }

        public void setNotifyFound(boolean on) {
            if (buy) o.addProperty("notify", on);
        }

        public boolean enabled() {
            return Json.bool(o, "enabled", true);
        }

        public void setEnabled(boolean on) {
            o.addProperty("enabled", on);
        }

        public int minDurabilityPct() {
            return (int) Json.num(Json.obj(o, "req"), "minDurabilityPct", 0);
        }

        public void setMinDurabilityPct(int pct) {
            if (!buy) return;
            req().addProperty("minDurabilityPct", pct);
        }

        public boolean allowCustomName() {
            return Json.bool(Json.obj(o, "req"), "allowCustomName", false);
        }

        public void setAllowCustomName(boolean on) {
            if (buy) req().addProperty("allowCustomName", on);
        }

        public boolean allowCurses() {
            return Json.bool(Json.obj(o, "req"), "allowCurses", false);
        }

        public void setAllowCurses(boolean on) {
            if (buy) req().addProperty("allowCurses", on);
        }

        private JsonObject req() {
            JsonObject req = Json.obj(o, "req");
            if (req == null) {
                req = new JsonObject();
                req.add("ench", new JsonObject());
                req.addProperty("minDurabilityPct", 0);
                req.addProperty("allowCustomName", false);
                req.addProperty("allowCurses", false);
                o.add("req", req);
            }
            return req;
        }

        public JsonObject json() {
            return o.deepCopy();
        }
    }

    public List<Row> rows(boolean buy) {
        List<Row> out = new ArrayList<>();
        JsonArray a = Json.arr(mDoc, buy ? "buy" : "sell");
        for (JsonElement e : a) if (e.isJsonObject()) out.add(new Row(buy, e.getAsJsonObject()));
        return out;
    }

    private JsonArray list(boolean buy) {
        String key = buy ? "buy" : "sell";
        JsonElement e = mDoc.get(key);
        if (e == null || !e.isJsonArray()) {
            JsonArray a = new JsonArray();
            mDoc.add(key, a);
            return a;
        }
        return e.getAsJsonArray();
    }

    /** The ids of both lists (unique across them). */
    public Set<String> rowIds() {
        Set<String> ids = new HashSet<>();
        for (Row r : rows(true)) ids.add(r.id());
        for (Row r : rows(false)) ids.add(r.id());
        return ids;
    }

    /** A new row id: {@code ^[a-z0-9]{1,8}$}, unique across buy and sell. */
    public String newRowId(java.util.Random random) {
        Set<String> taken = rowIds();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int tries = 0; tries < 1000; tries++) {
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 6; i++) id.append(alphabet.charAt(random.nextInt(alphabet.length())));
            if (!taken.contains(id.toString())) return id.toString();
        }
        throw new IllegalStateException("no free id");
    }

    /** A new row in the contract's shape (the defaults the site uses). */
    public static JsonObject newRow(boolean buy, String id, String item, long qty, String unit, long target, long bound, String style,
                                    String approval) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("item", item);
        if (buy) {
            JsonObject req = new JsonObject();
            req.add("ench", new JsonObject());
            req.addProperty("minDurabilityPct", 0);
            req.addProperty("allowCustomName", false);
            req.addProperty("allowCurses", false);
            o.add("req", req);
        }
        o.addProperty("qty", qty);
        o.addProperty("unit", unit);
        o.addProperty("target", target);
        o.addProperty(buy ? "max" : "min", bound);
        o.addProperty("style", style);
        o.addProperty("approval", approval);
        o.addProperty("dailyQty", Math.max(1, Math.min(QTY_MAX, buy ? qty : qty * 2)));
        if (buy) o.addProperty("notify", true);
        o.addProperty("enabled", true);
        return o;
    }

    public void addRow(boolean buy, JsonObject row) {
        list(buy).add(row);
    }

    public void replaceRow(boolean buy, String id, JsonObject row) {
        JsonArray a = list(buy);
        for (int i = 0; i < a.size(); i++) {
            JsonElement e = a.get(i);
            if (e.isJsonObject() && id.equals(Json.str(e.getAsJsonObject(), "id"))) {
                a.set(i, row);
                return;
            }
        }
        a.add(row);
    }

    public void removeRow(boolean buy, String id) {
        JsonArray a = list(buy);
        for (int i = a.size() - 1; i >= 0; i--) {
            JsonElement e = a.get(i);
            if (e.isJsonObject() && id.equals(Json.str(e.getAsJsonObject(), "id"))) a.remove(i);
        }
    }

    @Nullable
    public Row row(boolean buy, String id) {
        for (Row r : rows(buy)) if (r.id().equals(id)) return r;
        return null;
    }

    // ---- checks (the Worker's rules; it and the agent have the last word) ----

    /** Where a problem is: {@code field} the document's path ({@code buy[2].max}), {@code reason} a key the screen words. */
    public static final class Problem {
        public final String field;
        public final String reason;

        Problem(String field, String reason) {
            this.field = field;
            this.reason = reason;
        }

        /** The list and row a path names ({@code buy}, 2), or null for a field outside the lists. */
        @Nullable
        public String list() {
            Matcher m = ROW_PATH.matcher(field);
            return m.find() ? m.group(1) : null;
        }

        public int index() {
            Matcher m = ROW_PATH.matcher(field);
            return m.find() ? Integer.parseInt(m.group(2)) : -1;
        }

        @Override
        public String toString() {
            return field + ":" + reason;
        }
    }

    private static final Pattern ROW_PATH = Pattern.compile("^(buy|sell)\\[(\\d+)]");
    private static final Pattern ROW_ID = Pattern.compile("^[a-z0-9]{1,8}$");

    /** The problems the service would refuse, found here; {@code catalog} may be null (items are not checked then). */
    public List<Problem> check(@Nullable CloudModel.PlusCatalog catalog, long now) {
        List<Problem> problems = new ArrayList<>();
        long maxDeal = catalog == null ? 10_000_000L : catalog.maxDeal;
        int listMax = catalog == null ? LIST_MAX : Math.min(LIST_MAX, catalog.listMax);
        Set<String> ids = new HashSet<>();
        for (int pass = 0; pass < 2; pass++) {
            boolean buy = pass == 0;
            String key = buy ? "buy" : "sell";
            List<Row> rows = rows(buy);
            if (rows.size() > listMax) problems.add(new Problem(key, "limit"));
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                String path = key + "[" + i + "]";
                if (!ROW_ID.matcher(r.id()).matches() || !ids.add(r.id())) problems.add(new Problem(path + ".id", "id"));
                if (catalog != null && catalog.item(r.item()) == null) problems.add(new Problem(path + ".item", "item"));
                if (r.qty() < 1 || r.qty() > QTY_MAX) problems.add(new Problem(path + ".qty", "range"));
                if (r.target() < 1 || r.target() > maxDeal) problems.add(new Problem(path + ".target", "range"));
                if (r.bound() < 1 || r.bound() > maxDeal) problems.add(new Problem(path + (buy ? ".max" : ".min"), "range"));
                else if (buy ? r.target() > r.bound() : r.bound() > r.target()) problems.add(new Problem(path + (buy ? ".max" : ".min"), "order"));
                if (r.dailyQty() < 1 || r.dailyQty() > QTY_MAX) problems.add(new Problem(path + ".dailyQty", "range"));
            }
        }
        Long perDeal = limit("perDealMax");
        Long approvalAbove = limit("approvalAbove");
        for (String k : new String[]{"perDealMax", "dailySpendMax", "totalBudget", "approvalAbove", "newCounterpartApprovalAbove"}) {
            Long v = limit(k);
            // The Worker refuses any amount above the profile's money.maxDeal.
            if (v != null && (v < 1 || v > maxDeal)) problems.add(new Problem("limits." + k, "range"));
        }
        if (perDeal != null && approvalAbove != null && approvalAbove > perDeal) problems.add(new Problem("limits.approvalAbove", "limits"));
        if (balanceFloor() < 0) problems.add(new Problem("limits.balanceFloor", "range"));
        int timeout = approvalTimeoutMin();
        int lo = catalog == null ? 1 : catalog.approvalTimeoutMin;
        int hi = catalog == null ? 60 : catalog.approvalTimeoutMax;
        if (timeout < lo || timeout > hi) problems.add(new Problem("limits.approvalTimeoutMin", "range"));
        List<String> blocked = blocked();
        if (blocked.size() > BLOCKED_MAX) problems.add(new Problem("blocked", "limit"));
        Set<String> names = new HashSet<>();
        for (int i = 0; i < blocked.size(); i++) {
            String n = blocked.get(i);
            if (!CloudEdit.isGameName(n) || !names.add(n.toLowerCase(Locale.ROOT))) problems.add(new Problem("blocked[" + i + "]", "name"));
        }
        if (intro().codePointCount(0, intro().length()) > INTRO_MAX) problems.add(new Problem("persona.intro", "text"));
        if (status().codePointCount(0, status().length()) > STATUS_MAX) problems.add(new Problem("persona.status", "text"));
        List<Window> windows = windows();
        if (windows.size() > WINDOWS_MAX) problems.add(new Problem("schedule.windows", "range"));
        if (scheduleOn() && windows.isEmpty()) problems.add(new Problem("schedule.windows", "range"));
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.get(i);
            Set<Integer> distinct = new HashSet<>(w.days);
            boolean daysOk = !w.days.isEmpty() && distinct.size() == w.days.size();
            for (int d : w.days) if (d < 1 || d > 7) daysOk = false;
            if (!daysOk) problems.add(new Problem("schedule.windows[" + i + "].days", "range"));
            if (!CloudEdit.isClock(w.from) || !CloudEdit.isClock(w.to)) problems.add(new Problem("schedule.windows[" + i + "]", "range"));
        }
        Long pause = pauseUntil();
        if (pause != null && pause > now + PAUSE_MAX_SECONDS) problems.add(new Problem("pauseUntil", "range"));
        return problems;
    }

    /** "En fazla" given alone: the target is 80 % of it, rounded to the profile's steps (the concept's "Hedef 32.000"). */
    public static long suggestedTarget(long max) {
        long target = Math.round(max * 0.8);
        long step = target >= 10_000 ? 500 : 50;
        return Math.max(1, (target / step) * step);
    }

    /** Whether a typed number is an in-game amount (digits, with dots or commas as group marks, or "40k"). */
    @Nullable
    public static Long parseAmount(@Nullable String typed) {
        if (typed == null) return null;
        String t = typed.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (t.isEmpty()) return null;
        long factor = 1;
        if (t.endsWith("k")) {
            factor = 1000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            factor = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        if (factor > 1) {
            t = t.replace(',', '.');
            if (!t.matches("^\\d+(\\.\\d{1,3})?$")) return null;
            double v = Double.parseDouble(t) * factor;
            return v < 1 || v > 1e12 ? null : Math.round(v);
        }
        String digits = t.replace(".", "").replace(",", "");
        if (!digits.matches("^\\d{1,12}$")) return null;
        long v = Long.parseLong(digits);
        return v < 1 ? null : v;
    }

    /** Studio's "booleans" and numbers as the screens show them: an unknown key reads as missing. */
    static boolean isNumber(@Nullable JsonElement e) {
        return e != null && e.isJsonPrimitive() && ((JsonPrimitive) e).isNumber();
    }
}
