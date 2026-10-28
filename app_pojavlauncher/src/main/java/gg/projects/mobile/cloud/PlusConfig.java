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

import gg.projects.mobile.social.Json;

/**
 * An AFK+ account's settings ({@code AfkPlusConfig} v2, services/cloud-afk README "AfkPlusConfig v2") as the phone's
 * settings screens edit them: a copy of the stored document changed in place, so a {@code PUT} writes back what the
 * service gave with only the player's changes (fields the phone does not know stay, nothing unknown is ever added).
 * Orders and custom items have their own routes: {@link #settingsJson()} leaves them out of a {@code PUT} (the stored
 * ones stay), and {@link #orders()} / {@link #customItems()} only read them. The checks are the Worker's for the fields
 * these screens edit, before anything is sent; the Worker and the agent have the last word ({@code invalid_plus_config}).
 */
public final class PlusConfig {
    public static final int BLOCKED_MAX = 100;
    public static final int FRIENDS_MAX = 50;
    public static final int WINDOWS_MAX = 7;
    public static final int INTRO_MAX = 60;
    public static final int STATUS_MAX = 80;
    public static final int TAG_MAX = 16;
    public static final String TAG_DEFAULT = "[AFK+]";
    public static final int QTY_MAX = 9999;
    public static final long PAUSE_MAX_SECONDS = 90L * 86_400;
    public static final String[] PRESETS = {"friendly", "brief", "polite", "playful"};
    public static final String[] STATUS_PRESETS = {"back_soon", "back_evening", "back_tomorrow", "weekend", "busy"};
    public static final String[] STYLES = {"patient", "balanced", "quick"};
    public static final String[] AUTHORITIES = {"reply", "ask", "deal"};
    public static final String[] PAYS = {"money", "items", "both"};

    private final JsonObject mDoc;

    public PlusConfig(JsonObject stored) {
        mDoc = stored.deepCopy();
    }

    /** The whole document (the stored one with the changes). */
    public JsonObject toJson() {
        return mDoc.deepCopy();
    }

    /** What a settings screen sends ({@code PUT}): the document without its orders and custom items (the stored ones stay). */
    public JsonObject settingsJson() {
        JsonObject out = mDoc.deepCopy();
        out.remove("orders");
        out.remove("customItems");
        return out;
    }

    public PlusConfig copy() {
        return new PlusConfig(mDoc);
    }

    /** Whether the settings a screen edits are the same (orders and custom items change through their own routes). */
    public boolean same(PlusConfig other) {
        return settingsJson().equals(other.settingsJson());
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

    /** Small talk on; "Konu dışına çıkmasın" is its opposite (default: small talk off, the switch on). */
    public boolean smallTalk() {
        return Json.bool(Json.obj(mDoc, "persona"), "smallTalk", false);
    }

    public void setSmallTalk(boolean on) {
        obj("persona").addProperty("smallTalk", on);
    }

    /** "Oyuncu gibi konuşsun": chats like a player, not in formal language (default off). */
    public boolean playerTalk() {
        return Json.bool(Json.obj(mDoc, "persona"), "playerTalk", false);
    }

    public void setPlayerTalk(boolean on) {
        obj("persona").addProperty("playerTalk", on);
    }

    /** "Mesajlarının başına etiket koysun": the player's choice, off by default. */
    public boolean tagOn() {
        return Json.bool(Json.obj(Json.obj(mDoc, "persona"), "prefix"), "on", false);
    }

    public String tagText() {
        return Json.str(Json.obj(Json.obj(mDoc, "persona"), "prefix"), "text", TAG_DEFAULT);
    }

    /** Sets the tag; a document without one gets it only when the player changes it (an older service never sees the field). */
    public void setTag(boolean on, String text) {
        JsonObject prefix = new JsonObject();
        prefix.addProperty("on", on);
        prefix.addProperty("text", text);
        obj("persona").add("prefix", prefix);
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

    // ---- fine-tuning ("İnce ayar") ----

    /** "Asistan ne yapsın?": {@code reply}, {@code ask} (default) or {@code deal}. */
    public String authority() {
        String a = Json.str(mDoc, "authority", "ask");
        return "reply".equals(a) || "deal".equals(a) ? a : "ask";
    }

    public void setAuthority(String authority) {
        mDoc.addProperty("authority", authority);
    }

    /** "Alış" / "Satış": an order of a side switched off waits. */
    public boolean allows(String side) {
        return Json.bool(Json.obj(mDoc, "allow"), "sell".equals(side) ? "sell" : "buy", true);
    }

    public void setAllows(String side, boolean on) {
        obj("allow").addProperty("sell".equals(side) ? "sell" : "buy", on);
    }

    /** What deals may be paid with: {@code money} (default), {@code items} or {@code both}. */
    public String pay() {
        String p = Json.str(mDoc, "pay", "money");
        return "items".equals(p) || "both".equals(p) ? p : "money";
    }

    public void setPay(String pay) {
        mDoc.addProperty("pay", pay);
    }

    /** "Kimlerle": {@code all} or {@code friends}. */
    public String whoMode() {
        return "friends".equals(Json.str(Json.obj(mDoc, "who"), "mode")) ? "friends" : "all";
    }

    public List<String> friends() {
        return Json.strings(Json.obj(mDoc, "who"), "friends");
    }

    public void setWho(String mode, List<String> friends) {
        JsonObject who = obj("who");
        who.addProperty("mode", mode);
        JsonArray a = new JsonArray();
        for (String n : friends) a.add(n);
        who.add("friends", a);
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

    /** Buy orders for money the bot would trade itself only report what they find until both limits are set. */
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

    // ---- orders and custom items (read only here: they have their own routes) ----

    /** The orders as stored (copies), in the document's order. */
    public List<JsonObject> orders() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject o : Json.objects(mDoc, "orders")) out.add(o.deepCopy());
        return out;
    }

    /** The order {@code id} (a copy), or null. */
    @Nullable
    public JsonObject order(String id) {
        for (JsonObject o : Json.objects(mDoc, "orders")) if (id.equals(Json.str(o, "id"))) return o.deepCopy();
        return null;
    }

    public List<JsonObject> customItems() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject o : Json.objects(mDoc, "customItems")) out.add(o.deepCopy());
        return out;
    }

    /** The custom item {@code k} (a copy), or null. */
    @Nullable
    public JsonObject customItem(String k) {
        for (JsonObject o : Json.objects(mDoc, "customItems")) if (k.equals(Json.str(o, "k"))) return o.deepCopy();
        return null;
    }

    /** A new custom item key ({@code ^c[0-9a-z]{1,4}$}) that is in neither this document nor {@code taken}. */
    public String newCustomKey(java.util.Random random, Set<String> taken) {
        Set<String> used = new HashSet<>(taken);
        for (JsonObject o : Json.objects(mDoc, "customItems")) used.add(Json.str(o, "k", ""));
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int tries = 0; tries < 1000; tries++) {
            StringBuilder k = new StringBuilder("c");
            for (int i = 0; i < 4; i++) k.append(alphabet.charAt(random.nextInt(alphabet.length())));
            if (!used.contains(k.toString())) return k.toString();
        }
        throw new IllegalStateException("no free custom item key");
    }

    // ---- checks (the Worker's rules for the fields the settings screens edit; it and the agent have the last word) ----

    /** Where a problem is: {@code field} the document's path ({@code limits.perDealMax}), {@code reason} a key the screen words. */
    public static final class Problem {
        public final String field;
        public final String reason;

        Problem(String field, String reason) {
            this.field = field;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return field + ":" + reason;
        }
    }

    /**
     * The problems the service would refuse in the settings, found here: amounts within the mode's money ({@code maxDeal}),
     * the approval limit not above the per-deal limit, the approval time within {@code [timeoutMin, timeoutMax]}, the
     * names (blocked, friends: in-game names, each once, a friend not blocked), persona text lengths, the tag rule's
     * length, working hours and the break. A tag's own words are checked by {@code PlusText} while the player types.
     */
    public List<Problem> check(long maxDeal, int timeoutMin, int timeoutMax, long now) {
        List<Problem> problems = new ArrayList<>();
        Long perDeal = limit("perDealMax");
        Long approvalAbove = limit("approvalAbove");
        for (String k : new String[]{"perDealMax", "dailySpendMax", "totalBudget", "approvalAbove", "newCounterpartApprovalAbove"}) {
            Long v = limit(k);
            if (v != null && (v < 1 || v > maxDeal)) problems.add(new Problem("limits." + k, "range"));
        }
        if (perDeal != null && approvalAbove != null && approvalAbove > perDeal) problems.add(new Problem("limits.approvalAbove", "limits"));
        if (balanceFloor() < 0 || balanceFloor() > maxDeal) problems.add(new Problem("limits.balanceFloor", "range"));
        int timeout = approvalTimeoutMin();
        if (timeout < timeoutMin || timeout > timeoutMax) problems.add(new Problem("limits.approvalTimeoutMin", "range"));
        List<String> blocked = blocked();
        if (blocked.size() > BLOCKED_MAX) problems.add(new Problem("blocked", "limit"));
        Set<String> blockedNames = new HashSet<>();
        for (int i = 0; i < blocked.size(); i++) {
            String n = blocked.get(i);
            if (!CloudEdit.isGameName(n) || !blockedNames.add(n.toLowerCase(Locale.ROOT))) problems.add(new Problem("blocked[" + i + "]", "name"));
        }
        List<String> friends = friends();
        if (friends.size() > FRIENDS_MAX) problems.add(new Problem("who.friends", "limit"));
        Set<String> friendNames = new HashSet<>();
        for (int i = 0; i < friends.size(); i++) {
            String n = friends.get(i);
            String low = n.toLowerCase(Locale.ROOT);
            if (!CloudEdit.isGameName(n) || !friendNames.add(low)) problems.add(new Problem("who.friends[" + i + "]", "name"));
            else if (blockedNames.contains(low)) problems.add(new Problem("who.friends[" + i + "]", "blocked"));
        }
        if (intro().codePointCount(0, intro().length()) > INTRO_MAX) problems.add(new Problem("persona.intro", "text"));
        if (status().codePointCount(0, status().length()) > STATUS_MAX) problems.add(new Problem("persona.status", "text"));
        String tag = tagText().trim();
        int tagLength = tag.codePointCount(0, tag.length());
        if (tagOn() && tagLength == 0) problems.add(new Problem("persona.prefix.text", "empty"));
        else if (tagLength > TAG_MAX) problems.add(new Problem("persona.prefix.text", "length"));
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

    /** Whether a typed number is an in-game amount (digits, with dots or commas as group marks, or "40k", "40 bin"). */
    @Nullable
    public static Long parseAmount(@Nullable String typed) {
        if (typed == null) return null;
        String t = typed.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (t.isEmpty()) return null;
        long factor = 1;
        if (t.endsWith("bin")) {
            factor = 1000;
            t = t.substring(0, t.length() - 3);
        } else if (t.endsWith("milyon")) {
            factor = 1_000_000;
            t = t.substring(0, t.length() - 6);
        } else if (t.endsWith("k")) {
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
            // Twelve digits at most, as typed digits (the words under a field read up to 999 billion).
            return v < 1 || v >= 1e12 ? null : Math.round(v);
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
