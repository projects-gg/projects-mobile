package gg.projects.mobile.afk.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

public final class AfkLookTest {
    static AfkStatus status(String id, AfkState state, AfkReason reason) {
        return new AfkStatus(id, "Name_" + id, state, 1_000, state == AfkState.IN_GAME ? 1_000 : 0, 0, 0, reason, null);
    }

    @Test
    public void wordsAreBoneTheBarsCarryTheStateAndFlareIsForTheServersStops() {
        assertEquals(AfkLook.Tone.OK, AfkLook.tone(status("a", AfkState.IN_GAME, null)));
        assertEquals(AfkLook.Signal.ONLINE, AfkLook.signal(status("a", AfkState.IN_GAME, null)));
        for (AfkState joining : new AfkState[]{AfkState.CONNECTING, AfkState.LOGGING_IN, AfkState.CONFIGURING}) {
            assertEquals(AfkLook.Tone.MUTED, AfkLook.tone(status("a", joining, null)));
            assertEquals(AfkLook.Signal.PENDING, AfkLook.signal(status("a", joining, null)));
        }
        assertEquals(AfkLook.Tone.WARN, AfkLook.tone(status("a", AfkState.WAITING, AfkReason.KICKED)));
        assertEquals(AfkLook.Signal.PENDING, AfkLook.signal(status("a", AfkState.WAITING, AfkReason.KICKED)));
        assertEquals(AfkLook.Tone.WARN, AfkLook.tone(status("a", AfkState.NO_NETWORK, AfkReason.WAITING_WIFI)));
        assertEquals(AfkLook.Signal.IDLE, AfkLook.signal(status("a", AfkState.NO_NETWORK, AfkReason.WAITING_WIFI)));
        // The player's own stop is quiet; the server's refusal is flare with the cross; the phone's stop a warning.
        assertEquals(AfkLook.Tone.MUTED, AfkLook.tone(status("a", AfkState.STOPPED, null)));
        assertEquals(AfkLook.Signal.IDLE, AfkLook.signal(status("a", AfkState.STOPPED, null)));
        assertEquals(AfkLook.Tone.ERROR, AfkLook.tone(status("a", AfkState.STOPPED, AfkReason.BANNED)));
        assertEquals(AfkLook.Signal.OFFLINE, AfkLook.signal(status("a", AfkState.STOPPED, AfkReason.BANNED)));
        assertEquals(AfkLook.Tone.WARN, AfkLook.tone(status("a", AfkState.STOPPED, AfkReason.BATTERY_LOW)));
        assertEquals(AfkLook.Signal.IDLE, AfkLook.signal(status("a", AfkState.STOPPED, AfkReason.PROCESS_ENDED)));
    }

    @Test
    public void theMainButtonFixesWhatTheServerRefused() {
        assertEquals(AfkLook.Action.STOP, AfkLook.action(status("a", AfkState.WAITING, AfkReason.KICKED)));
        assertEquals(AfkLook.Action.STOP, AfkLook.action(status("a", AfkState.IN_GAME, null)));
        assertEquals(AfkLook.Action.CONNECT, AfkLook.action(status("a", AfkState.STOPPED, null)));
        assertEquals(AfkLook.Action.CONNECT, AfkLook.action(status("a", AfkState.STOPPED, AfkReason.BANNED)));
        assertEquals(AfkLook.Action.FIX_PASSWORD, AfkLook.action(status("a", AfkState.STOPPED, AfkReason.WRONG_PASSWORD)));
        assertEquals(AfkLook.Action.FIX_LOGIN, AfkLook.action(status("a", AfkState.STOPPED, AfkReason.LOGIN_TIMEOUT)));
    }

    @Test
    public void connectAllLeavesOutRunningAccountsAndTheServersRefusals() {
        assertTrue(AfkLook.bulkConnects(status("a", AfkState.STOPPED, null)));
        assertTrue(AfkLook.bulkConnects(status("a", AfkState.STOPPED, AfkReason.BATTERY_LOW)));
        assertTrue(AfkLook.bulkConnects(status("a", AfkState.STOPPED, AfkReason.PROCESS_ENDED)));
        assertFalse(AfkLook.bulkConnects(status("a", AfkState.STOPPED, AfkReason.WRONG_PASSWORD)));
        assertFalse(AfkLook.bulkConnects(status("a", AfkState.STOPPED, AfkReason.BANNED)));
        assertFalse(AfkLook.bulkConnects(status("a", AfkState.CONNECTING, null)));
    }

    @Test
    public void spansHaveTwoUnitsAtMostAndSecondsOnlyInTheFirstMinute() {
        int s = AfkLook.Unit.SECOND.ordinal(), m = AfkLook.Unit.MINUTE.ordinal(), h = AfkLook.Unit.HOUR.ordinal(), d = AfkLook.Unit.DAY.ordinal();
        assertArrayEquals(new int[]{0, s}, AfkLook.span(-5));
        assertArrayEquals(new int[]{45, s}, AfkLook.span(45_900));
        assertArrayEquals(new int[]{1, m}, AfkLook.span(60_000));
        assertArrayEquals(new int[]{59, m}, AfkLook.span(3_599_000));
        assertArrayEquals(new int[]{1, h}, AfkLook.span(3_600_000));
        assertArrayEquals(new int[]{1, h, 5, m}, AfkLook.span(3_900_000));
        assertArrayEquals(new int[]{23, h, 59, m}, AfkLook.span(86_399_000));
        assertArrayEquals(new int[]{1, d}, AfkLook.span(86_400_000));
        assertArrayEquals(new int[]{2, d, 3, h}, AfkLook.span(2 * 86_400_000L + 3 * 3_600_000L + 59_000));
    }

    @Test
    public void wordsTickOnlyWhileTheyChange() {
        AfkStatus inGame = new AfkStatus("a", "A", AfkState.IN_GAME, 0, 10_000, 0, 0, null, null);
        assertEquals(1000, AfkLook.nextChange(inGame, 10_000));
        assertEquals(700, AfkLook.nextChange(inGame, 10_300));
        // After the first minute the words change once a minute.
        assertEquals(60_000 - 5_000, AfkLook.nextChange(inGame, 10_000 + 65_000));
        AfkStatus waiting = new AfkStatus("a", "A", AfkState.WAITING, 0, 0, 200_000, 1, AfkReason.KICKED, null);
        // 125 s left reads "3 dakika" until 120 s are left.
        assertEquals(5_000, AfkLook.nextChange(waiting, 75_000));
        assertEquals(400, AfkLook.nextChange(waiting, 200_000 - 30_400));
        assertEquals(0, AfkLook.nextChange(new AfkStatus("a", "A", AfkState.STOPPED, 0, 0, 0, 0, null, null), 5));
        assertEquals(0, AfkLook.nextChange(new AfkStatus("a", "A", AfkState.CONNECTING, 0, 0, 0, 0, null, null), 5));
        assertEquals(-1, AfkLook.uptime(new AfkStatus("a", "A", AfkState.IN_GAME, 0, 0, 0, 0, null, null), 5));
    }
}
