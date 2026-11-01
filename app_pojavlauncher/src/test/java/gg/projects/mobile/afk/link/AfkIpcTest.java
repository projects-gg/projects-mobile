package gg.projects.mobile.afk.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

/** What the screens and the service say to each other survives the JSON both ways. */
public class AfkIpcTest {
    @Test
    public void statusesRoundTrip() {
        AfkStatus waiting = new AfkStatus("a", "SleepyFox_42", AfkState.WAITING, 10, 0, 50, 3, AfkReason.KICKED, "Bye");
        AfkStatus stopped = AfkStatus.stopped("b", "Night_Owl");
        List<AfkStatus> back = AfkIpc.statuses(AfkIpc.statuses(Arrays.asList(waiting, stopped)));
        assertEquals(2, back.size());
        AfkStatus w = back.get(0);
        assertEquals(AfkState.WAITING, w.state);
        assertEquals(50, w.nextAttemptAt);
        assertEquals(3, w.attempt);
        assertEquals(AfkReason.KICKED, w.reason);
        assertEquals("Bye", w.serverMessage);
        assertEquals(AfkState.STOPPED, back.get(1).state);
        assertNull(back.get(1).reason);
        // A newer service's state reads as connecting; broken JSON as nothing.
        assertEquals(AfkState.CONNECTING, AfkIpc.status(AfkIpc.object("{\"a\":\"x\",\"s\":\"flying\"}")).state);
        assertTrue(AfkIpc.statuses("not json").isEmpty());
    }

    @Test
    public void linesAndAlertsRoundTrip() {
        AfkLine line = new AfkLine("a", 7, 99, AfkLine.Kind.PRIVATE, "[Ember_77 -> ben] selam",
                Collections.singletonList(new AfkLine.Span(1, 9, 0xFFAA00, AfkLine.BOLD)), "Ember_77", true);
        AfkLine back = AfkIpc.lines(AfkIpc.lines(Collections.singletonList(line))).get(0);
        assertEquals(7, back.seq);
        assertEquals(AfkLine.Kind.PRIVATE, back.kind);
        assertEquals("[Ember_77 -> ben] selam", back.text);
        assertEquals(0xFFAA00, back.spans.get(0).color);
        assertEquals(AfkLine.BOLD, back.spans.get(0).style);
        assertEquals("Ember_77", back.sender);
        assertTrue(back.mentioned);
        AfkAlert alert = new AfkAlert("a", "SleepyFox_42", AfkAlert.Kind.STOPPED, "", AfkReason.BANNED, 5);
        AfkAlert a = AfkIpc.alert(AfkIpc.object(AfkIpc.alert(alert).toString()));
        assertEquals(AfkAlert.Kind.STOPPED, a.kind);
        assertEquals(AfkReason.BANNED, a.reason);
        assertNull(AfkIpc.alert(AfkIpc.object("{\"k\":\"unknown\"}")));
    }
}
