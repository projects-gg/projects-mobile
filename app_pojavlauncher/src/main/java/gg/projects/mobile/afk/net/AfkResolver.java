package gg.projects.mobile.afk.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Turns the address the player wrote into the one to dial: the server's SRV record first when no port was written
 * ({@code _minecraft._tcp}), then its A/AAAA. Blocking; the wire calls it on its resolver thread. The phone's is
 * {@code AndroidResolver}; a plain JVM one ({@link #PLAIN}) skips SRV.
 */
public interface AfkResolver {
    final class Resolved {
        public final InetAddress address;
        /** The host the handshake names (the SRV target when there was one, like the game). */
        public final String host;
        public final int port;

        public Resolved(InetAddress address, String host, int port) {
            this.address = address;
            this.host = host;
            this.port = port;
        }
    }

    int DEFAULT_PORT = 25565;

    /** {@code port} 0: none written. */
    Resolved resolve(String host, int port) throws UnknownHostException;

    AfkResolver PLAIN = (host, port) -> new Resolved(InetAddress.getByName(host), host, port == 0 ? DEFAULT_PORT : port);

    /** An IPv4 or IPv6 literal (no lookup needed). */
    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') dots++;
            else if (c < '0' || c > '9') return false;
        }
        return dots == 3;
    }
}
