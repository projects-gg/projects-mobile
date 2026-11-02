package gg.projects.mobile.afk.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkSendResult;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.afk.core.AfkEngine;
import gg.projects.mobile.afk.core.AfkHost;
import gg.projects.mobile.afk.core.AfkLang;
import gg.projects.mobile.afk.net.AfkMcWire;
import gg.projects.mobile.afk.store.AfkEnds;
import gg.projects.mobile.afk.store.AfkGameLang;
import gg.projects.mobile.afk.store.AfkRuntime;
import gg.projects.mobile.afk.store.AfkStore;
import gg.projects.mobile.afk.store.KeystoreSealer;

/**
 * PHONE-AFK: the {@code :afk} process's {@code specialUse} foreground service (phone-afk.md §3). Started only by the
 * player's tap (or, when the player lifted the battery limit, by its own watchdog or Android's sticky restart after
 * the process was closed); it runs the engine on its main thread (the process has no screens), the client's network
 * on one event loop and the address lookups on one thread. It keeps the CPU awake while accounts run, stops them at
 * 15 % battery off the charger or when the phone gets too hot, writes {@code runtime.json} and goes away when none
 * runs. The screens talk to it through {@link AfkHub}.
 */
public final class AfkService extends Service {
    private static final String TAG = "ProjectsAfk";
    public static final String ACTION_START = "gg.projects.mobile.afk.START";
    public static final String ACTION_STOP = "gg.projects.mobile.afk.STOP";
    public static final String ACTION_STOP_ALL = "gg.projects.mobile.afk.STOP_ALL";
    public static final String ACTION_RESUME = "gg.projects.mobile.afk.RESUME";
    public static final String ACTION_RECONNECT = "gg.projects.mobile.afk.RECONNECT";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_ERASE = "erase";
    private static final long TICK_MS = 5 * 60 * 1000L;

    private static volatile boolean sRunning;

    public static Intent startIntent(Context context, String accountId) {
        return new Intent(context, AfkService.class).setAction(ACTION_START).putExtra(EXTRA_ACCOUNT, accountId);
    }

    public static Intent stopIntent(Context context, String accountId) {
        return new Intent(context, AfkService.class).setAction(ACTION_STOP).putExtra(EXTRA_ACCOUNT, accountId);
    }

    public static Intent stopAllIntent(Context context, boolean erase) {
        return new Intent(context, AfkService.class).setAction(ACTION_STOP_ALL).putExtra(EXTRA_ERASE, erase);
    }

    static Intent resumeIntent(Context context) {
        return new Intent(context, AfkService.class).setAction(ACTION_RESUME);
    }

    /** A running account connects again with its saved settings (started through startForegroundService). */
    public static Intent reconnectIntent(Context context, String accountId) {
        return new Intent(context, AfkService.class).setAction(ACTION_RECONNECT).putExtra(EXTRA_ACCOUNT, accountId);
    }

    /** The service is alive in this process. */
    static boolean running() {
        return sRunning;
    }

    /**
     * MOB-NEXT: the game's {@code .minecraft} folder, as the launcher's start-up places it (Tools.initStorageConstants,
     * which the {@code :afk} process skips): the app's external files on Android 10+, else the shared games folder;
     * null while that storage is not there.
     */
    @Nullable
    static File gameDir(Context context) {
        File root = Build.VERSION.SDK_INT >= 29 ? context.getExternalFilesDir(null)
                : new File(android.os.Environment.getExternalStorageDirectory(), "games/ProjectsLauncher");
        return root == null ? null : new File(root, ".minecraft");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private File dir;
    private AfkStore store;
    private KeystoreSealer sealer;
    private AndroidWords words;
    private AfkMcWire wire;
    private AfkEngine engine;
    private AfkHub hub;
    private AfkPhone phone;
    private long startedAt;
    private boolean foreground;
    private List<String> resumable = Collections.emptyList();
    /** MOB-NEXT: the closed {@code :afk} process whose accounts {@link #resumable} brings back (0: none). */
    private int closedPid;
    private long closedStartedAt;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (engine.runningCount() > 0) {
                phone.holdAwake(true);
                AfkWatchdog.arm(AfkService.this);
                writeRuntime();
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        // The foreground first (Android allows ~10 s from the start; a slow phone's first set-up below must not count):
        // a stop that started the service from the background may not, which is fine.
        try {
            AfkNotifications.ensureChannels(this);
            AfkNotifications.startForeground(this, AfkNotifications.running(this, Collections.<AfkStatus>emptyList()));
            foreground = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "AFK service not in the foreground yet", e);
        }
        dir = PhoneAfk.afkDir(this);
        store = new AfkStore(dir);
        sealer = new KeystoreSealer();
        words = new AndroidWords(this);
        startedAt = System.currentTimeMillis();
        wire = new AfkMcWire(main::post, new AndroidResolver(this), words.turkish(), new AfkMcWire.Log() {
            @Override
            public void info(String message) {
                Log.i(TAG, message);
            }

            @Override
            public void warn(String message, Throwable error) {
                Log.w(TAG, message, error);
            }
        });
        engine = new AfkEngine(wire, new Host(), new Random());
        hub = new AfkHub(new HubEngine());
        // The stops a previous process recorded, so the screens still say why; and what it left running.
        AfkRuntime previous = AfkRuntime.read(dir);
        for (Map.Entry<String, AfkRuntime.Stop> e : previous.stops.entrySet()) {
            AfkStore.Entry stored = store.find(e.getKey());
            if (stored == null) continue;
            AfkRuntime.Stop s = e.getValue();
            engine.restoreStopped(new AfkStatus(e.getKey(), stored.account.name, AfkState.STOPPED, s.at, 0, 0, 0, s.reason, s.message));
        }
        if (!previous.running.isEmpty() && previous.pid != Process.myPid()) {
            AfkEnds.detect(this, dir, previous, false);
            resumable = new ArrayList<>(previous.running);
            closedPid = previous.pid;
            closedStartedAt = previous.startedAt;
        }
        // MOB-NEXT: the console's vanilla keys the built-in table lacks, from the game files on this phone.
        File game = gameDir(this);
        AfkLang.setSource(game == null ? null : new AfkGameLang(game));
        phone = new AfkPhone(this, new PhoneListener());
        phone.start();
        main.postDelayed(tick, TICK_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        String id = intent == null ? null : intent.getStringExtra(EXTRA_ACCOUNT);
        // Starts come through startForegroundService (the foreground at once); a stop may find no service running.
        boolean starts = intent == null || ACTION_START.equals(action) || ACTION_RESUME.equals(action)
                || ACTION_RECONNECT.equals(action);
        if (starts && !ensureForeground()) return START_NOT_STICKY;
        if (ACTION_RECONNECT.equals(action) && id != null && !engine.reconnect(id)) {
            // Not running in this service (it was just started for this): a start, as the screens asked.
            action = ACTION_START;
        }
        if (ACTION_START.equals(action) && id != null) {
            if (!engine.start(id).ok()) {
                // Refused (three already run, or the account is gone): the screens drop their "starting".
                AfkStore.Entry stored = store.find(id);
                hub.status(AfkStatus.stopped(id, stored == null ? "" : stored.account.name));
            }
            phoneCheck();
        } else if (ACTION_STOP.equals(action) && id != null) {
            engine.stop(id);
        } else if (ACTION_STOP_ALL.equals(action)) {
            stopAll(intent.getBooleanExtra(EXTRA_ERASE, false));
        } else if (intent == null || ACTION_RESUME.equals(action)) {
            // Android's sticky restart or the watchdog: what the player had running comes back.
            int back = 0;
            for (String r : resumable) {
                if (engine.start(r).ok()) back++;
            }
            resumable = Collections.emptyList();
            // MOB-NEXT: back by itself, so the closed process's end needs no morning notice.
            if (back > 0) AfkEnds.cameBack(dir, closedPid, closedStartedAt);
            phoneCheck();
        }
        if (engine.runningCount() == 0) main.post(this::finishIfIdle);
        else refreshNotification();
        return START_STICKY;
    }

    private void phoneCheck() {
        if (engine.runningCount() == 0) return;
        if (phone.batteryLowNow()) phoneStop(AfkReason.BATTERY_LOW);
        else if (phone.tooHotNow()) phoneStop(AfkReason.TOO_HOT);
    }

    private boolean ensureForeground() {
        if (foreground) return true;
        try {
            AfkNotifications.ensureChannels(this);
            AfkNotifications.startForeground(this, notification());
            foreground = true;
            return true;
        } catch (RuntimeException e) {
            // Android 12+ refused a start from the background (ForegroundServiceStartNotAllowedException).
            Log.w(TAG, "AFK service could not come to the foreground", e);
            stopSelf();
            return false;
        }
    }

    private Notification notification() {
        List<AfkStatus> running = new ArrayList<>();
        for (AfkStatus s : engine.statuses()) {
            if (s.state.running()) running.add(s);
        }
        return AfkNotifications.running(this, running);
    }

    private void refreshNotification() {
        if (foreground) AfkNotifications.updateRunning(this, notification());
    }

    private void stopAll(boolean erase) {
        engine.stopAll(null);
        if (erase) {
            for (AfkStatus s : new ArrayList<>(engine.stoppedStatuses().values())) engine.stop(s.accountId);
            AfkNotifications.cancelAlerts(this);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, AfkRuntime.FILE).delete();
        }
    }

    private void phoneStop(AfkReason reason) {
        if (engine.runningCount() == 0) return;
        engine.stopAll(reason);
        AfkNotifications.phoneStop(this, reason);
    }

    private void finishIfIdle() {
        if (engine.runningCount() > 0) return;
        writeRuntime();
        AfkWatchdog.disarm(this);
        phone.holdAwake(false);
        if (foreground) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            foreground = false;
        }
        stopSelf();
    }

    private void writeRuntime() {
        AfkRuntime rt = new AfkRuntime();
        rt.pid = Process.myPid();
        rt.startedAt = startedAt;
        rt.updatedAt = System.currentTimeMillis();
        rt.running.addAll(engine.runningIds());
        rt.runningNames.addAll(engine.runningNames());
        for (AfkStatus s : engine.stoppedStatuses().values()) {
            if (s.reason != null) rt.stops.put(s.accountId, new AfkRuntime.Stop(s.reason, s.serverMessage, s.since));
        }
        try {
            rt.write(dir);
        } catch (IOException e) {
            Log.w(TAG, "AFK runtime not written", e);
        }
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        main.removeCallbacks(tick);
        // Android ending the service while accounts run: the runtime keeps them (a sticky restart or the watchdog
        // brings them back); their connections go with the event loop.
        if (engine.runningCount() > 0) writeRuntime();
        main.removeCallbacksAndMessages(null);
        phone.stop();
        wire.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return hub.messenger.getBinder();
    }

    /** The engine's surroundings: this main thread, the store, the app's words, the link and the notifications. */
    private final class Host implements AfkHost {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public Cancel schedule(long delayMs, Runnable r) {
            final Runnable safe = () -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    Log.w(TAG, "AFK engine step failed", e);
                }
            };
            main.postDelayed(safe, Math.max(0, delayMs));
            return () -> main.removeCallbacks(safe);
        }

        @Override
        public Loaded load(String accountId) {
            AfkStore.Entry e = store.find(accountId);
            if (e == null) return null;
            List<String> commands;
            try {
                commands = sealer.unseal(e.sealed);
            } catch (Exception ex) {
                Log.w(TAG, "login commands unreadable; connecting without them");
                commands = Collections.emptyList();
            }
            return new Loaded(e.account, commands);
        }

        @Override
        public Words words() {
            return words;
        }

        @Override
        public void status(AfkStatus status) {
            hub.status(status);
            refreshNotification();
        }

        @Override
        public void line(AfkLine line) {
            hub.line(line);
        }

        @Override
        public void alert(AfkAlert alert) {
            hub.alert(alert);
            AfkNotifications.alert(AfkService.this, alert);
        }

        @Override
        public void alertCleared(String accountId, AfkAlert.Kind kind) {
            AfkNotifications.clearAlert(AfkService.this, accountId, kind);
        }

        @Override
        public void changed() {
            writeRuntime();
            boolean any = engine.runningCount() > 0;
            phone.holdAwake(any);
            if (any) AfkWatchdog.arm(AfkService.this);
            refreshNotification();
            hub.statuses(engine.statuses());
            if (!any) main.post(AfkService.this::finishIfIdle);
        }
    }

    private final class HubEngine implements AfkHub.Engine {
        @Override
        public List<AfkStatus> statuses() {
            return engine.statuses();
        }

        @Override
        public List<AfkLine> backlog(String accountId) {
            return engine.backlog(accountId);
        }

        @Override
        public List<String> runningIds() {
            List<String> ids = new ArrayList<>(engine.runningIds());
            ids.addAll(engine.stoppedStatuses().keySet());
            return ids;
        }

        @Override
        public void send(String accountId, String text, AfkHub.Done<AfkSendResult> done) {
            done.done(engine.send(accountId, text));
        }

        @Override
        public void stop(String accountId) {
            engine.stop(accountId);
        }

        @Override
        public void stopAll(boolean erase) {
            AfkService.this.stopAll(erase);
        }

        @Override
        public void reconnect(String accountId) {
            engine.reconnect(accountId);
        }
    }

    private final class PhoneListener implements AfkPhone.Listener {
        @Override
        public void network(AfkEngine.Net net) {
            engine.network(net);
        }

        @Override
        public void batteryLow() {
            phoneStop(AfkReason.BATTERY_LOW);
        }

        @Override
        public void tooHot() {
            phoneStop(AfkReason.TOO_HOT);
        }
    }
}
