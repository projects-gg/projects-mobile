package gg.projects.mobile.update;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.content.ContentConfig;
import gg.projects.mobile.content.ContentSources;
import gg.projects.mobile.content.HttpFetcher;
import gg.projects.mobile.content.HttpStatusException;
import gg.projects.mobile.controls.KeyFixer;
import gg.projects.mobile.join.ProjectsJoin;

/**
 * APK-UPD: the app's own updates from the signed Android channel (docs/content-format.md, "Android release
 * channel"; the owner, 30 October 2026). One background thread; the state for the home card on the main
 * thread.
 *
 * <ul>
 * <li>The check: when the home screen opens, with "Güncellemeleri otomatik denetle" on, at most once a day
 * ({@link UpdatePolicy#checkDue}); nothing runs in the background, nothing polls. The verified channel file
 * stays on the phone and is verified again whenever it is read.</li>
 * <li>The card: from that file on every opening while an update waits and no snooze holds it ("Kapat" 2
 * hours, "7 gün sonra hatırlat" 7 days, a newer version at once).</li>
 * <li>"Güncelle": the channel read afresh, the APK downloaded with progress (on the card and in the app's
 * progress notification, which keeps the download alive when the player leaves), its size and SHA-256, its
 * package, version and signing certificates checked against the installed app and the channel, then
 * Android's installer ({@link ApkInstaller}). Never while the game or another download runs.</li>
 * </ul>
 * Debug builds read only a channel the debug override names ({@code updateMirrors}, {@code updateTestKey});
 * the Play build has no updater (Play forbids an app updating itself another way).
 */
public final class AppUpdates {
    private AppUpdates() {}

    private static final String TAG = "AppUpdates";
    /** The settings switch "Güncellemeleri otomatik denetle" (default on). */
    public static final String KEY_AUTO_CHECK = "projects_update_check";
    /** Upstream's progress record: keeps the progress service (and so the download) alive. */
    static final String PROGRESS_KEY = "projects_app_update";
    private static final String PREFS = "projects_updates";
    private static final String KEY_LAST_OK = "last_ok";
    private static final String KEY_LAST_TRY = "last_try";
    private static final String KEY_SNOOZE_CODE = "snooze_code";
    private static final String KEY_SNOOZE_SET = "snooze_set";
    private static final String KEY_SNOOZE_UNTIL = "snooze_until";
    /** The version an install was handed to Android for (the "updated" notification's condition). */
    private static final String KEY_INSTALLING = "installing";
    private static final int NOTIFICATION_ID = 46;

    public enum Phase { HIDDEN, OFFER, DOWNLOADING, VERIFYING, INSTALLING }

    /** What the home card shows. */
    public static final class State {
        public final Phase phase;
        @Nullable
        public final UpdateManifest manifest;
        public final long done;
        public final long total;
        /** Why the last try stopped (shown under the offer), or null. */
        @Nullable
        public final UpdateException.Kind error;

        State(Phase phase, @Nullable UpdateManifest manifest, long done, long total, @Nullable UpdateException.Kind error) {
            this.phase = phase;
            this.manifest = manifest;
            this.done = done;
            this.total = total;
            this.error = error;
        }

        static final State HIDDEN = new State(Phase.HIDDEN, null, 0, 0, null);

        public int percent() {
            return total <= 0 ? 0 : (int) Math.min(100, done * 100 / total);
        }
    }

    public interface Listener {
        /** On the main thread. */
        void onUpdateState(State state);
    }

    public interface CheckCallback {
        /** On the main thread: the update found (null: none), or why the check failed. */
        void onChecked(@Nullable UpdateManifest update, @Nullable UpdateException.Kind error);
    }

    /** Where the channel comes from, the keys, the app's folder for it, and whether it is used at all. */
    static final class Sources {
        final boolean usable;
        final List<String> mirrors;
        final List<Minisign.PublicKey> keys;
        final File dir;
        final boolean allowHttp;

        Sources(boolean usable, List<String> mirrors, List<Minisign.PublicKey> keys, File dir, boolean allowHttp) {
            this.usable = usable;
            this.mirrors = mirrors;
            this.keys = keys;
            this.dir = dir;
            this.allowHttp = allowHttp;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final List<Listener> sListeners = new ArrayList<>();

    private static Sources sSources;
    private static State sState = State.HIDDEN;
    /** The verified channel file (null: none, or not read yet). */
    @Nullable
    private static UpdateManifest sManifest;
    private static boolean sLoaded;
    private static boolean sLoading;
    private static boolean sChecking;
    /** "Güncelle" runs (download, checks, install). */
    private static boolean sWorking;
    @Nullable
    private static UpdateException.Kind sError;
    /** Android's confirmation, waiting for the home screen to be in front. */
    @Nullable
    private static Intent sConfirm;
    @Nullable
    private static WeakReference<Activity> sHome;
    private static volatile boolean sCancel;
    private static long sInstalledCode = -1;

    // ---- sources and storage ----

    static synchronized Sources sources(Context context) {
        if (sSources != null) return sSources;
        Context app = context.getApplicationContext();
        ContentSources content = ProjectsContent.contentSources(app);
        List<String> keyTexts = new ArrayList<>();
        boolean usable = BuildConfig.PROJECTS_UPDATER;
        if (BuildConfig.DEBUG) {
            // A debug build never reads the real channel (its package is another one anyway).
            usable &= content.override && content.updateTestKey != null;
            if (content.updateTestKey != null) keyTexts.add(content.updateTestKey);
        } else {
            keyTexts.addAll(UpdateConfig.TRUSTED_KEYS);
        }
        List<Minisign.PublicKey> keys;
        try {
            keys = Minisign.publicKeys(keyTexts);
        } catch (UpdateException e) {
            Log.w(TAG, "update keys are invalid", e);
            keys = new ArrayList<>();
            usable = false;
        }
        List<String> mirrors = content.updateMirrors.isEmpty() ? new ArrayList<>(ContentConfig.MIRRORS)
                : new ArrayList<>(content.updateMirrors);
        File dir = new File(app.getFilesDir(), content.override ? "projects-updates-debug" : "projects-updates");
        sSources = new Sources(usable, mirrors, keys, dir, BuildConfig.DEBUG && content.override);
        return sSources;
    }

    /** Whether this build has the updater and may use it (the settings rows show only then). */
    public static boolean available(Context context) {
        return sources(context).usable;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean autoCheck() {
        return LauncherPreferences.DEFAULT_PREF == null || LauncherPreferences.DEFAULT_PREF.getBoolean(KEY_AUTO_CHECK, true);
    }

    private static long installedCode(Context context) {
        if (sInstalledCode < 0) sInstalledCode = ApkFacts.installedVersionCode(context);
        return sInstalledCode;
    }

    @Nullable
    private static UpdatePolicy.Snooze snooze(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_SNOOZE_CODE)) return null;
        return new UpdatePolicy.Snooze(p.getInt(KEY_SNOOZE_CODE, 0), p.getLong(KEY_SNOOZE_SET, 0), p.getLong(KEY_SNOOZE_UNTIL, 0));
    }

    private static void setSnooze(Context context, @Nullable UpdatePolicy.Snooze snooze) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (snooze == null) {
            editor.remove(KEY_SNOOZE_CODE).remove(KEY_SNOOZE_SET).remove(KEY_SNOOZE_UNTIL);
        } else {
            editor.putInt(KEY_SNOOZE_CODE, snooze.versionCode).putLong(KEY_SNOOZE_SET, snooze.setAt).putLong(KEY_SNOOZE_UNTIL, snooze.until);
        }
        editor.apply();
    }

    private static File channelFile(Sources s) {
        return new File(s.dir, UpdateConfig.CHANNEL + ".json");
    }

    private static File signatureFile(Sources s) {
        return new File(s.dir, UpdateConfig.CHANNEL + ".json.sig");
    }

    /** The channel file kept on the phone, verified again (background). */
    @Nullable
    private static UpdateManifest readCache(Sources s) {
        File json = channelFile(s);
        File sig = signatureFile(s);
        if (!json.isFile() || !sig.isFile()) return null;
        try {
            return UpdateManifest.read(AtomicFiles.read(json, UpdateManifest.MAX_BYTES), AtomicFiles.read(sig, Minisign.MAX_SIGNATURE_FILE),
                    UpdateConfig.CHANNEL, s.keys, s.allowHttp);
        } catch (IOException | UpdateException e) {
            Log.w(TAG, "dropping the kept channel file", e);
            delete(json);
            delete(sig);
            return null;
        }
    }

    /** Downloads of versions that are not the offered one go (background). */
    private static void cleanUp(Sources s, @Nullable UpdateManifest offered) {
        File[] files = s.dir.listFiles();
        if (files == null) return;
        String keep = offered == null ? null : UpdateConfig.apkName(offered);
        for (File file : files) {
            String name = file.getName();
            boolean apk = name.endsWith(".apk") || name.endsWith(".apk.part");
            if (apk && (keep == null || !(name.equals(keep) || name.equals(keep + ".part")))) delete(file);
        }
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "could not delete " + file);
    }

    // ---- listeners and state ----

    @MainThread
    public static void addListener(Listener listener) {
        sListeners.add(listener);
        listener.onUpdateState(sState);
    }

    @MainThread
    public static void removeListener(Listener listener) {
        sListeners.remove(listener);
    }

    @MainThread
    private static void publish(State state) {
        sState = state;
        for (Listener listener : new ArrayList<>(sListeners)) listener.onUpdateState(state);
    }

    private static boolean offered(Context context, @Nullable UpdateManifest manifest) {
        return UpdatePolicy.offered(manifest, context.getPackageName(), installedCode(context), Build.VERSION.SDK_INT);
    }

    /** The offer (or nothing) from what is known; a running "Güncelle" publishes its own states. */
    @MainThread
    private static void refreshState(Context context) {
        if (sWorking) return;
        UpdateManifest manifest = sManifest;
        boolean offered = offered(context, manifest);
        if (!offered) {
            sError = null;
            publish(State.HIDDEN);
            return;
        }
        boolean shows = UpdatePolicy.cardShows(true, manifest.versionCode, snooze(context), System.currentTimeMillis());
        publish(shows ? new State(Phase.OFFER, manifest, 0, manifest.size, sError) : State.HIDDEN);
    }

    // ---- the home screen ----

    /** The home screen is in front: the card from the kept file, a check when one is due, Android's waiting question. */
    @MainThread
    public static void onHomeResumed(Activity activity) {
        Context app = activity.getApplicationContext();
        if (!sources(app).usable) {
            publish(State.HIDDEN);
            return;
        }
        sHome = new WeakReference<>(activity);
        if (sConfirm != null) {
            Intent confirm = sConfirm;
            sConfirm = null;
            startConfirm(activity, confirm);
        }
        if (!sLoaded) {
            load(app);
            return;
        }
        refreshState(app);
        maybeCheck(app);
    }

    @MainThread
    public static void onHomePaused(Activity activity) {
        if (sHome != null && sHome.get() == activity) sHome = null;
    }

    @MainThread
    private static void load(final Context app) {
        if (sLoading) return;
        sLoading = true;
        final Sources s = sources(app);
        final long installed = installedCode(app);
        EXECUTOR.execute(() -> {
            UpdateManifest cached = readCache(s);
            boolean offer = cached != null && cached.applicationId.equals(app.getPackageName()) && cached.versionCode > installed;
            cleanUp(s, offer ? cached : null);
            MAIN.post(() -> {
                sLoading = false;
                sLoaded = true;
                if (sManifest == null) sManifest = cached;
                refreshState(app);
                maybeCheck(app);
            });
        });
    }

    @MainThread
    private static void maybeCheck(Context app) {
        if (sChecking || sWorking) return;
        SharedPreferences p = prefs(app);
        if (!UpdatePolicy.checkDue(autoCheck(), System.currentTimeMillis(), p.getLong(KEY_LAST_OK, 0), p.getLong(KEY_LAST_TRY, 0))) return;
        check(app, false, null);
    }

    /**
     * Settings' "Güncellemeleri denetle": asks the channel now (whatever the switch and the day say). An update
     * it finds shows on the home screen even if it was set aside before.
     */
    @MainThread
    public static void checkNow(Context context, CheckCallback callback) {
        Context app = context.getApplicationContext();
        if (!sources(app).usable) {
            callback.onChecked(null, null);
            return;
        }
        if (sChecking || sWorking) {
            callback.onChecked(sWorking ? sManifest : null, sWorking ? null : UpdateException.Kind.BUSY);
            return;
        }
        check(app, true, callback);
    }

    @MainThread
    private static void check(final Context app, final boolean manual, @Nullable final CheckCallback callback) {
        sChecking = true;
        final Sources s = sources(app);
        EXECUTOR.execute(() -> {
            UpdateManifest found = null;
            UpdateException.Kind error = null;
            try {
                found = fetchChannel(s);
            } catch (UpdateException e) {
                error = e.kind;
                Log.i(TAG, "update check failed: " + e);
            }
            long now = System.currentTimeMillis();
            SharedPreferences.Editor editor = prefs(app).edit().putLong(KEY_LAST_TRY, now);
            if (error == null) editor.putLong(KEY_LAST_OK, now);
            editor.apply();
            final UpdateManifest result = found;
            final UpdateException.Kind failure = error;
            MAIN.post(() -> {
                sChecking = false;
                if (failure == null) {
                    sManifest = result;
                    if (manual && offered(app, result)) setSnooze(app, null);
                }
                refreshState(app);
                if (callback != null) callback.onChecked(failure == null && offered(app, result) ? result : null, failure);
            });
        });
    }

    /**
     * Reads the channel from the mirrors in order and keeps the verified file (background). Null when every
     * mirror answers that there is no channel file (none published yet, or taken down): the kept file goes too.
     */
    @Nullable
    private static UpdateManifest fetchChannel(Sources s) throws UpdateException {
        HttpFetcher fetcher = new HttpFetcher.UrlConnection(ProjectsContent.userAgent());
        String path = UpdateConfig.channelPath(UpdateConfig.CHANNEL);
        UpdateException last = null;
        int missing = 0;
        for (String mirror : s.mirrors) {
            try {
                byte[][] files = fetchPair(fetcher, mirror + path, "");
                UpdateManifest manifest;
                try {
                    manifest = UpdateManifest.read(files[0], files[1], UpdateConfig.CHANNEL, s.keys, s.allowHttp);
                } catch (UpdateException e) {
                    if (e.kind != UpdateException.Kind.BAD_SIGNATURE) throw e;
                    // An edge cache may hold the file and its signature from two publishes: once more past it.
                    files = fetchPair(fetcher, mirror + path, "?t=" + System.currentTimeMillis());
                    manifest = UpdateManifest.read(files[0], files[1], UpdateConfig.CHANNEL, s.keys, s.allowHttp);
                }
                keep(s, files[0], files[1]);
                return manifest;
            } catch (HttpStatusException e) {
                if (e.status == 404) {
                    missing++;
                    continue;
                }
                last = new UpdateException(UpdateException.Kind.NETWORK, String.valueOf(e), e);
            } catch (IOException e) {
                last = new UpdateException(UpdateException.Kind.NETWORK, String.valueOf(e), e);
            } catch (UpdateException e) {
                last = e;
            }
        }
        if (missing == s.mirrors.size()) {
            delete(channelFile(s));
            delete(signatureFile(s));
            return null;
        }
        throw last != null ? last : new UpdateException(UpdateException.Kind.NETWORK, "no mirror");
    }

    private static byte[][] fetchPair(HttpFetcher fetcher, String url, String query) throws IOException {
        byte[] json = fetcher.get(url + query, UpdateManifest.MAX_BYTES);
        byte[] sig = fetcher.get(url + ".sig" + query, Minisign.MAX_SIGNATURE_FILE);
        return new byte[][]{json, sig};
    }

    private static void keep(Sources s, byte[] json, byte[] sig) {
        try {
            if (!s.dir.isDirectory() && !s.dir.mkdirs()) throw new IOException("cannot create " + s.dir);
            AtomicFiles.write(signatureFile(s), sig);
            AtomicFiles.write(channelFile(s), json);
        } catch (IOException e) {
            Log.w(TAG, "could not keep the channel file", e);
        }
    }

    // ---- the card's buttons ----

    /** "Kapat": the card stays away 2 hours (a newer version comes back at once). */
    @MainThread
    public static void close(Context context) {
        snoozeFor(context, false);
    }

    /** "7 gün sonra hatırlat": 7 days. */
    @MainThread
    public static void remindLater(Context context) {
        snoozeFor(context, true);
    }

    @MainThread
    private static void snoozeFor(Context context, boolean week) {
        UpdateManifest manifest = sManifest;
        if (manifest == null || sWorking) return;
        long now = System.currentTimeMillis();
        setSnooze(context, week ? UpdatePolicy.Snooze.remindLater(manifest.versionCode, now) : UpdatePolicy.Snooze.close(manifest.versionCode, now));
        sError = null;
        refreshState(context);
    }

    /** "Durdur": the download stops; its part stays for the next "Güncelle". */
    @MainThread
    public static void stop() {
        sCancel = true;
    }

    /** "Güncelle". */
    @MainThread
    public static void update(Activity activity) {
        final Context app = activity.getApplicationContext();
        final UpdateManifest known = sManifest;
        if (sWorking || known == null || !sources(app).usable) return;
        if (KeyFixer.gameRunning(app)) {
            sError = UpdateException.Kind.GAME_RUNNING;
            refreshState(app);
            return;
        }
        if (ProgressKeeper.getTaskCount() > 0 || ProjectsJoin.isBusy()) {
            sError = UpdateException.Kind.BUSY;
            refreshState(app);
            return;
        }
        sWorking = true;
        sCancel = false;
        sError = null;
        publish(new State(Phase.DOWNLOADING, known, 0, known.size, null));
        ProgressKeeper.submitProgress(PROGRESS_KEY, 0, R.string.projects_update_downloading);
        final Sources s = sources(app);
        EXECUTOR.execute(() -> work(app, s, known));
    }

    /** "Güncelle" in the background: the channel afresh, the download, the checks, the installer. */
    private static void work(final Context app, Sources s, UpdateManifest known) {
        UpdateManifest manifest = known;
        try {
            try {
                manifest = fetchChannel(s);
            } catch (UpdateException e) {
                // Offline now: the kept file's own addresses are tried (the download says if they fail too).
                if (e.kind != UpdateException.Kind.NETWORK) throw e;
            }
            final UpdateManifest current = manifest;
            if (current == null || !offered(app, current)) {
                MAIN.post(() -> {
                    sManifest = current;
                    finish(app, null);
                });
                return;
            }
            if (current != known) MAIN.post(() -> sManifest = current);
            cleanUp(s, current);
            File apk = ApkDownload.fetch(current.urls, current.size, current.sha256, s.dir, UpdateConfig.apkName(current),
                    ProjectsContent.userAgent(), new ApkDownload.Listener() {
                        @Override
                        public void onProgress(final long done, final long total) {
                            int percent = total <= 0 ? 0 : (int) Math.min(100, done * 100 / total);
                            ProgressKeeper.submitProgress(PROGRESS_KEY, percent, R.string.projects_update_downloading);
                            MAIN.post(() -> {
                                if (sWorking) publish(new State(Phase.DOWNLOADING, current, done, total, null));
                            });
                        }

                        @Override
                        public boolean cancelled() {
                            return sCancel;
                        }
                    });
            MAIN.post(() -> publish(new State(Phase.VERIFYING, current, current.size, current.size, null)));
            verifyApk(app, apk, current);
            if (KeyFixer.gameRunning(app)) throw new UpdateException(UpdateException.Kind.GAME_RUNNING, "the game started");
            ProgressKeeper.submitProgress(PROGRESS_KEY, -1, -1);
            MAIN.post(() -> publish(new State(Phase.INSTALLING, current, current.size, current.size, null)));
            // commit(): the process may end with the install, the version must be on disk before.
            prefs(app).edit().putInt(KEY_INSTALLING, current.versionCode).commit();
            ApkInstaller.install(app, apk);
        } catch (final UpdateException e) {
            Log.i(TAG, "update stopped: " + e);
            final UpdateException.Kind kind = e.kind == UpdateException.Kind.CANCELLED ? null : e.kind;
            MAIN.post(() -> finish(app, kind));
        } catch (RuntimeException e) {
            Log.w(TAG, "update failed", e);
            MAIN.post(() -> finish(app, UpdateException.Kind.INSTALL_FAILED));
        }
    }

    /** The downloaded APK is this app, the announced version, and signed as the installed app and the channel say. */
    private static void verifyApk(Context app, File apk, UpdateManifest manifest) throws UpdateException {
        ApkFacts facts = ApkFacts.archive(app, apk);
        if (facts == null) {
            delete(apk);
            throw new UpdateException(UpdateException.Kind.CORRUPT, "Android cannot read the APK");
        }
        if (!app.getPackageName().equals(facts.packageName) || facts.versionCode != manifest.versionCode) {
            delete(apk);
            throw new UpdateException(UpdateException.Kind.FOREIGN, "the APK is " + facts.packageName + " " + facts.versionCode);
        }
        ApkFacts installed = ApkFacts.installed(app);
        if (!Signers.sameAsInstalled(facts.signers, installed.signers) || !Signers.announced(facts.signers, manifest.signers)) {
            delete(apk);
            throw new UpdateException(UpdateException.Kind.FOREIGN, "the APK's signers are not the app's");
        }
    }

    /** Back to the offer (or nothing), with the sentence for {@code error}. */
    @MainThread
    private static void finish(Context app, @Nullable UpdateException.Kind error) {
        ProgressKeeper.submitProgress(PROGRESS_KEY, -1, -1);
        sWorking = false;
        sError = error;
        refreshState(app);
    }

    // ---- Android's answers (UpdateReceiver) ----

    @MainThread
    static void onInstallStatus(Context context, int status, @Nullable Intent confirm, @Nullable String message) {
        Context app = context.getApplicationContext();
        switch (InstallStatus.outcome(status)) {
            case CONFIRM:
                Activity home = sHome == null ? null : sHome.get();
                if (confirm == null) {
                    endInstall(app, UpdateException.Kind.INSTALL_FAILED);
                } else if (home != null && !home.isFinishing()) {
                    startConfirm(home, confirm);
                } else {
                    // The player left: Android's question opens when the home screen is back.
                    sConfirm = confirm;
                }
                return;
            case DONE:
                endInstall(app, null);
                return;
            case DECLINED:
                endInstall(app, null);
                return;
            default:
                Log.w(TAG, "install failed: " + status + " " + message);
                endInstall(app, InstallStatus.failure(status));
        }
    }

    @MainThread
    private static void endInstall(Context app, @Nullable UpdateException.Kind error) {
        prefs(app).edit().remove(KEY_INSTALLING).apply();
        sConfirm = null;
        finish(app, error);
    }

    @MainThread
    private static void startConfirm(Activity activity, Intent confirm) {
        try {
            activity.startActivity(confirm);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Android's install question did not open", e);
            endInstall(activity.getApplicationContext(), UpdateException.Kind.INSTALL_FAILED);
        }
    }

    /**
     * The app was replaced: when it was this updater's install, a quiet notification says so (the app closed
     * under the player). The downloads go either way.
     */
    static void onPackageReplaced(Context context) {
        final Context app = context.getApplicationContext();
        SharedPreferences p = prefs(app);
        int installing = p.getInt(KEY_INSTALLING, 0);
        p.edit().remove(KEY_INSTALLING).apply();
        if (installing > 0 && ApkFacts.installedVersionCode(app) >= installing) notifyUpdated(app);
        final Sources s = sources(app);
        EXECUTOR.execute(() -> cleanUp(s, null));
    }

    private static void notifyUpdated(Context app) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent open = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (open == null) return;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent tap = PendingIntent.getActivity(app, NOTIFICATION_ID, open, flags);
        Tools.buildNotificationChannel(app);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, app.getString(R.string.notif_channel_id))
                .setSmallIcon(R.drawable.notif_icon)
                .setColor(ContextCompat.getColor(app, R.color.projects_gold_500))
                .setContentTitle(app.getString(R.string.projects_update_done_title, app.getApplicationInfo().loadLabel(app.getPackageManager())))
                .setContentText(app.getString(R.string.projects_update_done_body))
                .setContentIntent(tap)
                .setAutoCancel(true);
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
    }
}
