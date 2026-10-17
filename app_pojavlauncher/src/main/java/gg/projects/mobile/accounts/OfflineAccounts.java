package gg.projects.mobile.accounts;

import java.util.Collection;

/**
 * Offline ("local") accounts: the desktop launcher's name rules ({@code name-rules.ts} in
 * apps/launcher, {@code identity.rs} in pl-mc).
 *
 * <p>Since 0.1.3 an offline account needs no Microsoft account and no copy of the game (the owner's
 * decision of 17 October 2026, as on the desktop and in PojavLauncher before Amethyst's 2025
 * changes). Pure Java, JVM-tested.
 */
public final class OfflineAccounts {
    private OfflineAccounts() {}

    /** Minecraft's limits for player names, enforced for offline accounts too. */
    public static final int NAME_MIN = 3;
    public static final int NAME_MAX = 16;

    /** Why a name cannot be used, in the order the desktop checks. */
    public enum Problem { EMPTY, TOO_SHORT, TOO_LONG, TURKISH, SPACES, CHARACTERS }

    /** The result of {@link #check}. */
    public static final class Check {
        /** What would be saved: the input without surrounding white space. */
        public final String name;
        /** Why the name cannot be used, or null. */
        public final Problem problem;
        /** A valid name close to the input that no account has yet (Turkish letters spelled out, spaces as {@code _}), or null. */
        public final String suggestion;
        /** The account that already has this name, ignoring case: using the name switches to it. Null otherwise. */
        public final String existing;

        Check(String name, Problem problem, String suggestion, String existing) {
            this.name = name;
            this.problem = problem;
            this.suggestion = suggestion;
            this.existing = existing;
        }
    }

    private static final String TURKISH = "çÇğĞıİöÖşŞüÜ";
    private static final String TURKISH_SPELLED = "cCgGiIoOsSuU";

    /**
     * Checks a name typed for a new offline account. While the player is still typing
     * ({@code submitted} false) an empty or too short name is not a problem yet; on submit it is.
     * {@code accounts} are the names of the accounts already on the device, any kind.
     */
    public static Check check(String raw, Collection<String> accounts, boolean submitted) {
        String name = trim(raw);
        if (name.isEmpty()) return new Check(name, submitted ? Problem.EMPTY : null, null, null);

        Problem problem = null;
        if (containsAny(name, TURKISH)) problem = Problem.TURKISH;
        else if (containsSpace(name)) problem = Problem.SPACES;
        else if (!allAllowed(name)) problem = Problem.CHARACTERS;
        if (problem != null) {
            String suggestion = suggest(name);
            if (suggestion != null && findExisting(suggestion, accounts) != null) suggestion = null;
            return new Check(name, problem, suggestion, null);
        }
        if (name.length() > NAME_MAX) return new Check(name, Problem.TOO_LONG, null, null);
        if (name.length() < NAME_MIN) return new Check(name, submitted ? Problem.TOO_SHORT : null, null, null);
        return new Check(name, null, null, findExisting(name, accounts));
    }

    /** Whether {@code name} is a usable offline name: 3–16 characters of A–Z, a–z, 0–9 and _. */
    public static boolean isValidName(String name) {
        return name != null && name.length() >= NAME_MIN && name.length() <= NAME_MAX && allAllowed(name);
    }

    /** Turkish letters spelled out, white space as {@code _}, anything else dropped, cut to 16; null when unusable. */
    public static String suggest(String raw) {
        String trimmed = trim(raw);
        StringBuilder out = new StringBuilder(trimmed.length());
        boolean inSpace = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (isSpace(c)) {
                if (!inSpace) out.append('_');
                inSpace = true;
                continue;
            }
            inSpace = false;
            int turkish = TURKISH.indexOf(c);
            if (turkish >= 0) c = TURKISH_SPELLED.charAt(turkish);
            if (isAllowed(c)) out.append(c);
        }
        String cleaned = out.length() > NAME_MAX ? out.substring(0, NAME_MAX) : out.toString();
        return isValidName(cleaned) ? cleaned : null;
    }

    /**
     * The account in {@code accounts} with this name: the exact name first, else the first one equal
     * ignoring ASCII case (servers and their login plugins treat "Steve" and "steve" as one player).
     */
    public static String findExisting(String name, Collection<String> accounts) {
        if (name == null || accounts == null) return null;
        for (String account : accounts) {
            if (name.equals(account)) return account;
        }
        for (String account : accounts) {
            if (account != null && equalsIgnoreAsciiCase(account, name)) return account;
        }
        return null;
    }

    private static String trim(String raw) {
        if (raw == null) return "";
        int start = 0, end = raw.length();
        while (start < end && isSpace(raw.charAt(start))) start++;
        while (end > start && isSpace(raw.charAt(end - 1))) end--;
        return raw.substring(start, end);
    }

    /** White space as JavaScript's {@code \s} sees it (no-break spaces included), like the desktop. */
    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0xFEFF;
    }

    private static boolean containsSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isSpace(s.charAt(i))) return true;
        }
        return false;
    }

    private static boolean containsAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private static boolean isAllowed(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean allAllowed(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isAllowed(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean equalsIgnoreAsciiCase(String a, String b) {
        if (a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) {
            if (lowerAscii(a.charAt(i)) != lowerAscii(b.charAt(i))) return false;
        }
        return true;
    }

    private static char lowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }
}
