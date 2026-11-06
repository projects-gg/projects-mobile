package gg.projects.mobile.afk.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gg.projects.mobile.afk.api.AfkStatus;

/**
 * The console's account tabs (phone-afk.md §5: chips over pages that swipe, at most the 3 running accounts and the one
 * opened). The set is made when the console opens and only grows while it is on screen: an account that starts joins
 * at its place in the list, one that stops keeps its tab (tabs never move under the finger); a removed account goes.
 * Plain Java, JVM-tested.
 */
public final class AfkTabs {
    private AfkTabs() {}

    /** The running accounts and {@code opened}, in the list's order. */
    public static List<String> initial(List<AfkStatus> statuses, String opened) {
        List<String> out = new ArrayList<>();
        for (AfkStatus s : statuses) {
            if (s.state.running() || s.accountId.equals(opened)) out.add(s.accountId);
        }
        return out;
    }

    /** {@code tabs} with the accounts that started since in their list places, and without the removed ones. */
    public static List<String> grow(List<String> tabs, List<AfkStatus> statuses) {
        Set<String> kept = new HashSet<>(tabs);
        List<String> out = new ArrayList<>();
        for (AfkStatus s : statuses) {
            if (kept.contains(s.accountId) || s.state.running()) out.add(s.accountId);
        }
        return out;
    }
}
