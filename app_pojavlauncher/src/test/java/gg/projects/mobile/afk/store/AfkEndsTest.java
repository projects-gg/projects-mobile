package gg.projects.mobile.afk.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import gg.projects.mobile.afk.api.AfkEnd;

/**
 * end.json, the morning notice ("Telefonun AFK'yı 06:13'te kapattı."): recorded once for a closed {@code :afk} process
 * that left accounts running. MOB-NEXT: when AFK came back by itself (Android restarted the service, or the watchdog
 * did) that process's end needs no notice; before, a killed process that came back half a second later still left the
 * notice for the morning.
 */
public class AfkEndsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static AfkRuntime runtime(int pid, long startedAt, String... names) {
        AfkRuntime r = new AfkRuntime();
        r.pid = pid;
        r.startedAt = startedAt;
        r.updatedAt = startedAt + 60_000;
        for (String name : names) {
            r.running.add("id-" + name);
            r.runningNames.add(name);
        }
        return r;
    }

    private static final AfkEnds.Exits CLOSED_AT_0613 = pid -> new AfkEnds.Exit(AfkEnd.Kind.CLOSED_BY_PHONE, 1_762_053_180_000L);

    @Test
    public void aClosedProcessThatStayedClosedLeavesTheNoticeOnce() throws Exception {
        File dir = tmp.newFolder("afk");
        AfkRuntime closed = runtime(4242, 1_000L, "SleepyFox_42", "Night_Owl");
        AfkEnd end = AfkEnds.record(dir, closed, CLOSED_AT_0613);
        assertNotNull(end);
        assertEquals(AfkEnd.Kind.CLOSED_BY_PHONE, end.kind);
        AfkEnd shown = AfkEnds.read(dir);
        assertNotNull(shown);
        assertEquals(1_762_053_180_000L, shown.at);
        assertEquals(2, shown.names.size());
        // The same process is never reported twice.
        assertNull(AfkEnds.record(dir, closed, CLOSED_AT_0613));
        assertNotNull(AfkEnds.read(dir));
    }

    @Test
    public void aProcessKilledThatCameBackByItselfLeavesNoNotice() throws Exception {
        File dir = tmp.newFolder("afk");
        AfkRuntime killed = runtime(4242, 1_000L, "SleepyFox_42");
        // The new process's service records the end it finds, then brings the accounts back.
        assertNotNull(AfkEnds.record(dir, killed, CLOSED_AT_0613));
        AfkEnds.cameBack(dir, 4242, 1_000L);
        assertNull("no morning notice", AfkEnds.read(dir));
        // The screens looking again (the process is gone from their view for a moment) report nothing new.
        assertNull(AfkEnds.record(dir, killed, CLOSED_AT_0613));
        assertNull(AfkEnds.read(dir));
    }

    @Test
    public void anotherProcessesEndStaysUntilSeen() throws Exception {
        File dir = tmp.newFolder("afk");
        assertNotNull(AfkEnds.record(dir, runtime(4242, 1_000L, "SleepyFox_42"), CLOSED_AT_0613));
        // A later process came back: not the one whose end the player has not seen.
        AfkEnds.cameBack(dir, 5151, 2_000L);
        AfkEnds.cameBack(dir, 4242, 9_999L);
        AfkEnds.cameBack(dir, 0, 1_000L);
        assertNotNull(AfkEnds.read(dir));
        // Nothing recorded, nothing to set aside.
        File empty = tmp.newFolder("empty");
        AfkEnds.cameBack(empty, 4242, 1_000L);
        assertNull(AfkEnds.read(empty));
    }

    @Test
    public void nothingRunningOrNoProcessIsNoEnd() throws Exception {
        File dir = tmp.newFolder("afk");
        assertNull(AfkEnds.record(dir, runtime(4242, 1_000L), CLOSED_AT_0613));
        assertNull(AfkEnds.record(dir, runtime(0, 1_000L, "SleepyFox_42"), CLOSED_AT_0613));
        // Without Android's account (before Android 11) the kind is unknown and the time the file's last write.
        AfkEnd end = AfkEnds.record(dir, runtime(77, 5_000L, "Night_Owl"), pid -> null);
        assertNotNull(end);
        assertEquals(AfkEnd.Kind.UNKNOWN, end.kind);
        assertEquals(65_000L, end.at);
    }
}
