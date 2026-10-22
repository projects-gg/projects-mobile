package gg.projects.mobile.social;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.language.LanguageManager;

/**
 * Crew chat and direct messages on the phone (M20, CHAT1; README "Chat"): the {@link ChatBook} and its
 * file ({@link ChatStore}) for the signed-in account, the sync after every connect, the player's
 * messages going out one at a time under their nonce, edits and deletes, and what a direct message
 * becomes while the app is not on screen (the same notification as its push). Everything runs on the
 * main thread; the file is written on its own thread.
 */
public final class SocialChat {
    private static final String TAG = "ProjectsSocial";
    private static final String NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    /** A lost answer is tried again this long after (the same nonce: never a second copy). */
    private static final long RETRY_LOST_MS = 1_500;
    private static final int OLDER_PAGE = 200;

    /** What the chat needs of the friends service. */
    interface Host {
        Context context();

        SocialState state();

        SocialGate gate();

        SocialPrefs prefs();

        /** The live socket that took {@code chat} (null otherwise). */
        @Nullable
        PresenceConnection chatConnection();

        boolean foreground();

        /** The conversation on screen now (null: none). */
        @Nullable
        String openConversation();

        void changed();
    }

    public interface Done {
        void ok();

        void failed(SocialProblem problem);
    }

    /** Why a message was not even written down (the composer says it in place). */
    public enum Refusal { NONE, EMPTY, TOO_LONG, LINK, PHONE, MUTED, OFF, NOT_FRIENDS, NOT_IN_CREW, UNAVAILABLE }

    private final Host mHost;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final SecureRandom mRandom = new SecureRandom();
    private final Runnable mFlush = this::flush;

    @Nullable
    private ChatBook mBook;
    @Nullable
    private ChatStore mStore;
    @Nullable
    private String mAccount;
    /** The connection the last sync ran on, and whether it finished (sending waits for it). */
    @Nullable
    private PresenceConnection mSyncedOn;
    private boolean mSynced;
    private boolean mSending;
    /** Older pages being read for these conversations. */
    private final java.util.Set<String> mLoadingOlder = new java.util.HashSet<>();

    SocialChat(Host host) {
        mHost = host;
    }

    // ---- the account ----

    /** Signed in (or the app started signed in): the account's history is read from its file. */
    @MainThread
    void open(final String userId) {
        if (userId.equals(mAccount)) return;
        close();
        mAccount = userId;
        final ChatStore store = new ChatStore(mHost.context(), userId);
        mStore = store;
        store.afterWrites(() -> {
            final ChatBook.Snapshot snapshot = store.load();
            mMain.post(() -> {
                if (store != mStore || !userId.equals(mAccount)) return;
                mBook = new ChatBook(userId, store, snapshot);
                // The phone's push handler reads the mutes without the file (the app may not be running then).
                long now = SocialService.nowSeconds();
                for (ChatBook.Summary s : mBook.summaries()) {
                    long until = mBook.mutedUntil(s.conv);
                    if (until != 0) mHost.prefs().setConversationMuted(s.conv, until, now);
                }
                mHost.changed();
                // A socket that came first syncs now.
                connected();
            });
        });
    }

    /** Signed out: the history stays in its file (it comes back with the same account), nothing in memory. */
    @MainThread
    void close() {
        if (mStore != null) mStore.close();
        mStore = null;
        mBook = null;
        mAccount = null;
        mSyncedOn = null;
        mSynced = false;
        mSending = false;
        mMain.removeCallbacks(mFlush);
    }

    /** "Verilerimi sil": the account's history leaves this phone too. */
    @MainThread
    void deleteLocal(final String userId) {
        final ChatStore store = mStore;
        close();
        final Context context = mHost.context();
        Runnable delete = () -> ChatStore.delete(context, userId);
        if (store != null) store.afterWrites(delete);
        else new Thread(delete, "chat-delete").start();
    }

    @Nullable
    public ChatBook book() {
        return mBook;
    }

    /** Chat shows: the service and Studio offer it, and the history is read. */
    public boolean available() {
        return mBook != null && mHost.gate().chat;
    }

    // ---- the socket ----

    /** A socket said hello: with {@code chat} taken, the device asks for what it missed, then sends what waits. */
    @MainThread
    void connected() {
        PresenceConnection connection = mHost.chatConnection();
        if (mBook == null || connection == null || connection == mSyncedOn) return;
        mSyncedOn = connection;
        mSynced = false;
        sync(connection, mBook.syncSince(), null);
    }

    private void sync(final PresenceConnection connection, final long since, @Nullable String after) {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "chat_sync");
        frame.addProperty("since", since);
        if (after != null) frame.addProperty("after", after);
        connection.request(frame, answer -> {
            if (connection != mSyncedOn || mBook == null) return;
            String more = null;
            if (answer != null && "chat_synced".equals(Json.str(answer, "t"))) {
                more = mBook.synced(answer, System.currentTimeMillis());
            } else if (answer != null) {
                Log.i(TAG, "chat sync refused: " + Json.str(answer, "code"));
            }
            if (more != null) {
                sync(connection, since, more);
                return;
            }
            mSynced = true;
            mBook.expire(System.currentTimeMillis());
            mHost.changed();
            flush();
        });
    }

    /** The socket went: what was on its way waits for the next one. */
    @MainThread
    void disconnected() {
        mSyncedOn = null;
        mSynced = false;
        mSending = false;
        mMain.removeCallbacks(mFlush);
        if (mBook != null) mBook.connectionLost();
    }

    /** A chat frame from the socket; true when it was one. */
    @MainThread
    boolean onFrame(JsonObject frame) {
        String type = Json.str(frame, "t", "");
        if (!type.equals("chat") && !type.equals("chat_edited") && !type.equals("chat_deleted") && !type.equals("chat_sync")) {
            return false;
        }
        if (mBook == null) return true;
        List<ChatBook.Event> events = mBook.apply(frame);
        String open = mHost.openConversation();
        for (ChatBook.Event e : events) {
            if (open != null && open.equals(e.conv) && mHost.foreground()) {
                mBook.markRead(e.conv);
                continue;
            }
            if (e.kind == ChatBook.Event.Kind.NEW && e.message != null) announce(e.message);
        }
        return true;
    }

    /**
     * A direct message while the app is not on screen (its socket stays a few seconds after it goes):
     * the notification its push would have made. On screen the badges say it; crew chat never notifies.
     */
    private void announce(ChatMessage message) {
        if (!ChatConv.isDirect(message.conv) || mHost.foreground()) return;
        SocialModel.Me me = mHost.state().me();
        if (me == null || SocialModel.Status.DND.equals(me.status) || !mHost.prefs().wantsMessages()) return;
        if (!mHost.state().isFriend(message.from)) return;
        SocialModel.Profile from = mHost.state().profile(message.from);
        boolean silent = mBook != null && mBook.mutedAt(message.conv, SocialService.nowSeconds());
        SocialNotifications.showMessage(LanguageManager.wrap(mHost.context()), message.conv, from == null ? null : from.name(), silent);
    }

    // ---- sending ----

    /** The new nonce of a message: 12 random characters of the README's alphabet. */
    String nonce() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i++) out.append(NONCE_ALPHABET.charAt(mRandom.nextInt(NONCE_ALPHABET.length())));
        return out.toString();
    }

    /** Whether the player may write in {@code conv} now, and why not. */
    public Refusal canWrite(String conv) {
        if (!available()) return Refusal.UNAVAILABLE;
        SocialState state = mHost.state();
        if (!state.chat().on) return Refusal.OFF;
        if (state.chat().mutedAt(state.hubNow(SocialService.nowSeconds()))) return Refusal.MUTED;
        String crew = ChatConv.crewId(conv);
        if (crew != null) return state.crew(crew) == null ? Refusal.NOT_IN_CREW : Refusal.NONE;
        String other = ChatConv.other(conv, state.myId());
        if (other == null || !state.isFriend(other)) return Refusal.NOT_FRIENDS;
        return Refusal.NONE;
    }

    /** "Gönder": checked, shown at once as waiting, sent when the socket is there. */
    @MainThread
    public Refusal send(String conv, String typed) {
        Refusal can = canWrite(conv);
        if (can != Refusal.NONE) return can;
        switch (ChatText.check(typed)) {
            case EMPTY: return Refusal.EMPTY;
            case TOO_LONG: return Refusal.TOO_LONG;
            case LINK: return Refusal.LINK;
            case PHONE: return Refusal.PHONE;
            default: break;
        }
        mBook.write(conv, ChatText.clean(typed), nonce(), System.currentTimeMillis());
        mBook.markRead(conv);
        mHost.changed();
        flush();
        return Refusal.NONE;
    }

    /** "Tekrar dene". */
    @MainThread
    public void retry(String nonce) {
        if (mBook == null) return;
        mBook.retry(nonce, nonce(), System.currentTimeMillis());
        mHost.changed();
        flush();
    }

    /** "Sil" on a message that did not go. */
    @MainThread
    public void discard(String nonce) {
        if (mBook == null) return;
        mBook.discard(nonce);
        mHost.changed();
    }

    /** Sends the next waiting message (one at a time, in order), once the sync is done. */
    @MainThread
    void flush() {
        mMain.removeCallbacks(mFlush);
        final PresenceConnection connection = mHost.chatConnection();
        if (mBook == null || connection == null || !mSynced || connection != mSyncedOn || mSending) return;
        long now = System.currentTimeMillis();
        List<ChatBook.Outbox> due = mBook.due(now);
        if (due.isEmpty()) {
            long next = mBook.nextDue(now);
            if (next > 0 && next != Long.MAX_VALUE) mMain.postDelayed(mFlush, Math.max(200, next - now));
            return;
        }
        final ChatBook.Outbox o = due.get(0);
        mSending = true;
        mBook.sending(o.nonce, now);
        mHost.changed();
        connection.request(o.frame(), answer -> {
            mSending = false;
            if (mBook == null) return;
            long at = System.currentTimeMillis();
            if (answer == null) {
                mBook.notSent(o.nonce, "unavailable", null, 0, at);
                mHost.changed();
                if (connection == mHost.chatConnection()) mMain.postDelayed(mFlush, RETRY_LOST_MS);
                return;
            }
            if ("ack".equals(Json.str(answer, "t"))) {
                mBook.sent(o.nonce, Json.obj(answer, "msg"));
            } else {
                String code = Json.str(answer, "code", "bad_request");
                mBook.notSent(o.nonce, code, Json.str(answer, "reason"), Json.num(answer, "retryAfter", 0), at);
                Log.i(TAG, "chat message refused: " + code);
            }
            mHost.changed();
            flush();
        });
    }

    // ---- edits and deletes ----

    /** "Düzenle": the new text shows at once; a refusal brings the old one back and says why. */
    @MainThread
    public void edit(final String conv, final long id, String typed, final Done done) {
        PresenceConnection connection = mHost.chatConnection();
        if (mBook == null || connection == null) {
            done.failed(SocialProblem.unavailable("no socket"));
            return;
        }
        ChatText.Problem problem = ChatText.check(typed);
        if (problem != ChatText.Problem.NONE) {
            done.failed(new SocialProblem(SocialProblem.Kind.CHAT_REJECTED, 0, "local", 0, null, null,
                    problem == ChatText.Problem.LINK ? "link" : problem == ChatText.Problem.PHONE ? "phone" : "invalid"));
            return;
        }
        JsonObject frame = mBook.edit(conv, id, ChatText.clean(typed));
        if (frame == null) {
            done.failed(new SocialProblem(SocialProblem.Kind.MESSAGE_GONE, 0, "message_not_found", 0));
            return;
        }
        mHost.changed();
        connection.request(frame, answer -> {
            if (mBook == null) return;
            if (answer != null && "ack".equals(Json.str(answer, "t"))) {
                mBook.edited(conv, id, Json.obj(answer, "msg"));
                mHost.changed();
                done.ok();
                return;
            }
            SocialProblem p = answer == null ? SocialProblem.unavailable("no answer") : SocialProblem.ofSocket(answer);
            mBook.editFailed(conv, id, p.code == null ? "unavailable" : p.code);
            mHost.changed();
            done.failed(p);
        });
    }

    /** "Herkesten sil": the message shows as going until the service says so. */
    @MainThread
    public void delete(final String conv, final long id, final Done done) {
        PresenceConnection connection = mHost.chatConnection();
        if (mBook == null || connection == null) {
            done.failed(SocialProblem.unavailable("no socket"));
            return;
        }
        JsonObject frame = mBook.delete(conv, id);
        if (frame == null) {
            done.failed(new SocialProblem(SocialProblem.Kind.MESSAGE_GONE, 0, "message_not_found", 0));
            return;
        }
        mHost.changed();
        connection.request(frame, answer -> {
            if (mBook == null) return;
            if (answer != null && "ack".equals(Json.str(answer, "t"))) {
                // FIX2: the player's own tombstone ("Bu mesajı sildin"), at the hub's time now.
                mBook.deleted(conv, id, mBook.hubNowMs(System.currentTimeMillis()));
                mHost.changed();
                done.ok();
                return;
            }
            SocialProblem p = answer == null ? SocialProblem.unavailable("no answer") : SocialProblem.ofSocket(answer);
            mBook.deleteFailed(conv, id, p.code == null ? "unavailable" : p.code);
            mHost.changed();
            done.failed(p);
        });
    }

    // ---- the player's own settings ----

    /** The conversation is on screen: read, and its notification goes. */
    @MainThread
    public void seen(String conv) {
        if (mBook == null) return;
        if (mBook.markRead(conv)) mHost.changed();
        SocialNotifications.cancelMessage(mHost.context(), conv);
    }

    @MainThread
    public void setMuted(String conv, long untilSeconds) {
        if (mBook == null) return;
        mBook.setMuted(conv, untilSeconds);
        mHost.prefs().setConversationMuted(conv, untilSeconds, SocialService.nowSeconds());
        mHost.changed();
    }

    @MainThread
    public void clearHistory(String conv) {
        if (mBook == null) return;
        mBook.clearHistory(conv);
        mHost.changed();
    }

    /** Scrolling up: the next older page from the file. */
    @MainThread
    public void loadOlder(final String conv) {
        final ChatBook book = mBook;
        final ChatStore store = mStore;
        if (book == null || store == null || !book.hasOlder(conv) || !mLoadingOlder.add(conv)) return;
        final long before = book.oldestId(conv);
        store.afterWrites(() -> {
            final ChatStore.Page page = store.older(conv, before, OLDER_PAGE);
            mMain.post(() -> {
                mLoadingOlder.remove(conv);
                if (book != mBook) return;
                book.addOlder(conv, page.messages, page.tombstones, page.more);
                mHost.changed();
            });
        });
    }

    /** The home row's unread direct message: the newest such conversation, with how many there are. */
    @Nullable
    public HomeLine.Unread unreadDirect() {
        if (!available()) return null;
        String me = mHost.state().myId();
        ChatBook.Summary newest = null;
        int conversations = 0;
        for (ChatBook.Summary s : mBook.summaries()) {
            if (!ChatConv.isDirect(s.conv) || s.unread == 0) continue;
            String other = ChatConv.other(s.conv, me);
            if (other == null || !mHost.state().isFriend(other)) continue;
            conversations++;
            if (newest == null) newest = s;
        }
        if (newest == null) return null;
        return new HomeLine.Unread(newest.conv, ChatConv.other(newest.conv, me), newest.unread, conversations);
    }

    /** Unread messages of the conversations the player can still open (friends' and their crews'). */
    public int unreadTotal() {
        if (!available()) return 0;
        int n = 0;
        for (ChatBook.Summary s : visibleSummaries()) n += s.unread;
        return n;
    }

    /** The conversations of the player's crews and friends, the newest first. */
    public List<ChatBook.Summary> visibleSummaries() {
        List<ChatBook.Summary> out = new ArrayList<>();
        if (mBook == null) return out;
        SocialState state = mHost.state();
        for (ChatBook.Summary s : mBook.summaries()) {
            String crew = ChatConv.crewId(s.conv);
            if (crew != null) {
                if (state.crew(crew) != null) out.add(s);
                continue;
            }
            String other = ChatConv.other(s.conv, state.myId());
            if (other != null && state.isFriend(other)) out.add(s);
        }
        return out;
    }
}
