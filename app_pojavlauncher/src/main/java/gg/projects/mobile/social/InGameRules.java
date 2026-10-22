package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * FIX2 (item 8): what the game's own presence socket turns into a notification ({@link InGameSocial}),
 * pure so it is tested without a phone. The service already keeps a call quiet on do-not-disturb, with
 * crew calls off, for a crew the player muted and on the caller's own devices; it sends no invite live
 * on do-not-disturb and nothing across a block. The phone adds its own switches (the same ones its
 * pushes follow) and keeps friend requests quiet on do-not-disturb too.
 */
final class InGameRules {
    private InGameRules() {}

    private static final String DEFAULT_PORT = ":25565";

    static boolean announceRequest(boolean dnd, boolean wanted) {
        return wanted && !dnd;
    }

    static boolean announceInvite(boolean dnd, boolean wanted, boolean expired) {
        return wanted && !dnd && !expired;
    }

    /**
     * A call is news when this socket has not seen it ({@code seenWhere} null) or it moved to another
     * server ({@link #callWhere}); calls already open when the socket came are not news.
     */
    static boolean announceCall(@Nullable String seenWhere, String where, boolean quiet, boolean own, boolean dnd,
                                boolean wanted, boolean ended, long secondsLeft) {
        boolean news = seenWhere == null || !seenWhere.equals(where);
        return news && !quiet && !own && !dnd && wanted && !ended && secondsLeft > 0;
    }

    /** Where a call is, as one word for "it moved": {@code projects|}, {@code other|mc.example.net}, an old kind. */
    static String callWhere(SocialModel.Call call) {
        String where = call.serverWhere != null ? call.serverWhere : call.kind;
        return where + "|" + (call.serverHost == null ? "" : call.serverHost.toLowerCase(Locale.ROOT));
    }

    /**
     * The game is on that server now, so its "Bağlan" would only join it again: Projects by any of its
     * hosts, another server by its address (the default port or none is the same).
     */
    static boolean isOn(@Nullable GameActivity.Status status, boolean projects, @Nullable String address) {
        if (status == null || status.where != GameActivity.Status.Where.SERVER || status.host == null) return false;
        String on = normalized(status.host);
        if (projects) {
            String host = hostOf(on);
            return host.equals("projects.gg") || host.endsWith(".projects.gg");
        }
        return address != null && on.equals(normalized(address));
    }

    private static String normalized(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(DEFAULT_PORT)) a = a.substring(0, a.length() - DEFAULT_PORT.length());
        while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        return a;
    }

    private static String hostOf(String address) {
        int colon = address.lastIndexOf(':');
        String host = colon > 0 && address.indexOf(':') == colon ? address.substring(0, colon) : address;
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }
}
