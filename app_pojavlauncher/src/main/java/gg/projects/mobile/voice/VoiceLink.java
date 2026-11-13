package gg.projects.mobile.voice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.SparseArray;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's screens and the voice process ({@link VoiceService}, process {@code :voice}): a stay begins with
 * {@link #join} (a foreground service start, so it lasts through the game and the background); while a screen of the app
 * is up it binds ({@link #attach}) and hears the stay's state and notices, and sends the player's choices. Nothing is
 * bound while no screen shows, and nothing is started without a stay.
 */
public final class VoiceLink {
    // Launcher → voice.
    static final int REGISTER = 1;
    static final int UNREGISTER = 2;
    static final int MUTE = 3;
    static final int DEAF = 4;
    static final int LEAVE = 5;
    static final int VOLUME = 6;
    static final int LMUTE = 7;
    static final int INVITE = 8;
    static final int CANCEL_INVITE = 9;
    static final int MOD = 10;
    static final int METERS = 11;
    static final int SOUNDS = 12;
    static final int TOGGLE_MIC = 13;
    /** M053: the ignore list ("ids"). */
    static final int IGNORED = 14;
    /** SQOL: the room chat: a text ("text") or sticker ("sticker"), a delete ("id", answered), seen up to ("at"), a refused send's retry and discard (arg1). */
    static final int CHAT_SEND = 15;
    static final int CHAT_DELETE = 16;
    static final int CHAT_SEEN = 17;
    static final int CHAT_RETRY = 18;
    static final int CHAT_DISCARD = 19;
    /** After 0.5.4: the player blocked "person": their room chat messages go, a call of two with them ends. */
    static final int BLOCKED = 20;
    /**
     * MOB-WATCH: the stream screen: watch ("person", arg1 the share, "layer"), stop watching ("person", arg1), another
     * layer, on screen or not (arg1), a touch, its surface ("surface"; none: gone), a streamer's sound ("person", arg1
     * volume, arg2 muted) and the duck's choices (arg1 on, arg2 level, "self").
     */
    static final int WATCH = 21;
    static final int UNWATCH = 22;
    static final int WATCH_LAYER = 23;
    static final int WATCH_SHOWN = 24;
    static final int WATCH_STILL = 25;
    static final int WATCH_SURFACE = 26;
    static final int STREAM_SOUND = 27;
    static final int DUCK = 28;
    // Voice → launcher.
    static final int STATE = 101;
    static final int NOTICE = 102;
    static final int ANSWER = 103;
    /** SQOL: the room chat as it is now ("json"; none: no stay). */
    static final int CHAT = 104;

    // Intent actions and extras of the voice process.
    static final String ACTION_JOIN = "gg.projects.mobile.voice.JOIN";
    static final String ACTION_LEAVE = "gg.projects.mobile.voice.LEAVE";
    static final String ACTION_MIC = "gg.projects.mobile.voice.MIC";
    static final String EXTRA_ROOM = "room";
    static final String EXTRA_TICKET = "ticket";
    static final String EXTRA_CALLING = "calling";
    static final String EXTRA_OPENED = "opened";
    static final String EXTRA_KBPS = "kbps";
    static final String EXTRA_RING = "ring";
    static final String EXTRA_IDLE = "idle";
    static final String EXTRA_SOUNDS = "sounds";
    static final String EXTRA_ME = "me";
    static final String EXTRA_TITLE = "title";

    /** What the screens hear. */
    public interface Listener {
        /** The stay as it is now (null: none). */
        void onVoiceState(@Nullable VoiceState state);

        /** A word for the player (a stay that ended, a move, a moderator's mute, the microphone). */
        void onVoiceNotice(VoiceLink.Notice notice);

        /** The room turned the join down before it began (full, gone, removed, restricted). */
        void onVoiceRefused(VoiceAnswer answer);

        /** SQOL: the room chat changed (null: no stay). Only the room's surfaces listen. */
        default void onVoiceChat(@Nullable RoomChat.View chat) {}
    }

    /** A notice as it crosses the processes. */
    public static final class Notice {
        public final VoiceMachine.Notice.Kind kind;
        @Nullable public final VoiceMachine.LeftReason reason;
        @Nullable public final String friend;
        @Nullable public final Long until;
        @Nullable public final String room;
        @Nullable public final VoiceMachine.Mic mic;

        Notice(Bundle b) {
            kind = VoiceMachine.Notice.Kind.valueOf(b.getString("kind", "LEFT"));
            String r = b.getString("reason");
            reason = r == null ? null : VoiceMachine.LeftReason.valueOf(r);
            friend = b.getString("friend");
            until = b.containsKey("until") ? b.getLong("until") : null;
            room = b.getString("room");
            String m = b.getString("mic");
            mic = m == null ? null : VoiceMachine.Mic.valueOf(m);
        }

        static Bundle bundle(VoiceMachine.Notice n) {
            Bundle b = new Bundle();
            b.putString("kind", n.kind.name());
            if (n.reason != null) b.putString("reason", n.reason.name());
            if (n.friend != null) b.putString("friend", n.friend);
            if (n.until != null) b.putLong("until", n.until);
            if (n.room != null) b.putString("room", n.room);
            if (n.mic != null) b.putString("mic", n.mic.name());
            return b;
        }
    }

    /** Hears the stay's state without counting as a screen (the friends feature keeps its socket for a stay). */
    public interface Watcher {
        void onState(@Nullable VoiceState state);
    }

    /** An answer to a request ({@code invite}, {@code mod}) as it crosses the processes. */
    public interface Answered {
        void onAnswer(VoiceAnswer answer);
    }

    private static VoiceLink sInstance;

    @MainThread
    public static VoiceLink get(Context context) {
        if (sInstance == null) sInstance = new VoiceLink(context.getApplicationContext());
        return sInstance;
    }

    private final Context mApp;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();
    private final List<Watcher> mWatchers = new ArrayList<>();
    private final SparseArray<Answered> mWaiting = new SparseArray<>();
    private int mNextRid = 1;
    @Nullable private Messenger mService;
    private boolean mBound;
    private int mAttached;
    @Nullable private VoiceState mState;
    /** SQOL: the room chat as the voice process last sent it. */
    @Nullable private RoomChat.View mChat;
    private boolean mMeters;
    /** A stay was asked for and its first state has not come yet. */
    private boolean mJoining;
    private final Runnable mJoinTimeout = () -> {
        mJoining = false;
        if (!wanted()) unbind();
    };
    private final Messenger mReplies = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            onReply(msg);
        }
    });

    private VoiceLink(Context app) {
        mApp = app;
    }

    // ---- a stay begins ----

    /** Starts the stay in the voice process (from a screen, a notification's "Katıl" or the game). */
    public static void join(Context context, String room, String ticket, @Nullable String calling, boolean openedByMe,
                            VoiceMachine.Service service, boolean sounds, String me, String title) {
        Intent intent = joinIntent(context, room, ticket, calling, openedByMe, service, sounds, me, title);
        ContextCompat.startForegroundService(context, intent);
        // The link binds now and hears the new stay (a screen shows it, the friends feature keeps its socket for it).
        VoiceLink link = get(context);
        link.mJoining = true;
        link.bindIfWanted();
        link.mMain.removeCallbacks(link.mJoinTimeout);
        link.mMain.postDelayed(link.mJoinTimeout, 15_000);
    }

    /** The voice process's start of a stay (the ticket stays in this app: never logged). */
    static Intent joinIntent(Context context, String room, String ticket, @Nullable String calling, boolean openedByMe,
                             VoiceMachine.Service service, boolean sounds, String me, String title) {
        Intent intent = new Intent(context, VoiceService.class)
                .setAction(ACTION_JOIN)
                .putExtra(EXTRA_ROOM, room)
                .putExtra(EXTRA_TICKET, ticket)
                .putExtra(EXTRA_OPENED, openedByMe)
                .putExtra(EXTRA_KBPS, service.kbps)
                .putExtra(EXTRA_RING, service.ringSeconds)
                .putExtra(EXTRA_IDLE, service.idleMinutes)
                .putExtra(EXTRA_SOUNDS, sounds)
                .putExtra(EXTRA_ME, me)
                .putExtra(EXTRA_TITLE, title);
        if (calling != null) intent.putExtra(EXTRA_CALLING, calling);
        return intent;
    }

    // ---- the screens ----

    /** A screen came up (counted): bind while a stay runs. */
    @MainThread
    public void attach(Listener listener) {
        if (!mListeners.contains(listener)) mListeners.add(listener);
        mAttached++;
        bindIfWanted();
        listener.onVoiceState(mState);
        if (mChat != null) listener.onVoiceChat(mChat);
    }

    @MainThread
    public void detach(Listener listener) {
        mListeners.remove(listener);
        mAttached = Math.max(0, mAttached - 1);
        if (!wanted()) unbind();
    }

    @Nullable
    public VoiceState state() {
        return mState;
    }

    /** {@code watcher} hears every change of the stay (a stay keeps the link bound while it lasts). */
    @MainThread
    public void watch(Watcher watcher) {
        if (!mWatchers.contains(watcher)) mWatchers.add(watcher);
    }

    @MainThread
    public void unwatch(Watcher watcher) {
        mWatchers.remove(watcher);
    }

    /** Bound while a screen is up or a stay is known (so the stay's end is heard), never otherwise. */
    private boolean wanted() {
        return mAttached > 0 || mState != null || mJoining;
    }

    private void bindIfWanted() {
        if (mBound || !wanted()) return;
        // BIND flag 0: never starts the service by itself; a stay's start makes it come.
        mBound = mApp.bindService(new Intent(mApp, VoiceService.class), mConnection, 0);
    }

    private void unbind() {
        if (!mBound) return;
        send(Message.obtain(null, UNREGISTER));
        mBound = false;
        mService = null;
        try {
            mApp.unbindService(mConnection);
        } catch (IllegalArgumentException ignored) {
            // Not bound after all.
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = new Messenger(binder);
            send(Message.obtain(null, REGISTER));
            if (mMeters) sendFlag(METERS, true);
            // MOB-WATCH: a stream screen that came up before the link was bound says what it wants now.
            sendDuck();
            if (mWatchU != null) {
                sendFlag(WATCH_SHOWN, mWatchShown);
                sendWatch();
                if (mWatchSurface != null) sendSurface();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The voice process ended (a crash, or Android): no stay any more.
            mService = null;
            setState(null);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            mService = null;
            setState(null);
            if (mBound) {
                mBound = false;
                try {
                    mApp.unbindService(this);
                } catch (IllegalArgumentException ignored) {
                    // Gone already.
                }
                bindIfWanted();
            }
        }
    };

    private void onReply(Message msg) {
        switch (msg.what) {
            case STATE:
                setState(VoiceState.read(msg.getData().getString("json")));
                break;
            case CHAT: {
                RoomChat.View chat = RoomChat.View.read(msg.getData().getString("json"));
                // A chat of another room than the state's (a move just now) waits for its own.
                mChat = chat;
                for (Listener l : new ArrayList<>(mListeners)) l.onVoiceChat(chat);
                break;
            }
            case NOTICE: {
                Notice notice = new Notice(msg.getData());
                for (Listener l : new ArrayList<>(mListeners)) l.onVoiceNotice(notice);
                break;
            }
            case ANSWER: {
                Bundle b = msg.getData();
                String refused = b.getString("refused");
                VoiceAnswer answer = new VoiceAnswer(refused == null ? null : VoiceAnswer.Refusal.valueOf(refused),
                        b.containsKey("retryAfter") ? b.getLong("retryAfter") : null,
                        b.containsKey("until") ? b.getLong("until") : null);
                if (msg.arg2 == 0) {
                    for (Listener l : new ArrayList<>(mListeners)) l.onVoiceRefused(answer);
                    break;
                }
                Answered answered = mWaiting.get(msg.arg2);
                mWaiting.remove(msg.arg2);
                if (answered != null) answered.onAnswer(answer);
                break;
            }
            default:
                break;
        }
    }

    private void setState(@Nullable VoiceState state) {
        if (state != null) mJoining = false;
        mState = state;
        if (state == null && mChat != null) {
            // The stay's chat went with it.
            mChat = null;
            for (Listener l : new ArrayList<>(mListeners)) l.onVoiceChat(null);
        }
        for (Listener l : new ArrayList<>(mListeners)) l.onVoiceState(state);
        for (Watcher w : new ArrayList<>(mWatchers)) w.onState(state);
        if (!wanted()) unbind();
    }

    // ---- the player's choices ----

    public void setMute(boolean on) {
        sendFlag(MUTE, on);
    }

    /** "Mikrofon": deafened, it opens the microphone and the sound together. */
    public void toggleMic() {
        send(Message.obtain(null, TOGGLE_MIC));
    }

    public void setDeaf(boolean on) {
        sendFlag(DEAF, on);
    }

    public void leave() {
        // Also when no screen is bound (the call bar of a screen that just came up).
        if (mService == null) {
            Intent intent = new Intent(mApp, VoiceService.class).setAction(ACTION_LEAVE);
            try {
                mApp.startService(intent);
            } catch (IllegalStateException ignored) {
                // No stay to leave.
            }
            return;
        }
        send(Message.obtain(null, LEAVE));
    }

    public void setVolume(String person, int percent) {
        Message m = Message.obtain(null, VOLUME, percent, 0);
        m.getData().putString("person", person);
        send(m);
    }

    /** M053: the ignore list changed: a running stay silences (or no longer silences) those people at once. */
    public void setIgnored(java.util.List<String> ids) {
        Message m = Message.obtain(null, IGNORED);
        m.getData().putStringArrayList("ids", new java.util.ArrayList<>(ids));
        send(m);
    }

    public void setMutedForMe(String person, boolean on) {
        Message m = Message.obtain(null, LMUTE, on ? 1 : 0, 0);
        m.getData().putString("person", person);
        send(m);
    }

    public void invite(String person, Answered answered) {
        request(INVITE, person, null, null, null, answered);
    }

    public void cancelInvite(String person, Answered answered) {
        request(CANCEL_INVITE, person, null, null, null, answered);
    }

    public void moderate(VoiceWire.ModAct act, @Nullable String person, @Nullable String room, @Nullable String name,
                         Answered answered) {
        request(MOD, person, room, name, act, answered);
    }

    // ---- SQOL: the room chat ----

    /** The room chat as it is now (null: no stay, or not heard yet). */
    @Nullable
    public RoomChat.View chat() {
        return mChat;
    }

    /** Text to the room chat; the outcome shows on the message (the chat's own words), nothing answers here. */
    public void chatSend(String text) {
        Message m = Message.obtain(null, CHAT_SEND);
        m.getData().putString("text", text);
        send(m);
    }

    public void chatSticker(String sticker) {
        Message m = Message.obtain(null, CHAT_SEND);
        m.getData().putString("sticker", sticker);
        send(m);
    }

    public void chatDelete(String id, Answered answered) {
        request(CHAT_DELETE, id, null, null, null, answered);
    }

    /** The chat is on screen up to {@code at} (the newest message shown). */
    public void chatSeen(long at) {
        Message m = Message.obtain(null, CHAT_SEEN);
        m.getData().putLong("at", at);
        send(m);
    }

    public void chatRetry(int rid) {
        send(Message.obtain(null, CHAT_RETRY, rid, 0));
    }

    public void chatDiscard(int rid) {
        send(Message.obtain(null, CHAT_DISCARD, rid, 0));
    }

    /** The player blocked {@code person} (a stay hears it at once; nothing without one). */
    public void blocked(String person) {
        Message m = Message.obtain(null, BLOCKED);
        m.getData().putString("person", person);
        send(m);
    }

    // ---- MOB-WATCH: the stream screen ----

    @Nullable private String mWatchU;
    private int mWatchN;
    private String mWatchLayer = StreamView.FULL;
    private boolean mWatchShown;
    @Nullable private android.view.Surface mWatchSurface;

    /** "İzle": {@code person}'s share {@code n}, asked at {@code layer} (the stream screen's first choice). */
    public void watch(String person, int n, String layer) {
        mWatchU = person;
        mWatchN = n;
        mWatchLayer = StreamView.layer(layer);
        sendWatch();
    }

    /** "İzlemeyi Bırak" (the stream screen closes). */
    public void stopWatching(String person, int n) {
        if (person.equals(mWatchU) && n == mWatchN) {
            mWatchU = null;
            mWatchSurface = null;
        }
        Message m = Message.obtain(null, UNWATCH, n, 0);
        m.getData().putString("person", person);
        send(m);
    }

    /** The layer the stream screen asks for now ({@code f}, {@code q}). */
    public void watchLayer(String person, int n, String layer) {
        if (person.equals(mWatchU) && n == mWatchN) mWatchLayer = StreamView.layer(layer);
        Message m = Message.obtain(null, WATCH_LAYER, n, 0);
        m.getData().putString("person", person);
        m.getData().putString("layer", StreamView.layer(layer));
        send(m);
    }

    /** The stream screen is on screen (picture-in-picture counts) or not. */
    public void watchShown(boolean shown) {
        mWatchShown = shown;
        sendFlag(WATCH_SHOWN, shown);
    }

    /** A touch of the stream screen, or "Evet" on "Hâlâ izliyor musun?". */
    public void watchStill() {
        send(Message.obtain(null, WATCH_STILL));
    }

    /** The stream screen's surface for the picture (null: it went). */
    public void watchSurface(@Nullable android.view.Surface surface) {
        mWatchSurface = surface;
        sendSurface();
    }

    /** A streamer's "Yayın Sesi" (percent) and "Yayını Sessize Al" on this phone. */
    public void setStreamSound(String person, int volume, boolean muted) {
        Message m = Message.obtain(null, STREAM_SOUND, StreamSound.clampVolume(volume), muted ? 1 : 0);
        m.getData().putString("person", person);
        send(m);
    }

    /** "Biri Konuşunca Yayın Sesini Kıs", "Kısma Miktarı" (percent), "Ben Konuşunca da Kıs". */
    public void setDuck(boolean on, int level, boolean self) {
        mDuck = new Object[]{on, StreamSound.clampDuckLevel(level), self};
        sendDuck();
    }

    /** The duck's choices as last said (sent again when the link binds), or null. */
    @Nullable private Object[] mDuck;

    private void sendDuck() {
        if (mDuck == null) return;
        Message m = Message.obtain(null, DUCK, (Boolean) mDuck[0] ? 1 : 0, (Integer) mDuck[1]);
        m.getData().putBoolean("self", (Boolean) mDuck[2]);
        send(m);
    }

    private void sendWatch() {
        if (mWatchU == null) return;
        Message m = Message.obtain(null, WATCH, mWatchN, 0);
        m.getData().putString("person", mWatchU);
        m.getData().putString("layer", mWatchLayer);
        send(m);
    }

    private void sendSurface() {
        Message m = Message.obtain(null, WATCH_SURFACE);
        if (mWatchSurface != null && mWatchSurface.isValid()) m.getData().putParcelable("surface", mWatchSurface);
        send(m);
    }

    /** A room surface is on screen: live speaking rings (only then is anything measured). */
    public void setMeters(boolean on) {
        if (mMeters == on) return;
        mMeters = on;
        sendFlag(METERS, on);
    }

    public void setSounds(boolean on) {
        sendFlag(SOUNDS, on);
    }

    private void request(int what, @Nullable String person, @Nullable String room, @Nullable String name,
                         @Nullable VoiceWire.ModAct act, Answered answered) {
        if (mService == null) {
            answered.onAnswer(VoiceAnswer.refused(VoiceAnswer.Refusal.NOTHING));
            return;
        }
        int rid = mNextRid++;
        mWaiting.put(rid, answered);
        Message m = Message.obtain(null, what, 0, rid);
        Bundle b = m.getData();
        if (person != null) b.putString("person", person);
        if (room != null) b.putString("room", room);
        if (name != null) b.putString("name", name);
        if (act != null) b.putString("act", act.name());
        send(m);
        // An answer that never comes (the voice process ended) frees its caller after 10 s.
        mMain.postDelayed(() -> {
            Answered waiting = mWaiting.get(rid);
            if (waiting == null) return;
            mWaiting.remove(rid);
            waiting.onAnswer(VoiceAnswer.refused(VoiceAnswer.Refusal.FAILED));
        }, 10_000);
    }

    private void sendFlag(int what, boolean on) {
        send(Message.obtain(null, what, on ? 1 : 0, 0));
    }

    private void send(Message message) {
        Messenger service = mService;
        if (service == null) return;
        message.replyTo = mReplies;
        try {
            service.send(message);
        } catch (RemoteException e) {
            mService = null;
            setState(null);
        }
    }

    /** Whether the app may record now (asked on a screen before a call when it may not). */
    public static boolean micAllowed(Context context) {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
