package gg.projects.mobile.accounts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The order of the phone's accounts (SWIPE-M), the desktop's rule ({@code account-order.ts} in apps/launcher): the pinned
 * accounts first, in the order they were pinned; then the active account; then by last play, newest first; then by when
 * they were added, newest first; then by name. The account chip's list and its swipe walk this order, around the end.
 *
 * <p>The chip sorts when its list is built (a start, an account added or removed, a pin) and keeps that order while the
 * player swipes: sorting the active account first after every switch would send the next swipe back to the account just
 * left, so three accounts could never all be reached. Pure Java, JVM-tested ({@code AccountOrderTest}).
 */
public final class AccountOrder {
    private AccountOrder() {}

    /**
     * The display order of {@code names} (each once). {@code pinned} lists the pinned accounts in the order they were
     * pinned (names that are not in {@code names} are skipped); {@code active} may be null or unknown; {@code played} and
     * {@code added} map names to epoch milliseconds (missing names count as never).
     */
    public static List<String> sort(Collection<String> names, List<String> pinned, final String active,
                                    final Map<String, Long> played, final Map<String, Long> added) {
        Set<String> present = new LinkedHashSet<>(names);
        List<String> out = new ArrayList<>(present.size());
        for (String name : pinned) {
            if (present.contains(name) && !out.contains(name)) out.add(name);
        }
        List<String> rest = new ArrayList<>(present.size());
        for (String name : present) {
            if (!out.contains(name)) rest.add(name);
        }
        Collections.sort(rest, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                boolean aActive = a.equals(active);
                boolean bActive = b.equals(active);
                if (aActive != bActive) return aActive ? -1 : 1;
                int byPlay = Long.compare(time(played, b), time(played, a));
                if (byPlay != 0) return byPlay;
                int byAdded = Long.compare(time(added, b), time(added, a));
                if (byAdded != 0) return byAdded;
                int byName = String.CASE_INSENSITIVE_ORDER.compare(a, b);
                return byName != 0 ? byName : a.compareTo(b);
            }
        });
        out.addAll(rest);
        return out;
    }

    private static long time(Map<String, Long> times, String name) {
        Long at = times.get(name);
        return at == null ? Long.MIN_VALUE : at;
    }

    /**
     * The account {@code step} places from {@code current} in {@code order} (1 the next, -1 the previous), around the
     * ends; the first (or, going back, the last) account when {@code current} is not in the order; null when there is no
     * other account to go to.
     */
    public static String neighbour(List<String> order, String current, int step) {
        int count = order.size();
        if (count == 0 || step == 0) return null;
        int at = current == null ? -1 : order.indexOf(current);
        if (at < 0) return order.get(step > 0 ? 0 : count - 1);
        if (count < 2) return null;
        int to = ((at + step) % count + count) % count;
        return to == at ? null : order.get(to);
    }

    /**
     * The pins after pinning {@code name} (it joins the end of the pinned ones; already pinned: no change) or unpinning
     * it. A new list; the input is not changed.
     */
    public static List<String> setPinned(List<String> pinned, String name, boolean pin) {
        List<String> out = new ArrayList<>(pinned);
        if (pin) {
            if (!out.contains(name)) out.add(name);
            return out;
        }
        while (out.remove(name)) {
            // Every copy goes (a hand-edited store could hold two).
        }
        return out;
    }

    /** The stored form of a name list: one name per line. */
    public static String joinNames(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (name == null || name.isEmpty() || name.indexOf('\n') >= 0) continue;
            if (out.length() > 0) out.append('\n');
            out.append(name);
        }
        return out.toString();
    }

    /** A name list read back from {@link #joinNames}; empty lines and repeats are dropped, null reads as empty. */
    public static List<String> splitNames(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null) return out;
        for (String line : stored.split("\n")) {
            String name = line.trim();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    /** The stored form of a time map: {@code name=millis} per line, by name. */
    public static String joinTimes(Map<String, Long> times) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : new TreeMap<>(times).entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isEmpty() || name.indexOf('\n') >= 0 || entry.getValue() == null) continue;
            if (out.length() > 0) out.append('\n');
            out.append(name).append('=').append(entry.getValue().longValue());
        }
        return out.toString();
    }

    /** A time map read back from {@link #joinTimes}; broken lines are skipped, null reads as empty. */
    public static Map<String, Long> splitTimes(String stored) {
        Map<String, Long> out = new TreeMap<>();
        if (stored == null) return out;
        for (String line : stored.split("\n")) {
            int at = line.lastIndexOf('=');
            if (at <= 0) continue;
            String name = line.substring(0, at).trim();
            if (name.isEmpty()) continue;
            try {
                out.put(name, Long.parseLong(line.substring(at + 1).trim()));
            } catch (NumberFormatException ignored) {
                // A broken line drops itself only.
            }
        }
        return out;
    }
}
