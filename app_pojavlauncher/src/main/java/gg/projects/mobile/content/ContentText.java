package gg.projects.mobile.content;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Text rules shared with the desktop's pl-content, which works on Rust strings: Unicode
 * whitespace ({@code char::is_whitespace}), lengths in Unicode scalar values, ordering by code
 * point (the byte order of UTF-8) and the links feed content may open.
 */
public final class ContentText {
    private ContentText() {}

    /** Rust's {@code char::is_whitespace}: the Unicode White_Space property. */
    public static boolean isWhitespace(int cp) {
        switch (cp) {
            case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D: case 0x20: case 0x85: case 0xA0:
            case 0x1680: case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000:
                return true;
            default:
                return cp >= 0x2000 && cp <= 0x200A;
        }
    }

    /** Rust's {@code char::is_control}: general category Cc. */
    public static boolean isControl(int cp) {
        return cp <= 0x1F || (cp >= 0x7F && cp <= 0x9F);
    }

    /** {@code text} without leading and trailing Unicode whitespace. */
    public static String trim(String text) {
        if (text == null) return "";
        int start = 0;
        int end = text.length();
        while (start < end) {
            int cp = text.codePointAt(start);
            if (!isWhitespace(cp)) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = text.codePointBefore(end);
            if (!isWhitespace(cp)) break;
            end -= Character.charCount(cp);
        }
        return text.substring(start, end);
    }

    public static boolean isBlank(String text) {
        return trim(text).isEmpty();
    }

    /** More than {@code max} Unicode scalar values. */
    public static boolean longerThan(String text, int max) {
        return text != null && text.codePointCount(0, text.length()) > max;
    }

    /** Orders like Rust's {@code str::cmp}: by code point, which is the byte order of UTF-8. */
    public static int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) return ca < cb ? -1 : 1;
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        boolean aLeft = i < a.length();
        boolean bLeft = j < b.length();
        return aLeft == bLeft ? 0 : (aLeft ? 1 : -1);
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isWhitespace(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Links feed content may open (pl-content's {@code is_safe_link}): {@code https://} URLs with a
     * host and no credentials, and {@code projects://} deep links. Everything else ({@code http://},
     * {@code mailto:}, {@code javascript:}, ...) is plain text. A few exotic forms the desktop's URL
     * parser repairs (spaces, non-ASCII hosts) are refused here, which is the safe direction.
     */
    public static boolean isSafeLink(String text) {
        if (text == null || text.isEmpty() || !text.equals(trim(text))) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isControl(cp)) return false;
            i += Character.charCount(cp);
        }
        if (text.startsWith("projects://")) {
            String rest = text.substring("projects://".length());
            return !rest.isEmpty() && !containsWhitespace(rest);
        }
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String userInfo = uri.getRawUserInfo();
            return scheme != null && scheme.equalsIgnoreCase("https")
                    && host != null && !host.isEmpty()
                    && (userInfo == null || userInfo.isEmpty());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** A safe link that is an {@code https://} URL (Discord message links, {@code source.url}). */
    public static boolean isHttpsLink(String text) {
        return isSafeLink(text) && text.startsWith("https://");
    }
}
