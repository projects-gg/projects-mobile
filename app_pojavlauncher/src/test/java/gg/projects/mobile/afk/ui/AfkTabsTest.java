package gg.projects.mobile.afk.ui;

import static gg.projects.mobile.afk.ui.AfkLookTest.status;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

public final class AfkTabsTest {
    @Test
    public void theRunningAccountsAndTheOpenedOneInTheListsOrder() {
        List<AfkStatus> list = Arrays.asList(status("a", AfkState.STOPPED, null), status("b", AfkState.IN_GAME, null),
                status("c", AfkState.STOPPED, null), status("d", AfkState.WAITING, null));
        assertEquals(Arrays.asList("b", "c", "d"), AfkTabs.initial(list, "c"));
        assertEquals(Arrays.asList("b", "d"), AfkTabs.initial(list, "b"));
        assertEquals(Arrays.asList("a"), AfkTabs.initial(Arrays.asList(status("a", AfkState.STOPPED, null)), "a"));
    }

    @Test
    public void tabsOnlyGrowWhileOnScreenAndRemovedAccountsGo() {
        List<String> tabs = Arrays.asList("b", "c");
        // "b" stopped (its tab stays), "a" started (joins in its place), "c" was removed.
        List<AfkStatus> now = Arrays.asList(status("a", AfkState.CONNECTING, null), status("b", AfkState.STOPPED, null),
                status("d", AfkState.STOPPED, null));
        assertEquals(Arrays.asList("a", "b"), AfkTabs.grow(tabs, now));
    }
}
