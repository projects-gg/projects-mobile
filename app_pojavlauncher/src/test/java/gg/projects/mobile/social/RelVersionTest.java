package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * M048 (SCALE-SOC on the phone; scale-soc.md, the phone's spec 2-5 and 7): the relations version the phone holds for
 * the lists it shows. A read keeps the version it brings unless the copy changed meanwhile; the connect names it; the
 * hello decides whether the lists are read (the same version: no read); every change of the lists or the profile forgets
 * it. Then a day of starts with the copy kept on the phone (spec 6), as SocialService runs them.
 */
public class RelVersionTest {
    private static final String REL = "0123456789abcdef";
    private static final String NEXT = "fedcba9876543210";
    private static final List<String> CAPS = Arrays.asList("callServer", "crews", "plans");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aReadKeepsTheVersionItBringsAndTheConnectNamesIt() {
        RelVersion rel = new RelVersion();
        assertNull(rel.held());
        assertEquals("v1/presence?caps=callServer,crews,plans", RelVersion.presencePath(CAPS, rel.held()));

        assertTrue(rel.keep(REL, rel.readStarts()));
        assertEquals(REL, rel.held());
        assertEquals("v1/presence?caps=callServer,crews,plans&rel=" + REL, RelVersion.presencePath(CAPS, rel.held()));
        assertEquals("v1/presence?rel=" + REL, RelVersion.presencePath(Collections.<String>emptyList(), REL));

        // A later read of an older service brings none: the one held no longer describes the lists shown.
        assertFalse(rel.keep(null, rel.readStarts()));
        assertNull(rel.held());
    }

    @Test
    public void aChangeWhileTheListsAreReadKeepsNoVersion() {
        RelVersion rel = new RelVersion();
        long since = rel.readStarts();
        // An event (or the player's own change) while the read is on its way.
        rel.forget();
        assertFalse(rel.keep(REL, since));
        assertNull(rel.held());
        // The read after it keeps its own.
        assertTrue(rel.keep(NEXT, rel.readStarts()));
        assertEquals(NEXT, rel.held());
        rel.forget();
        assertNull(rel.held());
    }

    @Test
    public void onlyAVersionAsTheServiceWritesItIsKeptOrNamed() {
        for (String bad : Arrays.asList(null, "", "0123456789ABCDEF", "0123456789abcde", "0123456789abcdef0", "0123456789abcdeg",
                " 0123456789abcdef", "0123456789abcdef\n", "0123456789abcdef&x=1")) {
            RelVersion rel = new RelVersion();
            assertFalse(String.valueOf(bad), rel.keep(bad, rel.readStarts()));
            assertNull(rel.held());
            assertFalse(String.valueOf(bad), KeptLists.isRel(bad));
            assertEquals("v1/presence?caps=callServer,crews,plans", RelVersion.presencePath(CAPS, bad));
        }
        assertTrue(KeptLists.isRel(REL));
    }

    @Test
    public void theHelloDecidesWhetherTheListsAreRead() {
        RelVersion rel = new RelVersion();
        rel.keep(REL, rel.readStarts());
        // The service's version is the one held: nothing is read, the version stays.
        assertEquals(RelVersion.Hello.SAME, rel.hello(REL, true));
        assertEquals(REL, rel.held());
        // No version in the hello (none named, an older service): the usual rule, the version held stays.
        assertEquals(RelVersion.Hello.UNSAID, rel.hello(null, true));
        assertEquals(RelVersion.Hello.UNSAID, rel.hello("NOT-HEX", true));
        assertEquals(REL, rel.held());
        // The lists are not loaded: read whatever the version.
        assertEquals(RelVersion.Hello.CHANGED, rel.hello(REL, false));
        assertNull(rel.held());
        // Another version: read, and the one held is dropped (the copy is older than the service's).
        rel.keep(REL, rel.readStarts());
        assertEquals(RelVersion.Hello.CHANGED, rel.hello(NEXT, true));
        assertNull(rel.held());
        // Nothing held: a hello with a version reads.
        assertEquals(RelVersion.Hello.CHANGED, rel.hello(NEXT, true));
    }

    @Test
    public void anUnforcedReadWaitsForTheHelloWhileAVersionIsHeld() {
        RelVersion rel = new RelVersion();
        assertFalse(rel.refreshSkips(false, true, true));
        rel.keep(REL, rel.readStarts());
        // A screen coming up or a setting changing reads nothing: the hello of the connection up or on its way decides.
        assertTrue(rel.refreshSkips(false, true, true));
        // No connection, so no hello will come (replaced elsewhere, failed and waiting to retry): the usual rule reads.
        assertFalse(rel.refreshSkips(false, true, false));
        // "Tekrar dene" reads; lists not loaded are read.
        assertFalse(rel.refreshSkips(true, true, true));
        assertFalse(rel.refreshSkips(false, false, true));
        rel.forget();
        assertFalse(rel.refreshSkips(false, true, true));
    }

    @Test
    public void exactlyTheChangesOfTheListsAndTheProfileForgetTheVersion() {
        // The launcher's changes_relations (pl-social service.rs), BADGES' "marks" (every badge of someone) included.
        for (String type : Arrays.asList("friend_added", "friend_removed", "request", "request_cancelled", "team_invite", "team",
                "team_requests", "crew_order", "crew_notifications", "team_notifications", "groups", "badge", "mark", "marks",
                "status", "profile", "notice")) {
            assertTrue(type, RelVersion.changesRelations(type));
        }
        // The badge catalogue's revision ("markdefs") is no relation: it is read on its own (GET v1/marks).
        for (String type : Arrays.asList(null, "", "hello", "presence", "invite", "invites", "call", "call_answer", "call_end",
                "chat", "chat_state", "chat_synced", "chat_typing", "typing", "plan", "plans", "plan_removed", "bye", "afk_notice",
                "markdefs", "ack", "error", "TEAM", "team ")) {
            assertFalse(String.valueOf(type), RelVersion.changesRelations(type));
        }
    }

    /**
     * SOC-REL-M (the lead's decision, 1 November): a push forgets the version and the copy kept for a start only when it
     * changes what the list shows. A friend request does (its notification opens the list, which must show it at once);
     * a message, a crew call, its end and "Birlikte oyna" (a game invite, not in the list) do not: the version stays, an
     * unforced read still waits for the hello, and the next start the same day finds the copy. SocialService and SocialPush
     * decide with pushChangesLists (RelWiringTest).
     */
    @Test
    public void onlyAPushThatChangesTheListsForgetsTheVersion() {
        File dir = new File(tmp.getRoot(), "lists");
        final String me = SocialStateTest.ME;
        final long day = 20758;
        for (String kind : Arrays.asList(PushMessage.REQUEST, PushMessage.INVITE, PushMessage.MESSAGE, PushMessage.CALL,
                PushMessage.CALL_END, "afk", null, "", "REQUEST")) {
            KeptLists kept = new KeptLists(dir);
            RelVersion rel = new RelVersion();
            assertTrue(rel.keep(REL, rel.readStarts()));
            assertTrue(kept.save(kept.generation(), me, day, KeptListsTest.answer(me, "Blaze_Runner", REL)));
            // The service's pushArrived (and SocialPush's clear while the app is not running).
            boolean forgets = RelVersion.pushChangesLists(kind);
            if (forgets) {
                rel.forget();
                kept.clear();
            }
            boolean request = PushMessage.REQUEST.equals(kind);
            assertEquals(String.valueOf(kind), request, forgets);
            assertEquals(String.valueOf(kind), request ? null : REL, rel.held());
            assertEquals(String.valueOf(kind), !request, rel.refreshSkips(false, true, true));
            assertEquals(String.valueOf(kind), !request, new KeptLists(dir).load(me, day) != null);
            new KeptLists(dir).clear();
        }
    }

    /**
     * A day with the copy kept on the phone, as SocialService runs it: the first start reads the lists and keeps them;
     * the next start the same day shows them, names their version and reads nothing when the hello names the same; a
     * change on the socket takes the copy, so the start after reads again; the next day's start reads too.
     */
    @Test
    public void aDayOfStartsReadsOnlyWhenSomethingChanged() {
        File dir = new File(tmp.getRoot(), "lists");
        final String me = SocialStateTest.ME;
        final long day = 20758;

        // Start 1: nothing kept; the read brings the version and the answer is kept.
        KeptLists kept = new KeptLists(dir);
        RelVersion rel = new RelVersion();
        assertNull(kept.load(me, day));
        long since = rel.readStarts();
        long generation = kept.generation();
        assertTrue(rel.keep(REL, since));
        assertTrue(kept.save(generation, me, day, KeptListsTest.answer(me, "Blaze_Runner", REL)));

        // Start 2 (a new process): the copy shows at once, the connect names it, the hello says the same: no read.
        kept = new KeptLists(dir);
        rel = new RelVersion();
        KeptLists.Copy copy = kept.load(me, day);
        assertNotNull(copy);
        assertTrue(rel.keep(copy.rel, rel.readStarts()));
        assertTrue(RelVersion.presencePath(CAPS, rel.held()).endsWith("&rel=" + REL));
        assertEquals(RelVersion.Hello.SAME, rel.hello(REL, true));
        assertTrue(rel.refreshSkips(false, true, true));

        // A friend request on the socket: the copy is no longer its version's; both go.
        assertTrue(RelVersion.changesRelations("request"));
        rel.forget();
        kept.clear();
        assertNull(rel.held());
        assertEquals("v1/presence?caps=callServer,crews,plans", RelVersion.presencePath(CAPS, rel.held()));

        // Start 3: nothing kept, so the lists are read as before; their answer is kept again.
        kept = new KeptLists(dir);
        rel = new RelVersion();
        assertNull(kept.load(me, day));
        assertTrue(rel.keep(NEXT, rel.readStarts()));
        assertTrue(kept.save(kept.generation(), me, day, KeptListsTest.answer(me, "Blaze_Runner", NEXT)));

        // Start 4, the same day, but the service's version moved on while the phone was away: one read.
        rel = new RelVersion();
        copy = new KeptLists(dir).load(me, day);
        assertNotNull(copy);
        rel.keep(copy.rel, rel.readStarts());
        assertEquals(RelVersion.Hello.CHANGED, rel.hello(REL, true));
        assertFalse(rel.refreshSkips(false, true, true));

        // The next day: yesterday's copy is no copy (the version holds the day).
        assertNull(new KeptLists(dir).load(me, day + 1));
    }
}
