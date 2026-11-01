package gg.projects.mobile.afk.store;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.afk.api.AfkDraft;
import gg.projects.mobile.afk.api.AfkLimits;
import gg.projects.mobile.afk.api.AfkProblem;

/**
 * PHONE-AFK: the add/edit form's rules (pure Java, JVM tested). {@link #check} lists every problem of a draft;
 * {@link #normalize} gives what is kept. The name rule is the desktop's (3–16 of {@code [A-Za-z0-9_]}); the address is
 * {@code host}, {@code host:port} or {@code [ipv6]:port}, its host kept in lower case with IDN in ASCII; servers in the
 * local network are not supported yet (phone-afk.md §4: Android 17 asks a permission for them).
 */
public final class AfkDrafts {
    private AfkDrafts() {}

    /** Debug builds' adb harness only (a local test server on an emulator); never set by the app itself. */
    public static volatile boolean allowLocalForTests;

    /** A draft as it is kept: trimmed name, lower-case ASCII host, port (0: none written), clean login commands. */
    public static final class Clean {
        public final String name;
        public final String host;
        public final int port;
        public final List<String> loginCommands;

        Clean(String name, String host, int port, List<String> loginCommands) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.loginCommands = loginCommands;
        }
    }

    /** A parsed address: host (as written, brackets removed) and port (0: none, -1: not a valid port). */
    static final class Address {
        final String host;
        final int port;

        Address(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static boolean validName(String name) {
        if (name == null) return false;
        int n = name.length();
        if (n < AfkLimits.NAME_MIN || n > AfkLimits.NAME_MAX) return false;
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Every problem of {@code draft}. {@code otherNames}: the names of the other AFK accounts (the edited one left
     * out); {@code adding}: a new account, which also checks {@link AfkLimits#MAX_ACCOUNTS} against
     * {@code accountCount}.
     */
    public static List<AfkProblem> check(AfkDraft draft, Collection<String> otherNames, boolean adding, int accountCount) {
        List<AfkProblem> problems = new ArrayList<>();
        if (adding && accountCount >= AfkLimits.MAX_ACCOUNTS) problems.add(AfkProblem.TOO_MANY_ACCOUNTS);
        String name = trim(draft.name);
        if (!validName(name)) {
            problems.add(AfkProblem.NAME_INVALID);
        } else {
            for (String other : otherNames) {
                if (other != null && other.equalsIgnoreCase(name)) {
                    problems.add(AfkProblem.NAME_TAKEN);
                    break;
                }
            }
        }
        String address = trim(draft.address);
        if (address.isEmpty()) {
            problems.add(AfkProblem.ADDRESS_MISSING);
        } else {
            Address parsed = parseAddress(address);
            String host = parsed == null ? null : asciiHost(parsed.host);
            if (parsed == null || host == null || address.length() > AfkLimits.MAX_ADDRESS_CHARS) {
                problems.add(AfkProblem.ADDRESS_INVALID);
            } else {
                if (parsed.port < 0) problems.add(AfkProblem.PORT_INVALID);
                if (isLocal(host) && !allowLocalForTests) problems.add(AfkProblem.LOCAL_ADDRESS);
            }
        }
        List<String> commands = cleanCommands(draft.loginCommands);
        if (commands.size() > AfkLimits.MAX_LOGIN_COMMANDS) problems.add(AfkProblem.TOO_MANY_COMMANDS);
        for (String command : commands) {
            if (command.length() > AfkLimits.MAX_LINE_CHARS) {
                problems.add(AfkProblem.COMMAND_TOO_LONG);
                break;
            }
        }
        return problems;
    }

    /** What is kept of a draft that has no problems ({@link #check} empty). */
    public static Clean normalize(AfkDraft draft) {
        Address parsed = parseAddress(trim(draft.address));
        String host = parsed == null ? "" : asciiHost(parsed.host);
        return new Clean(trim(draft.name), host == null ? "" : host, parsed == null ? 0 : Math.max(0, parsed.port),
                cleanCommands(draft.loginCommands));
    }

    /** Lines kept: control characters removed, spaces around trimmed, blank lines dropped. */
    public static List<String> cleanCommands(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        for (String line : lines) {
            if (line == null) continue;
            StringBuilder b = new StringBuilder(line.length());
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c < 0x20 || c == 0x7f) continue;
                b.append(c);
            }
            String kept = b.toString().trim();
            if (!kept.isEmpty()) out.add(kept);
        }
        return out;
    }

    static Address parseAddress(String text) {
        if (text == null || text.isEmpty()) return null;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 2) return null;
            String host = text.substring(1, close);
            String rest = text.substring(close + 1);
            if (rest.isEmpty()) return new Address(host, 0);
            if (!rest.startsWith(":")) return null;
            return new Address(host, parsePort(rest.substring(1)));
        }
        int first = text.indexOf(':');
        if (first < 0) return new Address(text, 0);
        if (text.indexOf(':', first + 1) >= 0) {
            // More than one colon without brackets: an IPv6 literal without a port.
            return new Address(text, 0);
        }
        return new Address(text.substring(0, first), parsePort(text.substring(first + 1)));
    }

    private static int parsePort(String text) {
        if (text.isEmpty() || text.length() > 5) return -1;
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
        }
        return value >= 1 && value <= 65535 ? value : -1;
    }

    /** The host in lower-case ASCII (IDN), or null when it cannot be a host name or an IP literal. */
    static String asciiHost(String host) {
        if (host == null) return null;
        String h = host.trim();
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return null;
        if (h.indexOf(':') >= 0) return isIpv6(h) ? h.toLowerCase(Locale.ROOT) : null;
        String ascii;
        try {
            ascii = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (ascii.isEmpty() || ascii.length() > 253) return null;
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) return null;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
                if (!ok) return null;
            }
            if (label.startsWith("-") || label.endsWith("-")) return null;
        }
        return ascii;
    }

    private static boolean isIpv6(String h) {
        int colons = 0;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == ':') {
                colons++;
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.')) {
                return false;
            }
        }
        return colons >= 2 && colons <= 7;
    }

    /** An IPv4 literal's four parts, or null. */
    static int[] ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') return null;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return null;
            out[i] = v;
        }
        return out;
    }

    /** A server in the local network or on the phone itself (by its written address; names are not resolved). */
    public static boolean isLocal(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan")
                || h.endsWith(".home.arpa") || h.endsWith(".internal")) {
            return true;
        }
        int[] v4 = ipv4(h);
        if (v4 != null) return isLocalIpv4(v4);
        if (h.indexOf(':') >= 0) {
            if (h.equals("::1") || h.equals("::")) return true;
            if (h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")) return true;
            if (h.startsWith("fc") || h.startsWith("fd")) return true;
            int last = h.lastIndexOf(':');
            int[] mapped = h.startsWith("::ffff:") ? ipv4(h.substring(last + 1)) : null;
            return mapped != null && isLocalIpv4(mapped);
        }
        return false;
    }

    /** 0/8, 10/8, 127/8, 169.254/16, 172.16/12, 192.168/16. */
    public static boolean isLocalIpv4(int[] a) {
        return a[0] == 0 || a[0] == 10 || a[0] == 127 || (a[0] == 169 && a[1] == 254)
                || (a[0] == 172 && a[1] >= 16 && a[1] <= 31) || (a[0] == 192 && a[1] == 168);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
