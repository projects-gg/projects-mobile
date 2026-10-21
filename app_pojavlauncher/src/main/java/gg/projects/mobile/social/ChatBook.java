package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The player's chat on this phone (CHAT1; README "Chat"): every conversation's messages as the
 * service sealed them, the messages the player is sending (each under its own {@code nonce}: shown at
 * once as waiting, then sent or failed), edits and deletes on their way, what the player has read,
 * and the conversations they muted on this phone. The history belongs to the device: a
 * {@link Store} keeps it (the newest {@link #KEEP_PER_CONVERSATION} of each conversation); the
 * service's mailbox only fills what a device missed, through a sync after each connect.
 *
 * <p>Pure Java and JVM-tested; one thread calls it (the app's main thread), the store writes
 * elsewhere. Times: message ids and edits are the hub's milliseconds; outbox times the phone's.
 */
public final class ChatBook {
    /** The device keeps this many messages of each conversation (the desktop's number). */
    public static final int KEEP_PER_CONVERSATION = 5000;
    /** In memory per conversation; older ones are read from the store when the player scrolls up. */
    public static final int WINDOW = 600;
    public static final long EDIT_WINDOW_MS = 15 * 60_000L;
    public static final long DELETE_WINDOW_MS = 7 * 86_400_000L;
    /** A message waiting longer than this (no connection) is not sent by itself any more: it fails. */
    public static final long RESEND_WINDOW_MS = 10 * 60_000L;
    /** A retry keeps its nonce this long after the first try (the service remembers one for 15 minutes). */
    public static final long NONCE_WINDOW_MS = 14 * 60_000L;
    /** A sync asks from the newest message held minus a minute (the README's overlap). */
    public static final long SYNC_OVERLAP_MS = 60_000L;
    /** Tries before a message whose answer keeps getting lost fails. */
    public static final int MAX_ATTEMPTS = 3;
    /** A conversation muted until unmuted. */
    public static final long MUTED_FOREVER = Long.MAX_VALUE;

    /** Why a message did not go (what the screen says under it). */
    public enum Failure {
        LINK, PHONE, WORDS, MUTED, OFF, RATE, DAILY, NOT_FRIENDS, NOT_IN_CREW, INVALID, UNAVAILABLE, FAILED
    }

    /** Where the device keeps the chat (the app's SQLite file; a map in the tests). */
    public interface Store {
        void putMessage(ChatMessage message);

        void removeMessage(String conv, long id);

        void putOutbox(Outbox entry);

        void removeOutbox(String nonce);

        void putMeta(String conv, Meta meta);

        /** "Geçmişi temizle": every message of the conversation and its outbox go. */
        void clear(String conv);

        void putSyncCursor(long newestSeen);
    }

    /** A store that keeps nothing (the tests' default; the app until its file is open). */
    public static final Store NO_STORE = new Store() {
        @Override public void putMessage(ChatMessage message) {}
        @Override public void removeMessage(String conv, long id) {}
        @Override public void putOutbox(Outbox entry) {}
        @Override public void removeOutbox(String nonce) {}
        @Override public void putMeta(String conv, Meta meta) {}
        @Override public void clear(String conv) {}
        @Override public void putSyncCursor(long newestSeen) {}
    };

    /** A conversation's own settings on this phone. */
    public static final class Meta {
        /** The newest message id the player saw. */
        public final long lastRead;
        /** "Geçmişi temizle": messages up to this id stay away (a later sync would bring them back). */
        public final long clearedUpTo;
        /** Muted on this phone until then (Unix seconds); 0: not; {@link #MUTED_FOREVER}: until unmuted. */
        public final long mutedUntil;

        public Meta(long lastRead, long clearedUpTo, long mutedUntil) {
            this.lastRead = lastRead;
            this.clearedUpTo = clearedUpTo;
            this.mutedUntil = mutedUntil;
        }

        static final Meta NONE = new Meta(0, 0, 0);
    }

    /** A message the player is sending. */
    public static final class Outbox {
        public enum State { WAITING, SENDING, FAILED }

        public final String nonce;
        public final String conv;
        public final String text;
        /** When the player wrote it (the phone's ms): its place among other waiting messages. */
        public final long createdAt;
        /** The first try (ms), 0 before it: the nonce is kept for a retry within {@link #NONCE_WINDOW_MS}. */
        public final long firstSentAt;
        public final int attempts;
        public final State state;
        @Nullable
        public final Failure failure;
        /** Not sent again before this (ms; a short rate limit), 0: at once. */
        public final long notBefore;

        public Outbox(String nonce, String conv, String text, long createdAt, long firstSentAt, int attempts, State state,
                      @Nullable Failure failure, long notBefore) {
            this.nonce = nonce;
            this.conv = conv;
            this.text = text;
            this.createdAt = createdAt;
            this.firstSentAt = firstSentAt;
            this.attempts = attempts;
            this.state = state;
            this.failure = failure;
            this.notBefore = notBefore;
        }

        Outbox with(State next, @Nullable Failure why, long wait) {
            return new Outbox(nonce, conv, text, createdAt, firstSentAt, attempts, next, why, wait);
        }

        Outbox sending(long nowMs) {
            return new Outbox(nonce, conv, text, createdAt, firstSentAt == 0 ? nowMs : firstSentAt, attempts + 1,
                    State.SENDING, null, 0);
        }

        /** The same message under a new nonce (a retry after the service forgot the old one). */
        Outbox renewed(String newNonce) {
            return new Outbox(newNonce, conv, text, createdAt, 0, 0, State.WAITING, null, 0);
        }

        /** The frame that sends it ({@code rid} is the connection's). */
        public JsonObject frame() {
            JsonObject o = new JsonObject();
            o.addProperty("t", "chat");
            o.addProperty("conv", conv);
            o.addProperty("text", text);
            o.addProperty("nonce", nonce);
            return o;
        }
    }

    /** One line of a conversation as the screens show it. */
    public static final class Entry {
        @Nullable
        public final ChatMessage message;
        @Nullable
        public final Outbox outbox;
        /** An edit on its way: the new text is shown already. */
        @Nullable
        public final String editing;
        /** "Herkesten sil" on its way. */
        public final boolean deleting;

        Entry(@Nullable ChatMessage message, @Nullable Outbox outbox, @Nullable String editing, boolean deleting) {
            this.message = message;
            this.outbox = outbox;
            this.editing = editing;
            this.deleting = deleting;
        }

        /** Stable while a sent message becomes the one the service took (its nonce), else its id. */
        public String key() {
            if (outbox != null) return "n:" + outbox.nonce;
            return message.nonce != null ? "n:" + message.nonce : "m:" + message.id;
        }

        public String text() {
            if (outbox != null) return outbox.text;
            return editing != null ? editing : message.text;
        }

        public String from(String me) {
            return outbox != null ? me : message.from;
        }

        public boolean mine(String me) {
            return outbox != null || message.from.equals(me);
        }

        public boolean pending() {
            return outbox != null ? outbox.state != Outbox.State.FAILED : editing != null || deleting;
        }

        public boolean failed() {
            return outbox != null && outbox.state == Outbox.State.FAILED;
        }
    }

    /** What changed for the app to react to (a notification, the open screen). */
    public static final class Event {
        public enum Kind { NEW, EDITED, DELETED, SYNCED }

        public final Kind kind;
        public final String conv;
        @Nullable
        public final ChatMessage message;

        Event(Kind kind, String conv, @Nullable ChatMessage message) {
            this.kind = kind;
            this.conv = conv;
            this.message = message;
        }
    }

    /** A conversation for the lists: its newest line and how many of the others' messages are unread. */
    public static final class Summary {
        public final String conv;
        @Nullable
        public final Entry last;
        public final int unread;
        /** The newest line's time (ms; the hub's for sent messages). */
        public final long activity;

        Summary(String conv, @Nullable Entry last, int unread, long activity) {
            this.conv = conv;
            this.last = last;
            this.unread = unread;
            this.activity = activity;
        }
    }

    /** What the store held when the app started. */
    public static final class Snapshot {
        public final Map<String, List<ChatMessage>> messages;
        public final Map<String, Boolean> hasOlder;
        public final List<Outbox> outbox;
        public final Map<String, Meta> meta;
        public final long syncCursor;

        public Snapshot(Map<String, List<ChatMessage>> messages, Map<String, Boolean> hasOlder, List<Outbox> outbox,
                        Map<String, Meta> meta, long syncCursor) {
            this.messages = messages;
            this.hasOlder = hasOlder;
            this.outbox = outbox;
            this.meta = meta;
            this.syncCursor = syncCursor;
        }

        public static Snapshot empty() {
            return new Snapshot(new HashMap<String, List<ChatMessage>>(), new HashMap<String, Boolean>(),
                    new ArrayList<Outbox>(), new HashMap<String, Meta>(), 0);
        }
    }

    private static final class Conversation {
        final String conv;
        final TreeMap<Long, ChatMessage> messages = new TreeMap<>();
        final LinkedHashMap<String, Outbox> outbox = new LinkedHashMap<>();
        final Map<Long, String> editing = new HashMap<>();
        final Set<Long> deleting = new HashSet<>();
        Meta meta = Meta.NONE;
        boolean hasOlder;

        Conversation(String conv) {
            this.conv = conv;
        }
    }

    private final String mMe;
    private final Store mStore;
    private final Map<String, Conversation> mConversations = new LinkedHashMap<>();
    /** The newest message id this device ever held (the next sync starts a minute before it). */
    private long mSyncCursor;
    /** The hub's clock minus the phone's (ms), from {@code chat_synced}. */
    private long mHubOffsetMs;

    public ChatBook(String me, Store store, Snapshot snapshot) {
        mMe = me;
        mStore = store;
        for (Map.Entry<String, List<ChatMessage>> e : snapshot.messages.entrySet()) {
            Conversation c = conversation(e.getKey());
            for (ChatMessage m : e.getValue()) c.messages.put(m.id, m);
            Boolean older = snapshot.hasOlder.get(e.getKey());
            c.hasOlder = older != null && older;
        }
        for (Outbox o : snapshot.outbox) {
            // A message that was on its way when the app stopped waits again (the same nonce: no second copy).
            Outbox waiting = o.state == Outbox.State.SENDING ? o.with(Outbox.State.WAITING, null, 0) : o;
            conversation(o.conv).outbox.put(o.nonce, waiting);
        }
        for (Map.Entry<String, Meta> e : snapshot.meta.entrySet()) conversation(e.getKey()).meta = e.getValue();
        mSyncCursor = snapshot.syncCursor;
    }

    public ChatBook(String me) {
        this(me, NO_STORE, Snapshot.empty());
    }

    public String me() {
        return mMe;
    }

    private Conversation conversation(String conv) {
        Conversation c = mConversations.get(conv);
        if (c == null) {
            c = new Conversation(conv);
            mConversations.put(conv, c);
        }
        return c;
    }

    // ---- reading ----

    /** The conversation's lines, oldest first: the service's messages by id, then the player's waiting ones. */
    public List<Entry> entries(String conv) {
        Conversation c = mConversations.get(conv);
        List<Entry> out = new ArrayList<>();
        if (c == null) return out;
        for (ChatMessage m : c.messages.values()) out.add(new Entry(m, null, c.editing.get(m.id), c.deleting.contains(m.id)));
        List<Outbox> waiting = new ArrayList<>(c.outbox.values());
        Collections.sort(waiting, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        for (Outbox o : waiting) out.add(new Entry(null, o, null, false));
        return out;
    }

    @Nullable
    public ChatMessage message(String conv, long id) {
        Conversation c = mConversations.get(conv);
        return c == null ? null : c.messages.get(id);
    }

    @Nullable
    public Outbox outbox(String nonce) {
        for (Conversation c : mConversations.values()) {
            Outbox o = c.outbox.get(nonce);
            if (o != null) return o;
        }
        return null;
    }

    public boolean hasOlder(String conv) {
        Conversation c = mConversations.get(conv);
        return c != null && c.hasOlder;
    }

    /** The oldest message id held in memory (the store is read before it for older ones), or 0. */
    public long oldestId(String conv) {
        Conversation c = mConversations.get(conv);
        return c == null || c.messages.isEmpty() ? 0 : c.messages.firstKey();
    }

    public long lastRead(String conv) {
        Conversation c = mConversations.get(conv);
        return c == null ? 0 : c.meta.lastRead;
    }

    /** The others' messages after what the player read. */
    public int unread(String conv) {
        Conversation c = mConversations.get(conv);
        if (c == null) return 0;
        int n = 0;
        for (ChatMessage m : c.messages.tailMap(c.meta.lastRead, false).values()) if (!m.from.equals(mMe)) n++;
        return n;
    }

    /** The oldest of the others' messages after what the player read (the "Yeni mesajlar" line), or 0. */
    public long firstUnread(String conv) {
        Conversation c = mConversations.get(conv);
        if (c == null) return 0;
        for (ChatMessage m : c.messages.tailMap(c.meta.lastRead, false).values()) if (!m.from.equals(mMe)) return m.id;
        return 0;
    }

    /** Every conversation with something in it, the newest first. */
    public List<Summary> summaries() {
        List<Summary> out = new ArrayList<>();
        for (Conversation c : mConversations.values()) {
            Entry last = null;
            long activity = 0;
            if (!c.outbox.isEmpty()) {
                Outbox newest = null;
                for (Outbox o : c.outbox.values()) if (newest == null || o.createdAt >= newest.createdAt) newest = o;
                last = new Entry(null, newest, null, false);
                activity = newest.createdAt + mHubOffsetMs;
            }
            if (!c.messages.isEmpty()) {
                ChatMessage m = c.messages.lastEntry().getValue();
                if (last == null || m.id > activity) {
                    last = new Entry(m, null, c.editing.get(m.id), c.deleting.contains(m.id));
                    activity = m.id;
                }
            }
            if (last == null) continue;
            out.add(new Summary(c.conv, last, unread(c.conv), activity));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.activity, a.activity));
        return out;
    }

    /** Unread messages of every conversation together (crews and direct ones). */
    public int unreadTotal() {
        int n = 0;
        for (Conversation c : mConversations.values()) n += unread(c.conv);
        return n;
    }

    /** {@code since} for the next {@code chat_sync}: the newest message held minus a minute, or 0 on a new device. */
    public long syncSince() {
        return mSyncCursor <= 0 ? 0 : Math.max(0, mSyncCursor - SYNC_OVERLAP_MS);
    }

    public long hubNowMs(long phoneNowMs) {
        return phoneNowMs + mHubOffsetMs;
    }

    public void setHubOffsetMs(long offset) {
        mHubOffsetMs = offset;
    }

    // ---- rules ----

    /** "Düzenle": the player's own message, within 15 minutes of its id, nothing else on its way. */
    public boolean canEdit(Entry entry, long hubNowMs) {
        if (entry.message == null || entry.deleting || entry.editing != null) return false;
        return entry.message.from.equals(mMe) && hubNowMs - entry.message.id < EDIT_WINDOW_MS;
    }

    /**
     * "Herkesten sil": the player's own message within 7 days; in a crew's chat its leader and
     * moderators ({@code moderator}) any of its messages within 7 days.
     */
    public boolean canDelete(Entry entry, long hubNowMs, boolean moderator) {
        if (entry.message == null || entry.deleting) return false;
        if (hubNowMs - entry.message.id >= DELETE_WINDOW_MS) return false;
        if (entry.message.from.equals(mMe)) return true;
        return moderator && ChatConv.isCrew(entry.message.conv);
    }

    /** "Rapor et": someone else's message the service sealed. */
    public boolean canReport(Entry entry) {
        return entry.message != null && !entry.message.from.equals(mMe);
    }

    /** Up to 20 earlier messages of the conversation as held, oldest first (a report's context). */
    public List<ChatMessage> before(ChatMessage message, int max) {
        Conversation c = mConversations.get(message.conv);
        List<ChatMessage> out = new ArrayList<>();
        if (c == null) return out;
        for (ChatMessage m : c.messages.headMap(message.id, false).descendingMap().values()) {
            if (out.size() >= max) break;
            out.add(0, m);
        }
        return out;
    }

    // ---- the service's frames ----

    /**
     * {@code chat}, {@code chat_edited}, {@code chat_deleted} and {@code chat_sync}; anything else
     * changes nothing. A new message from someone else, live, is an {@link Event.Kind#NEW} (a sync's
     * are not: a phone that was away got their pushes).
     */
    public List<Event> apply(JsonObject frame) {
        List<Event> events = new ArrayList<>();
        String type = Json.str(frame, "t", "");
        switch (type) {
            case "chat": {
                ChatMessage m = ChatMessage.read(Json.obj(frame, "msg"), null);
                if (m == null) break;
                boolean known = put(m);
                if (!known && !m.from.equals(mMe)) events.add(new Event(Event.Kind.NEW, m.conv, m));
                else events.add(new Event(Event.Kind.EDITED, m.conv, m));
                break;
            }
            case "chat_edited": {
                ChatMessage m = ChatMessage.read(Json.obj(frame, "msg"), null);
                if (m == null) break;
                Conversation c = conversation(m.conv);
                c.editing.remove(m.id);
                put(m);
                events.add(new Event(Event.Kind.EDITED, m.conv, m));
                break;
            }
            case "chat_deleted": {
                String conv = Json.str(frame, "conv");
                long id = Json.num(frame, "id", 0);
                if (!ChatConv.valid(conv) || id <= 0) break;
                remove(conv, id);
                events.add(new Event(Event.Kind.DELETED, conv, null));
                break;
            }
            case "chat_sync": {
                String conv = Json.str(frame, "conv");
                if (!ChatConv.valid(conv)) break;
                for (JsonObject wire : Json.objects(frame, "messages")) {
                    ChatMessage m = ChatMessage.read(wire, conv);
                    if (m != null && m.conv.equals(conv)) put(m);
                }
                for (JsonObject deleted : Json.objects(frame, "deleted")) {
                    long id = Json.num(deleted, "id", 0);
                    if (id > 0) remove(conv, id);
                }
                events.add(new Event(Event.Kind.SYNCED, conv, null));
                break;
            }
            default:
                break;
        }
        return events;
    }

    /** {@code chat_synced}: the hub's clock, and the cursor to continue with when there is {@code more} (else null). */
    @Nullable
    public String synced(JsonObject frame, long phoneNowMs) {
        long now = Json.num(frame, "now", 0);
        if (now > 0) mHubOffsetMs = now - phoneNowMs;
        String more = Json.str(frame, "more");
        return more == null || more.isEmpty() || more.length() > 512 ? null : more;
    }

    /** Keeps a message (a newer version replaces the one held). True when it was held already. */
    private boolean put(ChatMessage m) {
        Conversation c = conversation(m.conv);
        if (m.id <= c.meta.clearedUpTo) return true;
        ChatMessage before = c.messages.get(m.id);
        ChatMessage keep = m.keepingNonce(before);
        // The player's own message from this device: it leaves the outbox (a sync after a lost answer, too).
        if (keep.nonce != null && keep.from.equals(mMe)) {
            Outbox o = c.outbox.remove(keep.nonce);
            if (o != null) mStore.removeOutbox(o.nonce);
        }
        if (before != null && sameVersion(before, keep)) return true;
        c.messages.put(keep.id, keep);
        mStore.putMessage(keep);
        if (keep.id > mSyncCursor) {
            mSyncCursor = keep.id;
            mStore.putSyncCursor(mSyncCursor);
        }
        if (c.messages.size() > WINDOW * 2) {
            // Memory keeps the newest; the store keeps the rest for scrolling up.
            while (c.messages.size() > WINDOW) c.messages.pollFirstEntry();
            c.hasOlder = true;
        }
        return before != null;
    }

    private static boolean sameVersion(ChatMessage a, ChatMessage b) {
        return a.text.equals(b.text) && a.mac.equals(b.mac) && a.editedAt == b.editedAt
                && (a.nonce == null ? b.nonce == null : a.nonce.equals(b.nonce));
    }

    /** A message deleted for everyone goes (also one only the store still holds). */
    private void remove(String conv, long id) {
        Conversation c = conversation(conv);
        c.editing.remove(id);
        c.deleting.remove(id);
        c.messages.remove(id);
        mStore.removeMessage(conv, id);
    }

    /** Older messages read from the store (scrolling up): kept in memory too. */
    public void addOlder(String conv, List<ChatMessage> older, boolean more) {
        Conversation c = conversation(conv);
        for (ChatMessage m : older) if (m.conv.equals(conv) && m.id > c.meta.clearedUpTo) c.messages.put(m.id, m);
        c.hasOlder = more;
    }

    // ---- sending ----

    /** A new message: it shows at once as waiting. */
    public Outbox write(String conv, String text, String nonce, long phoneNowMs) {
        Outbox o = new Outbox(nonce, conv, text, phoneNowMs, 0, 0, Outbox.State.WAITING, null, 0);
        conversation(conv).outbox.put(nonce, o);
        mStore.putOutbox(o);
        return o;
    }

    /** The messages to send now, the oldest first (waiting and not held back by a short rate limit). */
    public List<Outbox> due(long phoneNowMs) {
        List<Outbox> out = new ArrayList<>();
        for (Conversation c : mConversations.values()) {
            for (Outbox o : c.outbox.values()) if (o.state == Outbox.State.WAITING && o.notBefore <= phoneNowMs) out.add(o);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        return out;
    }

    /** When a held-back message may go (ms), or 0 when none waits for that. */
    public long nextDue(long phoneNowMs) {
        long next = 0;
        for (Conversation c : mConversations.values()) {
            for (Outbox o : c.outbox.values()) {
                if (o.state == Outbox.State.WAITING && o.notBefore > phoneNowMs && (next == 0 || o.notBefore < next)) {
                    next = o.notBefore;
                }
            }
        }
        return next;
    }

    /** It goes out now (the frame is sent). */
    public void sending(String nonce, long phoneNowMs) {
        Conversation c = byNonce(nonce);
        if (c == null) return;
        Outbox o = c.outbox.get(nonce).sending(phoneNowMs);
        c.outbox.put(nonce, o);
        mStore.putOutbox(o);
    }

    /** The service took it: {@code msg} of the {@code ack} (its id, seal and cleaned text). */
    public void sent(String nonce, @Nullable JsonObject msg) {
        Conversation c = byNonce(nonce);
        if (c == null) return;
        ChatMessage m = ChatMessage.read(msg, c.conv);
        if (m == null) {
            // An ack without the message: a sync brings it (its nonce matches then).
            Outbox o = c.outbox.get(nonce);
            c.outbox.put(nonce, o.with(Outbox.State.WAITING, null, Long.MAX_VALUE));
            return;
        }
        if (m.nonce == null) m = new ChatMessage(m.conv, m.id, m.from, m.text, m.mac, m.editedAt, nonce);
        put(m);
        if (c.outbox.remove(nonce) != null) mStore.removeOutbox(nonce);
    }

    /**
     * The service refused it, or its answer got lost ({@code unavailable}: the connection went or no
     * answer came). A lost answer waits for the next try with the same nonce (no second copy); a short
     * rate limit waits it out; anything else fails with its reason.
     */
    public void notSent(String nonce, String code, @Nullable String reason, long retryAfterSeconds, long phoneNowMs) {
        Conversation c = byNonce(nonce);
        if (c == null) return;
        Outbox o = c.outbox.get(nonce);
        Outbox next;
        if ("unavailable".equals(code) || "internal".equals(code)) {
            next = o.attempts >= MAX_ATTEMPTS ? o.with(Outbox.State.FAILED, Failure.UNAVAILABLE, 0)
                    : o.with(Outbox.State.WAITING, null, "internal".equals(code) ? phoneNowMs + 2_000 : 0);
        } else if ("rate_limited".equals(code) && retryAfterSeconds > 0 && retryAfterSeconds <= 30) {
            next = o.with(Outbox.State.WAITING, null, phoneNowMs + retryAfterSeconds * 1000L);
        } else {
            next = o.with(Outbox.State.FAILED, failure(code, reason, retryAfterSeconds), 0);
        }
        c.outbox.put(nonce, next);
        mStore.putOutbox(next);
    }

    /** What the screen says for a refusal of a send or an edit. */
    public static Failure failure(String code, @Nullable String reason, long retryAfterSeconds) {
        switch (code) {
            case "chat_rejected":
                if ("link".equals(reason)) return Failure.LINK;
                if ("phone".equals(reason)) return Failure.PHONE;
                return Failure.WORDS;
            case "chat_muted": return Failure.MUTED;
            case "chat_off": return Failure.OFF;
            case "rate_limited": return retryAfterSeconds > 30 ? Failure.DAILY : Failure.RATE;
            case "friend_not_found": return Failure.NOT_FRIENDS;
            case "team_not_found": return Failure.NOT_IN_CREW;
            case "bad_request": return Failure.INVALID;
            case "unavailable":
            case "internal": return Failure.UNAVAILABLE;
            default: return Failure.FAILED;
        }
    }

    /** The connection went: messages on their way wait for the next one (sent again with their nonce). */
    public void connectionLost() {
        for (Conversation c : mConversations.values()) {
            for (Map.Entry<String, Outbox> e : c.outbox.entrySet()) {
                if (e.getValue().state == Outbox.State.SENDING) e.setValue(e.getValue().with(Outbox.State.WAITING, null, 0));
            }
            // Edits and deletes without an answer are not known to have happened: the next sync tells.
            c.editing.clear();
            c.deleting.clear();
        }
    }

    /** Messages that waited too long without a connection fail (the player sends them again if they want). */
    public void expire(long phoneNowMs) {
        for (Conversation c : mConversations.values()) {
            for (Map.Entry<String, Outbox> e : c.outbox.entrySet()) {
                Outbox o = e.getValue();
                if (o.state != Outbox.State.FAILED && o.notBefore != Long.MAX_VALUE && phoneNowMs - o.createdAt > RESEND_WINDOW_MS) {
                    Outbox failed = o.with(Outbox.State.FAILED, Failure.UNAVAILABLE, 0);
                    e.setValue(failed);
                    mStore.putOutbox(failed);
                }
            }
        }
    }

    /** "Tekrar dene": a failed message waits again (a new nonce once the service forgot the old one). */
    @Nullable
    public Outbox retry(String nonce, String freshNonce, long phoneNowMs) {
        Conversation c = byNonce(nonce);
        if (c == null) return null;
        Outbox o = c.outbox.get(nonce);
        if (o.state != Outbox.State.FAILED) return o;
        Outbox next;
        if (o.firstSentAt == 0 || phoneNowMs - o.firstSentAt < NONCE_WINDOW_MS) {
            next = new Outbox(o.nonce, o.conv, o.text, o.createdAt, o.firstSentAt, 0, Outbox.State.WAITING, null, 0);
            c.outbox.put(nonce, next);
        } else {
            next = o.renewed(freshNonce);
            c.outbox.remove(nonce);
            mStore.removeOutbox(nonce);
            c.outbox.put(next.nonce, next);
        }
        mStore.putOutbox(next);
        return next;
    }

    /** "Sil" on a message that did not go: it leaves this phone. */
    public void discard(String nonce) {
        Conversation c = byNonce(nonce);
        if (c != null && c.outbox.remove(nonce) != null) mStore.removeOutbox(nonce);
    }

    @Nullable
    private Conversation byNonce(String nonce) {
        for (Conversation c : mConversations.values()) if (c.outbox.containsKey(nonce)) return c;
        return null;
    }

    // ---- edits and deletes ----

    /** "Düzenle": the frame (with the message as held), the new text shown at once; null when it cannot be. */
    @Nullable
    public JsonObject edit(String conv, long id, String text) {
        Conversation c = mConversations.get(conv);
        ChatMessage m = c == null ? null : c.messages.get(id);
        if (m == null || !m.from.equals(mMe)) return null;
        c.editing.put(id, text);
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "chat_edit");
        frame.add("msg", m.wire());
        frame.addProperty("text", text);
        return frame;
    }

    /** The edit went through: {@code msg} of its {@code ack}. */
    public void edited(String conv, long id, @Nullable JsonObject msg) {
        Conversation c = conversation(conv);
        c.editing.remove(id);
        ChatMessage m = ChatMessage.read(msg, conv);
        if (m != null && m.id == id) put(m);
    }

    /** The edit did not go: the text the crew has comes back. */
    public void editFailed(String conv, long id, String code) {
        Conversation c = conversation(conv);
        c.editing.remove(id);
        if ("message_not_found".equals(code)) remove(conv, id);
    }

    /** "Herkesten sil": the frame, the message shown as going; null when it cannot be. */
    @Nullable
    public JsonObject delete(String conv, long id) {
        Conversation c = mConversations.get(conv);
        ChatMessage m = c == null ? null : c.messages.get(id);
        if (m == null) return null;
        c.deleting.add(id);
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "chat_delete");
        frame.add("msg", m.wire());
        return frame;
    }

    public void deleted(String conv, long id) {
        remove(conv, id);
    }

    /** The delete did not go: the message stays (one the service no longer has goes from here too). */
    public void deleteFailed(String conv, long id, String code) {
        Conversation c = conversation(conv);
        c.deleting.remove(id);
        if ("message_not_found".equals(code)) remove(conv, id);
    }

    // ---- the player's own settings ----

    /** The player saw the conversation up to its newest message. */
    public boolean markRead(String conv) {
        Conversation c = mConversations.get(conv);
        if (c == null || c.messages.isEmpty()) return false;
        long newest = c.messages.lastKey();
        if (newest <= c.meta.lastRead) return false;
        c.meta = new Meta(newest, c.meta.clearedUpTo, c.meta.mutedUntil);
        mStore.putMeta(conv, c.meta);
        return true;
    }

    /** Muted on this phone until {@code untilSeconds} ({@link #MUTED_FOREVER}; 0 unmutes). */
    public void setMuted(String conv, long untilSeconds) {
        Conversation c = conversation(conv);
        c.meta = new Meta(c.meta.lastRead, c.meta.clearedUpTo, Math.max(0, untilSeconds));
        mStore.putMeta(conv, c.meta);
    }

    public long mutedUntil(String conv) {
        Conversation c = mConversations.get(conv);
        return c == null ? 0 : c.meta.mutedUntil;
    }

    public boolean mutedAt(String conv, long nowSeconds) {
        long until = mutedUntil(conv);
        return until == MUTED_FOREVER || nowSeconds < until;
    }

    /** "Geçmişi temizle" (this phone only): what is here goes and does not come back with a sync. */
    public void clearHistory(String conv) {
        Conversation c = conversation(conv);
        long upTo = Math.max(c.meta.clearedUpTo, c.messages.isEmpty() ? mSyncCursor : Math.max(c.messages.lastKey(), mSyncCursor));
        c.messages.clear();
        c.outbox.clear();
        c.editing.clear();
        c.deleting.clear();
        c.hasOlder = false;
        c.meta = new Meta(upTo, upTo, c.meta.mutedUntil);
        mStore.clear(conv);
        mStore.putMeta(conv, c.meta);
    }
}
