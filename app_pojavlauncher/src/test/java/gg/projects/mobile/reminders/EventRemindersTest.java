package gg.projects.mobile.reminders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.content.HomeContent;

/** Event reminders (the desktop's features/home/reminders.test.ts). */
public class EventRemindersTest {
    private static final long MIN = 60_000L;
    /** 2026-10-18T17:00:00Z, an event's start. */
    private static final long START = 1_792_342_800_000L;

    private static HomeContent.Event event(String id, long startsAt, long endsAt) {
        return new HomeContent.Event(id, "Etkinlik " + id, startsAt, endsAt, false, null, null, 0, false);
    }

    private static Map<String, EventReminders.Reminder> set(HomeContent.Event... events) {
        Map<String, EventReminders.Reminder> stored = new LinkedHashMap<>();
        for (HomeContent.Event event : events) stored = EventReminders.toggle(stored, event);
        return stored;
    }

    private static List<String> ids(List<EventReminders.Due> due) {
        List<String> ids = new ArrayList<>();
        for (EventReminders.Due d : due) ids.add(d.reminder.id);
        return ids;
    }

    @Test
    public void ringsFiveMinutesBeforeTheStart() {
        assertEquals(START - 5 * MIN, EventReminders.ringsAt(START));
        Map<String, EventReminders.Reminder> stored = set(event("a", START, START + 60 * MIN), event("b", START - 30 * MIN, START));
        assertEquals(Long.valueOf(START - 35 * MIN), EventReminders.nextRing(stored, START - 60 * MIN));
        // A moment already past is never waited on; a told reminder neither.
        assertEquals(Long.valueOf(START - 5 * MIN), EventReminders.nextRing(stored, START - 35 * MIN));
        stored = EventReminders.markFired(stored, Collections.singletonList("a"), START - 6 * MIN);
        assertNull(EventReminders.nextRing(stored, START - 35 * MIN));
    }

    @Test
    public void countsWholeMinutesUpToTheStartNeverZero() {
        assertEquals(5, EventReminders.minutesUntil(START, START - 5 * MIN));
        assertEquals(5, EventReminders.minutesUntil(START, START - 4 * MIN - 1));
        assertEquals(1, EventReminders.minutesUntil(START, START - 1));
        assertEquals(1, EventReminders.minutesUntil(START, START));
        assertEquals(1, EventReminders.minutesUntil(START, START + MIN));
    }

    @Test
    public void tellsNothingBeforeTheMomentAndHowSoonAtIt() {
        Map<String, EventReminders.Reminder> stored = set(event("a", START, START + 60 * MIN));
        assertTrue(EventReminders.due(stored, START - 5 * MIN - 1).isEmpty());
        List<EventReminders.Due> due = EventReminders.due(stored, START - 5 * MIN);
        assertEquals(1, due.size());
        assertFalse(due.get(0).running);
        assertEquals(5, due.get(0).minutes);
        assertEquals("Etkinlik a", due.get(0).reminder.title);
    }

    @Test
    public void aReminderSetLessThanFiveMinutesBeforeIsDueAtOnce() {
        Map<String, EventReminders.Reminder> stored = set(event("a", START, START + 60 * MIN));
        List<EventReminders.Due> due = EventReminders.due(stored, START - 3 * MIN);
        assertEquals(3, due.get(0).minutes);
        assertNull(EventReminders.nextRing(stored, START - 3 * MIN));
    }

    @Test
    public void aMissedReminderSaysTheEventIsOn() {
        // The phone was off at the moment: after a restart it says the event runs.
        Map<String, EventReminders.Reminder> stored = set(event("a", START, START + 60 * MIN));
        List<EventReminders.Due> due = EventReminders.due(stored, START + 10 * MIN);
        assertTrue(due.get(0).running);
        assertEquals(0, due.get(0).minutes);
        // Never once the event has ended.
        assertTrue(EventReminders.due(stored, START + 60 * MIN).isEmpty());
    }

    @Test
    public void tellsAReminderOnce() {
        Map<String, EventReminders.Reminder> stored = set(event("a", START, START + 60 * MIN));
        stored = EventReminders.markFired(stored, Collections.singletonList("a"), START - 5 * MIN);
        assertTrue(EventReminders.due(stored, START - 4 * MIN).isEmpty());
        assertTrue(EventReminders.due(stored, START + MIN).isEmpty());
        assertEquals(Long.valueOf(START - 5 * MIN), stored.get("a").firedAt);
    }

    @Test
    public void putsTheSoonestStartFirst() {
        Map<String, EventReminders.Reminder> stored = set(event("late", START + 2 * MIN, START + 60 * MIN),
                event("early", START, START + 60 * MIN));
        assertEquals(Arrays.asList("early", "late"), ids(EventReminders.due(stored, START - MIN)));
    }

    @Test
    public void pressingAgainRemovesIt() {
        HomeContent.Event a = event("a", START, START + 60 * MIN);
        Map<String, EventReminders.Reminder> stored = set(a);
        assertTrue(stored.containsKey("a"));
        assertFalse(EventReminders.toggle(stored, a).containsKey("a"));
    }

    @Test
    public void reconcileReturnsTheSameRemindersWhenNothingChanged() {
        HomeContent.Event a = event("a", START, START + 60 * MIN);
        Map<String, EventReminders.Reminder> stored = set(a);
        assertSame(stored, EventReminders.reconcile(stored, Collections.singletonList(a), START - 60 * MIN));
    }

    @Test
    public void followsAnEventStudioMovedAndRingsAgainWhenItsMomentIsAhead() {
        Map<String, EventReminders.Reminder> stored = set(event("a", START, START + 60 * MIN));
        stored = EventReminders.markFired(stored, Collections.singletonList("a"), START - 5 * MIN);
        HomeContent.Event moved = event("a", START + 60 * MIN, START + 120 * MIN);
        Map<String, EventReminders.Reminder> next = EventReminders.reconcile(stored, Collections.singletonList(moved), START - 4 * MIN);
        assertNotSame(stored, next);
        assertEquals(START + 60 * MIN, next.get("a").startsAt);
        assertNull(next.get("a").firedAt);
        assertEquals(Long.valueOf(START + 55 * MIN), EventReminders.nextRing(next, START - 4 * MIN));
    }

    @Test
    public void doesNotRingTwiceWhenTheMovedMomentIsPast() {
        Map<String, EventReminders.Reminder> stored = set(event("a", START, START + 60 * MIN));
        stored = EventReminders.markFired(stored, Collections.singletonList("a"), START - 5 * MIN);
        HomeContent.Event earlier = event("a", START - 2 * MIN, START + 60 * MIN);
        Map<String, EventReminders.Reminder> next = EventReminders.reconcile(stored, Collections.singletonList(earlier), START - 4 * MIN);
        assertEquals(Long.valueOf(START - 5 * MIN), next.get("a").firedAt);
        assertTrue(EventReminders.due(next, START - 4 * MIN).isEmpty());
    }

    @Test
    public void keepsTheToldStateWhenOnlyTheEndOrTitleChanged() {
        Map<String, EventReminders.Reminder> stored = set(event("a", START, START + 60 * MIN));
        stored = EventReminders.markFired(stored, Collections.singletonList("a"), START - 5 * MIN);
        HomeContent.Event longer = new HomeContent.Event("a", "Kış finali", START, START + 90 * MIN, false, null, null, 0, false);
        Map<String, EventReminders.Reminder> next = EventReminders.reconcile(stored, Collections.singletonList(longer), START - MIN);
        assertEquals(START + 90 * MIN, next.get("a").endsAt);
        assertEquals("Kış finali", next.get("a").title);
        assertEquals(Long.valueOf(START - 5 * MIN), next.get("a").firedAt);
    }

    @Test
    public void dropsRemindersWhoseEventHasEndedListedOrNot() {
        HomeContent.Event a = event("a", START, START + 60 * MIN);
        HomeContent.Event b = event("b", START, START + 30 * MIN);
        Map<String, EventReminders.Reminder> stored = set(a, b);
        Map<String, EventReminders.Reminder> next = EventReminders.reconcile(stored, Collections.singletonList(a), START + 30 * MIN);
        assertEquals(Collections.singleton("a"), next.keySet());
        assertTrue(EventReminders.reconcile(stored, new ArrayList<HomeContent.Event>(), START + 60 * MIN).isEmpty());
        assertEquals(Collections.singleton("a"), EventReminders.prune(stored, START + 30 * MIN).keySet());
        assertSame(stored, EventReminders.prune(stored, START));
    }

    @Test
    public void aMissingEventWaitsUntilItsKnownEndWithoutRinging() {
        // A feed outage or a hidden event: the reminder keeps its times but does not ring.
        HomeContent.Event a = event("a", START, START + 60 * MIN);
        Map<String, EventReminders.Reminder> stored = set(a);
        Map<String, EventReminders.Reminder> gone = EventReminders.reconcile(stored, new ArrayList<HomeContent.Event>(), START - 60 * MIN);
        assertTrue(gone.get("a").missing);
        assertNull(EventReminders.nextRing(gone, START - 60 * MIN));
        assertTrue(EventReminders.due(gone, START - MIN).isEmpty());
        // Back in the content: it rings again.
        Map<String, EventReminders.Reminder> back = EventReminders.reconcile(gone, Collections.singletonList(a), START - 60 * MIN);
        assertFalse(back.get("a").missing);
        assertEquals(Long.valueOf(START - 5 * MIN), EventReminders.nextRing(back, START - 60 * MIN));
    }

    @Test
    public void keepsRemindersAcrossARestart() {
        Map<String, EventReminders.Reminder> stored = set(event("a", START, START + 60 * MIN), event("b", START + MIN, START + 2 * MIN));
        stored = EventReminders.markFired(stored, Collections.singletonList("b"), START - 4 * MIN);
        stored = EventReminders.reconcile(stored, Collections.singletonList(event("b", START + MIN, START + 2 * MIN)), START - 60 * MIN);
        Map<String, EventReminders.Reminder> back = EventReminders.fromJson(EventReminders.toJson(stored));
        assertEquals(stored, back);
        assertTrue(back.get("a").missing);
        assertEquals(Long.valueOf(START - 4 * MIN), back.get("b").firedAt);
    }

    @Test
    public void aBrokenStoreStartsOverWithoutLosingGoodEntries() {
        assertTrue(EventReminders.fromJson(null).isEmpty());
        assertTrue(EventReminders.fromJson("not json").isEmpty());
        assertTrue(EventReminders.fromJson("[]").isEmpty());
        Map<String, EventReminders.Reminder> back = EventReminders.fromJson(
                "{\"version\":1,\"items\":{\"bad\":{\"title\":\"x\"},\"good\":{\"title\":\"İyi\",\"startsAt\":1,\"endsAt\":2}}}");
        assertEquals(Collections.singleton("good"), back.keySet());
        assertEquals("İyi", back.get("good").title);
    }
}
