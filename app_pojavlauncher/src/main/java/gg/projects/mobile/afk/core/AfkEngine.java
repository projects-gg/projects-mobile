package gg.projects.mobile.afk.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import gg.projects.mobile.afk.api.AfkAccount;
import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLimits;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkLoginMode;
import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkSendResult;
import gg.projects.mobile.afk.api.AfkStartResult;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

/**
 * PHONE-AFK's engine (pure Java, JVM tested): the running accounts' state machines over an {@link AfkWire}. Each
 * account connects in its turn per server ({@link AfkPacer}), logs in, sends its login commands
 * ({@link AfkLimits#LOGIN_COMMAND_GAP_SECONDS} apart: every join, or once per connection), sneaks now and then
 * against AFK checks, and after a drop waits ({@link AfkRestart}) or stops for a refusal. No usable network: it waits
 * for one ("Bağlantı yok", "Wi-Fi bekleniyor") and goes on at once when it comes back. Every line is masked before it
 * is kept. Single-threaded: every method runs on the engine thread ({@link AfkHost#schedule}).
 */
public final class AfkEngine {
    /** The phone's network for AFK. */
    public enum Net {
        /** Not known yet: connecting is tried. */
        UNKNOWN,
        NONE,
        /** Wi-Fi, Ethernet or another unmetered network. */
        WIFI,
        /** Mobile data only. */
        MOBILE
    }

    static final long DROPPED_ALERT_MS = 180_000L;
    static final long LOGIN_GAP_MS = AfkLimits.LOGIN_COMMAND_GAP_SECONDS * 1000L;
    static final long SNEAK_MIN_MS = 60_000L;
    static final long SNEAK_MAX_MS = 90_000L;
    static final long SNEAK_HOLD_MS = 800L;
    /** Longest console line kept (characters). */
    static final int MAX_LINE_CHARS = 1000;
    /** Longest server message kept in a status (the desktop's). */
    static final int MAX_MESSAGE_CHARS = 300;
    /** A line the account sent comes back within this: its echo is no mention. */
    static final long OWN_ECHO_MS = 5_000L;

    private final AfkWire wire;
    private final AfkHost host;
    private final Random random;
    private final AfkPacer pacer = new AfkPacer();
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Map<String, AfkStatus> stopped = new LinkedHashMap<>();
    private final Map<String, Console> consoles = new LinkedHashMap<>();
    private Net net = Net.UNKNOWN;

    public AfkEngine(AfkWire wire, AfkHost host, Random random) {
        this.wire = wire;
        this.host = host;
        this.random = random;
    }

    // ---- what the service asks ----

    public AfkStartResult start(String id) {
        if (runs.containsKey(id)) return AfkStartResult.ALREADY_RUNNING;
        if (runs.size() >= AfkLimits.MAX_RUNNING) return AfkStartResult.LIMIT_REACHED;
        AfkHost.Loaded loaded = host.load(id);
        if (loaded == null) return AfkStartResult.NO_ACCOUNT;
        stopped.remove(id);
        Run run = new Run(id, loaded);
        runs.put(id, run);
        run.requestConnect();
        host.changed();
        return AfkStartResult.STARTED;
    }

    /** The player stopped it. */
    public void stop(String id) {
        Run run = runs.get(id);
        if (run != null) {
            run.end(null, null, false);
        } else if (stopped.remove(id) != null) {
            host.changed();
        }
    }

    /** Every account: {@code reason} null for the player's stop, else the phone's (battery, heat). */
    public void stopAll(AfkReason reason) {
        for (Run run : new ArrayList<>(runs.values())) run.end(reason, null, false);
    }

    public AfkSendResult send(String id, String text) {
        Run run = runs.get(id);
        if (run == null) return AfkSendResult.NOT_RUNNING;
        String line = text == null ? "" : text.trim();
        if (line.isEmpty()) return AfkSendResult.EMPTY;
        if (line.length() > AfkLimits.MAX_LINE_CHARS) return AfkSendResult.TOO_LONG;
        if (run.state != AfkState.IN_GAME || run.conn == null) return AfkSendResult.NOT_IN_GAME;
        long now = host.now();
        if (!run.limiter.tryTake(now)) return AfkSendResult.TOO_FAST;
        run.filter.addUserCommand(line);
        run.conn.chat(line);
        run.lastSent = line;
        run.lastSentAt = now;
        run.emit(AfkLine.Kind.OWN, AfkText.plain(run.filter.mask(line)), run.account.name, false);
        return AfkSendResult.SENT;
    }

    public void network(Net now) {
        if (now == net) return;
        net = now;
        for (Run run : new ArrayList<>(runs.values())) run.networkChanged();
    }

    public Net network() {
        return net;
    }

    /** The running accounts' statuses, then the ones that stopped for a reason. */
    public List<AfkStatus> statuses() {
        List<AfkStatus> out = new ArrayList<>();
        for (Run run : runs.values()) out.add(run.status());
        out.addAll(stopped.values());
        return out;
    }

    public List<String> runningIds() {
        return new ArrayList<>(runs.keySet());
    }

    public List<String> runningNames() {
        List<String> out = new ArrayList<>();
        for (Run run : runs.values()) out.add(run.account.name);
        return out;
    }

    public int runningCount() {
        return runs.size();
    }

    public int inGameCount() {
        int n = 0;
        for (Run run : runs.values()) {
            if (run.state == AfkState.IN_GAME) n++;
        }
        return n;
    }

    /** Accounts that stopped for a reason (reason, server message, when). */
    public Map<String, AfkStatus> stoppedStatuses() {
        return Collections.unmodifiableMap(stopped);
    }

    /** Restores stops a previous process recorded (so the screens still say why). */
    public void restoreStopped(AfkStatus status) {
        if (!runs.containsKey(status.accountId) && status.reason != null) stopped.put(status.accountId, status);
    }

    public List<AfkLine> backlog(String id) {
        Console console = consoles.get(id);
        return console == null ? Collections.<AfkLine>emptyList() : new ArrayList<>(console.lines);
    }

    // ---- one account ----

    private static final class Console {
        final ArrayDeque<AfkLine> lines = new ArrayDeque<>();
        long seq;
    }

    private Console console(String id) {
        Console c = consoles.get(id);
        if (c == null) {
            c = new Console();
            consoles.put(id, c);
        }
        return c;
    }

    final class Run {
        final String id;
        AfkAccount account;
        List<String> commands;
        AfkLineFilter filter;
        final AfkSendLimiter limiter = new AfkSendLimiter();

        AfkState state = AfkState.CONNECTING;
        long since;
        long inGameSince;
        long nextAttemptAt;
        int attempts;
        AfkRestart.KickCause lastCause;
        int causeRepeats;
        AfkReason reason;
        String serverMessage;
        boolean running = true;

        AfkWire.Conn conn;
        Events events;
        boolean joinedThisConn;
        long joinedAt;
        String transferHost;
        int transferPort;

        AfkHost.Cancel connectTimer;
        AfkHost.Cancel droppedTimer;
        AfkHost.Cancel sneakTimer;
        AfkHost.Cancel sneakRelease;
        final List<AfkHost.Cancel> loginTimers = new ArrayList<>();
        boolean droppedShown;

        String lastSent;
        long lastSentAt;

        Run(String id, AfkHost.Loaded loaded) {
            this.id = id;
            take(loaded);
            this.since = host.now();
        }

        private void take(AfkHost.Loaded loaded) {
            this.account = loaded.account;
            this.commands = loaded.loginCommands == null ? Collections.<String>emptyList() : loaded.loginCommands;
            this.filter = new AfkLineFilter(commands);
        }

        AfkStatus status() {
            AfkReason r = state == AfkState.WAITING || state == AfkState.NO_NETWORK ? reason : null;
            if (state == AfkState.NO_NETWORK) r = netReason();
            return new AfkStatus(id, account.name, state, since, state == AfkState.IN_GAME ? inGameSince : 0,
                    state == AfkState.WAITING ? nextAttemptAt : 0, attempts, r,
                    state == AfkState.WAITING ? serverMessage : null);
        }

        void setState(AfkState s) {
            if (s != state) since = host.now();
            state = s;
            host.status(status());
        }

        boolean netOk() {
            return net == Net.UNKNOWN || net == Net.WIFI || (net == Net.MOBILE && account.mobileData);
        }

        AfkReason netReason() {
            return net == Net.MOBILE ? AfkReason.WAITING_WIFI : AfkReason.NO_NETWORK;
        }

        /** Takes this account's turn at its server and connects in it. */
        void requestConnect() {
            cancel(connectTimer);
            connectTimer = null;
            if (!running) return;
            if (!netOk()) {
                setState(AfkState.NO_NETWORK);
                armDroppedAlert();
                return;
            }
            long now = host.now();
            String dest = AfkPacer.destination(transferHost != null ? transferHost : account.host);
            long slot = pacer.reserve(dest, AfkPacer.MIN_CONNECT_SPACING_MS, now);
            setState(AfkState.CONNECTING);
            connectTimer = host.schedule(Math.max(0, slot - now), this::connectNow);
        }

        void connectNow() {
            connectTimer = null;
            if (!running) return;
            if (!netOk()) {
                setState(AfkState.NO_NETWORK);
                return;
            }
            AfkHost.Loaded loaded = host.load(id);
            if (loaded == null) {
                // Removed while it ran.
                end(null, null, true);
                return;
            }
            take(loaded);
            boolean transfer = transferHost != null;
            AfkWire.Target target = new AfkWire.Target(transfer ? transferHost : account.host,
                    transfer ? transferPort : account.port, account.name, transfer, host.words().turkish() ? "tr_tr" : "en_us");
            transferHost = null;
            notice(host.words().connecting(target.address()));
            joinedThisConn = false;
            joinedAt = 0;
            Events ev = new Events();
            events = ev;
            conn = wire.open(target, ev);
        }

        void joined() {
            long now = host.now();
            boolean first = !joinedThisConn;
            joinedThisConn = true;
            if (first) {
                joinedAt = now;
                inGameSince = now;
            }
            reason = null;
            serverMessage = null;
            setState(AfkState.IN_GAME);
            if (first) notice(host.words().joined());
            cancel(droppedTimer);
            droppedTimer = null;
            if (droppedShown) {
                droppedShown = false;
                host.alertCleared(id, AfkAlert.Kind.DROPPED);
            }
            if (account.loginMode == AfkLoginMode.EVERY_JOIN || first) sendLoginCommands();
            scheduleSneak();
        }

        void sendLoginCommands() {
            cancelLogin();
            final List<String> list = new ArrayList<>(commands);
            for (int i = 0; i < list.size(); i++) {
                final String command = list.get(i);
                final boolean last = i == list.size() - 1;
                loginTimers.add(host.schedule((i + 1) * LOGIN_GAP_MS, () -> {
                    if (conn == null || state != AfkState.IN_GAME) return;
                    conn.chat(command);
                    if (last) notice(host.words().loginCommandsSent());
                }));
            }
        }

        void scheduleSneak() {
            if (!account.sneak || sneakTimer != null) return;
            long wait = SNEAK_MIN_MS + (long) (random.nextDouble() * (SNEAK_MAX_MS - SNEAK_MIN_MS));
            sneakTimer = host.schedule(wait, () -> {
                sneakTimer = null;
                if (conn == null || state != AfkState.IN_GAME) return;
                final AfkWire.Conn pressed = conn;
                pressed.sneak(true);
                cancel(sneakRelease);
                sneakRelease = host.schedule(SNEAK_HOLD_MS, () -> {
                    sneakRelease = null;
                    if (conn == pressed) pressed.sneak(false);
                });
                scheduleSneak();
            });
        }

        void line(AfkLine.Kind kind, AfkText text, String sender) {
            AfkText masked = text.withText(filter.mask(text.text)).capped(MAX_LINE_CHARS);
            if (masked.isEmpty()) return;
            long now = host.now();
            boolean fromSelf = sender != null && sender.equalsIgnoreCase(account.name);
            AfkLine.Kind k = kind;
            String from = sender;
            if (k == AfkLine.Kind.SYSTEM) {
                String pm = AfkChatRules.privateFrom(masked.text);
                if (pm != null && !pm.equalsIgnoreCase(account.name)) {
                    k = AfkLine.Kind.PRIVATE;
                    from = pm;
                }
            }
            boolean ownEcho = fromSelf || (lastSent != null && now - lastSentAt < OWN_ECHO_MS
                    && masked.text.contains(filter.mask(lastSent)));
            boolean mentioned = !ownEcho && AfkChatRules.mentions(masked.text, account.name);
            emit(k, masked, from, mentioned);
            if (k == AfkLine.Kind.PRIVATE && !fromSelf) {
                alert(AfkAlert.Kind.PRIVATE_MESSAGE, masked.text, null);
            } else if (mentioned) {
                alert(AfkAlert.Kind.MENTION, masked.text, null);
            }
        }

        void window(String title) {
            String t = title == null ? "" : filter.mask(oneLine(title, 200));
            notice(host.words().window(t));
            alert(AfkAlert.Kind.SERVER_WINDOW, t, null);
        }

        void transfer(String toHost, int toPort) {
            conn = null;
            events = null;
            cancelConnTimers();
            transferHost = toHost == null ? null : toHost.trim().toLowerCase(java.util.Locale.ROOT);
            transferPort = toPort;
            if (transferHost == null || transferHost.isEmpty()) transferHost = null;
            String shown = transferHost == null ? "" : new AfkWire.Target(transferHost, toPort, "", true, "").address();
            notice(host.words().transfer(shown));
            joinedThisConn = false;
            joinedAt = 0;
            requestConnect();
        }

        void ended(AfkDrop drop) {
            conn = null;
            events = null;
            cancelConnTimers();
            if (!running) return;
            long now = host.now();
            AfkDrop d = drop.masked(filter.mask(oneLine(drop.message, MAX_MESSAGE_CHARS)),
                    filter.mask(oneLine(drop.english, MAX_MESSAGE_CHARS)));
            AfkRestart.KickCause cause = AfkRestart.causeOf(d);
            if (joinedAt > 0 && AfkRestart.lasting(now - joinedAt, cause)) {
                attempts = 0;
                lastCause = null;
                causeRepeats = 0;
            }
            int repeats = cause != null && cause == lastCause ? causeRepeats : 0;
            AfkRestart.Decision decision = AfkRestart.decide(d, attempts, repeats);
            if (cause != null && cause == lastCause) {
                causeRepeats++;
            } else {
                lastCause = cause;
                causeRepeats = cause != null ? 1 : 0;
            }
            joinedThisConn = false;
            joinedAt = 0;
            inGameSince = 0;
            if (decision.stops()) {
                end(decision.stop, d.message, false);
                return;
            }
            attempts = decision.attempt;
            reason = d.kind.reason;
            serverMessage = d.message;
            armDroppedAlert();
            if (!netOk()) {
                setState(AfkState.NO_NETWORK);
                return;
            }
            long delay = AfkRestart.spreadDelayMs(attempts, random.nextDouble());
            nextAttemptAt = now + delay;
            setState(AfkState.WAITING);
            notice(host.words().dropped(reason, delay));
            connectTimer = host.schedule(delay, this::requestConnect);
        }

        void networkChanged() {
            if (!running) return;
            if (!netOk()) {
                if (conn != null) {
                    // The connection went with its network: closed now instead of after the read timeout.
                    conn.close();
                    conn = null;
                    events = null;
                    cancelConnTimers();
                    joinedThisConn = false;
                    joinedAt = 0;
                    inGameSince = 0;
                    reason = AfkReason.CONNECTION_LOST;
                }
                cancel(connectTimer);
                connectTimer = null;
                setState(AfkState.NO_NETWORK);
                armDroppedAlert();
            } else if (state == AfkState.NO_NETWORK) {
                requestConnect();
            }
        }

        void armDroppedAlert() {
            if (droppedTimer != null || droppedShown) return;
            droppedTimer = host.schedule(DROPPED_ALERT_MS, () -> {
                droppedTimer = null;
                if (!running || state == AfkState.IN_GAME) return;
                droppedShown = true;
                alert(AfkAlert.Kind.DROPPED, "", state == AfkState.NO_NETWORK ? netReason() : reason);
            });
        }

        /**
         * Stops the account: {@code why} null for the player's stop (or a removed account, {@code quiet}), else a
         * refusal or the phone's reason.
         */
        void end(AfkReason why, String message, boolean quiet) {
            if (!running) return;
            running = false;
            if (conn != null) conn.close();
            conn = null;
            events = null;
            cancelConnTimers();
            cancel(connectTimer);
            connectTimer = null;
            cancel(droppedTimer);
            droppedTimer = null;
            if (droppedShown) {
                droppedShown = false;
                host.alertCleared(id, AfkAlert.Kind.DROPPED);
            }
            runs.remove(id);
            long now = host.now();
            AfkStatus status;
            if (why == null) {
                if (!quiet) notice(host.words().stoppedByPlayer());
                stopped.remove(id);
                status = AfkStatus.stopped(id, account.name);
            } else {
                notice(host.words().stopped(why));
                status = new AfkStatus(id, account.name, AfkState.STOPPED, now, 0, 0, attempts, why, message);
                stopped.put(id, status);
                if (why.kind == AfkReason.Kind.REFUSAL) alert(AfkAlert.Kind.STOPPED, message == null ? "" : message, why);
            }
            state = AfkState.STOPPED;
            host.status(status);
            host.changed();
        }

        void cancelConnTimers() {
            cancelLogin();
            cancel(sneakTimer);
            sneakTimer = null;
            cancel(sneakRelease);
            sneakRelease = null;
        }

        void cancelLogin() {
            for (AfkHost.Cancel c : loginTimers) c.cancel();
            loginTimers.clear();
        }

        void notice(String text) {
            emit(AfkLine.Kind.NOTICE, AfkText.plain(text), null, false);
        }

        void emit(AfkLine.Kind kind, AfkText text, String sender, boolean mentioned) {
            Console c = console(id);
            AfkLine line = new AfkLine(id, ++c.seq, host.now(), kind, text.text, text.spans, sender, mentioned);
            c.lines.addLast(line);
            while (c.lines.size() > AfkLimits.CONSOLE_LINES) c.lines.pollFirst();
            host.line(line);
        }

        void alert(AfkAlert.Kind kind, String text, AfkReason why) {
            host.alert(new AfkAlert(id, account.name, kind, text, why, host.now()));
        }

        /** One connection's events; a closed or replaced connection's late events are ignored. */
        final class Events implements AfkWire.Events {
            private boolean live() {
                return running && events == this;
            }

            @Override
            public void loggingIn() {
                if (live()) setState(AfkState.LOGGING_IN);
            }

            @Override
            public void configuring() {
                if (live()) setState(AfkState.CONFIGURING);
            }

            @Override
            public void joined() {
                if (live()) Run.this.joined();
            }

            @Override
            public void line(AfkLine.Kind kind, AfkText text, String sender) {
                if (live()) Run.this.line(kind, text, sender);
            }

            @Override
            public void died() {
                if (live()) notice(host.words().respawned());
            }

            @Override
            public void window(String title) {
                if (live()) Run.this.window(title);
            }

            @Override
            public void transfer(String toHost, int toPort) {
                if (live()) Run.this.transfer(toHost, toPort);
            }

            @Override
            public void ended(AfkDrop drop) {
                if (live()) Run.this.ended(drop);
            }
        }
    }

    private static void cancel(AfkHost.Cancel c) {
        if (c != null) c.cancel();
    }

    /** Line breaks flattened, trimmed, at most {@code max} characters with "…". */
    static String oneLine(String text, int max) {
        if (text == null) return null;
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.codePointCount(0, flat.length()) <= max) return flat;
        int cut = flat.offsetByCodePoints(0, max - 1);
        return flat.substring(0, cut) + "…";
    }
}
