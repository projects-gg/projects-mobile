package gg.projects.mobile.accounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The phone's account order (SWIPE-M), the desktop's {@code account-order.test.ts} cases plus the swipe's walk. */
public class AccountOrderTest {
    private static final long NOW = 1_793_000_000_000L;

    private static long hoursAgo(int hours) {
        return NOW - hours * 3_600_000L;
    }

    private static Map<String, Long> times(Object... pairs) {
        Map<String, Long> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], (Long) pairs[i + 1]);
        return out;
    }

    private static final List<String> NONE = Collections.emptyList();
    private static final Map<String, Long> NEVER = Collections.emptyMap();

    @Test
    public void putsPinnedAccountsFirstInPinOrderThenTheActiveOneThenByLastPlay() {
        List<String> names = Arrays.asList("old", "pinB", "recent", "active", "pinA", "never");
        Map<String, Long> played = times("old", hoursAgo(300), "pinB", hoursAgo(900), "recent", hoursAgo(2),
                "active", hoursAgo(50));
        assertEquals(Arrays.asList("pinB", "pinA", "active", "recent", "old", "never"),
                AccountOrder.sort(names, Arrays.asList("pinB", "pinA"), "active", played, NEVER));
    }

    @Test
    public void keepsAPinnedActiveAccountInItsPinnedPlace() {
        List<String> names = Arrays.asList("a", "b", "c");
        assertEquals(Arrays.asList("a", "b", "c"),
                AccountOrder.sort(names, Arrays.asList("a", "b"), "b", times("c", hoursAgo(1)), NEVER));
    }

    @Test
    public void sortsNeverPlayedAccountsByWhenTheyWereAddedThenByName() {
        List<String> names = Arrays.asList("first", "newest", "twinB", "twinA", "noDate", "Bravo", "alpha");
        Map<String, Long> added = times("first", hoursAgo(30), "newest", hoursAgo(1), "twinA", hoursAgo(10),
                "twinB", hoursAgo(10));
        // Without a date the name decides, ignoring case ("alpha" before "Bravo"), the same on every phone.
        assertEquals(Arrays.asList("newest", "twinA", "twinB", "first", "alpha", "Bravo", "noDate"),
                AccountOrder.sort(names, NONE, null, NEVER, added));
    }

    @Test
    public void lastPlayOutranksWhenAnAccountWasAdded() {
        List<String> names = Arrays.asList("added_now", "played_once");
        assertEquals(Arrays.asList("played_once", "added_now"), AccountOrder.sort(names, NONE, null,
                times("played_once", hoursAgo(500)), times("added_now", hoursAgo(0), "played_once", hoursAgo(900))));
    }

    @Test
    public void skipsPinsOfAccountsThatAreGoneAndListsEachAccountOnce() {
        List<String> names = Arrays.asList("a", "b", "a");
        assertEquals(Arrays.asList("b", "a"),
                AccountOrder.sort(names, Arrays.asList("gone", "b", "b"), "unknown", NEVER, NEVER));
        assertEquals(NONE, AccountOrder.sort(NONE, Arrays.asList("gone"), "gone", NEVER, NEVER));
    }

    @Test
    public void doesNotChangeItsInput() {
        List<String> names = new ArrayList<>(Arrays.asList("b", "a"));
        List<String> pinned = new ArrayList<>(Arrays.asList("a"));
        AccountOrder.sort(names, pinned, "b", NEVER, NEVER);
        assertEquals(Arrays.asList("b", "a"), names);
        assertEquals(Arrays.asList("a"), pinned);
        AccountOrder.setPinned(pinned, "b", true);
        AccountOrder.setPinned(pinned, "a", false);
        assertEquals(Arrays.asList("a"), pinned);
    }

    @Test
    public void stepsAroundTheEndsBothWays() {
        List<String> order = Arrays.asList("a", "b", "c");
        assertEquals("b", AccountOrder.neighbour(order, "a", 1));
        assertEquals("a", AccountOrder.neighbour(order, "c", 1));
        assertEquals("c", AccountOrder.neighbour(order, "a", -1));
        assertEquals("b", AccountOrder.neighbour(order, "c", -1));
    }

    @Test
    public void withTwoAccountsBothWaysGoToTheOtherOne() {
        List<String> order = Arrays.asList("a", "b");
        assertEquals("b", AccountOrder.neighbour(order, "a", 1));
        assertEquals("b", AccountOrder.neighbour(order, "a", -1));
        assertEquals("a", AccountOrder.neighbour(order, "b", 1));
        assertEquals("a", AccountOrder.neighbour(order, "b", -1));
    }

    @Test
    public void hasNowhereToGoAloneAndStartsAtAnEndFromNowhere() {
        assertNull(AccountOrder.neighbour(Arrays.asList("a"), "a", 1));
        assertNull(AccountOrder.neighbour(NONE, "a", 1));
        assertNull(AccountOrder.neighbour(Arrays.asList("a", "b"), "a", 0));
        assertEquals("a", AccountOrder.neighbour(Arrays.asList("a", "b", "c"), "gone", 1));
        assertEquals("c", AccountOrder.neighbour(Arrays.asList("a", "b", "c"), null, -1));
        assertEquals("a", AccountOrder.neighbour(Arrays.asList("a"), "gone", -1));
    }

    /**
     * The reason the chip keeps one order while the player swipes: walking it reaches every account and comes back,
     * where sorting the active account first after each switch would bounce between the two most recent ones.
     */
    @Test
    public void swipingTheKeptOrderReachesEveryAccountAndComesBack() {
        List<String> names = Arrays.asList("c", "a", "d", "b");
        Map<String, Long> played = times("a", hoursAgo(1), "b", hoursAgo(2), "c", hoursAgo(3), "d", hoursAgo(4));
        List<String> order = AccountOrder.sort(names, NONE, "a", played, NEVER);
        assertEquals(Arrays.asList("a", "b", "c", "d"), order);
        Set<String> seen = new LinkedHashSet<>();
        String at = "a";
        for (int i = 0; i < order.size(); i++) {
            at = AccountOrder.neighbour(order, at, 1);
            seen.add(at);
        }
        assertEquals("a", at);
        assertEquals(new LinkedHashSet<>(Arrays.asList("b", "c", "d", "a")), seen);

        // Re-sorting after every switch (what the chip does not do) never gets past the two most recent accounts.
        Set<String> bounced = new LinkedHashSet<>();
        String active = "a";
        for (int i = 0; i < 6; i++) {
            active = AccountOrder.neighbour(AccountOrder.sort(names, NONE, active, played, NEVER), active, 1);
            bounced.add(active);
        }
        assertEquals(new LinkedHashSet<>(Arrays.asList("b", "a")), bounced);
    }

    @Test
    public void aNewPinJoinsTheEndOfThePinnedOnes() {
        List<String> pinned = AccountOrder.setPinned(NONE, "c", true);
        pinned = AccountOrder.setPinned(pinned, "a", true);
        assertEquals(Arrays.asList("c", "a"), pinned);
        assertEquals(Arrays.asList("c", "a"), AccountOrder.setPinned(pinned, "c", true));
        assertEquals(Arrays.asList("a"), AccountOrder.setPinned(pinned, "c", false));
        assertEquals(Arrays.asList("c", "a"), AccountOrder.setPinned(pinned, "zz", false));
        assertEquals(Arrays.asList("c", "a", "d"), AccountOrder.setPinned(pinned, "d", true));
        assertEquals(Arrays.asList("b"), AccountOrder.setPinned(Arrays.asList("a", "b", "a"), "a", false));
    }

    @Test
    public void storesNamesOnePerLine() {
        List<String> names = Arrays.asList("KorAtesi_", "Golge_Avci", "x1");
        assertEquals(names, AccountOrder.splitNames(AccountOrder.joinNames(names)));
        assertEquals("a\nb", AccountOrder.joinNames(Arrays.asList("a", "", "b", "bad\nname", null)));
        assertEquals(Arrays.asList("a", "b"), AccountOrder.splitNames("a\n\n b \na\n"));
        assertEquals(NONE, AccountOrder.splitNames(null));
        assertEquals(NONE, AccountOrder.splitNames(""));
    }

    @Test
    public void storesTimesAsNameEqualsMillis() {
        Map<String, Long> played = times("Golge_Avci", 1_793_000_000_123L, "b", 5L);
        assertEquals(played, AccountOrder.splitTimes(AccountOrder.joinTimes(played)));
        assertEquals("Golge_Avci=1793000000123\nb=5", AccountOrder.joinTimes(played));
        assertEquals(times("a=b", 7L, "c", 9L), AccountOrder.splitTimes("a=b=7\nbroken\n=4\nd=x\nc=9\n"));
        assertEquals(NEVER, AccountOrder.splitTimes(null));
    }
}
