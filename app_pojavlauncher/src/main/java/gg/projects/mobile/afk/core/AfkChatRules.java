package gg.projects.mobile.afk.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which server lines the player should hear about (phone-afk.md §1.4: the server's bot checks are answered by the
 * player, never by the app): the account's name in someone's line, and private messages (vanilla's whisper, and the
 * {@code [Ahmet -> ben] ...} form chat plugins use). Pure Java.
 */
public final class AfkChatRules {
    private AfkChatRules() {}

    private static final String NAME = "([A-Za-z0-9_]{3,16})";
    private static final String TO_ME = "(?:ben|me|sen|you|sana|bana)";
    private static final String ARROW = "(?:->|→|»|>>|➜|➔)";
    private static final Pattern[] PRIVATE = {
            Pattern.compile("^\\s*\\[\\s*(?:[^\\]\\s]+\\s+)?" + NAME + "\\s*" + ARROW + "\\s*" + TO_ME + "\\s*\\]",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("^\\s*" + NAME + "\\s*" + ARROW + "\\s*" + TO_ME + "\\s*[:»>]",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("^\\s*" + NAME + " (?:whispers to you|sana fısıldıyor)\\s*:",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
    };

    /** The sender of a private message line, or null. */
    public static String privateFrom(String line) {
        if (line == null) return null;
        for (Pattern p : PRIVATE) {
            Matcher m = p.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static boolean nameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * {@code name} stands in {@code line} as a word of its own somewhere other than the speaker's place (the line's
     * start, or followed by {@code :}, {@code »}, {@code >}...): a join message or the account's own chat is no
     * mention.
     */
    public static boolean mentions(String line, String name) {
        if (line == null || name == null || name.isEmpty()) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        String needle = name.toLowerCase(Locale.ROOT);
        int from = 0;
        int at;
        while ((at = lower.indexOf(needle, from)) >= 0) {
            int end = at + needle.length();
            from = at + 1;
            boolean before = at == 0 || !nameChar(line.charAt(at - 1));
            boolean after = end >= line.length() || !nameChar(line.charAt(end));
            if (!before || !after) continue;
            if (speaker(line, at, end)) continue;
            return true;
        }
        return false;
    }

    private static boolean speaker(String line, int start, int end) {
        // At the line's start (after brackets and spaces): "SleepyFox_42 joined", "<SleepyFox_42> hi".
        boolean first = true;
        for (int i = 0; i < start; i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '<' && c != '[' && c != '(' && c != '*') {
                first = false;
                break;
            }
        }
        if (first) return true;
        int i = end;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ' || c == ']' || c == ')') {
                i++;
                continue;
            }
            return c == ':' || c == '»' || c == '>' || c == '|' || c == '›' || c == '→' || c == '-';
        }
        return false;
    }
}
