package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** What the phone keeps of the audit log: each entry once, newest first, 7 days, bounded, gone with its account. */
public class PlusAuditCacheTest {
    private static final String A = "a_7KQ2MZ3P9TW4XB1C";
    private static final String B = "a_8RT4WQ2N6P1KJH3D";
    private static final long NOW = 1792846800_000L;

    private static PlusModel.AuditEntry entry(long s, long t, String k) {
        return PlusModel.AuditEntry.read(Vectors.json("{\"s\":" + s + ",\"t\":" + t + ",\"k\":\"" + k + "\",\"w\":\"lavci_42\"}"));
    }

    @Test
    public void keepsEachEntryOnceNewestFirstForSevenDays() throws Exception {
        File dir = Files.createTempDirectory("plus-audit").toFile();
        PlusAuditCache cache = new PlusAuditCache(dir);
        assertTrue(cache.read(A, NOW).isEmpty());
        cache.merge(A, Arrays.asList(entry(20750_000_001L, NOW - 60_000, "pm_in"), entry(20750_000_002L, NOW - 30_000, "reply")), NOW);
        // A page that brings an entry again and an older one.
        cache.merge(A, Arrays.asList(entry(20750_000_002L, NOW - 30_000, "reply"), entry(20743_000_001L, NOW - 8L * 86_400_000, "offer"),
                entry(20749_000_005L, NOW - 86_400_000, "agree")), NOW);
        List<PlusModel.AuditEntry> kept = cache.read(A, NOW);
        List<String> kinds = new ArrayList<>();
        for (PlusModel.AuditEntry e : kept) kinds.add(e.k);
        // The 8-day-old entry was not kept; newest first by sequence.
        assertEquals(Arrays.asList("reply", "pm_in", "agree"), kinds);
        // Read a day later than 7 days after the oldest: it goes.
        assertEquals(2, cache.read(A, NOW + 6L * 86_400_000 + 1).size());
        assertTrue(cache.read(B, NOW).isEmpty());
        assertEquals("lavci_42", kept.get(0).w);
    }

    @Test
    public void boundedAndForgotten() throws Exception {
        File dir = Files.createTempDirectory("plus-audit").toFile();
        PlusAuditCache cache = new PlusAuditCache(dir);
        List<PlusModel.AuditEntry> many = new ArrayList<>();
        for (int i = 0; i < PlusAuditCache.MAX_ENTRIES + 50; i++) many.add(entry(20750_000_000L + i, NOW - i * 1000L, "pm_in"));
        cache.merge(A, many, NOW);
        List<PlusModel.AuditEntry> kept = cache.read(A, NOW);
        assertEquals(PlusAuditCache.MAX_ENTRIES, kept.size());
        assertEquals(20750_000_000L + PlusAuditCache.MAX_ENTRIES + 49, kept.get(0).s);
        cache.merge(B, Collections.singletonList(entry(20750_000_001L, NOW, "reply")), NOW);
        cache.keepOnly(Collections.singletonList(B));
        assertTrue(cache.read(A, NOW).isEmpty());
        assertFalse(cache.read(B, NOW).isEmpty());
        cache.drop(B);
        assertTrue(cache.read(B, NOW).isEmpty());
        // A name that is not an account's id is never a file name.
        assertTrue(cache.merge("../x", Collections.singletonList(entry(1, NOW, "pm_in")), NOW).isEmpty());
    }
}
