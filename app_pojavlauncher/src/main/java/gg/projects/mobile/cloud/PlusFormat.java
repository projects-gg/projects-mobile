package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.social.Json;

/**
 * AFK+ in words, pure: in-game amounts by language (TR "37.500", EN "37,500"), the assistant's state line from
 * {@code ai}, and what changed between two saved revisions of the settings ("Elytra en fazla 40.000 yerine 45.000",
 * QoL Q7; the difference is made here, the service keeps whole documents).
 */
public final class PlusFormat {
    private PlusFormat() {}

    /** An in-game amount: TR "37.500", EN "37,500". */
    public static String money(long value, String lang) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder();
        char mark = "en".equals(lang) ? ',' : '.';
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append(mark);
            out.append(digits.charAt(i));
        }
        return (value < 0 ? "-" : "") + out;
    }

    /** The assistant's state, as one short sentence's key. */
    public enum Ai {
        ON, ON_NOTIFY_ONLY, UNKNOWN,
        PAUSED_BREAK, PAUSED_SCHEDULE, PAUSED_QUOTA, PAUSED_BUDGET, PAUSED_OUTAGE, PAUSED_HOST, PAUSED_SERVICE, PAUSED,
        OFF_PLAYER, OFF_STAFF, OFF_RESTRICTED, OFF_SERVICE, OFF_SERVER, OFF_GATE, OFF_MUTED, OFF_MODERATION, OFF
    }

    public static Ai ai(@Nullable CloudModel.AiView ai) {
        if (ai == null) return Ai.UNKNOWN;
        String reason = ai.reason == null ? "" : ai.reason;
        switch (ai.state) {
            case "on":
                return "notify".equals(ai.buy) ? Ai.ON_NOTIFY_ONLY : Ai.ON;
            case "paused":
                switch (reason) {
                    case "break": return Ai.PAUSED_BREAK;
                    case "schedule": return Ai.PAUSED_SCHEDULE;
                    case "quota": return Ai.PAUSED_QUOTA;
                    case "budget": return Ai.PAUSED_BUDGET;
                    case "outage": return Ai.PAUSED_OUTAGE;
                    case "host": return Ai.PAUSED_HOST;
                    case "service": return Ai.PAUSED_SERVICE;
                    default: return Ai.PAUSED;
                }
            case "off":
                switch (reason) {
                    case "player": return Ai.OFF_PLAYER;
                    case "staff": return Ai.OFF_STAFF;
                    case "restricted": return Ai.OFF_RESTRICTED;
                    case "service": return Ai.OFF_SERVICE;
                    case "server": return Ai.OFF_SERVER;
                    case "gate": return Ai.OFF_GATE;
                    case "muted": return Ai.OFF_MUTED;
                    case "moderation": return Ai.OFF_MODERATION;
                    default: return Ai.OFF;
                }
            default:
                return Ai.UNKNOWN;
        }
    }

    /** The player may start the AI again ("Yapay zekâyı başlat"): their own stop, the gate's or a server mute. */
    public static boolean playerCanResume(Ai ai) {
        return ai == Ai.OFF_PLAYER || ai == Ai.OFF_GATE || ai == Ai.OFF_MUTED;
    }

    /** One change between two saved revisions. */
    public static final class Change {
        public enum Kind {
            AI_ON, AI_OFF, ROW_ADDED, ROW_REMOVED, ROW_TARGET, ROW_BOUND, ROW_QTY, ROW_ON, ROW_OFF, ROW_OTHER, LIMIT, SCHEDULE_ON,
            SCHEDULE_OFF, SCHEDULE_HOURS, BREAK_ON, BREAK_OFF, PERSONA, REPLIES, BLOCKED, FINE, OTHER
        }

        public final Kind kind;
        public final boolean buy;
        @Nullable
        public final String item;
        @Nullable
        public final String field;
        @Nullable
        public final Long before;
        @Nullable
        public final Long after;

        Change(Kind kind, boolean buy, @Nullable String item, @Nullable String field, @Nullable Long before, @Nullable Long after) {
            this.kind = kind;
            this.buy = buy;
            this.item = item;
            this.field = field;
            this.before = before;
            this.after = after;
        }

        static Change of(Kind kind) {
            return new Change(kind, false, null, null, null, null);
        }

        @Override
        public String toString() {
            return kind + (item == null ? "" : " " + item) + (field == null ? "" : " " + field) + (before == null && after == null ? "" : " " + before + "→" + after);
        }
    }

    /** What changed from {@code before} to {@code after} (two {@code AfkPlusConfig} documents), most telling first. */
    public static List<Change> diff(JsonObject before, JsonObject after) {
        List<Change> out = new ArrayList<>();
        boolean wasOn = Json.bool(before, "enabled", true);
        boolean isOn = Json.bool(after, "enabled", true);
        if (wasOn != isOn) out.add(Change.of(isOn ? Change.Kind.AI_ON : Change.Kind.AI_OFF));
        orders(out, before, after);
        // "İnce ayar": the authority, the sides, the payment, the counterparts.
        for (String key : new String[]{"authority", "allow", "pay", "who"}) {
            com.google.gson.JsonElement b = before.get(key);
            com.google.gson.JsonElement a = after.get(key);
            if (b == null ? a != null : !b.equals(a)) {
                out.add(Change.of(Change.Kind.FINE));
                break;
            }
        }
        JsonObject lb = Json.obj(before, "limits");
        JsonObject la = Json.obj(after, "limits");
        for (String key : new String[]{"perDealMax", "dailySpendMax", "totalBudget", "balanceFloor", "approvalAbove",
                "newCounterpartApprovalAbove", "approvalTimeoutMin"}) {
            Long b = Json.numOrNull(lb, key);
            Long a = Json.numOrNull(la, key);
            if (b == null ? a != null : !b.equals(a)) out.add(new Change(Change.Kind.LIMIT, false, null, key, b, a));
        }
        JsonObject sb = Json.obj(before, "schedule");
        JsonObject sa = Json.obj(after, "schedule");
        boolean schedB = Json.bool(sb, "on", false);
        boolean schedA = Json.bool(sa, "on", false);
        if (schedB != schedA) out.add(Change.of(schedA ? Change.Kind.SCHEDULE_ON : Change.Kind.SCHEDULE_OFF));
        else if (!Json.arr(sb, "windows").equals(Json.arr(sa, "windows"))) out.add(Change.of(Change.Kind.SCHEDULE_HOURS));
        Long pb = Json.numOrNull(before, "pauseUntil");
        Long pa = Json.numOrNull(after, "pauseUntil");
        if (pb == null && pa != null) out.add(new Change(Change.Kind.BREAK_ON, false, null, null, null, pa));
        else if (pb != null && pa == null) out.add(Change.of(Change.Kind.BREAK_OFF));
        if (!same(Json.obj(before, "persona"), Json.obj(after, "persona"))) out.add(Change.of(Change.Kind.PERSONA));
        if (!same(Json.obj(before, "replies"), Json.obj(after, "replies")) || !same(Json.obj(before, "discovery"), Json.obj(after, "discovery"))) {
            out.add(Change.of(Change.Kind.REPLIES));
        }
        if (!Json.arr(before, "blocked").equals(Json.arr(after, "blocked"))) out.add(Change.of(Change.Kind.BLOCKED));
        if (out.isEmpty() && !before.equals(after)) out.add(Change.of(Change.Kind.OTHER));
        return out;
    }

    private static boolean same(@Nullable JsonObject a, @Nullable JsonObject b) {
        return a == null ? b == null : a.equals(b);
    }

    /** The orders' changes (AfkPlusConfig v2 {@code orders}): added, removed, their bound, target, quantity, paused or resumed. */
    private static void orders(List<Change> out, JsonObject before, JsonObject after) {
        Map<String, JsonObject> old = new HashMap<>();
        for (JsonObject r : Json.objects(before, "orders")) old.put(Json.str(r, "id", ""), r);
        Map<String, JsonObject> now = new HashMap<>();
        for (JsonObject r : Json.objects(after, "orders")) now.put(Json.str(r, "id", ""), r);
        for (JsonObject r : Json.objects(after, "orders")) {
            String id = Json.str(r, "id", "");
            boolean buy = !"sell".equals(Json.str(r, "side"));
            JsonObject was = old.get(id);
            String item = Json.str(r, "item", "");
            if (was == null) {
                out.add(new Change(Change.Kind.ROW_ADDED, buy, item, null, null, null));
                continue;
            }
            int before0 = out.size();
            long tb = Json.num(was, "target", 0);
            long ta = Json.num(r, "target", 0);
            long bb = Json.num(was, "limit", 0);
            long ba = Json.num(r, "limit", 0);
            long qb = Json.num(was, "qty", 0);
            long qa = Json.num(r, "qty", 0);
            if (bb != ba) out.add(new Change(Change.Kind.ROW_BOUND, buy, item, "limit", bb, ba));
            if (tb != ta) out.add(new Change(Change.Kind.ROW_TARGET, buy, item, "target", tb, ta));
            if (qb != qa) out.add(new Change(Change.Kind.ROW_QTY, buy, item, "qty", qb, qa));
            boolean eb = Json.bool(was, "on", true);
            boolean ea = Json.bool(r, "on", true);
            if (eb != ea) out.add(new Change(ea ? Change.Kind.ROW_ON : Change.Kind.ROW_OFF, buy, item, null, null, null));
            if (out.size() == before0 && !was.equals(r)) out.add(new Change(Change.Kind.ROW_OTHER, buy, item, null, null, null));
        }
        for (JsonObject r : Json.objects(before, "orders")) {
            if (!now.containsKey(Json.str(r, "id", ""))) {
                out.add(new Change(Change.Kind.ROW_REMOVED, !"sell".equals(Json.str(r, "side")), Json.str(r, "item", ""), null, null, null));
            }
        }
    }
}
