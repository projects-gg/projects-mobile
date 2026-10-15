package gg.projects.mobile.launch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Game arguments that join a server on start. Minecraft 1.20+ (23w14a) uses Quick Play
 * ({@code --quickPlayMultiplayer host:port}); older versions take {@code --server}/{@code --port}.
 */
public final class QuickPlay {
    private QuickPlay() {}

    /** The community server every Projects profile joins. */
    public static final String PROJECTS_SERVER = "play.projects.gg";
    public static final int DEFAULT_PORT = 25565;

    private static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");

    /** True for {@code host} or {@code host:port} with a DNS name or IPv4 address and a valid port. */
    public static boolean isValidAddress(String address) {
        if (address == null) return false;
        String host = address;
        int colon = address.lastIndexOf(':');
        if (colon >= 0) {
            host = address.substring(0, colon);
            if (parsePort(address.substring(colon + 1)) < 0) return false;
        }
        return HOST.matcher(host).matches();
    }

    private static int parsePort(String text) {
        if (text.isEmpty() || text.length() > 5) return -1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < '0' || text.charAt(i) > '9') return -1;
        int port = Integer.parseInt(text);
        return port >= 1 && port <= 65535 ? port : -1;
    }

    /** Whether the version's game arguments offer Quick Play (they mention quickPlayMultiplayer). */
    public static boolean supportsQuickPlay(Object[] gameArguments) {
        if (gameArguments == null) return false;
        for (Object argument : gameArguments) {
            if (argument != null && String.valueOf(argument).contains("quickPlayMultiplayer")) return true;
        }
        return false;
    }

    /**
     * Appends the arguments that join {@code address}. Arguments that already join a server
     * are returned unchanged, as are invalid addresses.
     */
    public static String[] withServer(String[] args, String address, boolean quickPlay) {
        if (!isValidAddress(address)) return args;
        for (String arg : args) {
            if ("--quickPlayMultiplayer".equals(arg) || "--server".equals(arg)
                    || "--quickPlaySingleplayer".equals(arg) || "--quickPlayRealms".equals(arg)) {
                return args;
            }
        }
        List<String> out = new ArrayList<>(Arrays.asList(args));
        if (quickPlay) {
            out.add("--quickPlayMultiplayer");
            out.add(address);
        } else {
            int colon = address.lastIndexOf(':');
            String host = colon >= 0 ? address.substring(0, colon) : address;
            String port = colon >= 0 ? address.substring(colon + 1) : String.valueOf(DEFAULT_PORT);
            out.add("--server");
            out.add(host);
            out.add("--port");
            out.add(port);
        }
        return out.toArray(new String[0]);
    }
}
