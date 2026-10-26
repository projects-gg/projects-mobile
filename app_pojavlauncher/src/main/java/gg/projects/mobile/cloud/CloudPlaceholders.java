package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholders in auto-replies (services/cloud-afk README "Rule placeholders"; vectors rule-placeholder-v1.json): exactly
 * {@code {u}} (the sender's name) and {@code {1}}…{@code {9}} (the pattern's capturing groups), only in a rule's plain
 * reply. The phone checks a reply before it saves (the same order the service answers {@code invalid_command} in) and
 * tries one on a sample message ("Dene"); the bot itself fills in only values of letters, digits and underscores.
 */
public final class CloudPlaceholders {
    private CloudPlaceholders() {}

    /** One placeholder at {@code at} in a text: {@code 0} for {@code {u}}, else its part number 1–9. */
    public static final class Token {
        public final int at;
        public final int part;

        Token(int at, int part) {
            this.at = at;
            this.part = part;
        }

        public boolean sender() {
            return part == 0;
        }
    }

    /** The placeholders of a text, left to right. Anything else with braces is plain text ({@code {0}}, {@code {10}}, {@code {U}}). */
    public static List<Token> tokens(@Nullable String text) {
        List<Token> out = new ArrayList<>();
        if (text == null) return out;
        for (int i = 0; i + 2 < text.length(); i++) {
            if (text.charAt(i) != '{' || text.charAt(i + 2) != '}') continue;
            char c = text.charAt(i + 1);
            if (c == 'u') out.add(new Token(i, 0));
            else if (c >= '1' && c <= '9') out.add(new Token(i, c - '0'));
            else continue;
            i += 2;
        }
        return out;
    }

    public static boolean holds(@Nullable String text) {
        return !tokens(text).isEmpty();
    }

    /** A save check's refusal: {@code reason} as the service names it, {@code part} for {@code placeholder_part}. */
    public static final class Problem {
        public final String reason;
        public final int part;

        Problem(String reason, int part) {
            this.reason = reason;
            this.part = part;
        }

        @Override
        public String toString() {
            return reason + (part > 0 ? "/" + part : "");
        }
    }

    /** The longest value a placeholder may take: what {@code placeholder_length} counts each one as. */
    public static final int VALUE_MAX = 24;
    private static final int TEXT_MAX = 256;

    /** A command row's text: no placeholders there ({@code placeholder_row}). */
    @Nullable
    public static Problem checkCommand(String text) {
        return holds(text) ? new Problem("placeholder_row", 0) : null;
    }

    /**
     * A rule's plain reply, after the text rule: null when it may be saved. {@code captures}: the pattern's capturing
     * groups (0 for plain text); {@code commands}: the names a reply with placeholders may start with.
     */
    @Nullable
    public static Problem checkReply(String text, int captures, String from, List<String> commands) {
        List<Token> tokens = tokens(text);
        if (tokens.isEmpty()) return null;
        for (Token t : tokens) if (t.part > captures) return new Problem("placeholder_part", t.part);
        if ("server".equals(from)) for (Token t : tokens) if (t.sender()) return new Problem("placeholder_sender", 0);
        String body = CloudText.withoutLeadingIgnorable(text);
        if (!tokens(body).isEmpty() && tokens(body).get(0).at == 0) return new Problem("placeholder_start", 0);
        if (body.startsWith("/")) {
            int space = body.indexOf(' ');
            String first = space < 0 ? body : body.substring(0, space);
            if (holds(first)) return new Problem("placeholder_command", 0);
            String name = asciiLower(first.substring(1));
            if (!commands.contains(name)) return new Problem("placeholder_command", 0);
        }
        if (text.length() + 21 * tokens.size() > TEXT_MAX) return new Problem("placeholder_length", 0);
        return null;
    }

    private static String asciiLower(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return out.toString();
    }

    /**
     * A pattern's capturing groups as Rust numbers them (pl-afk {@code capture_count}): every capturing group by its
     * opening parenthesis, named or not; none in a plain-text rule. Escapes and character classes (also nested ones, and
     * a {@code ]} first in a class) hold no groups.
     */
    public static int captureCount(String pattern, boolean regex) {
        if (!regex) return 0;
        int count = 0;
        int classDepth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (classDepth > 0) {
                if (c == '[') {
                    // "[:alpha:]" inside a class is a name, not a nested class.
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == ':') {
                        int end = pattern.indexOf(":]", i + 2);
                        if (end > 0) {
                            i = end + 1;
                            continue;
                        }
                    }
                    classDepth++;
                    i = skipClassStart(pattern, i);
                } else if (c == ']') {
                    classDepth--;
                }
                continue;
            }
            if (c == '[') {
                classDepth = 1;
                i = skipClassStart(pattern, i);
                continue;
            }
            if (c != '(') continue;
            if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
                String rest = pattern.substring(i + 2);
                if (rest.startsWith("P<") || (rest.startsWith("<") && !rest.startsWith("<=") && !rest.startsWith("<!"))) count++;
                continue;
            }
            count++;
        }
        return count;
    }

    /** Past a class's opening ({@code [}, {@code [^}) and a {@code ]} right after it, which is a literal there. */
    private static int skipClassStart(String pattern, int open) {
        int i = open + 1;
        if (i < pattern.length() && pattern.charAt(i) == '^') i++;
        if (i < pattern.length() && pattern.charAt(i) == ']') return i;
        return i - 1;
    }

    /**
     * The strict value class the bot fills in ({@code MCC_MATCH_VARS=strict}): 1–24 characters, each {@code _} or a letter
     * or number (Unicode L or N) of the Basic Multilingual Plane that is not Default_Ignorable_Code_Point.
     */
    public static boolean valueOk(@Nullable String value) {
        if (value == null) return false;
        int n = 0;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            n++;
            if (n > VALUE_MAX) return false;
            if (cp == '_') continue;
            if (cp > 0xFFFF || Character.isSurrogate((char) cp) || CloudText.isDefaultIgnorable(cp)) return false;
            switch (Character.getType(cp)) {
                case Character.UPPERCASE_LETTER:
                case Character.LOWERCASE_LETTER:
                case Character.TITLECASE_LETTER:
                case Character.MODIFIER_LETTER:
                case Character.OTHER_LETTER:
                case Character.DECIMAL_DIGIT_NUMBER:
                case Character.LETTER_NUMBER:
                case Character.OTHER_NUMBER:
                    break;
                default:
                    return false;
            }
        }
        return n >= 1;
    }

    /** The pattern in Java's regex syntax: Rust's {@code (?P<name>…)} as {@code (?<name>…)}; null when Java cannot read it. */
    @Nullable
    static Pattern javaPattern(String pattern) {
        try {
            return Pattern.compile(pattern.replace("(?P<", "(?<"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** What a rule does with one chat line: the reply it sends, or why it sends none. */
    public static final class Run {
        @Nullable
        public final String reply;
        /** With no reply: {@code from} (another kind of line), {@code self}, {@code nomatch} or {@code value}. */
        @Nullable
        public final String why;

        Run(@Nullable String reply, @Nullable String why) {
            this.reply = reply;
            this.why = why;
        }
    }

    /**
     * One rule against one chat line as the bot reads it ({@code type} public, private or other; {@code sender} null for
     * a line without one): whether it answers and with what. The bot's own lines get no answer.
     */
    public static Run run(String match, boolean regex, String from, String reply, String type, @Nullable String sender,
                          String message, @Nullable String self) {
        boolean kindOk;
        switch (from) {
            case "player": kindOk = "public".equals(type); break;
            case "private": kindOk = "private".equals(type); break;
            case "server": kindOk = "other".equals(type); break;
            default: kindOk = true; break;
        }
        if (!kindOk) return new Run(null, "from");
        if (sender != null && self != null && sender.equalsIgnoreCase(self)) return new Run(null, "self");
        String[] groups;
        if (regex) {
            Pattern p = javaPattern(match);
            if (p == null) return new Run(null, "nomatch");
            Matcher m = p.matcher(message);
            if (!m.find()) return new Run(null, "nomatch");
            groups = new String[m.groupCount() + 1];
            for (int i = 0; i <= m.groupCount(); i++) groups[i] = m.group(i);
        } else {
            if (!message.toLowerCase(Locale.ROOT).contains(match.trim().toLowerCase(Locale.ROOT))) return new Run(null, "nomatch");
            groups = new String[]{message};
        }
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (Token t : tokens(reply)) {
            String value = t.sender() ? sender : (t.part < groups.length ? groups[t.part] : null);
            if (!valueOk(value)) return new Run(null, "value");
            out.append(reply, last, t.at).append(value);
            last = t.at + 3;
        }
        out.append(reply.substring(last));
        return new Run(out.toString(), null);
    }

    /** "Dene": a sample message against the rule, {@code {u}} filled with {@code sender} (the account's owner, else "Oyuncu"). */
    public static Run tryIt(String match, boolean regex, String from, String reply, String sample, String sender) {
        // A pattern that cannot match anything is the thing to fix first ("pattern"), not a sample that does not match.
        if (match == null || match.trim().isEmpty() || (regex && javaPattern(match) == null)) return new Run(null, "pattern");
        String type = "private".equals(from) ? "private" : "server".equals(from) ? "other" : "public";
        return run(match, regex, "any", reply, type, "server".equals(from) ? null : sender, sample, null);
    }

    /** The chips under a reply: {@code {u}} unless the rule reads server lines, then one per capturing group. */
    public static List<String> chips(String match, boolean regex, String from) {
        List<String> out = new ArrayList<>();
        if (!"server".equals(from)) out.add("{u}");
        int groups = Math.min(9, captureCount(match, regex));
        for (int i = 1; i <= groups; i++) out.add("{" + i + "}");
        return out;
    }
}
