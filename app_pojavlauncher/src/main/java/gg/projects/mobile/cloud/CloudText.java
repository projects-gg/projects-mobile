package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

/**
 * Command lines, auto-replies and console lines of 7/24 Bulut (services/cloud-afk README "Text"; the
 * launcher's {@code pl-cloud-afk} {@code text.rs}, which this follows rule for rule): what the service
 * accepts, which lines carry a password (they are sealed on the phone, never sent in plain text), and
 * what a secret row shows instead of its words. Shared cases: {@code text-rule-v1.json}.
 */
public final class CloudText {
    private CloudText() {}

    /** The longest text in UTF-16 units (Minecraft's own chat limit). */
    public static final int MAX_UNITS = 256;

    /** Why a text cannot be sent ({@code invalid_command}'s {@code reason}). */
    public enum Problem {
        EMPTY("empty"), LENGTH("length"), UNICODE("unicode"), SPACE("space"), CONTROL("control"),
        BACKSLASH("backslash"), VARIABLE("variable");

        public final String code;

        Problem(String code) {
            this.code = code;
        }

        @Nullable
        public static Problem of(@Nullable String code) {
            for (Problem p : values()) if (p.code.equals(code)) return p;
            return null;
        }
    }

    private static final String[] VARIABLES = {"username", "serverip", "serverport", "datetime"};

    /** pl-afk's {@code sensitive_command_names()} (the contract's list). */
    static final String[] SENSITIVE = {
            "/login", "/l", "/log", "/signin", "/g", "/gir", "/giris", "/giriş", "/girisyap", "/girişyap", "/register",
            "/reg", "/signup", "/k", "/kayit", "/kayıt", "/kaydol", "/password", "/pass", "/passwd", "/sifre", "/şifre",
            "/changepassword", "/changepass", "/cp", "/auth", "/authme", "/2fa", "/2fauth",
    };

    /** Default_Ignorable_Code_Point (Unicode 16.0) as sorted ranges: JavaScript's {@code \p{Default_Ignorable_Code_Point}}. */
    private static final int[][] DEFAULT_IGNORABLE = {
            {0x00AD, 0x00AD}, {0x034F, 0x034F}, {0x061C, 0x061C}, {0x115F, 0x1160}, {0x17B4, 0x17B5}, {0x180B, 0x180F},
            {0x200B, 0x200F}, {0x202A, 0x202E}, {0x2060, 0x206F}, {0x3164, 0x3164}, {0xFE00, 0xFE0F}, {0xFEFF, 0xFEFF},
            {0xFFA0, 0xFFA0}, {0xFFF0, 0xFFF8}, {0x1BCA0, 0x1BCA3}, {0x1D173, 0x1D17A}, {0xE0000, 0xE0FFF},
    };

    /**
     * Unicode's White_Space property (Rust's {@code char::is_whitespace}); Java's own
     * {@code Character.isWhitespace} leaves out the no-break spaces and takes some separators in.
     */
    static boolean isWhiteSpace(int c) {
        return (c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    static boolean isDefaultIgnorable(int c) {
        int lo = 0;
        int hi = DEFAULT_IGNORABLE.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int[] range = DEFAULT_IGNORABLE[mid];
            if (range[1] < c) lo = mid + 1;
            else if (range[0] > c) hi = mid - 1;
            else return true;
        }
        return false;
    }

    /** White_Space or Default_Ignorable_Code_Point: what the client trims from a line's start before it reads it. */
    public static boolean isLeadingIgnorable(int c) {
        return isWhiteSpace(c) || isDefaultIgnorable(c);
    }

    /** The text without its leading {@link #isLeadingIgnorable} characters (the contract's rule 4). */
    public static String withoutLeadingIgnorable(String text) {
        int i = 0;
        while (i < text.length()) {
            int c = text.codePointAt(i);
            if (!isLeadingIgnorable(c)) break;
            i += Character.charCount(c);
        }
        return text.substring(i);
    }

    private static boolean isControl(int c) {
        return c <= 0x1F || (c >= 0x7F && c <= 0x9F);
    }

    /** The contract's text rule; null when the text may be sent. A Java string is UTF-16: a lone surrogate is {@code unicode}. */
    @Nullable
    public static Problem check(@Nullable String text) {
        if (text == null || text.isEmpty()) return Problem.EMPTY;
        if (text.length() > MAX_UNITS) return Problem.LENGTH;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) return Problem.UNICODE;
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return Problem.UNICODE;
            }
        }
        if (text.charAt(0) == ' ' || text.charAt(text.length() - 1) == ' ') return Problem.SPACE;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (isControl(c) || c == 0x2028 || c == 0x2029) return Problem.CONTROL;
            i += Character.charCount(c);
        }
        if (withoutLeadingIgnorable(text).startsWith("\\")) return Problem.BACKSLASH;
        if (!variablesAllowed(text)) return Problem.VARIABLE;
        return null;
    }

    /** The client's own variable scan: at a {@code %}, the longest run of name characters followed by another {@code %} names a variable. */
    private static boolean variablesAllowed(String text) {
        // No String.codePoints(): it needs Android 7 (the app runs from Android 5).
        int[] cps = new int[text.codePointCount(0, text.length())];
        for (int at = 0, n = 0; at < text.length(); n++) {
            cps[n] = text.codePointAt(at);
            at += Character.charCount(cps[n]);
        }
        int i = 0;
        while (i < cps.length) {
            if (cps[i] != '%') {
                i++;
                continue;
            }
            int start = i + 1;
            int end = start;
            while (end < cps.length && isNameChar(cps[end])) end++;
            if (end > start && end < cps.length && cps[end] == '%') {
                String name = new String(cps, start, end - start);
                boolean known = false;
                for (String v : VARIABLES) {
                    if (v.equalsIgnoreCase(name) && asciiOnly(name)) {
                        known = true;
                        break;
                    }
                }
                if (!known) return false;
                i = end + 1;
            } else {
                i++;
            }
        }
        return true;
    }

    private static boolean asciiOnly(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return false;
        return true;
    }

    private static boolean isNameChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c > 0x7F;
    }

    /** JavaScript's {@code trim()}, as the service's check does it: White_Space and U+FEFF. */
    private static String jsTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end) {
            int c = text.codePointAt(start);
            if (!isWhiteSpace(c) && c != 0xFEFF) break;
            start += Character.charCount(c);
        }
        while (end > start) {
            int c = text.codePointBefore(end);
            if (!isWhiteSpace(c) && c != 0xFEFF) break;
            end -= Character.charCount(c);
        }
        return text.substring(start, end);
    }

    /** The first word (up to a space or a tab), after the leading characters a client trims. */
    static String firstWord(String text) {
        String trimmed = jsTrim(withoutLeadingIgnorable(text));
        int cut = trimmed.length();
        int space = trimmed.indexOf(' ');
        int tab = trimmed.indexOf('\t');
        if (space >= 0) cut = Math.min(cut, space);
        if (tab >= 0) cut = Math.min(cut, tab);
        return trimmed.substring(0, cut);
    }

    /** Each character's simple uppercase form (not the Turkish one); one whose uppercase is longer stays as it is. */
    private static String foldUpper(String word) {
        StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); ) {
            int c = word.codePointAt(i);
            String full = new String(Character.toChars(c)).toUpperCase(java.util.Locale.ROOT);
            out.appendCodePoint(full.codePointCount(0, full.length()) == 1 ? full.codePointAt(0) : c);
            i += Character.charCount(c);
        }
        return out.toString();
    }

    /** Lowercase as a Turkish reader writes it (I → ı, İ → i). */
    private static String turkishLower(String word) {
        StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); ) {
            int c = word.codePointAt(i);
            if (c == 'I') out.append('ı');
            else if (c == 'İ') out.append('i');
            else {
                String full = new String(Character.toChars(c)).toLowerCase(java.util.Locale.ROOT);
                out.appendCodePoint(full.codePointCount(0, full.length()) == 1 ? full.codePointAt(0) : c);
            }
            i += Character.charCount(c);
        }
        return out.toString();
    }

    /** The contract's rule: the first word is a password command (each character folded to its simple uppercase). */
    public static boolean isSensitive(String text) {
        String name = firstWord(text);
        if (name.isEmpty()) return false;
        String folded = foldUpper(name);
        for (String s : SENSITIVE) if (foldUpper(s).equals(folded)) return true;
        return false;
    }

    /** What the phone seals by itself: the contract's rule, and what a Turkish keyboard types in capitals ({@code /GİRİŞ}). */
    public static boolean looksSensitive(String text) {
        if (isSensitive(text)) return true;
        String name = firstWord(text);
        if (name.isEmpty()) return false;
        String lower = turkishLower(name);
        for (String s : SENSITIVE) if (turkishLower(s).equals(lower)) return true;
        return false;
    }

    /**
     * What a secret row shows instead of its words: the command and {@code ******} ({@code /login ******}); a secret
     * that is not a command shows only the stars (its first word may be the secret itself).
     */
    public static String preview(String text) {
        String name = firstWord(text);
        boolean plainWord = true;
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            if (isWhiteSpace(c) || c == 0xFEFF) {
                plainWord = false;
                break;
            }
            i += Character.charCount(c);
        }
        if (name.startsWith("/") && name.length() <= 32 && plainWord) return name + " ******";
        return "******";
    }
}
