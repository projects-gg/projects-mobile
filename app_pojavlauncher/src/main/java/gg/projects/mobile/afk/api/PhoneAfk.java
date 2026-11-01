package gg.projects.mobile.afk.api;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.BuildConfig;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.afk.link.AfkClient;
import gg.projects.mobile.afk.link.AfkIpc;
import gg.projects.mobile.afk.service.AfkService;
import gg.projects.mobile.afk.store.AfkDrafts;
import gg.projects.mobile.afk.store.AfkEnds;
import gg.projects.mobile.afk.store.AfkRuntime;
import gg.projects.mobile.afk.store.AfkSealer;
import gg.projects.mobile.afk.store.AfkStore;
import gg.projects.mobile.afk.store.KeystoreSealer;
import gg.projects.mobile.content.LauncherConfig;

/**
 * PHONE-AFK's entry point for the app's screens (docs/tasks/phone-afk.md): the phone's own AFK accounts (offline names
 * only), at most {@link AfkLimits#MAX_RUNNING} at once, kept in game by the {@code :afk} process's foreground service
 * while the phone is on. Behind Studio's {@code features.phoneAfk} (off by default) and not in the Play build.
 *
 * <p>Threads: everything on the main thread unless a method says otherwise; {@link #add}, {@link #update} and
 * {@link #remove} are quick (a small file and the Keystore) and may run on any thread. Watching ({@link #watch}) hears
 * statuses, console lines and alerts on the main thread. Stable: methods may be added.
 */
public final class PhoneAfk {
    private static final String TAG = "ProjectsAfk";
    private static final String PREFS = "projects_afk";
    private static final String PREF_STUDIO_ON = "studio_on";
    private static final String PREF_PAUSED = "paused_for_game";
    /** Debug builds only: this file in the app's external files folder turns the feature on for local tests. */
    private static final String DEBUG_ON_FILE = "projects-afk-debug-on";
    /** Statuses a start promised before the service answered; dropped if it never does. */
    private static final long START_GRACE_MS = 20_000L;

    private static PhoneAfk sInstance;
    private static volatile boolean sStudioOn;
    private static volatile boolean sDebugOn;
    @Nullable
    private static String sOpenRequest;

    private final Context app;
    private final File dir;
    private final AfkStore store;
    private final AfkSealer sealer;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "projects-afk-io");
        t.setDaemon(true);
        return t;
    });
    private final AfkClient client;
    private final List<AfkListener> listeners = new ArrayList<>();
    /** The service's statuses while connected (and a start's promise until it answers). */
    private final Map<String, AfkStatus> live = new LinkedHashMap<>();
    private final Map<String, Long> promised = new HashMap<>();
    private final Map<String, ArrayDeque<AfkLine>> lines = new HashMap<>();
    private final SparseArray<AfkCallback<AfkSendResult>> sends = new SparseArray<>();
    private int nextRequest = 1;
    private boolean connected;

    /** The app's instance (main thread). */
    @MainThread
    public static synchronized PhoneAfk get(Context context) {
        if (sInstance == null) sInstance = new PhoneAfk(context.getApplicationContext());
        return sInstance;
    }

    private PhoneAfk(Context app) {
        this.app = app;
        this.dir = afkDir(app);
        this.store = new AfkStore(dir);
        this.sealer = new KeystoreSealer();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sStudioOn = prefs.getBoolean(PREF_STUDIO_ON, false);
        if (BuildConfig.DEBUG) {
            File ext = app.getExternalFilesDir(null);
            sDebugOn = ext != null && new File(ext, DEBUG_ON_FILE).isFile();
        }
        this.client = new AfkClient(app, new Sink());
        if (phoneSupported()) client.open();
        ProjectsContent.addListener((snapshot, refreshing) -> {
            if (snapshot != null) onConfig(snapshot.config);
        });
    }

    /** {@code no_backup/afk}: the accounts, the runtime and the last end (also the {@code :afk} process's). */
    public static File afkDir(Context context) {
        return new File(context.getNoBackupFilesDir(), "afk");
    }

    // ---- availability ----

    /** This build has phone AFK (not the Play build) and the phone is Android 8 or newer. */
    public static boolean phoneSupported() {
        return BuildConfig.PROJECTS_PHONE_AFK && Build.VERSION.SDK_INT >= AfkLimits.MIN_SDK;
    }

    /** The AFK row shows: {@link #phoneSupported()} and Studio's {@code features.phoneAfk} is on in {@code config}. */
    public static boolean available(@Nullable LauncherConfig config) {
        return phoneSupported() && ((config != null && config.phoneAfkFeature) || sDebugOn);
    }

    /** Follows Studio's switch: turned off, the running accounts stop (it is the feature's off switch). */
    @MainThread
    void onConfig(@Nullable LauncherConfig config) {
        if (config == null) return;
        boolean on = config.phoneAfkFeature;
        if (on == sStudioOn) return;
        sStudioOn = on;
        prefs.edit().putBoolean(PREF_STUDIO_ON, on).apply();
        if (!on && !sDebugOn && runningCount() > 0) stopAll();
    }

    private static boolean switchOn() {
        return phoneSupported() && (sStudioOn || sDebugOn);
    }

    // ---- accounts ----

    /** The AFK accounts in the order they were added. */
    public List<AfkAccount> accounts() {
        List<AfkAccount> out = new ArrayList<>();
        for (AfkStore.Entry e : store.entries()) out.add(e.account);
        return Collections.unmodifiableList(out);
    }

    @Nullable
    public AfkAccount account(String id) {
        AfkStore.Entry e = store.find(id);
        return e == null ? null : e.account;
    }

    /** The draft's problems with the rules {@link #add} and {@link #update} apply ({@code accountId}: the edited one). */
    public List<AfkProblem> check(AfkDraft draft, @Nullable String accountId) {
        List<String> others = new ArrayList<>();
        int count = 0;
        for (AfkStore.Entry e : store.entries()) {
            count++;
            if (!e.account.id.equals(accountId)) others.add(e.account.name);
        }
        return AfkDrafts.check(draft, others, accountId == null, count);
    }

    /** Adds an account (its login commands sealed). Any thread. */
    public AfkAccount add(AfkDraft draft) throws AfkDraftException {
        synchronized (store) {
            List<AfkProblem> problems = check(draft, null);
            if (!problems.isEmpty()) throw new AfkDraftException(problems);
            AfkDrafts.Clean clean = AfkDrafts.normalize(draft);
            AfkAccount account = new AfkAccount(AfkStore.newId(), clean.name, clean.host, clean.port,
                    clean.loginCommands.size(), draft.loginMode, draft.sneak, draft.mobileData, System.currentTimeMillis());
            List<AfkStore.Entry> entries = new ArrayList<>(store.entries());
            entries.add(new AfkStore.Entry(account, seal(clean.loginCommands)));
            save(entries);
            changedAccounts();
            return account;
        }
    }

    /**
     * Saves the edit screen's draft over account {@code id}; null when the account no longer exists. A running
     * account takes the new settings when it reconnects ("Yeni ayarlar yeniden bağlanınca geçerli olur."). Any thread.
     */
    @Nullable
    public AfkAccount update(String id, AfkDraft draft) throws AfkDraftException {
        synchronized (store) {
            AfkStore.Entry old = store.find(id);
            if (old == null) return null;
            List<AfkProblem> problems = check(draft, id);
            if (!problems.isEmpty()) throw new AfkDraftException(problems);
            AfkDrafts.Clean clean = AfkDrafts.normalize(draft);
            AfkAccount account = new AfkAccount(id, clean.name, clean.host, clean.port, clean.loginCommands.size(),
                    draft.loginMode, draft.sneak, draft.mobileData, old.account.createdAt);
            List<AfkStore.Entry> entries = new ArrayList<>();
            for (AfkStore.Entry e : store.entries()) {
                entries.add(e.account.id.equals(id) ? new AfkStore.Entry(account, seal(clean.loginCommands)) : e);
            }
            save(entries);
            changedAccounts();
            return account;
        }
    }

    /**
     * The edit screen's draft of {@code id} with its login commands unsealed (off the main thread); null when the
     * account is gone. If the commands cannot be read (the phone's key was lost), the draft has none and
     * {@link AfkDraft#loginCommandsUnreadable} is set.
     */
    @MainThread
    public void edit(String id, AfkCallback<AfkDraft> done) {
        io.execute(() -> {
            AfkStore.Entry e = store.find(id);
            AfkDraft draft = null;
            if (e != null) {
                List<String> commands;
                boolean unreadable = false;
                try {
                    commands = sealer.unseal(e.sealed);
                } catch (Exception ex) {
                    Log.w(TAG, "login commands unreadable for an AFK account");
                    commands = new ArrayList<>();
                    unreadable = true;
                }
                draft = AfkDraft.of(e.account, commands);
                draft.loginCommandsUnreadable = unreadable;
            }
            final AfkDraft result = draft;
            main.post(() -> done.done(result));
        });
    }

    /** Removes an account (stopping it first). Any thread. */
    public void remove(String id) {
        synchronized (store) {
            if (store.find(id) == null) return;
            main.post(() -> {
                if (status(id).state.running()) stop(id);
            });
            List<AfkStore.Entry> entries = new ArrayList<>();
            for (AfkStore.Entry e : store.entries()) {
                if (!e.account.id.equals(id)) entries.add(e);
            }
            try {
                save(entries);
            } catch (AfkDraftException ignored) {
                // The file could not be written: the account stays (logged in save()).
            }
            changedAccounts();
        }
    }

    private String seal(List<String> commands) throws AfkDraftException {
        try {
            return sealer.seal(commands);
        } catch (Exception e) {
            Log.w(TAG, "login commands not sealed", e);
            // The draft is fine; the phone could not lock the commands, so nothing is saved.
            throw new AfkDraftException(Collections.singletonList(AfkProblem.NOT_SAVED));
        }
    }

    private void save(List<AfkStore.Entry> entries) throws AfkDraftException {
        try {
            store.write(entries);
        } catch (java.io.IOException e) {
            Log.w(TAG, "AFK accounts not saved", e);
            throw new AfkDraftException(Collections.singletonList(AfkProblem.NOT_SAVED));
        }
    }

    private void changedAccounts() {
        main.post(this::dispatchStatuses);
    }

    // ---- statuses ----

    /** Every account's status now (stopped ones with the reason they stopped for, if any). */
    @MainThread
    public List<AfkStatus> statuses() {
        dropStalePromises();
        AfkRuntime runtime = connected ? null : AfkRuntime.read(dir);
        boolean ended = runtime != null && !runtime.running.isEmpty() && !afkAlive();
        AfkEnd end = ended ? lastEnd() : null;
        List<AfkStatus> out = new ArrayList<>();
        for (AfkStore.Entry e : store.entries()) {
            AfkAccount a = e.account;
            AfkStatus s = live.get(a.id);
            if (s == null && runtime != null) {
                AfkRuntime.Stop stop = runtime.stops.get(a.id);
                if (ended && runtime.running.contains(a.id)) {
                    AfkReason reason = end != null && end.kind == AfkEnd.Kind.APP_UPDATED ? AfkReason.APP_UPDATED
                            : AfkReason.PROCESS_ENDED;
                    s = new AfkStatus(a.id, a.name, AfkState.STOPPED, runtime.updatedAt, 0, 0, 0, reason, null);
                } else if (stop != null) {
                    s = new AfkStatus(a.id, a.name, AfkState.STOPPED, stop.at, 0, 0, 0, stop.reason, stop.message);
                }
            }
            if (s == null) s = AfkStatus.stopped(a.id, a.name);
            out.add(s);
        }
        return Collections.unmodifiableList(out);
    }

    @MainThread
    public AfkStatus status(String id) {
        for (AfkStatus s : statuses()) {
            if (s.accountId.equals(id)) return s;
        }
        return AfkStatus.stopped(id, "");
    }

    /** The running AFK account with this name (ignoring case), or null: OYNA asks first, the list says "AFK'da". */
    @MainThread
    @Nullable
    public AfkStatus afkByName(String name) {
        if (name == null) return null;
        for (AfkStatus s : statuses()) {
            if (s.state.running() && s.name.equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    @MainThread
    public int runningCount() {
        int n = 0;
        for (AfkStatus s : statuses()) {
            if (s.state.running()) n++;
        }
        return n;
    }

    /**
     * Starts watching (main thread): {@link AfkListener#onStatuses} at once, then everything the service says.
     * Close it in {@code onStop}/{@code onDestroyView}.
     */
    @MainThread
    public AfkWatch watch(AfkListener listener) {
        listeners.add(listener);
        client.open();
        client.setFull(true);
        listener.onStatuses(statuses());
        for (Map.Entry<String, ArrayDeque<AfkLine>> e : lines.entrySet()) {
            if (!e.getValue().isEmpty()) listener.onBacklog(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return () -> main.post(() -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) client.setFull(false);
        });
    }

    // ---- start, stop, send ----

    /** Starts one account (the player's tap: Android lets a foreground service start only while the app is on screen). */
    @MainThread
    public AfkStartResult start(String id) {
        if (!switchOn()) return AfkStartResult.NOT_AVAILABLE;
        AfkAccount account = account(id);
        if (account == null) return AfkStartResult.NO_ACCOUNT;
        if (status(id).state.running()) return AfkStartResult.ALREADY_RUNNING;
        if (runningCount() >= AfkLimits.MAX_RUNNING) return AfkStartResult.LIMIT_REACHED;
        try {
            ContextCompat.startForegroundService(app, AfkService.startIntent(app, id));
        } catch (IllegalStateException | SecurityException e) {
            // Android 12+: ForegroundServiceStartNotAllowedException (an IllegalStateException) off screen.
            Log.w(TAG, "AFK service not started", e);
            return AfkStartResult.NOT_ALLOWED;
        }
        long now = System.currentTimeMillis();
        AfkStatus starting = new AfkStatus(id, account.name, AfkState.CONNECTING, now, 0, 0, 0, null, null);
        live.put(id, starting);
        promised.put(id, now);
        client.open();
        dispatchStatus(starting);
        return AfkStartResult.STARTED;
    }

    /** "Hepsini başlat": the stopped accounts in list order while places are free. The first problem, else STARTED. */
    @MainThread
    public AfkStartResult startAll() {
        if (!switchOn()) return AfkStartResult.NOT_AVAILABLE;
        AfkStartResult result = AfkStartResult.ALREADY_RUNNING;
        for (AfkStatus s : statuses()) {
            if (s.state.running()) continue;
            AfkStartResult r = start(s.accountId);
            if (r == AfkStartResult.STARTED) {
                if (result == AfkStartResult.ALREADY_RUNNING) result = AfkStartResult.STARTED;
            } else if (r != AfkStartResult.ALREADY_RUNNING) {
                return r;
            }
        }
        return result;
    }

    @MainThread
    public void stop(String id) {
        promised.remove(id);
        Bundle data = new Bundle();
        data.putString(AfkIpc.KEY_ACCOUNT, id);
        if (!client.send(AfkIpc.STOP, 0, data)) startServiceQuietly(AfkService.stopIntent(app, id));
        AfkStatus old = live.remove(id);
        if (old != null) dispatchStatus(AfkStatus.stopped(id, old.name));
    }

    @MainThread
    public void stopAll() {
        promised.clear();
        if (!client.send(AfkIpc.STOP_ALL, 0, null)) startServiceQuietly(AfkService.stopAllIntent(app, false));
        if (!live.isEmpty()) {
            live.clear();
            dispatchStatuses();
        }
    }

    /**
     * OYNA with a name that is AFK, "AFK'yı durdur ve oyna": stops it now and starts it again after the game
     * ({@link #afterGame}).
     */
    @MainThread
    public void stopForGame(String id) {
        Set<String> paused = new HashSet<>(prefs.getStringSet(PREF_PAUSED, Collections.<String>emptySet()));
        paused.add(id);
        prefs.edit().putStringSet(PREF_PAUSED, paused).apply();
        stop(id);
    }

    /** The launcher is on screen again after a game: the accounts {@link #stopForGame} stopped start again. */
    @MainThread
    public void afterGame() {
        Set<String> paused = prefs.getStringSet(PREF_PAUSED, Collections.<String>emptySet());
        if (paused.isEmpty()) return;
        prefs.edit().remove(PREF_PAUSED).apply();
        for (String id : paused) {
            if (account(id) != null) start(id);
        }
    }

    /** One line the account says or a command ({@code /...}); the answer comes on the main thread. */
    @MainThread
    public void send(String id, String text, AfkCallback<AfkSendResult> done) {
        String line = text == null ? "" : text.trim();
        if (line.isEmpty()) {
            main.post(() -> done.done(AfkSendResult.EMPTY));
            return;
        }
        if (line.length() > AfkLimits.MAX_LINE_CHARS) {
            main.post(() -> done.done(AfkSendResult.TOO_LONG));
            return;
        }
        int request = nextRequest++;
        Bundle data = new Bundle();
        data.putString(AfkIpc.KEY_ACCOUNT, id);
        data.putString(AfkIpc.KEY_TEXT, line);
        sends.put(request, done);
        if (!client.send(AfkIpc.SEND, request, data)) {
            sends.remove(request);
            main.post(() -> done.done(AfkSendResult.NOT_RUNNING));
        }
    }

    private void startServiceQuietly(Intent intent) {
        if (!afkAlive()) return;
        try {
            app.startService(intent);
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "AFK service not reached", e);
        }
    }

    /** The {@code :afk} process is there. */
    boolean afkAlive() {
        if (connected) return true;
        ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes == null) return false;
        String name = app.getPackageName() + ":afk";
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (name.equals(p.processName)) return true;
        }
        return false;
    }

    // ---- battery ----

    /** Android lets AFK run without battery limits (the player allowed it). */
    public boolean batteryUnrestricted() {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(app.getPackageName());
    }

    /** Android's own question "let the app run in the background without limits?" (APK only), or null. */
    @Nullable
    public Intent batteryIntent() {
        if (!BuildConfig.PROJECTS_PHONE_AFK || batteryUnrestricted()) return null;
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + app.getPackageName()));
    }

    /**
     * The phone's maker when it is known to close apps in the background ({@code "xiaomi"}, {@code "samsung"},
     * {@code "huawei"}, {@code "honor"}, {@code "oppo"}, {@code "realme"}, {@code "oneplus"}, {@code "vivo"}), else "".
     */
    public static String batteryMaker() {
        String m = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER).toLowerCase(Locale.ROOT).trim();
        String b = (Build.BRAND == null ? "" : Build.BRAND).toLowerCase(Locale.ROOT).trim();
        if (m.contains("xiaomi") || b.contains("redmi") || b.contains("poco") || b.contains("xiaomi")) return "xiaomi";
        if (m.contains("samsung")) return "samsung";
        if (m.contains("honor") || b.contains("honor")) return "honor";
        if (m.contains("huawei")) return "huawei";
        if (m.contains("oneplus") || b.contains("oneplus")) return "oneplus";
        if (m.contains("realme") || b.contains("realme")) return "realme";
        if (m.contains("oppo")) return "oppo";
        if (m.contains("vivo") || b.contains("iqoo")) return "vivo";
        return "";
    }

    /**
     * The maker's own setting for it where one is known (Xiaomi: "Otomatik başlatma"; Samsung: battery settings with
     * "Uyumayan uygulamalar"), or null. Opening it may fail on some versions: catch the exception and offer
     * {@link #batteryMakerPage()}.
     */
    @Nullable
    public static Intent batteryMakerIntent() {
        switch (batteryMaker()) {
            case "xiaomi":
                return new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            case "samsung":
                return new Intent().setComponent(new ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            default:
                return null;
        }
    }

    /** dontkillmyapp.com's page for the maker ({@code https://}), or null. */
    @Nullable
    public static String batteryMakerPage() {
        String maker = batteryMaker();
        return maker.isEmpty() ? null : "https://dontkillmyapp.com/" + maker;
    }

    // ---- the end of a night ----

    /** Why AFK ended last time while accounts ran and nobody stopped it, until dismissed; null otherwise. */
    @MainThread
    @Nullable
    public AfkEnd lastEnd() {
        if (!connected) AfkEnds.detect(app, dir, AfkRuntime.read(dir), afkAlive());
        return AfkEnds.read(dir);
    }

    @MainThread
    public void dismissLastEnd() {
        AfkEnds.dismiss(dir);
    }

    // ---- "Verilerimi sil" ----

    /** Stops AFK and deletes every AFK account, its sealed login commands and their key from the phone. */
    @MainThread
    public void eraseAll() {
        promised.clear();
        live.clear();
        lines.clear();
        if (!client.send(AfkIpc.STOP_ALL, 1, null)) startServiceQuietly(AfkService.stopAllIntent(app, true));
        synchronized (store) {
            store.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, AfkEnds.FILE).delete();
            sealer.forget();
        }
        prefs.edit().remove(PREF_PAUSED).apply();
        dispatchStatuses();
    }

    // ---- notification taps ----

    /** Set by a tap on an AFK notification: the account to open ("" = the list); see {@link #takeOpenRequest()}. */
    public static void requestOpen(@Nullable String accountId) {
        sOpenRequest = accountId == null ? "" : accountId;
    }

    /**
     * The AFK screen a notification asked to open, once: an account id, "" for the list, or null for nothing. The
     * launcher checks it when it comes on screen.
     */
    @MainThread
    @Nullable
    public static String takeOpenRequest() {
        String r = sOpenRequest;
        sOpenRequest = null;
        return r;
    }

    // ---- dispatch ----

    private void dropStalePromises() {
        if (promised.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Long> e : promised.entrySet()) {
            if (now - e.getValue() > START_GRACE_MS) stale.add(e.getKey());
        }
        for (String id : stale) {
            promised.remove(id);
            live.remove(id);
        }
    }

    private void dispatchStatuses() {
        if (listeners.isEmpty()) return;
        List<AfkStatus> all = statuses();
        for (AfkListener l : new ArrayList<>(listeners)) l.onStatuses(all);
    }

    private void dispatchStatus(AfkStatus s) {
        for (AfkListener l : new ArrayList<>(listeners)) l.onStatus(s);
    }

    private final class Sink implements AfkClient.Sink {
        @Override
        public void connected(boolean now) {
            connected = now;
            if (!now) {
                live.clear();
                promised.clear();
                for (int i = 0; i < sends.size(); i++) {
                    AfkCallback<AfkSendResult> cb = sends.valueAt(i);
                    cb.done(AfkSendResult.NOT_RUNNING);
                }
                sends.clear();
                dispatchStatuses();
            }
        }

        @Override
        public void statuses(List<AfkStatus> all) {
            // A start the service has not reached yet (its first answer comes before the start) stays promised.
            Map<String, AfkStatus> kept = new LinkedHashMap<>();
            for (String id : promised.keySet()) {
                AfkStatus s = live.get(id);
                if (s != null) kept.put(id, s);
            }
            live.clear();
            live.putAll(kept);
            for (AfkStatus s : all) {
                promised.remove(s.accountId);
                if (s.state.running() || s.reason != null) live.put(s.accountId, s);
                else live.remove(s.accountId);
            }
            dispatchStatuses();
        }

        @Override
        public void status(AfkStatus s) {
            promised.remove(s.accountId);
            if (s.state.running() || s.reason != null) live.put(s.accountId, s);
            else live.remove(s.accountId);
            AfkAccount account = account(s.accountId);
            if (account != null) dispatchStatus(s);
        }

        @Override
        public void backlog(String accountId, List<AfkLine> backlog) {
            ArrayDeque<AfkLine> kept = new ArrayDeque<>(backlog);
            while (kept.size() > AfkLimits.CONSOLE_LINES) kept.pollFirst();
            lines.put(accountId, kept);
            for (AfkListener l : new ArrayList<>(listeners)) l.onBacklog(accountId, new ArrayList<>(kept));
        }

        @Override
        public void line(AfkLine line) {
            ArrayDeque<AfkLine> kept = lines.get(line.accountId);
            if (kept == null) {
                kept = new ArrayDeque<>();
                lines.put(line.accountId, kept);
            }
            kept.addLast(line);
            while (kept.size() > AfkLimits.CONSOLE_LINES) kept.pollFirst();
            for (AfkListener l : new ArrayList<>(listeners)) l.onLine(line);
        }

        @Override
        public void alert(AfkAlert alert) {
            for (AfkListener l : new ArrayList<>(listeners)) l.onAlert(alert);
        }

        @Override
        public void sendResult(int request, AfkSendResult result) {
            AfkCallback<AfkSendResult> cb = sends.get(request);
            if (cb == null) return;
            sends.remove(request);
            cb.done(result);
        }
    }
}
