package gg.projects.mobile.content;

/**
 * Minimal glob matcher for config policy rules (mirror of pl-content's glob.rs):
 * whole path, case-sensitive; {@code *} = any characters except {@code /}; {@code **} = any
 * characters including {@code /} ({@code **}{@code /} also matches zero folders); {@code ?} = one
 * character except {@code /}; everything else matches itself.
 */
public final class Glob {
    private Glob() {}

    public static boolean matches(String pattern, String path) {
        return matches(pattern, 0, path, 0);
    }

    private static boolean matches(String pattern, int p, String path, int s) {
        if (p == pattern.length()) return s == path.length();
        char c = pattern.charAt(p);
        if (c == '*' && p + 1 < pattern.length() && pattern.charAt(p + 1) == '*') {
            int rest = p + 2;
            // "**/" may stand for zero folders: "config/**/x" matches "config/x".
            if (rest < pattern.length() && pattern.charAt(rest) == '/' && matches(pattern, rest + 1, path, s)) return true;
            for (int skip = s; skip <= path.length(); skip++) {
                if (matches(pattern, rest, path, skip)) return true;
            }
            return false;
        }
        if (c == '*') {
            for (int skip = s; skip <= path.length(); skip++) {
                if (matches(pattern, p + 1, path, skip)) return true;
                if (skip < path.length() && path.charAt(skip) == '/') break;
            }
            return false;
        }
        if (s >= path.length()) return false;
        if (c == '?') return path.charAt(s) != '/' && matches(pattern, p + 1, path, s + 1);
        return path.charAt(s) == c && matches(pattern, p + 1, path, s + 1);
    }
}
