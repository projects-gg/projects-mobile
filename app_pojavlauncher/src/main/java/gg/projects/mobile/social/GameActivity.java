package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * FIX2 (the desktop's {@code pl_social::game}): what the running game is doing, read from its log: the
 * server it connects to, a singleplayer world, or back in the menus. Pure and cheap, because every
 * line of the game's log passes through here (in the game's process, while its socket keeps the
 * player "Oyunda").
 *
 * <p>Lines the game writes (message part, without the {@code [time] [thread/LEVEL]:} prefix):
 * <ul>
 * <li>{@code Connecting to play.projects.gg, 25565}: joining a server (every version since 1.7; 1.17+
 * logs the address as typed, older versions the SRV target);</li>
 * <li>{@code Starting integrated minecraft server version 1.21.1}: a singleplayer world opens;</li>
 * <li>{@code Stopping server}: that world closes (back in the menus);</li>
 * <li>{@code Couldn't connect to server}: a join failed (back in the menus).</li>
 * </ul>
 * A normal disconnect from a server leaves no line, so the last server stays until the next signal;
 * the status never claims more than the log said.
 */
public final class GameActivity {
    private static final int DEFAULT_PORT = 25565;
    private static final String CONNECTING = "Connecting to ";
    private static final String INTEGRATED = "Starting integrated minecraft server";
    private static final String STOPPING = "Stopping server";
    private static final String FAILED = "Couldn't connect to server";

    /** Where the player is, as friends may see it. */
    public static final class Status {
        public enum Where { PLAYING, SERVER, SINGLEPLAYER }

        public final Where where;
        /** {@link Where#SERVER}: the public server ({@code host} or {@code host:port}). */
        @Nullable
        public final String host;

        private Status(Where where, @Nullable String host) {
            this.where = where;
            this.host = host;
        }

        /** In game, the place not known (the menus, a LAN world, a server the log did not name). */
        public static final Status PLAYING = new Status(Where.PLAYING, null);
        public static final Status SINGLEPLAYER = new Status(Where.SINGLEPLAYER, null);

        public static Status server(String host) {
            return new Status(Where.SERVER, host);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Status)) return false;
            Status other = (Status) o;
            return where == other.where && (host == null ? other.host == null : host.equals(other.host));
        }

        @Override
        public int hashCode() {
            return where.hashCode() * 31 + (host == null ? 0 : host.hashCode());
        }

        @Override
        public String toString() {
            return host == null ? where.toString() : where + ":" + host;
        }
    }

    /** One meaningful log line. */
    static final class Signal {
        enum Kind { CONNECTING, SINGLEPLAYER, MENU }

        final Kind kind;
        @Nullable
        final String host;
        final int port;

        Signal(Kind kind, @Nullable String host, int port) {
            this.kind = kind;
            this.host = host;
            this.port = port;
        }
    }

    /**
     * The message of a log line: what follows the time, the thread and level, and a logger's name:
     * {@code [12:00:00] [Render thread/INFO]: …} (vanilla), {@code [12:00:00] [Render thread/INFO]
     * (Minecraft) …} (Fabric) or {@code [12:00:00] [Render thread/INFO] [minecraft/ConnectScreen]: …}
     * (Forge). Only that prefix goes: a chat line keeps its own {@code [CHAT]} and the sender, so what a
     * player or a server writes never reads as a signal. A line without the prefix is the message.
     */
    static String messageOf(String line) {
        String t = line.trim();
        if (!t.startsWith("[")) return t;
        int time = t.indexOf(']');
        if (time < 0) return t;
        int i = skipSpaces(t, time + 1);
        if (i >= t.length() || t.charAt(i) != '[') return t;
        int level = t.indexOf(']', i);
        if (level < 0 || t.substring(i, level).indexOf('/') < 0) return t;
        i = level + 1;
        if (i < t.length() && t.charAt(i) == ':') return t.substring(skipSpaces(t, i + 1));
        i = skipSpaces(t, i);
        if (i < t.length() && t.charAt(i) == '[') {
            int logger = t.indexOf(']', i);
            if (logger > 0 && logger + 1 < t.length() && t.charAt(logger + 1) == ':') return t.substring(skipSpaces(t, logger + 2));
            return t;
        }
        if (i < t.length() && t.charAt(i) == '(') {
            int logger = t.indexOf(')', i);
            if (logger > 0) {
                int j = logger + 1;
                if (j < t.length() && t.charAt(j) == ':') j++;
                return t.substring(skipSpaces(t, j));
            }
        }
        return t.substring(i);
    }

    private static int skipSpaces(String t, int i) {
        while (i < t.length() && t.charAt(i) == ' ') i++;
        return i;
    }

    /** A cheap pre-check: false for messages that can never be a {@link Signal}. */
    public static boolean maySignal(String message) {
        String m = message.trim();
        return m.startsWith(CONNECTING) || m.startsWith(INTEGRATED) || m.startsWith(STOPPING) || m.startsWith(FAILED);
    }

    /** Reads one log message. */
    @Nullable
    static Signal signal(String message) {
        String m = message.trim();
        if (m.startsWith(CONNECTING)) {
            String rest = m.substring(CONNECTING.length());
            int comma = rest.lastIndexOf(", ");
            if (comma < 0) return null;
            String host = rest.substring(0, comma).trim();
            String port = rest.substring(comma + 2).trim();
            if (host.isEmpty() || !port.matches("[0-9]{1,5}")) return null;
            int p = Integer.parseInt(port);
            if (p > 65535) return null;
            return new Signal(Signal.Kind.CONNECTING, host, p);
        }
        if (m.startsWith(INTEGRATED)) return new Signal(Signal.Kind.SINGLEPLAYER, null, 0);
        if (m.equals(STOPPING) || m.startsWith(FAILED)) return new Signal(Signal.Kind.MENU, null, 0);
        return null;
    }

    /**
     * {@code host} or {@code host:port} (the default port left out) as friends may see it, or null for
     * local network addresses and anything that is not a plausible public server name.
     */
    @Nullable
    public static String serverLabel(@Nullable String host, int port) {
        if (host == null) return null;
        String h = host.trim();
        while (h.startsWith("[")) h = h.substring(1);
        while (h.endsWith("]")) h = h.substring(0, h.length() - 1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        h = h.toLowerCase(Locale.ROOT);
        if (h.indexOf(':') >= 0) return null; // IPv6: the service takes host names and IPv4 only.
        int[] ip = ipv4(h);
        if (ip != null) {
            if (localIpv4(ip)) return null;
        } else if (h.equals("localhost") || !validHostName(h)) {
            return null;
        }
        if (port <= 0 || port > 65535) return null;
        return port == DEFAULT_PORT ? h : h + ":" + port;
    }

    @Nullable
    private static int[] ipv4(String h) {
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) return null;
            out[i] = Integer.parseInt(parts[i]);
            if (out[i] > 255) return null;
        }
        return out;
    }

    /** Loopback, private, link-local, unspecified and broadcast: nothing friends could join. */
    private static boolean localIpv4(int[] ip) {
        if (ip[0] == 127 || ip[0] == 10 || ip[0] == 0) return true;
        if (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) return true;
        if (ip[0] == 192 && ip[1] == 168) return true;
        if (ip[0] == 169 && ip[1] == 254) return true;
        return ip[0] == 255 && ip[1] == 255 && ip[2] == 255 && ip[3] == 255;
    }

    /** Labels of {@code [a-z0-9-]}, 1–63 characters, no leading or trailing hyphen (what the service accepts). */
    private static boolean validHostName(String host) {
        if (host.isEmpty() || host.length() > 253) return false;
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return false;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) return false;
            }
        }
        return true;
    }

    private Status mStatus;
    /**
     * The server the launch joins by itself: it names the first connection, because older versions
     * log the SRV target rather than the address the player knows.
     */
    @Nullable
    private String mQuickPlay;

    private GameActivity(@Nullable String quickPlay) {
        mStatus = Status.PLAYING;
        mQuickPlay = quickPlay;
    }

    /**
     * The game just started: playing, server not known yet. {@code quickPlay}: the server the launch joins
     * ({@code host} or {@code host:port}), if any; a local network address names nothing.
     */
    public static GameActivity started(@Nullable String quickPlay) {
        return new GameActivity(publicLabel(quickPlay));
    }

    /** {@code host}, {@code host:port} or {@code [v6]:port} as {@link #serverLabel} shows it; null when not public. */
    @Nullable
    public static String publicLabel(@Nullable String address) {
        if (address == null) return null;
        String a = address.trim();
        if (a.isEmpty()) return null;
        String host = a;
        int port = DEFAULT_PORT;
        int close = a.lastIndexOf(']');
        int colon = a.lastIndexOf(':');
        if (a.startsWith("[") && close > 0) {
            host = a.substring(1, close);
            if (colon > close) port = parsePort(a.substring(colon + 1));
        } else if (colon > 0 && a.indexOf(':') == colon) {
            host = a.substring(0, colon);
            port = parsePort(a.substring(colon + 1));
        }
        return serverLabel(host, port);
    }

    private static int parsePort(String port) {
        return port.matches("[0-9]{1,5}") ? Integer.parseInt(port) : -1;
    }

    /** A log line (prefix and all) that may change the status: the cheap test every line goes through. */
    public static boolean mayMatter(String line) {
        return line.contains(CONNECTING) || line.contains(INTEGRATED) || line.contains(STOPPING) || line.contains(FAILED);
    }

    public Status status() {
        return mStatus;
    }

    /** Reads one log line (or its message); returns the new status when it changed. */
    @Nullable
    public Status observe(String line) {
        String message = messageOf(line);
        if (!maySignal(message)) return null;
        Signal s = signal(message);
        if (s == null) return null;
        Status next;
        switch (s.kind) {
            case CONNECTING: {
                String quick = mQuickPlay;
                mQuickPlay = null;
                String label = quick != null ? quick : serverLabel(s.host, s.port);
                next = label == null ? Status.PLAYING : Status.server(label);
                break;
            }
            case SINGLEPLAYER:
                mQuickPlay = null;
                next = Status.SINGLEPLAYER;
                break;
            case MENU:
            default:
                next = Status.PLAYING;
                break;
        }
        if (next.equals(mStatus)) return null;
        mStatus = next;
        return next;
    }

    /**
     * What Pojav's logger hands over (stdio_is.c): one read of the game's output, its last line break taken
     * off. The game writes whole lines, so a piece ends where a line ends and may hold several. Returns its
     * lines that may change the status; most pieces are dropped by one {@link #mayMatter} test.
     */
    public static List<String> linesThatMatter(@Nullable String piece) {
        if (piece == null || !mayMatter(piece)) return Collections.emptyList();
        List<String> out = new ArrayList<>(1);
        int start = 0;
        while (start < piece.length()) {
            int end = piece.indexOf('\n', start);
            if (end < 0) end = piece.length();
            String line = piece.substring(start, end);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (mayMatter(line)) out.add(line);
            start = end + 1;
        }
        return out;
    }
}
