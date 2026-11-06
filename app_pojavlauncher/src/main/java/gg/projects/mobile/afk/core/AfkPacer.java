package gg.projects.mobile.afk.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Connection pacing per destination: pl-afk's {@code pacing.rs} in Java. Proxies count logins per address (Velocity
 * turns away a second login within 3 s, BungeeCord within 4 s); the phone's accounts share one address, so their
 * attempts to one server are kept {@link #MIN_CONNECT_SPACING_MS} apart and go in the order they asked. Pure Java,
 * engine thread.
 */
public final class AfkPacer {
    public static final long MIN_CONNECT_SPACING_MS = 4_500L;

    private final Map<String, Long> last = new HashMap<>();

    /**
     * The next slot for a connection to {@code destination} asked for at {@code now}: {@code now} when the latest slot
     * there is at least {@code spacing} earlier, else {@code spacing} after it. Slots stay taken.
     */
    public long reserve(String destination, long spacingMs, long now) {
        for (Iterator<Map.Entry<String, Long>> it = last.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() + spacingMs <= now) it.remove();
        }
        Long at = last.get(destination);
        long slot = at == null ? now : Math.max(at + spacingMs, now);
        last.put(destination, slot);
        return slot;
    }

    int size() {
        return last.size();
    }

    /** The destination of an account's connections: its host as dialled (lower case, no root dot); no port. */
    public static String destination(String host) {
        String h = host.trim();
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.toLowerCase(Locale.ROOT);
    }
}
