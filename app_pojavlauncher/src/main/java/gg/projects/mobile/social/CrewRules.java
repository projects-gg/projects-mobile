package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The service's rules for a crew's name, tag and motto (services/social src/names.ts), checked on
 * the phone before anything is sent so the form can say what is wrong at once. The word list stays
 * the service's: a rejected word comes back as its own message.
 */
public final class CrewRules {
    private CrewRules() {}

    public static final int NAME_MIN = 3;
    public static final int NAME_MAX = 24;
    public static final int TAG_MIN = 2;
    public static final int TAG_MAX = 4;
    public static final int MOTTO_MAX = 40;

    public enum Problem { NONE, NAME_SHORT, NAME_LONG, NAME_CHARS, TAG, MOTTO_LONG, MOTTO_CHARS, MOTTO_LETTERS }

    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M}\\p{N} '&!_-]+$");
    private static final Pattern MOTTO = Pattern.compile("^[\\p{L}\\p{M}\\p{N} '&!_,?-]+$");
    private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");
    /** Dotted capital I and dotless small i (written as code points: the English interface keeps no Turkish letters). */
    private static final char DOTTED_CAPITAL_I = (char) 0x0130;
    private static final char DOTLESS_SMALL_I = (char) 0x0131;
    /** A–Z, 0–9 and the Turkish capitals Ç Ğ İ Ö Ş Ü. */
    private static final Pattern TAG = Pattern.compile("^[A-Z0-9" + (char) 0x00C7 + (char) 0x011E + DOTTED_CAPITAL_I
            + (char) 0x00D6 + (char) 0x015E + (char) 0x00DC + "]+$");

    /** NFC, without control, format, private-use or separator characters, spaces collapsed, trimmed. */
    public static String clean(@Nullable String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(value, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.CONTROL) continue;
            if (type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static int lettersOrDigits(String text) {
        Matcher m = LETTER_OR_DIGIT.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    public static Problem name(@Nullable String value) {
        String name = clean(value);
        int size = length(name);
        if (size < NAME_MIN || lettersOrDigits(name) < 2) return Problem.NAME_SHORT;
        if (size > NAME_MAX) return Problem.NAME_LONG;
        if (!NAME.matcher(name).matches()) return Problem.NAME_CHARS;
        return Problem.NONE;
    }

    /** Upper case the Turkish way (i → İ, ı → I), whatever the phone's locale. */
    public static String turkishUpper(String text) {
        return text.replace('i', DOTTED_CAPITAL_I).replace(DOTLESS_SMALL_I, 'I').toUpperCase(new Locale("tr", "TR"));
    }

    /** The tag as the service keeps it: spaces out, upper case the Turkish way. */
    public static String tagOf(@Nullable String value) {
        return turkishUpper(clean(value).replace(" ", ""));
    }

    public static Problem tag(@Nullable String value) {
        String tag = tagOf(value);
        int size = length(tag);
        if (size < TAG_MIN || size > TAG_MAX || !TAG.matcher(tag).matches()) return Problem.TAG;
        return Problem.NONE;
    }

    /** An empty motto is no motto. */
    public static Problem motto(@Nullable String value) {
        String motto = clean(value);
        if (motto.isEmpty()) return Problem.NONE;
        if (length(motto) > MOTTO_MAX) return Problem.MOTTO_LONG;
        if (!MOTTO.matcher(motto).matches()) return Problem.MOTTO_CHARS;
        if (lettersOrDigits(motto) < 1) return Problem.MOTTO_LETTERS;
        return Problem.NONE;
    }
}
