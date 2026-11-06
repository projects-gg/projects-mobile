package gg.projects.mobile.afk.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Secret masking of console lines: pl-afk's {@code console.rs} {@code LineFilter} in Java. A login command whose first
 * word is a login/register/password command ({@code /login}, {@code /l}, {@code /giriş}, ...) is a secret: wherever
 * the server echoes it, it becomes {@code /login ******}, and its argument words of four characters or more are masked
 * on their own. The player's own typed secrets join in ({@link #addUserCommand}). Every line is masked before it is
 * kept, shown or sent anywhere. Case folding is .NET's {@code ToUpperInvariant} per character, like the desktop's.
 */
public final class AfkLineFilter {
    public static final String MASK = "******";
    static final int MIN_MASKED_WORD_CHARS = 4;

    public enum Kind { NORMAL, SCRIPT, SENSITIVE }

    private static final String[] SENSITIVE_COMMANDS = {"/login", "/l", "/log", "/signin", "/g", "/gir", "/giris",
            "/giriş", "/girisyap", "/girişyap", "/register", "/reg", "/signup", "/k", "/kayit", "/kayıt", "/kaydol",
            "/password", "/pass", "/passwd", "/sifre", "/şifre", "/changepassword", "/changepass", "/cp", "/auth",
            "/authme", "/2fa", "/2fauth"};

    private static final class Secret {
        final String command;
        final String replacement;
        final List<String> words;

        Secret(String command, String replacement, List<String> words) {
            this.command = command;
            this.replacement = replacement;
            this.words = words;
        }
    }

    private final List<String> scriptCommands = new ArrayList<>();
    private final List<Boolean> scriptSensitive = new ArrayList<>();
    private final List<Secret> secrets = new ArrayList<>();

    public AfkLineFilter(List<String> loginCommands) {
        if (loginCommands == null) return;
        for (String raw : loginCommands) {
            String command = raw == null ? "" : raw.trim();
            if (command.isEmpty() || scriptCommands.contains(command)) continue;
            boolean sensitive = isSensitiveCommand(command);
            scriptCommands.add(command);
            scriptSensitive.add(sensitive);
            if (sensitive) addSecret(command);
        }
    }

    /** First word of a command (split at a space or tab). */
    static String firstToken(String command) {
        String t = command.trim();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ' || c == '\t') return t.substring(0, i);
        }
        return t;
    }

    /** The first word is a login/register/password command (exact, ignoring case: {@code /l} but not {@code /list}). */
    public static boolean isSensitiveCommand(String command) {
        String name = firstToken(command);
        if (name.isEmpty()) return false;
        for (String s : SENSITIVE_COMMANDS) {
            if (eqIgnoreCase(s, name)) return true;
        }
        return false;
    }

    /** Remembers a sensitive line the player typed so the server's echo of it is masked too. */
    public void addUserCommand(String line) {
        if (isSensitiveCommand(line)) addSecret(line);
    }

    private void addSecret(String raw) {
        String command = raw.trim();
        String head = firstToken(command);
        String args = command.substring(head.length()).trim();
        if (args.isEmpty()) return;
        for (Secret s : secrets) {
            if (s.command.equals(command)) return;
        }
        List<String> words = new ArrayList<>();
        for (String w : args.split("\\s+")) {
            if (!w.isEmpty() && w.codePointCount(0, w.length()) >= MIN_MASKED_WORD_CHARS) words.add(w);
        }
        secrets.add(new Secret(command, head + " " + MASK, words));
        // Longest first (in UTF-8 bytes, like the desktop's), so a command that contains another is replaced whole.
        java.util.Collections.sort(secrets, (a, b) -> Integer.compare(utf8Len(b.command), utf8Len(a.command)));
    }

    private static int utf8Len(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** The legacy classification of a raw (unmasked) line. */
    public Kind classify(String raw) {
        Kind kind = Kind.NORMAL;
        for (int i = 0; i < scriptCommands.size(); i++) {
            if (findIgnoreCase(raw, scriptCommands.get(i), 0) != null) {
                if (scriptSensitive.get(i)) return Kind.SENSITIVE;
                kind = Kind.SCRIPT;
            }
        }
        return kind;
    }

    /** Every known secret in {@code text} replaced. */
    public String mask(String text) {
        if (secrets.isEmpty() || text == null) return text;
        String out = text;
        for (Secret s : secrets) {
            int from = 0;
            int[] range;
            while ((range = findIgnoreCase(out, s.command, from)) != null) {
                out = out.substring(0, range[0]) + s.replacement + out.substring(range[1]);
                from = range[0] + s.replacement.length();
            }
        }
        for (Secret s : secrets) {
            for (String w : s.words) {
                if (out.contains(w)) out = out.replace(w, MASK);
            }
        }
        return out;
    }

    public boolean hasSecrets() {
        return !secrets.isEmpty();
    }

    /** .NET {@code char.ToUpperInvariant} per character (multi-character uppercase forms keep the character). */
    static int fold(int cp) {
        String upper = new String(Character.toChars(cp)).toUpperCase(java.util.Locale.ROOT);
        return upper.codePointCount(0, upper.length()) == 1 ? upper.codePointAt(0) : cp;
    }

    static boolean eqIgnoreCase(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (fold(ca) != fold(cb)) return false;
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return i == a.length() && j == b.length();
    }

    /** The {@code [start, end)} char range of the first case-insensitive {@code needle} at or after {@code from}. */
    static int[] findIgnoreCase(String haystack, String needle, int from) {
        if (needle.isEmpty()) return null;
        int[] wanted = new int[needle.codePointCount(0, needle.length())];
        for (int k = 0, i = 0; i < needle.length(); k++) {
            int cp = needle.codePointAt(i);
            wanted[k] = fold(cp);
            i += Character.charCount(cp);
        }
        int start = Math.max(0, from);
        while (start < haystack.length()) {
            int i = start;
            boolean ok = true;
            for (int w : wanted) {
                if (i >= haystack.length()) {
                    ok = false;
                    break;
                }
                int c = haystack.codePointAt(i);
                if (fold(c) != w) {
                    ok = false;
                    break;
                }
                i += Character.charCount(c);
            }
            if (ok) return new int[]{start, i};
            start += Character.charCount(haystack.codePointAt(start));
        }
        return null;
    }
}
