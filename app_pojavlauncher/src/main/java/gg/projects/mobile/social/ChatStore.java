package gg.projects.mobile.social;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;


import androidx.annotation.WorkerThread;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The chat history on this phone (CHAT1: "the history belongs to the devices"): one SQLite file per
 * account in the app's no-backup folder (Android's backup never copies messages to another phone or
 * to the cloud), the newest {@link ChatBook#KEEP_PER_CONVERSATION} messages of each conversation, the
 * player's waiting messages, what they read and the conversations they muted here. Every write goes
 * to one background thread in order; the main thread never waits for the file.
 */
public final class ChatStore implements ChatBook.Store {
    private static final String TAG = "ProjectsSocial";
    private static final int VERSION = 1;
    /** In memory after a start, per conversation. */
    private static final int LOAD = 200;

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
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE messages (conv TEXT NOT NULL, id INTEGER NOT NULL, sender TEXT NOT NULL, "
                    + "text TEXT NOT NULL, mac TEXT NOT NULL, edited_at INTEGER NOT NULL DEFAULT 0, nonce TEXT, "
                    + "PRIMARY KEY (conv, id)) WITHOUT ROWID");
            db.execSQL("CREATE TABLE outbox (nonce TEXT PRIMARY KEY, conv TEXT NOT NULL, text TEXT NOT NULL, "
                    + "created_at INTEGER NOT NULL, first_sent_at INTEGER NOT NULL, attempts INTEGER NOT NULL, "
                    + "state INTEGER NOT NULL, failure TEXT)");
            db.execSQL("CREATE TABLE convs (conv TEXT PRIMARY KEY, last_read INTEGER NOT NULL, "
                    + "cleared_up_to INTEGER NOT NULL, muted_until INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Version 1 is the first.
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

    /** What the file holds: the newest messages of each conversation, the outbox, the settings. */
    @WorkerThread
    public ChatBook.Snapshot load() {
        Map<String, List<ChatMessage>> messages = new HashMap<>();
        Map<String, Boolean> hasOlder = new HashMap<>();
        List<ChatBook.Outbox> outbox = new ArrayList<>();
        Map<String, ChatBook.Meta> meta = new HashMap<>();
        long cursor = 0;
        try {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            List<String> convs = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT DISTINCT conv FROM messages", null)) {
                while (c.moveToNext()) convs.add(c.getString(0));
            }
            for (String conv : convs) {
                List<ChatMessage> list = newest(db, conv, LOAD + 1);
                boolean more = list.size() > LOAD;
                if (more) list = list.subList(list.size() - LOAD, list.size());
                messages.put(conv, new ArrayList<>(list));
                hasOlder.put(conv, more);
            }
            try (Cursor c = db.rawQuery("SELECT nonce, conv, text, created_at, first_sent_at, attempts, state, failure FROM outbox", null)) {
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
                    outbox.add(new ChatBook.Outbox(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4),
                            c.getInt(5), state, failure, 0));
                }
            }
            try (Cursor c = db.rawQuery("SELECT conv, last_read, cleared_up_to, muted_until FROM convs", null)) {
                while (c.moveToNext()) meta.put(c.getString(0), new ChatBook.Meta(c.getLong(1), c.getLong(2), c.getLong(3)));
            }
            try (Cursor c = db.rawQuery("SELECT value FROM state WHERE key = 'sync_cursor'", null)) {
                if (c.moveToNext()) cursor = c.getLong(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "chat history not read", e);
            return ChatBook.Snapshot.empty();
        }
        return new ChatBook.Snapshot(messages, hasOlder, outbox, meta, cursor);
    }

    /** Messages before {@code beforeId}, oldest first (scrolling up); the boolean says whether more are there. */
    @WorkerThread
    public List<ChatMessage> older(String conv, long beforeId, int limit, boolean[] more) {
        List<ChatMessage> out = new ArrayList<>();
        try {
            SQLiteDatabase db = mHelper.getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT conv, id, sender, text, mac, edited_at, nonce FROM messages "
                    + "WHERE conv = ? AND id < ? ORDER BY id DESC LIMIT ?", new String[]{conv, String.valueOf(beforeId), String.valueOf(limit + 1)})) {
                while (c.moveToNext()) out.add(read(c));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "older messages not read", e);
        }
        more[0] = out.size() > limit;
        if (more[0]) out = out.subList(0, limit);
        Collections.reverse(out);
        return new ArrayList<>(out);
    }

    private static List<ChatMessage> newest(SQLiteDatabase db, String conv, int limit) {
        List<ChatMessage> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT conv, id, sender, text, mac, edited_at, nonce FROM messages "
                + "WHERE conv = ? ORDER BY id DESC LIMIT ?", new String[]{conv, String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(read(c));
        }
        Collections.reverse(out);
        return out;
    }

    private static ChatMessage read(Cursor c) {
        return new ChatMessage(c.getString(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5),
                c.isNull(6) ? null : c.getString(6));
    }

    private void write(final String what, final Runnable step) {
        mWriter.execute(() -> {
            try {
                step.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "chat history not written: " + what, e);
            }
        });
    }

    @Override
    public void putMessage(final ChatMessage m) {
        write("message", () -> {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("conv", m.conv);
            v.put("id", m.id);
            v.put("sender", m.from);
            v.put("text", m.text);
            v.put("mac", m.mac);
            v.put("edited_at", m.editedAt);
            if (m.nonce != null) v.put("nonce", m.nonce);
            else v.putNull("nonce");
            db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            if (++mPutsSinceTrim >= 100) {
                mPutsSinceTrim = 0;
                trim(db);
            }
        });
    }

    /** Keeps the newest messages of each conversation. */
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
            mHelper.getWritableDatabase().insertWithOnConflict("convs", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    @Override
    public void clear(final String conv) {
        write("clear", () -> {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            db.delete("messages", "conv = ?", new String[]{conv});
            db.delete("outbox", "conv = ?", new String[]{conv});
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

    /** Signed out: the file is closed after the writes already asked for. */
    public void close() {
        write("close", mHelper::close);
        mWriter.shutdown();
    }

    /** For a reader that must run after the writes already asked for (the start's load, scrolling up). */
    public void afterWrites(Runnable task) {
        mWriter.execute(task);
    }
}
