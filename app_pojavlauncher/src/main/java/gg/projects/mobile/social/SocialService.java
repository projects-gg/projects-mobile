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
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.FriendsData;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Request;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamNotifications;

/**
 * The friends feature on the phone (M19): the session, the friend list and crew from the service,
 * the presence socket, the Discord sign-in and the phone notifications. One instance in the
 * launcher's process; every method runs on the main thread, network calls on two worker threads.
 *
 * <p>The socket is open only while the launcher's screens are on screen (closed 10 s after they
 * leave, so a quick look at another app keeps it), and while the player's own crew call runs, which
 * would end with the player's last socket: calling the crew and then starting the game keeps it
 * until the call ends. Without a socket the service sends phone notifications instead (soc6.md);
 * while the app keeps one in the background it turns what the socket brings into the same
 * notifications. The list is read when a screen shows and is older than a minute, and after a
 * reconnect: nothing polls.
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
    public enum SignInStatus { IDLE, CHECKING, WAITING, CLAIMING, FAILED, UNAVAILABLE }

    /** The connection as the screens tell it. */
    public enum Connection { OFF, CONNECTING, LIVE, UNAVAILABLE, REPLACED }

    /** A screen that shows friends' news itself (no notification needed while it is up). */
    public enum Surface { HOME, FRIENDS, CREW }

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
    private final Runnable mReconnect = this::ensureConnected;
    private final Runnable mBackgroundClose = this::closeIfIdle;
    private boolean mPushSyncing;
    /** The socket told the hub the player is in game (their own call runs while the game plays). */
    private boolean mPlayingSent;

    private SocialService(Context app) {
        mApp = app;
        mDebug = SocialDebug.read(app);
        mApi = new SocialApi(mDebug.url, ProjectsContent.userAgent());
        mStore = new SessionStore(app, mDebug.override ? "session-debug.json" : "session.json");
        mPrefs = new SocialPrefs(app);
        boolean[] config = mPrefs.lastConfig();
        mConfigFriends = config[0];
        mConfigTeams = config[1];
        SessionStore.Saved saved = mStore.load();
        mToken = saved.token;
        mApi.setToken(mToken);
        if (mToken != null && saved.me != null) mState.setMe(Me.read(saved.me));
        if (mToken == null) {
            mSignIn.restore(saved.attempt, nowSeconds());
            if (mSignIn.phase() == SignInFlow.Phase.WAITING) mSignInStatus = SignInStatus.WAITING;
        }
        ProjectsContent.addListener(this);
    }

    // ---- reading ----

    public SocialState state() {
        return mState;
    }

    public SocialPrefs prefs() {
        return mPrefs;
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
        return SocialGate.of(friends, teams, mState.limits().teamMembers, mState.teamFeatures(), mState.features(),
                BuildConfig.PROJECTS_FIREBASE || mDebug.pushToken != null);
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

    public HomeLine homeLine() {
        return HomeLine.of(signedIn(), mUnavailable, mState, mPrefs.favourites(), gate().teams, nowSeconds());
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
        boolean same = friends == mConfigFriends && teams == mConfigTeams;
        if (mConfigKnown && same) return;
        mConfigKnown = true;
        mConfigFriends = friends;
        mConfigTeams = teams;
        mPrefs.setLastConfig(friends, teams);
        if (same) return;
        if (!enabled()) {
            // Turned off: the session is kept (the desktop's rule), the socket and the phone's pushes go.
            closeConnection();
            if (mToken != null) syncPush();
        } else {
            ensureConnected();
            if (mUiStarted > 0) refresh(false);
        }
        changed();
    }

    // ---- screens on and off ----

    /** The launcher's screens came to the front. */
    @MainThread
    public void uiStarted() {
        mUiStarted++;
        mMain.removeCallbacks(mBackgroundClose);
        if (mPlayingSent) {
            mPlayingSent = false;
            backInLauncher();
        }
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

    private boolean surfaceShown(Surface surface) {
        return mUiStarted > 0 && mSurfaces[surface.ordinal()] > 0;
    }

    /** No screen and no call of the player's own: the socket goes, the phone's notifications take over. */
    private void closeIfIdle() {
        if (mUiStarted > 0) return;
        Call own = mState.ownCall(nowSeconds());
        if (own != null && mConnection != null) {
            // The player's own call ends with their last socket: kept until the call is over. Started
            // from here, the game shows the crew where the caller is (a call on Projects becomes "join").
            if (!mPlayingSent && SocialRoutes.gameRunning(mApp)) {
                mPlayingSent = true;
                gameStarting(projectsSelected());
            }
            long left = mState.secondsLeft(own, nowSeconds());
            mMain.postDelayed(mBackgroundClose, Math.max(5_000, Math.min(60_000, left * 1000L + 2_000)));
            return;
        }
        closeConnection();
        changed();
    }

    /** The profile Play starts is the Projects profile (its start joins projects.gg). */
    private static boolean projectsSelected() {
        try {
            android.content.SharedPreferences prefs = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF;
            return prefs != null && gg.projects.mobile.join.ProjectsProfile.KEY.equals(
                    prefs.getString(net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A push came while the app ran without a socket: the list is read again when its screens are up. */
    @MainThread
    public void pushArrived() {
        mLastFetch = 0;
        if (mUiStarted > 0) refresh(false);
    }

    // ---- the presence socket ----

    @MainThread
    private void ensureConnected() {
        mMain.removeCallbacks(mReconnect);
        if (mToken == null || !enabled() || mReplaced || mConnection != null) return;
        boolean wanted = mUiStarted > 0 || mState.ownCall(nowSeconds()) != null;
        if (!wanted) return;
        mConnection = PresenceConnection.open(mApi, mToken, this);
        changed();
    }

    private void closeConnection() {
        mMain.removeCallbacks(mReconnect);
        PresenceConnection connection = mConnection;
        mConnection = null;
        mLiveSocket = false;
        if (connection != null) connection.close();
        mState.setOffline();
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
        if (type.equals("hello")) {
            mLiveSocket = true;
            mUnavailable = false;
            mBackoff.healthy();
            // A new socket starts in the launcher: still in game, the hub hears it again.
            if (mPlayingSent && mUiStarted == 0) gameStarting(projectsSelected());
            // After a reconnect the list may have moved on.
            refresh(false);
        } else if (type.equals("bye")) {
            return;
        }
        List<SocialState.Notice> notices = mState.apply(message, now);
        if (type.equals("team_notifications") || type.equals("hello")) mPrefs.setCrewSettings(mState.teamNotifications());
        handleNotices(notices);
        changed();
    }

    @Override
    public void onEnded(PresenceConnection connection, int code, boolean failed) {
        if (connection != mConnection) return;
        mConnection = null;
        mLiveSocket = false;
        mState.setOffline();
        switch (code) {
            case 4001:
                sessionEnded(false);
                return;
            case 4002:
                sessionEnded(true);
                return;
            case 4000:
                // Replaced by a newer connection of the same account: no automatic reconnect.
                mReplaced = true;
                changed();
                return;
            default:
                break;
        }
        if (failed) mUnavailable = true;
        long delay = code == 4008 ? mBackoff.atLeastMs(60) : mBackoff.nextDelayMs();
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
        mIo.execute(() -> {
            try {
                final JsonObject me = mApi.me();
                final JsonObject friends = mApi.friends();
                mMain.post(() -> {
                    mFetching = false;
                    if (mToken == null) return;
                    Me parsed = Me.read(me);
                    if (parsed != null) {
                        mState.setMe(parsed);
                        mStore.save(mToken, parsed.profile.id, me, null);
                    }
                    mState.applyFriends(FriendsData.read(friends));
                    mPrefs.setCrewSettings(mState.teamNotifications());
                    mLastFetch = System.currentTimeMillis();
                    if (!mLiveSocket && mConnection == null) mUnavailable = false;
                    else if (mLiveSocket) mUnavailable = false;
                    changed();
                    syncPush();
                    ensureConnected();
                });
            } catch (final SocialProblem problem) {
                mMain.post(() -> {
                    mFetching = false;
                    if (problem.kind == SocialProblem.Kind.SIGNED_OUT) {
                        sessionEnded(false);
                        return;
                    }
                    mUnavailable = true;
                    changed();
                });
            }
        });
    }

    // ---- sign-in ----

    /** "Discord ile giriş yap": the browser opens only when the service answers. */
    @MainThread
    public void beginSignIn(final Activity activity) {
        if (mToken != null || mSignInStatus == SignInStatus.CHECKING || mSignInStatus == SignInStatus.CLAIMING) return;
        mSignIn.starting();
        mSignInStatus = SignInStatus.CHECKING;
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

    /** {@code projects://auth/done?c=<code>} came back: claimed only for this app's own waiting sign-in. */
    @MainThread
    public void onAuthLink(@Nullable String code) {
        final SignInFlow.Attempt attempt = mSignIn.onDeepLink(code, nowSeconds());
        if (attempt == null || code == null) {
            Log.i(TAG, "sign-in link ignored: no sign-in of this app waits");
            return;
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
                    mSignInStatus = problem.kind == SocialProblem.Kind.UNAVAILABLE ? SignInStatus.UNAVAILABLE : SignInStatus.FAILED;
                    changed();
                });
            }
        });
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
        mUnavailable = false;
        mReplaced = false;
        mLastFetch = 0;
        mBackoff.reset();
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
            try {
                // Sign-out also ends this session's push registration on the service.
                mApi.logout();
            } catch (SocialProblem ignored) {
                // Signed out on the phone anyway; the session expires on the service by itself.
            }
        });
        forgetSession();
        PushRegistrar.forget(mApp);
        changed();
    }

    /** "Verilerimi sil": the account and everything in it, then signed out. */
    @MainThread
    public void deleteAccount(final Result<Void> result) {
        if (mToken == null) return;
        mIo.execute(() -> {
            try {
                mApi.deleteAccount();
                mMain.post(() -> {
                    forgetSession();
                    PushRegistrar.forget(mApp);
                    changed();
                    result.ok(null);
                });
            } catch (final SocialProblem problem) {
                mMain.post(() -> result.failed(problem));
            }
        });
    }

    private void sessionEnded(boolean deleted) {
        Log.i(TAG, deleted ? "account deleted elsewhere" : "session ended");
        forgetSession();
        mSignedOutRemotely = !deleted;
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
        SocialNotifications.cancelAll(mApp);
    }

    // ---- phone notifications ----

    /** Registers this phone for pushes, refreshes or ends its registration, as the switches say. */
    @MainThread
    public void syncPush() {
        if (mPushSyncing) return;
        mPushSyncing = true;
        PushRegistrar.sync(mApp, this, () -> mPushSyncing = false);
    }

    /** FCM gave this phone a new token. */
    @MainThread
    public void onNewPushToken() {
        mPrefs.setPushRegistered("");
        if (mToken != null) syncPush();
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
        Set<String> kinds = new java.util.HashSet<>();
        if (mPrefs.wantsRequests()) kinds.add(PushMessage.REQUEST);
        if (mPrefs.wantsInvites()) kinds.add(PushMessage.INVITE);
        boolean calls = gate().mute ? mState.teamNotifications().calls : mPrefs.wantsCallsLocally();
        if (calls) kinds.add(PushMessage.CALL);
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
                    boolean audible = gate().mute ? mState.teamNotifications().callsAudible(now) : mPrefs.wantsCallsLocally();
                    if (!audible || surfaceShown(Surface.CREW) || surfaceShown(Surface.HOME) || surfaceShown(Surface.FRIENDS)) break;
                    SocialNotifications.showCall(context, call.id, call.from, notice.who == null ? null : notice.who.name(),
                            call.joinable(), mState.secondsLeft(call, now) * 1000L);
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
                        String text = "kicked".equals(notice.reason)
                                ? context.getString(R.string.projects_crew_kicked, notice.teamName)
                                : "disbanded".equals(notice.reason) ? context.getString(R.string.projects_crew_disbanded, notice.teamName) : null;
                        if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show();
                    }
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
        if (mToken == null) {
            result.failed(new SocialProblem(SocialProblem.Kind.SIGNED_OUT, 401, null, 0));
            return;
        }
        mIo.execute(() -> {
            try {
                final T value = call.run();
                mMain.post(() -> {
                    if (apply != null) apply.apply(value);
                    changed();
                    result.ok(value);
                });
            } catch (final SocialProblem problem) {
                mMain.post(() -> {
                    if (problem.kind == SocialProblem.Kind.SIGNED_OUT) sessionEnded(false);
                    result.failed(problem);
                });
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
        mIo.execute(() -> {
            try {
                final JsonObject me = mApi.me();
                mMain.post(() -> {
                    Me parsed = Me.read(me);
                    if (parsed != null && mToken != null) {
                        mState.setMe(parsed);
                        mStore.save(mToken, parsed.profile.id, me, null);
                        changed();
                    }
                });
            } catch (SocialProblem ignored) {
                // The next refresh brings it.
            }
        });
    }

    public void toggleFavourite(String id) {
        mPrefs.setFavourite(id, !mPrefs.favourites().contains(id));
        changed();
    }

    // ---- invites ("Birlikte oyna") ----

    public void dismissInvite(String inviteId) {
        mState.dismissInvite(inviteId);
        changed();
    }

    // ---- crews ----

    private void applyTeamAnswer(JsonObject answer) {
        Team team = Team.read(Json.obj(answer, "team"));
        Set<String> invited = new java.util.HashSet<>();
        for (String id : Json.strings(answer, "invited")) if (SocialModel.isUserId(id)) invited.add(id);
        mState.setTeam(team, invited);
    }

    public void lookupTeam(String code, Result<JsonObject> result) {
        run(() -> mApi.lookupTeam(code), result, null);
    }

    public void joinTeam(String code, Result<JsonObject> result) {
        run(() -> mApi.joinTeam(code), result, this::applyTeamAnswer);
    }

    public void acceptTeamInvite(final String teamId, Result<JsonObject> result) {
        run(() -> mApi.acceptTeamInvite(teamId), result, answer -> {
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
        run(() -> mApi.createTeam(body), result, this::applyTeamAnswer);
    }

    public void leaveTeam(final String teamId, Result<Void> result) {
        run(() -> {
            mApi.leaveTeam(teamId);
            return null;
        }, result, value -> mState.setTeam(null, null));
    }

    public void disbandTeam(final String teamId, Result<Void> result) {
        run(() -> {
            mApi.disbandTeam(teamId);
            return null;
        }, result, value -> mState.setTeam(null, null));
    }

    public void inviteToTeam(final String teamId, final String friendId, Result<Void> result) {
        run(() -> {
            mApi.inviteToTeam(teamId, friendId);
            return null;
        }, result, value -> mState.addTeamInvited(friendId));
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
        mIo.execute(() -> {
            try {
                final JsonObject answer = mApi.teams();
                mMain.post(() -> {
                    if (mToken == null) return;
                    applyTeamAnswer(answer);
                    changed();
                });
            } catch (SocialProblem ignored) {
                // The socket's "team" message or the next refresh brings it.
            }
        });
    }

    /** "Sessize al": one of the presets (the hub's clock when known). */
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
        connection.request(message, (error, retryAfter) -> {
            if (error == null) {
                if (onAck != null) onAck.run();
                changed();
                result.ok(null);
                return;
            }
            SocialProblem.Kind kind;
            switch (error) {
                case "rate_limited": kind = SocialProblem.Kind.RATE_LIMITED; break;
                case "call_active": kind = SocialProblem.Kind.CALL_ACTIVE; break;
                case "call_not_found": kind = SocialProblem.Kind.CALL_ENDED; break;
                case "team_not_permitted": kind = SocialProblem.Kind.NOT_PERMITTED; break;
                case "team_not_found": kind = SocialProblem.Kind.NOT_IN_CREW; break;
                case "unavailable": kind = SocialProblem.Kind.UNAVAILABLE; break;
                default: kind = SocialProblem.Kind.FAILED; break;
            }
            result.failed(new SocialProblem(kind, 0, error, retryAfter));
        });
    }

    /** "Ekibi çağır". */
    public void startCall(Result<Void> result) {
        JsonObject message = new JsonObject();
        message.addProperty("t", "call");
        socketRequest(message, result, () -> mPrefs.setCalledAt(nowSeconds()));
    }

    /** Geliyorum / Birazdan / Gelemem: shown at once, confirmed by the socket. */
    public void answerCall(String callId, String answer, Result<Void> result) {
        if (!Call.isAnswer(answer)) return;
        JsonObject message = new JsonObject();
        message.addProperty("t", "call_answer");
        message.addProperty("call", callId);
        message.addProperty("answer", answer);
        mState.answerLocally(callId, answer);
        SocialNotifications.cancelCall(mApp, callId);
        changed();
        socketRequest(message, result, null);
    }

    /** "Bitir": the player's own call. */
    public void endCall(String callId, Result<Void> result) {
        JsonObject message = new JsonObject();
        message.addProperty("t", "call_end");
        message.addProperty("call", callId);
        socketRequest(message, result, null);
    }

    /** Seconds until the player may call the crew again (5 minutes after the last call from here). */
    public long callWaitSeconds() {
        long since = nowSeconds() - mPrefs.calledAt();
        return since >= 0 && since < 300 ? 300 - since : 0;
    }

    /** The app started the game while the player's own call runs: the crew sees them on Projects. */
    @MainThread
    public void gameStarting(boolean projects) {
        PresenceConnection connection = mConnection;
        if (connection == null || !mLiveSocket) return;
        JsonObject status = new JsonObject();
        status.addProperty("t", "status");
        status.addProperty("state", "playing");
        // The Projects profile joins play.projects.gg; another profile's server is not known here.
        if (projects) status.addProperty("host", "play.projects.gg");
        connection.send(status);
    }

    /** Back in the launcher (its screens came back after a game). */
    @MainThread
    public void backInLauncher() {
        PresenceConnection connection = mConnection;
        if (connection == null || !mLiveSocket) return;
        JsonObject status = new JsonObject();
        status.addProperty("t", "status");
        status.addProperty("state", "launcher");
        connection.send(status);
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
