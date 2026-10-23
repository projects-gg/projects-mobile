package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * <p>FIX2: a message deleted for everyone stays in its place as a {@link Tombstone} (who sent it and
 * when, who deleted it and when). Its text and seal are erased, here and in the store, and never come
 * back (a late sync may still list the message). Only a message this device held becomes one;
 * tombstones are lines of the conversation like messages, never unread, and offer no actions.
 *
 * <p>CHAT2 (README "Chat extras"): stickers and replies are messages (a reply keeps only the link to
 * the message it answers; the quote comes from this device's own history, "Önceki bir mesaj" when it
 * does not have it); reactions ride on the messages, the player's own changes shown at once until the
 * service answers each of them; read marks keep the unread counts in step across the player's devices
 * and, in a direct conversation, say what the friend has seen ("Görüldü"). A phone that gains these
 * capabilities syncs once from a week back (the README's catching up).
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
    /** CHAT2: the first sync with the extras reaches this far back once (the mailbox's week). */
    public static final long CATCH_UP_MS = 7 * 86_400_000L;
    /** Tries before a message whose answer keeps getting lost fails. */
    public static final int MAX_ATTEMPTS = 3;
    /** A conversation muted until unmuted. */
    public static final long MUTED_FOREVER = Long.MAX_VALUE;
    /** CHAT2: different emoji one player may put on one message (the owner's five). */
    public static final int REACTIONS_PER_PERSON = 5;
    /** CHAT2: different emoji one message may carry. */
    public static final int REACTION_KINDS = 10;
    /** CHAT2: messages take reactions while they are in the mailbox (a week). */
    public static final long REACTION_WINDOW_MS = 7 * 86_400_000L;

    private static final char VS16 = (char) 0xFE0F;
    /** The middle finger is never a reaction, in any tone (the service refuses it). */
    private static final String MIDDLE_FINGER = new String(Character.toChars(0x1F595));

    /** Why a message did not go (what the screen says under it). */
    public enum Failure {
        LINK, PHONE, WORDS, MUTED, OFF, RATE, DAILY, NOT_FRIENDS, NOT_IN_CREW, INVALID, UNAVAILABLE, FAILED,
        /** CHAT2: today's stickers are used up ({@code rate_limited} with {@code item: "stickers"}). */
        STICKERS,
        /** CHAT2: today's messages with links are used up ({@code item: "links"}). */
        LINKS,
        /** CHAT2: a new Discord account sends no links yet ({@code account_limited}, {@code chat_links}). */
        LIMITED_LINKS,
        /** CHAT2: Studio switched the extra off ({@code chat_feature_off}). */
        FEATURE_OFF
    }

    /** Where the device keeps the chat (the app's SQLite file; a map in the tests). */
    public interface Store {
        void putMessage(ChatMessage message);

        void removeMessage(String conv, long id);

        /**
         * FIX2: the message {@code id} of {@code conv} was deleted for everyone: its text and seal are
         * erased, its sender, time and nonce stay with who deleted it and when. Nothing is written for a
         * message the store does not hold.
         */
        void putTombstone(String conv, long id, String by, long at);

        void putOutbox(Outbox entry);

        void removeOutbox(String nonce);

        void putMeta(String conv, Meta meta);

        /** "Geçmişi temizle": every message of the conversation and its outbox go. */
        void clear(String conv);

        void putSyncCursor(long newestSeen);

        /** CHAT2: the one sync from a week back is done for this account. */
        default void putCaughtUp() {}
    }

    /** A store that keeps nothing (the tests' default; the app until its file is open). */
    public static final Store NO_STORE = new Store() {
        @Override public void putMessage(ChatMessage message) {}
        @Override public void removeMessage(String conv, long id) {}
        @Override public void putTombstone(String conv, long id, String by, long at) {}
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
        /** CHAT2: in a direct conversation, the newest message id the friend has seen (0: not known). */
        public final long peerRead;

        public Meta(long lastRead, long clearedUpTo, long mutedUntil) {
            this(lastRead, clearedUpTo, mutedUntil, 0);
        }

        public Meta(long lastRead, long clearedUpTo, long mutedUntil, long peerRead) {
            this.lastRead = lastRead;
            this.clearedUpTo = clearedUpTo;
            this.mutedUntil = mutedUntil;
            this.peerRead = peerRead;
        }

        Meta withLastRead(long id) {
            return new Meta(id, clearedUpTo, mutedUntil, peerRead);
        }

        Meta withPeerRead(long id) {
            return new Meta(lastRead, clearedUpTo, mutedUntil, id);
        }

        static final Meta NONE = new Meta(0, 0, 0, 0);
    }

    /** A message the player is sending. */
    public static final class Outbox {
        public enum State { WAITING, SENDING, FAILED }

        public final String nonce;
        public final String conv;
        /** The text; "" for a sticker. */
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
        /** CHAT2: a sticker in place of the text, or null. */
        @Nullable
        public final String sticker;
        /** CHAT2: the message it answers as this device held it when the player wrote (its seal), or null. */
        @Nullable
        public final JsonObject reply;
        /** CHAT2: the link rule a refusal named ({@code chat_rejected} with {@code link}), or null. */
        @Nullable
        public final String rule;

        public Outbox(String nonce, String conv, String text, long createdAt, long firstSentAt, int attempts, State state,
                      @Nullable Failure failure, long notBefore) {
            this(nonce, conv, text, createdAt, firstSentAt, attempts, state, failure, notBefore, null, null, null);
        }

        public Outbox(String nonce, String conv, String text, long createdAt, long firstSentAt, int attempts, State state,
                      @Nullable Failure failure, long notBefore, @Nullable String sticker, @Nullable JsonObject reply,
                      @Nullable String rule) {
            this.nonce = nonce;
            this.conv = conv;
            this.text = sticker != null ? "" : text;
            this.createdAt = createdAt;
            this.firstSentAt = firstSentAt;
            this.attempts = attempts;
            this.state = state;
            this.failure = failure;
            this.notBefore = notBefore;
            this.sticker = sticker;
            this.reply = reply;
            this.rule = rule;
        }

        Outbox with(State next, @Nullable Failure why, long wait) {
            return new Outbox(nonce, conv, text, createdAt, firstSentAt, attempts, next, why, wait, sticker, reply, null);
        }

        Outbox failed(Failure why, @Nullable String linkRule) {
            return new Outbox(nonce, conv, text, createdAt, firstSentAt, attempts, State.FAILED, why, 0, sticker, reply, linkRule);
        }

        Outbox sending(long nowMs) {
            return new Outbox(nonce, conv, text, createdAt, firstSentAt == 0 ? nowMs : firstSentAt, attempts + 1,
                    State.SENDING, null, 0, sticker, reply, null);
        }

        /** The same message under a new nonce (a retry after the service forgot the old one). */
        Outbox renewed(String newNonce) {
            return new Outbox(newNonce, conv, text, createdAt, 0, 0, State.WAITING, null, 0, sticker, reply, null);
        }

        /** The same message without its reply (the message it answered is gone). */
        Outbox withoutReply() {
            return new Outbox(nonce, conv, text, createdAt, firstSentAt, attempts, state, failure, notBefore, sticker, null, rule);
        }

        /** The message it answers: its id (0: not a reply). */
        public long replyId() {
            return reply == null ? 0 : Json.num(reply, "id", 0);
        }

        @Nullable
        public String replyFrom() {
            return reply == null ? null : Json.str(reply, "from");
        }

        /** The frame that sends it ({@code rid} is the connection's). */
        public JsonObject frame() {
            JsonObject o = new JsonObject();
            o.addProperty("t", "chat");
            o.addProperty("conv", conv);
            if (sticker != null) o.addProperty("sticker", sticker);
            else o.addProperty("text", text);
            if (reply != null) {
                JsonObject r = new JsonObject();
                r.add("msg", reply);
                o.add("reply", r);
            }
            o.addProperty("nonce", nonce);
            return o;
        }
    }

    /**
     * FIX2: a message deleted for everyone, kept in its place: who sent it and when ({@code id}, the
     * hub's clock), who deleted it and when. Its text and seal are gone.
     */
    public static final class Tombstone {
        /** {@link #by} of a message Projects' moderators deleted. */
        public static final String STAFF = "staff";

        public final String conv;
        public final long id;
        public final String from;
        /** The sender's own key (the player's messages): the tombstone keeps the message's place on screen. */
        @Nullable
        public final String nonce;
        /** A user id (the sender, or a crew's leader or moderator), {@link #STAFF}, or "" when not known. */
        public final String by;
        /** The hub's clock in ms when it was deleted (0 when not known). */
        public final long at;

        public Tombstone(String conv, long id, String from, @Nullable String nonce, @Nullable String by, long at) {
            this.conv = conv;
            this.id = id;
            this.from = from;
            this.nonce = nonce;
            this.by = by == null ? "" : by;
            this.at = Math.max(0, at);
        }

        static Tombstone of(ChatMessage message, @Nullable String by, long at) {
            return new Tombstone(message.conv, message.id, message.from, message.nonce, by, at);
        }

        /** The same key the message had ({@link Entry#key()}). */
        public String key() {
            return nonce != null ? "n:" + nonce : "m:" + id;
        }

        /** The sender deleted it themselves. */
        public boolean bySender() {
            return by.equals(from);
        }

        public boolean byStaff() {
            return STAFF.equals(by);
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
        /** FIX2: the line is a message deleted for everyone (then {@link #message} and {@link #outbox} are null). */
        @Nullable
        public final Tombstone tombstone;
        /** CHAT2: the reactions as shown (the player's changes on their way included); empty when none. */
        public final List<ChatMessage.Reaction> reactions;

        Entry(@Nullable ChatMessage message, @Nullable Outbox outbox, @Nullable String editing, boolean deleting) {
            this(message, outbox, editing, deleting, null,
                    message == null ? Collections.<ChatMessage.Reaction>emptyList() : message.reactions);
        }

        private Entry(@Nullable ChatMessage message, @Nullable Outbox outbox, @Nullable String editing, boolean deleting,
                      @Nullable Tombstone tombstone, List<ChatMessage.Reaction> reactions) {
            this.message = message;
            this.outbox = outbox;
            this.editing = editing;
            this.deleting = deleting;
            this.tombstone = tombstone;
            this.reactions = reactions;
        }

        static Entry of(Tombstone tombstone) {
            return new Entry(null, null, null, false, tombstone, Collections.<ChatMessage.Reaction>emptyList());
        }

        /** Stable while a sent message becomes the one the service took (its nonce), else its id; a tombstone keeps it. */
        public String key() {
            if (tombstone != null) return tombstone.key();
            if (outbox != null) return "n:" + outbox.nonce;
            return message.nonce != null ? "n:" + message.nonce : "m:" + message.id;
        }

        /** The text shown; "" for a tombstone (the screen says who deleted it instead) and for a sticker. */
        public String text() {
            if (tombstone != null) return "";
            if (outbox != null) return outbox.text;
            return editing != null ? editing : message.text;
        }

        /** CHAT2: the sticker ({@code pl1/gg}) of a sticker message, else null. */
        @Nullable
        public String sticker() {
            if (tombstone != null) return null;
            return outbox != null ? outbox.sticker : message.sticker;
        }

        /** CHAT2: the message it answers (0: not a reply). */
        public long replyId() {
            if (tombstone != null) return 0;
            return outbox != null ? outbox.replyId() : message.replyId;
        }

        @Nullable
        public String replyFrom() {
            if (tombstone != null) return null;
            return outbox != null ? outbox.replyFrom() : message.replyFrom;
        }

        public String from(String me) {
            if (tombstone != null) return tombstone.from;
            return outbox != null ? me : message.from;
        }

        public boolean mine(String me) {
            if (tombstone != null) return tombstone.from.equals(me);
            return outbox != null || message.from.equals(me);
        }

        public boolean pending() {
            if (tombstone != null) return false;
            return outbox != null ? outbox.state != Outbox.State.FAILED : editing != null || deleting;
        }

        public boolean failed() {
            return outbox != null && outbox.state == Outbox.State.FAILED;
        }

        /** FIX2: a message deleted for everyone. */
        public boolean deleted() {
            return tombstone != null;
        }

        /** When it was written (ms): the hub's for a sent message or a tombstone, the phone's for one on its way. */
        public long time() {
            if (tombstone != null) return tombstone.id;
            return message != null ? message.id : outbox.createdAt;
        }
    }

    /** What changed for the app to react to (a notification, the open screen). */
    public static final class Event {
        public enum Kind { NEW, EDITED, DELETED, SYNCED, REACTED, READ }

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
        /** FIX2: the tombstones among the newest lines, per conversation. */
        public final Map<String, List<Tombstone>> tombstones;
        /** CHAT2: the one sync from a week back was done. */
        public final boolean caughtUp;

        public Snapshot(Map<String, List<ChatMessage>> messages, Map<String, Boolean> hasOlder, List<Outbox> outbox,
                        Map<String, Meta> meta, long syncCursor) {
            this(messages, hasOlder, outbox, meta, syncCursor, new HashMap<String, List<Tombstone>>());
        }

        public Snapshot(Map<String, List<ChatMessage>> messages, Map<String, Boolean> hasOlder, List<Outbox> outbox,
                        Map<String, Meta> meta, long syncCursor, Map<String, List<Tombstone>> tombstones) {
            this(messages, hasOlder, outbox, meta, syncCursor, tombstones, false);
        }

        public Snapshot(Map<String, List<ChatMessage>> messages, Map<String, Boolean> hasOlder, List<Outbox> outbox,
                        Map<String, Meta> meta, long syncCursor, Map<String, List<Tombstone>> tombstones, boolean caughtUp) {
            this.messages = messages;
            this.hasOlder = hasOlder;
            this.outbox = outbox;
            this.meta = meta;
            this.syncCursor = syncCursor;
            this.tombstones = tombstones;
            this.caughtUp = caughtUp;
        }

        public static Snapshot empty() {
            return new Snapshot(new HashMap<String, List<ChatMessage>>(), new HashMap<String, Boolean>(),
                    new ArrayList<Outbox>(), new HashMap<String, Meta>(), 0);
        }
    }

    /**
     * CHAT2: what a reply quotes, from this device's own history: the message, a deleted one ("Mesaj
     * silindi"), or one it does not have ("Önceki bir mesaj").
     */
    public static final class Quote {
        public enum Kind { MESSAGE, DELETED, EARLIER }

        public final Kind kind;
        public final long id;
        /** Its sender as the link names it. */
        @Nullable
        public final String from;
        /** The message (with {@link Kind#MESSAGE}). */
        @Nullable
        public final ChatMessage message;

        Quote(Kind kind, long id, @Nullable String from, @Nullable ChatMessage message) {
            this.kind = kind;
            this.id = id;
            this.from = from;
            this.message = message;
        }
    }

    /** CHAT2: what a press on an emoji does to a message's reactions (the desktop's {@code reactVerdict}). */
    public enum ReactVerdict { ADD, REMOVE, MINE, KINDS, REFUSED }

    private static final class Conversation {
        final String conv;
        final TreeMap<Long, ChatMessage> messages = new TreeMap<>();
        /** FIX2: messages deleted for everyone, in their places (never in {@link #messages} too). */
        final TreeMap<Long, Tombstone> tombstones = new TreeMap<>();
        final LinkedHashMap<String, Outbox> outbox = new LinkedHashMap<>();
        final Map<Long, String> editing = new HashMap<>();
        final Set<Long> deleting = new HashSet<>();
        /** CHAT2: the player's reaction changes on their way, by message id, in the order made. */
        final Map<Long, List<Object[]>> reacting = new HashMap<>();
        /** CHAT2: answered messages looked up in the store (older than memory keeps): the message, or null when not there. */
        final Map<Long, ChatMessage> foundQuotes = new HashMap<>();
        final Set<Long> missingQuotes = new HashSet<>();
        final Map<Long, Tombstone> foundTombstones = new HashMap<>();
        Meta meta = Meta.NONE;
        boolean hasOlder;

        Conversation(String conv) {
            this.conv = conv;
        }
    }

    private final String mMe;
    private final Store mStore;
    /** CHAT2: the socket took {@code reactions} (see {@link #setReactionsTaken}). */
    private boolean mReactionsTaken;
    private final Map<String, Conversation> mConversations = new LinkedHashMap<>();
    /** The newest message id this device ever held (the next sync starts a minute before it). */
    private long mSyncCursor;
    /** The hub's clock minus the phone's (ms), from {@code chat_synced}. */
    private long mHubOffsetMs;
    /** CHAT2: the one sync from a week back is done. */
    private boolean mCaughtUp;

    public ChatBook(String me, Store store, Snapshot snapshot) {
        mMe = me;
        mStore = store;
        for (Map.Entry<String, List<ChatMessage>> e : snapshot.messages.entrySet()) {
            Conversation c = conversation(e.getKey());
            for (ChatMessage m : e.getValue()) c.messages.put(m.id, m);
            Boolean older = snapshot.hasOlder.get(e.getKey());
            c.hasOlder = older != null && older;
        }
        for (Map.Entry<String, List<Tombstone>> e : snapshot.tombstones.entrySet()) {
            Conversation c = conversation(e.getKey());
            for (Tombstone t : e.getValue()) {
                c.messages.remove(t.id);
                c.tombstones.put(t.id, t);
            }
        }
        for (Outbox o : snapshot.outbox) {
            // A message that was on its way when the app stopped waits again (the same nonce: no second copy).
            Outbox waiting = o.state == Outbox.State.SENDING ? o.with(Outbox.State.WAITING, null, 0) : o;
            conversation(o.conv).outbox.put(o.nonce, waiting);
        }
        for (Map.Entry<String, Meta> e : snapshot.meta.entrySet()) conversation(e.getKey()).meta = e.getValue();
        mSyncCursor = snapshot.syncCursor;
        mCaughtUp = snapshot.caughtUp;
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

    /**
     * The conversation's lines, oldest first: the service's messages and the tombstones of deleted ones by
     * id, then the player's waiting ones.
     */
    public List<Entry> entries(String conv) {
        Conversation c = mConversations.get(conv);
        List<Entry> out = new ArrayList<>();
        if (c == null) return out;
        TreeMap<Long, Entry> lines = new TreeMap<>();
        for (ChatMessage m : c.messages.values()) {
            lines.put(m.id, new Entry(m, null, c.editing.get(m.id), c.deleting.contains(m.id), null, shownReactions(c, m)));
        }
        for (Tombstone t : c.tombstones.values()) lines.put(t.id, Entry.of(t));
        out.addAll(lines.values());
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

    /** FIX2: the tombstone of a deleted message held here, or null. */
    @Nullable
    public Tombstone tombstone(String conv, long id) {
        Conversation c = mConversations.get(conv);
        return c == null ? null : c.tombstones.get(id);
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

    /** The oldest line's id held in memory (the store is read before it for older ones), or 0. */
    public long oldestId(String conv) {
        Conversation c = mConversations.get(conv);
        if (c == null) return 0;
        long oldest = c.messages.isEmpty() ? 0 : c.messages.firstKey();
        if (!c.tombstones.isEmpty() && (oldest == 0 || c.tombstones.firstKey() < oldest)) oldest = c.tombstones.firstKey();
        return oldest;
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
            // FIX2: a deleted newest message shows as its tombstone (the conversation keeps its place).
            if (!c.tombstones.isEmpty()) {
                Tombstone t = c.tombstones.lastEntry().getValue();
                if (last == null || t.id > activity) {
                    last = Entry.of(t);
                    activity = t.id;
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

    /**
     * CHAT2: {@code since} for a socket with the extras: once (per account on this phone) from a week
     * before the newest message held, so the stickers, reply links and reactions an older app could
     * not get come now (duplicates merge by {@code conv} + {@code id}); then as usual.
     */
    public long syncSince(boolean extras) {
        if (!extras || mCaughtUp || mSyncCursor <= 0) return syncSince();
        return Math.max(0, mSyncCursor - CATCH_UP_MS);
    }

    /** CHAT2: a sync with the extras finished (the week-back one is done). */
    public void caughtUp() {
        if (mCaughtUp) return;
        mCaughtUp = true;
        mStore.putCaughtUp();
    }

    public boolean isCaughtUp() {
        return mCaughtUp;
    }

    public long hubNowMs(long phoneNowMs) {
        return phoneNowMs + mHubOffsetMs;
    }

    public void setHubOffsetMs(long offset) {
        mHubOffsetMs = offset;
    }

    // ---- rules ----

    /** "Düzenle": the player's own text message, within 15 minutes of its id, nothing else on its way (a sticker never). */
    public boolean canEdit(Entry entry, long hubNowMs) {
        if (entry.message == null || entry.deleting || entry.editing != null || entry.message.isSticker()) return false;
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

    /** CHAT2 "Yanıtla": a message the service took (any age), not deleted, not being deleted. */
    public boolean canReply(Entry entry) {
        return entry.message != null && !entry.deleting && entry.tombstone == null;
    }

    /** CHAT2: the message takes reactions now: sent, not deleted, younger than a week (the mailbox's). */
    public boolean reactable(Entry entry, long hubNowMs) {
        return entry.message != null && !entry.deleting && entry.tombstone == null
                && hubNowMs - entry.message.id < REACTION_WINDOW_MS;
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
     * {@code chat}, {@code chat_edited}, {@code chat_deleted} and {@code chat_sync}; CHAT2's
     * {@code chat_reacted} and {@code chat_read}; anything else changes nothing. A new message from
     * someone else, live, is an {@link Event.Kind#NEW} (a sync's are not: a phone that was away got
     * their pushes).
     */
    public List<Event> apply(JsonObject frame) {
        List<Event> events = new ArrayList<>();
        String type = Json.str(frame, "t", "");
        switch (type) {
            case "chat": {
                JsonObject wire = Json.obj(frame, "msg");
                ChatMessage m = ChatMessage.read(wire, null);
                if (m == null || buried(m)) break;
                boolean known = put(m, wire != null && wire.has("reactions"));
                if (!known && !m.from.equals(mMe)) events.add(new Event(Event.Kind.NEW, m.conv, m));
                else events.add(new Event(Event.Kind.EDITED, m.conv, m));
                break;
            }
            case "chat_edited": {
                JsonObject wire = Json.obj(frame, "msg");
                ChatMessage m = ChatMessage.read(wire, null);
                if (m == null || buried(m)) break;
                Conversation c = conversation(m.conv);
                c.editing.remove(m.id);
                put(m, reactionsKnown(wire));
                events.add(new Event(Event.Kind.EDITED, m.conv, m));
                break;
            }
            case "chat_deleted": {
                String conv = Json.str(frame, "conv");
                long id = Json.num(frame, "id", 0);
                if (!ChatConv.valid(conv) || id <= 0) break;
                bury(conv, id, Json.str(frame, "by", ""), Json.num(frame, "at", 0));
                events.add(new Event(Event.Kind.DELETED, conv, null));
                break;
            }
            case "chat_sync": {
                String conv = Json.str(frame, "conv");
                if (!ChatConv.valid(conv)) break;
                for (JsonObject wire : Json.objects(frame, "messages")) {
                    ChatMessage m = ChatMessage.read(wire, conv);
                    // A sync's message says its reactions whole (none when it has none).
                    if (m != null && m.conv.equals(conv) && !buried(m)) put(m, true);
                }
                for (JsonObject deleted : Json.objects(frame, "deleted")) {
                    long id = Json.num(deleted, "id", 0);
                    if (id > 0) bury(conv, id, Json.str(deleted, "by", ""), Json.num(deleted, "at", 0));
                }
                // CHAT2: messages whose reactions changed while the message did not, and the read marks.
                for (JsonObject reacted : Json.objects(frame, "reacted")) {
                    long id = Json.num(reacted, "id", 0);
                    if (id > 0) setReactions(conv, id, ChatMessage.readReactions(reacted));
                }
                for (JsonObject read : Json.objects(frame, "reads")) {
                    readMark(conv, Json.str(read, "by"), Json.num(read, "id", 0));
                }
                events.add(new Event(Event.Kind.SYNCED, conv, null));
                break;
            }
            case "chat_reacted": {
                String conv = Json.str(frame, "conv");
                long id = Json.num(frame, "id", 0);
                String by = Json.str(frame, "by");
                String e = Json.str(frame, "e");
                if (!ChatConv.valid(conv) || id <= 0) break;
                JsonObject wire = frame;
                if (wire.has("reactions") && wire.get("reactions").isJsonArray()) {
                    setReactions(conv, id, ChatMessage.readReactions(wire));
                } else if (SocialModel.isUserId(by) && e != null && !e.isEmpty()) {
                    ChatMessage held = message(conv, id);
                    if (held != null) {
                        setReactions(conv, id, withReaction(held.reactions, e, by, Json.bool(frame, "on", true)));
                    }
                }
                events.add(new Event(Event.Kind.REACTED, conv, null));
                break;
            }
            case "chat_read": {
                String conv = Json.str(frame, "conv");
                if (!ChatConv.valid(conv)) break;
                if (readMark(conv, Json.str(frame, "by"), Json.num(frame, "id", 0))) {
                    events.add(new Event(Event.Kind.READ, conv, null));
                }
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

    /** FIX2: a message deleted for everyone stays a tombstone (a late frame or sync brings nothing back). */
    private boolean buried(ChatMessage m) {
        Conversation c = mConversations.get(m.conv);
        return c != null && c.tombstones.containsKey(m.id);
    }

    /**
     * CHAT2: whether the socket took {@code reactions} (the chat keeps it current). Then a {@code chat_edited}
     * or an {@code ack.msg} without {@code reactions} says the message has none (the service's README).
     */
    public void setReactionsTaken(boolean taken) {
        mReactionsTaken = taken;
    }

    /** The message's reactions are said by {@code wire}: listed there, or none while the socket took them. */
    private boolean reactionsKnown(@Nullable JsonObject wire) {
        return wire != null && (wire.has("reactions") || mReactionsTaken);
    }

    /**
     * Keeps a message (a newer version replaces the one held, keeping what it does not say: the nonce,
     * the reply link, and the reactions unless {@code reactionsKnown}). True when it was held already.
     */
    private boolean put(ChatMessage m, boolean reactionsKnown) {
        Conversation c = conversation(m.conv);
        if (m.id <= c.meta.clearedUpTo) return true;
        if (c.tombstones.containsKey(m.id)) return true;
        ChatMessage before = c.messages.get(m.id);
        ChatMessage keep = m.mergedWith(before, reactionsKnown);
        // The player's own message from this device: it leaves the outbox (a sync after a lost answer, too).
        if (keep.nonce != null && keep.from.equals(mMe)) {
            Outbox o = c.outbox.remove(keep.nonce);
            if (o != null) mStore.removeOutbox(o.nonce);
        }
        if (before != null && sameVersion(before, keep)) return true;
        c.messages.put(keep.id, keep);
        c.missingQuotes.remove(keep.id);
        mStore.putMessage(keep);
        if (keep.id > mSyncCursor) {
            mSyncCursor = keep.id;
            mStore.putSyncCursor(mSyncCursor);
        }
        if (c.messages.size() > WINDOW * 2) {
            // Memory keeps the newest; the store keeps the rest for scrolling up.
            while (c.messages.size() > WINDOW) c.messages.pollFirstEntry();
            long oldest = c.messages.firstKey();
            while (!c.tombstones.isEmpty() && c.tombstones.firstKey() < oldest) c.tombstones.pollFirstEntry();
            c.hasOlder = true;
        }
        return before != null;
    }

    private boolean put(ChatMessage m) {
        return put(m, false);
    }

    private static boolean sameVersion(ChatMessage a, ChatMessage b) {
        return a.text.equals(b.text) && a.mac.equals(b.mac) && a.editedAt == b.editedAt
                && (a.nonce == null ? b.nonce == null : a.nonce.equals(b.nonce))
                && (a.sticker == null ? b.sticker == null : a.sticker.equals(b.sticker))
                && a.replyId == b.replyId && ChatMessage.sameReactions(a.reactions, b.reactions);
    }

    /**
     * FIX2: a message deleted for everyone becomes its tombstone: the text and the seal leave memory and
     * the store. A message only the store holds (older than what memory keeps) is buried there; one this
     * device never held leaves no trace. A second word about it changes nothing.
     */
    private void bury(String conv, long id, @Nullable String by, long at) {
        Conversation c = conversation(conv);
        c.editing.remove(id);
        c.deleting.remove(id);
        c.reacting.remove(id);
        c.foundQuotes.remove(id);
        if (c.tombstones.containsKey(id)) return;
        ChatMessage held = c.messages.remove(id);
        if (held != null) c.tombstones.put(id, Tombstone.of(held, by, at));
        mStore.putTombstone(conv, id, by == null ? "" : by, Math.max(0, at));
    }

    /** Older messages read from the store (scrolling up): kept in memory too. */
    public void addOlder(String conv, List<ChatMessage> older, boolean more) {
        addOlder(conv, older, new ArrayList<Tombstone>(), more);
    }

    /** The same with the tombstones among them (FIX2). */
    public void addOlder(String conv, List<ChatMessage> older, List<Tombstone> tombstones, boolean more) {
        Conversation c = conversation(conv);
        for (Tombstone t : tombstones) {
            if (t.conv.equals(conv) && t.id > c.meta.clearedUpTo && !c.messages.containsKey(t.id)) c.tombstones.put(t.id, t);
        }
        for (ChatMessage m : older) {
            if (m.conv.equals(conv) && m.id > c.meta.clearedUpTo && !c.tombstones.containsKey(m.id)) c.messages.put(m.id, m);
        }
        c.hasOlder = more;
    }

    // ---- replies (CHAT2) ----

    /**
     * What a reply to {@code replyId} quotes now: the message held here (in memory, or found in the
     * store), "Mesaj silindi" for one deleted for everyone, else "Önceki bir mesaj".
     */
    public Quote quote(String conv, long replyId, @Nullable String replyFrom) {
        Conversation c = mConversations.get(conv);
        if (c != null) {
            ChatMessage m = c.messages.get(replyId);
            if (m == null) m = c.foundQuotes.get(replyId);
            if (m != null) return new Quote(Quote.Kind.MESSAGE, replyId, m.from, m);
            if (c.tombstones.containsKey(replyId) || c.foundTombstones.containsKey(replyId)) {
                return new Quote(Quote.Kind.DELETED, replyId, replyFrom, null);
            }
        }
        return new Quote(Quote.Kind.EARLIER, replyId, replyFrom, null);
    }

    /**
     * Whether the store should be asked for an answered message: not in memory, not asked before, and
     * the store holds older lines than memory does.
     */
    public boolean quoteNeedsLookup(String conv, long replyId) {
        Conversation c = mConversations.get(conv);
        if (c == null || replyId <= 0 || !c.hasOlder) return false;
        if (c.messages.containsKey(replyId) || c.tombstones.containsKey(replyId)) return false;
        if (c.foundQuotes.containsKey(replyId) || c.foundTombstones.containsKey(replyId)) return false;
        return !c.missingQuotes.contains(replyId) && replyId < oldestId(conv);
    }

    /** The store's answer for an answered message: the message, its tombstone, or neither (not there). */
    public void quoteFound(String conv, long replyId, @Nullable ChatMessage message, @Nullable Tombstone tombstone) {
        Conversation c = conversation(conv);
        if (message != null && message.id == replyId) c.foundQuotes.put(replyId, message);
        else if (tombstone != null && tombstone.id == replyId) c.foundTombstones.put(replyId, tombstone);
        else c.missingQuotes.add(replyId);
    }

    // ---- reactions (CHAT2) ----

    /** ❤ and ❤️ are the same reaction (the service adds a missing VS16); a skin tone makes another. */
    public static boolean sameEmoji(String a, String b) {
        return strip(a).equals(strip(b));
    }

    private static String strip(String emoji) {
        return emoji.indexOf(VS16) < 0 ? emoji : emoji.replace(String.valueOf(VS16), "");
    }

    /** The middle finger, in any tone: never a reaction. */
    public static boolean refusedReaction(String emoji) {
        return emoji.startsWith(MIDDLE_FINGER);
    }

    /**
     * What a press on {@code emoji} does to the reactions {@code shown}: takes the player's back when
     * they gave it, adds it when there is room, else why not (five of theirs; ten kinds and this one
     * new; the middle finger).
     */
    public ReactVerdict reactVerdict(List<ChatMessage.Reaction> shown, String emoji) {
        if (refusedReaction(emoji)) return ReactVerdict.REFUSED;
        ChatMessage.Reaction kind = null;
        int mine = 0;
        for (ChatMessage.Reaction r : shown) {
            if (sameEmoji(r.e, emoji)) kind = r;
            if (r.has(mMe)) mine++;
        }
        if (kind != null && kind.has(mMe)) return ReactVerdict.REMOVE;
        if (mine >= REACTIONS_PER_PERSON) return ReactVerdict.MINE;
        if (kind == null && shown.size() >= REACTION_KINDS) return ReactVerdict.KINDS;
        return ReactVerdict.ADD;
    }

    /**
     * The reactions with one player's change: their id added at the end of the kind's people (a new
     * kind at the end), or taken out (a kind nobody is left in goes).
     */
    public static List<ChatMessage.Reaction> withReaction(List<ChatMessage.Reaction> reactions, String emoji, String who, boolean on) {
        List<ChatMessage.Reaction> out = new ArrayList<>();
        boolean found = false;
        for (ChatMessage.Reaction r : reactions) {
            if (!sameEmoji(r.e, emoji)) {
                out.add(r);
                continue;
            }
            found = true;
            List<String> by = new ArrayList<>(r.by);
            if (on) {
                if (!by.contains(who)) by.add(who);
            } else {
                by.remove(who);
            }
            if (!by.isEmpty()) out.add(new ChatMessage.Reaction(r.e, by));
        }
        if (on && !found) {
            List<String> by = new ArrayList<>();
            by.add(who);
            out.add(new ChatMessage.Reaction(emoji, by));
        }
        return out;
    }

    /** The reactions as shown: the service's, with the player's own changes on their way laid over them. */
    private List<ChatMessage.Reaction> shownReactions(Conversation c, ChatMessage m) {
        List<Object[]> changes = c.reacting.get(m.id);
        if (changes == null || changes.isEmpty()) return m.reactions;
        List<ChatMessage.Reaction> out = m.reactions;
        for (Object[] change : changes) out = withReaction(out, (String) change[0], mMe, (Boolean) change[1]);
        return out;
    }

    /**
     * The player gives ({@code on}) or takes back an emoji on a message: shown at once; the frame to send,
     * or null when the message cannot take it (not held, older than a week, deleted).
     */
    @Nullable
    public JsonObject react(String conv, long id, String emoji, boolean on, long hubNowMs) {
        Conversation c = mConversations.get(conv);
        ChatMessage m = c == null ? null : c.messages.get(id);
        if (m == null || c.deleting.contains(id) || hubNowMs - id >= REACTION_WINDOW_MS) return null;
        List<Object[]> changes = c.reacting.get(id);
        if (changes == null) {
            changes = new ArrayList<>();
            c.reacting.put(id, changes);
        }
        changes.add(new Object[]{emoji, on});
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "chat_react");
        frame.addProperty("conv", conv);
        frame.addProperty("id", id);
        frame.addProperty("e", emoji);
        frame.addProperty("on", on);
        return frame;
    }

    /**
     * The service answered one change ({@code ack} with the message's {@code reactions} as the player
     * sees them, or a refusal: {@code ack} null). The change leaves the layer either way.
     */
    public void reactAnswered(String conv, long id, String emoji, boolean on, @Nullable JsonObject ack) {
        Conversation c = mConversations.get(conv);
        if (c == null) return;
        List<Object[]> changes = c.reacting.get(id);
        if (changes != null) {
            for (int i = 0; i < changes.size(); i++) {
                Object[] change = changes.get(i);
                if (sameEmoji((String) change[0], emoji) && ((Boolean) change[1]) == on) {
                    changes.remove(i);
                    break;
                }
            }
            if (changes.isEmpty()) c.reacting.remove(id);
        }
        if (ack != null && ack.has("reactions") && ack.get("reactions").isJsonArray()) {
            setReactions(conv, id, ChatMessage.readReactions(ack));
        }
    }

    /** A message's reactions as the service says them now (kept with the message in the store too). */
    private void setReactions(String conv, long id, List<ChatMessage.Reaction> reactions) {
        Conversation c = mConversations.get(conv);
        ChatMessage held = c == null ? null : c.messages.get(id);
        if (held == null || ChatMessage.sameReactions(held.reactions, reactions)) return;
        ChatMessage next = held.withReactions(reactions);
        c.messages.put(id, next);
        mStore.putMessage(next);
    }

    // ---- read marks (CHAT2) ----

    /**
     * A read mark: the player's own from another device moves what they read (the unread counts stay in
     * step); in a direct conversation the friend's says what they have seen. Only forward. True when it
     * changed something.
     */
    private boolean readMark(String conv, @Nullable String by, long id) {
        if (id <= 0 || by == null) return false;
        Conversation c = conversation(conv);
        if (by.equals(mMe)) {
            if (id <= c.meta.lastRead) return false;
            c.meta = c.meta.withLastRead(id);
            mStore.putMeta(conv, c.meta);
            return true;
        }
        if (!ChatConv.isDirect(conv) || !by.equals(ChatConv.other(conv, mMe)) || id <= c.meta.peerRead) return false;
        c.meta = c.meta.withPeerRead(id);
        mStore.putMeta(conv, c.meta);
        return true;
    }

    /** The newest message id the friend has seen in a direct conversation (0: not known). */
    public long peerRead(String conv) {
        Conversation c = mConversations.get(conv);
        return c == null ? 0 : c.meta.peerRead;
    }

    /**
     * "Görüldü": the key of the player's last sent message the friend's mark reaches, while the friend
     * has not written since (an answer says it was seen by itself); null when none (the desktop's
     * {@code seenKey}).
     */
    @Nullable
    public String seenKey(String conv) {
        long peer = peerRead(conv);
        if (peer <= 0 || !ChatConv.isDirect(conv)) return null;
        String found = null;
        for (Entry e : entries(conv)) {
            if (!e.mine(mMe)) {
                // A message of the friend's (also one deleted since) answers what came before it.
                found = null;
                continue;
            }
            if (e.message == null || e.deleted() || e.deleting) continue;
            if (e.message.id <= peer) found = e.key();
        }
        return found;
    }

    /**
     * The newest message of someone else's (not deleted) after anything the player wrote: the one a
     * read mark names once it was on screen for a second; 0 when the player wrote last (their message
     * reads everything before it) or there is none.
     */
    public long newestTheirs(String conv) {
        Conversation c = mConversations.get(conv);
        if (c == null) return 0;
        List<Entry> lines = entries(conv);
        for (int i = lines.size() - 1; i >= 0; i--) {
            Entry e = lines.get(i);
            if (e.mine(mMe)) return 0;
            if (e.message != null) return e.message.id;
        }
        return 0;
    }

    // ---- sending ----

    /** A new message: it shows at once as waiting. */
    public Outbox write(String conv, String text, String nonce, long phoneNowMs) {
        return write(conv, text, null, null, nonce, phoneNowMs);
    }

    /**
     * CHAT2: a new text or sticker, maybe a reply to {@code replyTo} (sent with the answered message as
     * this device holds it, its seal).
     */
    public Outbox write(String conv, String text, @Nullable String sticker, @Nullable ChatMessage replyTo, String nonce,
                        long phoneNowMs) {
        JsonObject reply = replyTo != null && replyTo.conv.equals(conv) ? replyTo.wire() : null;
        Outbox o = new Outbox(nonce, conv, text, phoneNowMs, 0, 0, Outbox.State.WAITING, null, 0, sticker, reply, null);
        conversation(conv).outbox.put(nonce, o);
        mStore.putOutbox(o);
        return o;
    }

    /**
     * The frame that sends {@code o} now: a reply whose message was deleted meanwhile goes without the
     * link (the service would refuse it; the quote would only say "Mesaj silindi").
     */
    public JsonObject frameOf(Outbox o) {
        long replyId = o.replyId();
        if (replyId > 0 && tombstone(o.conv, replyId) != null) {
            Outbox plain = o.withoutReply();
            Conversation c = byNonce(o.nonce);
            if (c != null) {
                c.outbox.put(o.nonce, plain);
                mStore.putOutbox(plain);
            }
            return plain.frame();
        }
        return o.frame();
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
        if (m.nonce == null) m = m.keepingNonce(new ChatMessage(m.conv, m.id, m.from, m.text, m.mac, m.editedAt, nonce));
        put(m, reactionsKnown(msg));
        if (c.outbox.remove(nonce) != null) mStore.removeOutbox(nonce);
    }

    /**
     * The service refused it, or its answer got lost ({@code unavailable}: the connection went or no
     * answer came). A lost answer waits for the next try with the same nonce (no second copy); a short
     * rate limit waits it out; a reply whose message the service no longer has goes again without the
     * link; anything else fails with its reason.
     */
    public void notSent(String nonce, String code, @Nullable String reason, long retryAfterSeconds, long phoneNowMs) {
        notSent(nonce, code, reason, retryAfterSeconds, phoneNowMs, null, null);
    }

    /** The same with CHAT2's {@code item} (which daily limit) and {@code rule} (which link rule). */
    public void notSent(String nonce, String code, @Nullable String reason, long retryAfterSeconds, long phoneNowMs,
                        @Nullable String item, @Nullable String rule) {
        Conversation c = byNonce(nonce);
        if (c == null) return;
        Outbox o = c.outbox.get(nonce);
        Outbox next;
        if ("unavailable".equals(code) || "internal".equals(code)) {
            next = o.attempts >= MAX_ATTEMPTS ? o.with(Outbox.State.FAILED, Failure.UNAVAILABLE, 0)
                    : o.with(Outbox.State.WAITING, null, "internal".equals(code) ? phoneNowMs + 2_000 : 0);
        } else if ("message_not_found".equals(code) && o.reply != null) {
            next = o.withoutReply().with(Outbox.State.WAITING, null, 0);
        } else if ("rate_limited".equals(code) && item == null && retryAfterSeconds > 0 && retryAfterSeconds <= 30) {
            next = o.with(Outbox.State.WAITING, null, phoneNowMs + retryAfterSeconds * 1000L);
        } else {
            Failure why = failure(code, reason, retryAfterSeconds, item);
            next = o.failed(why, why == Failure.LINK ? rule : null);
        }
        c.outbox.put(nonce, next);
        mStore.putOutbox(next);
    }

    /** What the screen says for a refusal of a send or an edit. */
    public static Failure failure(String code, @Nullable String reason, long retryAfterSeconds) {
        return failure(code, reason, retryAfterSeconds, null);
    }

    public static Failure failure(String code, @Nullable String reason, long retryAfterSeconds, @Nullable String item) {
        switch (code) {
            case "chat_rejected":
                if ("link".equals(reason)) return Failure.LINK;
                if ("phone".equals(reason)) return Failure.PHONE;
                return Failure.WORDS;
            case "chat_muted": return Failure.MUTED;
            case "chat_off": return Failure.OFF;
            case "chat_feature_off": return Failure.FEATURE_OFF;
            case "account_limited": return "chat_links".equals(item) ? Failure.LIMITED_LINKS : Failure.DAILY;
            case "rate_limited":
                if ("stickers".equals(item)) return Failure.STICKERS;
                if ("links".equals(item)) return Failure.LINKS;
                return retryAfterSeconds > 30 ? Failure.DAILY : Failure.RATE;
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
            // Edits, deletes and reactions without an answer are not known to have happened: the next sync tells.
            c.editing.clear();
            c.deleting.clear();
            c.reacting.clear();
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
            next = new Outbox(o.nonce, o.conv, o.text, o.createdAt, o.firstSentAt, 0, Outbox.State.WAITING, null, 0,
                    o.sticker, o.reply, null);
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
        if (m == null || !m.from.equals(mMe) || m.isSticker()) return null;
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
        if (m != null && m.id == id) put(m, reactionsKnown(msg));
    }

    /** The edit did not go: the text the crew has comes back (one the service no longer has is a tombstone). */
    public void editFailed(String conv, long id, String code) {
        Conversation c = conversation(conv);
        c.editing.remove(id);
        if ("message_not_found".equals(code)) bury(conv, id, "", 0);
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

    /** The player's own "Herkesten sil" went through (its {@code ack}): the message is the player's tombstone. */
    public void deleted(String conv, long id) {
        deleted(conv, id, 0);
    }

    /** The same, {@code atMs} the hub's clock now (0 when not known). */
    public void deleted(String conv, long id, long atMs) {
        bury(conv, id, mMe, atMs);
    }

    /** The delete did not go: the message stays (one the service no longer has is a tombstone, by someone not known). */
    public void deleteFailed(String conv, long id, String code) {
        Conversation c = conversation(conv);
        c.deleting.remove(id);
        if ("message_not_found".equals(code)) bury(conv, id, "", 0);
    }

    // ---- the player's own settings ----

    /** The player saw the conversation up to its newest message. */
    public boolean markRead(String conv) {
        Conversation c = mConversations.get(conv);
        if (c == null || c.messages.isEmpty()) return false;
        long newest = c.messages.lastKey();
        if (newest <= c.meta.lastRead) return false;
        c.meta = c.meta.withLastRead(newest);
        mStore.putMeta(conv, c.meta);
        return true;
    }

    /** Muted on this phone until {@code untilSeconds} ({@link #MUTED_FOREVER}; 0 unmutes). */
    public void setMuted(String conv, long untilSeconds) {
        Conversation c = conversation(conv);
        c.meta = new Meta(c.meta.lastRead, c.meta.clearedUpTo, Math.max(0, untilSeconds), c.meta.peerRead);
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
        long newest = c.messages.isEmpty() ? 0 : c.messages.lastKey();
        if (!c.tombstones.isEmpty()) newest = Math.max(newest, c.tombstones.lastKey());
        long upTo = Math.max(c.meta.clearedUpTo, Math.max(newest, mSyncCursor));
        c.messages.clear();
        c.tombstones.clear();
        c.outbox.clear();
        c.editing.clear();
        c.deleting.clear();
        c.reacting.clear();
        c.foundQuotes.clear();
        c.foundTombstones.clear();
        c.missingQuotes.clear();
        c.hasOlder = false;
        c.meta = new Meta(upTo, upTo, c.meta.mutedUntil, c.meta.peerRead);
        mStore.clear(conv);
        mStore.putMeta(conv, c.meta);
    }

    /** JSON of an outbox's answered message as the store keeps it (null: not a reply). */
    @Nullable
    public static String replyJson(Outbox o) {
        return o.reply == null ? null : o.reply.toString();
    }

    /** An outbox's answered message from the store's JSON (null: not a reply, or broken). */
    @Nullable
    public static JsonObject replyFrom(@Nullable String json) {
        JsonElement e = Json.parse(json);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Kept for tests: every reaction kind of a message as JSON. */
    static JsonArray reactionsJson(ChatMessage m) {
        return ChatMessage.reactionsJson(m.reactions);
    }
}
