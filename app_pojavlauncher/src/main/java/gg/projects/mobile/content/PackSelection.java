package gg.projects.mobile.content;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Choices from the mod screen. Passing one to the resolver or the planner means the player
 * confirmed the mod screen: afterwards every item of the manifest counts as seen and adopted
 * manual changes are acknowledged.
 */
public final class PackSelection {
    /** Item id to selected. Items not listed keep their choice; libraries and unknown ids are ignored. */
    public final Map<String, Boolean> items = new TreeMap<>();
    /** Legacy optional group id to enabled. Groups not listed keep their choice. */
    public final Map<String, Boolean> groups = new TreeMap<>();
    /** Items whose replaced file goes back to the pack's (the choice does not change). */
    public final Set<String> revert = new TreeSet<>();

    public boolean isEmpty() {
        return items.isEmpty() && groups.isEmpty() && revert.isEmpty();
    }
}
