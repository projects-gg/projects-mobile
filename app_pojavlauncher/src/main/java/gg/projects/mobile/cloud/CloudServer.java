package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import java.net.IDN;
import java.util.Locale;

/**
 * Server addresses as 7/24 Bulut takes them (services/cloud-afk README "Server address"; the launcher's
 * {@code server.rs}): sent already normalized (lowercase ASCII, IDNs as punycode, no trailing dot) because the
 * host is part of every seal's AAD and the service never rewrites it, the way a browser's
 * {@code new URL("http://" + input).hostname} reads it (the site sends the same). Private and reserved
 * addresses and the ports the machines never use are refused here before the service refuses them.
 */
public final class CloudServer {
    public final String host;
    /** 0: the client finds it (the SRV record, then 25565). */
    public final int port;

    public CloudServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Why an address cannot be used. */
    public enum Problem { EMPTY, FORMAT, DOT, PRIVATE, PORT }

    /** A normalized host, or why the input is not one. */
    public static final class Host {
        @Nullable
        public final String value;
        @Nullable
        public final Problem problem;

        private Host(@Nullable String value, @Nullable Problem problem) {
            this.value = value;
            this.problem = problem;
        }

        static Host ok(String value) {
            return new Host(value, null);
        }

        static Host refused(Problem problem) {
            return new Host(null, problem);
        }
    }

    private static final String[] PRIVATE_SUFFIXES = {
            ".localhost", ".local", ".lan", ".internal", ".home.arpa", ".invalid", ".test", ".example", ".onion",
    };

    private static final int[] REFUSED_PORTS = {
            1433, 1521, 2375, 2376, 3306, 3389, 5432, 5900, 5984, 6379, 6443, 8086, 9042, 9092, 9200, 9300, 11211, 27017,
    };

    /** The host the service stores for {@code input}, or why it cannot be one. */
    public static Host normalizeHost(@Nullable String input) {
        String raw = trimWhiteSpace(input == null ? "" : input);
        if (raw.isEmpty()) return Host.refused(Problem.EMPTY);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // `%` too: the browser would decode it first; nothing a player types needs it.
            if (c == '/' || c == '@' || c == '?' || c == '#' || c == ' ' || c == '[' || c == ']' || c == ':' || c == '\\' || c == '%') {
                return Host.refused(Problem.FORMAT);
            }
        }
        String host;
        try {
            // Lowercase first: IDNA leaves all-ASCII labels as they are (the browser lowercases them).
            host = IDN.toASCII(raw.toLowerCase(Locale.ROOT), IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return Host.refused(Problem.FORMAT);
        }
        if (endsInNumber(host)) {
            String ipv4 = parseWebIpv4(host);
            if (ipv4 == null) return Host.refused(Problem.FORMAT);
            host = ipv4;
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > 253) return Host.refused(Problem.FORMAT);
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            if (!ok) return Host.refused(Problem.FORMAT);
        }
        if (host.startsWith(".") || host.startsWith("-")) return Host.refused(Problem.FORMAT);
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) return Host.refused(Problem.FORMAT);
        }
        int[] ip = ipv4Literal(host);
        if (ip != null) return isPrivate(ip) ? Host.refused(Problem.PRIVATE) : Host.ok(host);
        if (host.equals("localhost")) return Host.refused(Problem.PRIVATE);
        for (String suffix : PRIVATE_SUFFIXES) if (host.endsWith(suffix)) return Host.refused(Problem.PRIVATE);
        if (host.indexOf('.') < 0) return Host.refused(Problem.DOT);
        String last = host.substring(host.lastIndexOf('.') + 1);
        if (allDigits(last)) return Host.refused(Problem.FORMAT);
        return Host.ok(host);
    }

    /** Whether the machines would connect to {@code port}; null when they would (0: the client finds it). */
    @Nullable
    public static Problem checkPort(int port) {
        if (port < 0 || port > 65535) return Problem.PORT;
        if (port >= 1 && port <= 1023) return Problem.PORT;
        for (int refused : REFUSED_PORTS) if (refused == port) return Problem.PORT;
        return null;
    }

    /**
     * Two addresses reach the same server: the same normalized host and the same port, where 0 and 25565 count as
     * one (two clients with one name there keep kicking each other).
     */
    public static boolean sameServer(CloudServer a, CloudServer b) {
        int pa = a.port == 0 ? 25565 : a.port;
        int pb = b.port == 0 ? 25565 : b.port;
        return pa == pb && hostKey(a.host).equals(hostKey(b.host));
    }

    private static String hostKey(String host) {
        Host normal = normalizeHost(host);
        return normal.value != null ? normal.value : host.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code play.projects.gg}, or {@code host:port} when a port other than the default is set. */
    public String label() {
        return port != 0 && port != 25565 ? host + ":" + port : host;
    }

    /** The AAD's server part: the host and port exactly as stored ({@code play.projects.gg:0}). */
    public String aadPart() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CloudServer)) return false;
        CloudServer other = (CloudServer) o;
        return port == other.port && host.equals(other.host);
    }

    @Override
    public int hashCode() {
        return host.hashCode() * 31 + port;
    }

    // ---- the browser's IPv4 parser (WHATWG URL: "ends in a number" and "IPv4 parser") ----

    private static boolean endsInNumber(String host) {
        String[] parts = host.split("\\.", -1);
        int n = parts.length;
        if (n > 1 && parts[n - 1].isEmpty()) n--;
        if (n == 0) return false;
        String last = parts[n - 1];
        if (!last.isEmpty() && allDigits(last)) return true;
        return parseIpv4Number(last) >= 0;
    }

    /** One part: decimal, {@code 0x} hex or {@code 0} octal; -1 when it is none of them. */
    private static long parseIpv4Number(String part) {
        if (part.isEmpty()) return -1;
        int radix = 10;
        String digits = part;
        if (part.length() >= 2 && (part.startsWith("0x") || part.startsWith("0X"))) {
            radix = 16;
            digits = part.substring(2);
        } else if (part.length() >= 2 && part.charAt(0) == '0') {
            radix = 8;
            digits = part.substring(1);
        }
        if (digits.isEmpty()) return 0;
        long value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = Character.digit(digits.charAt(i), radix);
            if (d < 0 || digits.charAt(i) > 0x7F) return -1;
            value = value * radix + d;
            if (value > 0xFFFFFFFFL * 256) return Long.MAX_VALUE / 4;
        }
        return value;
    }

    @Nullable
    private static String parseWebIpv4(String host) {
        String[] raw = host.split("\\.", -1);
        int n = raw.length;
        if (n > 1 && raw[n - 1].isEmpty()) n--;
        if (n == 0 || n > 4) return null;
        long[] numbers = new long[n];
        for (int i = 0; i < n; i++) {
            long value = parseIpv4Number(raw[i]);
            if (value < 0) return null;
            numbers[i] = value;
        }
        for (int i = 0; i < n - 1; i++) if (numbers[i] > 255) return null;
        long limit = 1L << (8 * (5 - n));
        if (numbers[n - 1] >= limit) return null;
        long address = numbers[n - 1];
        for (int i = 0; i < n - 1; i++) address += numbers[i] << (8 * (3 - i));
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "." + ((address >> 8) & 0xFF) + "." + (address & 0xFF);
    }

    /** Four decimal numbers 0-255 without leading zeros (the stored form). */
    @Nullable
    private static int[] ipv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || !allDigits(part) || (part.length() > 1 && part.charAt(0) == '0')) return null;
            int value = Integer.parseInt(part);
            if (value > 255) return null;
            octets[i] = value;
        }
        return octets;
    }

    private static boolean isPrivate(int[] ip) {
        int a = ip[0];
        int b = ip[1];
        int c = ip[2];
        return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 192 && b == 168) || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 0 && c == 2) || (a == 192 && b == 88 && c == 99) || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113) || (a == 100 && b >= 64 && b <= 127) || (a == 172 && b >= 16 && b <= 31)
                || (a == 198 && (b == 18 || b == 19)) || a >= 224;
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Rust's {@code str::trim}: Unicode White_Space at both ends. */
    private static String trimWhiteSpace(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && CloudText.isWhiteSpace(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (end > start && CloudText.isWhiteSpace(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }
}
