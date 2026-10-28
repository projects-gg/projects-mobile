package gg.projects.mobile.cloud;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.cloud.CloudModel.Account;
import gg.projects.mobile.cloud.items.ItemData;
import gg.projects.mobile.cloud.items.ItemDataStore;
import gg.projects.mobile.social.Backoff;
import gg.projects.mobile.social.SocialService;

/**
 * 7/24 Bulut on the phone (AFKPLUS-M): the service's gate ({@code GET /offer}), the player's cloud accounts with the
 * friends session (the launcher's way: no separate sign-in), the actions on them, AFK+'s settings and answers, the
 * live socket while a screen streams, and the notices. One instance in the launcher's process; every method runs on
 * the main thread, network calls on two worker threads.
 *
 * <p>Nothing polls. The offer is read when a cloud screen or the main menu shows (at most once a minute; after a
 * failure, once in two minutes), the accounts with it when they are older than a minute and after each action,
 * the vault key when a secret is sealed (at most five minutes old), and the live socket runs only while a console
 * or an AI feed is on screen.
 */
public final class CloudService implements SocialService.Listener {
    private static final String TAG = "ProjectsCloud";
    private static final long OFFER_FRESH_MS = 60_000;
    private static final long OFFER_FAILED_MS = 120_000;
    private static final long ACCOUNTS_STALE_MS = 60_000;
    private static final long VAULT_FRESH_MS = 5 * 60_000;
    private static final long VERSIONS_FRESH_MS = 5 * 60_000;
    /** A live socket stays this long after its last screen leaves (a quick look elsewhere keeps it). */
    private static final long LIVE_GRACE_MS = 10_000;
    /** A movement operation the machine has not answered by now is out of reach (the contract's 3 s, and the way back). */
    private static final long MOVE_ANSWER_MS = 5_000;

    public interface Listener {
        @MainThread
        void onCloudChanged();
    }

    public interface Result<T> {
        void ok(T value);

        void failed(CloudProblem problem);
    }

    /** What the list can say. */
    public enum Load { IDLE, LOADING, LOADED, FAILED, SIGNED_OUT }

    private static CloudService sInstance;

    @MainThread
    public static synchronized CloudService get(Context context) {
        if (sInstance == null) sInstance = new CloudService(context.getApplicationContext());
        return sInstance;
    }

    public static synchronized boolean exists() {
        return sInstance != null;
    }

    private final Context mApp;
    private final CloudDebug mDebug;
    private final CloudApi mApi;
    private final CloudPrefs mPrefs;
    private final ExecutorService mIo = Executors.newFixedThreadPool(2);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();

    @Nullable
    private CloudModel.Offer mOffer;
    private long mOfferAt;
    private boolean mOfferFailed;
    private boolean mOfferFetching;

    @Nullable
    private CloudModel.Me mMe;
    private List<Account> mAccounts = Collections.emptyList();
    private Load mLoad = Load.IDLE;
    private long mFetchedAt;
    private boolean mFetching;
    private long mSkewMs;
    /** The account whose list was read: an answer for a session that is gone is dropped. */
    @Nullable
    private String mFetchedFor;

    @Nullable
    private CloudSeal.VaultKey mVault;
    private long mVaultAt;
    @Nullable
    private CloudModel.Versions mVersions;
    private long mVersionsAt;
    /** The game's item data by the Minecraft version asked for ("" for the manifest's default), once per process. */
    private final Map<String, ItemData> mItemData = new HashMap<>();
    private final Map<String, List<Result<ItemData>>> mItemDataWaiting = new HashMap<>();
    /** Items whose names this process already remembered for notices. */
    private final java.util.Set<String> mNamedItems = new java.util.HashSet<>();
    @Nullable
    private ItemDataStore mItemStore;
    @Nullable
    private PlusAuditCache mAuditCache;
    /** Each account's last AFK+ settings read here (custom items' names for notices and lists). */
    private final Map<String, CloudModel.PlusDoc> mPlusDocs = new HashMap<>();
    /** The newest unread counts of "Mesajlar" by account (from the last inbox read). */
    private final Map<String, Integer> mUnread = new HashMap<>();

    private CloudService(Context app) {
        mApp = app;
        mDebug = CloudDebug.read(app);
        mApi = new CloudApi(mDebug.url, ProjectsContent.userAgent(), line -> Log.i(TAG, line));
        mPrefs = new CloudPrefs(app);
        SocialService.get(app).addListener(this);
        mLastSession = SocialService.get(app).signedInId();
    }

    // ---- reading ----

    public CloudPrefs prefs() {
        return mPrefs;
    }

    public CloudApi api() {
        return mApi;
    }

    @Nullable
    public CloudModel.Offer offer() {
        return mOffer;
    }

    /** The service shows (on or in maintenance); before the first answer, what the phone saw last time. */
    public boolean available() {
        return mOffer != null ? mOffer.available() : mPrefs.offerOn();
    }

    /** AFK+ shows: the offer carries {@code plus} (null while {@code plusMode} is off). */
    public boolean plusOn() {
        return mOffer != null ? mOffer.plus != null : mPrefs.plusOn();
    }

    public boolean plusFeature(String name) {
        return mOffer != null && mOffer.plusFeature(name);
    }

    public CloudModel.Limits limits() {
        return mOffer != null ? mOffer.limits : CloudModel.Limits.defaults();
    }

    /** CAFK-FEAT: the service's switches (all off before the first answer and on an older service). */
    public CloudModel.OfferFeatures features() {
        return mOffer != null ? mOffer.features : CloudModel.OfferFeatures.none();
    }

    /** The save checks an account's switches ask for: placeholders by its own features, the service's command list. */
    public CloudEdit.Checks checks(@Nullable Account account) {
        return new CloudEdit.Checks(account != null && account.features.placeholders, features().placeholderCommands);
    }

    public boolean signedIn() {
        return SocialService.get(mApp).signedIn();
    }

    @Nullable
    private String token() {
        return SocialService.get(mApp).sessionToken();
    }

    public Load load() {
        if (!signedIn()) return Load.SIGNED_OUT;
        return mLoad;
    }

    @Nullable
    public CloudModel.Me me() {
        return mMe;
    }

    public List<Account> accounts() {
        return mAccounts;
    }

    @Nullable
    public Account account(String id) {
        for (Account a : mAccounts) if (a.id.equals(id)) return a;
        return null;
    }

    /** The service's clock now (this phone's plus the skew the last list showed). */
    public long nowMs() {
        return System.currentTimeMillis() + mSkewMs;
    }

    public long nowSeconds() {
        return nowMs() / 1000;
    }

    public int unread(String acct) {
        Integer n = mUnread.get(acct);
        return n == null ? 0 : n;
    }

    public void setUnread(String acct, int count) {
        Integer before = mUnread.put(acct, Math.max(0, count));
        if (before == null || before != count) {
            remember();
            changed();
        }
    }

    /** The main menu's summary: from the last list, or from the phone's record before the first answer. */
    @Nullable
    public CloudPrefs.Summary summary() {
        String user = SocialService.get(mApp).signedInId();
        if (user == null) return null;
        if (mLoad == Load.LOADED && user.equals(mFetchedFor)) return summaryOf(user);
        CloudPrefs.Summary saved = mPrefs.summary();
        return saved != null && user.equals(saved.user) ? saved : null;
    }

    private CloudPrefs.Summary summaryOf(String user) {
        int inGame = 0;
        int approvals = 0;
        int unread = 0;
        String stopped = "";
        for (Account a : mAccounts) {
            if (CloudStatus.inGame(a)) inGame++;
            if (a.ai != null) approvals += a.ai.pendingApprovals;
            unread += unread(a.id);
            if (stopped.isEmpty() && a.hold != null && "cause".equals(a.hold.kind)) stopped = a.name;
        }
        return new CloudPrefs.Summary(user, mAccounts.size(), inGame, approvals, unread, stopped);
    }

    private void remember() {
        String user = mFetchedFor;
        if (user != null && mLoad == Load.LOADED) mPrefs.setSummary(summaryOf(user));
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
        for (Listener l : new ArrayList<>(mListeners)) l.onCloudChanged();
    }

    // ---- the friends session ----

    @Nullable
    private String mLastSession;

    @Override
    public void onSocialChanged() {
        String now = SocialService.get(mApp).signedInId();
        boolean same = now == null ? mLastSession == null : now.equals(mLastSession);
        if (same) return;
        mLastSession = now;
        if (now == null) {
            forget();
        } else {
            // Signed in (or as someone else): read again.
            mFetchedAt = 0;
            mAccounts = Collections.emptyList();
            mMe = null;
            mLoad = Load.IDLE;
            refresh(false);
        }
        changed();
    }

    /** "Discord ile giriş yap": the friends sign-in (one session for both). */
    @MainThread
    public void signIn(Activity activity) {
        SocialService.get(mApp).beginSignIn(activity);
    }

    /** Signed out of the friends service: what was read about the player, the sockets and the notices go. */
    @MainThread
    public void forget() {
        closeAllLive();
        mAccounts = Collections.emptyList();
        mMe = null;
        mLoad = Load.IDLE;
        mFetchedAt = 0;
        mFetchedFor = null;
        mUnread.clear();
        mPlusDocs.clear();
        mPrefs.clearAccount();
        // The audit log is the player's: it goes with the session (README "Deleting data": the devices drop their caches).
        mIo.execute(() -> {
            PlusAuditCache cache = auditCache();
            if (cache != null) cache.keepOnly(Collections.<String>emptyList());
        });
        CloudNotifications.cancelAll(mApp);
        changed();
    }

    // ---- the offer and the list ----

    /** Reads the offer and, with a session, the player and their accounts unless they are fresh ({@code force}: "Tekrar dene"). */
    @MainThread
    public void refresh(boolean force) {
        long now = System.currentTimeMillis();
        boolean offerFresh = mOfferAt > 0 && now - mOfferAt < (mOfferFailed ? OFFER_FAILED_MS : OFFER_FRESH_MS);
        if (!offerFresh || (force && mOfferFailed)) fetchOffer(force);
        else if (available()) fetchAccounts(force);
    }

    private void fetchOffer(final boolean force) {
        if (mOfferFetching) return;
        mOfferFetching = true;
        mIo.execute(() -> {
            CloudModel.Offer offer = null;
            try {
                offer = mApi.offer();
            } catch (CloudProblem problem) {
                Log.i(TAG, "offer not read: " + problem.kind);
            }
            final CloudModel.Offer answer = offer;
            mMain.post(() -> {
                mOfferFetching = false;
                mOfferAt = System.currentTimeMillis();
                mOfferFailed = answer == null;
                if (answer != null) {
                    mOffer = answer;
                    mPrefs.setOffer(answer.available(), answer.plus != null);
                    if (!answer.available()) {
                        closeAllLive();
                        CloudNotifications.cancelAll(mApp);
                    }
                    // Notifications open with AFK+ (the service sends none while plusMode is off): the push kinds follow.
                    SocialService.get(mApp).syncPush();
                }
                changed();
                if (available()) fetchAccounts(force);
            });
        });
    }

    private void fetchAccounts(boolean force) {
        final String token = token();
        final String user = SocialService.get(mApp).signedInId();
        if (token == null || user == null || mFetching) return;
        long now = System.currentTimeMillis();
        if (!force && mLoad == Load.LOADED && user.equals(mFetchedFor) && now - mFetchedAt < ACCOUNTS_STALE_MS) return;
        mFetching = true;
        if (mLoad != Load.LOADED || !user.equals(mFetchedFor)) mLoad = Load.LOADING;
        changed();
        final long asked = System.currentTimeMillis();
        mIo.execute(() -> {
            try {
                final CloudModel.Me me = mApi.me(token);
                final CloudApi.AccountList list = mApi.accounts(token);
                mMain.post(() -> {
                    mFetching = false;
                    if (!token.equals(token())) return;
                    mMe = me;
                    mAccounts = Collections.unmodifiableList(new ArrayList<>(list.accounts));
                    mFetchedFor = user;
                    mFetchedAt = System.currentTimeMillis();
                    if (list.now > 0) mSkewMs = list.now * 1000 - (asked + mFetchedAt) / 2;
                    mLoad = Load.LOADED;
                    remember();
                    // The kept audit logs of accounts that are not AFK+ any more go (the launcher's auditPrune).
                    final List<String> plus = new ArrayList<>();
                    for (Account a : list.accounts) if (a.plus()) plus.add(a.id);
                    mIo.execute(() -> {
                        PlusAuditCache cache = auditCache();
                        if (cache != null) cache.keepOnly(plus);
                    });
                    changed();
                });
            } catch (final CloudProblem problem) {
                mMain.post(() -> {
                    mFetching = false;
                    if (!token.equals(token())) return;
                    if (problem.kind == CloudProblem.Kind.SIGNED_OUT) {
                        sessionGone();
                        return;
                    }
                    if (mLoad != Load.LOADED) mLoad = Load.FAILED;
                    changed();
                });
            }
        });
    }

    /** A 401: the friends session is gone; the friends service reads its list again and ends it. */
    private void sessionGone() {
        mLoad = Load.SIGNED_OUT;
        SocialService.get(mApp).refresh(true);
        changed();
    }

    /** Puts an account the service answered with into the list (no extra read). */
    @MainThread
    public void store(Account account) {
        List<Account> next = new ArrayList<>(mAccounts);
        boolean found = false;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id.equals(account.id)) {
                next.set(i, account);
                found = true;
            }
        }
        if (!found) return;
        mAccounts = Collections.unmodifiableList(next);
        remember();
        changed();
    }

    // ---- work on the worker threads ----

    private interface Call<T> {
        T run(String token) throws CloudProblem;
    }

    private <T> void run(final Call<T> call, final Result<T> result) {
        final String token = token();
        if (token == null) {
            result.failed(new CloudProblem(CloudProblem.Kind.SIGNED_OUT, 401, null, 0, null));
            return;
        }
        mIo.execute(() -> {
            try {
                final T value = call.run(token);
                mMain.post(() -> result.ok(value));
            } catch (final CloudProblem problem) {
                mMain.post(() -> {
                    if (problem.kind == CloudProblem.Kind.SIGNED_OUT) sessionGone();
                    result.failed(problem);
                });
            } catch (RuntimeException e) {
                Log.w(TAG, "cloud request failed", e);
                mMain.post(() -> result.failed(CloudProblem.failed(e.getClass().getSimpleName())));
            }
        });
    }

    /** One action that answers with the account: it goes into the list. */
    private void accountAction(final Call<Account> call, final Result<Account> result) {
        run(call, new Result<Account>() {
            @Override
            public void ok(Account value) {
                store(value);
                result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                // What the service holds now may differ from what was shown.
                mFetchedAt = 0;
                fetchAccounts(true);
                result.failed(problem);
            }
        });
    }

    public void start(final String id, Result<Account> result) {
        accountAction(token -> mApi.start(token, id), result);
    }

    public void stop(final String id, Result<Account> result) {
        accountAction(token -> mApi.stop(token, id), result);
    }

    /** "Yeniden bağlan" (never repeated by itself). */
    public void restart(final String id, Result<Account> result) {
        accountAction(token -> mApi.restart(token, id), result);
    }

    /** Reads one account now (the settings screen starts from the newest). */
    public void fetchAccount(final String id, Result<Account> result) {
        accountAction(token -> mApi.account(token, id), result);
    }

    private CloudSeal.VaultKey vaultKey() throws CloudProblem {
        CloudSeal.VaultKey key = mVault;
        if (key != null && System.currentTimeMillis() - mVaultAt < VAULT_FRESH_MS) return key;
        key = mApi.vaultKey();
        if (!CloudSeal.ALG.equals(key.alg)) throw CloudProblem.failed("vault key of another suite");
        mVault = key;
        mVaultAt = System.currentTimeMillis();
        return key;
    }

    private String owner(String token) throws CloudProblem {
        CloudModel.Me me = mMe;
        if (me != null && !me.userId.isEmpty()) return me.userId;
        CloudModel.Me fresh = mApi.me(token);
        mMe = fresh;
        return fresh.userId;
    }

    /** A console line; a password command is sealed on the phone first. The console never echoes it. */
    public void send(final String id, final String typed, Result<Void> result) {
        final String line = typed.trim();
        run(token -> {
            CloudText.Problem problem = CloudText.check(line);
            if (problem != null) {
                JsonObject error = new JsonObject();
                error.addProperty("reason", problem.code);
                throw new CloudProblem(CloudProblem.Kind.REFUSED, 400, "invalid_command", 0, error);
            }
            if (!CloudText.looksSensitive(line)) {
                mApi.sendLine(token, id, line);
                return null;
            }
            Account account = account(id);
            if (account == null) account = mApi.account(token, id);
            String aad = CloudSeal.sealAad(owner(token), id, CloudSeal.CONSOLE_ROW, account.server);
            try {
                mApi.sendSealed(token, id, CloudSeal.seal(vaultKey(), line, aad));
            } catch (CloudSeal.SealException e) {
                throw CloudProblem.failed("seal " + e.error);
            }
            return null;
        }, result);
    }

    public void logs(final String id, final long from, final long to, @Nullable final String after, Result<CloudModel.LogPage> result) {
        run(token -> mApi.logs(token, id, from, to, 500, after), result);
    }

    /** "Göster": one stored secret row's words from the account's machine, sealed to a key made for this request. */
    public void reveal(final String id, final String rowId, Result<String> result) {
        run(token -> {
            CloudSeal.RevealKey key = CloudSeal.RevealKey.generate();
            CloudApi.Revealed answer = mApi.reveal(token, id, rowId, key.publicKey());
            try {
                return key.open(answer.revealed, CloudSeal.revealAad(owner(token), id, rowId, answer.nonce));
            } catch (CloudSeal.SealException e) {
                JsonObject error = new JsonObject();
                error.addProperty("reason", "unreadable");
                throw new CloudProblem(CloudProblem.Kind.REFUSED, 409, "secret_unreadable", 0, error);
            }
        }, result);
    }

    public void versions(Result<CloudModel.Versions> result) {
        CloudModel.Versions cached = mVersions;
        if (cached != null && System.currentTimeMillis() - mVersionsAt < VERSIONS_FRESH_MS) {
            result.ok(cached);
            return;
        }
        mIo.execute(() -> {
            try {
                final CloudModel.Versions versions = mApi.versions();
                mMain.post(() -> {
                    mVersions = versions;
                    mVersionsAt = System.currentTimeMillis();
                    result.ok(versions);
                });
            } catch (final CloudProblem problem) {
                mMain.post(() -> result.failed(problem));
            }
        });
    }

    /** How a save ended. */
    public static final class SaveOutcome {
        @Nullable
        public final Account saved;
        @Nullable
        public final Account conflict;
        public final List<CloudEdit.Issue> issues;

        SaveOutcome(@Nullable Account saved, @Nullable Account conflict, List<CloudEdit.Issue> issues) {
            this.saved = saved;
            this.conflict = conflict;
            this.issues = issues;
        }
    }

    /**
     * Saves the settings screen's change: secrets sealed here, stored seals kept, a new server sealed again for. A
     * change made elsewhere meanwhile comes back as a conflict with the newer account.
     */
    public void save(final Account base, @Nullable final String name, @Nullable final String hostTyped, final int port,
                     @Nullable final String version, @Nullable final String lang, @Nullable final CloudEdit.Config config,
                     Result<SaveOutcome> result) {
        run(token -> {
            CloudServer server = null;
            if (hostTyped != null) {
                CloudServer.Host host = CloudServer.normalizeHost(hostTyped);
                if (host.value == null) {
                    return new SaveOutcome(null, null, Collections.singletonList(new CloudEdit.Issue("general", -1, "server",
                            host.problem == CloudServer.Problem.DOT ? "dot" : host.problem == CloudServer.Problem.PRIVATE ? "private" : "format")));
                }
                if (CloudServer.checkPort(port) != null) {
                    return new SaveOutcome(null, null, Collections.singletonList(new CloudEdit.Issue("general", -1, "port", "port")));
                }
                server = new CloudServer(host.value, port);
            }
            Account current = mApi.account(token, base.id);
            if (current.rev != base.rev) return new SaveOutcome(null, current, Collections.<CloudEdit.Issue>emptyList());
            boolean serverChanged = server != null && !server.equals(current.server);
            CloudEdit.Config edited = config;
            if (edited == null && serverChanged && hasSecrets(current)) edited = CloudEdit.from(current.config);
            JsonObject configJson = null;
            if (edited != null) {
                final CloudServer target = serverChanged ? server : current.server;
                final CloudSeal.VaultKey key = vaultKey();
                final String owner = owner(token);
                final String acct = current.id;
                CloudEdit.Built built;
                try {
                    built = CloudEdit.build(edited, limits(), checks(current), serverChanged,
                            (text, rowId) -> CloudSeal.seal(key, text, CloudSeal.sealAad(owner, acct, rowId, target)), current.config.fingerprints());
                } catch (CloudSeal.SealException e) {
                    throw CloudProblem.failed("seal " + e.error);
                }
                if (!built.issues.isEmpty()) return new SaveOutcome(null, null, built.issues);
                configJson = built.config;
            }
            JsonObject patch = CloudEdit.patch(current, name, serverChanged ? server : null, version, lang, configJson);
            if (patch.size() == 0) return new SaveOutcome(current, null, Collections.<CloudEdit.Issue>emptyList());
            try {
                return new SaveOutcome(mApi.patchAccount(token, current.id, current.rev, patch), null, Collections.<CloudEdit.Issue>emptyList());
            } catch (CloudProblem problem) {
                List<CloudEdit.Issue> issues = CloudEdit.fromRefusal(problem);
                if (issues != null) return new SaveOutcome(null, null, issues);
                if (problem.kind == CloudProblem.Kind.CONFLICT) {
                    return new SaveOutcome(null, mApi.account(token, current.id), Collections.<CloudEdit.Issue>emptyList());
                }
                throw problem;
            }
        }, new Result<SaveOutcome>() {
            @Override
            public void ok(SaveOutcome value) {
                if (value.saved != null) store(value.saved);
                if (value.conflict != null) store(value.conflict);
                result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                result.failed(problem);
            }
        });
    }

    private static boolean hasSecrets(Account account) {
        for (CloudModel.CommandView c : account.config.commands) if (c.payload.secret) return true;
        for (CloudModel.RuleView r : account.config.rules) if (r.reply.secret) return true;
        return false;
    }

    /**
     * CAFK-FEAT: the account's inventory view on or off (its own choice, {@code config.inventory}). It applies at the
     * account's next connection; nothing restarts for it. Stored secrets are kept as they are; a change made elsewhere
     * meanwhile is read again and the switch tried once more on it. A stored row the save checks refuse comes back as
     * the issues (the settings screen shows where).
     */
    public void setInventory(final String id, final boolean on, final Result<SaveOutcome> result) {
        run(token -> {
            for (int attempt = 0; ; attempt++) {
                Account current = mApi.account(token, id);
                CloudEdit.Config config = CloudEdit.from(current.config);
                config.inventory = on;
                List<CloudEdit.Issue> issues = CloudEdit.validate(config, limits(), checks(current));
                if (!issues.isEmpty()) return new SaveOutcome(null, null, issues);
                CloudEdit.Built built;
                try {
                    // Every secret is SEALED here (never typed): build keeps them, nothing is sealed.
                    built = CloudEdit.build(config, limits(), checks(current), false, (text, rowId) -> {
                        throw new CloudSeal.SealException(CloudSeal.Error.KEY);
                    }, current.config.fingerprints());
                } catch (CloudSeal.SealException e) {
                    throw CloudProblem.failed("seal " + e.error);
                }
                if (built.config == null) return new SaveOutcome(null, null, built.issues);
                try {
                    return new SaveOutcome(mApi.patchAccount(token, id, current.rev, CloudEdit.patch(current, null, null, null, null, built.config)),
                            null, Collections.<CloudEdit.Issue>emptyList());
                } catch (CloudProblem problem) {
                    if (problem.kind == CloudProblem.Kind.CONFLICT && attempt == 0) continue;
                    List<CloudEdit.Issue> refused = CloudEdit.fromRefusal(problem);
                    if (refused != null) return new SaveOutcome(null, null, refused);
                    throw problem;
                }
            }
        }, new Result<SaveOutcome>() {
            @Override
            public void ok(SaveOutcome value) {
                if (value.saved != null) store(value.saved);
                result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                result.failed(problem);
            }
        });
    }

    /**
     * Several accounts at once (README "Bulk management"): start, stop or reconnect each, in the list's order, waiting
     * for each answer; one outcome with the first failure's reason. The accounts the service answered with go into the
     * list as they come.
     */
    public void bulk(final CloudBulk.Action action, final List<String> ids, final Result<CloudBulk.Outcome> result) {
        run(token -> {
            CloudBulk.Outcome outcome = new CloudBulk.Outcome();
            for (String id : ids) {
                try {
                    final Account answered;
                    switch (action) {
                        case STOP: answered = mApi.stop(token, id); break;
                        case RESTART: answered = mApi.restart(token, id); break;
                        default: answered = mApi.start(token, id); break;
                    }
                    outcome.ok();
                    mMain.post(() -> store(answered));
                } catch (CloudProblem problem) {
                    if (problem.kind == CloudProblem.Kind.SIGNED_OUT) throw problem;
                    outcome.fail(problem.code);
                }
            }
            return outcome;
        }, new Result<CloudBulk.Outcome>() {
            @Override
            public void ok(CloudBulk.Outcome value) {
                mFetchedAt = 0;
                fetchAccounts(true);
                result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                mFetchedAt = 0;
                fetchAccounts(true);
                result.failed(problem);
            }
        });
    }

    /**
     * "Ayarları kopyala": {@code source}'s anti-AFK, commands, replies and owner's name onto each target, with the server
     * and version when {@code server} is true. Secret rows take the words typed for each target ({@code secrets}: target
     * id → row id → words), sealed here for that target and its server. One {@code PATCH} per target with its own
     * revision; a change made elsewhere meanwhile is read again and tried once more.
     */
    public void copySettings(final Account source, final List<String> targets, final boolean server,
                             final Map<String, Map<String, String>> secrets, final Result<CloudBulk.Outcome> result) {
        run(token -> {
            CloudBulk.Outcome outcome = new CloudBulk.Outcome();
            final CloudSeal.VaultKey key = vaultKey();
            final String owner = owner(token);
            for (final String id : targets) {
                Map<String, String> typed = secrets.get(id);
                CloudEdit.Config config = CloudBulk.copied(source.config, typed == null ? Collections.<String, String>emptyMap() : typed);
                boolean sent = false;
                for (int attempt = 0; attempt < 2 && !sent; attempt++) {
                    try {
                        final Account current = mApi.account(token, id);
                        final CloudServer target = server ? source.server : current.server;
                        CloudEdit.Built built;
                        try {
                            built = CloudEdit.build(config, limits(), checks(current), false,
                                    (text, rowId) -> CloudSeal.seal(key, text, CloudSeal.sealAad(owner, id, rowId, target)),
                                    Collections.<String, String>emptyMap());
                        } catch (CloudSeal.SealException e) {
                            throw CloudProblem.failed("seal " + e.error);
                        }
                        if (built.config == null) {
                            outcome.fail("invalid_config");
                            sent = true;
                            continue;
                        }
                        JsonObject patch = CloudBulk.patch(current, built.config, server && !source.server.equals(current.server) ? source.server : null,
                                server && !source.version.equals(current.version) ? source.version : null);
                        final Account answered = mApi.patchAccount(token, id, current.rev, patch);
                        outcome.ok();
                        sent = true;
                        mMain.post(() -> store(answered));
                    } catch (CloudProblem problem) {
                        if (problem.kind == CloudProblem.Kind.SIGNED_OUT) throw problem;
                        if (problem.kind == CloudProblem.Kind.CONFLICT && attempt == 0) continue;
                        outcome.fail(problem.code);
                        sent = true;
                    }
                }
            }
            return outcome;
        }, new Result<CloudBulk.Outcome>() {
            @Override
            public void ok(CloudBulk.Outcome value) {
                mFetchedAt = 0;
                fetchAccounts(true);
                result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                result.failed(problem);
            }
        });
    }

    // ---- notifications (the player's switches, the same on every device) ----

    public void notifyPrefs(Result<CloudModel.NotifyPrefs> result) {
        run(mApi::notifications, result);
    }

    public void putNotifyPrefs(final CloudModel.NotifyPrefs prefs, Result<CloudModel.NotifyPrefs> result) {
        run(token -> mApi.putNotifications(token, prefs), result);
    }

    // ---- AFK+ ----

    public void plus(final String id, Result<CloudModel.PlusDoc> result) {
        run(token -> mApi.plus(token, id), keepDoc(id, result));
    }

    public void putPlus(final String id, final long aiRev, final JsonObject config, Result<CloudModel.PlusDoc> result) {
        run(token -> mApi.putPlus(token, id, aiRev, config), keepWritten(id, result));
    }

    /** The account's last AFK+ settings read on this phone (null: none yet). */
    @Nullable
    public CloudModel.PlusDoc lastPlus(String id) {
        return mPlusDocs.get(id);
    }

    /** Keeps the settings an answer brought (custom items' names), then hands them on. */
    /**
     * A write's answer ({@code PUT}, a restore) holds no rules, spent sums or order views: it is laid over the document
     * read before, so a screen after a save still knows them (the payment choice needs {@code rules.features.itemPay}).
     */
    private Result<CloudModel.PlusDoc> keepWritten(final String id, final Result<CloudModel.PlusDoc> result) {
        return new Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc value) {
                CloudModel.PlusDoc known = mPlusDocs.get(id);
                CloudModel.PlusDoc merged = known == null ? value : known.after(value);
                rememberDoc(id, merged);
                result.ok(merged);
            }

            @Override
            public void failed(CloudProblem problem) {
                result.failed(problem);
            }
        };
    }

    private Result<CloudModel.PlusDoc> keepDoc(final String id, final Result<CloudModel.PlusDoc> result) {
        return new Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc value) {
                rememberDoc(id, value);
                result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                result.failed(problem);
            }
        };
    }

    /** After an order write: the settings with the new document and revision. */
    @MainThread
    public void rememberDoc(String id, CloudModel.PlusDoc doc) {
        mPlusDocs.put(id, doc);
        Map<String, String[]> names = new HashMap<>();
        for (JsonObject custom : gg.projects.mobile.social.Json.objects(doc.config, "customItems")) {
            String k = gg.projects.mobile.social.Json.str(custom, "k");
            String name = gg.projects.mobile.social.Json.str(custom, "name");
            if (k != null && name != null && !name.isEmpty()) names.put(id + "|custom:" + k, new String[]{name, name});
        }
        mPrefs.rememberItems(names);
    }

    // ---- AFK+ trade orders ----

    /** "Kontrol et". */
    public void checkOrder(final String id, final String text, final String lang, Result<PlusModel.Check> result) {
        checkOrder(id, text, lang, false, result);
    }

    /** "Kontrol et" again once the player said the money is the game's ("Bu oyun parasi"). */
    public void checkOrder(final String id, final String text, final String lang, final boolean inGame, Result<PlusModel.Check> result) {
        run(token -> mApi.checkOrder(token, id, text, lang, inGame), result);
    }

    public void postOrder(final String id, final long aiRev, final JsonObject order, @Nullable final com.google.gson.JsonArray customItems,
                          @Nullable final JsonObject settings, Result<PlusModel.Written> result) {
        run(token -> mApi.postOrder(token, id, aiRev, order, customItems, settings), result);
    }

    public void patchOrder(final String id, final long aiRev, final String orderId, final JsonObject fields, Result<PlusModel.Written> result) {
        run(token -> mApi.patchOrder(token, id, aiRev, orderId, fields), result);
    }

    public void deleteOrder(final String id, final long aiRev, final String orderId, Result<PlusModel.Written> result) {
        run(token -> mApi.deleteOrder(token, id, aiRev, orderId), result);
    }

    /** "Envanterden seç": what the machine read in the picked slot. */
    public void pick(final String id, final int slot, final String itemId, @Nullable final Long cmd, @Nullable final String model,
                     Result<PlusModel.Pick> result) {
        run(token -> mApi.pick(token, id, slot, itemId, cmd, model), result);
    }

    /** A page of the audit log from the machine; what it brings is kept on the phone (7 days) with what came before. */
    public void audit(final String id, @Nullable final Long before, @Nullable final String group, final Result<PlusModel.AuditPage> result) {
        run(token -> {
            PlusModel.AuditPage page = mApi.audit(token, id, before, null, 100, group);
            PlusAuditCache cache = auditCache();
            if (cache != null) cache.merge(id, page.items, nowMs());
            return page;
        }, result);
    }

    /** What this phone kept of the account's audit log (newest first; read off the main thread, answered on it). */
    public void keptAudit(final String id, final Result<List<PlusModel.AuditEntry>> result) {
        mIo.execute(() -> {
            PlusAuditCache cache = auditCache();
            final List<PlusModel.AuditEntry> kept = cache == null ? Collections.<PlusModel.AuditEntry>emptyList() : cache.read(id, nowMs());
            mMain.post(() -> result.ok(kept));
        });
    }

    @Nullable
    private synchronized PlusAuditCache auditCache() {
        if (mAuditCache == null) mAuditCache = new PlusAuditCache(new java.io.File(mApp.getFilesDir(), "plus-audit"));
        return mAuditCache;
    }

    public void receipts(final String id, @Nullable final String before, Result<PlusModel.ReceiptPage> result) {
        run(token -> mApi.receipts(token, id, before), result);
    }

    // ---- the game's item data (FREE-ITEMS: names, stacks, durability, enchantments) ----

    /** The item data of Minecraft {@code mc} (null: the manifest's default), from the CDN or the phone's copy. */
    @MainThread
    public void itemData(@Nullable final String mc, final Result<ItemData> result) {
        final String key = mc == null ? "" : mc;
        ItemData known = mItemData.get(key);
        if (known != null) {
            result.ok(known);
            return;
        }
        List<Result<ItemData>> waiting = mItemDataWaiting.get(key);
        if (waiting != null) {
            waiting.add(result);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(result);
        mItemDataWaiting.put(key, waiting);
        mIo.execute(() -> {
            ItemData loaded;
            try {
                loaded = itemStore().load(mc);
                // The search tables are built here, not on the first keystroke.
                if (loaded != null) loaded.warm(CloudWords.lang(mApp));
            } catch (Throwable t) {
                // Even out of memory on a big file: no data (the screens say so), and the waiting list still hears.
                Log.w(TAG, "item data not read: " + t.getClass().getSimpleName());
                loaded = null;
            }
            final ItemData data = loaded;
            mMain.post(() -> {
                List<Result<ItemData>> those = mItemDataWaiting.remove(key);
                if (data != null) mItemData.put(key, data);
                if (those == null) return;
                for (Result<ItemData> r : those) {
                    if (data != null) r.ok(data);
                    else r.failed(CloudProblem.unavailable("item data"));
                }
            });
        });
    }

    /** The item data of {@code mc} if this process loaded it already. */
    @Nullable
    @MainThread
    public ItemData cachedItemData(@Nullable String mc) {
        return mItemData.get(mc == null ? "" : mc);
    }

    private synchronized ItemDataStore itemStore() {
        if (mItemStore == null) {
            mItemStore = mDebug.itemData == null ? new ItemDataStore(mApp.getCacheDir(), mApi.client())
                    : new ItemDataStore(mApp.getCacheDir(), mApi.client(), mDebug.itemData);
        }
        return mItemStore;
    }

    /**
     * An item's name in {@code lang}: a custom item ({@code custom:<k>}) by the account's own name for it, a vanilla one by
     * the item data this process read (remembered for notices), else the phone's record, else its id's words.
     */
    @MainThread
    public String itemName(@Nullable String acct, String ref, String lang) {
        if (ref.startsWith("custom:")) {
            String k = ref.substring("custom:".length());
            CloudModel.PlusDoc doc = acct == null ? null : mPlusDocs.get(acct);
            if (doc != null) {
                for (JsonObject custom : gg.projects.mobile.social.Json.objects(doc.config, "customItems")) {
                    if (k.equals(gg.projects.mobile.social.Json.str(custom, "k"))) {
                        String name = gg.projects.mobile.social.Json.str(custom, "name");
                        if (name != null && !name.isEmpty()) return name;
                    }
                }
            }
            String remembered = acct == null ? null : mPrefs.rememberedName(acct + "|" + ref, lang);
            return remembered != null ? remembered : mApp.getString(net.kdt.pojavlaunch.R.string.projects_plus_custom_item);
        }
        for (ItemData data : mItemData.values()) {
            String name = data.name(ref, lang);
            if (name != null) {
                // Remembered once a process for notices (a long feed names the same items again and again).
                if (mNamedItems.add(ref)) {
                    Map<String, String[]> names = new HashMap<>();
                    String tr = data.name(ref, "tr");
                    String en = data.name(ref, "en");
                    names.put(ref, new String[]{tr == null ? name : tr, en == null ? name : en});
                    mPrefs.rememberItems(names);
                }
                return name;
            }
        }
        return mPrefs.itemName(ref, lang);
    }

    /** {@code pause}/{@code resume}/{@code block}/{@code unblock}/{@code forget}/{@code reset_spent}; the account's AI view is updated. */
    public void control(final String id, final String action, @Nullable final String name, final Result<JsonObject> result) {
        run(token -> mApi.plusControl(token, id, action, name), new Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                JsonObject ai = gg.projects.mobile.social.Json.obj(value, "ai");
                Account account = account(id);
                if (ai != null && account != null) store(account.withAi(ai));
                result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                result.failed(problem);
            }
        });
    }

    public void approvals(final String id, Result<List<CloudModel.Approval>> result) {
        run(token -> mApi.approvals(token, id), result);
    }

    public void decide(final String id, final String approval, final boolean approve, @Nullable final Long maxPrice,
                       final Result<CloudModel.Approval> result) {
        run(token -> mApi.decide(token, id, approval, approve, maxPrice), new Result<CloudModel.Approval>() {
            @Override
            public void ok(CloudModel.Approval value) {
                CloudNotifications.cancelApproval(mApp, approval);
                mFetchedAt = 0;
                fetchAccounts(true);
                result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                if (problem.is("approval_closed")) CloudNotifications.cancelApproval(mApp, approval);
                result.failed(problem);
            }
        });
    }

    public void quota(final String id, Result<CloudModel.QuotaView> result) {
        run(token -> mApi.quota(token, id), result);
    }

    public void dealSummary(final String id, Result<CloudApi.DealSummary> result) {
        run(token -> mApi.dealSummary(token, id), result);
    }

    public void weekly(Result<List<CloudModel.WeeklyAccount>> result) {
        run(mApi::weekly, result);
    }

    public void inbox(final String id, final Result<CloudApi.InboxList> result) {
        run(token -> mApi.inbox(token, id, null), new Result<CloudApi.InboxList>() {
            @Override
            public void ok(CloudApi.InboxList value) {
                int unread = 0;
                for (CloudModel.InboxThread t : value.threads) unread += t.unread;
                setUnread(id, unread);
                result.ok(value);
            }

            @Override
            public void failed(CloudProblem problem) {
                result.failed(problem);
            }
        });
    }

    public void thread(final String id, final String name, Result<CloudModel.InboxPage> result) {
        run(token -> mApi.thread(token, id, name, null), result);
    }

    public void reply(final String id, final String name, final String text, Result<CloudModel.InboxLine> result) {
        run(token -> mApi.reply(token, id, name, text), result);
    }

    public void markRead(final String id, final String name, final long upTo) {
        run(token -> {
            mApi.markRead(token, id, name, upTo);
            return null;
        }, new Result<Object>() {
            @Override
            public void ok(Object value) {
            }

            @Override
            public void failed(CloudProblem problem) {
                Log.i(TAG, "read mark not sent: " + problem.kind);
            }
        });
    }

    public void handBack(final String id, final String name, Result<Void> result) {
        run(token -> {
            mApi.handBack(token, id, name);
            return null;
        }, result);
    }

    public void history(final String id, Result<List<CloudModel.HistoryEntry>> result) {
        run(token -> mApi.history(token, id), result);
    }

    public void restore(final String id, final long rev, final long aiRev, Result<CloudModel.PlusDoc> result) {
        run(token -> mApi.restore(token, id, rev, aiRev), keepWritten(id, result));
    }

    public void downgradePreview(final String id, Result<CloudModel.DowngradePreview> result) {
        run(token -> mApi.downgradePreview(token, id), result);
    }

    public void downgrade(final String id, Result<Account> result) {
        accountAction(token -> mApi.downgrade(token, id), result);
    }

    // ---- the site ----

    /** A page of afk.projects.gg in a Custom Tab ({@code /satin-al?hesap=…}, {@code /hesap/…}). */
    @MainThread
    public void openSite(Activity activity, String path) {
        String url = mApi.siteUrl(CloudApi.sitePath(path, CloudWords.lang(activity)));
        if (mDebug.logBrowser) {
            // Debug builds on the emulator: no browser runs there.
            Log.i(TAG, "site page: " + url);
            return;
        }
        SocialService.openBrowser(activity, url);
    }

    // ---- the live socket ----

    /** What a screen that streams gets from the socket. Every call on the main thread. */
    public interface LiveListener {
        /** The socket's state for this account: connecting, live, or ended with a reason. */
        void liveState(Live state);

        void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot);

        void approval(CloudModel.Approval approval);

        /** AFK+: new audit log entries (already kept on the phone). */
        default void audit(List<PlusModel.AuditEntry> items) {}

        /** AFK+: orders whose progress or live state changed. */
        default void orders(Map<String, PlusModel.OrderView> orders) {}

        void hostOnline(boolean online);

        /** CAFK-FEAT: the status strip (action bar, title, boss bars) of the account. */
        default void bars(gg.projects.mobile.cloud.console.Bars bars) {}
    }

    /** CAFK-FEAT: what an inventory screen gets (only while it watches with the inventory). Main thread. */
    public interface InventoryListener {
        void invState(gg.projects.mobile.cloud.inventory.InvFrames.Status status);

        void inventory(gg.projects.mobile.cloud.inventory.InvFrames.Inv frame);

        void window(gg.projects.mobile.cloud.inventory.InvFrames.Win frame);
    }

    /** Movement: what the "Hareket" panel gets (only while it watches with movement). Main thread. */
    public interface MovementListener {
        void moveState(gg.projects.mobile.cloud.movement.MoveFrames.State state);

        void position(gg.projects.mobile.cloud.movement.MoveFrames.Pos pos);
    }

    /** The answer to one movement operation: {@code error} null when the account started it. Main thread, once. */
    public interface MoveAnswer {
        void answered(@Nullable gg.projects.mobile.cloud.movement.MoveFrames.Error error);
    }

    public enum Live { CONNECTING, LIVE, LOST, REPLACED, SIGNED_OUT, HOST_OFFLINE, REFUSED }

    /** A screen's watch of one account; {@link #close()} when the screen goes. */
    public final class LiveHandle {
        final String acct;
        final boolean ai;
        final LiveListener listener;
        @Nullable
        final InventoryListener inventory;
        @Nullable
        final MovementListener movement;
        private boolean mClosed;

        LiveHandle(String acct, boolean ai, LiveListener listener, @Nullable InventoryListener inventory, @Nullable MovementListener movement) {
            this.acct = acct;
            this.ai = ai;
            this.listener = listener;
            this.inventory = inventory;
            this.movement = movement;
        }

        /**
         * Sends one movement operation of this account; {@code answer} comes once, on the main thread (also when the
         * socket cannot carry it: {@code host_offline}).
         */
        @MainThread
        public void moveOp(gg.projects.mobile.cloud.movement.MoveOp op, MoveAnswer answer) {
            if (mClosed) return;
            sendMoveOp(this, op, answer);
        }

        /**
         * Sends one inventory move of this account; {@code answer} comes once, on the main thread (also when the socket
         * cannot carry it: {@code host_offline}).
         */
        @MainThread
        public void move(gg.projects.mobile.cloud.inventory.InvOp op, gg.projects.mobile.cloud.inventory.InvQueue.Answer answer) {
            if (mClosed) return;
            sendMove(this, op, answer);
        }

        @MainThread
        public void close() {
            if (mClosed) return;
            mClosed = true;
            unwatch(this);
        }
    }

    private final class HostSocket implements CloudLive.Listener {
        final String host;
        @Nullable
        CloudLive live;
        final Backoff backoff = new Backoff(new Random());
        final List<LiveHandle> handles = new ArrayList<>();
        Live state = Live.CONNECTING;
        boolean online = true;
        final Runnable reconnect = this::connect;
        final Runnable closeIdle = this::closeIfIdle;

        HostSocket(String host) {
            this.host = host;
        }

        void connect() {
            mMain.removeCallbacks(reconnect);
            if (live != null || handles.isEmpty()) return;
            String token = token();
            if (token == null) {
                broadcast(Live.SIGNED_OUT);
                return;
            }
            broadcast(Live.CONNECTING);
            live = CloudLive.open(mApi, token, host, this);
            for (LiveHandle h : handles) live.watch(h.acct, h.ai);
            for (LiveHandle h : handles) if (h.inventory != null) live.inventory(h.acct, true);
            for (LiveHandle h : handles) if (h.movement != null) live.movement(h.acct, true);
        }

        /** Whether any screen still wants {@code acct}'s inventory. */
        boolean wantsInventory(String acct) {
            for (LiveHandle h : handles) if (h.acct.equals(acct) && h.inventory != null) return true;
            return false;
        }

        /** Whether any screen still wants {@code acct}'s movement. */
        boolean wantsMovement(String acct) {
            for (LiveHandle h : handles) if (h.acct.equals(acct) && h.movement != null) return true;
            return false;
        }

        @Override
        public void moveState(String acct, gg.projects.mobile.cloud.movement.MoveFrames.State state) {
            for (LiveHandle h : of(acct)) if (h.movement != null) h.movement.moveState(state);
        }

        @Override
        public void position(String acct, gg.projects.mobile.cloud.movement.MoveFrames.Pos pos) {
            for (LiveHandle h : of(acct)) if (h.movement != null) h.movement.position(pos);
        }

        @Override
        public void moveResult(String id, @Nullable gg.projects.mobile.cloud.movement.MoveFrames.Error error) {
            mMoveOpHosts.remove(id);
            MoveAnswer answer = mMoveOps.remove(id);
            if (answer != null) answer.answered(error);
        }

        @Override
        public void invState(String acct, gg.projects.mobile.cloud.inventory.InvFrames.Status status) {
            for (LiveHandle h : of(acct)) if (h.inventory != null) h.inventory.invState(status);
        }

        @Override
        public void inventory(String acct, gg.projects.mobile.cloud.inventory.InvFrames.Inv frame) {
            for (LiveHandle h : of(acct)) if (h.inventory != null) h.inventory.inventory(frame);
        }

        @Override
        public void window(String acct, gg.projects.mobile.cloud.inventory.InvFrames.Win frame) {
            for (LiveHandle h : of(acct)) if (h.inventory != null) h.inventory.window(frame);
        }

        @Override
        public void invResult(String id, @Nullable gg.projects.mobile.cloud.inventory.InvFrames.Error error) {
            gg.projects.mobile.cloud.inventory.InvQueue.Answer answer = mMoves.remove(id);
            if (answer != null) answer.answered(error);
        }

        @Override
        public void bars(String acct, gg.projects.mobile.cloud.console.Bars bars) {
            for (LiveHandle h : of(acct)) h.listener.bars(bars);
        }

        void closeIfIdle() {
            if (!handles.isEmpty()) return;
            mMain.removeCallbacks(reconnect);
            if (live != null) live.close();
            live = null;
            mSockets.remove(host);
        }

        void broadcast(Live next) {
            state = next;
            for (LiveHandle h : new ArrayList<>(handles)) h.listener.liveState(next);
        }

        List<LiveHandle> of(String acct) {
            List<LiveHandle> out = new ArrayList<>();
            for (LiveHandle h : handles) if (h.acct.equals(acct)) out.add(h);
            return out;
        }

        @Override
        public void hello(boolean hostOnline, List<String> accounts, long leaseSeconds, long now) {
            backoff.healthy();
            online = hostOnline;
            broadcast(hostOnline ? Live.LIVE : Live.HOST_OFFLINE);
            for (LiveHandle h : new ArrayList<>(handles)) h.listener.hostOnline(hostOnline);
        }

        @Override
        public void state(String acct, JsonObject liveState) {
            Account account = account(acct);
            if (account != null) store(account.withLive(liveState));
        }

        @Override
        public void console(String acct, List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {
            for (LiveHandle h : of(acct)) h.listener.console(lines, skipped, snapshot);
        }

        @Override
        public void host(boolean hostOnline) {
            online = hostOnline;
            broadcast(hostOnline ? Live.LIVE : Live.HOST_OFFLINE);
            for (LiveHandle h : new ArrayList<>(handles)) h.listener.hostOnline(hostOnline);
        }

        @Override
        public void error(String code, @Nullable String acct) {
            Live next = "host_offline".equals(code) ? Live.HOST_OFFLINE : "not_found".equals(code) ? Live.REFUSED : null;
            if (next == null) return;
            for (LiveHandle h : acct == null ? new ArrayList<>(handles) : of(acct)) h.listener.liveState(next);
        }

        @Override
        public void ai(String acct, JsonObject ai) {
            Account account = account(acct);
            if (account != null) store(account.withAi(ai));
        }

        @Override
        public void audit(final String acct, final List<PlusModel.AuditEntry> items) {
            if (items.isEmpty()) return;
            for (LiveHandle h : of(acct)) if (h.ai) h.listener.audit(items);
            mIo.execute(() -> {
                PlusAuditCache cache = auditCache();
                if (cache != null) cache.merge(acct, items, nowMs());
            });
        }

        @Override
        public void orders(String acct, Map<String, PlusModel.OrderView> orders) {
            for (LiveHandle h : of(acct)) if (h.ai) h.listener.orders(orders);
            CloudModel.PlusDoc doc = mPlusDocs.get(acct);
            if (doc != null) mPlusDocs.put(acct, doc.withOrders(orders));
        }

        @Override
        public void message(String acct, int count, long at) {
            if (count > unread(acct)) setUnread(acct, count);
        }

        @Override
        public void approval(String acct, CloudModel.Approval approval) {
            for (LiveHandle h : of(acct)) h.listener.approval(approval);
            if (!approval.pending(nowSeconds())) CloudNotifications.cancelApproval(mApp, approval.id);
            mFetchedAt = 0;
            fetchAccounts(true);
        }

        @Override
        public void bye(@Nullable String reason) {
            // The close that follows says what to do.
        }

        @Override
        public void ended(CloudLive ended, CloudLiveFrames.After after, long retryAfter, boolean failed) {
            if (ended != live) return;
            live = null;
            failMoves(host);
            switch (after) {
                case REPLACED:
                    broadcast(Live.REPLACED);
                    return;
                case SIGNED_OUT:
                    broadcast(Live.SIGNED_OUT);
                    sessionGone();
                    return;
                case RECONNECT_NOW:
                    // The player's accounts on this machine changed: read them again, then reconnect.
                    mFetchedAt = 0;
                    fetchAccounts(true);
                    mMain.postDelayed(reconnect, 500);
                    return;
                case WAIT_A_MINUTE:
                    broadcast(Live.LOST);
                    mMain.postDelayed(reconnect, backoff.atLeastMs(60));
                    return;
                case BACKOFF:
                default:
                    broadcast(Live.LOST);
                    mMain.postDelayed(reconnect, retryAfter > 0 ? backoff.atLeastMs(retryAfter) : backoff.nextDelayMs());
            }
        }
    }

    private final Map<String, HostSocket> mSockets = new HashMap<>();
    /** Inventory moves waiting for their {@code invres}, by id, and the machine they went to. */
    private final Map<String, gg.projects.mobile.cloud.inventory.InvQueue.Answer> mMoves = new HashMap<>();
    private final Map<String, String> mMoveHosts = new HashMap<>();
    private long mMoveCounter;
    /** Movement operations waiting for their {@code movres}, by id, and the machine they went to. */
    private final Map<String, MoveAnswer> mMoveOps = new HashMap<>();
    private final Map<String, String> mMoveOpHosts = new HashMap<>();

    /**
     * Streams one account while a screen shows it ({@code ai}: its AI feed and approvals too). Null when the account
     * has no machine yet (the console says so).
     */
    @MainThread
    @Nullable
    public LiveHandle watch(String acct, boolean ai, LiveListener listener) {
        return watch(acct, ai, listener, null);
    }

    /**
     * {@link #watch(String, boolean, LiveListener)} with the account's inventory too (CAFK-FEAT): only while an inventory
     * view is visible (the service streams frames to every watcher that asks).
     */
    @MainThread
    @Nullable
    public LiveHandle watch(String acct, boolean ai, LiveListener listener, @Nullable InventoryListener inventory) {
        return watch(acct, ai, listener, inventory, null);
    }

    /** {@link #watch(String, boolean, LiveListener, InventoryListener)} with movement: only while its panel is visible. */
    @MainThread
    @Nullable
    public LiveHandle watch(String acct, boolean ai, LiveListener listener, @Nullable InventoryListener inventory,
                            @Nullable MovementListener movement) {
        Account account = account(acct);
        if (account == null || account.host == null) return null;
        HostSocket socket = mSockets.get(account.host);
        if (socket == null) {
            socket = new HostSocket(account.host);
            mSockets.put(account.host, socket);
        }
        mMain.removeCallbacks(socket.closeIdle);
        LiveHandle handle = new LiveHandle(acct, ai, listener, inventory, movement);
        socket.handles.add(handle);
        if (socket.live == null) socket.connect();
        else {
            socket.live.watch(acct, ai);
            if (inventory != null) socket.live.inventory(acct, true);
            if (movement != null) socket.live.movement(acct, true);
            listener.liveState(socket.state);
        }
        return handle;
    }

    private void sendMoveOp(LiveHandle handle, gg.projects.mobile.cloud.movement.MoveOp op, final MoveAnswer answer) {
        Account account = account(handle.acct);
        HostSocket socket = account == null || account.host == null ? null : mSockets.get(account.host);
        mMoveCounter = (mMoveCounter + 1) % 1_000_000_000L;
        // Its own prefix: ids are unique among a socket's pending operations of every kind.
        String id = "v" + Long.toString(mMoveCounter, 36);
        if (socket == null || socket.live == null || !socket.live.send(op.frame(handle.acct, id))) {
            mMain.post(() -> answer.answered(gg.projects.mobile.cloud.movement.MoveFrames.Error.of("host_offline")));
            return;
        }
        mMoveOps.put(id, answer);
        mMoveOpHosts.put(id, socket.host);
        // No answer within the contract's 3 seconds and a little: the machine is out of reach.
        mMain.postDelayed(() -> {
            if (!mMoveOpHosts.containsKey(id)) return;
            mMoveOpHosts.remove(id);
            MoveAnswer late = mMoveOps.remove(id);
            if (late != null) late.answered(gg.projects.mobile.cloud.movement.MoveFrames.Error.of("host_offline"));
        }, MOVE_ANSWER_MS);
    }

    private void sendMove(LiveHandle handle, gg.projects.mobile.cloud.inventory.InvOp op,
                          final gg.projects.mobile.cloud.inventory.InvQueue.Answer answer) {
        Account account = account(handle.acct);
        HostSocket socket = account == null || account.host == null ? null : mSockets.get(account.host);
        mMoveCounter = (mMoveCounter + 1) % 1_000_000_000L;
        String id = "m" + Long.toString(mMoveCounter, 36);
        if (socket == null || socket.live == null || !socket.live.send(op.frame(handle.acct, id))) {
            mMain.post(() -> answer.answered(gg.projects.mobile.cloud.inventory.InvFrames.Error.of("host_offline")));
            return;
        }
        mMoves.put(id, answer);
        mMoveHosts.put(id, socket.host);
    }

    /** A socket that ended takes its moves with it: they are answered "out of reach" at once (never sent again). */
    private void failMoves(String host) {
        for (String id : new ArrayList<>(mMoveHosts.keySet())) {
            if (!host.equals(mMoveHosts.get(id))) continue;
            mMoveHosts.remove(id);
            gg.projects.mobile.cloud.inventory.InvQueue.Answer answer = mMoves.remove(id);
            if (answer != null) answer.answered(gg.projects.mobile.cloud.inventory.InvFrames.Error.of("host_offline"));
        }
        for (String id : new ArrayList<>(mMoveOpHosts.keySet())) {
            if (!host.equals(mMoveOpHosts.get(id))) continue;
            mMoveOpHosts.remove(id);
            MoveAnswer answer = mMoveOps.remove(id);
            if (answer != null) answer.answered(gg.projects.mobile.cloud.movement.MoveFrames.Error.of("host_offline"));
        }
    }

    /** "Burada aç" after the console was opened elsewhere: this phone takes it back. */
    @MainThread
    public void reopen(String acct) {
        Account account = account(acct);
        HostSocket socket = account == null || account.host == null ? null : mSockets.get(account.host);
        if (socket == null) return;
        socket.backoff.reset();
        socket.connect();
    }

    private void unwatch(LiveHandle handle) {
        for (HostSocket socket : new ArrayList<>(mSockets.values())) {
            if (!socket.handles.remove(handle)) continue;
            boolean stillWatched = !socket.of(handle.acct).isEmpty();
            if (!stillWatched && socket.live != null) socket.live.unwatch(handle.acct);
            else if (socket.live != null) {
                // The console stays; the inventory and movement streams stop with the last view that wanted them.
                if (handle.inventory != null && !socket.wantsInventory(handle.acct)) socket.live.inventory(handle.acct, false);
                if (handle.movement != null && !socket.wantsMovement(handle.acct)) socket.live.movement(handle.acct, false);
            }
            if (socket.handles.isEmpty()) mMain.postDelayed(socket.closeIdle, LIVE_GRACE_MS);
        }
    }

    private void closeAllLive() {
        for (HostSocket socket : new ArrayList<>(mSockets.values())) {
            mMain.removeCallbacks(socket.reconnect);
            mMain.removeCallbacks(socket.closeIdle);
            if (socket.live != null) socket.live.close();
            socket.live = null;
            failMoves(socket.host);
            socket.broadcast(Live.LOST);
            socket.handles.clear();
        }
        mSockets.clear();
    }

    // ---- notices from the friends service's socket ----

    /** An {@code afk_notice} on the presence socket (with {@code afk} in its caps): shown, and marked seen when on screen. */
    @MainThread
    public void onSocketNotice(JsonObject frame, @Nullable Activity onScreen) {
        CloudNotice notice = CloudNotice.fromSocket(frame);
        if (notice == null) return;
        CloudNotice.Decision decision = notice.decide(null, nowSeconds());
        if (decision.action == CloudNotice.Action.CANCEL) {
            String approval = notice.approvalId();
            if (approval != null) CloudNotifications.cancelApproval(mApp, approval);
            mFetchedAt = 0;
            fetchAccounts(true);
            return;
        }
        if (decision.action != CloudNotice.Action.SHOW) return;
        // What the notice is about may have changed what the screens show.
        mFetchedAt = 0;
        fetchAccounts(true);
        if (onScreen != null && !onScreen.isFinishing() && onScreen.hasWindowFocus()) {
            // Seen only when the line really showed: then the service sends no push for it.
            if (CloudNotifications.showInApp(onScreen, notice)) SocialService.get(mApp).sendAfkSeen(notice.id);
        } else if (!notice.quiet) {
            CloudNotifications.show(mApp, notice, CloudPush.gameRunning(mApp));
        }
        // A quiet-hours notice with the app off screen shows nothing now: unseen, it comes in the morning's digest.
    }
}
