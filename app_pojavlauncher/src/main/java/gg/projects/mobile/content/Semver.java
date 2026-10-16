package gg.projects.mobile.content;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic version precedence for pack versions ({@code latest}), as the desktop orders them. */
final class Semver {
    private Semver() {}

    private static final String NUMBER = "0|[1-9][0-9]*";
    private static final String PRE_PART = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)";
    private static final Pattern PATTERN = Pattern.compile(
            "^(" + NUMBER + ")\\.(" + NUMBER + ")\\.(" + NUMBER + ")"
                    + "(?:-(" + PRE_PART + "(?:\\." + PRE_PART + ")*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    /**
     * Negative when {@code a} has lower precedence than {@code b}. Versions that are not valid
     * semver compare as equal (the desktop does the same), so the caller's order stays.
     */
    static int compare(String a, String b) {
        Matcher x = a == null ? null : PATTERN.matcher(a.trim());
        Matcher y = b == null ? null : PATTERN.matcher(b.trim());
        if (x == null || y == null || !x.matches() || !y.matches()) return 0;
        for (int group = 1; group <= 3; group++) {
            int byNumber = compareNumbers(x.group(group), y.group(group));
            if (byNumber != 0) return byNumber;
        }
        String preX = x.group(4);
        String preY = y.group(4);
        if (preX == null && preY == null) return 0;
        if (preX == null) return 1;
        if (preY == null) return -1;
        String[] partsX = preX.split("\\.");
        String[] partsY = preY.split("\\.");
        for (int i = 0; i < Math.min(partsX.length, partsY.length); i++) {
            int byPart = comparePart(partsX[i], partsY[i]);
            if (byPart != 0) return byPart;
        }
        return Integer.signum(partsX.length - partsY.length);
    }

    private static boolean numeric(String part) {
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return !part.isEmpty();
    }

    private static int comparePart(String x, String y) {
        boolean nx = numeric(x);
        boolean ny = numeric(y);
        if (nx && ny) return compareNumbers(x, y);
        if (nx) return -1;
        if (ny) return 1;
        return Integer.signum(x.compareTo(y));
    }

    /** Decimal numbers without leading zeros, of any length. */
    private static int compareNumbers(String x, String y) {
        if (x.length() != y.length()) return x.length() < y.length() ? -1 : 1;
        return Integer.signum(x.compareTo(y));
    }
}
