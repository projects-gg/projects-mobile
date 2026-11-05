package gg.projects.mobile.social;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

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
import gg.projects.mobile.reminders.PlanAlarms;
import gg.projects.mobile.reminders.PlanReminders;
import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.FriendsData;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.MyRequest;
import gg.projects.mobile.social.SocialModel.Plan;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Request;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamNotifications;
import gg.projects.mobile.social.ui.PlanViews;
import gg.projects.mobile.social.ui.SocialUi;

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
 *
 * <p>M21 (TEAM5): joining a crew that approves newcomers sends a join request (the same route, with
 * {@code request}), the crew's leader or moderators answer them; invite links ({@code projects://crew/})
 * and the join setting; crew plans on the socket (the {@code plans} capability) with local reminders
 * ({@link PlanAlarms}, 10 minutes before a plan the player is coming to).
 *
 * <p>MOB1: CHAT2's socket capabilities beside {@code chat} (replies, stickers, reactions, typing, read
 * marks; links need none) and TEAM6's crew tags ({@code badges}): wearing a tag, a crew hidden or not
 * and taking requests from its tag or not (its leader's two settings), and a join request from a tag's
 * card. Older services ignore the names they do not know.
 *
 * <p>M048 (SCALE-SOC and SOC-DISK on the phone; scale-soc.md, the phone's spec): the list's answer brings the profile
 * and the relations version ({@link RelVersion}); the socket names that version when it connects and its hello says
 * whether the copy is still the service's, so a reconnect reads nothing when nothing changed. The answer is kept on the
 * phone ({@link KeptLists}): a start the same day shows it at once and reads only when the hello names another version.
 *
 * <p>SOC-ID (docs soc-id.md §8.2, §16): Microsoft signs in beside Discord through the same browser step while the
 * service offers it ({@link SignInOptions}); an account opened with Microsoft picks its name first ({@link #setName});
 * "Bağlı hesaplar" links another way in or removes one ({@link #linkIdentity}, {@link #unlinkIdentity}): the service
 * hands the page, and the {@code projects://auth/done} link that comes back is claimed at {@code v1/me/identities/claim}
 * while such a step waits ({@link SignInFlow.Attempt#claimPath}).
 */
public final class SocialService implements ProjectsContent.Listener, PresenceConnection.Listener {
    private static final String TAG = "ProjectsSocial";
    /** The list is read again only when it is older than this (the desktop's rule). */
    private static final long STALE_MS = 60_000;
    /** The socket stays this long after the launcher's screens leave. */
    private static final long BACKGROUND_GRACE_MS = 10_000;
    /** "Tekrar dene" waits this long between tries (the desktop's 10 s). */
    private static final long RETRY_GAP_MS = 10_000;
    /** BADGES: a change of the player's own badges is taken as on its way this long (its answer brings the profile). */
    private static final long MARKS_SENDING_MS = 20_000;
    /** BADGES: a failed catalogue read is tried once more after this long. */
    private static final long MARKS_RETRY_MS = 60_000;

    public interface Listener {
        @MainThread
        void onSocialChanged();
    }

    public interface Result<T> {
        void ok(T value);

        void failed(SocialProblem problem);
    }

    /**
     * What the sign-in screen shows. SOC-ID: {@code NOT_LINKED}, a Microsoft account no Projects account has while new
     * accounts open with Discord only; {@code PROVIDER_OFF}, that way in is switched off now.
     */
    public enum SignInStatus { IDLE, CHECKING, WAITING, CLAIMING, FAILED, UNAVAILABLE, RESTRICTED, SIGNUPS_CLOSED, NOT_LINKED, PROVIDER_OFF }

    /** SOC-ID: a link or a removal of a way in ("Bağlı hesaplar"): asking the service, in the browser, claiming. */
    public enum IdentityStatus { IDLE, STARTING, WAITING, CLAIMING }

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
    /** MOB-VOICE: voice chat in this process (rings, calls, crew rooms; the stay runs in the voice process). */
    private final gg.projects.mobile.voice.VoiceHub mVoice;
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
    /** MOB-VOICE: {@code features.voice} of the signed config (kept for a start before the config is read). */
    private boolean mConfigVoice;
    private boolean mConfigKnown;

    @Nullable
    private PresenceConnection mConnection;
    private boolean mLiveSocket;
    private int mUiStarted;
    /** MOB1b: the launcher's activity while it is on screen, for the app's own notice line (SocialUi.toast). */
    @Nullable
    private java.lang.ref.WeakReference<Activity> mUiActivity;
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
    /** M048: a read a hello asked for, held back by the 60 s rule, goes when the rule allows it (screens up only). */
    private final Runnable mStaleRead = () -> {
        if (mUiStarted > 0) refresh(false);
    };
    private boolean mPushSyncing;
    /** A push sync was asked for while one ran: it runs again after. */
    private boolean mPushAgain;
    /** Socket messages that change what {@code GET /v1/friends} answers (a list read meanwhile is read again). */
    private int mListChanges;
    /** A crew order on its way (TEAM4): the list read meanwhile keeps it. */
    @Nullable
    private List<String> mOrderSent;
    /** SOC9 (TEAM4-B): the crews' details kept on the phone, a folder per account ({@link CrewCache}). */
    private final java.io.File mCrewRoot;
    /** M048 (SCALE-SOC): the relations version of the profile and the lists shown. */
    private final RelVersion mRel = new RelVersion();
    /** M048 (SOC-DISK): the list's answer kept for the next start the same day. */
    private final KeptLists mKept;
    /** BADGES: the badge catalogue the screens draw with ({@link #marks()}) and when it is read again. */
    private final MarkBook mMarks = new MarkBook();
    /** BADGES: when the player last sent a change of their own badges (elapsed ms; the profile is not read meanwhile). */
    private long mMarksSentAt = Long.MIN_VALUE / 2;
    /** BADGES: a failed catalogue read was tried once more already (until a read gets through). */
    private boolean mMarksRetried;
    /** BADGES: that one later try, only while the socket is up (the app in use: no wake-up in the background). */
    private final Runnable mMarksRetry = () -> {
        if (connection() == Connection.LIVE && mMarks.retry()) readMarks();
    };
    /**
     * M048: a start reads the lists an earlier start kept today (a few milliseconds of disk, off the main thread); the
     * socket and the reads wait for it, so the connect names its version and nothing is read twice.
     */
    private boolean mKeptLoading;
    private boolean mAfterKeptConnect;
    private boolean mAfterKeptRefresh;
    private boolean mAfterKeptForce;

    /** SOC-ID: how long the ways in read from the service count (the sign-in and "Bağlı hesaplar" read them again after). */
    private static final long OPTIONS_FRESH_MS = 10 * 60_000;
    /** SOC-ID: the ways in the service offers ({@code GET /v1/auth/providers}), as last read (kept between starts). */
    private SignInOptions mOptions;
    private long mOptionsAt;
    private boolean mOptionsReading;
    /** SOC-ID: the way in of the sign-in on its way (the waiting card's words, the refusal's). */
    private String mSignInProvider = SignInFlow.DISCORD;
    /** SOC-ID: the profile as the service last sent it: the session file keeps it beside a link that waits. */
    @Nullable
    private JsonObject mMeJson;
    /** SOC-ID: a link or a removal on its way (its browser step: {@link #mSignIn}). */
    private IdentityStatus mIdentityStatus = IdentityStatus.IDLE;
    private String mIdentityProvider = SignInFlow.DISCORD;
    private SignInFlow.Purpose mIdentityPurpose = SignInFlow.Purpose.LINK;
    /**
     * SOC-ID: the note the service writes for a link or removal made on this phone ({@code identity_linked:microsoft})
     * and since when: it is no news here, so it is hidden without a word ({@link #hideOwnNotes}; other devices show it).
     */
    @Nullable
    private String mOwnIdentityNote;
    private long mOwnIdentityNoteSince;

    private SocialService(Context app) {
        mApp = app;
        mDebug = SocialDebug.read(app);
        mApi = new SocialApi(mDebug.url, ProjectsContent.userAgent());
        mStore = new SessionStore(app, mDebug.override ? "session-debug.json" : "session.json");
        mCrewRoot = new java.io.File(app.getNoBackupFilesDir(), mDebug.override ? "projects-social/crews-debug" : "projects-social/crews");
        mKept = new KeptLists(KeptLists.folder(app.getNoBackupFilesDir(), mDebug.override));
        mPrefs = new SocialPrefs(app);
        // APK-REL: the note of the last link or removal made on this phone outlives the process (SocialPrefs).
        String ownNote = mPrefs.ownIdentityNote();
        int bar = ownNote == null ? -1 : ownNote.lastIndexOf('|');
        if (bar > 0) {
            try {
                mOwnIdentityNoteSince = Long.parseLong(ownNote.substring(bar + 1));
                mOwnIdentityNote = ownNote.substring(0, bar);
            } catch (NumberFormatException ignored) {
                // An unreadable copy hides nothing.
            }
        }
        // M19's socket that kept the player's own call in the game is not used any more (see the class doc).
        mPrefs.setOwnCallUntil(0);
        boolean[] config = mPrefs.lastConfig();
        mConfigFriends = config[0];
        mConfigTeams = config[1];
        mConfigChat = config[2];
        mConfigVoice = mPrefs.lastVoiceConfig();
        mChat = new SocialChat(new ChatHost());
        mVoice = new gg.projects.mobile.voice.VoiceHub(app, new VoiceHost());
        mVoice.setConfig(mConfigFriends && mConfigVoice);
        gg.projects.mobile.voice.VoiceLink.get(app).watch(state -> {
            // MOB-VOICE: the socket stayed for the stay (rings, and "Sesli sohbette" for friends); it goes once that ends.
            if (state == null && mUiStarted == 0) {
                mMain.removeCallbacks(mBackgroundClose);
                mMain.postDelayed(mBackgroundClose, BACKGROUND_GRACE_MS);
            }
        });
        // CHAT2: the link rule table (a copy of the service's), read once off the main thread.
        final Context assets = app;
        ChatLinks.setSource(() -> assets.getAssets().open("social/chat-links.json"));
        mIo.execute(ChatLinks::rulesVersion);
        mOptions = SignInOptions.decode(mPrefs.signInOptions());
        SessionStore.Saved saved = mStore.load();
        mToken = saved.token;
        mApi.setToken(mToken);
        if (mToken != null && saved.me != null) {
            mState.setMe(Me.read(saved.me));
            mMeJson = saved.me;
        }
        if (mToken == null) {
            // A sign-in kept from before (SOC-ID: never a link of a session that is gone).
            if (saved.attempt != null && !saved.attempt.forIdentity()) mSignIn.restore(saved.attempt, nowSeconds());
            if (mSignIn.phase() == SignInFlow.Phase.WAITING) {
                mSignInStatus = SignInStatus.WAITING;
                mSignInProvider = mSignIn.attempt() == null ? SignInFlow.DISCORD : mSignIn.attempt().provider;
            }
            // M048 (SOC-DISK): nobody is signed in on this phone, so nothing of an account stays kept.
            mKept.clear();
        } else {
            // SOC-ID: a link or a removal whose browser step may still come back.
            if (saved.attempt != null && saved.attempt.forIdentity()) {
                mSignIn.restore(saved.attempt, nowSeconds());
                if (mSignIn.phase() == SignInFlow.Phase.WAITING) {
                    mIdentityProvider = saved.attempt.provider;
                    mIdentityPurpose = saved.attempt.purpose;
                }
            }
            if (saved.userId != null) {
                mChat.open(saved.userId);
                loadKept(saved.userId);
            }
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
        return mConnection != null || mFetching || mKeptLoading ? Connection.CONNECTING : Connection.OFF;
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
        // M048: before the socket's hello nobody's presence is known (the kept lists show before it): "Bağlanıyor".
        return HomeLine.of(signedIn(), mUnavailable, connection() == Connection.CONNECTING, mState, mPrefs.favourites(), gate().teams,
                nowSeconds(), mChat.unreadDirect());
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
        mVoice.signedIn(mToken == null ? null : mState.myId());
        for (Listener listener : new ArrayList<>(mListeners)) listener.onSocialChanged();
    }

    /** MOB-VOICE: voice chat in this process. */
    public gg.projects.mobile.voice.VoiceHub voice() {
        return mVoice;
    }

    /** MOB-VOICE: what voice chat needs of the friends feature. */
    private final class VoiceHost implements gg.projects.mobile.voice.VoiceHub.Host {
        @Override
        public boolean send(JsonObject message) {
            PresenceConnection connection = mConnection;
            return connection != null && mLiveSocket && connection.send(message);
        }

        @Override
        public void request(JsonObject message, gg.projects.mobile.voice.VoiceHub.Answer answer) {
            PresenceConnection connection = mConnection;
            if (connection == null || !mLiveSocket) {
                answer.onAnswer(null);
                return;
            }
            connection.request(message, (PresenceConnection.Answer) answer::onAnswer);
        }

        @Override
        public boolean foreground() {
            return mUiStarted > 0;
        }

        @Override
        public SocialApi api() {
            return mApi;
        }

        @Nullable
        @Override
        public String myId() {
            return mToken == null ? null : mState.myId();
        }

        @Nullable
        @Override
        public String nameOf(String id) {
            if (id.startsWith("crew:")) {
                Crew crew = mState.crew(id.substring(5));
                return crew == null ? null : crew.team.name;
            }
            Profile profile = mState.profile(id);
            if (profile != null) return profile.name();
            return mPrefs.peopleNames().get(id);
        }

        @Override
        public boolean doNotDisturb() {
            Me me = mState.me();
            return me != null && SocialModel.Status.DND.equals(me.status);
        }
    }

    // ---- the signed config ----

    @Override
    public void onContent(@Nullable Snapshot snapshot, boolean refreshing) {
        if (snapshot == null || snapshot.config == null) return;
        boolean friends = snapshot.config.friendsFeature;
        boolean teams = snapshot.config.teamsFeature;
        boolean chat = snapshot.config.chatFeature;
        boolean chatChanged = chat != mConfigChat;
        // MOB-VOICE: voice chat came or went: the socket asks again with the right capabilities, as for chat.
        boolean voice = snapshot.config.voiceFeature;
        if (voice != mConfigVoice) {
            mConfigVoice = voice;
            mPrefs.setLastVoiceConfig(voice);
            chatChanged = true;
        }
        mVoice.setConfig(friends && voice);
        boolean same = friends == mConfigFriends && teams == mConfigTeams && !chatChanged;
        if (mConfigKnown && same) return;
        mConfigKnown = true;
        mConfigFriends = friends;
        mConfigTeams = teams;
        mConfigChat = chat;
        mPrefs.setLastConfig(friends, teams, chat);
        if (same) return;
        if (!enabled()) {
            // Turned off: the session is kept (the desktop's rule), the socket and the phone's pushes go, and
            // the plan reminders (M21) with them.
            closeConnection();
            if (mToken != null) syncPush();
            syncPlanReminders();
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
        mVoice.foregroundChanged();
    }

    /** The launcher's activity came to the front (its notices go to its own line at the bottom). */
    @MainThread
    public void uiStarted(Activity activity) {
        mUiActivity = new java.lang.ref.WeakReference<>(activity);
        uiStarted();
    }

    @MainThread
    public void uiStopped() {
        mUiStarted = Math.max(0, mUiStarted - 1);
        if (mUiStarted == 0) {
            mUiActivity = null;
            mMain.removeCallbacks(mBackgroundClose);
            mMain.postDelayed(mBackgroundClose, BACKGROUND_GRACE_MS);
            mVoice.foregroundChanged();
        }
    }

    /** The launcher's activity while it is on screen, else null. */
    @Nullable
    public Activity uiActivity() {
        Activity activity = mUiActivity == null ? null : mUiActivity.get();
        return activity == null || activity.isFinishing() ? null : activity;
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
        // MOB-VOICE: in a voice room the socket stays (friends see "Sesli sohbette", invites still ring).
        if (gg.projects.mobile.voice.VoiceLink.get(mApp).state() != null) return;
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

    /**
     * A push came while the app ran without a socket: the list is read again when its screens are up. SOC-REL-M (the
     * lead's decision, 1 November): only a push that changes what the list shows (a friend request) forgets the version
     * held and the copy kept for a start; after a message, a call or "Birlikte oyna" the next hello's version decides.
     */
    @MainThread
    public void pushArrived(String type) {
        if (RelVersion.pushChangesLists(type)) forgetRel();
        mLastFetch = 0;
        if (mUiStarted > 0) refresh(false);
    }

    // ---- the presence socket ----

    /** What the socket says it understands (README "Capabilities"): older services ignore it. */
    private List<String> caps() {
        List<String> caps = new ArrayList<>();
        caps.add("callServer");
        caps.add("crews");
        if (chatEnabled()) {
            caps.add("chat");
            // CHAT2: each extra is a capability of its own (the launcher and the phone update apart).
            for (String extra : SocialChat.EXTRA_CAPS) caps.add(extra);
        }
        // M21 (TEAM5): crew plans. Asked for always: the list that says whether the service has them may
        // come after the socket opens, and a service without plans ignores the name.
        caps.add("plans");
        // TEAM6: crew tag changes, live (the same reasoning).
        caps.add("badges");
        // BADGES: profile badges, live: "marks" is SOC-VERIFY's single mark, "marks2" every badge and the catalogue's
        // revision (a service with both sends only the newer frames).
        caps.add("marks");
        caps.add("marks2");
        // AFKPLUS-M: 7/24 Bulut's notices on the socket ("afk_notice"; a service without them ignores the name).
        caps.add("afk");
        // MOB-VOICE: voice chat ("voice1"), while the signed config has it.
        mVoice.addCaps(caps);
        return caps;
    }

    // ---- profile badges (BADGES) ----

    /**
     * The badge catalogue to draw with: the built-in one until the copy kept on the phone is read (the first call reads
     * it, off the main thread, and the screens hear when it is in), then the service's when it names another one.
     */
    @MainThread
    public MarkCatalog marks() {
        if (mMarks.takeLoad()) loadMarks();
        return mMarks.catalog();
    }

    private MarkStore markStore() {
        return new MarkStore(new java.io.File(mApp.getNoBackupFilesDir(),
                mDebug.override ? "projects-social/marks-debug.json" : "projects-social/marks.json"));
    }

    @MainThread
    private void loadMarks() {
        final MarkStore store = markStore();
        mIo.execute(() -> {
            final MarkStore.Kept kept = store.load();
            mMain.post(() -> {
                boolean read = mMarks.loaded(kept);
                if (kept != null) changed();
                if (read) readMarks();
            });
        });
    }

    /** A fresh list named the catalogue's revision (null: a service before badges) and the badges it shows. */
    @MainThread
    private void marksListed(@Nullable Long rev) {
        if (rev == null) return;
        marks();
        if (mMarks.named(rev)) readMarks();
        if (mMarks.saw(mState.markKeys(), SystemClock.elapsedRealtime())) readMarks();
    }

    /** {@code markdefs} (a new catalogue), {@code marks} and {@code mark} (someone's badges; applied before this). */
    @MainThread
    private void onMarksFrame(String type, JsonObject message) {
        if (type.equals("markdefs")) {
            Long rev = Json.numOrNull(message, "rev");
            marks();
            if (rev != null && mMarks.named(rev)) readMarks();
            return;
        }
        if (mMarks.saw(MarkCatalog.keysOf(message), SystemClock.elapsedRealtime())) readMarks();
        // One of the player's shown badges went on another device (or Studio took it): the profile says which, read
        // again unless the change is this phone's own, still on its way (its answer says).
        if (mState.takeMyMarksUnsure() && SystemClock.elapsedRealtime() - mMarksSentAt > MARKS_SENDING_MS) refreshMeOnly();
    }

    /** One read of {@code GET /v1/marks} at a time (MarkBook's rules); a new catalogue is kept on the phone. */
    @MainThread
    private void readMarks() {
        final String etag = mMarks.begin();
        final MarkStore store = markStore();
        mIo.execute(() -> {
            MarkStore.Kept read = null;
            boolean failed = false;
            try {
                read = mApi.markCatalog(etag);
                if (read != null) store.save(read.catalog, read.etag);
            } catch (SocialProblem problem) {
                // The catalogue held stays: one more try a minute later, then the next list read or frame that asks.
                failed = true;
            }
            final MarkStore.Kept answer = read;
            final boolean retry = failed;
            mMain.post(() -> {
                boolean again = mMarks.ended(answer);
                if (answer != null) changed();
                if (again) {
                    readMarks();
                } else if (retry && !mMarksRetried) {
                    // A blip at a same-day start would keep the older catalogue all day (no list read names it again).
                    mMarksRetried = true;
                    mMain.removeCallbacks(mMarksRetry);
                    mMain.postDelayed(mMarksRetry, MARKS_RETRY_MS);
                }
                if (!retry) mMarksRetried = false;
            });
        });
    }

    /**
     * The player's own badges they keep off their profile ("Rozetler", {@link MarkCatalog#hiddenToSend}): the whole list
     * goes ({@code PATCH /v1/me {marksHidden}}), keys this version cannot draw included, so none of them shows again.
     */
    @MainThread
    public void setMarksHidden(List<String> hidden, Result<JsonObject> result) {
        mMarksSentAt = SystemClock.elapsedRealtime();
        patchMe(MarkCatalog.hiddenBody(hidden), result);
    }

    @MainThread
    private void ensureConnected() {
        mMain.removeCallbacks(mReconnect);
        if (mToken == null || !enabled() || mReplaced || mConnection != null) return;
        if (mUiStarted == 0) return;
        if (mKeptLoading) {
            // M048: the connect names the version of the copy this start kept, once that copy is read.
            mAfterKeptConnect = true;
            return;
        }
        mConnection = PresenceConnection.open(mApi, mToken, caps(), mRel.held(), this);
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
        mVoice.socketEnded();
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
        if (type.equals("afk_notice")) {
            // AFKPLUS-M: a 7/24 Bulut notice; the cloud side shows it (in the app's line when it is on screen).
            gg.projects.mobile.cloud.CloudService.get(mApp).onSocketNotice(message, uiActivity());
            return;
        }
        if (mChat.onFrame(message)) {
            changed();
            return;
        }
        // MOB-VOICE: rings, the crews' rooms, Studio's switch (the hello and presences go on to the rest too).
        if (mVoice.onFrame(message)) {
            changed();
            return;
        }
        if (type.equals("hello")) {
            mLiveSocket = true;
            mUnavailable = false;
            mBackoff.healthy();
            // After a reconnect the list may have moved on. M048 (SCALE-SOC, spec 4): the hello names the service's
            // relations version for the one the connect named; the same means nothing the lists or the profile show
            // changed meanwhile, and nothing is read.
            RelVersion.Hello hello = mRel.hello(Json.str(message, "rel"), mState.loaded());
            if (hello == RelVersion.Hello.CHANGED) {
                // A read on its way may have been answered before the change this hello reports: it keeps no version
                // and the lists are read once more when it ends. SOC-REL-M (spec 6): the copy kept for a start is older
                // than the service's, so it goes now, also when no read is on its way (held back, or the screens gone).
                if (mFetching) mListChanges++;
                forgetRel();
                readChanged();
            } else if (hello == RelVersion.Hello.UNSAID) {
                refresh(false, true);
            }
        }
        if (RelVersion.changesRelations(type)) {
            // What GET /v1/friends answers changed: a list read meanwhile is read again, and (M048, spec 5) the copy is
            // no longer the one its version describes.
            mListChanges++;
            forgetRel();
        }
        List<SocialState.Notice> notices = mState.apply(message, now);
        if (type.equals("marks") || type.equals("mark") || type.equals("markdefs")) onMarksFrame(type, message);
        if (type.equals("team_notifications") || type.equals("hello")) mPrefs.setCrewSettings(mState.teamNotifications());
        if (type.equals("team") || type.equals("crew_order")) rememberCrewNames();
        if (type.equals("team")) {
            // SOC9 (TEAM4-B): a team frame is the crew's newest copy (or its end): the phone's copy follows it.
            final String me = mState.myId();
            final JsonObject frame = message.deepCopy();
            final long day = CrewDetails.turkeyDay(now);
            if (me != null) mIo.execute(() -> CrewDetails.onTeamFrame(frame, new CrewCache(mCrewRoot, me), day));
        }
        handleNotices(notices);
        if (type.equals("hello")) mChat.connected();
        switch (type) {
            case "hello":
            case "plan":
            case "plans":
            case "plan_removed":
            case "team":
            case "status":
            case "team_notifications":
            case "crew_notifications":
                // M21: the plan reminders follow what the socket says (and what keeps them quiet).
                syncPlanReminders();
                break;
            default:
                break;
        }
        changed();
    }

    @Override
    public void onEnded(PresenceConnection connection, int code, boolean failed) {
        if (connection != mConnection) return;
        // M048: whether this connection said hello (then the version it named was decided on).
        boolean saidHello = mLiveSocket;
        mConnection = null;
        mLiveSocket = false;
        mState.setOffline();
        mChat.disconnected();
        mVoice.socketEnded();
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
        // M048: a connection that ended before its hello decided nothing. When nothing was read in this run yet (a start
        // that shows the copy kept on the phone), the lists are read now by the usual rule, as a start read them before.
        // SOC-REL-M: once per run (a read tried, not one that got through): in an outage every reconnect failed and read.
        if (!saidHello && mLastTry == 0) refresh(false, true);
        changed();
    }

    // ---- reading the list ----

    /** Reads {@code /v1/friends} (with the profile in it) unless the lists are fresh (force: "Tekrar dene"). */
    @MainThread
    public void refresh(boolean force) {
        refresh(force, false);
    }

    /**
     * {@code anyVersion}: for a hello that named no version (M048, spec 4's "otherwise") or a connection that ended
     * before its hello: the usual rule, whatever version is held (a read that ended just before such a connect keeps its
     * version, and is not read again at once).
     */
    @MainThread
    private void refresh(boolean force, boolean anyVersion) {
        if (mToken == null || !enabled() || mFetching) return;
        if (mKeptLoading) {
            // M048: the copy kept for this start comes first; the read then decides with its version.
            mAfterKeptRefresh = true;
            mAfterKeptForce |= force;
            return;
        }
        // M048 (SCALE-SOC, spec 7): while a version is held for loaded lists, the hello of the connection that is up or on
        // its way decides. Without one (replaced elsewhere, failed and waiting to retry) no hello comes: the usual rule.
        if (!anyVersion && mRel.refreshSkips(force, mState.loaded(), mConnection != null)) return;
        long now = System.currentTimeMillis();
        if (!force && now - mLastFetch < STALE_MS && mState.loaded()) return;
        if (force && now - mLastTry < RETRY_GAP_MS && mState.loaded()) return;
        mLastTry = now;
        mFetching = true;
        changed();
        final String token = mToken;
        final int changesBefore = mListChanges;
        final long relSince = mRel.readStarts();
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                final JsonObject friends = mApi.friends();
                // M048 (spec 2): the answer brings the profile too, so one request; an older service's does not. With it
                // (SOC-DISK, spec 6) come its version and the answer as the service sent it (crews still light), to keep.
                final KeptLists.Answer brought = KeptLists.Answer.of(friends);
                final JsonObject me = brought.me != null ? brought.me : mApi.me();
                final long day = CrewDetails.turkeyDay(nowSeconds());
                final String rel = brought.rel;
                final JsonObject keep = brought.keep;
                // SOC9 (TEAM4-B): the light list's crews in full, from the phone's copies or their details. SOC-REL-M: a
                // detail not read now shows the crew's older copy, and such lists are not the answer the version describes.
                Me who = Me.read(me);
                final CrewDetails.Noted crews = new CrewDetails.Noted(mApi::teamDetail);
                if (who != null) {
                    CrewDetails.resolve(friends, new CrewCache(mCrewRoot, who.profile.id), crews, day);
                }
                mMain.post(() -> {
                    mFetching = false;
                    // An answer for a session that is gone (signed out, or signed in again meanwhile) is dropped.
                    if (!token.equals(mToken)) return;
                    Me parsed = Me.read(me);
                    if (parsed != null) {
                        mState.setMe(parsed);
                        keepSession(parsed.profile.id, me);
                        mChat.open(parsed.profile.id);
                    }
                    List<String> order = mOrderSent;
                    mState.applyFriends(FriendsData.read(friends));
                    // BADGES: the answer names the badge catalogue's revision (read again when it moved).
                    marksListed(Json.numOrNull(friends, "marksRev"));
                    // An order the player just chose wins over a list read before it reached the service.
                    if (order != null) mState.setCrewOrder(order);
                    // M048: the version is held only when the copy is exactly this answer (no change since the read
                    // started, no order of the player's laid over it: spec 2; SOC-REL-M: every crew read, none from an
                    // older copy), and the answer is kept with it (spec 6). Otherwise a copy kept earlier goes.
                    if (mRel.keep(parsed != null && order == null && keep != null && !crews.missed() ? rel : null, relSince)) {
                        final long generation = mKept.generation();
                        final String account = parsed.profile.id;
                        mIo.execute(() -> mKept.save(generation, account, day, keep));
                    } else {
                        mKept.clear();
                    }
                    mPrefs.setCrewSettings(mState.teamNotifications());
                    rememberCrewNames();
                    syncPlanReminders();
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

    /**
     * M048 (SOC-DISK, the phone's spec 6): a start shows the lists an earlier start kept today for this account, each crew
     * from its own copy, and holds their version for the connect (whose hello says whether they are still the
     * service's). Read off the main thread; the socket and any read wait for it. Without a usable copy the start reads as
     * before.
     */
    @MainThread
    private void loadKept(final String account) {
        final String token = mToken;
        final long since = mRel.readStarts();
        final long today = CrewDetails.turkeyDay(nowSeconds());
        mKeptLoading = true;
        mIo.execute(() -> {
            KeptStart found = null;
            try {
                KeptLists.Copy copy = mKept.load(account, today);
                JsonObject lists = copy == null ? null : KeptLists.listsWithCrews(copy, new CrewCache(mCrewRoot, account), today);
                Me me = lists == null ? null : Me.read(copy.me);
                if (me != null && account.equals(me.profile.id)) {
                    found = new KeptStart(copy.rel, me, FriendsData.read(lists), Json.numOrNull(lists, "marksRev"));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "the friend list kept on the phone is not read", e);
                found = null;
            } finally {
                // Always back on the main thread: the connect and the reads wait for this.
                final KeptStart shown = found;
                mMain.post(() -> keptLoaded(token, since, shown));
            }
        });
    }

    /** What a start shows from the copy kept on the phone. */
    private static final class KeptStart {
        final String rel;
        final Me me;
        final FriendsData data;
        /** BADGES: the badge catalogue's revision the kept answer named (null: a service before badges). */
        @Nullable
        final Long marksRev;

        KeptStart(String rel, Me me, FriendsData data, @Nullable Long marksRev) {
            this.rel = rel;
            this.me = me;
            this.data = data;
            this.marksRev = marksRev;
        }
    }

    @MainThread
    private void keptLoaded(@Nullable String token, long since, @Nullable KeptStart kept) {
        mKeptLoading = false;
        if (kept != null && token != null && token.equals(mToken) && !mState.loaded() && mRel.keep(kept.rel, since)) {
            mState.setMe(kept.me);
            mState.applyFriends(kept.data);
            // BADGES: the kept answer names the catalogue's revision (a same-day start may read no list at all).
            marksListed(kept.marksRev);
            mPrefs.setCrewSettings(mState.teamNotifications());
            rememberCrewNames();
            syncPlanReminders();
            changed();
            syncPush();
        }
        // What waited for the copy goes now: the connect names its version, a read decides with it.
        boolean connect = mAfterKeptConnect;
        boolean read = mAfterKeptRefresh;
        boolean force = mAfterKeptForce;
        mAfterKeptConnect = false;
        mAfterKeptRefresh = false;
        mAfterKeptForce = false;
        if (connect) ensureConnected();
        if (read) refresh(force);
    }

    /**
     * M048 (spec 4): the hello named another version, so the lists shown are older than the service's: read now, or (the
     * 60 s rule, kept for another version) as soon as that rule allows it, not only at the next screen change.
     */
    @MainThread
    private void readChanged() {
        mMain.removeCallbacks(mStaleRead);
        long wait = STALE_MS - (System.currentTimeMillis() - mLastFetch);
        if (mFetching || wait <= 0 || !mState.loaded()) {
            refresh(false);
            return;
        }
        mMain.postDelayed(mStaleRead, wait);
    }

    /**
     * M048 (SCALE-SOC, spec 5 and 6): the copy changed here (a socket event, the player's own change, a push, the
     * session's end): no version describes it any more, a read on its way keeps none, and the copy kept for a start goes.
     */
    @MainThread
    private void forgetRel() {
        mRel.forget();
        mKept.clear();
    }

    /**
     * M048 (spec 5): a change of the player's own ({@link #run}'s way): the version is forgotten when it is sent and again
     * when its answer is applied (a read in between may have brought the copy before it).
     */
    private <T> void change(final Call0<T> call, final Result<T> result, @Nullable final OnOk<T> apply) {
        forgetRel();
        run(call, result, value -> {
            forgetRel();
            if (apply != null) apply.apply(value);
        });
    }

    /**
     * The crews' names for a call's push while the app sleeps (id → name); FIX2: friends' and crewmates'
     * names too, for the game's own socket (it reads no list).
     */
    private void rememberCrewNames() {
        Map<String, String> names = new HashMap<>();
        Map<String, String> people = new HashMap<>();
        for (Crew c : mState.crews()) {
            names.put(c.id(), c.team.name);
            for (SocialModel.Member m : c.team.members) people.put(m.profile.id, m.profile.name());
        }
        for (Friend f : mState.friends()) people.put(f.profile.id, f.profile.name());
        mPrefs.setCrewNames(names);
        mPrefs.setPeopleNames(people);
    }

    // ---- sign-in ----

    /** "Discord ile giriş yap": the browser opens only when the service answers. */
    @MainThread
    public void beginSignIn(final Activity activity) {
        beginSignIn(activity, SignInFlow.DISCORD);
    }

    /**
     * SOC-ID: "Discord ile giriş yap" or "Microsoft ile giriş yap". Discord's page opens once the service answers;
     * Microsoft's once the service also still offers it (Studio may have switched it off since the screen showed).
     */
    @MainThread
    public void beginSignIn(final Activity activity, final String provider) {
        if (mToken != null || mSignInStatus == SignInStatus.CHECKING || mSignInStatus == SignInStatus.CLAIMING) return;
        final String way = SignInFlow.isProvider(provider) ? provider : SignInFlow.DISCORD;
        final boolean discord = SignInFlow.DISCORD.equals(way);
        mSignIn.starting();
        mSignInStatus = SignInStatus.CHECKING;
        mSignInProvider = way;
        mRestrictedUntil = null;
        changed();
        mIo.execute(() -> {
            try {
                final SignInOptions options;
                if (discord) {
                    mApi.health();
                    options = null;
                } else {
                    options = mApi.providers();
                }
                mMain.post(() -> {
                    if (options != null) keepOptions(options);
                    if (mSignInStatus != SignInStatus.CHECKING) return;
                    if (options != null && !options.offers(way)) {
                        mSignIn.reset();
                        mSignInStatus = SignInStatus.PROVIDER_OFF;
                        changed();
                        return;
                    }
                    SignInFlow.Attempt attempt = mSignIn.begin(mRandom, nowSeconds(), way);
                    mStore.save(null, null, null, attempt);
                    mSignInStatus = SignInStatus.WAITING;
                    changed();
                    openSignInPage(pageHost(activity), attempt);
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

    /** SOC-ID: the way in of the sign-in on its way or last tried ({@link SignInFlow#DISCORD}, {@link SignInFlow#MICROSOFT}). */
    public String signInProvider() {
        SignInFlow.Attempt attempt = mSignIn.attempt();
        return attempt != null && !attempt.forIdentity() ? attempt.provider : mSignInProvider;
    }

    /** SOC-ID: the ways in the service offers, as last read (Discord only before the first read). */
    public SignInOptions signInOptions() {
        return mOptions;
    }

    /**
     * SOC-ID: reads the ways in when the sign-in or "Bağlı hesaplar" shows (once in ten minutes, {@code force} at once);
     * a failed read keeps what was known.
     */
    @MainThread
    public void readSignInOptions(boolean force) {
        if (!enabled() || mOptionsReading) return;
        if (!force && mOptionsAt > 0 && System.currentTimeMillis() - mOptionsAt < OPTIONS_FRESH_MS) return;
        mOptionsReading = true;
        mIo.execute(() -> {
            SignInOptions read = null;
            try {
                read = mApi.providers();
            } catch (SocialProblem problem) {
                Log.i(TAG, "ways in not read: " + problem.kind);
            }
            final SignInOptions options = read;
            mMain.post(() -> {
                mOptionsReading = false;
                if (options != null) keepOptions(options);
            });
        });
    }

    private void keepOptions(SignInOptions options) {
        mOptionsAt = System.currentTimeMillis();
        if (options.equals(mOptions)) return;
        mOptions = options;
        mPrefs.setSignInOptions(options.encode());
        changed();
    }

    /** "Tarayıcıyı yeniden aç": the same attempt's page again (a sign-in's, or SOC-ID's link or removal). */
    @MainThread
    public void reopenSignInPage(Activity activity) {
        SignInFlow.Attempt attempt = mSignIn.attempt();
        if (attempt != null && mSignIn.waiting(nowSeconds())) openSignInPage(activity, attempt);
    }

    /** The activity that asked, or the launcher on screen when that one went meanwhile (a turn of the phone). */
    @Nullable
    private Activity pageHost(Activity asked) {
        if (asked != null && !asked.isFinishing() && !asked.isDestroyed()) return asked;
        return uiActivity();
    }

    private void openSignInPage(@Nullable Activity activity, SignInFlow.Attempt attempt) {
        String url = attempt.forIdentity() ? attempt.url : SignInFlow.startUrl(mApi.base(), attempt.provider, attempt.challenge);
        if (url == null) return;
        if (mDebug.logBrowser) {
            // Debug builds on the emulator: a script plays the browser (no browser runs there).
            Log.i(TAG, (attempt.forIdentity() ? "identity page: " : "sign-in page: ") + url);
            return;
        }
        if (activity != null) openBrowser(activity, url);
    }

    /**
     * Opens a page in a Custom Tab when the browser has them (the extras the support library sets),
     * else in the browser: either way the browser's own cookies carry the sign-in.
     */
    public static void openBrowser(Activity activity, String url) {
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
                SocialUi.toast(activity, activity.getString(R.string.projects_link_no_app));
            }
        }
    }

    @MainThread
    public void cancelSignIn() {
        if (mToken != null) {
            // SOC-ID: a link or a removal that waited for the browser.
            cancelIdentity();
            return;
        }
        mSignIn.reset();
        mStore.save(mToken, mState.myId(), null, null);
        mSignInStatus = SignInStatus.IDLE;
        changed();
    }

    /**
     * {@code projects://auth/done?c=<code>} came back: claimed only for this app's own waiting sign-in.
     * False when it was not for one (a link any web page can open): then nothing else happens either.
     * SOC-ID: while a link or a removal of the signed-in account waits, the code is that one's
     * ({@code v1/me/identities/claim}); a sign-in's goes to {@code v1/auth/claim} as before.
     */
    @MainThread
    public boolean onAuthLink(@Nullable String code) {
        final SignInFlow.Attempt attempt = mSignIn.onDeepLink(code, nowSeconds());
        if (attempt == null || code == null) {
            Log.i(TAG, "sign-in link ignored: no sign-in of this app waits");
            return false;
        }
        if (attempt.forIdentity()) {
            claimIdentity(attempt, code);
            return true;
        }
        mStore.save(null, null, null, null);
        mSignInStatus = SignInStatus.CLAIMING;
        mSignInProvider = attempt.provider;
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
                    } else if (problem.kind == SocialProblem.Kind.IDENTITY_NOT_LINKED) {
                        // SOC-ID: "Bu Microsoft hesabı henüz bir Projects hesabına bağlı değil. Önce Discord ile gir…"
                        mSignInStatus = SignInStatus.NOT_LINKED;
                    } else if (problem.kind == SocialProblem.Kind.PROVIDER_OFF) {
                        mSignInStatus = SignInStatus.PROVIDER_OFF;
                        readSignInOptions(true);
                    } else {
                        mSignInStatus = problem.kind == SocialProblem.Kind.UNAVAILABLE ? SignInStatus.UNAVAILABLE : SignInStatus.FAILED;
                    }
                    changed();
                });
            }
        });
        return true;
    }

    // ---- SOC-ID: linked accounts ("Bağlı hesaplar") ----

    /** Where a link or a removal stands (its browser step waits in {@link #mSignIn}). */
    public IdentityStatus identityStatus() {
        if (mToken == null) return IdentityStatus.IDLE;
        if (mIdentityStatus == IdentityStatus.STARTING || mIdentityStatus == IdentityStatus.CLAIMING) return mIdentityStatus;
        return mSignIn.identityWaiting(nowSeconds()) != null ? IdentityStatus.WAITING : IdentityStatus.IDLE;
    }

    /** The way in being linked or removed (while {@link #identityStatus} is not idle). */
    public String identityProvider() {
        return mIdentityProvider;
    }

    public SignInFlow.Purpose identityPurpose() {
        return mIdentityPurpose;
    }

    /** "Microsoft hesabını bağla": the service hands the page; the browser proves the account if needed, then links. */
    @MainThread
    public void linkIdentity(Activity activity, String provider) {
        startIdentity(activity, provider, SignInFlow.Purpose.LINK);
    }

    /**
     * "Kaldır" after its question: done at once when this session's sign-in is fresh, otherwise the browser proves a
     * way in that stays first.
     */
    @MainThread
    public void unlinkIdentity(Activity activity, String provider) {
        startIdentity(activity, provider, SignInFlow.Purpose.UNLINK);
    }

    private void startIdentity(final Activity activity, final String provider, final SignInFlow.Purpose purpose) {
        final String token = mToken;
        if (token == null || !SignInFlow.isProvider(provider) || identityStatus() != IdentityStatus.IDLE) return;
        final SignInFlow.Attempt made = SignInFlow.make(mRandom, nowSeconds(), provider, purpose);
        mSignIn.reset();
        mIdentityStatus = IdentityStatus.STARTING;
        mIdentityProvider = provider;
        mIdentityPurpose = purpose;
        changed();
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                final String url;
                final JsonObject meNow;
                if (purpose == SignInFlow.Purpose.LINK) {
                    url = mApi.linkIdentity(provider, made.challenge);
                    meNow = null;
                } else {
                    JsonObject answer = mApi.unlinkIdentity(provider, made.challenge);
                    url = Json.str(answer, "url");
                    meNow = url == null ? Json.obj(answer, "me") : null;
                }
                mMain.post(() -> {
                    // Signed out, or "Vazgeç" while the service was asked.
                    if (!token.equals(mToken) || mIdentityStatus != IdentityStatus.STARTING) return;
                    mIdentityStatus = IdentityStatus.IDLE;
                    if (meNow != null) {
                        identityDone(made, meNow);
                        return;
                    }
                    String page = SignInFlow.pageUrl(mApi.base(), url);
                    if (page == null) {
                        Log.w(TAG, "identity page refused");
                        changed();
                        identityProblem(new SocialProblem(SocialProblem.Kind.FAILED, 0, "page", 0), provider);
                        return;
                    }
                    SignInFlow.Attempt attempt = made.withUrl(page);
                    mSignIn.waitFor(attempt);
                    keepSession(null, null);
                    changed();
                    openSignInPage(pageHost(activity), attempt);
                });
            } catch (final SocialProblem problem) {
                mMain.post(() -> {
                    if (mIdentityStatus == IdentityStatus.STARTING) mIdentityStatus = IdentityStatus.IDLE;
                    if (!token.equals(mToken)) return;
                    changed();
                    if (!sessionProblem(problem)) identityProblem(problem, provider);
                });
            } finally {
                mApi.bind(null);
            }
        });
    }

    /** "Vazgeç" while a link or a removal waits for the browser (or for the service's page). */
    @MainThread
    public void cancelIdentity() {
        mSignIn.reset();
        mIdentityStatus = IdentityStatus.IDLE;
        if (mToken != null) keepSession(null, null);
        changed();
    }

    private void claimIdentity(final SignInFlow.Attempt attempt, final String code) {
        final String token = mToken;
        if (token == null) {
            mSignIn.reset();
            return;
        }
        mIdentityStatus = IdentityStatus.CLAIMING;
        mIdentityProvider = attempt.provider;
        mIdentityPurpose = attempt.purpose;
        keepSession(null, null);
        changed();
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                final JsonObject me = mApi.claimIdentity(attempt.verifier, code);
                mMain.post(() -> {
                    mIdentityStatus = IdentityStatus.IDLE;
                    if (mSignIn.phase() == SignInFlow.Phase.CLAIMING) mSignIn.reset();
                    if (!token.equals(mToken)) return;
                    identityDone(attempt, me);
                });
            } catch (final SocialProblem problem) {
                mMain.post(() -> {
                    mIdentityStatus = IdentityStatus.IDLE;
                    if (mSignIn.phase() == SignInFlow.Phase.CLAIMING) mSignIn.reset();
                    if (!token.equals(mToken)) return;
                    changed();
                    if (!sessionProblem(problem)) identityProblem(problem, attempt.provider);
                });
            } finally {
                mApi.bind(null);
            }
        });
    }

    /** A link or a removal is done: the profile the service answered with, its own note quietly seen, a short line. */
    private void identityDone(SignInFlow.Attempt attempt, JsonObject meJson) {
        Me me = Me.read(meJson);
        if (me == null) {
            changed();
            refreshMeOnly();
            return;
        }
        boolean linked = attempt.purpose == SignInFlow.Purpose.LINK;
        mOwnIdentityNote = (linked ? SocialModel.ModNotice.IDENTITY_LINKED : SocialModel.ModNotice.IDENTITY_REMOVED) + ":" + attempt.provider;
        mOwnIdentityNoteSince = attempt.startedAt;
        mPrefs.setOwnIdentityNote(mOwnIdentityNote, mOwnIdentityNoteSince);
        mState.setMe(me);
        keepSession(me.profile.id, meJson);
        // M048: a profile read outside the lists' read: no version describes the copy shown now.
        forgetRel();
        changed();
        Set<String> own = new HashSet<>();
        for (SocialModel.ModNotice n : me.notices) if (ownIdentityNote(n)) own.add(n.id);
        hideOwnNotes(own);
        Context context = LanguageManager.wrap(mApp);
        String name = providerName(attempt.provider);
        SocialUi.toast(context, linked ? context.getString(R.string.projects_identity_linked_toast, name)
                : context.getString(R.string.projects_identity_removed_toast, name));
    }

    /** What went wrong with a link or a removal, in the player's words. */
    private void identityProblem(SocialProblem problem, String provider) {
        Context context = LanguageManager.wrap(mApp);
        SocialUi.toastLong(context, SocialUi.identityProblemText(context, problem, provider));
    }

    /** A 401 or a restriction ends the session (as every request's): true when it did. */
    private boolean sessionProblem(SocialProblem problem) {
        if (problem.kind == SocialProblem.Kind.SIGNED_OUT) {
            sessionEnded(false, null);
            return true;
        }
        if (problem.restricted()) {
            sessionEnded(false, problem.until == null ? 0L : problem.until);
            return true;
        }
        return false;
    }

    /**
     * The service's note about a link or a removal made on this phone (the same kind and way in, written since it
     * started, give or take the clocks): no news here, so it is hidden without a word ({@link #hideOwnNotes}).
     */
    public boolean ownIdentityNote(SocialModel.ModNotice notice) {
        if (!notice.identity() || mOwnIdentityNote == null) return false;
        if (!mOwnIdentityNote.equals(notice.kind + ":" + notice.provider)) return false;
        return notice.at >= mOwnIdentityNoteSince - 600 && notice.at <= nowSeconds() + 600;
    }

    /**
     * SOC-ID: notes about a change made on this phone go from this phone only, never marked seen at the service:
     * that would take them from the account's other devices, where they warn about a way in someone else changed.
     */
    @MainThread
    public void hideOwnNotes(Set<String> ids) {
        if (ids.isEmpty()) return;
        mState.noticesSeen(ids);
        changed();
    }

    /** "Discord", "Microsoft": brand names, the same in both languages. */
    public static String providerName(@Nullable String provider) {
        return SignInFlow.MICROSOFT.equals(provider) ? "Microsoft" : "Discord";
    }

    /** SOC-ID: the name step's "Devam et" ({@code PATCH /v1/me {name}}); the answer's profile replaces the placeholder. */
    public void setName(String name, Result<JsonObject> result) {
        JsonObject body = new JsonObject();
        body.addProperty("name", ProfileName.clean(name));
        patchMe(body, result);
    }

    /**
     * Keeps the session on the phone: the token, the profile as the service sent it ({@code meJson}, else the last
     * one) and SOC-ID's link or removal that waits for the browser (so its page can still come back after Android
     * ended the app).
     */
    private void keepSession(@Nullable String userId, @Nullable JsonObject meJson) {
        if (meJson != null) mMeJson = meJson;
        SignInFlow.Attempt waiting = mSignIn.phase() == SignInFlow.Phase.WAITING && mSignIn.attempt() != null
                && mSignIn.attempt().forIdentity() ? mSignIn.attempt() : null;
        mStore.save(mToken, userId != null ? userId : mState.myId(), mMeJson, waiting);
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

    /** M21: a crew invite link ({@code projects://crew/<link>}) opened before signing in. */
    @Nullable
    private String mPendingCrewLink;

    @MainThread
    public void setPendingCrewLink(@Nullable String link) {
        mPendingCrewLink = link;
    }

    /** The invite link a link brought before the sign-in, once signed in (cleared by reading it). */
    @MainThread
    @Nullable
    public String takePendingCrewLink() {
        if (mToken == null) return null;
        String link = mPendingCrewLink;
        mPendingCrewLink = null;
        return link;
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
        // M048: nothing an earlier session read or kept describes this one.
        forgetRel();
        mState.setMe(me);
        mMeJson = null;
        keepSession(me.profile.id, Json.obj(answer, "me"));
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
                    // What the phone kept of the account goes with it, also when the account's socket closed (4002)
                    // before this answer and signed it out here first (the service's usual order; M048, as the
                    // launcher's SOC-DISK found): its chat history and its lists. Not another session's meanwhile.
                    boolean ours = token.equals(mToken);
                    if (ours || mToken == null) {
                        if (userId != null) mChat.deleteLocal(userId);
                        mKept.clear();
                        PushRegistrar.forget(mApp);
                    }
                    if (ours) forgetSession();
                    changed();
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
        mMain.removeCallbacks(mStaleRead);
        closeConnection();
        mToken = null;
        mApi.setToken(null);
        mState.clear();
        mStore.save(null, null, null, null);
        mPrefs.clearAccount();
        mSignIn.reset();
        mSignInStatus = SignInStatus.IDLE;
        // SOC-ID: the profile kept beside a link, the link or removal on its way, and its note.
        mMeJson = null;
        mIdentityStatus = IdentityStatus.IDLE;
        mOwnIdentityNote = null;
        mUnavailable = false;
        mReplaced = false;
        mLastFetch = 0;
        mOrderSent = null;
        // M048 (spec 6): the version held and the lists kept on the phone go with the session.
        forgetRel();
        mChat.close();
        SocialNotifications.cancelAll(mApp);
        // M21: the plan reminders were this account's.
        PlanAlarms.clear(mApp);
        // AFKPLUS-M: 7/24 Bulut's notices and what the phone kept about the player's accounts go too.
        gg.projects.mobile.cloud.CloudNotifications.cancelAll(mApp);
        if (gg.projects.mobile.cloud.CloudService.exists()) gg.projects.mobile.cloud.CloudService.get(mApp).forget();
        // SOC9 (TEAM4-B): and the crews' details kept on the phone.
        final java.io.File crews = mCrewRoot;
        mIo.execute(() -> CrewCache.clear(crews));
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
        // AFKPLUS-M: 7/24 Bulut's notices, once the service announces them (an older one does not know the kinds).
        if (mState.features().contains("afk")) kinds.addAll(gg.projects.mobile.cloud.CloudNotifications.wantedKinds(mApp));
        // MOB-VOICE: rings while the app has no socket, once the service announces them ("voiceRing"; an older one does
        // not know the kind) and the player keeps voice chat on.
        if (mState.features().contains(gg.projects.mobile.voice.VoicePush.FEATURE) && mVoice.config() && mVoice.prefs().enabled()
                && SocialNotifications.channelOn(mApp, gg.projects.mobile.voice.VoiceNotify.CHANNEL_RINGS)) {
            kinds.add(gg.projects.mobile.voice.VoicePush.KIND);
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

    /** AFKPLUS-M: 7/24 Bulut signs in with the friends session (one Discord sign-in for both). */
    @Nullable
    public String sessionToken() {
        return mToken;
    }

    /** AFKPLUS-M: a cloud notice was seen on screen; the service then sends no push for it. */
    @MainThread
    public boolean sendAfkSeen(String id) {
        PresenceConnection connection = mConnection;
        if (connection == null || !mLiveSocket) return false;
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "afk_seen");
        frame.addProperty("id", id);
        return connection.send(frame);
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
                        SocialUi.toast(context, context.getString(R.string.projects_social_friend_added, notice.who.name()));
                    }
                    break;
                case TEAM_INVITE:
                    if (foreground && notice.teamInvite != null && gate().teams && !surfaceShown(Surface.FRIENDS) && !surfaceShown(Surface.CREW)) {
                        SocialUi.toastLong(context, context.getString(R.string.projects_crew_invited_you,
                                notice.teamInvite.from.name(), notice.teamInvite.team.name));
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
                        if (text != null) SocialUi.toastLong(context, text);
                    }
                    break;
                case JOIN_REQUEST: {
                    // TEAM5: "Ayşe ekibine katılmak istiyor" while the app is up (the service pushes none to
                    // phones); the crew page shows the list itself. Quiet on do-not-disturb and while muted.
                    if (!foreground || notice.who == null || notice.crewId == null || !gate().requests) break;
                    if (surfaceShown(Surface.CREW) || crewQuiet(notice.crewId, mState.hubNow(now))) break;
                    String text = mState.crews().size() > 1 && notice.teamName != null
                            ? context.getString(R.string.projects_requests_notice_crew, notice.who.name(), notice.teamName)
                            : context.getString(R.string.projects_requests_notice, notice.who.name());
                    SocialUi.toastLong(context, text);
                    break;
                }
                case REQUEST_ACCEPTED:
                    // CHAT-CALM: a crowded crew's notice (with "Sesi aç") said it already.
                    if (foreground && notice.teamName != null && gate().teams && !crowdedSaid(notice.crewId)) {
                        SocialUi.toastLong(context, context.getString(R.string.projects_crew_joined, notice.teamName));
                    }
                    break;
                case PLAN: {
                    // TEAM5: "Ayşe Cumartesi 21:00 için plan yaptı" (the service already made it quiet for
                    // do-not-disturb and muted crews); nothing is pushed for plans.
                    if (!foreground || notice.plan == null || !gate().plans || surfaceShown(Surface.CREW)) break;
                    String who = notice.who == null ? context.getString(R.string.projects_crew_someone) : notice.who.name();
                    String text = context.getString(R.string.projects_plans_made, who, PlanViews.when(context, notice.plan.at, now, true));
                    // Several crews: the sentence names which one (no middle dot in UI text).
                    if (mState.crews().size() > 1 && notice.teamName != null) {
                        text = context.getString(R.string.projects_plans_made_crew, who, PlanViews.when(context, notice.plan.at, now, true),
                                notice.teamName);
                    }
                    SocialUi.toastLong(context, text);
                    break;
                }
                case PLAN_REMOVED:
                    if (notice.planId != null) PlanAlarms.forget(mApp, notice.planId);
                    break;
                case RESTRICTED:
                    // The socket closes with 4001 next: onEnded ends the session with this.
                    break;
                case MOD_NOTICE:
                    // The screens show the note once (they read me.notices).
                    break;
                case TAG_OFF:
                    // TEAM6: "Lider ekibi gizledi; etiketin çıkarıldı." (the desktop's toast).
                    if (foreground && gate().badges) {
                        SocialUi.toastLong(context, context.getString(R.string.projects_badge_hidden_toast));
                    }
                    break;
                case CROWDED: {
                    // CHAT-CALM: "Gece Kartalları kalabalık, bu yüzden senin için sessize alındı." with "Sesi aç" (the
                    // desktop's toast); the crew's bell says it anyway when the app is away. The join's own line
                    // ("… ekibine katıldın") would take its place (one line at a time): it stays away for a while.
                    if (!foreground || notice.crewId == null || notice.teamName == null || !gate().teams) break;
                    final String crewId = notice.crewId;
                    final Context shown = context;
                    mCrowdedSaid.put(crewId, android.os.SystemClock.elapsedRealtime());
                    SocialUi.toastAction(context, context.getString(R.string.projects_chat_calm_crowded, notice.teamName),
                            context.getString(R.string.projects_crew_unmute), () -> unmuteCrew(crewId, new Result<TeamNotifications>() {
                                @Override
                                public void ok(TeamNotifications value) {
                                    // The crew's bell and menus follow the state.
                                }

                                @Override
                                public void failed(SocialProblem problem) {
                                    SocialUi.toastProblem(shown, problem, null);
                                }
                            }));
                    break;
                }
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
        change(() -> mApi.request(code), result, this::applyRequestAnswer);
    }

    /**
     * FIX2 ("Arkadaş ekle" on a crewmate who is not a friend, with {@code crewFriendRequests}): the same
     * request without their code; the same answers.
     */
    public void sendRequestToCrewmate(final String userId, Result<JsonObject> result) {
        change(() -> mApi.requestCrewmate(userId), result, this::applyRequestAnswer);
    }

    private void applyRequestAnswer(JsonObject answer) {
        if ("friends".equals(Json.str(answer, "status"))) {
            Friend friend = Friend.read(Json.obj(answer, "friend"));
            if (friend != null) mState.addFriend(friend);
        } else {
            Profile user = Profile.read(Json.obj(answer, "user"));
            if (user != null) mState.addOutgoing(new Request(user, Json.num(answer, "sentAt", nowSeconds())));
        }
    }

    public void accept(final String from, Result<JsonObject> result) {
        change(() -> mApi.accept(from), result, answer -> {
            Friend friend = Friend.read(Json.obj(answer, "friend"));
            if (friend != null) mState.addFriend(friend);
            else mState.removeIncoming(from);
        });
    }

    public void decline(final String from, Result<Void> result) {
        change(() -> {
            mApi.decline(from);
            return null;
        }, result, value -> mState.removeIncoming(from));
    }

    public void cancelRequest(final String to, Result<Void> result) {
        change(() -> {
            mApi.cancelRequest(to);
            return null;
        }, result, value -> mState.removeOutgoing(to));
    }

    public void removeFriend(final String id, Result<Void> result) {
        change(() -> {
            mApi.removeFriend(id);
            return null;
        }, result, value -> {
            mState.removeFriend(id);
            mPrefs.setFavourite(id, false);
        });
    }

    public void block(final Profile profile, Result<Void> result) {
        change(() -> {
            mApi.block(profile.id);
            return null;
        }, result, value -> {
            mState.block(profile);
            mPrefs.setFavourite(profile.id, false);
            mVoice.blocked(profile.id);
        });
    }

    public void unblock(final String id, Result<Void> result) {
        change(() -> {
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
        change(() -> mApi.patchMe(body), result, answer -> {
            Me me = Me.read(answer);
            if (me != null) {
                mState.setMe(me);
                // TEAM6: the player's own tag shows the same in their crews' lists.
                mState.setMyBadge(me.profile.badge);
                keepSession(me.profile.id, answer);
                // M21: "Rahatsız etmeyin" keeps plan reminders quiet.
                syncPlanReminders();
            }
        });
    }

    /** TEAM6: wears the tag of one of the player's crews ({@code crewId}), or takes it off (null). */
    public void setBadge(@Nullable String crewId, Result<JsonObject> result) {
        JsonObject body = new JsonObject();
        if (crewId == null) body.add("badge", com.google.gson.JsonNull.INSTANCE);
        else body.addProperty("badge", crewId);
        patchMe(body, result);
    }

    public void rotateCode(Result<String> result) {
        change(mApi::rotateFriendCode, result, code -> refreshMeOnly());
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
                        keepSession(parsed.profile.id, me);
                        // M048: a profile read outside the lists' read: no version describes the copy shown now.
                        forgetRel();
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
        final boolean blocks = block != null && Json.bool(body, "block", false);
        OnOk<JsonObject> apply = answer -> {
            if (blocks) {
                mState.block(block);
                mPrefs.setFavourite(block.id, false);
            }
        };
        // M048: a report alone changes nothing the list shows; one with "Engelle" is the player's own change.
        if (blocks) change(() -> mApi.report(body), result, apply);
        else run(() -> mApi.report(body), result, apply);
    }

    /** The player saw these moderators' notes: they go here at once, from the service when it answers. */
    @MainThread
    public void noticesSeen(final Set<String> ids) {
        if (ids.isEmpty()) return;
        mState.noticesSeen(ids);
        // M048 (spec 5): the profile's notices changed here.
        forgetRel();
        changed();
        final String token = mToken;
        if (token == null) return;
        final List<String> list = new ArrayList<>(ids);
        mIo.execute(() -> {
            mApi.bind(token);
            try {
                mApi.noticesSeen(list);
                // SOC-REL-M: and again when the service has them as seen (as change() does): a list read on the other
                // thread answered before that keeps no version and no copy with the notes still in it.
                mMain.post(() -> {
                    if (token.equals(mToken)) forgetRel();
                });
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
        lookupTeam(code, null, result);
    }

    /** M21: a crew by its code or (TEAM5) its invite link, exactly one of them. */
    public void lookupTeam(@Nullable final String code, @Nullable final String link, Result<JsonObject> result) {
        final boolean multi = gate().multi;
        run(() -> link != null ? mApi.lookupTeamByLink(link, multi) : mApi.lookupTeam(code, multi), result, null);
    }

    public void joinTeam(String code, Result<JsonObject> result) {
        joinOrAsk(code, null, result);
    }

    /**
     * "Ekibe katıl" or "Katılma isteği gönder" (TEAM5: the same request; the service joins the player to
     * an open crew, or one they were invited to, and keeps a join request for one that approves
     * newcomers: {@code {request}} in the answer).
     */
    public void joinOrAsk(@Nullable final String code, @Nullable final String link, Result<JsonObject> result) {
        final boolean multi = gate().multi;
        final boolean request = gate().requests;
        change(() -> mApi.joinTeam(code, link, multi, request), result, answer -> {
            JsonObject asked = Json.obj(answer, "request");
            if (asked != null) {
                MyRequest mine = MyRequest.read(asked);
                if (mine != null) mState.addMyRequest(mine);
            } else {
                applyTeamAnswer(answer);
                Team team = Team.read(Json.obj(answer, "team"));
                if (team != null) noteOwnJoin(team.id);
            }
        });
    }

    /** TEAM5 "Geri çek": the player's waiting join request goes (one already gone counts as withdrawn). */
    public void withdrawJoinRequest(final String teamId, Result<Void> result) {
        change(() -> {
            try {
                mApi.withdrawJoinRequest(teamId);
            } catch (SocialProblem problem) {
                if (problem.kind != SocialProblem.Kind.JOIN_REQUEST_GONE) throw problem;
            }
            return null;
        }, result, value -> mState.removeMyRequest(teamId));
    }

    /** TEAM5 "Kabul et": the player comes into the crew; its card comes with the answer. */
    public void acceptJoinRequest(final String teamId, final String userId, Result<JsonObject> result) {
        change(() -> mApi.acceptJoinRequest(teamId, userId), result, answer -> {
            mState.removeCrewRequest(teamId, userId);
            applyTeamAnswer(answer);
        });
    }

    /** TEAM5: a declined request goes silently (its sender is told nothing). */
    public void declineJoinRequest(final String teamId, final String userId, Result<Void> result) {
        change(() -> {
            mApi.declineJoinRequest(teamId, userId);
            return null;
        }, result, value -> mState.removeCrewRequest(teamId, userId));
    }

    /** A request that is gone (answered on another device, withdrawn, a block): it leaves the list here. */
    @MainThread
    public void forgetJoinRequest(String teamId, String userId) {
        mState.removeCrewRequest(teamId, userId);
        // M048 (spec 5): the lists changed here.
        forgetRel();
        changed();
    }

    /** TEAM5 "Engelle" on a request row: the usual block, which takes the request too. */
    public void blockRequester(final String teamId, final Profile profile, Result<Void> result) {
        change(() -> {
            mApi.block(profile.id);
            return null;
        }, result, value -> {
            mState.block(profile);
            mState.removeCrewRequest(teamId, profile.id);
            mPrefs.setFavourite(profile.id, false);
        });
    }

    /** TEAM5: a new invite link for the crew ("Bağlantı oluştur", "Yeni bağlantı"); the old one stops at once. */
    public void createInviteLink(final String teamId, Result<String> result) {
        change(() -> {
            String link = SocialCodes.normalizeInviteLink(Json.str(mApi.createInviteLink(teamId), "link"));
            if (link == null) throw SocialProblem.unavailable("link");
            return link;
        }, result, link -> mState.setCrewLink(teamId, link));
    }

    /** TEAM5 "Bağlantıyı kapat". */
    public void deleteInviteLink(final String teamId, Result<Void> result) {
        change(() -> {
            mApi.deleteInviteLink(teamId);
            return null;
        }, result, value -> mState.setCrewLink(teamId, null));
    }

    /** TEAM6 "Görünürlük" (the leader's): a hidden crew's tag shows nowhere and cannot be worn. */
    public void setCrewHidden(final String teamId, boolean hidden, Result<JsonObject> result) {
        final JsonObject body = new JsonObject();
        body.addProperty("hidden", hidden);
        change(() -> mApi.patchTeam(teamId, body), result, this::applyTeamAnswer);
    }

    /** TEAM6 "Etiketten istek" (the leader's): whether friends of its wearers may ask from the tag's card. */
    public void setCrewBadgeRequests(final String teamId, boolean on, Result<JsonObject> result) {
        final JsonObject body = new JsonObject();
        body.addProperty("badgeRequests", on);
        change(() -> mApi.patchTeam(teamId, body), result, this::applyTeamAnswer);
    }

    /**
     * TEAM6 "Katılma isteği gönder" on a tag's card: the request always waits for the crew's answer
     * ({@code 202 {request}}), whatever the crew's join setting.
     */
    public void requestFromBadge(final String teamId, final String via, Result<JsonObject> result) {
        change(() -> mApi.requestFromBadge(teamId, via), result, answer -> {
            MyRequest mine = MyRequest.read(Json.obj(answer, "request"));
            if (mine != null) mState.addMyRequest(mine);
        });
    }

    /** CHAT-CALM: when a crowded crew's notice showed (crew id → elapsed ms), so a join's own line does not replace it. */
    private final Map<String, Long> mCrowdedSaid = new HashMap<>();
    private static final long CROWDED_SAID_MS = 10_000;

    /**
     * CHAT-CALM: the crowded-crew notice of {@code crewId} showed in the last few seconds: the join's own line ("Gece
     * Kartalları ekibine katıldın", one line at a time) would take its place and its "Sesi aç", so it stays away. The
     * socket's word comes before the join's answer.
     */
    public boolean crowdedSaid(@Nullable String crewId) {
        Long at = crewId == null ? null : mCrowdedSaid.get(crewId);
        return at != null && android.os.SystemClock.elapsedRealtime() - at < CROWDED_SAID_MS;
    }

    /**
     * CHAT-CALM "Yavaş mod" (the leader's and moderators'): seconds each other member waits between two new messages in
     * the crew's chat, one of {@link SlowMode#STEPS} (0: off).
     */
    public void setCrewSlow(final String teamId, int seconds, Result<JsonObject> result) {
        final JsonObject body = new JsonObject();
        body.addProperty("slow", SlowMode.step(seconds));
        change(() -> mApi.patchTeam(teamId, body), result, this::applyTeamAnswer);
    }

    /** TEAM5 "Yeni üyeleri onayla" (the leader's): {@code join} {@code approval} or {@code open}. */
    public void setJoinApproval(final String teamId, boolean approval, Result<JsonObject> result) {
        final JsonObject body = new JsonObject();
        body.addProperty("join", approval ? SocialModel.TeamSummary.JOIN_APPROVAL : SocialModel.TeamSummary.JOIN_OPEN);
        change(() -> mApi.patchTeam(teamId, body), result, this::applyTeamAnswer);
    }

    public void acceptTeamInvite(final String teamId, Result<JsonObject> result) {
        final boolean multi = gate().multi;
        change(() -> mApi.acceptTeamInvite(teamId, multi), result, answer -> {
            mState.removeTeamInvite(teamId);
            applyTeamAnswer(answer);
            noteOwnJoin(teamId);
        });
    }

    /** TEAM6: the player came into a crew from this phone: its chat starts with "Ekibe katıldın". */
    private void noteOwnJoin(String teamId) {
        String me = mState.myId();
        if (me != null && mState.crew(teamId) != null) mState.noteJoin(teamId, me, nowSeconds());
    }

    public void declineTeamInvite(final String teamId, Result<Void> result) {
        change(() -> {
            mApi.declineTeamInvite(teamId);
            return null;
        }, result, value -> mState.removeTeamInvite(teamId));
    }

    public void createTeam(JsonObject body, Result<JsonObject> result) {
        // A second crew (and every one with several crews) needs "multi"; an older service refuses the field.
        if (gate().multi) body.addProperty("multi", true);
        change(() -> mApi.createTeam(body), result, this::applyTeamAnswer);
    }

    public void leaveTeam(final String teamId, Result<Void> result) {
        change(() -> {
            mApi.leaveTeam(teamId);
            return null;
        }, result, value -> leftCrew(teamId));
    }

    public void disbandTeam(final String teamId, Result<Void> result) {
        change(() -> {
            mApi.disbandTeam(teamId);
            return null;
        }, result, value -> leftCrew(teamId));
    }

    private void leftCrew(String teamId) {
        if (gate().multi) mState.removeCrew(teamId);
        else mState.setTeam(null, null);
        rememberCrewNames();
        syncPlanReminders();
    }

    public void inviteToTeam(final String teamId, final String friendId, Result<Void> result) {
        change(() -> {
            mApi.inviteToTeam(teamId, friendId);
            return null;
        }, result, value -> mState.addCrewInvited(teamId, friendId));
    }

    public void removeMember(final String teamId, final String userId, Result<Void> result) {
        change(() -> {
            mApi.removeMember(teamId, userId);
            return null;
        }, result, value -> refreshTeam());
    }

    public void handOver(final String teamId, final String to, Result<JsonObject> result) {
        change(() -> mApi.handOver(teamId, to), result, this::applyTeamAnswer);
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
                    // M048: the crew read outside the lists' read: no version describes the copy shown now.
                    forgetRel();
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
        change(() -> {
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
        change(() -> TeamNotifications.read(mApi.crewNotifications(teamId, body)), result, value -> {
            mState.setCrewMute(teamId, value);
            syncPlanReminders();
        });
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
        change(() -> TeamNotifications.read(mApi.teamNotifications(body)), result, value -> {
            mState.setTeamNotifications(value);
            mPrefs.setCrewSettings(value);
            syncPush();
            syncPlanReminders();
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

    /** A socket message whose whole answer the caller reads (a plan's {@code ack} carries the plan). */
    private void socketAsk(final JsonObject message, final Result<JsonObject> result) {
        PresenceConnection connection = mConnection;
        if (connection == null || !mLiveSocket) {
            result.failed(SocialProblem.unavailable("no socket"));
            return;
        }
        connection.request(message, (PresenceConnection.Answer) answer -> {
            if (answer != null && "ack".equals(Json.str(answer, "t"))) {
                result.ok(answer);
                return;
            }
            result.failed(answer == null ? SocialProblem.unavailable("no answer") : SocialProblem.ofSocket(answer));
        });
    }

    // ---- plans (TEAM5; the socket only) ----

    /** Plans can be made and answered now: the service has them and the socket took {@code plans}. */
    public boolean plansLive() {
        return gate().plans && mLiveSocket && mState.caps().contains("plans");
    }

    /** "Plan yap": a time on Projects for the crew ({@code at}: a whole quarter hour, Unix seconds). */
    public void makePlan(final String crewId, long at, final Result<Plan> result) {
        makePlan(crewId, at, null, result);
    }

    /** The same with FIX2's note ({@code planNotes} only; null or "": none). */
    public void makePlan(final String crewId, long at, @Nullable String note, final Result<Plan> result) {
        JsonObject message = new JsonObject();
        message.addProperty("t", "plan");
        message.addProperty("crew", crewId);
        message.addProperty("at", at);
        String clean = PlanNote.clean(note);
        if (!clean.isEmpty() && gate().planNotes) message.addProperty("note", clean);
        socketAsk(message, new Result<JsonObject>() {
            @Override
            public void ok(JsonObject answer) {
                Plan plan = Plan.read(Json.obj(answer, "plan"));
                if (plan == null) {
                    result.failed(SocialProblem.unavailable("plan"));
                    return;
                }
                mState.putPlan(plan);
                syncPlanReminders();
                changed();
                result.ok(plan);
            }

            @Override
            public void failed(SocialProblem problem) {
                result.failed(problem);
            }
        });
    }

    /** Geliyorum / Belki / Gelemem to a plan: shown at once; a refusal brings the answer before back. */
    public void answerPlan(final String planId, final String answer, final Result<Void> result) {
        if (!Plan.isAnswer(answer)) {
            result.failed(new SocialProblem(SocialProblem.Kind.FAILED, 0, "bad_request", 0));
            return;
        }
        final String previous = mState.myPlanAnswer(planId);
        if (answer.equals(previous)) {
            result.ok(null);
            return;
        }
        JsonObject message = new JsonObject();
        message.addProperty("t", "plan_answer");
        message.addProperty("plan", planId);
        message.addProperty("answer", answer);
        mState.answerPlanLocally(planId, answer);
        changed();
        socketRequest(message, new Result<Void>() {
            @Override
            public void ok(Void value) {
                syncPlanReminders();
                result.ok(value);
            }

            @Override
            public void failed(SocialProblem problem) {
                if (problem.kind == SocialProblem.Kind.PLAN_GONE) {
                    // Over or cancelled meanwhile: it goes here too.
                    mState.removePlan(planId);
                    PlanAlarms.forget(mApp, planId);
                } else {
                    mState.restorePlanAnswer(planId, previous);
                }
                changed();
                result.failed(problem);
            }
        }, null);
    }

    /**
     * FIX2 ({@code planNotes}): the maker sets, changes or clears the note ({@code ""}); the crew's leader
     * and moderators may only clear it. The answer carries the plan as stored: it replaces the one here.
     */
    public void setPlanNote(final Plan plan, @Nullable String note, final Result<Plan> result) {
        JsonObject message = new JsonObject();
        message.addProperty("t", "plan_note");
        message.addProperty("crew", plan.crew);
        message.addProperty("id", plan.id);
        message.addProperty("note", PlanNote.clean(note));
        socketAsk(message, new Result<JsonObject>() {
            @Override
            public void ok(JsonObject answer) {
                Plan stored = Plan.read(Json.obj(answer, "plan"));
                // An older answer without the plan: the note as sent (the socket's plan frame follows anyway).
                Plan next = stored != null ? stored : plan.withNote(PlanNote.clean(note));
                mState.putPlan(next);
                changed();
                result.ok(next);
            }

            @Override
            public void failed(SocialProblem problem) {
                if (problem.kind == SocialProblem.Kind.PLAN_GONE) {
                    mState.removePlan(plan.id);
                    PlanAlarms.forget(mApp, plan.id);
                    changed();
                }
                result.failed(problem);
            }
        });
    }

    /** "Planı sil": its maker, the leader or a moderator; crewmates get {@code plan_removed}. */
    public void cancelPlan(final String planId, final Result<Void> result) {
        JsonObject message = new JsonObject();
        message.addProperty("t", "plan_cancel");
        message.addProperty("plan", planId);
        socketRequest(message, new Result<Void>() {
            @Override
            public void ok(Void value) {
                mState.removePlan(planId);
                PlanAlarms.forget(mApp, planId);
                changed();
                result.ok(value);
            }

            @Override
            public void failed(SocialProblem problem) {
                if (problem.kind == SocialProblem.Kind.PLAN_GONE) {
                    mState.removePlan(planId);
                    PlanAlarms.forget(mApp, planId);
                    changed();
                }
                result.failed(problem);
            }
        }, null);
    }

    /** "Plan hatırlatmaları" on this phone. */
    @MainThread
    public void setPlanReminders(boolean on) {
        mPrefs.setWantsPlanReminders(on);
        syncPlanReminders();
        changed();
    }

    /**
     * The plan reminders follow the plans the socket shows (once a {@code hello} with plans came in
     * this process: until then the stored ones stand) and the player's answers, the crews' names and
     * what keeps them quiet (do-not-disturb, muted crews).
     */
    private void syncPlanReminders() {
        long now = nowSeconds();
        boolean wanted = mToken != null && enabled() && mPrefs.wantsPlanReminders();
        if (!wanted || (mState.loaded() && !gate().plans)) {
            PlanAlarms.sync(mApp, Collections.<String, PlanReminders.Item>emptyMap(), now);
            return;
        }
        String me = mState.myId();
        if (me == null) return;
        PlanAlarms.setQuiet(mApp, planQuiet());
        if (!mState.plansKnown() || !mState.loaded()) return;
        Map<String, String> names = new HashMap<>();
        for (Crew c : mState.crews()) names.put(c.id(), c.team.name);
        PlanAlarms.sync(mApp, PlanReminders.desired(mState.plans(now), me, names, mState.hubNow(now)), now);
    }

    private PlanReminders.Quiet planQuiet() {
        Me me = mState.me();
        boolean dnd = me != null && SocialModel.Status.DND.equals(me.status);
        Map<String, Long> crews = new HashMap<>();
        for (Crew c : mState.crews()) {
            if (c.mute.muted) crews.put(c.id(), c.mute.muteUntil == null ? PlanReminders.FOREVER : c.mute.muteUntil);
        }
        return new PlanReminders.Quiet(dnd, muteEnd(mState.teamNotifications()), crews);
    }

    private static long muteEnd(TeamNotifications notifications) {
        if (!notifications.muted) return 0;
        return notifications.muteUntil == null ? PlanReminders.FOREVER : notifications.muteUntil;
    }

    /** The crew's notifications are silent now: do-not-disturb, every crew muted, or that one. */
    boolean crewQuiet(String crewId, long hubNowSeconds) {
        Me me = mState.me();
        if (me != null && SocialModel.Status.DND.equals(me.status)) return true;
        if (mState.teamNotifications().mutedAt(hubNowSeconds)) return true;
        Crew c = mState.crew(crewId);
        return c != null && c.mute.mutedAt(hubNowSeconds);
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
