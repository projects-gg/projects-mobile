package gg.projects.mobile.afk.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import gg.projects.mobile.afk.api.AfkAccount;
import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkLoginMode;
import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkStatus;

/** The engine's surroundings for JVM tests: a manual clock and timers, stored accounts, and everything it says. */
final class FakeHost implements AfkHost {
    long now = 1_000_000L;
    private long seq;
    private final PriorityQueue<Timer> timers = new PriorityQueue<>();
    final Map<String, Loaded> accounts = new HashMap<>();
    final List<AfkStatus> statuses = new ArrayList<>();
    final List<AfkLine> lines = new ArrayList<>();
    final List<AfkAlert> alerts = new ArrayList<>();
    final List<String> cleared = new ArrayList<>();
    int changed;

    private final class Timer implements Comparable<Timer>, Cancel {
        final long at;
        final long order = seq++;
        final Runnable r;
        boolean cancelled;

        Timer(long at, Runnable r) {
            this.at = at;
            this.r = r;
        }

        @Override
        public int compareTo(Timer o) {
            return at != o.at ? Long.compare(at, o.at) : Long.compare(order, o.order);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    AfkAccount account(String id, String name, String host, List<String> commands, AfkLoginMode mode, boolean mobileData) {
        AfkAccount a = new AfkAccount(id, name, host, 0, commands.size(), mode, true, mobileData, 1L);
        accounts.put(id, new Loaded(a, commands));
        return a;
    }

    /** Runs every timer due within {@code ms}, in order, moving the clock. */
    void advance(long ms) {
        long end = now + ms;
        while (true) {
            Timer t = timers.peek();
            if (t == null || t.at > end) break;
            timers.poll();
            if (t.cancelled) continue;
            now = Math.max(now, t.at);
            t.r.run();
        }
        now = end;
    }

    int pendingTimers() {
        int n = 0;
        for (Timer t : timers) {
            if (!t.cancelled) n++;
        }
        return n;
    }

    AfkStatus lastStatus(String id) {
        for (int i = statuses.size() - 1; i >= 0; i--) {
            if (statuses.get(i).accountId.equals(id)) return statuses.get(i);
        }
        return null;
    }

    List<String> texts(String id) {
        List<String> out = new ArrayList<>();
        for (AfkLine l : lines) {
            if (l.accountId.equals(id)) out.add(l.kind + ": " + l.text);
        }
        return out;
    }

    @Override
    public long now() {
        return now;
    }

    @Override
    public Cancel schedule(long delayMs, Runnable r) {
        Timer t = new Timer(now + Math.max(0, delayMs), r);
        timers.add(t);
        return t;
    }

    @Override
    public Loaded load(String accountId) {
        return accounts.get(accountId);
    }

    @Override
    public Words words() {
        return WORDS;
    }

    @Override
    public void status(AfkStatus status) {
        statuses.add(status);
    }

    @Override
    public void line(AfkLine line) {
        lines.add(line);
    }

    @Override
    public void alert(AfkAlert alert) {
        alerts.add(alert);
    }

    @Override
    public void alertCleared(String accountId, AfkAlert.Kind kind) {
        cleared.add(accountId + ":" + kind);
    }

    @Override
    public void changed() {
        changed++;
    }

    static final Words WORDS = new Words() {
        @Override
        public String connecting(String address) {
            return "connecting " + address;
        }

        @Override
        public String joined() {
            return "joined";
        }

        @Override
        public String dropped(AfkReason reason, long waitMs) {
            return "dropped " + reason.key() + " " + waitMs;
        }

        @Override
        public String stopped(AfkReason reason) {
            return "stopped " + reason.key();
        }

        @Override
        public String stoppedByPlayer() {
            return "stopped by player";
        }

        @Override
        public String transfer(String address) {
            return "transfer " + address;
        }

        @Override
        public String window(String title) {
            return "window " + title;
        }

        @Override
        public String respawned() {
            return "respawned";
        }

        @Override
        public String loginCommandsSent() {
            return "login commands sent";
        }

        @Override
        public boolean turkish() {
            return false;
        }
    };
}
