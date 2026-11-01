package gg.projects.mobile.afk.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import gg.projects.mobile.afk.api.AfkAccount;
import gg.projects.mobile.afk.api.AfkLoginMode;
import gg.projects.mobile.afk.api.AfkReason;

/** accounts.json and runtime.json: written whole, read back, broken files read as nothing. */
public class AfkStoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void accountsRoundTripWithTheirSealedCommands() throws Exception {
        File dir = tmp.newFolder("afk");
        AfkStore store = new AfkStore(dir);
        assertTrue(store.entries().isEmpty());
        AfkAccount a = new AfkAccount(AfkStore.newId(), "SleepyFox_42", "play.example.com", 0, 2, AfkLoginMode.FIRST_JOIN,
                false, true, 123L);
        AfkAccount b = new AfkAccount(AfkStore.newId(), "Night_Owl", "2001:db8::1", 25566, 0, AfkLoginMode.EVERY_JOIN,
                true, false, 456L);
        store.write(Arrays.asList(new AfkStore.Entry(a, "k1:abc"), new AfkStore.Entry(b, "")));
        List<AfkStore.Entry> read = new AfkStore(dir).entries();
        assertEquals(2, read.size());
        AfkAccount r = read.get(0).account;
        assertEquals(a.id, r.id);
        assertEquals("SleepyFox_42", r.name);
        assertEquals(AfkLoginMode.FIRST_JOIN, r.loginMode);
        assertEquals(2, r.loginCommandCount);
        assertEquals(true, r.mobileData);
        assertEquals(false, r.sneak);
        assertEquals(123L, r.createdAt);
        assertEquals("k1:abc", read.get(0).sealed);
        assertEquals("[2001:db8::1]:25566", read.get(1).account.address());
        assertEquals(16, a.id.length());
        assertNull(store.find("nope"));
        assertEquals("Night_Owl", store.find(b.id).account.name);
        // The secrets never show in the account's text.
        assertTrue(!read.get(0).account.toString().contains("k1:"));
    }

    @Test
    public void aBrokenFileReadsAsNoAccounts() throws Exception {
        File dir = tmp.newFolder("afk");
        try (FileOutputStream out = new FileOutputStream(new File(dir, AfkStore.FILE))) {
            out.write("{\"accounts\":[{\"id\":\"x\",\"name\":\"no\"}, 5] trailing".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(new AfkStore(dir).entries().isEmpty());
        try (FileOutputStream out = new FileOutputStream(new File(dir, AfkStore.FILE))) {
            out.write("{\"v\":1,\"accounts\":[{\"id\":\"x\",\"name\":\"ab\",\"host\":\"h\"},{\"id\":\"y\",\"name\":\"Good_1\",\"host\":\"h.com\",\"port\":99999}]}"
                    .getBytes(StandardCharsets.UTF_8));
        }
        List<AfkStore.Entry> read = new AfkStore(dir).entries();
        assertEquals(1, read.size());
        assertEquals(0, read.get(0).account.port);
    }

    @Test
    public void runtimeRoundTrip() throws Exception {
        File dir = tmp.newFolder("afk");
        AfkRuntime rt = new AfkRuntime();
        rt.pid = 4242;
        rt.startedAt = 10;
        rt.updatedAt = 20;
        rt.running.add("a");
        rt.runningNames.add("SleepyFox_42");
        rt.stops.put("b", new AfkRuntime.Stop(AfkReason.BANNED, "You are banned", 15));
        rt.write(dir);
        AfkRuntime read = AfkRuntime.read(dir);
        assertEquals(4242, read.pid);
        assertEquals(Arrays.asList("a"), read.running);
        assertEquals(Arrays.asList("SleepyFox_42"), read.runningNames);
        assertEquals(AfkReason.BANNED, read.stops.get("b").reason);
        assertEquals("You are banned", read.stops.get("b").message);
        assertTrue(AfkRuntime.read(tmp.newFolder("empty")).running.isEmpty());
    }
}
