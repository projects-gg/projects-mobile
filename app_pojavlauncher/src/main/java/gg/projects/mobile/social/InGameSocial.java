package gg.projects.mobile.social;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.MainActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.launch.ConnectTarget;
import gg.projects.mobile.launch.GameConnect;

/**
 * FIX2 (item 8, the owner's wish of 22 October): the player stays online for friends while the game runs,
 * as on the desktop: "Oyunda" and where (Projects, another public server, a singleplayer world) as the
 * game's log says ({@link GameWhere}). The launcher's process ends when the game starts; the game's process
 * keeps one presence socket for the whole game, and it ends with the process.
 *
 * <p>Minimal on purpose: it reads no list and polls nothing (OkHttp's protocol ping every 45 s keeps it, which
 * the service answers without waking its hub); names come from what the launcher last read ({@link
 * SocialPrefs#peopleNames}) and from the frames themselves. It asks for no chat: a direct message still comes
 * as a push to a phone without a socket that takes chat (README "Push notifications"), so the launcher's
 * process shows it as before. It asks for no plans either (nothing of them is pushed). While the socket is
 * open the service pushes nothing else to this phone, so what those pushes said comes from here, with the
 * same words and switches: a friend request, a "Birlikte oyna" invite and a crew call, the last two with
 * "Bağlan" when they say where (asked over the game; {@link GameConnect}). Never on do-not-disturb; a muted
 * crew's call is quiet (the service says so); a block lets nothing through.
 */
public final class InGameSocial implements PresenceConnection.Listener, GameWhere.Listener {
    private static final String TAG = "ProjectsSocial";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<String> CAPS = Arrays.asList("callServer", "crews");
    private static final int REQUEST_GAME = 0x5A0001;
    @Nullable
    private static InGameSocial sSession;

    private final Context mApp;
    private final SocialApi mApi;
    private final String mToken;
    private final String mMe;
    private final SocialPrefs mPrefs;
    private final boolean mTeams;
    private final Backoff mBackoff = new Backoff(new Random());
    private final Map<String, String> mNames;
    /** Calls this socket saw, by where they were ({@link InGameRules#callWhere}). */
    private final Map<String, String> mCalls = new HashMap<>();
    private final Runnable mReconnect = this::connect;
    @Nullable
    private PresenceConnection mConnection;
    private boolean mLive;
    private boolean mDnd;
    private boolean mStopped;
    /** The hub's clock minus the phone's (seconds). */
    private long mClockOffset;
    @Nullable
    private GameActivity.Status mSent;

    private InGameSocial(Context app, SocialDebug debug, String token, String me, SocialPrefs prefs, boolean teams) {
        mApp = app;
        mApi = new SocialApi(debug.url, ProjectsContent.userAgent());
        mApi.setToken(token);
        mToken = token;
        mMe = me;
        mPrefs = prefs;
        mTeams = teams;
        mNames = new HashMap<>(prefs.peopleNames());
    }

    /** The game starts in this process (any thread): signed in with friends on, the player stays online. */
    public static void start(Context context) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (sSession != null) return;
            InGameSocial session = create(app);
            if (session == null) return;
            sSession = session;
            GameWhere.setListener(session);
            session.connect();
        });
    }

    @Nullable
    private static InGameSocial create(Context app) {
        try {
            SocialDebug debug = SocialDebug.read(app);
            SessionStore.Saved saved = new SessionStore(app, debug.override ? "session-debug.json" : "session.json").load();
            if (saved.token == null || saved.userId == null) return null;
            SocialPrefs prefs = new SocialPrefs(app);
            boolean[] config = prefs.lastConfig();
            if (!config[0] && !debug.forceFriends) return null;
            return new InGameSocial(app, debug, saved.token, saved.userId, prefs, config[1] || debug.forceTeams);
        } catch (RuntimeException e) {
            Log.w(TAG, "no friends session in the game", e);
            return null;
        }
    }

    @MainThread
    private void connect() {
        if (mStopped || mConnection != null) return;
        Log.i(TAG, "game socket opening");
        mConnection = PresenceConnection.open(mApi, mToken, CAPS, this);
    }

    @MainThread
    private void stop() {
        mStopped = true;
        MAIN.removeCallbacks(mReconnect);
        PresenceConnection connection = mConnection;
        mConnection = null;
        mLive = false;
        if (connection != null) connection.close();
    }

    // ---- what the game does ----

    @Override
    public void onWhere(GameActivity.Status status) {
        sendStatus(status);
    }

    @MainThread
    private void sendStatus(GameActivity.Status status) {
        PresenceConnection connection = mConnection;
        if (connection == null || !mLive || status.equals(mSent)) return;
        JsonObject message = new JsonObject();
        message.addProperty("t", "status");
        message.addProperty("state", "playing");
        if (status.where == GameActivity.Status.Where.SERVER && status.host != null) message.addProperty("host", status.host);
        else if (status.where == GameActivity.Status.Where.SINGLEPLAYER) message.addProperty("singleplayer", true);
        if (connection.send(message)) {
            mSent = status;
            Log.i(TAG, "game status sent: " + status);
        }
    }

    // ---- the socket ----

    @Override
    public void onOpen(PresenceConnection connection) {
        // The hub speaks first ("hello").
    }

    @Override
    public void onMessage(PresenceConnection connection, JsonObject message) {
        if (connection != mConnection) return;
        long now = SocialService.nowSeconds();
        String type = Json.str(message, "t", "");
        switch (type) {
            case "hello": {
                mLive = true;
                mBackoff.healthy();
                mSent = null;
                if (Json.has(message, "now")) mClockOffset = Json.num(message, "now", now) - now;
                mDnd = "dnd".equals(Json.str(message, "status"));
                // Calls already open are not news (shown quietly): only later ones are announced.
                for (JsonObject wire : Json.objects(message, "calls")) {
                    SocialModel.Call call = SocialModel.Call.read(wire);
                    if (call != null) mCalls.put(call.id, InGameRules.callWhere(call));
                }
                sendStatus(GameWhere.status());
                break;
            }
            case "status": {
                String status = Json.str(message, "status");
                if (status != null) mDnd = "dnd".equals(status);
                break;
            }
            case "request":
                onRequest(SocialModel.Profile.read(Json.obj(message, "from")));
                break;
            case "request_cancelled": {
                String id = Json.str(message, "id");
                if (id != null) SocialNotifications.cancelRequest(mApp, id);
                break;
            }
            case "friend_added": {
                SocialModel.Profile friend = SocialModel.Profile.read(Json.obj(message, "friend"));
                if (friend != null) mNames.put(friend.id, friend.name());
                break;
            }
            case "team": {
                SocialModel.Team team = SocialModel.Team.read(Json.obj(message, "team"));
                if (team != null) {
                    for (SocialModel.Member m : team.members) mNames.put(m.profile.id, m.profile.name());
                }
                break;
            }
            case "invite":
                onInvite(SocialModel.Invite.read(Json.obj(message, "invite")), now);
                break;
            case "call":
                onCall(message, now);
                break;
            case "call_end": {
                String call = Json.str(message, "call");
                if (call != null) {
                    mPrefs.addEndedCall(call, 0, now);
                    SocialNotifications.cancelCall(mApp, call);
                }
                break;
            }
            default:
                // Invites queued during do-not-disturb ("invites") come quietly; the rest is the launcher's.
                break;
        }
    }

    @Override
    public void onEnded(PresenceConnection connection, int code, boolean failed) {
        if (connection != mConnection) return;
        mConnection = null;
        mLive = false;
        // Signed out, deleted, replaced by newer sockets or restricted: not again in this game.
        if (code == 4001 || code == 4002 || code == 4000 || code == PresenceConnection.FORBIDDEN) {
            Log.i(TAG, "game socket ended: " + code);
            stop();
            return;
        }
        if (mStopped) return;
        long retryAfter = connection.retryAfterSeconds();
        long delay = code == 4008 ? mBackoff.atLeastMs(60) : retryAfter > 0 ? mBackoff.atLeastMs(retryAfter) : mBackoff.nextDelayMs();
        MAIN.postDelayed(mReconnect, delay);
    }

    // ---- what it announces ----

    private void onRequest(@Nullable SocialModel.Profile from) {
        if (from == null) return;
        mNames.put(from.id, from.name());
        if (!InGameRules.announceRequest(mDnd, mPrefs.wantsRequests())) return;
        Log.i(TAG, "in game: friend request shown");
        SocialNotifications.showRequestInGame(mApp, from.id, from.name(), gameIntent());
    }

    private void onInvite(@Nullable SocialModel.Invite invite, long now) {
        if (invite == null) return;
        long hubNow = now + mClockOffset;
        if (!InGameRules.announceInvite(mDnd, mPrefs.wantsInvites(), invite.expired(hubNow))) return;
        String name = mNames.get(invite.fromId);
        ConnectTarget target = "projects".equals(invite.where) ? ConnectTarget.PROJECTS
                : "other".equals(invite.where) ? ConnectTarget.server(invite.host) : null;
        long timeoutMs = invite.expires > hubNow ? (invite.expires - hubNow) * 1000L : 0;
        String tag = "invite:" + invite.fromId;
        PendingIntent connect = target == null ? null : connectIntent(target, tag, SocialNotifications.ID_INVITE);
        Log.i(TAG, "in game: invite shown" + (connect != null ? " with Connect" : ""));
        SocialNotifications.showInviteInGame(mApp, invite.fromId, name, invite.where, invite.host, timeoutMs,
                connect != null ? connect : gameIntent(), connect);
    }

    private void onCall(JsonObject message, long now) {
        SocialModel.Call call = SocialModel.Call.read(Json.obj(message, "call"));
        if (call == null) return;
        String where = InGameRules.callWhere(call);
        String seen = mCalls.put(call.id, where);
        long hubNow = Json.has(message, "now") ? Json.num(message, "now", now) : now + mClockOffset;
        boolean wanted = mTeams && mPrefs.wantsCallsLocally() && mPrefs.crewAudible(now);
        if (!InGameRules.announceCall(seen, where, Json.bool(message, "quiet", false), mMe.equals(call.from), mDnd, wanted,
                mPrefs.endedCalls(now).contains(call.id), call.until - hubNow)) {
            return;
        }
        Context c = LanguageManager.wrap(mApp);
        Map<String, String> crewNames = mPrefs.crewNames();
        List<String> named = new ArrayList<>();
        for (String crew : call.crews) {
            String name = crewNames.get(crew);
            if (name != null) named.add(name);
        }
        String title = SocialNotifications.callTitle(c, mNames.get(call.from), named, crewNames.size() > 1);
        String place = SocialNotifications.callPlace(c, call.place(), call.serverHost);
        ConnectTarget target = call.place() == SocialModel.Call.Place.PROJECTS ? ConnectTarget.PROJECTS
                : call.place() == SocialModel.Call.Place.OTHER ? ConnectTarget.server(call.serverHost) : null;
        PendingIntent connect = target == null ? null : connectIntent(target, "call:" + call.id, SocialNotifications.ID_CALL);
        Log.i(TAG, "in game: crew call shown" + (connect != null ? " with Connect" : ""));
        SocialNotifications.showCallInGame(mApp, call.id, title, place, (call.until - hubNow) * 1000L,
                connect != null ? connect : gameIntent(), connect);
    }

    /** A tap that brings the game back. */
    private PendingIntent gameIntent() {
        Intent intent = new Intent(mApp, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(mApp, REQUEST_GAME, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** "Bağlan": the game comes back and asks ("Oyun yeniden açılsın mı?"). */
    private PendingIntent connectIntent(ConnectTarget target, String tag, int id) {
        Intent intent = new Intent(mApp, MainActivity.class)
                .putExtra(GameConnect.EXTRA_CONNECT_ASK, target.wire())
                .putExtra(GameConnect.EXTRA_NOTIFICATION_TAG, tag)
                .putExtra(GameConnect.EXTRA_NOTIFICATION_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(mApp, tag.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
