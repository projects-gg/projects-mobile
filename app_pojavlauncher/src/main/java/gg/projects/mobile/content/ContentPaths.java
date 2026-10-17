package gg.projects.mobile.content;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Path safety for paths that come from remote content (mirror of pl-content's path.rs).
 * Content is signed, but a bug or a leaked key must still never let it write outside the game
 * directory or into launcher-owned folders.
 */
public final class ContentPaths {
    private ContentPaths() {}

    public static final int MAX_RELATIVE_PATH = 512;
    /** Folder inside a game directory that belongs to the launcher (pack state). */
    public static final String LAUNCHER_DIR = ".projects";

    /** Validates a {@code /}-separated relative path from a pack manifest. */
    public static void checkRelativePath(String path) throws ContentException {
        if (path == null || path.isEmpty()) throw unsafe(path, "empty path");
        if (path.getBytes(StandardCharsets.UTF_8).length > MAX_RELATIVE_PATH) throw unsafe(path, "path is too long");
        if (path.startsWith("/")) throw unsafe(path, "absolute path");
        if (path.indexOf('\\') >= 0) throw unsafe(path, "backslash separator");
        if (path.indexOf(':') >= 0) throw unsafe(path, "drive letter or stream separator");
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) throw unsafe(path, "control character");
        }
        String[] components = path.split("/", -1);
        for (int index = 0; index < components.length; index++) {
            String component = components[index];
            if (component.isEmpty()) throw unsafe(path, "empty path component");
            if (component.equals(".") || component.equals("..")) throw unsafe(path, "relative path component");
            if (component.endsWith(".") || component.endsWith(" ")) throw unsafe(path, "component ends with a dot or space");
            for (int i = 0; i < component.length(); i++) {
                char c = component.charAt(i);
                if (c == '<' || c == '>' || c == '"' || c == '|' || c == '?' || c == '*') {
                    throw unsafe(path, "character not allowed on Windows");
                }
            }
            if (isWindowsDeviceName(component)) throw unsafe(path, "reserved device name");
            if (index == 0 && component.equalsIgnoreCase(LAUNCHER_DIR)) throw unsafe(path, "launcher-owned folder");
        }
    }

    /** Validates a path relative to a mirror base: {@link #checkRelativePath} plus {@code [A-Za-z0-9._-/]}. */
    public static void checkMirrorPath(String path) throws ContentException {
        checkRelativePath(path);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '/';
            if (!ok) throw unsafe(path, "character not allowed in a mirror path");
        }
    }

    public static boolean isMirrorPath(String path) {
        try {
            checkMirrorPath(path);
            return true;
        } catch (ContentException e) {
            return false;
        }
    }

    /** True for absolute {@code http://} or {@code https://} URLs with a host. */
    public static boolean isHttpUrl(String text) {
        if (text == null) return false;
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** True for absolute {@code https://} URLs with a host (what the app opens in the browser). */
    public static boolean isHttpsUrl(String text) {
        return isHttpUrl(text) && text.regionMatches(true, 0, "https:", 0, 6);
    }

    /**
     * The address to hand to the browser: an {@link #isHttpsUrl https URL} with its scheme in lower
     * case (Android matches intent schemes case-sensitively, so "HTTPS://" would find no browser),
     * otherwise null. Nothing else is ever opened.
     */
    public static String browserUrl(String text) {
        return isHttpsUrl(text) ? "https" + text.substring(5) : null;
    }

    static boolean isWindowsDeviceName(String component) {
        int dot = component.indexOf('.');
        String stem = dot >= 0 ? component.substring(0, dot) : component;
        int end = stem.length();
        while (end > 0 && Character.isWhitespace(stem.charAt(end - 1))) end--;
        String upper = stem.substring(0, end).toUpperCase(Locale.ROOT);
        switch (upper) {
            case "CON": case "PRN": case "AUX": case "NUL": case "CONIN$": case "CONOUT$":
                return true;
            default:
                return upper.length() == 4
                        && (upper.startsWith("COM") || upper.startsWith("LPT"))
                        && upper.charAt(3) >= '0' && upper.charAt(3) <= '9';
        }
    }

    private static ContentException unsafe(String path, String reason) {
        return new ContentException(ContentException.Kind.INVALID, "unsafe path " + quote(path) + ": " + reason);
    }

    private static String quote(String path) {
        return path == null ? "null" : "\"" + path + "\"";
    }
}
