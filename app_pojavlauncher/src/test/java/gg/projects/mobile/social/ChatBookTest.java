package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M20: the phone's chat (CHAT1, README "Chat"): conversation ids, live messages and unread counts,
 * sending under a nonce (waiting, sent, a lost answer, refusals), syncs, edits and deletes, who may do
 * what, the report's context, clearing the history and what the store keeps. FIX2: a deleted
 * message stays as its tombstone (text and seal erased, in memory and in the store), never unread and
 * never brought back.
 */
public class ChatBookTest {
    private static final String ME = "100000000000000011";
    private static final String B = "100000000000000012";
    private static final String C = "100000000000000013";
    private static final String CREW = "0a1b2c3d4e5f6a7b";
    private static final String DM = ChatConv.direct(ME, B);
    private static final String CREW_CHAT = ChatConv.crew(CREW);
    private static final long T = 1_792_521_000_000L;
    private static final String MAC = "AbCdEfGhIjKlMnOpQrStUv";

    private static JsonObject msg(String conv, long id, String from, String text, long editedAt, String nonce) {
        JsonObject m = new JsonObject();
        m.addProperty("conv", conv);
        m.addProperty("id", id);
        m.addProperty("from", from);
        m.addProperty("text", text);
        m.addProperty("mac", MAC.substring(0, 21) + (char) ('a' + (int) (id % 20)));
        if (editedAt > 0) m.addProperty("editedAt", editedAt);
        if (nonce != null) m.addProperty("nonce", nonce);
        return m;
    }

    private static JsonObject frame(String type, JsonObject msg) {
        JsonObject f = new JsonObject();
        f.addProperty("t", type);
        f.add("msg", msg);
        return f;
    }

    /** A store that keeps what it is told, like the app's file (a tombstone replaces only a row it holds). */
    private static final class MemoryStore implements ChatBook.Store {
        final Map<String, ChatMessage> messages = new HashMap<>();
        final Map<String, ChatBook.Tombstone> tombstones = new HashMap<>();
        final Map<String, ChatBook.Outbox> outbox = new HashMap<>();
        final Map<String, ChatBook.Meta> meta = new HashMap<>();
        long cursor;

        @Override public void putMessage(ChatMessage m) {
            if (!tombstones.containsKey(m.conv + "#" + m.id)) messages.put(m.conv + "#" + m.id, m);
        }
        @Override public void removeMessage(String conv, long id) { messages.remove(conv + "#" + id); }
        @Override public void putTombstone(String conv, long id, String by, long at) {
            ChatMessage held = messages.remove(conv + "#" + id);
            if (held != null) tombstones.put(conv + "#" + id, new ChatBook.Tombstone(conv, id, held.from, held.nonce, by, at));
        }
        @Override public void putOutbox(ChatBook.Outbox o) { outbox.put(o.nonce, o); }
        @Override public void removeOutbox(String nonce) { outbox.remove(nonce); }
        @Override public void putMeta(String conv, ChatBook.Meta m) { meta.put(conv, m); }
        @Override public void clear(String conv) {
            messages.keySet().removeIf(k -> k.startsWith(conv + "#"));
            tombstones.keySet().removeIf(k -> k.startsWith(conv + "#"));
            outbox.values().removeIf(o -> o.conv.equals(conv));
        }
        @Override public void putSyncCursor(long newestSeen) { cursor = newestSeen; }
    }

    @Test
    public void conversationIdsFollowTheService() {
        assertEquals("d:100000000000000011:100000000000000012", ChatConv.direct(B, ME));
        // A shorter id is the smaller one; the same length compares as text.
        assertEquals("d:99999999999999999:100000000000000000", ChatConv.direct("100000000000000000", "99999999999999999"));
        assertTrue(ChatConv.isDirect(DM));
        assertFalse(ChatConv.isDirect("d:100000000000000012:100000000000000011"));
        assertFalse(ChatConv.isDirect("d:" + ME + ":" + ME));
        assertTrue(ChatConv.isCrew(CREW_CHAT));
        assertEquals(CREW, ChatConv.crewId(CREW_CHAT));
        assertEquals(B, ChatConv.other(DM, ME));
        assertEquals(ME, ChatConv.other(DM, B));
        assertNull(ChatConv.other(DM, C));
        assertFalse(ChatConv.valid("x:1"));
    }

    @Test
    public void liveMessagesCountUntilRead() {
        ChatBook book = new ChatBook(ME);
        List<ChatBook.Event> events = book.apply(frame("chat", msg(DM, T, B, "Selam", 0, null)));
        assertEquals(ChatBook.Event.Kind.NEW, events.get(0).kind);
        assertEquals(1, book.unread(DM));
        assertEquals(T, book.firstUnread(DM));
        // The same message again (a sync's overlap) is no news.
        assertEquals(ChatBook.Event.Kind.EDITED, book.apply(frame("chat", msg(DM, T, B, "Selam", 0, null))).get(0).kind);
        assertEquals(1, book.unread(DM));
        // The player's own message from another device is not unread and not news.
        book.apply(frame("chat", msg(DM, T + 5, ME, "Naber", 0, "otherDevice1")));
        assertEquals(1, book.unread(DM));
        assertTrue(book.markRead(DM));
        assertEquals(0, book.unread(DM));
        assertFalse(book.markRead(DM));
        assertEquals(T + 5 - ChatBook.SYNC_OVERLAP_MS, book.syncSince());
        // Broken messages change nothing.
        JsonObject broken = msg(DM, T + 9, B, "x", 0, null);
        broken.addProperty("mac", "?");
        assertTrue(book.apply(frame("chat", broken)).isEmpty());
        assertEquals(2, book.entries(DM).size());
    }

    @Test
    public void aMessageWaitsThenGoesUnderItsNonce() {
        MemoryStore store = new MemoryStore();
        ChatBook book = new ChatBook(ME, store, ChatBook.Snapshot.empty());
        ChatBook.Outbox o = book.write(DM, "Geliyorum", "q8Zr3kT0aWbc", 1000);
        assertEquals("q8Zr3kT0aWbc", store.outbox.get("q8Zr3kT0aWbc").nonce);
        List<ChatBook.Entry> entries = book.entries(DM);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).pending());
        String key = entries.get(0).key();
        assertEquals(1, book.due(1000).size());
        JsonObject f = o.frame();
        assertEquals("chat", Json.str(f, "t"));
        assertEquals("q8Zr3kT0aWbc", Json.str(f, "nonce"));
        book.sending(o.nonce, 1000);
        assertTrue(book.due(1001).isEmpty());
        // The ack's message (without a nonce here) keeps this device's key.
        book.sent(o.nonce, msg(DM, T, ME, "Geliyorum", 0, null));
        entries = book.entries(DM);
        assertEquals(1, entries.size());
        assertNotNull(entries.get(0).message);
        assertEquals(key, entries.get(0).key());
        assertFalse(entries.get(0).pending());
        assertTrue(store.outbox.isEmpty());
        assertEquals(1, store.messages.size());
        assertEquals(T, store.cursor);
        assertEquals(0, book.unread(DM));
    }

    @Test
    public void aLostAnswerTriesAgainWithTheSameNonceThenFails() {
        ChatBook book = new ChatBook(ME);
        ChatBook.Outbox o = book.write(DM, "Hey", "n1", 0);
        for (int i = 1; i < ChatBook.MAX_ATTEMPTS; i++) {
            book.sending(o.nonce, i * 10);
            book.notSent(o.nonce, "unavailable", null, 0, i * 10);
            assertEquals(1, book.due(i * 10).size());
            assertEquals("n1", book.due(i * 10).get(0).nonce);
        }
        book.sending(o.nonce, 100);
        book.notSent(o.nonce, "unavailable", null, 0, 100);
        assertTrue(book.entries(DM).get(0).failed());
        assertEquals(ChatBook.Failure.UNAVAILABLE, book.outbox("n1").failure);
        // A connection that drops mid-send leaves it waiting, not failed.
        ChatBook.Outbox p = book.write(DM, "Again", "n2", 200);
        book.sending(p.nonce, 200);
        book.connectionLost();
        assertEquals(ChatBook.Outbox.State.WAITING, book.outbox("n2").state);
        // Waiting too long without a connection: it fails.
        book.expire(200 + ChatBook.RESEND_WINDOW_MS + 1);
        assertEquals(ChatBook.Outbox.State.FAILED, book.outbox("n2").state);
    }

    @Test
    public void refusalsSayWhy() {
        ChatBook book = new ChatBook(ME);
        book.write(DM, "discord.gg/x", "a", 0);
        book.notSent("a", "chat_rejected", "link", 0, 0);
        assertEquals(ChatBook.Failure.LINK, book.outbox("a").failure);
        book.write(DM, "x", "b", 0);
        book.notSent("b", "rate_limited", null, 3, 1000);
        assertEquals(ChatBook.Outbox.State.WAITING, book.outbox("b").state);
        assertTrue(book.due(2000).isEmpty());
        assertEquals(4000, book.nextDue(2000));
        assertEquals(1, book.due(4000).size());
        book.write(DM, "x", "c", 0);
        book.notSent("c", "rate_limited", null, 7200, 0);
        assertEquals(ChatBook.Failure.DAILY, book.outbox("c").failure);
        assertEquals(ChatBook.Failure.MUTED, ChatBook.failure("chat_muted", null, 0));
        assertEquals(ChatBook.Failure.NOT_FRIENDS, ChatBook.failure("friend_not_found", null, 0));
        assertEquals(ChatBook.Failure.NOT_IN_CREW, ChatBook.failure("team_not_found", null, 0));
        assertEquals(ChatBook.Failure.WORDS, ChatBook.failure("chat_rejected", "words", 0));
        assertEquals(ChatBook.Failure.PHONE, ChatBook.failure("chat_rejected", "phone", 0));
        assertEquals(ChatBook.Failure.OFF, ChatBook.failure("chat_off", null, 0));
    }

    @Test
    public void retriesKeepTheNonceWhileTheServiceRemembersIt() {
        ChatBook book = new ChatBook(ME);
        book.write(DM, "x", "keep", 0);
        book.sending("keep", 1000);
        book.notSent("keep", "chat_muted", null, 0, 1000);
        assertEquals("keep", book.retry("keep", "fresh", 1000 + ChatBook.NONCE_WINDOW_MS - 1).nonce);
        book.sending("keep", 2000);
        book.notSent("keep", "chat_off", null, 0, 2000);
        ChatBook.Outbox renewed = book.retry("keep", "fresh", 1000 + ChatBook.NONCE_WINDOW_MS + 1);
        assertEquals("fresh", renewed.nonce);
        assertNull(book.outbox("keep"));
        assertEquals(1, book.entries(DM).size());
        book.discard("fresh");
        assertTrue(book.entries(DM).isEmpty());
    }

    @Test
    public void aSyncFillsInEditsAndDeletesAndMatchesTheNonce() {
        ChatBook book = new ChatBook(ME);
        book.write(CREW_CHAT, "Geliyorum", "lost", 0);
        book.sending("lost", 0);
        book.connectionLost();
        JsonObject sync = new JsonObject();
        sync.addProperty("t", "chat_sync");
        sync.addProperty("conv", CREW_CHAT);
        JsonArray messages = new JsonArray();
        JsonObject one = msg(CREW_CHAT, T, B, "Gel", 0, null);
        one.remove("conv");
        messages.add(one);
        JsonObject mine = msg(CREW_CHAT, T + 1, ME, "Geliyorum", 0, "lost");
        mine.remove("conv");
        messages.add(mine);
        JsonObject edited = msg(CREW_CHAT, T + 2, C, "Neredesin?", T + 9, null);
        edited.remove("conv");
        messages.add(edited);
        sync.add("messages", messages);
        JsonArray deleted = new JsonArray();
        JsonObject gone = new JsonObject();
        gone.addProperty("id", T + 3);
        gone.addProperty("by", C);
        gone.addProperty("at", T + 4);
        deleted.add(gone);
        sync.add("deleted", deleted);
        book.apply(frame("chat", msg(CREW_CHAT, T + 3, C, "silinecek", 0, null)));
        assertEquals(ChatBook.Event.Kind.SYNCED, book.apply(sync).get(0).kind);
        List<ChatBook.Entry> entries = book.entries(CREW_CHAT);
        // FIX2: the message the sync says was deleted stays as its tombstone.
        assertEquals(4, entries.size());
        assertTrue(entries.get(3).deleted());
        assertEquals(C, entries.get(3).tombstone.by);
        assertEquals(T + 4, entries.get(3).tombstone.at);
        // The waiting message was taken before its answer got lost: it is the service's one now.
        assertNull(book.outbox("lost"));
        assertEquals("n:lost", entries.get(1).key());
        assertTrue(entries.get(2).message.edited());
        JsonObject synced = new JsonObject();
        synced.addProperty("t", "chat_synced");
        synced.addProperty("now", T + 100);
        synced.addProperty("more", "c2");
        assertEquals("c2", book.synced(synced, T));
        assertEquals(T + 100 + 5, book.hubNowMs(T + 5));
        synced.remove("more");
        assertNull(book.synced(synced, T));
    }

    @Test
    public void editsAndDeletesShowAtOnceAndComeBackWhenRefused() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", msg(DM, T, ME, "Geliyrum", 0, null)));
        ChatBook.Entry entry = book.entries(DM).get(0);
        assertTrue(book.canEdit(entry, T + ChatBook.EDIT_WINDOW_MS - 1));
        assertFalse(book.canEdit(entry, T + ChatBook.EDIT_WINDOW_MS));
        JsonObject edit = book.edit(DM, T, "Geliyorum");
        assertEquals("chat_edit", Json.str(edit, "t"));
        assertEquals("Geliyrum", Json.str(Json.obj(edit, "msg"), "text"));
        assertEquals("Geliyorum", book.entries(DM).get(0).text());
        assertTrue(book.entries(DM).get(0).pending());
        book.editFailed(DM, T, "chat_too_old");
        assertEquals("Geliyrum", book.entries(DM).get(0).text());
        book.edit(DM, T, "Geliyorum");
        book.edited(DM, T, msg(DM, T, ME, "Geliyorum", T + 60_000, null));
        ChatMessage after = book.message(DM, T);
        assertEquals("Geliyorum", after.text);
        assertTrue(after.edited());
        // The edited copy goes back as it is now (with editedAt: its seal covers it).
        assertEquals(T + 60_000, Json.num(book.delete(DM, T).getAsJsonObject("msg"), "editedAt", 0));
        assertTrue(book.entries(DM).get(0).deleting);
        book.deleteFailed(DM, T, "rate_limited");
        assertFalse(book.entries(DM).get(0).deleting);
        book.delete(DM, T);
        book.deleted(DM, T, T + 70_000);
        // FIX2: the player's own message stays as the player's tombstone, under the same key.
        assertEquals(1, book.entries(DM).size());
        ChatBook.Entry mine = book.entries(DM).get(0);
        assertTrue(mine.deleted());
        assertEquals(ME, mine.tombstone.by);
        assertEquals("", mine.text());
        assertNull(book.message(DM, T));
        // Deleted by someone else: a tombstone too.
        book.apply(frame("chat", msg(DM, T + 10, B, "x", 0, null)));
        JsonObject gone = new JsonObject();
        gone.addProperty("t", "chat_deleted");
        gone.addProperty("conv", DM);
        gone.addProperty("id", T + 10);
        gone.addProperty("by", B);
        book.apply(gone);
        assertEquals(2, book.entries(DM).size());
        assertTrue(book.entries(DM).get(1).tombstone.bySender());
    }

    @Test
    public void aDeletedMessageLeavesATombstoneWithoutItsText() {
        MemoryStore store = new MemoryStore();
        ChatBook book = new ChatBook(ME, store, ChatBook.Snapshot.empty());
        book.apply(frame("chat", msg(CREW_CHAT, T, B, "gizli metin", 0, null)));
        book.apply(frame("chat", msg(CREW_CHAT, T + 1, B, "ikinci", 0, null)));
        assertEquals(2, book.unread(CREW_CHAT));
        JsonObject gone = new JsonObject();
        gone.addProperty("t", "chat_deleted");
        gone.addProperty("conv", CREW_CHAT);
        gone.addProperty("id", T);
        gone.addProperty("by", "staff");
        gone.addProperty("at", T + 50);
        assertEquals(ChatBook.Event.Kind.DELETED, book.apply(gone).get(0).kind);
        // Its text and seal are gone from memory and from the store; who sent it and when stay.
        assertNull(book.message(CREW_CHAT, T));
        ChatBook.Tombstone t = book.tombstone(CREW_CHAT, T);
        assertNotNull(t);
        assertEquals(B, t.from);
        assertTrue(t.byStaff());
        assertFalse(store.messages.containsKey(CREW_CHAT + "#" + T));
        assertEquals("staff", store.tombstones.get(CREW_CHAT + "#" + T).by);
        // A tombstone is never unread, offers nothing, and is no report context.
        assertEquals(1, book.unread(CREW_CHAT));
        assertEquals(T + 1, book.firstUnread(CREW_CHAT));
        ChatBook.Entry entry = book.entries(CREW_CHAT).get(0);
        assertTrue(entry.deleted());
        assertFalse(book.canEdit(entry, T + 1));
        assertFalse(book.canDelete(entry, T + 1, true));
        assertFalse(book.canReport(entry));
        assertTrue(book.before(book.message(CREW_CHAT, T + 1), Reports.CONTEXT).isEmpty());
        assertNull(book.edit(CREW_CHAT, T, "x"));
        assertNull(book.delete(CREW_CHAT, T));
        // A late copy of the message (a sync, a live frame) brings nothing back.
        book.apply(frame("chat", msg(CREW_CHAT, T, B, "gizli metin", 0, null)));
        book.apply(frame("chat_edited", msg(CREW_CHAT, T, B, "gizli metin 2", T + 9, null)));
        assertNull(book.message(CREW_CHAT, T));
        assertTrue(book.entries(CREW_CHAT).get(0).deleted());
        assertFalse(store.messages.containsKey(CREW_CHAT + "#" + T));
        // A second word about it changes nothing.
        gone.addProperty("by", B);
        book.apply(gone);
        assertTrue(book.tombstone(CREW_CHAT, T).byStaff());
        // The list's line: the newest line is the tombstone when it is the newest.
        JsonObject last = new JsonObject();
        last.addProperty("t", "chat_deleted");
        last.addProperty("conv", CREW_CHAT);
        last.addProperty("id", T + 1);
        last.addProperty("by", "");
        book.apply(last);
        ChatBook.Summary summary = book.summaries().get(0);
        assertTrue(summary.last.deleted());
        assertEquals(T + 1, summary.activity);
        assertEquals(0, summary.unread);
        assertEquals("", summary.last.tombstone.by);
    }

    @Test
    public void noTombstoneForAMessageThisDeviceNeverHeld() {
        MemoryStore store = new MemoryStore();
        ChatBook book = new ChatBook(ME, store, ChatBook.Snapshot.empty());
        JsonObject gone = new JsonObject();
        gone.addProperty("t", "chat_deleted");
        gone.addProperty("conv", DM);
        gone.addProperty("id", T);
        gone.addProperty("by", B);
        book.apply(gone);
        assertTrue(book.entries(DM).isEmpty());
        assertTrue(store.tombstones.isEmpty());
        assertTrue(book.summaries().isEmpty());
        // The service no longer having a message the player tried to delete: a tombstone by someone not known.
        book.apply(frame("chat", msg(DM, T + 5, ME, "eski", 0, "nonce1")));
        book.delete(DM, T + 5);
        book.deleteFailed(DM, T + 5, "message_not_found");
        ChatBook.Entry entry = book.entries(DM).get(0);
        assertTrue(entry.deleted());
        assertEquals("", entry.tombstone.by);
        assertEquals("n:nonce1", entry.key());
    }

    @Test
    public void tombstonesComeBackFromTheStoreInTheirPlaces() {
        Map<String, List<ChatMessage>> held = new HashMap<>();
        List<ChatMessage> dm = new ArrayList<>();
        dm.add(ChatMessage.read(msg(DM, T, B, "a", 0, null), null));
        dm.add(ChatMessage.read(msg(DM, T + 2, B, "c", 0, null), null));
        held.put(DM, dm);
        Map<String, List<ChatBook.Tombstone>> buried = new HashMap<>();
        List<ChatBook.Tombstone> list = new ArrayList<>();
        list.add(new ChatBook.Tombstone(DM, T + 1, ME, "n1", ME, T + 9));
        buried.put(DM, list);
        ChatBook book = new ChatBook(ME, ChatBook.NO_STORE, new ChatBook.Snapshot(held, new HashMap<String, Boolean>(),
                new ArrayList<ChatBook.Outbox>(), new HashMap<String, ChatBook.Meta>(), T + 2, buried));
        List<ChatBook.Entry> entries = book.entries(DM);
        assertEquals(3, entries.size());
        assertTrue(entries.get(1).deleted());
        assertTrue(entries.get(1).mine(ME));
        assertEquals(T + 1, entries.get(1).time());
        assertEquals(T, book.oldestId(DM));
        // An older page with a tombstone in it.
        List<ChatBook.Tombstone> older = new ArrayList<>();
        older.add(new ChatBook.Tombstone(DM, T - 5, B, null, B, 0));
        book.addOlder(DM, new ArrayList<ChatMessage>(), older, false);
        assertEquals(T - 5, book.oldestId(DM));
        assertEquals(4, book.entries(DM).size());
        assertEquals("m:" + (T - 5), book.entries(DM).get(0).key());
    }

    @Test
    public void whoMayDeleteWhat() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", msg(CREW_CHAT, T, B, "x", 0, null)));
        book.apply(frame("chat", msg(DM, T, B, "y", 0, null)));
        book.apply(frame("chat", msg(DM, T + 1, ME, "z", 0, null)));
        ChatBook.Entry crewTheirs = book.entries(CREW_CHAT).get(0);
        ChatBook.Entry dmTheirs = book.entries(DM).get(0);
        ChatBook.Entry dmMine = book.entries(DM).get(1);
        assertFalse(book.canDelete(crewTheirs, T + 1, false));
        assertTrue(book.canDelete(crewTheirs, T + 1, true));
        assertFalse(book.canDelete(dmTheirs, T + 1, true));
        assertTrue(book.canDelete(dmMine, T + ChatBook.DELETE_WINDOW_MS, false));
        assertFalse(book.canDelete(dmMine, T + 1 + ChatBook.DELETE_WINDOW_MS, false));
        assertFalse(book.canEdit(dmTheirs, T + 1));
        assertTrue(book.canReport(dmTheirs));
        assertFalse(book.canReport(dmMine));
    }

    @Test
    public void aReportCarriesTheEarlierMessagesOldestFirst() {
        ChatBook book = new ChatBook(ME);
        for (int i = 0; i < 25; i++) book.apply(frame("chat", msg(DM, T + i, i % 2 == 0 ? B : ME, "m" + i, 0, null)));
        ChatMessage reported = book.message(DM, T + 24);
        List<ChatMessage> before = book.before(reported, Reports.CONTEXT);
        assertEquals(20, before.size());
        assertEquals(T + 4, before.get(0).id);
        assertEquals(T + 23, before.get(19).id);
        JsonObject body = Reports.message(reported, before, Reports.HARASSMENT, "  kötü\n\n  söz  ", true);
        JsonObject target = body.getAsJsonObject("target");
        assertEquals("message", Json.str(target, "kind"));
        assertEquals(20, target.getAsJsonArray("before").size());
        assertEquals("kötü söz", Json.str(body, "note"));
        assertTrue(Json.bool(body, "block", false));
        assertFalse(Json.has(target.getAsJsonObject("message"), "nonce"));
        JsonObject player = Reports.player(B, Reports.NAME, "", false);
        assertFalse(player.has("note"));
        assertFalse(player.has("block"));
        assertEquals("player", Json.str(player.getAsJsonObject("target"), "kind"));
        JsonObject crew = Reports.crew(CREW, null, Reports.NAME, null);
        assertFalse(crew.getAsJsonObject("target").has("code"));
        assertEquals(6, Reports.reasons(Reports.Target.PLAYER).size());
        assertTrue(Reports.reasons(Reports.Target.MESSAGE).contains(Reports.INAPPROPRIATE));
        assertFalse(Reports.reasons(Reports.Target.CREW).contains(Reports.PROFILE));
        assertFalse(Reports.offersBlock(Reports.Target.CREW));
        assertEquals(Reports.NOTE_MAX, Reports.cleanNote(repeat("a", 250)).length());
    }

    @Test
    public void clearingTheHistoryKeepsItAwayFromLaterSyncs() {
        MemoryStore store = new MemoryStore();
        ChatBook book = new ChatBook(ME, store, ChatBook.Snapshot.empty());
        book.apply(frame("chat", msg(DM, T, B, "eski", 0, null)));
        book.apply(frame("chat", msg(DM, T - 1, B, "silinen", 0, null)));
        JsonObject gone = new JsonObject();
        gone.addProperty("t", "chat_deleted");
        gone.addProperty("conv", DM);
        gone.addProperty("id", T - 1);
        gone.addProperty("by", B);
        book.apply(gone);
        book.clearHistory(DM);
        assertTrue(book.entries(DM).isEmpty());
        assertTrue(store.messages.isEmpty());
        assertTrue(store.tombstones.isEmpty());
        book.apply(frame("chat", msg(DM, T, B, "eski", 0, null)));
        assertTrue(book.entries(DM).isEmpty());
        book.apply(frame("chat", msg(DM, T + 1, B, "yeni", 0, null)));
        assertEquals(1, book.entries(DM).size());
    }

    @Test
    public void conversationsListTheNewestFirstAndCountUnread() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", msg(DM, T, B, "a", 0, null)));
        book.apply(frame("chat", msg(CREW_CHAT, T + 10, C, "b", 0, null)));
        book.apply(frame("chat", msg(CREW_CHAT, T + 11, C, "c", 0, null)));
        List<ChatBook.Summary> list = book.summaries();
        assertEquals(CREW_CHAT, list.get(0).conv);
        assertEquals(2, list.get(0).unread);
        assertEquals("c", list.get(0).last.text());
        assertEquals(3, book.unreadTotal());
        book.setMuted(DM, ChatBook.MUTED_FOREVER);
        assertTrue(book.mutedAt(DM, 0));
        book.setMuted(DM, 100);
        assertTrue(book.mutedAt(DM, 99));
        assertFalse(book.mutedAt(DM, 100));
    }

    @Test
    public void whatTheStoreHeldComesBackAndMessagesOnTheirWayWaitAgain() {
        Map<String, List<ChatMessage>> held = new HashMap<>();
        List<ChatMessage> dm = new ArrayList<>();
        dm.add(ChatMessage.read(msg(DM, T, B, "a", 0, null), null));
        held.put(DM, dm);
        List<ChatBook.Outbox> outbox = new ArrayList<>();
        outbox.add(new ChatBook.Outbox("w", DM, "b", 5, 5, 1, ChatBook.Outbox.State.SENDING, null, 0));
        Map<String, ChatBook.Meta> meta = new HashMap<>();
        meta.put(DM, new ChatBook.Meta(T, 0, 0));
        ChatBook book = new ChatBook(ME, ChatBook.NO_STORE, new ChatBook.Snapshot(held, new HashMap<String, Boolean>(), outbox, meta, T));
        assertEquals(2, book.entries(DM).size());
        assertEquals(0, book.unread(DM));
        assertEquals(1, book.due(10).size());
        assertEquals(T - ChatBook.SYNC_OVERLAP_MS, book.syncSince());
    }

    private static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(s);
        return out.toString();
    }
}
