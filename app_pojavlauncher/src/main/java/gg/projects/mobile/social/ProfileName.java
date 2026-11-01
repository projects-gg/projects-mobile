package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * SOC-ID: the profile name a player picks (services/social src/profile-name.ts), checked on the phone before it is
 * sent so the name step can say what is wrong at once: 3 to 16 characters, Latin and Turkish letters, digits, spaces
 * and {@code _}, at least two of them letters or digits. The word list and the reserved words stay the service's: a
 * refused name comes back as its own answer ({@code name_rejected}: "Bu ad kullanılamıyor."). Pure Java.
 */
public final class ProfileName {
    private ProfileName() {}

    public static final int MIN = 3;
    public static final int MAX = 16;

    public enum Problem { NONE, EMPTY, TOO_SHORT, TOO_LONG, CHARACTERS, LETTERS }

    /** Ç Ğ İ Ö Ş Ü ç ğ ı ö ş ü, written as code points (the English interface keeps no Turkish letters). */
    private static final String TURKISH = new String(new char[]{(char) 0x00C7, (char) 0x011E, (char) 0x0130, (char) 0x00D6,
            (char) 0x015E, (char) 0x00DC, (char) 0x00E7, (char) 0x011F, (char) 0x0131, (char) 0x00F6, (char) 0x015F, (char) 0x00FC});
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9" + TURKISH + " _]+$");
    private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[A-Za-z0-9" + TURKISH + "]");

    /**
     * The name as the service keeps it: NFC, no control or format characters, every kind of space (a no-break space
     * too, as the service's Unicode {@code \s}) one space, trimmed.
     */
    public static String clean(@Nullable String value) {
        String text = CrewRules.clean(value);
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = out.length() > 0;
                continue;
            }
            if (space) out.append(' ');
            space = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /**
     * What is wrong with {@code raw}. {@code submitted}: the player pressed "Devam et" (an empty or short name says so
     * only then; a long one or a character the service refuses at once, as the offline name field does).
     */
    public static Problem check(@Nullable String raw, boolean submitted) {
        String name = clean(raw);
        int size = CrewRules.length(name);
        if (size > MAX) return Problem.TOO_LONG;
        if (size > 0 && !ALLOWED.matcher(name).matches()) return Problem.CHARACTERS;
        if (!submitted) return Problem.NONE;
        if (size == 0) return Problem.EMPTY;
        if (size < MIN) return Problem.TOO_SHORT;
        if (lettersOrDigits(name) < 2) return Problem.LETTERS;
        return Problem.NONE;
    }

    public static boolean valid(@Nullable String raw) {
        return check(raw, true) == Problem.NONE;
    }

    /**
     * The field's first value: the active Minecraft account's name when it passes the rules (a Minecraft name always
     * has the right letters, but "Demo.…" and names of mostly {@code _} do not), else empty.
     */
    public static String prefill(@Nullable String minecraftName) {
        String name = clean(minecraftName);
        return valid(name) ? name : "";
    }

    private static int lettersOrDigits(String text) {
        java.util.regex.Matcher m = LETTER_OR_DIGIT.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
