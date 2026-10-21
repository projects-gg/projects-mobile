package gg.projects.mobile.social;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.R;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.FriendsData;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Request;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamNotifications;

/**
 * The friends feature on the phone (M19): the session, the friend list and crews from the service,
 * the presence socket, the Discord sign-in and the phone notifications. One instance in the
 * launcher's process; every method runs on the main thread, network calls on two worker threads.
 *
 * <p>The socket is open only while the launcher's screens are on screen (closed 10 s after they
 * leave, so a quick look at another app keeps it). Without a socket the service sends phone
 * notifications instead (soc6.md); while the app keeps one in the background it turns what the
 * socket brings into the same notifications. The list is read when a screen shows and is older than
 * a minute, and after a reconnect: nothing polls.
 *
 * <p>M20 (SOC7): several crews ({@code multi}), calls naming their crews and their server, crew chat
 * and direct messages ({@link SocialChat}), reports, moderators' notes and restricted accounts. The
 * phone does not start crew calls (CALL1: a call starts only from a multiplayer server in a game the
 * launcher reported, and the phone cannot say it is in game yet), so M19's socket that kept the
 * player's own call through the game is gone; answering calls works as before.
 */
public final class SocialService implements ProjectsContent.Listener, PresenceConnection.Listener {
    private static final String TAG = "ProjectsSocial";
    /** The list is read again only when it is older than this (the desktop's rule). */
    private static final long STALE_MS = 60_000;
    /** The socket stays this long after the launcher's screens leave. */
    private static final long BACKGROUND_GRACE_MS = 10_000;
    /** "Tekrar dene" waits this long between tries (the desktop's 10 s). */
    private static final long RETRY_GAP_MS = 10_000;

    public interface Listener {
        @MainThread
        void onSocialChanged();
    }

    public interface Result<T> {
        void ok(T value);

        void failed(SocialProblem problem);
    }

    /** What the sign-in screen shows. */
    public enum SignInStatus { IDLE, CHECKING, WAITING, CLAIMING, FAILED, UNAVAILABLE, RESTRICTED, SIGNUPS_CLOSED }

    /** The connection as the screens tell it. */
    public enum Connection { OFF, CONNECTING, LIVE, UNAVAILABLE, REPLACED }

    /** A screen that shows friends' news itself (no notification needed while it is up). */
    public enum Surface { HOME, FRIENDS, CREW, CHAT }

    private static SocialService sInstance;

    @MainThread
    public static synchronized SocialService get(Context context) {
        if (sInstance == null) sInstance = new SocialService(context.getApplicationContext());
        return sInstance;
    }

    /** True once the service exists in this process (no work is started by asking). */
    public static synchronized boolean exists() {
        return sInstance != null;
    }

    private final Context mApp;
    private final SocialDebug mDebug;
    private final SocialApi mApi;
    private final SessionStore mStore;
    private final SocialPrefs mPrefs;
    private final SocialState mState = new SocialState();
    private final SignInFlow mSignIn = new SignInFlow();
    private final SocialChat mChat;
    private final ExecutorService mIo = Executors.newFixedThreadPool(2);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();
    private final Backoff mBackoff = new Backoff(new Random());
    private final SecureRandom mRandom = new SecureRandom();
    private final int[] mSurfaces = new int[Surface.values().length];

    @Nullable
    private String mToken;
    private boolean mConfigFriends;
    private boolean mConfigTeams;
    private boolean mConfigChat;
    private boolean mConfigKnown;

    @Nullable
    private PresenceConnection mConnection;
    private boolean mLiveSocket;
    private int mUiStarted;
    private boolean mReplaced;
    private boolean mUnavailable;
    private long mLastFetch;
    private long mLastTry;
    private boolean mFetching;
    private SignInStatus mSignInStatus = SignInStatus.IDLE;
    /** The session ended on the service's side: the friends screen says so once. */
    private boolean mSignedOutRemotely;
    /**
     * MOD1: the session ended because the account is suspended (until then, Unix seconds) or banned
     * (0); null otherwise. The sign-in screen says so (and a claim refused the same way sets it).
     */
    @Nullable
    private Long mRestrictedUntil;
    /** The conversation on screen (the chat screen), null when none. */
    @Nullable
    private String mOpenConversation;
    private final Runnable mReconnect = this::ensureConnected;
    private final Runnable mBackgroundClose = this::closeIfIdle;
    private boolean mPushSyncing;
    /** A push sync was asked for while one ran: it runs again after. */
    private boolean mPushAgain;
    /** Socket messages that change what {@code GET /v1/friends} answers (a list read meanwhile is read again). */
    private int mListChanges;
    /** A crew order on its way (TEAM4): the list read meanwhile keeps it. */
    @Nullable
    private List<String> mOrderSent;

    private SocialService(Context app) {
        mApp = app;
        mDebug = SocialDebug.read(app);
        mApi = new SocialApi(mDebug.url, ProjectsContent.userAgent());
        mStore = new SessionStore(app, mDebug.override ? "session-debug.json" : "session.json");
        mPrefs = new SocialPrefs(app);
        // M19's socket that kept the player's own call in the game is not used any more (see the class doc).
        mPrefs.setOwnCallUntil(0);
        boolean[] config = mPrefs.lastConfig();
        mConfigFriends = config[0];
        mConfigTeams = config[1];
        mConfigChat = config[2];
        mChat = new SocialChat(new ChatHost());
        SessionStore.Saved saved = mStore.load();
        mToken = saved.token;
        mApi.setToken(mToken);
        if (mToken != null && saved.me != null) mState.setMe(Me.read(saved.me));
        if (mToken == null) {
            mSignIn.restore(saved.attempt, nowSeconds());
            if (mSignIn.phase() == SignInFlow.Phase.WAITING) mSignInStatus = SignInStatus.WAITING;
        } else if (saved.userId != null) {
            mChat.open(saved.userId);
        }
        ProjectsContent.addListener(this);
    }

    /** What the chat needs of this service. */
    private final class ChatHost implements SocialChat.Host {
        @Override
        public Context context() {
            return mApp;
        }

        @Override
        public SocialState state() {
            return mState;
        }

        @Override
        public SocialGate gate() {
            return SocialService.this.gate();
        }

        @Override
        public SocialPrefs prefs() {
            return mPrefs;
        }

        @Nullable
        @Override
        public PresenceConnection chatConnection() {
            PresenceConnection connection = mConnection;
            if (connection == null || !mLiveSocket || !mState.caps().contains("chat") || !gate().chat) return null;
            return connection;
        }

        @Override
        public boolean foreground() {
            return mUiStarted > 0;
        }

        @Nullable
        @Override
        public String openConversation() {
            return surfaceShown(Surface.CHAT) ? mOpenConversation : null;
        }

        @Override
        public void changed() {
            SocialService.this.changed();
        }
    }

    // ---- reading ----

    public SocialState state() {
        return mState;
    }

    public SocialPrefs prefs() {
        return mPrefs;
    }

    public SocialChat chat() {
        return mChat;
    }

    public boolean signedIn() {
        return mToken != null;
    }

    public String serviceBase() {
        return mApi.base();
    }

    public SocialGate gate() {
        boolean friends = mConfigFriends || mDebug.forceFriends;
        boolean teams = mConfigTeams || mDebug.forceTeams;
        Me me = mState.me();
        return SocialGate.of(friends, teams, chatEnabled(), mState.limits().teamMembers, mState.teamFeatures(), mState.features(),
                BuildConfig.PROJECTS_FIREBASE || mDebug.pushToken != null, me != null && me.moderation);
    }

    /** Studio's {@code features.chat} (or the debug override): the socket asks for chat only then. */
    public boolean chatEnabled() {
        return (mConfigFriends || mDebug.forceFriends) && (mConfigChat || mDebug.forceChat);
    }

    /** Debug builds: a made-up FCM token to register instead of Firebase's (projects-debug.json). */
    @Nullable
    String debugPushToken() {
        return mDebug.pushToken;
    }

    /** Friends are on (the signed config or a debug override); nothing of the feature shows otherwise. */
    public boolean enabled() {
        return mConfigFriends || mDebug.forceFriends;
    }

    public SignInStatus signInStatus() {
        if (mSignInStatus == SignInStatus.WAITING && !mSignIn.waiting(nowSeconds())) mSignInStatus = SignInStatus.IDLE;
        return mSignInStatus;
    }

    public Connection connection() {
        if (mToken == null) return Connection.OFF;
        if (mReplaced) return Connection.REPLACED;
        if (mLiveSocket) return Connection.LIVE;
        if (mUnavailable) return Connection.UNAVAILABLE;
        return mConnection != null || mFetching ? Connection.CONNECTING : Connection.OFF;
    }

    /** The session ended elsewhere since the player last looked (cleared by reading it). */
    public boolean takeSignedOutRemotely() {
        boolean was = mSignedOutRemotely;
        mSignedOutRemotely = false;
        return was;
    }

    /** Suspended until then (Unix seconds), banned (0), or null: what the sign-in screen says. */
    @Nullable
    public Long restrictedUntil() {
        return mRestrictedUntil;
    }

    public HomeLine homeLine() {
        return HomeLine.of(signedIn(), mUnavailable, mState, mPrefs.favourites(), gate().teams, nowSeconds(), mChat.unreadDirect());
    }

    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    @MainThread
    public void addListener(Listener listener) {
        if (!mListeners.contains(listener)) mListeners.add(listener);
    }

    @MainThread
    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void changed() {
        for (Listener listener : new ArrayList<>(mListeners)) listener.onSocialChanged();
    }

    // ---- the signed config ----

    @Override
    public void onContent(@Nullable Snapshot snapshot, boolean refreshing) {
        if (snapshot == null || snapshot.config == null) return;
        boolean friends = snapshot.config.friendsFeature;
        boolean teams = snapshot.config.teamsFeature;
        boolean chat = snapshot.config.chatFeature;
        boolean chatChanged = chat != mConfigChat;
        boolean same = friends == mConfigFriends && teams == mConfigTeams && !chatChanged;
        if (mConfigKnown && same) return;
        mConfigKnown = true;
        mConfigFriends = friends;
        mConfigTeams = teams;
        mConfigChat = chat;
        mPrefs.setLastConfig(friends, teams, chat);
        if (same) return;
        if (!enabled()) {
            // Turned off: the session is kept (the desktop's rule), the socket and the phone's pushes go.
            closeConnection();
            if (mToken != null) syncPush();
        } else {
            // Chat came or went: the socket asks again with the right capabilities.
            if (chatChanged && mConnection != null) closeConnection();
            ensureConnected();
            if (mUiStarted > 0) refresh(false);
            if (mToken != null) syncPush();
        }
        changed();
    }

    // ---- screens on and off ----

    /** The launcher's screens came to the front. */
    @MainThread
    public void uiStarted() {
        mUiStarted++;
        mMain.removeCallbacks(mBackgroundClose);
        if (mToken != null && enabled()) {
            ensureConnected();
            refresh(false);
            syncPush();
        }
        SocialNotifications.cancelSeen(mApp, mState, surfaceShown(Surface.FRIENDS), surfaceShown(Surface.CREW));
    }

    @MainThread
    public void uiStopped() {
        mUiStarted = Math.max(0, mUiStarted - 1);
        if (mUiStarted == 0) {
            mMain.removeCallbacks(mBackgroundClose);
            mMain.postDelayed(mBackgroundClose, BACKGROUND_GRACE_MS);
        }
    }

    public boolean foreground() {
        return mUiStarted > 0;
    }

    @MainThread
    public void surfaceShown(Surface surface, boolean shown) {
        mSurfaces[surface.ordinal()] = Math.max(0, mSurfaces[surface.ordinal()] + (shown ? 1 : -1));
        if (shown) SocialNotifications.cancelSeen(mApp, mState, surface == Surface.FRIENDS, surface == Surface.CREW);
    }

    /** M20: the chat screen shows {@code conv} (null: it left). */
    @MainThread
    public void conversationShown(@Nullable String conv) {
        mOpenConversation = conv;
        surfaceShown(Surface.CHAT, conv != null);
        if (conv != null) mChat.seen(conv);
    }

    private boolean surfaceShown(Surface surface) {
        return mUiStarted > 0 && mSurfaces[surface.ordinal()] > 0;
    }

    /** No screen: the socket goes, the phone's notifications take over. */
    private void closeIfIdle() {
        if (mUiStarted > 0) return;
        closeConnection();
        changed();
    }

    /**
     * M19 handed the player's own crew call to the game's process here; the phone no longer starts
     * calls (CALL1), so nothing is handed over and the launcher's process goes as upstream ends it.
     */
    @MainThread
    public static long handOverOwnCall(Context context) {
        return 0;
    }

    /** M19's keeper of the player's own call in the game's process: nothing to keep any more (CALL1). */
    @MainThread
    public static void keepOwnCallInGame(Context context) {
        try {
            SocialPrefs prefs = new SocialPrefs(context);
            if (prefs.ownCallUntil() != 0) prefs.setOwnCallUntil(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "crew call record not cleared", e);
        }
    }

    /** A push came while the app ran without a socket: the list is read again when its screens are up. */
    @MainThread
    public void pushArrived() {
        mLastFetch = 0;
        if (mUiStarted > 0) refresh(false);
    }

    // ---- the presence socket ----

    /** What the socket says it understands (README "Capabilities"): older services ignore it. */
    private List<String> caps() {
        List<String> caps = new ArrayList<>();
        caps.add("callServer");
        caps.add("crews");
        if (chatEnabled()) caps.add("chat");
        return caps;
    }

    @MainThread
    private void ensureConnected() {
        mMain.removeCallbacks(mReconnect);
        if (mToken == null || !enabled() || mReplaced || mConnection != null) return;
        if (mUiStarted == 0) return;
        mConnection = PresenceConnection.open(mApi, mToken, caps(), this);
        changed();
    }

    private void closeConnection() {
        mMain.removeCallbacks(mReconnect);
        PresenceConnection connection = mConnection;
        mConnection = null;
        mLiveSocket = false;
        if (connection != null) connection.close();
        mState.setOffline();
        mChat.disconnected();
    }

    /** "Yeniden bağlan" after the account was opened on another device. */
    @MainThread
    public void reconnect() {
        mReplaced = false;
        mBackoff.reset();
        ensureConnected();
        refresh(true);
        changed();
    }

    @Override
    public void onOpen(PresenceConnection connection) {
        // The hub speaks first ("hello").
    }

    @Override
    public void onMessage(PresenceConnection connection, JsonObject message) {
        if (connection != mConnection) return;
        long now = nowSeconds();
        String type = Json.str(message, "t", "");
        if (mChat.onFrame(message)) {
            changed();
            return;
        }
        if (type.equals("hello")) {
            mLiveSocket = true;
            mUnavailable = false;
            mBackoff.healthy();
            // After a reconnect the list may have moved on.
            refresh(false);
        }
        switch (type) {
            case "friend_added":
            case "friend_removed":
            case "request":
            case "request_cancelled":
            case "team":
            case "team_invite":
            case "team_notifications":
            case "crew_notifications":
            case "crew_order":
                mListChanges++;
                break;
            default:
                break;
        }
        List<SocialState.Notice> notices = mState.apply(message, now);
        if (type.equals("team_notifications") || type.equals("hello")) mPrefs.setCrewSettings(mState.teamNotifications());
        if (type.equals("team") || type.equals("crew_order")) rememberCrewNames();
        handleNotices(notices);
        if (type.equals("hello")) mChat.connected();
        changed();
    }

    @Override
    public void onEnded(PresenceConnection connection, int code, boolean failed) {
        if (connection != mConnection) return;
        mConnection = null;
        mLiveSocket = false;
        mState.setOffline();
        mChat.disconnected();
        switch (code) {
            case 4001: {
                // Signed out, revoked, expired or (after "bye") restricted: sign in again, no reconnect.
                Long restricted = mState.restrictedUntil();
                sessionEnded(false, restricted);
                return;
            }
            case 4002:
                sessionEnded(true, null);
                return;
            case 4000:
                // Replaced by a newer connection of the same account: no automatic reconnect.
                mReplaced = true;
                changed();
                return;
            case PresenceConnection.FORBIDDEN:
                // A restricted account: the list's answer says until when (and ends the session).
                mLastTry = 0;
                refresh(true);
                return;
            default:
                break;
        }
        if (failed) mUnavailable = true;
        long retryAfter = connection.retryAfterSeconds();
        long delay = code == 4008 ? mBackoff.atLeastMs(60) : retryAfter > 0 ? mBackoff.atLeastMs(retryAfter) : mBackoff.nextDelayMs();
        mMain.postDelayed(mReconnect, delay);
        changed();
    }

    // ---- reading the list ----

    /** Reads {@code /v1/me} and {@code /v1/friends} unless they are fresh (force: "Tekrar dene"). */
    @MainThread
    public void refresh(boolean force) {
        if (mToken == null || !enabled() || mFetching) return;
        long now = System.currentTimeMillis();
        if (!force && now - mLastFetch < STALE_MS && mState.loaded()) return;
        if (force && now - mLastTry < RETRY_GAP_MS && mState.loaded()) return;
        mLastTry = now;
        mFetching = true;
        changed();
        final String token = mToken;
        final int changesBefore = mListChanges;
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                final JsonObject me = mApi.me();
                final JsonObject friends = mApi.friends();
                mMain.post(() -> {
                    mFetching = false;
                    // An answer for a session that is gone (signed out, or signed in again meanwhile) is dropped.
                    if (!token.equals(mToken)) return;
                    Me parsed = Me.read(me);
                    if (parsed != null) {
                        mState.setMe(parsed);
                        mStore.save(mToken, parsed.profile.id, me, null);
                        mChat.open(parsed.profile.id);
                    }
                    List<String> order = mOrderSent;
                    mState.applyFriends(FriendsData.read(friends));
                    // An order the player just chose wins over a list read before it reached the service.
                    if (order != null) mState.setCrewOrder(order);
                    mPrefs.setCrewSettings(mState.teamNotifications());
                    rememberCrewNames();
                    // CHAT1: the list says whether the service has chat; a socket that said hello before it
                    // syncs now (and then sends what waits).
                    mChat.connected();
                    mLastFetch = System.currentTimeMillis();
                    if (!mLiveSocket && mConnection == null) mUnavailable = false;
                    else if (mLiveSocket) mUnavailable = false;
                    changed();
                    syncPush();
                    ensureConnected();
                    if (mListChanges != changesBefore) {
                        // The socket changed the lists while this was read: read once more (the newer answer wins).
                        mLastFetch = 0;
                        refresh(false);
                    }
                });
            } catch (final SocialProblem problem) {
                mMain.post(() -> {
                    mFetching = false;
                    if (!token.equals(mToken)) return;
                    if (problem.kind == SocialProblem.Kind.SIGNED_OUT) {
                        sessionEnded(false, null);
                        return;
                    }
                    if (problem.restricted()) {
                        sessionEnded(false, problem.until == null ? 0L : problem.until);
                        return;
                    }
                    mUnavailable = true;
                    changed();
                });
            } finally {
                mApi.bind(null);
            }
        });
    }

    /** The crews' names for a call's push while the app sleeps (id → name). */
    private void rememberCrewNames() {
        Map<String, String> names = new HashMap<>();
        for (Crew c : mState.crews()) names.put(c.id(), c.team.name);
        mPrefs.setCrewNames(names);
    }

    // ---- sign-in ----

    /** "Discord ile giriş yap": the browser opens only when the service answers. */
    @MainThread
    public void beginSignIn(final Activity activity) {
        if (mToken != null || mSignInStatus == SignInStatus.CHECKING || mSignInStatus == SignInStatus.CLAIMING) return;
        mSignIn.starting();
        mSignInStatus = SignInStatus.CHECKING;
        mRestrictedUntil = null;
        changed();
        mIo.execute(() -> {
            try {
                mApi.health();
                mMain.post(() -> {
                    if (mSignInStatus != SignInStatus.CHECKING) return;
                    SignInFlow.Attempt attempt = mSignIn.begin(mRandom, nowSeconds());
                    mStore.save(null, null, null, attempt);
                    mSignInStatus = SignInStatus.WAITING;
                    changed();
                    openSignInPage(activity, attempt);
                });
            } catch (SocialProblem problem) {
                mMain.post(() -> {
                    mSignIn.reset();
                    mSignInStatus = SignInStatus.UNAVAILABLE;
                    changed();
                });
            }
        });
    }

    /** "Tarayıcıyı yeniden aç": the same attempt's page again. */
    @MainThread
    public void reopenSignInPage(Activity activity) {
        SignInFlow.Attempt attempt = mSignIn.attempt();
        if (attempt != null && mSignIn.waiting(nowSeconds())) openSignInPage(activity, attempt);
    }

    private void openSignInPage(Activity activity, SignInFlow.Attempt attempt) {
        String url = SignInFlow.startUrl(mApi.base(), attempt.challenge);
        if (mDebug.logBrowser) {
            // Debug builds on the emulator: a script plays the browser (no browser runs there).
            Log.i(TAG, "sign-in page: " + url);
            return;
        }
        openBrowser(activity, url);
    }

    /**
     * Opens a page in a Custom Tab when the browser has them (the extras the support library sets),
     * else in the browser: either way the browser's own cookies carry the sign-in.
     */
    static void openBrowser(Activity activity, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        Bundle extras = new Bundle();
        extras.putBinder("android.support.customtabs.extra.SESSION", null);
        intent.putExtras(extras);
        intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", ContextCompat.getColor(activity, R.color.projects_obsidian_900));
        intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
            } catch (RuntimeException again) {
                Toast.makeText(activity, R.string.projects_link_no_app, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @MainThread
    public void cancelSignIn() {
        mSignIn.reset();
        mStore.save(mToken, mState.myId(), null, null);
        mSignInStatus = SignInStatus.IDLE;
        changed();
    }

    /**
     * {@code projects://auth/done?c=<code>} came back: claimed only for this app's own waiting sign-in.
     * False when it was not for one (a link any web page can open): then nothing else happens either.
     */
    @MainThread
    public boolean onAuthLink(@Nullable String code) {
        final SignInFlow.Attempt attempt = mSignIn.onDeepLink(code, nowSeconds());
        if (attempt == null || code == null) {
            Log.i(TAG, "sign-in link ignored: no sign-in of this app waits");
            return false;
        }
        mStore.save(null, null, null, null);
        mSignInStatus = SignInStatus.CLAIMING;
        changed();
        final String device = deviceLabel();
        mIo.execute(() -> {
            try {
                final JsonObject answer = mApi.claim(attempt.verifier, code, device);
                mMain.post(() -> signedIn(answer));
            } catch (final SocialProblem problem) {
                mMain.post(() -> {
                    mSignIn.reset();
                    if (problem.restricted()) {
                        // MOD1: suspended or banned; the sign-in screen says until when, nothing technical.
                        mRestrictedUntil = problem.until == null ? 0L : problem.until;
                        mSignInStatus = SignInStatus.RESTRICTED;
                    } else if (problem.kind == SocialProblem.Kind.SIGNUPS_CLOSED) {
                        mSignInStatus = SignInStatus.SIGNUPS_CLOSED;
                    } else {
                        mSignInStatus = problem.kind == SocialProblem.Kind.UNAVAILABLE ? SignInStatus.UNAVAILABLE : SignInStatus.FAILED;
                    }
                    changed();
                });
            }
        });
        return true;
    }

    /** A friend link ({@code projects://friend/<code>}) opened before signing in: kept for after the sign-in. */
    @Nullable
    private String mPendingFriendCode;

    @MainThread
    public void setPendingFriendCode(@Nullable String code) {
        mPendingFriendCode = code;
    }

    /** The friend code a link brought before the sign-in, once signed in (cleared by reading it). */
    @MainThread
    @Nullable
    public String takePendingFriendCode() {
        if (mToken == null) return null;
        String code = mPendingFriendCode;
        mPendingFriendCode = null;
        return code;
    }

    private void signedIn(JsonObject answer) {
        String token = Json.str(answer, "token");
        Me me = Me.read(Json.obj(answer, "me"));
        mSignIn.reset();
        if (token == null || me == null) {
            mSignInStatus = SignInStatus.FAILED;
            changed();
            return;
        }
        mToken = token;
        mApi.setToken(token);
        mState.clear();
        mState.setMe(me);
        mStore.save(token, me.profile.id, Json.obj(answer, "me"), null);
        mSignInStatus = SignInStatus.IDLE;
        mRestrictedUntil = null;
        mUnavailable = false;
        mReplaced = false;
        mLastFetch = 0;
        mBackoff.reset();
        mChat.open(me.profile.id);
        changed();
        refresh(true);
        ensureConnected();
    }

    /** What the sessions list calls this phone: its maker and model ("Samsung SM-A515F"). */
    static String deviceLabel() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String label = model.toLowerCase(java.util.Locale.ROOT).startsWith(maker.toLowerCase(java.util.Locale.ROOT))
                ? model : (maker.isEmpty() ? model : capitalize(maker) + " " + model);
        label = label.replaceAll("\\s+", " ").trim();
        if (label.isEmpty()) label = "Android";
        return label.length() > 64 ? label.substring(0, 64) : label;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** "Çıkış yap": the token goes from the phone at once; the service is told when it answers. */
    @MainThread
    public void signOut() {
        final String token = mToken;
        if (token == null) return;
        mIo.execute(() -> {
            // The token leaves the phone at once (below): the request carries it itself.
            mApi.bind(token);
            try {
                // Sign-out also ends this session's push registration on the service.
                mApi.logout();
            } catch (SocialProblem ignored) {
                // Signed out on the phone anyway; the session expires on the service by itself.
            } finally {
                mApi.bind(null);
            }
        });
        forgetSession();
        PushRegistrar.forget(mApp);
        changed();
    }

    /** "Verilerimi sil": the account and everything in it, then signed out (its chat history leaves the phone too). */
    @MainThread
    public void deleteAccount(final Result<Void> result) {
        final String token = mToken;
        final String userId = signedInId();
        if (token == null) return;
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                mApi.deleteAccount();
                mMain.post(() -> {
                    if (token.equals(mToken)) {
                        if (userId != null) mChat.deleteLocal(userId);
                        forgetSession();
                        PushRegistrar.forget(mApp);
                        changed();
                    }
                    result.ok(null);
                });
            } catch (final SocialProblem problem) {
                mMain.post(() -> result.failed(problem));
            } finally {
                mApi.bind(null);
            }
        });
    }

    /** @param restricted suspended until then, banned (0), or null for any other end */
    private void sessionEnded(boolean deleted, @Nullable Long restricted) {
        Log.i(TAG, deleted ? "account deleted elsewhere" : restricted != null ? "account restricted" : "session ended");
        forgetSession();
        mSignedOutRemotely = !deleted && restricted == null;
        mRestrictedUntil = restricted;
        if (restricted != null) mSignInStatus = SignInStatus.RESTRICTED;
        changed();
    }

    private void forgetSession() {
        closeConnection();
        mToken = null;
        mApi.setToken(null);
        mState.clear();
        mStore.save(null, null, null, null);
        mPrefs.clearAccount();
        mSignIn.reset();
        mSignInStatus = SignInStatus.IDLE;
        mUnavailable = false;
        mReplaced = false;
        mLastFetch = 0;
        mOrderSent = null;
        mChat.close();
        SocialNotifications.cancelAll(mApp);
    }

    // ---- phone notifications ----

    /** Registers this phone for pushes, refreshes or ends its registration, as the switches say. */
    @MainThread
    public void syncPush() {
        if (mPushSyncing) {
            // A switch changed (or the list came) while one ran: once more after it.
            mPushAgain = true;
            return;
        }
        mPushSyncing = true;
        PushRegistrar.sync(mApp, this, () -> {
            mPushSyncing = false;
            if (mPushAgain) {
                mPushAgain = false;
                syncPush();
            }
        });
    }

    /** FCM gave this phone a new token: registered once the list says what the service announces. */
    @MainThread
    public void onNewPushToken() {
        mPrefs.setPushRegistered("");
        if (mToken == null || !enabled()) return;
        if (mState.loaded()) syncPush();
        else refresh(false);
    }

    /** What the push registration asks for, given the switches, the channels and the service. */
    List<String> wantedKinds() {
        List<String> kinds = new ArrayList<>();
        if (mToken == null || !enabled() || !SocialNotifications.canNotify(mApp)) return kinds;
        if (mPrefs.wantsRequests() && SocialNotifications.channelOn(mApp, SocialNotifications.CHANNEL_REQUESTS)) kinds.add(PushMessage.REQUEST);
        if (mPrefs.wantsInvites() && SocialNotifications.channelOn(mApp, SocialNotifications.CHANNEL_INVITES)) kinds.add(PushMessage.INVITE);
        SocialGate gate = gate();
        boolean calls = gate.mute ? mState.teamNotifications().calls : mPrefs.wantsCallsLocally();
        if (gate.teams && calls && SocialNotifications.channelOn(mApp, SocialNotifications.CHANNEL_CALLS)) kinds.add(PushMessage.CALL);
        // CHAT1: "message" must be asked for by name (an older registration never gets them).
        if (gate.chat && mPrefs.wantsMessages() && SocialNotifications.channelOn(mApp, SocialNotifications.CHANNEL_MESSAGES)) {
            kinds.add(PushMessage.MESSAGE);
        }
        return kinds;
    }

    SocialApi api() {
        return mApi;
    }

    @Nullable
    String token() {
        return mToken;
    }

    ExecutorService io() {
        return mIo;
    }

    Handler main() {
        return mMain;
    }

    /** The kinds the player keeps on, for pushes that come in (whatever the channels say: Android drops those). */
    Set<String> wantedForIncoming() {
        Set<String> kinds = new HashSet<>();
        if (mPrefs.wantsRequests()) kinds.add(PushMessage.REQUEST);
        if (mPrefs.wantsInvites()) kinds.add(PushMessage.INVITE);
        boolean calls = gate().mute ? mState.teamNotifications().calls : mPrefs.wantsCallsLocally();
        if (calls) kinds.add(PushMessage.CALL);
        if (mPrefs.wantsMessages()) kinds.add(PushMessage.MESSAGE);
        return kinds;
    }

    private void handleNotices(List<SocialState.Notice> notices) {
        if (notices.isEmpty()) return;
        long now = nowSeconds();
        boolean foreground = mUiStarted > 0;
        Context context = LanguageManager.wrap(mApp);
        for (SocialState.Notice notice : notices) {
            switch (notice.kind) {
                case REQUEST:
                    if (!mPrefs.wantsRequests() || surfaceShown(Surface.FRIENDS) || notice.who == null) break;
                    SocialNotifications.showRequest(context, notice.who.id, notice.who.name());
                    break;
                case INVITE:
                    if (!mPrefs.wantsInvites() || notice.invite == null) break;
                    if (surfaceShown(Surface.FRIENDS) || surfaceShown(Surface.HOME)) break;
                    SocialNotifications.showInvite(context, notice.invite.fromId,
                            notice.who == null ? null : notice.who.name(), notice.invite.id,
                            notice.invite.where, notice.invite.host, (notice.invite.expires - now) * 1000L);
                    break;
                case CALL: {
                    Call call = notice.call;
                    if (call == null || !gate().calls) break;
                    boolean audible = gate().mute ? mState.callAudible(call, now) : mPrefs.wantsCallsLocally();
                    if (!audible || surfaceShown(Surface.CREW) || surfaceShown(Surface.HOME) || surfaceShown(Surface.FRIENDS)) break;
                    List<Crew> crews = mState.callCrews(call);
                    List<String> names = new ArrayList<>();
                    for (Crew c : crews) names.add(c.team.name);
                    String title = SocialNotifications.callTitle(context, notice.who == null ? null : notice.who.name(), names,
                            mState.crews().size() > 1);
                    SocialNotifications.showCall(context, call.id, crews.isEmpty() ? null : crews.get(0).id(), title,
                            SocialNotifications.callPlace(context, call.place(), call.serverHost), mState.secondsLeft(call, now) * 1000L);
                    break;
                }
                case CALL_ENDED:
                    if (notice.callId != null) {
                        mPrefs.addEndedCall(notice.callId, 0, now);
                        SocialNotifications.cancelCall(mApp, notice.callId);
                    }
                    break;
                case FRIEND_ADDED:
                    if (foreground && notice.who != null) {
                        Toast.makeText(context, context.getString(R.string.projects_social_friend_added, notice.who.name()), Toast.LENGTH_SHORT).show();
                    }
                    break;
                case TEAM_INVITE:
                    if (foreground && notice.teamInvite != null && gate().teams && !surfaceShown(Surface.FRIENDS) && !surfaceShown(Surface.CREW)) {
                        Toast.makeText(context, context.getString(R.string.projects_crew_invited_you,
                                notice.teamInvite.from.name(), notice.teamInvite.team.name), Toast.LENGTH_LONG).show();
                    }
                    break;
                case TEAM_GONE:
                    if (foreground && !notice.byMe && gate().teams) {
                        String text;
                        if ("disbanded".equals(notice.reason) && notice.byStaff) {
                            text = notice.teamName == null || notice.teamName.isEmpty()
                                    ? context.getString(R.string.projects_mod_crew_disbanded_unnamed)
                                    : context.getString(R.string.projects_mod_crew_disbanded, notice.teamName);
                        } else if ("kicked".equals(notice.reason)) {
                            text = context.getString(R.string.projects_crew_kicked, notice.teamName);
                        } else if ("disbanded".equals(notice.reason)) {
                            text = context.getString(R.string.projects_crew_disbanded, notice.teamName);
                        } else {
                            text = null;
                        }
                        if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show();
                    }
                    break;
                case RESTRICTED:
                    // The socket closes with 4001 next: onEnded ends the session with this.
                    break;
                case MOD_NOTICE:
                    // The screens show the note once (they read me.notices).
                    break;
                default:
                    break;
            }
        }
    }

    // ---- actions (main thread in, main thread out) ----

    private interface Call0<T> {
        T run() throws SocialProblem;
    }

    private <T> void run(final Call0<T> call, final Result<T> result, final OnOk<T> apply) {
        final String token = mToken;
        if (token == null) {
            result.failed(new SocialProblem(SocialProblem.Kind.SIGNED_OUT, 401, null, 0));
            return;
        }
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                final T value = call.run();
                mMain.post(() -> {
                    // The session that asked is gone (signed out, or another sign-in): its answer changes nothing.
                    if (!token.equals(mToken)) return;
                    if (apply != null) apply.apply(value);
                    changed();
                    result.ok(value);
                });
            } catch (final SocialProblem problem) {
                mMain.post(() -> {
                    if (!token.equals(mToken)) return;
                    if (problem.kind == SocialProblem.Kind.SIGNED_OUT) sessionEnded(false, null);
                    else if (problem.restricted()) sessionEnded(false, problem.until == null ? 0L : problem.until);
                    result.failed(problem);
                });
            } finally {
                mApi.bind(null);
            }
        });
    }

    private interface OnOk<T> {
        void apply(T value);
    }

    public void lookup(String code, Result<JsonObject> result) {
        run(() -> mApi.lookup(code), result, null);
    }

    /** "İstek gönder": a request, or a friendship at once when they had asked first. */
    public void sendRequest(String code, Result<JsonObject> result) {
        run(() -> mApi.request(code), result, answer -> {
            if ("friends".equals(Json.str(answer, "status"))) {
                Friend friend = Friend.read(Json.obj(answer, "friend"));
                if (friend != null) mState.addFriend(friend);
            } else {
                Profile user = Profile.read(Json.obj(answer, "user"));
                if (user != null) mState.addOutgoing(new Request(user, Json.num(answer, "sentAt", nowSeconds())));
            }
        });
    }

    public void accept(final String from, Result<JsonObject> result) {
        run(() -> mApi.accept(from), result, answer -> {
            Friend friend = Friend.read(Json.obj(answer, "friend"));
            if (friend != null) mState.addFriend(friend);
            else mState.removeIncoming(from);
        });
    }

    public void decline(final String from, Result<Void> result) {
        run(() -> {
            mApi.decline(from);
            return null;
        }, result, value -> mState.removeIncoming(from));
    }

    public void cancelRequest(final String to, Result<Void> result) {
        run(() -> {
            mApi.cancelRequest(to);
            return null;
        }, result, value -> mState.removeOutgoing(to));
    }

    public void removeFriend(final String id, Result<Void> result) {
        run(() -> {
            mApi.removeFriend(id);
            return null;
        }, result, value -> {
            mState.removeFriend(id);
            mPrefs.setFavourite(id, false);
        });
    }

    public void block(final Profile profile, Result<Void> result) {
        run(() -> {
            mApi.block(profile.id);
            return null;
        }, result, value -> {
            mState.block(profile);
            mPrefs.setFavourite(profile.id, false);
        });
    }

    public void unblock(final String id, Result<Void> result) {
        run(() -> {
            mApi.unblock(id);
            return null;
        }, result, value -> mState.unblock(id));
    }

    public void setStatus(final String status, Result<JsonObject> result) {
        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        patchMe(body, result);
    }

    public void setPrivacy(String key, boolean on, Result<JsonObject> result) {
        JsonObject body = new JsonObject();
        body.addProperty(key, on);
        patchMe(body, result);
    }

    private void patchMe(final JsonObject body, Result<JsonObject> result) {
        run(() -> mApi.patchMe(body), result, answer -> {
            Me me = Me.read(answer);
            if (me != null) {
                mState.setMe(me);
                mStore.save(mToken, me.profile.id, answer, null);
            }
        });
    }

    public void rotateCode(Result<String> result) {
        run(mApi::rotateFriendCode, result, code -> refreshMeOnly());
    }

    private void refreshMeOnly() {
        final String token = mToken;
        if (token == null) return;
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                final JsonObject me = mApi.me();
                mMain.post(() -> {
                    Me parsed = Me.read(me);
                    if (parsed != null && token.equals(mToken)) {
                        mState.setMe(parsed);
                        mStore.save(mToken, parsed.profile.id, me, null);
                        changed();
                    }
                });
            } catch (SocialProblem ignored) {
                // The next refresh brings it.
            } finally {
                mApi.bind(null);
            }
        });
    }

    public void toggleFavourite(String id) {
        mPrefs.setFavourite(id, !mPrefs.favourites().contains(id));
        changed();
    }

    // ---- reports and notices (MOD1) ----

    /**
     * "Rapor et": the report goes; with "Engelle" ({@code block} in the body) the player is blocked here
     * too (the service blocks with it, best effort).
     */
    public void report(final JsonObject body, @Nullable final Profile block, Result<JsonObject> result) {
        run(() -> mApi.report(body), result, answer -> {
            if (block != null && Json.bool(body, "block", false)) {
                mState.block(block);
                mPrefs.setFavourite(block.id, false);
            }
        });
    }

    /** The player saw these moderators' notes: they go here at once, from the service when it answers. */
    @MainThread
    public void noticesSeen(final Set<String> ids) {
        if (ids.isEmpty()) return;
        mState.noticesSeen(ids);
        changed();
        final String token = mToken;
        if (token == null) return;
        final List<String> list = new ArrayList<>(ids);
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                mApi.noticesSeen(list);
            } catch (SocialProblem problem) {
                Log.i(TAG, "notices not marked seen: " + problem.kind);
            } finally {
                mApi.bind(null);
            }
        });
    }

    // ---- invites ("Birlikte oyna") ----

    public void dismissInvite(String inviteId) {
        mState.dismissInvite(inviteId);
        changed();
    }

    // ---- crews ----

    /** A crew's answer ({@code {team, invited}}): with several crews that crew, else the player's one crew. */
    private void applyTeamAnswer(JsonObject answer) {
        Team team = Team.read(Json.obj(answer, "team"));
        Set<String> invited = new HashSet<>();
        for (String id : Json.strings(answer, "invited")) if (SocialModel.isUserId(id)) invited.add(id);
        if (gate().multi) {
            if (team != null) mState.upsertCrew(team, invited);
        } else {
            mState.setTeam(team, invited);
        }
        rememberCrewNames();
    }

    public void lookupTeam(String code, Result<JsonObject> result) {
        final boolean multi = gate().multi;
        run(() -> mApi.lookupTeam(code, multi), result, null);
    }

    public void joinTeam(String code, Result<JsonObject> result) {
        final boolean multi = gate().multi;
        run(() -> mApi.joinTeam(code, multi), result, this::applyTeamAnswer);
    }

    public void acceptTeamInvite(final String teamId, Result<JsonObject> result) {
        final boolean multi = gate().multi;
        run(() -> mApi.acceptTeamInvite(teamId, multi), result, answer -> {
            mState.removeTeamInvite(teamId);
            applyTeamAnswer(answer);
        });
    }

    public void declineTeamInvite(final String teamId, Result<Void> result) {
        run(() -> {
            mApi.declineTeamInvite(teamId);
            return null;
        }, result, value -> mState.removeTeamInvite(teamId));
    }

    public void createTeam(JsonObject body, Result<JsonObject> result) {
        // A second crew (and every one with several crews) needs "multi"; an older service refuses the field.
        if (gate().multi) body.addProperty("multi", true);
        run(() -> mApi.createTeam(body), result, this::applyTeamAnswer);
    }

    public void leaveTeam(final String teamId, Result<Void> result) {
        run(() -> {
            mApi.leaveTeam(teamId);
            return null;
        }, result, value -> leftCrew(teamId));
    }

    public void disbandTeam(final String teamId, Result<Void> result) {
        run(() -> {
            mApi.disbandTeam(teamId);
            return null;
        }, result, value -> leftCrew(teamId));
    }

    private void leftCrew(String teamId) {
        if (gate().multi) mState.removeCrew(teamId);
        else mState.setTeam(null, null);
        rememberCrewNames();
    }

    public void inviteToTeam(final String teamId, final String friendId, Result<Void> result) {
        run(() -> {
            mApi.inviteToTeam(teamId, friendId);
            return null;
        }, result, value -> mState.addCrewInvited(teamId, friendId));
    }

    public void removeMember(final String teamId, final String userId, Result<Void> result) {
        run(() -> {
            mApi.removeMember(teamId, userId);
            return null;
        }, result, value -> refreshTeam());
    }

    public void handOver(final String teamId, final String to, Result<JsonObject> result) {
        run(() -> mApi.handOver(teamId, to), result, this::applyTeamAnswer);
    }

    private void refreshTeam() {
        if (gate().multi) {
            // The crews' answer of several crews is the list's: read it whole.
            mLastFetch = 0;
            refresh(false);
            return;
        }
        final String token = mToken;
        if (token == null) return;
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                final JsonObject answer = mApi.teams();
                mMain.post(() -> {
                    if (!token.equals(mToken)) return;
                    applyTeamAnswer(answer);
                    changed();
                });
            } catch (SocialProblem ignored) {
                // The socket's "team" message or the next refresh brings it.
            } finally {
                mApi.bind(null);
            }
        });
    }

    /**
     * TEAM4: the crews in a new order (the first is the primary crew), shown at once; a refusal
     * brings the order back and reads the list again ({@code crews_changed}).
     */
    @MainThread
    public void setCrewOrder(final List<String> order, final Result<Void> result) {
        final List<String> before = mState.crewIds();
        if (before.equals(order)) {
            result.ok(null);
            return;
        }
        mState.setCrewOrder(order);
        mOrderSent = new ArrayList<>(order);
        changed();
        run(() -> {
            mApi.crewOrder(order);
            return null;
        }, new Result<Void>() {
            @Override
            public void ok(Void value) {
                if (order.equals(mOrderSent)) mOrderSent = null;
                result.ok(null);
            }

            @Override
            public void failed(SocialProblem problem) {
                if (order.equals(mOrderSent)) mOrderSent = null;
                mState.setCrewOrder(before);
                changed();
                if (problem.kind == SocialProblem.Kind.CREWS_CHANGED) {
                    mLastFetch = 0;
                    refresh(false);
                }
                result.failed(problem);
            }
        }, null);
    }

    /** "Sessize al": one of the presets (the hub's clock when known), for every crew. */
    public void mute(MutePresets.Preset preset, Result<TeamNotifications> result) {
        long now = mState.hubNow(nowSeconds());
        JsonObject body = Json.parseObject(MutePresets.body(preset, now, TimeZone.getDefault()));
        patchNotifications(body, result);
    }

    public void unmute(Result<TeamNotifications> result) {
        JsonObject body = new JsonObject();
        body.addProperty("muted", false);
        patchNotifications(body, result);
    }

    /** TEAM4: "Sessize al" for one crew (the player's own mute of it). */
    public void muteCrew(final String teamId, MutePresets.Preset preset, Result<TeamNotifications> result) {
        long now = mState.hubNow(nowSeconds());
        JsonObject body = Json.parseObject(MutePresets.body(preset, now, TimeZone.getDefault()));
        patchCrewNotifications(teamId, body, result);
    }

    public void unmuteCrew(final String teamId, Result<TeamNotifications> result) {
        JsonObject body = new JsonObject();
        body.addProperty("muted", false);
        patchCrewNotifications(teamId, body, result);
    }

    private void patchCrewNotifications(final String teamId, final JsonObject body, Result<TeamNotifications> result) {
        run(() -> TeamNotifications.read(mApi.crewNotifications(teamId, body)), result, value -> mState.setCrewMute(teamId, value));
    }

    /** "Ekip çağrıları" (with {@code mute}: the service's setting, the same on every device). */
    public void setCrewCalls(boolean on, Result<TeamNotifications> result) {
        if (!gate().mute) {
            mPrefs.setWants(PushMessage.CALL, on);
            changed();
            syncPush();
            result.ok(mState.teamNotifications());
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("calls", on);
        patchNotifications(body, result);
    }

    private void patchNotifications(final JsonObject body, final Result<TeamNotifications> result) {
        run(() -> TeamNotifications.read(mApi.teamNotifications(body)), result, value -> {
            mState.setTeamNotifications(value);
            mPrefs.setCrewSettings(value);
            syncPush();
        });
    }

    /** A crew call message on the socket; fails at once without a live socket. */
    private void socketRequest(final JsonObject message, final Result<Void> result, @Nullable final Runnable onAck) {
        PresenceConnection connection = mConnection;
        if (connection == null || !mLiveSocket) {
            result.failed(SocialProblem.unavailable("no socket"));
            return;
        }
        connection.request(message, (PresenceConnection.Answer) answer -> {
            if (answer != null && "ack".equals(Json.str(answer, "t"))) {
                if (onAck != null) onAck.run();
                changed();
                result.ok(null);
                return;
            }
            result.failed(answer == null ? SocialProblem.unavailable("no answer") : SocialProblem.ofSocket(answer));
        });
    }

    /** Geliyorum / Birazdan / Gelemem: shown at once, confirmed by the socket. */
    public void answerCall(final String callId, String answer, final Result<Void> result) {
        if (!Call.isAnswer(answer)) {
            result.failed(new SocialProblem(SocialProblem.Kind.FAILED, 0, "bad_request", 0));
            return;
        }
        JsonObject message = new JsonObject();
        message.addProperty("t", "call_answer");
        message.addProperty("call", callId);
        message.addProperty("answer", answer);
        final String previous = mState.myAnswer(callId);
        mState.answerLocally(callId, answer);
        SocialNotifications.cancelCall(mApp, callId);
        changed();
        socketRequest(message, new Result<Void>() {
            @Override
            public void ok(Void value) {
                result.ok(value);
            }

            @Override
            public void failed(SocialProblem problem) {
                // Not sent: the screen shows the answer the crew has.
                mState.restoreAnswer(callId, previous);
                changed();
                result.failed(problem);
            }
        }, null);
    }

    /** "Bitir": the player's own call (started on another device). */
    public void endCall(String callId, Result<Void> result) {
        JsonObject message = new JsonObject();
        message.addProperty("t", "call_end");
        message.addProperty("call", callId);
        socketRequest(message, result, null);
    }

    /** For tests and the debug push path: the session's account id (null when signed out). */
    @Nullable
    public String signedInId() {
        if (mToken == null) return null;
        Me me = mState.me();
        if (me != null) return me.profile.id;
        return mStore.load().userId;
    }

    public List<Friend> friendsSnapshot() {
        return Collections.unmodifiableList(mState.friends());
    }
}
