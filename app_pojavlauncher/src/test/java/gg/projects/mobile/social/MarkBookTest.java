package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * BADGES: when the phone reads the badge catalogue again ({@code GET /v1/marks}): the built-in one until the kept copy
 * is in, a revision the service names, a badge a card names that the catalogue does not know (at most every 30
 * minutes), one read at a time, and never in a loop.
 */
public class MarkBookTest {
    private static final long MINUTE = 60_000L;

    private static MarkStore.Kept kept(long rev, String etag) {
        return new MarkStore.Kept(new MarkCatalog(rev, MarkCatalog.BUILTIN.defs), etag);
    }

    private static final List<String> UNKNOWN = Collections.singletonList("m12");

    @Test
    public void aStartDrawsTheBuiltInCatalogueUntilTheKeptOneIsIn() {
        MarkBook book = new MarkBook();
        assertSame(MarkCatalog.BUILTIN, book.catalog());
        assertTrue(book.takeLoad());
        assertFalse(book.takeLoad());
        // Nothing kept, nothing named: no read.
        assertFalse(book.loaded(null));
        assertSame(MarkCatalog.BUILTIN, book.catalog());
        // Loaded once only.
        assertFalse(book.loaded(kept(4, "\"4\"")));
        assertSame(MarkCatalog.BUILTIN, book.catalog());
    }

    @Test
    public void aRevisionNamedBeforeTheKeptCopyWaitsForIt() {
        MarkBook same = new MarkBook();
        assertFalse(same.named(3));
        // The kept copy is that revision: nothing to read.
        assertFalse(same.loaded(kept(3, "\"3\"")));
        assertEquals(3, same.catalog().rev);
        MarkBook older = new MarkBook();
        assertFalse(older.named(3));
        assertTrue(older.loaded(kept(2, "\"2\"")));
        // The ETag the kept copy came with goes back.
        assertEquals("\"2\"", older.begin());
    }

    @Test
    public void theRevisionHeldReadsNothing() {
        MarkBook book = new MarkBook();
        book.loaded(null);
        // The service never saved a catalogue: it is the built-in one.
        assertFalse(book.named(0));
        assertFalse(book.named(-1));
        assertTrue(book.named(1));
    }

    @Test
    public void oneReadAtATimeAndOnceMoreForAChangeMeanwhile() {
        MarkBook book = new MarkBook();
        book.loaded(null);
        assertTrue(book.named(3));
        assertNull(book.begin());
        assertTrue(book.reading());
        // Named again while the read is on its way: no second read now.
        assertFalse(book.named(4));
        // The read brought 3: 4 is still newer, so once more.
        assertTrue(book.ended(kept(3, "\"3\"")));
        assertEquals(3, book.catalog().rev);
        assertEquals("\"3\"", book.begin());
        assertFalse(book.ended(kept(4, "\"4\"")));
        assertFalse(book.reading());
        assertEquals(4, book.catalog().rev);
    }

    @Test
    public void aFailedOrUnchangedReadWaitsForTheNextTrigger() {
        MarkBook book = new MarkBook();
        book.loaded(kept(2, "\"2\""));
        assertTrue(book.named(3));
        book.begin();
        // A failure (or a 304 that disagrees): the catalogue held stays, no loop.
        assertFalse(book.ended(null));
        assertEquals(2, book.catalog().rev);
        // The next list read names 3 again: one more try.
        assertTrue(book.named(3));
    }

    @Test
    public void anUnknownBadgeReadsAtMostEveryHalfHour() {
        MarkBook book = new MarkBook();
        book.loaded(null);
        // A service that named no revision has no catalogue to read.
        assertFalse(book.saw(UNKNOWN, 0));
        assertFalse(book.named(0));
        long t = 5 * MINUTE;
        assertTrue(book.saw(UNKNOWN, t));
        // The revision held is read anyway (the card was made with a newer one).
        book.begin();
        assertFalse(book.saw(UNKNOWN, t + MINUTE));
        assertFalse(book.ended(null));
        assertFalse(book.saw(UNKNOWN, t + 29 * MINUTE));
        assertTrue(book.saw(UNKNOWN, t + MarkBook.UNKNOWN_GAP_MS));
    }

    @Test
    public void knownBadgesReadNothing() {
        MarkBook book = new MarkBook();
        book.loaded(null);
        book.named(0);
        assertFalse(book.saw(Arrays.asList("admin", "premium"), 0));
        assertFalse(book.saw(Collections.<String>emptyList(), 0));
    }

    @Test
    public void anUnknownBadgeWaitsForTheKeptCopy() {
        MarkBook book = new MarkBook();
        book.named(0);
        // The kept copy may know it.
        assertFalse(book.saw(UNKNOWN, 0));
        assertFalse(book.loaded(new MarkStore.Kept(new MarkCatalog(0, Arrays.asList(
                new MarkCatalog.Def("m12", "Yaz turnuvası", "Summer cup", "pink", "trophy", "shield"))), null)));
        assertFalse(book.saw(UNKNOWN, MINUTE));
    }
}
