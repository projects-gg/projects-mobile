package gg.projects.mobile.cloud.plus;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.cloud.PlusModel;

/**
 * PLUS-ACTIVITY ("Etkinlik", the site's {@code audit-section.tsx} and the launcher's same rules): the audit log as rows.
 * The entries of a day (newest first) become one row per player, their day together, and one row per event without a
 * player; a row says how the day went by its most telling entry; the filters (Tümü, Anlaşmalar, Yazışmalar, Retler) keep
 * what they name before the rows are made; the search finds a row by the player's name or an item's. No Android here:
 * the JVM tests run it as it is.
 */
public final class ActivityRows {
    private ActivityRows() {}

    /** The filters, as the site names them: null is "Tümü". */
    public static final String DEALS = "deals";
    public static final String MESSAGES = "messages";
    public static final String REFUSALS = "warnings";

    private static final Set<String> MESSAGE_KINDS = set("pm_in", "reply", "owner_msg");
    private static final Set<String> DEAL_KINDS = set("offer", "counter", "agree", "ask_owner", "owner_ok", "owner_no",
            "owner_timeout", "trade_start", "trade_done", "trade_fail", "handoff", "handoff_done", "handoff_no", "cancel",
            "found", "confirm_item", "order_done", "order_expired", "menu_done", "menu_fail");
    /** "Retler": what was turned down, by a rule, the other player, the trade or the player. */
    private static final Set<String> REFUSAL_KINDS = set("refuse", "gate", "cancel", "trade_fail", "owner_no",
            "owner_timeout", "handoff_no", "menu_fail");

    /** The kinds that say how a player's day went, the most telling first; kinds in one rank tie (the newest wins). */
    private static final Map<String, Integer> OUTCOME = new HashMap<>();

    static {
        String[][] ranks = {{"trade_done", "menu_done"}, {"trade_fail", "menu_fail"}, {"handoff_done"}, {"handoff_no"},
                {"owner_ok"}, {"owner_no"}, {"owner_timeout"}, {"handoff"}, {"ask_owner"}, {"confirm_item"}, {"agree"}, {"gate"},
                {"refuse"}, {"cancel"}, {"found"}, {"counter", "offer"}, {"order_done"}, {"order_expired"}};
        for (int i = 0; i < ranks.length; i++) for (String k : ranks[i]) OUTCOME.put(k, i);
    }

    private static Set<String> set(String... kinds) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(kinds)));
    }

    /** Whether an entry is in a filter (null: all). An entry may be in two ("cancel" is a deal and a refusal). */
    public static boolean in(@Nullable String filter, PlusModel.AuditEntry entry) {
        if (filter == null) return true;
        switch (filter) {
            case MESSAGES: return MESSAGE_KINDS.contains(entry.k);
            case DEALS: return DEAL_KINDS.contains(entry.k);
            case REFUSALS: return REFUSAL_KINDS.contains(entry.k);
            default: return true;
        }
    }

    /** A player's day in one row, or one event without a player alone: its entries newest first. */
    public static final class Group {
        /** {@code w:<name, lower case>} for a player's row, {@code s<sequence>} for an event. */
        public final String key;
        @Nullable
        public final String who;
        public final List<PlusModel.AuditEntry> entries;

        Group(String key, @Nullable String who, List<PlusModel.AuditEntry> entries) {
            this.key = key;
            this.who = who;
            this.entries = entries;
        }

        /** The newest entry (the row's time). */
        public PlusModel.AuditEntry newest() {
            return entries.get(0);
        }

        /** The first item any entry names (the row's tile), or null. */
        @Nullable
        public String item() {
            for (PlusModel.AuditEntry e : entries) if (e.i != null && !e.i.isEmpty()) return e.i;
            return null;
        }
    }

    /** A day's entries (newest first) as rows: a player's together (by name, any case), an event without one alone. */
    public static List<Group> groups(List<PlusModel.AuditEntry> dayEntries) {
        Map<String, Group> byWho = new LinkedHashMap<>();
        List<Group> out = new ArrayList<>();
        for (PlusModel.AuditEntry entry : dayEntries) {
            if (entry.w == null || entry.w.isEmpty()) {
                List<PlusModel.AuditEntry> one = new ArrayList<>();
                one.add(entry);
                out.add(new Group("s" + entry.s, null, one));
                continue;
            }
            String key = "w:" + entry.w.toLowerCase(Locale.ROOT);
            Group group = byWho.get(key);
            if (group == null) {
                group = new Group(key, entry.w, new ArrayList<>());
                byWho.put(key, group);
                out.add(group);
            }
            group.entries.add(entry);
        }
        return out;
    }

    /** What a row says: the most telling entry of its day (the newest of those), else its newest. */
    public static PlusModel.AuditEntry outcome(Group group) {
        PlusModel.AuditEntry best = null;
        int rank = Integer.MAX_VALUE;
        // Newest first: a later entry of the same rank never takes the place of a newer one.
        for (PlusModel.AuditEntry entry : group.entries) {
            Integer at = OUTCOME.get(entry.k);
            if (at != null && at < rank) {
                best = entry;
                rank = at;
            }
        }
        return best != null ? best : group.newest();
    }

    /** Names an item for the search, in the player's language. */
    public interface Namer {
        String name(String ref);
    }

    /** A row the search finds: by the player's name or an item's (case and Turkish letters as the player types them). */
    public static boolean found(Group group, @Nullable String query, Namer namer, Locale locale) {
        String needle = query == null ? "" : query.trim().toLowerCase(locale);
        if (needle.isEmpty()) return true;
        if (group.who != null && group.who.toLowerCase(locale).contains(needle)) return true;
        for (PlusModel.AuditEntry e : group.entries) {
            if (e.i != null && !e.i.isEmpty() && namer.name(e.i).toLowerCase(locale).contains(needle)) return true;
        }
        return false;
    }

    /** Today's done trades among the entries (the slim line over the list): "trade_done" and "handoff_done". */
    public static int tradesSince(List<PlusModel.AuditEntry> entries, long sinceMs) {
        int n = 0;
        for (PlusModel.AuditEntry e : entries) {
            if (e.t >= sinceMs && ("trade_done".equals(e.k) || "handoff_done".equals(e.k))) n++;
        }
        return n;
    }
}
