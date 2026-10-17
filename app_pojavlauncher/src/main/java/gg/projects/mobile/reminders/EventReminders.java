package gg.projects.mobile.reminders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.content.HomeContent;

/**
 * Event reminders ("Hatırlat"), the pure part (the desktop's {@code features/home/reminders.ts}):
 * when a reminder rings, which ones are due, and how stored ones follow the content. The store,
 * the alarm and the notification are in {@link Reminders}. Reminders stay on the phone.
 *
 * <p>A reminder copies its event's title and times from the last content that listed it, so the
 * alarm can tell the player without reading the content. While the event is missing from the
 * content (a feed outage, or Studio hid it) the reminder waits instead of ringing, and it goes
 * once the known end has passed.
 */
public final class EventReminders {
    private EventReminders() {}

    /** A reminder rings this long before its event starts. */
    public static final long LEAD_MS = 5 * 60_000L;

    /** A reminder the player set. */
    public static final class Reminder {
        public final String id;
        public final String title;
        public final long startsAt;
        public final long endsAt;
        /** When it was told (or passed over while the game ran); null until then. */
        public final Long firedAt;
        /** The last content did not list the event: it does not ring until it is back. */
        public final boolean missing;

        public Reminder(String id, String title, long startsAt, long endsAt, Long firedAt, boolean missing) {
            this.id = id;
            this.title = title;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
            this.firedAt = firedAt;
            this.missing = missing;
        }

        Reminder fired(long at) {
            return new Reminder(id, title, startsAt, endsAt, at, missing);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Reminder)) return false;
            Reminder r = (Reminder) o;
            return id.equals(r.id) && title.equals(r.title) && startsAt == r.startsAt && endsAt == r.endsAt
                    && (firedAt == null ? r.firedAt == null : firedAt.equals(r.firedAt)) && missing == r.missing;
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    /** A reminder whose moment has come: its event starts in a few minutes, or already runs. */
    public static final class Due {
        public final Reminder reminder;
        /** The event already runs (the reminder was missed while the phone was off or asleep). */
        public final boolean running;
        /** Whole minutes until the start, at least 1; 0 while running. */
        public final int minutes;

        Due(Reminder reminder, boolean running, int minutes) {
            this.reminder = reminder;
            this.running = running;
            this.minutes = minutes;
        }
    }

    /** When the reminder for an event starting at {@code startsAt} rings. */
    public static long ringsAt(long startsAt) {
        return startsAt - LEAD_MS;
    }

    /** Whole minutes until the start, rounded up: "1 dakika" in the last minute, never 0. */
    public static int minutesUntil(long startsAt, long now) {
        long ms = startsAt - now;
        return (int) Math.max(1, (ms + 59_999) / 60_000);
    }

    /** A new reminder for {@code event} (shown on the card, so listed). */
    public static Reminder of(HomeContent.Event event) {
        return new Reminder(event.id, event.title, event.startsAt, event.endsAt, null, false);
    }

    /** {@code stored} with the reminder for {@code event} added, or removed when it was set. */
    public static Map<String, Reminder> toggle(Map<String, Reminder> stored, HomeContent.Event event) {
        Map<String, Reminder> next = new LinkedHashMap<>(stored);
        if (next.remove(event.id) == null) next.put(event.id, of(event));
        return next;
    }

    /**
     * Brings the stored reminders up to date with the events the content lists at {@code now}.
     * A moved event moves its reminder, which rings again if it already rang and its new moment
     * is still ahead; an event that is not listed keeps its last known times and does not ring;
     * reminders whose event has ended go. Returns the same object when nothing changed.
     */
    public static Map<String, Reminder> reconcile(Map<String, Reminder> stored, List<HomeContent.Event> listed, long now) {
        Map<String, HomeContent.Event> byId = new HashMap<>();
        for (HomeContent.Event event : listed) byId.put(event.id, event);
        Map<String, Reminder> next = new LinkedHashMap<>();
        boolean changed = false;
        for (Reminder reminder : stored.values()) {
            Reminder current = reminder;
            HomeContent.Event event = byId.get(reminder.id);
            if (event != null) {
                boolean moved = event.startsAt != reminder.startsAt;
                boolean rearm = moved && ringsAt(event.startsAt) > now;
                Long fired = rearm ? null : reminder.firedAt;
                current = new Reminder(reminder.id, event.title, event.startsAt, event.endsAt, fired, false);
            } else if (!reminder.missing) {
                current = new Reminder(reminder.id, reminder.title, reminder.startsAt, reminder.endsAt, reminder.firedAt, true);
            }
            if (current.endsAt <= now) {
                changed = true;
                continue;
            }
            if (!current.equals(reminder)) changed = true;
            next.put(current.id, current);
        }
        return changed ? next : stored;
    }

    /** {@code stored} without the reminders whose event has ended; the same object when none has. */
    public static Map<String, Reminder> prune(Map<String, Reminder> stored, long now) {
        Map<String, Reminder> next = new LinkedHashMap<>();
        for (Reminder reminder : stored.values()) if (reminder.endsAt > now) next.put(reminder.id, reminder);
        return next.size() == stored.size() ? stored : next;
    }

    /**
     * The reminders to tell now, soonest start first: not told yet, their moment has passed, their
     * event is listed and has not ended. One set less than five minutes before the start is due at
     * once; one missed while the phone was off says that the event already runs.
     */
    public static List<Due> due(Map<String, Reminder> stored, long now) {
        List<Due> due = new ArrayList<>();
        for (Reminder reminder : stored.values()) {
            if (reminder.firedAt != null || reminder.missing || ringsAt(reminder.startsAt) > now || now >= reminder.endsAt) {
                continue;
            }
            due.add(now < reminder.startsAt
                    ? new Due(reminder, false, minutesUntil(reminder.startsAt, now))
                    : new Due(reminder, true, 0));
        }
        Collections.sort(due, new Comparator<Due>() {
            @Override
            public int compare(Due a, Due b) {
                return Long.compare(a.reminder.startsAt, b.reminder.startsAt);
            }
        });
        return due;
    }

    /** {@code stored} with the reminders of {@code ids} marked as told at {@code at}. */
    public static Map<String, Reminder> markFired(Map<String, Reminder> stored, List<String> ids, long at) {
        if (ids.isEmpty()) return stored;
        Map<String, Reminder> next = new LinkedHashMap<>(stored);
        for (String id : ids) {
            Reminder reminder = next.get(id);
            if (reminder != null) next.put(id, reminder.fired(at));
        }
        return next;
    }

    /** The next moment a reminder rings (not told, listed, still ahead), or null. */
    public static Long nextRing(Map<String, Reminder> stored, long now) {
        Long next = null;
        for (Reminder reminder : stored.values()) {
            if (reminder.firedAt != null || reminder.missing) continue;
            long at = ringsAt(reminder.startsAt);
            if (at > now && (next == null || at < next)) next = at;
        }
        return next;
    }

    // ---- storage ----

    /** The reminders as JSON for the app's preferences. */
    public static String toJson(Map<String, Reminder> stored) {
        JsonObject items = new JsonObject();
        for (Reminder reminder : stored.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("title", reminder.title);
            item.addProperty("startsAt", reminder.startsAt);
            item.addProperty("endsAt", reminder.endsAt);
            if (reminder.firedAt != null) item.addProperty("firedAt", reminder.firedAt);
            if (reminder.missing) item.addProperty("missing", true);
            items.add(reminder.id, item);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("items", items);
        return root.toString();
    }

    /** Reminders read back from {@link #toJson}; a broken entry is dropped, a broken file is empty. */
    public static Map<String, Reminder> fromJson(String json) {
        Map<String, Reminder> stored = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return stored;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return stored;
            JsonElement items = root.getAsJsonObject().get("items");
            if (items == null || !items.isJsonObject()) return stored;
            for (Map.Entry<String, JsonElement> entry : items.getAsJsonObject().entrySet()) {
                try {
                    JsonObject item = entry.getValue().getAsJsonObject();
                    JsonElement fired = item.get("firedAt");
                    JsonElement missing = item.get("missing");
                    stored.put(entry.getKey(), new Reminder(entry.getKey(), item.get("title").getAsString(),
                            item.get("startsAt").getAsLong(), item.get("endsAt").getAsLong(),
                            fired == null || fired.isJsonNull() ? null : fired.getAsLong(),
                            missing != null && missing.getAsBoolean()));
                } catch (RuntimeException e) {
                    // One unreadable entry does not take the others with it.
                }
            }
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
        return stored;
    }
}
