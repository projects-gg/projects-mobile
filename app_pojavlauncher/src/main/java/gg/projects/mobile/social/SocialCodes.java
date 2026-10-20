package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Friend codes ({@code PRJ-XXXX-XXXX}) and crew codes ({@code EKP-XXXX-XXXX}), both Crockford base32,
 * the share link and the deep links, as the desktop's {@code pl-social} codes.rs: input is
 * case-insensitive, spaces, hyphens and underscores are ignored, {@code I}/{@code L} read as 1 and
 * {@code O} as 0. Crews show {@code EKP-}; the service sends {@code TKM-} and takes either prefix or
 * the 8 symbols alone, so the app sends the symbols alone.
 */
public final class SocialCodes {
    private SocialCodes() {}

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int LENGTH = 8;
    public static final String FRIEND_PREFIX = "PRJ";
    public static final String CREW_PREFIX = "EKP";
    private static final String OLD_CREW_PREFIX = "TKM";
    /** What the QR landing page opens: {@code projects://friend/<code>}. */
    public static final String FRIEND_DEEP_LINK_PREFIX = "projects://friend/";

    /** {@code PRJ-XXXX-XXXX}, or null when {@code input} cannot be a friend code. */
    @Nullable
    public static String normalizeFriendCode(@Nullable String input) {
        String symbols = symbols(input, FRIEND_PREFIX);
        return symbols == null ? null : FRIEND_PREFIX + "-" + symbols;
    }

    /** {@code XXXX-XXXX} (what every service version takes), or null when it cannot be a crew code. */
    @Nullable
    public static String normalizeCrewCode(@Nullable String input) {
        return symbols(input, CREW_PREFIX, OLD_CREW_PREFIX);
    }

    /** A crew code as players see it: {@code EKP-XXXX-XXXX}; anything else stays as it is. */
    public static String crewCode(@Nullable String code) {
        String symbols = normalizeCrewCode(code);
        return symbols == null ? (code == null ? "" : code) : CREW_PREFIX + "-" + symbols;
    }

    @Nullable
    private static String symbols(@Nullable String input, String... prefixes) {
        if (input == null || input.length() > 32) return null;
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c) || c == '-' || c == '_') continue;
            code.append(Character.toUpperCase(c));
        }
        String text = code.toString();
        for (String prefix : prefixes) {
            if (text.length() == prefix.length() + LENGTH && text.startsWith(prefix)) {
                text = text.substring(prefix.length());
                break;
            }
        }
        if (text.length() != LENGTH) return null;
        StringBuilder out = new StringBuilder(LENGTH + 1);
        for (int i = 0; i < LENGTH; i++) {
            if (i == 4) out.append('-');
            char c = text.charAt(i);
            if (c == 'O') c = '0';
            else if (c == 'I' || c == 'L') c = '1';
            if (c > 127 || ALPHABET.indexOf(c) < 0) return null;
            out.append(c);
        }
        return out.toString();
    }

    private static final Pattern FRIEND_IN_TEXT = Pattern.compile(
            "(?i)(?:PRJ[\\s_-]*)?([0-9A-Z]{4})[\\s_-]?([0-9A-Z]{4})(?![0-9A-Z])");
    private static final Pattern CREW_IN_TEXT = Pattern.compile(
            "(?i)(EKP|TKM|PRJ)?[\\s_-]*([0-9A-Z]{4})[\\s_-]?([0-9A-Z]{4})(?![0-9A-Z])");

    /**
     * The friend code in what a player pasted: the whole text when it is one, else the last full code
     * in it (a message like "kodum PRJ-ABCD-EFGH"); null when there is none.
     */
    @Nullable
    public static String friendCodeIn(@Nullable String text) {
        if (text == null) return null;
        String whole = normalizeFriendCode(text.trim());
        if (whole != null) return whole;
        String found = null;
        Matcher m = FRIEND_IN_TEXT.matcher(text);
        while (m.find()) {
            String code = normalizeFriendCode(m.group(1) + m.group(2));
            if (code != null) found = code;
        }
        return found;
    }

    /**
     * The crew code in what a player pasted (the desktop's rule: the last full code; a friend code,
     * {@code PRJ-}, is never taken for a crew code), as {@code XXXX-XXXX}; null when there is none.
     */
    @Nullable
    public static String crewCodeIn(@Nullable String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (!trimmed.toUpperCase(Locale.ROOT).replaceAll("[\\s_-]", "").startsWith(FRIEND_PREFIX)) {
            String whole = normalizeCrewCode(trimmed);
            if (whole != null) return whole;
        }
        String found = null;
        Matcher m = CREW_IN_TEXT.matcher(text);
        while (m.find()) {
            String prefix = m.group(1);
            if (prefix != null && prefix.equalsIgnoreCase(FRIEND_PREFIX)) continue;
            String code = normalizeCrewCode(m.group(2) + m.group(3));
            if (code != null) found = code;
        }
        return found;
    }

    /** The friend code of {@code projects://friend/<code>}, normalised; null for anything else. */
    @Nullable
    public static String friendCodeFromDeepLink(@Nullable String link) {
        if (link == null || !link.regionMatches(true, 0, FRIEND_DEEP_LINK_PREFIX, 0, FRIEND_DEEP_LINK_PREFIX.length())) {
            return null;
        }
        String rest = link.substring(FRIEND_DEEP_LINK_PREFIX.length());
        int query = indexOfAny(rest, '?', '#');
        if (query >= 0) rest = rest.substring(0, query);
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        return normalizeFriendCode(rest);
    }

    private static final Pattern AUTH_CODE = Pattern.compile("[A-Za-z0-9_-]{43}");

    /**
     * The one-time code of {@code projects://auth/done?c=<code>} (43 base64url characters, 32 bytes);
     * null for any other link.
     */
    @Nullable
    public static String authCodeFromDeepLink(@Nullable String link) {
        String prefix = "projects://auth/done";
        if (link == null || !link.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        String rest = link.substring(prefix.length());
        if (rest.startsWith("/")) rest = rest.substring(1);
        if (!rest.startsWith("?")) return null;
        for (String pair : rest.substring(1).split("&")) {
            if (pair.startsWith("c=")) {
                String value = pair.substring(2);
                int hash = value.indexOf('#');
                if (hash >= 0) value = value.substring(0, hash);
                return AUTH_CODE.matcher(value).matches() ? value : null;
            }
        }
        return null;
    }

    /** The share link the QR carries: {@code <service>/f/PRJ-XXXX-XXXX}. */
    @Nullable
    public static String friendShareLink(String serviceBase, @Nullable String code) {
        String normalized = normalizeFriendCode(code);
        if (normalized == null) return null;
        return (serviceBase.endsWith("/") ? serviceBase : serviceBase + "/") + "f/" + normalized;
    }

    private static int indexOfAny(String text, char a, char b) {
        int ia = text.indexOf(a);
        int ib = text.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }
}
