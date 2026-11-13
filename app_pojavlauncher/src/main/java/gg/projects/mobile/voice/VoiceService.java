package gg.projects.mobile.voice;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.social.SessionStore;
import gg.projects.mobile.social.SocialDebug;

/**
 * Voice chat's stay on the phone, in a process of its own ({@code :voice}, like the game's): a foreground service of
 * type microphone, so the voice goes on while Minecraft runs and with the app in the background. It runs the
 * {@link VoiceMachine} with its I/O: the room socket ({@link VoiceSocket}), the audio engine ({@link VoiceEngine}), the
 * stay's notification ("Sesli sohbette" with Mikrofon and Ayrıl) and the screens that are up ({@link VoiceLink}).
 *
 * <p>It starts with a stay and stops a minute after it ended (the engine lingers for a quick way back). Nothing wakes
 * without a stay: one timer at the machine's next deadline.
 */
public final class VoiceService extends Service implements VoiceEngine.Listener, VoiceSocket.Listener {
    private static final String TAG = "ProjectsVoice";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private VoicePrefs mPrefs;
    private VoiceMachine mMachine;
    @Nullable private VoiceEngine mEngine;
    @Nullable private VoiceSocket mSocket;
    private String mBase = SocialDebug.PRODUCTION_URL;
    @Nullable private String mToken;
    private String mTitle = "";
    private boolean mForeground;
    @Nullable private PowerManager.WakeLock mWake;
    /** The phone's last touch on the machine's clock (the screen on counts as use). */
    private long mScreenOffAt;
    private final List<Messenger> mClients = new ArrayList<>();
    private final Map<Messenger, Boolean> mMeters = new HashMap<>();
    /** The machine's rid → [the screen that asked, its own rid]. */
    private final Map<Integer, Object[]> mAsked = new HashMap<>();
    private boolean mChanged;
    /** SQOL: the room chat changed since it last went to the screens. */
    private boolean mChatChanged;
    @Nullable private String mLastLine;
    @Nullable private Boolean mLastMicOff;

    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            onClient(msg);
        }
    });

    private final Runnable mTick = this::tick;

    private final BroadcastReceiver mScreen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) mScreenOffAt = now();
            schedule();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = new VoicePrefs(this);
        mMachine = new VoiceMachine(mPrefs, new SecureRandom());
        mScreenOffAt = now();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mScreen, filter);
    }

    @Override
    public void onDestroy() {
        mMain.removeCallbacks(mTick);
        try {
            unregisterReceiver(mScreen);
        } catch (IllegalArgumentException ignored) {
            // Never registered.
        }
        if (mSocket != null) mSocket.close();
        mSocket = null;
        if (mEngine != null) mEngine.close();
        mEngine = null;
        releaseWake();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (VoiceLink.ACTION_JOIN.equals(action)) {
            // A foreground start must be made foreground at once, whatever follows.
            mTitle = intent.getStringExtra(VoiceLink.EXTRA_TITLE) == null ? "" : intent.getStringExtra(VoiceLink.EXTRA_TITLE);
            goForeground();
            onJoin(intent);
        } else if (VoiceLink.ACTION_LEAVE.equals(action)) {
            if (!mForeground && !mMachine.inRoom()) {
                stopSelf();
            } else {
                run(mMachine.leave(now()));
            }
        } else if (VoiceLink.ACTION_MIC.equals(action)) {
            run(mMachine.toggleMic());
        } else if (!mMachine.inRoom()) {
            // Started again by Android after the process went: there is no stay to come back to.
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void onJoin(Intent intent) {
        String room = intent.getStringExtra(VoiceLink.EXTRA_ROOM);
        String ticket = intent.getStringExtra(VoiceLink.EXTRA_TICKET);
        String me = intent.getStringExtra(VoiceLink.EXTRA_ME);
        SocialDebug debug = SocialDebug.read(this);
        SessionStore.Saved saved = new SessionStore(this, debug.override ? "session-debug.json" : "session.json").load();
        mToken = saved.token;
        mBase = debug.url.endsWith("/") ? debug.url : debug.url + "/";
        if (room == null || ticket == null || me == null || mToken == null || !me.equals(saved.userId)) {
            Log.i(TAG, "join refused: no session for it");
            if (!mMachine.inRoom()) {
                stopForegroundNow();
                if (!mMachine.engineOpen()) stopSelf();
            }
            return;
        }
        mPrefs.setSounds(intent.getBooleanExtra(VoiceLink.EXTRA_SOUNDS, true));
        // M053: the ignore list as the launcher process last wrote it.
        mPrefs.setIgnored(gg.projects.mobile.social.IgnoreList.forVoice(this));
        mMachine.setMe(me);
        VoiceMachine.Service service = new VoiceMachine.Service(intent.getIntExtra(VoiceLink.EXTRA_KBPS, 0),
                intent.getIntExtra(VoiceLink.EXTRA_RING, 0), intent.getIntExtra(VoiceLink.EXTRA_IDLE, 0));
        VoiceNotify.cancelEnded(this);
        holdWake();
        run(mMachine.join(new VoiceMachine.Start(room, ticket, intent.getStringExtra(VoiceLink.EXTRA_CALLING),
                intent.getBooleanExtra(VoiceLink.EXTRA_OPENED, false)), service, now(), unix()));
    }

    // ---- the screens ----

    private void onClient(Message msg) {
        Messenger from = msg.replyTo;
        Bundle data = msg.getData();
        switch (msg.what) {
            case VoiceLink.REGISTER:
                if (from != null && !mClients.contains(from)) mClients.add(from);
                // M053: a change to the ignore list made while no screen was bound reaches the stay now.
                mPrefs.setIgnored(gg.projects.mobile.social.IgnoreList.forVoice(this));
                if (mMachine.inRoom()) run(mMachine.replay());
                if (from != null) {
                    sendState(from);
                    sendChat(from);
                }
                break;
            case VoiceLink.UNREGISTER:
                mClients.remove(from);
                mMeters.remove(from);
                syncMeters();
                break;
            case VoiceLink.MUTE:
                run(mMachine.setMute(msg.arg1 != 0));
                break;
            case VoiceLink.TOGGLE_MIC:
                run(mMachine.toggleMic());
                break;
            case VoiceLink.DEAF:
                run(mMachine.setDeaf(msg.arg1 != 0));
                break;
            case VoiceLink.LEAVE:
                run(mMachine.leave(now()));
                break;
            case VoiceLink.VOLUME:
                run(mMachine.setVolume(data.getString("person", ""), msg.arg1));
                break;
            case VoiceLink.LMUTE:
                run(mMachine.setMutedForMe(data.getString("person", ""), msg.arg1 != 0));
                break;
            case VoiceLink.INVITE:
                asked(from, msg.arg2, mMachine.invite(data.getString("person", "")));
                break;
            case VoiceLink.CANCEL_INVITE:
                asked(from, msg.arg2, mMachine.cancelInvite(data.getString("person", "")));
                break;
            case VoiceLink.MOD: {
                VoiceWire.ModAct act;
                try {
                    act = VoiceWire.ModAct.valueOf(data.getString("act", ""));
                } catch (IllegalArgumentException e) {
                    break;
                }
                asked(from, msg.arg2, mMachine.moderate(act, data.getString("person"), data.getString("room"), data.getString("name")));
                break;
            }
            case VoiceLink.METERS:
                if (from != null) mMeters.put(from, msg.arg1 != 0);
                syncMeters();
                break;
            case VoiceLink.SOUNDS:
                mPrefs.setSounds(msg.arg1 != 0);
                break;
            case VoiceLink.IGNORED: {
                java.util.ArrayList<String> ids = data.getStringArrayList("ids");
                mPrefs.setIgnored(ids == null ? java.util.Collections.<String>emptyList() : ids);
                run(mMachine.replay());
                break;
            }
            // SQOL: the room chat.
            case VoiceLink.CHAT_SEND:
                run(mMachine.chatSend(data.getString("text"), data.getString("sticker")));
                break;
            case VoiceLink.CHAT_DELETE:
                // The message's id travels as the request's "person" (VoiceLink#request).
                asked(from, msg.arg2, mMachine.chatDelete(data.getString("person", "")));
                break;
            case VoiceLink.CHAT_SEEN:
                run(mMachine.chatSeen(data.getLong("at", 0)));
                break;
            case VoiceLink.CHAT_RETRY:
                run(mMachine.chatRetry(msg.arg1));
                break;
            case VoiceLink.CHAT_DISCARD:
                run(mMachine.chatDiscard(msg.arg1));
                break;
            case VoiceLink.BLOCKED: {
                // After 0.5.4: their room chat messages go at once; a call of two with them ends (machine.rs's rule).
                String person = data.getString("person", "");
                run(mMachine.chatBlocked(person));
                run(mMachine.blocked(person, now()));
                break;
            }
            default:
                break;
        }
    }

    private void asked(@Nullable Messenger from, int clientRid, VoiceMachine.Request request) {
        if (request.refused != null) {
            answer(from, clientRid, request.refused);
            return;
        }
        mAsked.put(request.rid, new Object[] {from, clientRid});
        run(request.effects);
    }

    private void answer(@Nullable Messenger to, int clientRid, VoiceAnswer answer) {
        if (to == null) return;
        Message m = Message.obtain(null, VoiceLink.ANSWER, 0, clientRid);
        Bundle b = m.getData();
        if (answer.refused != null) b.putString("refused", answer.refused.name());
        if (answer.retryAfter != null) b.putLong("retryAfter", answer.retryAfter);
        if (answer.until != null) b.putLong("until", answer.until);
        deliver(to, m);
    }

    private void syncMeters() {
        boolean on = false;
        for (Boolean wanted : mMeters.values()) on |= wanted;
        run(mMachine.setMeters(on));
    }

    private void sendState(Messenger to) {
        Message m = Message.obtain(null, VoiceLink.STATE);
        VoiceState state = mMachine.view(now(), unix());
        if (state != null) m.getData().putString("json", state.json());
        deliver(to, m);
    }

    /** SQOL: the room chat, apart from the state (the state goes out with every speaking ring; the chat only when it changes). */
    private void sendChat(Messenger to) {
        RoomChat.View chat = mMachine.chatView();
        if (chat == null) return;
        Message m = Message.obtain(null, VoiceLink.CHAT);
        m.getData().putString("json", chat.json());
        deliver(to, m);
    }

    private void deliver(Messenger to, Message m) {
        try {
            to.send(m);
        } catch (RemoteException e) {
            mClients.remove(to);
            mMeters.remove(to);
        }
    }

    // ---- the machine's effects ----

    private void run(List<VoiceMachine.Effect> effects) {
        for (VoiceMachine.Effect e : effects) {
            switch (e.kind) {
                case CONNECT:
                    if (mSocket != null) mSocket.close();
                    mSocket = mToken == null || e.ticket == null ? null
                            : new VoiceSocket(mBase, mToken, ProjectsContent.userAgent(), e.generation, e.ticket, this);
                    break;
                case SEND:
                    if (mSocket != null && mSocket.generation() == e.generation && e.frame != null) mSocket.send(e.frame);
                    break;
                case DISCONNECT:
                    if (mSocket != null && mSocket.generation() == e.generation) {
                        mSocket.close();
                        mSocket = null;
                    }
                    break;
                case ENGINE_OPEN:
                    if (mEngine == null) {
                        mEngine = new VoiceEngine(this, this);
                        mEngine.open();
                    }
                    run(mMachine.engineAttached());
                    break;
                case ENGINE_CLOSE:
                    if (mEngine != null) mEngine.close();
                    mEngine = null;
                    break;
                case ENGINE:
                    if (mEngine != null && e.command != null) mEngine.run(e.command);
                    break;
                case NOTICE:
                    if (e.notice != null) notice(e.notice);
                    break;
                case SPEAKING:
                case CHANGED:
                    mChanged = true;
                    break;
                case CHAT:
                    mChatChanged = true;
                    break;
                case ANSWER:
                    if (e.answer == null) break;
                    if (e.rid == VoiceMachine.JOIN_RID) {
                        // A join the room turned down before it began: the screens say why.
                        if (e.answer.refused != null && e.answer.refused != VoiceAnswer.Refusal.NOTHING) joinRefused(e.answer);
                    } else {
                        Object[] asked = mAsked.remove(e.rid);
                        if (asked != null) answer((Messenger) asked[0], (Integer) asked[1], e.answer);
                    }
                    break;
                default:
                    break;
            }
        }
        flush();
    }

    private void flush() {
        if (mChatChanged) {
            // Before the state: a screen that reads the state's unread count has the messages already.
            mChatChanged = false;
            for (Messenger client : new ArrayList<>(mClients)) sendChat(client);
        }
        if (mChanged) {
            mChanged = false;
            for (Messenger client : new ArrayList<>(mClients)) sendState(client);
            updateNotification();
        }
        if (!mMachine.inRoom()) {
            releaseWake();
            if (mForeground) stopForegroundNow();
            if (!mMachine.engineOpen()) {
                stopSelf();
                return;
            }
        }
        schedule();
    }

    private void notice(VoiceMachine.Notice notice) {
        if (!mClients.isEmpty()) {
            Bundle b = VoiceLink.Notice.bundle(notice);
            for (Messenger client : new ArrayList<>(mClients)) {
                Message m = Message.obtain(null, VoiceLink.NOTICE);
                m.setData(new Bundle(b));
                deliver(client, m);
            }
            return;
        }
        // No screen of the app shows: a stay's end leaves one quiet line (else the stay's notification just went).
        if (notice.kind == VoiceMachine.Notice.Kind.LEFT || notice.system()) {
            Context c = LanguageManager.wrap(this);
            VoiceNotify.ended(this, VoiceNotify.noticeText(c, notice.kind, notice.reason, notice.until, notice.mic));
        }
    }

    private void joinRefused(VoiceAnswer answer) {
        Context c = LanguageManager.wrap(this);
        String text = VoiceNotify.refusalText(c, answer, null);
        if (text.isEmpty()) return;
        if (!mClients.isEmpty()) {
            // rid 0: the join's own answer (VoiceLink.Listener#onVoiceRefused).
            for (Messenger client : new ArrayList<>(mClients)) {
                Message m = Message.obtain(null, VoiceLink.ANSWER, 0, 0);
                Bundle a = m.getData();
                a.putString("refused", answer.refused.name());
                if (answer.until != null) a.putLong("until", answer.until);
                if (answer.retryAfter != null) a.putLong("retryAfter", answer.retryAfter);
                deliver(client, m);
            }
            return;
        }
        VoiceNotify.ended(this, text);
    }

    // ---- the socket ----

    @Override
    public void onOpen(long generation) {
        run(mMachine.socketOpen(generation, now()));
    }

    @Override
    public void onMessage(long generation, VoiceWire.Message message) {
        run(mMachine.socketMessage(generation, message, now(), unix()));
    }

    @Override
    public void onRefused(long generation, int status, @Nullable String code) {
        run(mMachine.socketRefused(generation, status, code, now()));
    }

    @Override
    public void onClosed(long generation, int code) {
        if (mSocket != null && mSocket.generation() == generation) mSocket = null;
        run(mMachine.socketClosed(generation, code, now()));
    }

    // ---- the engine ----

    @Override
    public void onOffer(@Nullable String sdp, @Nullable String mid) {
        run(mMachine.engineOffer(sdp, mid));
    }

    @Override
    public void onAnswer(String sdp) {
        run(mMachine.engineAnswer(sdp));
    }

    @Override
    public void onConnected() {
        run(mMachine.engineConnected());
    }

    @Override
    public void onLost() {
        run(mMachine.engineLost(now()));
    }

    @Override
    public void onNeedTurn() {
        run(mMachine.engineNeedTurn(now()));
    }

    @Override
    public void onQuality(int quality) {
        run(mMachine.engineQuality(quality));
    }

    @Override
    public void onSpeaking(List<String> ids, boolean me) {
        run(mMachine.engineSpeaking(ids, me, now()));
    }

    @Override
    public void onSpoke() {
        run(mMachine.engineSpeaking(speakingNow(), true, now()));
    }

    private List<String> speakingNow() {
        VoiceState state = mMachine.view(now(), unix());
        List<String> out = new ArrayList<>();
        if (state != null) for (VoiceState.Person p : state.people) if (p.speaking) out.add(p.id);
        return out;
    }

    @Override
    public void onMic(VoiceMachine.Mic mic) {
        run(mMachine.engineMic(mic));
    }

    // ---- time ----

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    private static long unix() {
        return System.currentTimeMillis() / 1000;
    }

    /** The phone's last use: now while its screen is on, else when it went off. */
    private long lastInput() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        boolean on = power == null || (Build.VERSION.SDK_INT >= 20 ? power.isInteractive() : power.isScreenOn());
        return on ? now() : mScreenOffAt;
    }

    private void schedule() {
        mMain.removeCallbacks(mTick);
        Long at = mMachine.nextDeadline(lastInput());
        if (at == null) return;
        mMain.postDelayed(mTick, Math.max(0, at - now()));
    }

    private void tick() {
        run(mMachine.tick(now(), unix(), lastInput()));
    }

    // ---- foreground ----

    private void goForeground() {
        Notification notification = stayNotification();
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK | (mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
                try {
                    startForeground(VoiceNotify.ID_STAY, notification, type);
                } catch (SecurityException e) {
                    // Android may refuse the microphone from the background: listen only then.
                    startForeground(VoiceNotify.ID_STAY, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(VoiceNotify.ID_STAY, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(VoiceNotify.ID_STAY, notification);
            }
            mForeground = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "the stay could not go foreground: " + e.getClass().getSimpleName());
        }
    }

    private void stopForegroundNow() {
        mForeground = false;
        mLastLine = null;
        mLastMicOff = null;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    private Notification stayNotification() {
        Context c = LanguageManager.wrap(this);
        VoiceState state = mMachine == null ? null : mMachine.view(now(), unix());
        String line;
        if (state != null && state.reconnecting()) line = c.getString(R.string.projects_voice_reconnecting);
        else if (state != null && state.calling != null) line = c.getString(R.string.projects_voice_ringing);
        else line = mTitle;
        boolean micOff = state != null && (state.me.mute || state.me.deaf);
        mLastLine = line;
        mLastMicOff = micOff;
        return VoiceNotify.stay(this, line, micOff, VoiceNotify.serviceAction(this, VoiceLink.ACTION_MIC),
                VoiceNotify.serviceAction(this, VoiceLink.ACTION_LEAVE));
    }

    private void updateNotification() {
        if (!mForeground || !mMachine.inRoom()) return;
        VoiceState state = mMachine.view(now(), unix());
        Context c = LanguageManager.wrap(this);
        String line = state != null && state.reconnecting() ? c.getString(R.string.projects_voice_reconnecting)
                : state != null && state.calling != null ? c.getString(R.string.projects_voice_ringing) : mTitle;
        boolean micOff = state != null && (state.me.mute || state.me.deaf);
        // Only when what it says changed (speaking rings change nothing here).
        if (line.equals(mLastLine) && Boolean.valueOf(micOff).equals(mLastMicOff)) return;
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(VoiceNotify.ID_STAY, stayNotification());
        } catch (SecurityException ignored) {
            // The foreground service keeps its first notification.
        }
    }

    private void holdWake() {
        if (mWake != null) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return;
        // The room socket and the timers keep going with the screen off (a call); released when the stay ends.
        mWake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "projects:voice");
        mWake.setReferenceCounted(false);
        mWake.acquire(6 * 60 * 60_000L);
    }

    private void releaseWake() {
        if (mWake == null) return;
        if (mWake.isHeld()) mWake.release();
        mWake = null;
    }
}
