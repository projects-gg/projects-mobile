package gg.projects.mobile.social;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;


import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The chat history on this phone (CHAT1: "the history belongs to the devices"): one SQLite file per
 * account in the app's no-backup folder (Android's backup never copies messages to another phone or
 * to the cloud), the newest {@link ChatBook#KEEP_PER_CONVERSATION} messages of each conversation, the
 * player's waiting messages, what they read and the conversations they muted here. Every write goes
 * to one background thread in order; the main thread never waits for the file.
 *
 * <p>FIX2 (version 2): a message deleted for everyone stays as its tombstone, the same row with its
 * text and seal erased ({@code deleted_at} &gt; 0, {@code deleted_by}). SQLite's secure delete
 * overwrites what the update frees, and the write-ahead log is emptied after it, so the text does not
 * linger in the file either.
 *
 * <p>CHAT2 (version 3): a message's sticker, its reply link with the link's seal and its reactions
 * (the same JSON the service sends); a waiting message's sticker and the answered message it carries;
 * the friend's read mark of a direct conversation; whether the week-back sync was done. A tombstone
 * erases the sticker, the link and the reactions with the text.
 */
public final class ChatStore implements ChatBook.Store {
    private static final String TAG = "ProjectsSocial";
    private static final int VERSION = 3;
    /** In memory after a start, per conversation. */
    private static final int LOAD = 200;
    private static final String COLUMNS = "conv, id, sender, text, mac, edited_at, nonce, deleted_by, deleted_at, "
            + "sticker, reply_id, reply_from, rmac, reactions";

    /** CHAT2: cards kept at most (the newest by message id; a card is at most ~90 KB with its picture). */
    static final int CARDS = 300;

    private final Helper mHelper;
    private final ExecutorService mWriter = Executors.newSingleThreadExecutor();
    private int mPutsSinceTrim;

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context, File file) {
            super(context, file.getPath(), null, VERSION);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            db.enableWriteAheadLogging();
            // FIX2: what a tombstone erases is overwritten in the file, not only unlinked.
            pragma(db, "PRAGMA secure_delete = ON");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE messages (conv TEXT NOT NULL, id INTEGER NOT NULL, sender TEXT NOT NULL, "
                    + "text TEXT NOT NULL, mac TEXT NOT NULL, edited_at INTEGER NOT NULL DEFAULT 0, nonce TEXT, "
                    + "deleted_by TEXT, deleted_at INTEGER NOT NULL DEFAULT 0, "
                    + "sticker TEXT, reply_id INTEGER NOT NULL DEFAULT 0, reply_from TEXT, rmac TEXT, reactions TEXT, "
                    + "PRIMARY KEY (conv, id)) WITHOUT ROWID");
            db.execSQL("CREATE TABLE outbox (nonce TEXT PRIMARY KEY, conv TEXT NOT NULL, text TEXT NOT NULL, "
                    + "created_at INTEGER NOT NULL, first_sent_at INTEGER NOT NULL, attempts INTEGER NOT NULL, "
                    + "state INTEGER NOT NULL, failure TEXT, sticker TEXT, reply TEXT, rule TEXT)");
            db.execSQL("CREATE TABLE convs (conv TEXT PRIMARY KEY, last_read INTEGER NOT NULL, "
                    + "cleared_up_to INTEGER NOT NULL, muted_until INTEGER NOT NULL, peer_read INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
            createCards(db);
        }

        /** CHAT2: the link cards kept with their messages (the hub's answer as JSON; at most {@link #CARDS} rows). */
        static void createCards(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE cards (conv TEXT NOT NULL, id INTEGER NOT NULL, card TEXT NOT NULL, "
                    + "PRIMARY KEY (conv, id)) WITHOUT ROWID");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // FIX2: tombstones. Messages deleted before this version are already gone from the file.
                db.execSQL("ALTER TABLE messages ADD COLUMN deleted_by TEXT");
                db.execSQL("ALTER TABLE messages ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0");
            }
            if (oldVersion < 3) {
                // CHAT2: stickers, reply links, reactions, the friend's read mark. Older rows have none of them.
                db.execSQL("ALTER TABLE messages ADD COLUMN sticker TEXT");
                db.execSQL("ALTER TABLE messages ADD COLUMN reply_id INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE messages ADD COLUMN reply_from TEXT");
                db.execSQL("ALTER TABLE messages ADD COLUMN rmac TEXT");
                db.execSQL("ALTER TABLE messages ADD COLUMN reactions TEXT");
                db.execSQL("ALTER TABLE outbox ADD COLUMN sticker TEXT");
                db.execSQL("ALTER TABLE outbox ADD COLUMN reply TEXT");
                db.execSQL("ALTER TABLE outbox ADD COLUMN rule TEXT");
                db.execSQL("ALTER TABLE convs ADD COLUMN peer_read INTEGER NOT NULL DEFAULT 0");
                createCards(db);
            }
        }
    }

    /** A pragma that answers with a row (the framework's execSQL refuses those on some versions). */
    private static void pragma(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            c.moveToFirst();
        } catch (RuntimeException e) {
            Log.w(TAG, "chat file setting not applied: " + sql, e);
        }
    }

    /** The file of one account's chat. */
    public static File file(Context context, String userId) {
        File dir = new File(context.getNoBackupFilesDir(), "chat");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "chat folder not made");
        return new File(dir, "chat-" + userId + ".db");
    }

    public ChatStore(Context context, String userId) {
        mHelper = new Helper(context.getApplicationContext(), file(context, userId));
    }

    /** "Verilerimi sil": the account's chat leaves this phone. */
    public static void delete(Context context, String userId) {
        File db = file(context, userId);
        SQLiteDatabase.deleteDatabase(db);
    }

    /** A page of a conversation's older lines: messages and the tombstones among them, and whether more are there. */
    public static final class Page {
        public final List<ChatMessage> messages;
        public final List<ChatBook.Tombstone> tombstones;
        public final boolean more;

        Page(List<ChatMessage> messages, List<ChatBook.Tombstone> tombstones, boolean more) {
            this.messages = messages;
            this.tombstones = tombstones;
            this.more = more;
        }
    }

    /** One row as the book takes it: a message, or a tombstone. */
    private static final class Row {
        final ChatMessage message;
        final ChatBook.Tombstone tombstone;

        Row(ChatMessage message, ChatBook.Tombstone tombstone) {
            this.message = message;
            this.tombstone = tombstone;
        }
    }

    /** What the file holds: the newest lines of each conversation, the outbox, the settings. */
    @WorkerThread
    public ChatBook.Snapshot load() {
        Map<String, List<ChatMessage>> messages = new HashMap<>();
        Map<String, List<ChatBook.Tombstone>> tombstones = new HashMap<>();
        Map<String, Boolean> hasOlder = new HashMap<>();
        List<ChatBook.Outbox> outbox = new ArrayList<>();
        Map<String, ChatBook.Meta> meta = new HashMap<>();
        long cursor = 0;
        boolean caughtUp = false;
        try {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            List<String> convs = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT DISTINCT conv FROM messages", null)) {
                while (c.moveToNext()) convs.add(c.getString(0));
            }
            for (String conv : convs) {
                List<Row> list = newest(db, conv, LOAD + 1);
                boolean more = list.size() > LOAD;
                if (more) list = list.subList(list.size() - LOAD, list.size());
                List<ChatMessage> kept = new ArrayList<>();
                List<ChatBook.Tombstone> buried = new ArrayList<>();
                for (Row row : list) {
                    if (row.tombstone != null) buried.add(row.tombstone);
                    else kept.add(row.message);
                }
                messages.put(conv, kept);
                if (!buried.isEmpty()) tombstones.put(conv, buried);
                hasOlder.put(conv, more);
            }
            try (Cursor c = db.rawQuery("SELECT nonce, conv, text, created_at, first_sent_at, attempts, state, failure, "
                    + "sticker, reply, rule FROM outbox", null)) {
                while (c.moveToNext()) {
                    ChatBook.Outbox.State state = c.getInt(6) == 2 ? ChatBook.Outbox.State.FAILED : ChatBook.Outbox.State.WAITING;
                    ChatBook.Failure failure = null;
                    if (!c.isNull(7)) {
                        try {
                            failure = ChatBook.Failure.valueOf(c.getString(7));
                        } catch (IllegalArgumentException e) {
                            failure = ChatBook.Failure.FAILED;
                        }
                    }
                    String sticker = c.isNull(8) ? null : c.getString(8);
                    outbox.add(new ChatBook.Outbox(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4),
                            c.getInt(5), state, failure, 0, ChatMessage.isSticker(sticker) ? sticker : null,
                            ChatBook.replyFrom(c.isNull(9) ? null : c.getString(9)), c.isNull(10) ? null : c.getString(10)));
                }
            }
            try (Cursor c = db.rawQuery("SELECT conv, last_read, cleared_up_to, muted_until, peer_read FROM convs", null)) {
                while (c.moveToNext()) {
                    meta.put(c.getString(0), new ChatBook.Meta(c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4)));
                }
            }
            try (Cursor c = db.rawQuery("SELECT value FROM state WHERE key = 'sync_cursor'", null)) {
                if (c.moveToNext()) cursor = c.getLong(0);
            }
            try (Cursor c = db.rawQuery("SELECT value FROM state WHERE key = 'caught_up'", null)) {
                if (c.moveToNext()) caughtUp = c.getLong(0) != 0;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "chat history not read", e);
            return ChatBook.Snapshot.empty();
        }
        return new ChatBook.Snapshot(messages, hasOlder, outbox, meta, cursor, tombstones, caughtUp);
    }

    /** CHAT2: one line of the file: a message, its tombstone, or neither (not there). */
    public static final class Line {
        @androidx.annotation.Nullable
        public final ChatMessage message;
        @androidx.annotation.Nullable
        public final ChatBook.Tombstone tombstone;

        Line(@androidx.annotation.Nullable ChatMessage message, @androidx.annotation.Nullable ChatBook.Tombstone tombstone) {
            this.message = message;
            this.tombstone = tombstone;
        }
    }

    /** CHAT2: one line by id (a reply's answered message, older than what memory keeps). */
    @WorkerThread
    public Line line(String conv, long id) {
        try {
            SQLiteDatabase db = mHelper.getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT " + COLUMNS + " FROM messages WHERE conv = ? AND id = ?",
                    new String[]{conv, String.valueOf(id)})) {
                if (c.moveToNext()) {
                    Row row = read(c);
                    return new Line(row.message, row.tombstone);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "answered message not read", e);
        }
        return new Line(null, null);
    }

    /** Lines before {@code beforeId}, oldest first (scrolling up), with whether more are there. */
    @WorkerThread
    public Page older(String conv, long beforeId, int limit) {
        List<Row> out = new ArrayList<>();
        try {
            SQLiteDatabase db = mHelper.getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT " + COLUMNS + " FROM messages "
                    + "WHERE conv = ? AND id < ? ORDER BY id DESC LIMIT ?", new String[]{conv, String.valueOf(beforeId), String.valueOf(limit + 1)})) {
                while (c.moveToNext()) out.add(read(c));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "older messages not read", e);
        }
        boolean more = out.size() > limit;
        if (more) out = out.subList(0, limit);
        List<Row> rows = new ArrayList<>(out);
        Collections.reverse(rows);
        List<ChatMessage> messages = new ArrayList<>();
        List<ChatBook.Tombstone> tombstones = new ArrayList<>();
        for (Row row : rows) {
            if (row.tombstone != null) tombstones.add(row.tombstone);
            else messages.add(row.message);
        }
        return new Page(messages, tombstones, more);
    }

    private static List<Row> newest(SQLiteDatabase db, String conv, int limit) {
        List<Row> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT " + COLUMNS + " FROM messages "
                + "WHERE conv = ? ORDER BY id DESC LIMIT ?", new String[]{conv, String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(read(c));
        }
        Collections.reverse(out);
        return out;
    }

    private static Row read(Cursor c) {
        String nonce = c.isNull(6) ? null : c.getString(6);
        long deletedAt = c.getLong(8);
        if (deletedAt > 0) {
            return new Row(null, new ChatBook.Tombstone(c.getString(0), c.getLong(1), c.getString(2), nonce,
                    c.isNull(7) ? "" : c.getString(7), deletedAt == 1 ? 0 : deletedAt));
        }
        String sticker = c.isNull(9) ? null : c.getString(9);
        return new Row(new ChatMessage(c.getString(0), c.getLong(1), c.getString(2), c.getString(3),
                ChatMessage.isSticker(sticker) ? sticker : null, c.getString(4), c.getLong(5), nonce, c.getLong(10),
                c.isNull(11) ? null : c.getString(11), c.isNull(12) ? null : c.getString(12),
                ChatMessage.reactionsFrom(c.isNull(13) ? null : c.getString(13))), null);
    }

    private void write(final String what, final Runnable step) {
        try {
            mWriter.execute(() -> {
                try {
                    step.run();
                } catch (RuntimeException e) {
                    Log.w(TAG, "chat history not written: " + what, e);
                }
            });
        } catch (RejectedExecutionException e) {
            // Closed (signed out): nothing more is written.
        }
    }

    @Override
    public void putMessage(final ChatMessage m) {
        write("message", () -> {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("sender", m.from);
            v.put("text", m.text);
            v.put("mac", m.mac);
            v.put("edited_at", m.editedAt);
            if (m.nonce != null) v.put("nonce", m.nonce);
            else v.putNull("nonce");
            if (m.sticker != null) v.put("sticker", m.sticker);
            else v.putNull("sticker");
            v.put("reply_id", m.replyId);
            if (m.replyId > 0) {
                v.put("reply_from", m.replyFrom);
                v.put("rmac", m.rmac);
            } else {
                v.putNull("reply_from");
                v.putNull("rmac");
            }
            if (m.reactions.isEmpty()) v.putNull("reactions");
            else v.put("reactions", ChatMessage.reactionsJson(m.reactions).toString());
            // A newer version replaces the row; a tombstone stays one (a late sync never brings a deleted text back).
            int updated = db.update("messages", v, "conv = ? AND id = ? AND deleted_at = 0",
                    new String[]{m.conv, String.valueOf(m.id)});
            if (updated == 0) {
                v.put("conv", m.conv);
                v.put("id", m.id);
                db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
            if (++mPutsSinceTrim >= 100) {
                mPutsSinceTrim = 0;
                trim(db);
            }
        });
    }

    /** Keeps the newest lines of each conversation. */
    private static void trim(SQLiteDatabase db) {
        List<String> convs = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT conv FROM messages GROUP BY conv HAVING COUNT(*) > ?",
                new String[]{String.valueOf(ChatBook.KEEP_PER_CONVERSATION)})) {
            while (c.moveToNext()) convs.add(c.getString(0));
        }
        for (String conv : convs) {
            db.execSQL("DELETE FROM messages WHERE conv = ? AND id < (SELECT id FROM messages WHERE conv = ? "
                    + "ORDER BY id DESC LIMIT 1 OFFSET ?)", new Object[]{conv, conv, ChatBook.KEEP_PER_CONVERSATION - 1});
        }
    }

    @Override
    public void removeMessage(final String conv, final long id) {
        write("delete", () -> mHelper.getWritableDatabase().delete("messages", "conv = ? AND id = ?",
                new String[]{conv, String.valueOf(id)}));
    }

    @Override
    public void putTombstone(final String conv, final long id, final String by, final long at) {
        write("tombstone", () -> {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("text", "");
            v.put("mac", "");
            v.put("edited_at", 0);
            v.putNull("sticker");
            v.put("reply_id", 0);
            v.putNull("reply_from");
            v.putNull("rmac");
            v.putNull("reactions");
            v.put("deleted_by", by);
            // 1 marks a tombstone whose time is not known (0 is "not deleted").
            v.put("deleted_at", at > 0 ? at : 1);
            int buried = db.update("messages", v, "conv = ? AND id = ? AND deleted_at = 0", new String[]{conv, String.valueOf(id)});
            db.delete("cards", "conv = ? AND id = ?", new String[]{conv, String.valueOf(id)});
            // The old text leaves the write-ahead log too (secure delete covers the file itself).
            if (buried > 0) pragma(db, "PRAGMA wal_checkpoint(TRUNCATE)");
        });
    }

    @Override
    public void putOutbox(final ChatBook.Outbox o) {
        write("outbox", () -> {
            ContentValues v = new ContentValues();
            v.put("nonce", o.nonce);
            v.put("conv", o.conv);
            v.put("text", o.text);
            v.put("created_at", o.createdAt);
            v.put("first_sent_at", o.firstSentAt);
            v.put("attempts", o.attempts);
            v.put("state", o.state == ChatBook.Outbox.State.FAILED ? 2 : o.state == ChatBook.Outbox.State.SENDING ? 1 : 0);
            if (o.failure != null) v.put("failure", o.failure.name());
            else v.putNull("failure");
            if (o.sticker != null) v.put("sticker", o.sticker);
            else v.putNull("sticker");
            String reply = ChatBook.replyJson(o);
            if (reply != null) v.put("reply", reply);
            else v.putNull("reply");
            if (o.rule != null) v.put("rule", o.rule);
            else v.putNull("rule");
            mHelper.getWritableDatabase().insertWithOnConflict("outbox", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    @Override
    public void removeOutbox(final String nonce) {
        write("outbox delete", () -> mHelper.getWritableDatabase().delete("outbox", "nonce = ?", new String[]{nonce}));
    }

    @Override
    public void putMeta(final String conv, final ChatBook.Meta meta) {
        write("meta", () -> {
            ContentValues v = new ContentValues();
            v.put("conv", conv);
            v.put("last_read", meta.lastRead);
            v.put("cleared_up_to", meta.clearedUpTo);
            v.put("muted_until", meta.mutedUntil);
            v.put("peer_read", meta.peerRead);
            mHelper.getWritableDatabase().insertWithOnConflict("convs", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    @Override
    public void putCaughtUp() {
        write("caught up", () -> {
            ContentValues v = new ContentValues();
            v.put("key", "caught_up");
            v.put("value", 1);
            mHelper.getWritableDatabase().insertWithOnConflict("state", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    @Override
    public void clear(final String conv) {
        write("clear", () -> {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            db.delete("messages", "conv = ?", new String[]{conv});
            db.delete("outbox", "conv = ?", new String[]{conv});
            db.delete("cards", "conv = ?", new String[]{conv});
        });
    }

    @Override
    public void putSyncCursor(final long newestSeen) {
        write("cursor", () -> {
            ContentValues v = new ContentValues();
            v.put("key", "sync_cursor");
            v.put("value", newestSeen);
            mHelper.getWritableDatabase().insertWithOnConflict("state", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    /** CHAT2: a message's link card (the hub's answer, or the plain or refused one), kept with it. */
    public void putCard(final String conv, final long id, final String json) {
        write("card", () -> {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("conv", conv);
            v.put("id", id);
            v.put("card", json);
            db.insertWithOnConflict("cards", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM cards WHERE id < (SELECT id FROM cards ORDER BY id DESC LIMIT 1 OFFSET ?)",
                    new Object[]{CARDS - 1});
        });
    }

    /** CHAT2: the cards kept for a conversation's messages, by message id (read when it opens). */
    @WorkerThread
    public Map<Long, String> cards(String conv) {
        Map<Long, String> out = new HashMap<>();
        try {
            SQLiteDatabase db = mHelper.getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT id, card FROM cards WHERE conv = ?", new String[]{conv})) {
                while (c.moveToNext()) out.put(c.getLong(0), c.getString(1));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "link cards not read", e);
        }
        return out;
    }

    /** Signed out: the file is closed after the writes already asked for. */
    public void close() {
        close(null);
    }

    /** Closes the file after the writes already asked for, then runs {@code then} on the writer ("Verilerimi sil"). */
    public void close(@Nullable Runnable then) {
        write("close", mHelper::close);
        if (then != null) write("after close", then);
        mWriter.shutdown();
    }

    /** For a reader that must run after the writes already asked for (the start's load, scrolling up). */
    public void afterWrites(Runnable task) {
        try {
            mWriter.execute(task);
        } catch (RejectedExecutionException e) {
            // Closed (signed out): the reader's answer would go to an account that is gone.
        }
    }
}
