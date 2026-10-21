package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Friend codes ({@code PRJ-XXXX-XXXX}) and crew codes ({@code EKP-XXXX-XXXX}), both Crockford base32,
 * crew invite links ({@code XXXX-XXXX-XXXX}, TEAM5), the share links and the deep links, as the
 * desktop's {@code pl-social} codes.rs: input is
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
        // TEAM5: an invite link's last 8 symbols look like a code; a text with a link is the link's.
        if (inviteLinkIn(text) != null) return null;
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

    // ---- crew invite links (TEAM5) ----

    private static final int LINK_LENGTH = 12;
    /** What the invite link's page opens: {@code projects://crew/<link>} ({@code ekip} is taken too). */
    public static final String CREW_DEEP_LINK_PREFIX = "projects://crew/";
    private static final String CREW_DEEP_LINK_ALIAS = "projects://ekip/";
    private static final Pattern LINK_IN_TEXT = Pattern.compile(
            "(?i)(?:/e/|crew/|ekip/)([0-9A-Z]{4}[\\s_-]?[0-9A-Z]{4}[\\s_-]?[0-9A-Z]{4})(?![0-9A-Z])");

    /**
     * A crew invite link as the service shows it, {@code XXXX-XXXX-XXXX} (12 symbols of the code
     * alphabet; case, spaces, hyphens and underscores ignored, {@code O} read as 0 and {@code I}/{@code L}
     * as 1, as the service reads it), or null. The length tells it from codes, which have 8.
     */
    @Nullable
    public static String normalizeInviteLink(@Nullable String input) {
        if (input == null || input.length() > 40) return null;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c) || c == '-' || c == '_') continue;
            text.append(Character.toUpperCase(c));
        }
        if (text.length() != LINK_LENGTH) return null;
        StringBuilder out = new StringBuilder(LINK_LENGTH + 2);
        for (int i = 0; i < LINK_LENGTH; i++) {
            if (i == 4 || i == 8) out.append('-');
            char c = text.charAt(i);
            if (c == 'O') c = '0';
            else if (c == 'I' || c == 'L') c = '1';
            if (c > 127 || ALPHABET.indexOf(c) < 0) return null;
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The invite link in what a player pasted or typed: the whole text when it is the 12 symbols, else
     * the link of a {@code <service>/e/<link>} address or a {@code projects://crew/<link>} link in it; null
     * when there is none (a crew code is never taken for a link: it has 8 symbols).
     */
    @Nullable
    public static String inviteLinkIn(@Nullable String text) {
        if (text == null) return null;
        String whole = normalizeInviteLink(text.trim());
        if (whole != null) return whole;
        String found = null;
        Matcher m = LINK_IN_TEXT.matcher(text);
        while (m.find()) {
            String link = normalizeInviteLink(m.group(1));
            if (link != null) found = link;
        }
        return found;
    }

    /** The link of {@code projects://crew/<link>} (or {@code projects://ekip/<link>}), normalised; null for anything else. */
    @Nullable
    public static String inviteLinkFromDeepLink(@Nullable String link) {
        if (link == null) return null;
        String prefix = link.regionMatches(true, 0, CREW_DEEP_LINK_PREFIX, 0, CREW_DEEP_LINK_PREFIX.length()) ? CREW_DEEP_LINK_PREFIX
                : link.regionMatches(true, 0, CREW_DEEP_LINK_ALIAS, 0, CREW_DEEP_LINK_ALIAS.length()) ? CREW_DEEP_LINK_ALIAS : null;
        if (prefix == null) return null;
        String rest = link.substring(prefix.length());
        int query = indexOfAny(rest, '?', '#');
        if (query >= 0) rest = rest.substring(0, query);
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        try {
            rest = java.net.URLDecoder.decode(rest, "UTF-8");
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
        return normalizeInviteLink(rest);
    }

    /** The invite link's address on this app's service: {@code <service>/e/XXXX-XXXX-XXXX}. */
    public static String inviteLinkUrl(String serviceBase, String link) {
        return (serviceBase.endsWith("/") ? serviceBase : serviceBase + "/") + "e/" + link;
    }

    /** The address as people read it, without its scheme: {@code social.projects.gg/e/XXXX-XXXX-XXXX}. */
    public static String inviteLinkShort(String serviceBase, String link) {
        String url = inviteLinkUrl(serviceBase, link);
        int scheme = url.indexOf("://");
        return scheme >= 0 ? url.substring(scheme + 3) : url;
    }

    private static int indexOfAny(String text, char a, char b) {
        int ia = text.indexOf(a);
        int ib = text.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }
}
