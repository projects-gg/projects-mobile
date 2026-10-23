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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.language.LanguageManager;

/**
 * Crew chat and direct messages on the phone (M20, CHAT1; README "Chat"): the {@link ChatBook} and its
 * file ({@link ChatStore}) for the signed-in account, the sync after every connect, the player's
 * messages going out one at a time under their nonce, edits and deletes, and what a direct message
 * becomes while the app is not on screen (the same notification as its push). Everything runs on the
 * main thread; the file is written on its own thread.
 *
 * <p>MOB1 (CHAT2, README "Chat extras"): stickers and replies go like messages; reactions are shown at
 * once and sent one by one; "yazıyor…" goes out at most every 4 seconds while the player writes and
 * shows others' for 6 seconds ({@link ChatTyping}); a read mark goes when someone else's newest
 * message has been on screen for a second, at most every 2 seconds per conversation, only forward;
 * link cards are asked once per message as it comes on screen, one at a time ({@link ChatCards}); a
 * reply whose message memory does not hold is looked up in the file. Each extra only while the
 * service offers it ({@link SocialModel.ChatState#offers}); an older service gets exactly CHAT1.
 */
public final class SocialChat {
    private static final String TAG = "ProjectsSocial";
    private static final String NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    /** A lost answer is tried again this long after (the same nonce: never a second copy). */
    private static final long RETRY_LOST_MS = 1_500;
    private static final int OLDER_PAGE = 200;
    /** A read mark goes at most this often per conversation (README). */
    private static final long READ_EVERY_MS = 2_000;
    /** CHAT2's socket capabilities beside {@code chat}. */
    static final String[] EXTRA_CAPS = {"replies", "stickers", "reactions", "typing", "reads"};

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
    public enum Refusal { NONE, EMPTY, TOO_LONG, LINK, PHONE, WORDS, MUTED, OFF, NOT_FRIENDS, NOT_IN_CREW, UNAVAILABLE, LIMITED_LINKS }

    /** CHAT2: what a press on an emoji did ("Ben de", "Tepkimi kaldır") or why it did not. */
    public enum React { ADDED, REMOVED, FULL_MINE, FULL_KINDS, REFUSED, GONE, UNAVAILABLE }

    private final Host mHost;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final SecureRandom mRandom = new SecureRandom();
    private final Runnable mFlush = this::flush;
    private final Runnable mTypingTick = this::typingTick;
    private final Runnable mCardPump = this::pumpCards;
    private final ChatTyping mTyping = new ChatTyping();
    private final ChatCards mCards = new ChatCards();

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
    private final Set<String> mLoadingOlder = new HashSet<>();
    /** CHAT2: conversations whose cards were read from the file. */
    private final Set<String> mCardsLoaded = new HashSet<>();
    /** CHAT2: the read mark sent last per conversation, and when (ms). */
    private final Map<String, Long> mReadSent = new HashMap<>();
    private final Map<String, Long> mReadSentAt = new HashMap<>();
    private final Map<String, Runnable> mReadLater = new HashMap<>();
    /** CHAT2: answered messages being looked up in the file. */
    private final Set<String> mLookingUp = new HashSet<>();

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
        mMain.removeCallbacks(mTypingTick);
        mMain.removeCallbacks(mCardPump);
        for (Runnable later : mReadLater.values()) mMain.removeCallbacks(later);
        mReadLater.clear();
        mReadSent.clear();
        mReadSentAt.clear();
        mTyping.clear();
        mCards.clear();
        mCardsLoaded.clear();
        mLookingUp.clear();
    }

    /** "Verilerimi sil": the account's history leaves this phone too. */
    @MainThread
    void deleteLocal(final String userId) {
        final ChatStore store = mStore;
        // The store is closed here, with the deletion after its last writes (close() must not close it first).
        mStore = null;
        close();
        final Context context = mHost.context();
        Runnable delete = () -> ChatStore.delete(context, userId);
        if (store != null) store.close(delete);
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

    // ---- CHAT2: what the service offers now ----

    /** An extra may be offered now (Studio has it on and the socket took it; links need no capability). */
    public boolean offers(String extra) {
        SocialState state = mHost.state();
        return available() && state.chat().offers(extra, state.caps());
    }

    /** Studio's link lists while links are on (null: links off, or an older service: every link is refused). */
    @Nullable
    public ChatLinks.Lists linkLists() {
        if (!offers(SocialModel.ChatState.LINKS)) return null;
        ChatLinks.Lists lists = ChatLinks.Lists.read(mHost.state().chat().links);
        return lists != null ? lists : ChatLinks.Lists.defaults();
    }

    /** The sticker sets the service takes now (empty without the extra). */
    public List<String> stickerSets() {
        if (!offers(SocialModel.ChatState.STICKERS)) return Collections.emptyList();
        return mHost.state().chat().stickerSets;
    }

    /** The socket took at least one of CHAT2's capabilities. */
    private boolean extrasCapable() {
        Set<String> caps = mHost.state().caps();
        for (String cap : EXTRA_CAPS) if (caps.contains(cap)) return true;
        return false;
    }

    // ---- the socket ----

    /** A socket said hello: with {@code chat} taken, the device asks for what it missed, then sends what waits. */
    @MainThread
    void connected() {
        PresenceConnection connection = mHost.chatConnection();
        if (mBook == null || connection == null || connection == mSyncedOn) return;
        mSyncedOn = connection;
        mSynced = false;
        boolean extras = extrasCapable();
        sync(connection, mBook.syncSince(extras), null, extras);
    }

    private void sync(final PresenceConnection connection, final long since, @Nullable String after, final boolean extras) {
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "chat_sync");
        frame.addProperty("since", since);
        if (after != null) frame.addProperty("after", after);
        connection.request(frame, answer -> {
            if (connection != mSyncedOn || mBook == null) return;
            String more = null;
            boolean done = false;
            if (answer != null && "chat_synced".equals(Json.str(answer, "t"))) {
                more = mBook.synced(answer, System.currentTimeMillis());
                done = more == null;
            } else if (answer != null) {
                Log.i(TAG, "chat sync refused: " + Json.str(answer, "code"));
            }
            if (more != null) {
                sync(connection, since, more, extras);
                return;
            }
            // CHAT2: the week-back sync is done once it ran through with the extras.
            if (done && extras) mBook.caughtUp();
            mSynced = true;
            mBook.expire(System.currentTimeMillis());
            mHost.changed();
            flush();
            pumpCards();
        });
    }

    /** The socket went: what was on its way waits for the next one. */
    @MainThread
    void disconnected() {
        mSyncedOn = null;
        mSynced = false;
        mSending = false;
        mMain.removeCallbacks(mFlush);
        mMain.removeCallbacks(mTypingTick);
        mMain.removeCallbacks(mCardPump);
        mTyping.clear();
        mCards.connectionLost();
        if (mBook != null) mBook.connectionLost();
    }

    /** A chat frame from the socket; true when it was one (CHAT2's {@code presence} peek is not). */
    @MainThread
    boolean onFrame(JsonObject frame) {
        String type = Json.str(frame, "t", "");
        if (type.equals("presence")) {
            // A writer who went offline stops "yazıyor" at once (the state reads the frame too).
            SocialModel.Presence p = SocialModel.Presence.read(Json.obj(frame, "presence"));
            if (p != null && !p.online() && mTyping.offline(p.id)) mHost.changed();
            return false;
        }
        if (type.equals("chat_typing")) {
            String conv = Json.str(frame, "conv");
            String by = Json.str(frame, "by");
            String me = mHost.state().myId();
            if (ChatConv.valid(conv) && SocialModel.isUserId(by) && !by.equals(me)) {
                mTyping.saw(conv, by, System.currentTimeMillis());
                scheduleTypingTick();
                mHost.changed();
            }
            return true;
        }
        if (!type.equals("chat") && !type.equals("chat_edited") && !type.equals("chat_deleted") && !type.equals("chat_sync")
                && !type.equals("chat_reacted") && !type.equals("chat_read")) {
            return false;
        }
        if (mBook == null) return true;
        mBook.setReactionsTaken(offers(SocialModel.ChatState.REACTIONS));
        List<ChatBook.Event> events = mBook.apply(frame);
        String open = mHost.openConversation();
        for (ChatBook.Event e : events) {
            if (e.kind == ChatBook.Event.Kind.REACTED || e.kind == ChatBook.Event.Kind.READ) continue;
            // Their message came: they are done writing.
            if (e.message != null) mTyping.stop(e.conv, e.message.from);
            if (e.kind == ChatBook.Event.Kind.DELETED || e.kind == ChatBook.Event.Kind.SYNCED) continue;
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

    /** What the text checks say of {@code typed} now (links by the service's rules while they are on). */
    public ChatText.Verdict verdict(@Nullable String typed) {
        return ChatText.verdict(typed, linkLists());
    }

    /**
     * A new account sends no links yet (SPAM1's {@code chat_links}): the composer says so in place of
     * the rule; null when the player may.
     */
    @Nullable
    public SocialModel.Limited linksLimited() {
        SocialModel.Me me = mHost.state().me();
        if (me == null || me.limited == null || !me.limited.active(SocialService.nowSeconds())) return null;
        return me.limited.has("chat_links") ? me.limited : null;
    }

    /** "Gönder": checked, shown at once as waiting, sent when the socket is there. */
    @MainThread
    public Refusal send(String conv, String typed) {
        return send(conv, typed, null);
    }

    /** The same as a reply to {@code replyTo} (CHAT2; without the extra it goes as a plain message). */
    @MainThread
    public Refusal send(String conv, String typed, @Nullable ChatMessage replyTo) {
        Refusal can = canWrite(conv);
        if (can != Refusal.NONE) return can;
        ChatText.Verdict verdict = verdict(typed);
        switch (verdict.problem) {
            case EMPTY: return Refusal.EMPTY;
            case TOO_LONG: return Refusal.TOO_LONG;
            case LINK: return Refusal.LINK;
            case PHONE: return Refusal.PHONE;
            case WORDS: return Refusal.WORDS;
            default: break;
        }
        String clean = ChatText.clean(typed);
        if (linksLimited() != null && !ChatLinks.find(clean).isEmpty()) return Refusal.LIMITED_LINKS;
        ChatMessage reply = replyTo != null && offers(SocialModel.ChatState.REPLIES) ? replyTo : null;
        mBook.write(conv, clean, null, reply, nonce(), System.currentTimeMillis());
        mBook.markRead(conv);
        mTyping.sent(conv);
        mHost.changed();
        flush();
        return Refusal.NONE;
    }

    /** CHAT2: a sticker of a set the service takes, maybe as a reply. */
    @MainThread
    public Refusal sendSticker(String conv, String sticker, @Nullable ChatMessage replyTo) {
        Refusal can = canWrite(conv);
        if (can != Refusal.NONE) return can;
        if (!ChatMessage.isSticker(sticker) || !stickerSets().contains(sticker.substring(0, sticker.indexOf('/')))) {
            return Refusal.UNAVAILABLE;
        }
        ChatMessage reply = replyTo != null && offers(SocialModel.ChatState.REPLIES) ? replyTo : null;
        mBook.write(conv, "", sticker, reply, nonce(), System.currentTimeMillis());
        mBook.markRead(conv);
        mTyping.sent(conv);
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
        JsonObject frame = mBook.frameOf(o);
        mBook.sending(o.nonce, now);
        mHost.changed();
        connection.request(frame, answer -> {
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
                mBook.setReactionsTaken(offers(SocialModel.ChatState.REACTIONS));
                mBook.sent(o.nonce, Json.obj(answer, "msg"));
            } else {
                String code = Json.str(answer, "code", "bad_request");
                mBook.notSent(o.nonce, code, Json.str(answer, "reason"), Json.num(answer, "retryAfter", 0), at,
                        Json.str(answer, "item"), Json.str(answer, "rule"));
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
        ChatText.Verdict verdict = verdict(typed);
        if (verdict.problem != ChatText.Problem.NONE) {
            ChatText.Problem problem = verdict.problem;
            done.failed(new SocialProblem(SocialProblem.Kind.CHAT_REJECTED, 0, "local", 0, null, null,
                    problem == ChatText.Problem.LINK ? "link" : problem == ChatText.Problem.PHONE ? "phone"
                            : problem == ChatText.Problem.WORDS ? "words" : "invalid"));
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
                mBook.setReactionsTaken(offers(SocialModel.ChatState.REACTIONS));
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
                mCards.forget(conv, id);
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

    // ---- reactions (CHAT2) ----

    /**
     * A press on {@code emoji} for a message: takes the player's back when they gave it, else gives it
     * when there is room. Shown at once; the service's answer settles it ({@code done} hears a refusal).
     */
    @MainThread
    public React react(final String conv, ChatBook.Entry entry, final String emoji, final Done done) {
        PresenceConnection connection = mHost.chatConnection();
        if (mBook == null || connection == null || !offers(SocialModel.ChatState.REACTIONS) || entry.message == null) {
            return React.UNAVAILABLE;
        }
        long hubNow = mBook.hubNowMs(System.currentTimeMillis());
        if (!mBook.reactable(entry, hubNow)) return React.GONE;
        ChatBook.ReactVerdict verdict = mBook.reactVerdict(entry.reactions, emoji);
        switch (verdict) {
            case REFUSED: return React.REFUSED;
            case MINE: return React.FULL_MINE;
            case KINDS: return React.FULL_KINDS;
            default: break;
        }
        final boolean on = verdict == ChatBook.ReactVerdict.ADD;
        final long id = entry.message.id;
        JsonObject frame = mBook.react(conv, id, emoji, on, hubNow);
        if (frame == null) return React.GONE;
        mHost.changed();
        connection.request(frame, answer -> {
            if (mBook == null) return;
            boolean ok = answer != null && "ack".equals(Json.str(answer, "t"));
            mBook.reactAnswered(conv, id, emoji, on, ok ? answer : null);
            mHost.changed();
            if (ok) done.ok();
            else done.failed(answer == null ? SocialProblem.unavailable("no answer") : SocialProblem.ofSocket(answer));
        });
        return on ? React.ADDED : React.REMOVED;
    }

    // ---- "yazıyor…" (CHAT2) ----

    /**
     * The player typed in {@code conv} (the box has text and the focus, no edit): a frame goes on the
     * first key and then at most every 4 seconds, while the extra is on, the player shares it, is not
     * invisible, and someone else in the conversation is online.
     */
    @MainThread
    public void typed(String conv) {
        PresenceConnection connection = mHost.chatConnection();
        if (connection == null || !mHost.foreground() || !offers(SocialModel.ChatState.TYPING)) return;
        SocialState state = mHost.state();
        SocialModel.Me me = state.me();
        if (me == null || !me.shareTyping || SocialModel.Status.INVISIBLE.equals(me.status)) return;
        if (!someoneOnline(conv)) return;
        if (!mTyping.wrote(conv, System.currentTimeMillis())) return;
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "chat_typing");
        frame.addProperty("conv", conv);
        connection.send(frame);
    }

    /** Someone else in the conversation is online (the friend, or a crewmate). */
    private boolean someoneOnline(String conv) {
        SocialState state = mHost.state();
        String me = state.myId();
        String crew = ChatConv.crewId(conv);
        if (crew != null) {
            SocialModel.Crew c = state.crew(crew);
            if (c == null) return false;
            for (SocialModel.Member m : c.team.members) {
                if (!m.profile.id.equals(me) && state.presence(m.profile.id).online()) return true;
            }
            return false;
        }
        String other = ChatConv.other(conv, me);
        return other != null && state.presence(other).online();
    }

    /** Who writes in {@code conv} now (those the player shares "Yazıyor bilgisi" with; the player has it on). */
    public List<String> writers(String conv) {
        SocialModel.Me me = mHost.state().me();
        if (me != null && !me.shareTyping) return Collections.emptyList();
        return mTyping.writers(conv, System.currentTimeMillis());
    }

    private void scheduleTypingTick() {
        mMain.removeCallbacks(mTypingTick);
        long next = mTyping.nextEnd();
        if (next > 0) mMain.postDelayed(mTypingTick, Math.max(50, next - System.currentTimeMillis() + 20));
    }

    private void typingTick() {
        if (mTyping.prune(System.currentTimeMillis())) mHost.changed();
        scheduleTypingTick();
    }

    // ---- read marks (CHAT2) ----

    /**
     * Someone else's newest message ({@code id}) has been on screen for a second, the app in front: the
     * mark goes (only forward, at most every 2 seconds per conversation; a later one waits for its turn).
     */
    @MainThread
    public void seenOnScreen(final String conv, final long id) {
        if (id <= 0 || !offers(SocialModel.ChatState.READS) || !mHost.foreground()) return;
        Long sent = mReadSent.get(conv);
        if (sent != null && id <= sent) return;
        long now = System.currentTimeMillis();
        Long at = mReadSentAt.get(conv);
        if (at != null && now - at < READ_EVERY_MS) {
            Runnable before = mReadLater.remove(conv);
            if (before != null) mMain.removeCallbacks(before);
            Runnable later = () -> {
                mReadLater.remove(conv);
                seenOnScreen(conv, id);
            };
            mReadLater.put(conv, later);
            mMain.postDelayed(later, READ_EVERY_MS - (now - at) + 20);
            return;
        }
        PresenceConnection connection = mHost.chatConnection();
        if (connection == null) return;
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "chat_read");
        frame.addProperty("conv", conv);
        frame.addProperty("id", id);
        if (connection.send(frame)) {
            mReadSent.put(conv, id);
            mReadSentAt.put(conv, now);
        }
    }

    // ---- link cards (CHAT2) ----

    /** A message with a link came on screen: its card is asked for once (the first link the rules let through). */
    @MainThread
    public void wantCard(String conv, ChatMessage message) {
        if (mBook == null || message.isSticker()) return;
        ChatLinks.Lists lists = linkLists();
        if (lists == null) return;
        loadCards(conv);
        for (ChatLinks.MessageLink link : ChatLinks.messageLinks(message.text, lists)) {
            if (!link.ok) continue;
            long age = mBook.hubNowMs(System.currentTimeMillis()) - message.id;
            // Posted: this runs while a chat row is bound, and a socket that is closing answers at once (its
            // change would reach the list in the middle of its layout).
            if (mCards.want(conv, message.id, link.link.url, ChatLinkUi.domainOf(link.host), age)) {
                mMain.removeCallbacks(mCardPump);
                mMain.post(mCardPump);
            }
            return;
        }
    }

    /** The composer shows a link the rules let through: its card (memory only). */
    @MainThread
    public void wantComposerCard(String url, String host) {
        if (linkLists() == null) return;
        if (mCards.wantComposer(url, ChatLinkUi.domainOf(host))) pumpCards();
    }

    @Nullable
    public ChatCards.Card card(String conv, long id) {
        return mCards.card(conv, id);
    }

    @Nullable
    public ChatCards.Card composerCard(String url) {
        return mCards.composerCard(url);
    }

    /** The conversation's kept cards are read from the file once (when it first needs one). */
    private void loadCards(final String conv) {
        final ChatStore store = mStore;
        if (store == null || !mCardsLoaded.add(conv)) return;
        store.afterWrites(() -> {
            final Map<Long, String> rows = store.cards(conv);
            mMain.post(() -> {
                if (store != mStore) return;
                Map<Long, ChatCards.Card> cards = new HashMap<>();
                for (Map.Entry<Long, String> e : rows.entrySet()) {
                    ChatCards.Card card = ChatCards.Card.fromJson(Json.parseObject(e.getValue()));
                    if (card != null) cards.put(e.getKey(), card);
                }
                if (cards.isEmpty()) return;
                mCards.loaded(conv, cards);
                mHost.changed();
            });
        });
    }

    /** Sends the next card request (one at a time per socket). */
    private void pumpCards() {
        mMain.removeCallbacks(mCardPump);
        final PresenceConnection connection = mHost.chatConnection();
        if (connection == null || !mSynced) return;
        long now = System.currentTimeMillis();
        final ChatCards.Ask ask = mCards.next(now);
        if (ask == null) {
            long wait = mCards.waitUntil();
            if (wait > now) mMain.postDelayed(mCardPump, wait - now + 20);
            return;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "chat_unfurl");
        frame.addProperty("url", ask.url);
        connection.request(frame, answer -> {
            ChatCards.Outcome outcome = mCards.answered(ask, answer, System.currentTimeMillis());
            ChatCards.Card card = ask.id > 0 ? mCards.card(ask.conv, ask.id) : null;
            ChatStore store = mStore;
            if (store != null && card != null && ChatCards.worthKeeping(outcome)) {
                store.putCard(ask.conv, ask.id, card.toJson().toString());
            }
            mHost.changed();
            pumpCards();
        });
    }

    // ---- replies (CHAT2) ----

    /** What a reply quotes; a message memory does not hold is looked up in the file (once). */
    @MainThread
    public ChatBook.Quote quote(final String conv, final long replyId, @Nullable String replyFrom) {
        final ChatBook book = mBook;
        if (book == null) return new ChatBook.Quote(ChatBook.Quote.Kind.EARLIER, replyId, replyFrom, null);
        ChatBook.Quote quote = book.quote(conv, replyId, replyFrom);
        final ChatStore store = mStore;
        if (quote.kind == ChatBook.Quote.Kind.EARLIER && store != null && book.quoteNeedsLookup(conv, replyId)
                && mLookingUp.add(conv + "|" + replyId)) {
            store.afterWrites(() -> {
                final ChatStore.Line line = store.line(conv, replyId);
                mMain.post(() -> {
                    mLookingUp.remove(conv + "|" + replyId);
                    if (book != mBook) return;
                    book.quoteFound(conv, replyId, line.message, line.tombstone);
                    if (line.message != null || line.tombstone != null) mHost.changed();
                });
            });
        }
        return quote;
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
        mCards.forgetConversation(conv);
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
