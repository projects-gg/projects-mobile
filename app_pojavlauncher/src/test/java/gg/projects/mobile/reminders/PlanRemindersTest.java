package gg.projects.mobile.reminders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.social.SocialModel.Plan;

/** M21 (TEAM5): a plan the player is coming to rings 10 minutes before its time, unless kept quiet. */
public class PlanRemindersTest {
    private static final String ME = "100000000000000011";
    private static final String AYSE = "100000000000000012";
    private static final String GECE = "0a1b2c3d4e5f6a7b";
    private static final String KALE = "1b2c3d4e5f6a7b8c";
    private static final long NOW = 1_792_521_000L;

    private static Plan plan(String id, String crew, long at, String mine) {
        Map<String, String> answers = new HashMap<>();
        answers.put(AYSE, "yes");
        if (mine != null) answers.put(ME, mine);
        return new Plan(id, crew, AYSE, at, at + 7200, NOW - 60, answers);
    }

    private static Map<String, String> names() {
        Map<String, String> names = new HashMap<>();
        names.put(GECE, "Gece Kartalları");
        names.put(KALE, "Kale");
        return names;
    }

    @Test
    public void comingOrMaybeAsksForAReminder() {
        List<Plan> plans = Arrays.asList(plan("p1", GECE, NOW + 3600, "yes"), plan("p2", KALE, NOW + 7200, "maybe"),
                plan("p3", GECE, NOW + 9000, "no"), plan("p4", GECE, NOW + 10800, null),
                plan("p5", GECE, NOW - 7300, "yes"));
        Map<String, PlanReminders.Item> want = PlanReminders.desired(plans, ME, names(), NOW);
        assertEquals(Arrays.asList("p1", "p2"), new java.util.ArrayList<>(want.keySet()));
        assertEquals("Gece Kartalları", want.get("p1").crewName);
        assertEquals(NOW + 3600 - 600, want.get("p1").ringsAt());
    }

    @Test
    public void theStoreBecomesWhatThePlansCallFor() {
        Map<String, PlanReminders.Item> stored = new LinkedHashMap<>();
        Map<String, PlanReminders.Item> want = PlanReminders.desired(Arrays.asList(plan("p1", GECE, NOW + 3600, "yes"),
                plan("p2", KALE, NOW + 300, "yes")), ME, names(), NOW);
        Map<String, PlanReminders.Item> next = PlanReminders.merge(stored, want, NOW);
        assertNull(next.get("p1").firedAt);
        assertEquals("an answer given inside the ten minutes sets none", Long.valueOf(NOW), next.get("p2").firedAt);
        assertEquals(Long.valueOf(NOW + 3000), PlanReminders.nextRing(next, NOW));
        // Told, it stays told while the plan keeps its time.
        Map<String, PlanReminders.Item> told = PlanReminders.markFired(next, Collections.singletonList("p1"), NOW + 3000);
        Map<String, PlanReminders.Item> again = PlanReminders.merge(told, want, NOW + 3001);
        assertEquals(Long.valueOf(NOW + 3000), again.get("p1").firedAt);
        assertNull(PlanReminders.nextRing(again, NOW + 3001));
        // An answer turned to "Gelemem" takes it away.
        Map<String, PlanReminders.Item> none = PlanReminders.merge(again,
                PlanReminders.desired(Collections.singletonList(plan("p1", GECE, NOW + 3600, "no")), ME, names(), NOW), NOW);
        assertTrue(none.isEmpty());
    }

    @Test
    public void dueTenMinutesBeforeAndForAQuarterHourAfterTheStart() {
        Map<String, PlanReminders.Item> stored = PlanReminders.merge(new LinkedHashMap<String, PlanReminders.Item>(),
                PlanReminders.desired(Collections.singletonList(plan("p1", GECE, NOW + 3600, "yes")), ME, names(), NOW), NOW);
        assertTrue(PlanReminders.due(stored, NOW + 2999).isEmpty());
        assertEquals(1, PlanReminders.due(stored, NOW + 3000).size());
        assertEquals(10, PlanReminders.minutesUntil(NOW + 3600, NOW + 3000));
        assertEquals("a late alarm: the plan started", 0, PlanReminders.minutesUntil(NOW + 3600, NOW + 3700));
        assertEquals(1, PlanReminders.due(stored, NOW + 3700).size());
        assertEquals("a quarter hour after the start: still told", 1, PlanReminders.due(stored, NOW + 3600 + 899).size());
        assertTrue("later nothing (the desktop's rule)", PlanReminders.due(stored, NOW + 3600 + 900).isEmpty());
        assertNull("and no alarm for it", PlanReminders.nextRing(stored, NOW + 3600 + 900));
        assertFalse("kept while the plan runs, so it is not set again", PlanReminders.prune(stored, NOW + 3600 + 900).isEmpty());
        assertTrue("over", PlanReminders.due(stored, NOW + 3600 + 7200).isEmpty());
        assertTrue(PlanReminders.prune(stored, NOW + 3600 + 7200).isEmpty());
        assertEquals(1, PlanReminders.minutesUntil(NOW + 3600, NOW + 3599));
    }

    @Test
    public void quietOnDoNotDisturbAndWhileMuted() {
        Map<String, Long> crews = new HashMap<>();
        crews.put(KALE, PlanReminders.FOREVER);
        PlanReminders.Quiet quiet = new PlanReminders.Quiet(false, 0, crews);
        assertFalse(quiet.quiet(GECE, NOW));
        assertTrue("Kale muted until unmuted", quiet.quiet(KALE, NOW));
        assertTrue(new PlanReminders.Quiet(true, 0, Collections.<String, Long>emptyMap()).quiet(GECE, NOW));
        PlanReminders.Quiet all = new PlanReminders.Quiet(false, NOW + 3600, Collections.<String, Long>emptyMap());
        assertTrue(all.quiet(GECE, NOW));
        assertFalse("a timed mute ends by itself", all.quiet(GECE, NOW + 3600));
        PlanReminders.Quiet back = PlanReminders.quietFromJson(PlanReminders.quietToJson(quiet));
        assertTrue(back.quiet(KALE, NOW));
        assertFalse(back.quiet(GECE, NOW));
        assertFalse(PlanReminders.quietFromJson("not json").quiet(GECE, NOW));
    }

    @Test
    public void theStoreReadsBack() {
        Map<String, PlanReminders.Item> stored = PlanReminders.merge(new LinkedHashMap<String, PlanReminders.Item>(),
                PlanReminders.desired(Arrays.asList(plan("p1", GECE, NOW + 3600, "yes"), plan("p2", KALE, NOW + 120, "maybe")),
                        ME, names(), NOW), NOW);
        Map<String, PlanReminders.Item> back = PlanReminders.fromJson(PlanReminders.toJson(stored));
        assertEquals(stored, back);
        assertNotNull(back.get("p2").firedAt);
        assertTrue(PlanReminders.fromJson("{broken").isEmpty());
        assertTrue(PlanReminders.fromJson(null).isEmpty());
        String oneBroken = PlanReminders.toJson(stored).replace("\"crew\":\"" + KALE + "\"", "\"crew\":[]");
        assertEquals("one broken entry goes alone", 1, PlanReminders.fromJson(oneBroken).size());
    }
}
