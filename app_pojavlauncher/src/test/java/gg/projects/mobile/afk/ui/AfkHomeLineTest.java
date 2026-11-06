package gg.projects.mobile.afk.ui;

import static gg.projects.mobile.afk.ui.AfkLookTest.status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

public final class AfkHomeLineTest {
    @Test
    public void aStopThePlayerShouldReadComesFirst() {
        AfkStatus banned = status("b", AfkState.STOPPED, AfkReason.BANNED);
        AfkHomeLine one = AfkHomeLine.of(Arrays.asList(status("a", AfkState.IN_GAME, null), banned));
        assertEquals(AfkHomeLine.Kind.STOPPED_ONE, one.kind);
        assertSame(banned, one.status);
        assertTrue(one.alarming());
        AfkHomeLine many = AfkHomeLine.of(Arrays.asList(banned, status("c", AfkState.STOPPED, AfkReason.PROCESS_ENDED)));
        assertEquals(AfkHomeLine.Kind.STOPPED_MANY, many.kind);
        assertEquals(2, many.count);
    }

    @Test
    public void thePlayersOwnStopIsNoNews() {
        AfkHomeLine none = AfkHomeLine.of(Arrays.asList(status("a", AfkState.STOPPED, null), status("b", AfkState.STOPPED, null)));
        assertEquals(AfkHomeLine.Kind.NONE_RUNNING, none.kind);
        assertFalse(none.alarming());
        assertEquals(AfkHomeLine.Kind.NONE_RUNNING, AfkHomeLine.of(Collections.<AfkStatus>emptyList()).kind);
    }

    @Test
    public void countsWhoIsInTheGame() {
        AfkHomeLine all = AfkHomeLine.of(Arrays.asList(status("a", AfkState.IN_GAME, null), status("b", AfkState.IN_GAME, null),
                status("c", AfkState.STOPPED, null)));
        assertEquals(AfkHomeLine.Kind.ALL_IN_GAME, all.kind);
        assertEquals(2, all.count);
        AfkHomeLine some = AfkHomeLine.of(Arrays.asList(status("a", AfkState.IN_GAME, null), status("b", AfkState.WAITING, AfkReason.KICKED),
                status("c", AfkState.CONNECTING, null)));
        assertEquals(AfkHomeLine.Kind.SOME_IN_GAME, some.kind);
        assertEquals(3, some.count);
        assertEquals(1, some.inGame);
        assertFalse(some.waiting());
    }

    @Test
    public void accountsOnTheirWaySayWhatTheyWaitFor() {
        AfkStatus wifi = status("a", AfkState.NO_NETWORK, AfkReason.WAITING_WIFI);
        AfkHomeLine one = AfkHomeLine.of(Arrays.asList(wifi, status("b", AfkState.STOPPED, null)));
        assertEquals(AfkHomeLine.Kind.ONE_ON_THE_WAY, one.kind);
        assertSame(wifi, one.status);
        assertTrue(one.waiting());
        AfkHomeLine many = AfkHomeLine.of(Arrays.asList(wifi, status("b", AfkState.CONNECTING, null)));
        assertEquals(AfkHomeLine.Kind.MANY_ON_THE_WAY, many.kind);
        assertEquals(2, many.count);
    }
}
