package gg.projects.mobile.reminders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.social.SocialModel.Plan;

/**
 * M21 (TEAM5): crew plan reminders, the pure part. A plan the player said "Geliyorum" or "Belki" to
 * rings 10 minutes before its time (README "Crew plans": reminders are the launcher's, set on the
 * player's own device). Plans reach the phone only while its socket is open, so the reminders keep a
 * copy of what they need (the crew's name, the time) and ring with the app closed; a plan cancelled
 * while the phone did not look still rings (the next look takes it away). One missed (the phone off or
 * asleep, a late alarm) says the plan started during its first quarter of an hour; later nothing (the
 * desktop's rule). The store, the alarm and the notification are in {@link PlanAlarms}.
 *
 * <p>Quiet: nothing shows on do-not-disturb ("Rahatsız etmeyin"), while the crew or every crew is muted
 * ("Sessize al"), or while the game is on screen; the reminder then counts as told. An answer given
 * less than 10 minutes before the time sets none (the player just looked).
 */
public final class PlanReminders {
    private PlanReminders() {}

    /** A reminder rings this long before the plan's time. */
    public static final long LEAD_SECONDS = 10 * 60L;
    /** A reminder is still told this long after the plan's time ("… planı başladı"), not later. */
    public static final long LATE_SECONDS = 15 * 60L;
    /** A mute without an end. */
    public static final long FOREVER = Long.MAX_VALUE;

    /** One plan's reminder. */
    public static final class Item {
        public final String plan;
        public final String crew;
        public final String crewName;
        /** The plan's time and end (Unix seconds). */
        public final long at;
        public final long until;
        /** When it was told (or passed over quietly); null until then. */
        public final Long firedAt;

        public Item(String plan, String crew, String crewName, long at, long until, Long firedAt) {
            this.plan = plan;
            this.crew = crew;
            this.crewName = crewName;
            this.at = at;
            this.until = until;
            this.firedAt = firedAt;
        }

        public long ringsAt() {
            return at - LEAD_SECONDS;
        }

        /** Told before this moment only: a quarter of an hour after the plan's time (never after its end). */
        public long tellsUntil() {
            return Math.min(until, at + LATE_SECONDS);
        }

        Item fired(long when) {
            return new Item(plan, crew, crewName, at, until, when);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Item)) return false;
            Item i = (Item) o;
            return plan.equals(i.plan) && crew.equals(i.crew) && crewName.equals(i.crewName) && at == i.at && until == i.until
                    && (firedAt == null ? i.firedAt == null : firedAt.equals(i.firedAt));
        }

        @Override
        public int hashCode() {
            return plan.hashCode();
        }
    }

    /** What keeps a reminder from showing when it rings, as last known on this phone. */
    public static final class Quiet {
        /** "Rahatsız etmeyin". */
        public final boolean dnd;
        /** Every crew muted until then (Unix seconds; {@link #FOREVER}; 0: not muted). */
        public final long allMutedUntil;
        /** Crew id → muted until then. */
        public final Map<String, Long> crewMutedUntil;

        public Quiet(boolean dnd, long allMutedUntil, Map<String, Long> crewMutedUntil) {
            this.dnd = dnd;
            this.allMutedUntil = allMutedUntil;
            this.crewMutedUntil = Collections.unmodifiableMap(new HashMap<>(crewMutedUntil));
        }

        public static final Quiet NONE = new Quiet(false, 0, Collections.<String, Long>emptyMap());

        /** A reminder of {@code crew} stays quiet at {@code now}. */
        public boolean quiet(String crew, long now) {
            if (dnd || now < allMutedUntil) return true;
            Long until = crewMutedUntil.get(crew);
            return until != null && now < until;
        }
    }

    /**
     * The reminders the plans call for: those the player said "Geliyorum" or "Belki" to that are not
     * over ({@code crewNames}: id → the crew's name).
     */
    public static Map<String, Item> desired(List<Plan> plans, String me, Map<String, String> crewNames, long now) {
        Map<String, Item> out = new LinkedHashMap<>();
        for (Plan p : plans) {
            String answer = p.answers.get(me);
            if (!Plan.YES.equals(answer) && !Plan.MAYBE.equals(answer)) continue;
            if (p.until <= now) continue;
            String name = crewNames.get(p.crew);
            out.put(p.id, new Item(p.id, p.crew, name == null ? "" : name, p.at, p.until, null));
        }
        return out;
    }

    /**
     * The new store: exactly {@code desired}, keeping when a reminder of the same plan and time was told.
     * One that is new when its moment has already come is told at once (quietly: the player just looked).
     */
    public static Map<String, Item> merge(Map<String, Item> stored, Map<String, Item> desired, long now) {
        Map<String, Item> next = new LinkedHashMap<>();
        for (Item want : desired.values()) {
            Item had = stored.get(want.plan);
            Long fired = had != null && had.at == want.at ? had.firedAt : null;
            if (fired == null && had == null && now >= want.ringsAt()) fired = now;
            next.put(want.plan, new Item(want.plan, want.crew, want.crewName, want.at, want.until, fired));
        }
        return next;
    }

    /** Reminders that are not over, the same object when none is. */
    public static Map<String, Item> prune(Map<String, Item> stored, long now) {
        Map<String, Item> next = new LinkedHashMap<>();
        for (Item item : stored.values()) if (item.until > now) next.put(item.plan, item);
        return next.size() == stored.size() ? stored : next;
    }

    /** Reminders to tell now, soonest plan first: not told, their moment has come, the plan started at most 15 minutes ago. */
    public static List<Item> due(Map<String, Item> stored, long now) {
        List<Item> due = new ArrayList<>();
        for (Item item : stored.values()) {
            if (item.firedAt == null && now >= item.ringsAt() && now < item.tellsUntil()) due.add(item);
        }
        Collections.sort(due, (a, b) -> Long.compare(a.at, b.at));
        return due;
    }

    public static Map<String, Item> markFired(Map<String, Item> stored, List<String> plans, long now) {
        if (plans.isEmpty()) return stored;
        Map<String, Item> next = new LinkedHashMap<>(stored);
        for (String plan : plans) {
            Item item = next.get(plan);
            if (item != null) next.put(plan, item.fired(now));
        }
        return next;
    }

    /** The next moment a reminder rings (not told, still ahead), or null. */
    public static Long nextRing(Map<String, Item> stored, long now) {
        Long next = null;
        for (Item item : stored.values()) {
            if (item.firedAt != null || item.tellsUntil() <= now) continue;
            long at = Math.max(item.ringsAt(), now);
            if (next == null || at < next) next = at;
        }
        return next;
    }

    /** Whole minutes until {@code at}, rounded up (never 0 before it); 0 once it has come. */
    public static int minutesUntil(long at, long now) {
        if (now >= at) return 0;
        return (int) Math.max(1, (at - now + 59) / 60);
    }

    // ---- storage ----

    public static String toJson(Map<String, Item> stored) {
        JsonObject items = new JsonObject();
        for (Item item : stored.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("crew", item.crew);
            o.addProperty("name", item.crewName);
            o.addProperty("at", item.at);
            o.addProperty("until", item.until);
            if (item.firedAt != null) o.addProperty("firedAt", item.firedAt);
            items.add(item.plan, o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("items", items);
        return root.toString();
    }

    /** Read back from {@link #toJson}; a broken entry is dropped, a broken store is empty. */
    public static Map<String, Item> fromJson(String json) {
        Map<String, Item> stored = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return stored;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return stored;
            JsonElement items = root.getAsJsonObject().get("items");
            if (items == null || !items.isJsonObject()) return stored;
            for (Map.Entry<String, JsonElement> entry : items.getAsJsonObject().entrySet()) {
                try {
                    JsonObject o = entry.getValue().getAsJsonObject();
                    JsonElement fired = o.get("firedAt");
                    stored.put(entry.getKey(), new Item(entry.getKey(), o.get("crew").getAsString(), o.get("name").getAsString(),
                            o.get("at").getAsLong(), o.get("until").getAsLong(),
                            fired == null || fired.isJsonNull() ? null : fired.getAsLong()));
                } catch (RuntimeException e) {
                    // One unreadable entry does not take the others with it.
                }
            }
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
        return stored;
    }

    public static String quietToJson(Quiet quiet) {
        JsonObject o = new JsonObject();
        o.addProperty("dnd", quiet.dnd);
        o.addProperty("all", quiet.allMutedUntil);
        JsonObject crews = new JsonObject();
        for (Map.Entry<String, Long> e : quiet.crewMutedUntil.entrySet()) crews.addProperty(e.getKey(), e.getValue());
        o.add("crews", crews);
        return o.toString();
    }

    public static Quiet quietFromJson(String json) {
        if (json == null || json.isEmpty()) return Quiet.NONE;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Long> crews = new HashMap<>();
            JsonElement c = o.get("crews");
            if (c != null && c.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : c.getAsJsonObject().entrySet()) crews.put(e.getKey(), e.getValue().getAsLong());
            }
            return new Quiet(o.has("dnd") && o.get("dnd").getAsBoolean(), o.has("all") ? o.get("all").getAsLong() : 0, crews);
        } catch (RuntimeException e) {
            return Quiet.NONE;
        }
    }
}
