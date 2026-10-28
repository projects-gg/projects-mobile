package gg.projects.mobile.cloud.plus;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gg.projects.mobile.social.ChatText;

/**
 * AFK+'s text rules a screen checks while the player types (services/cloud-afk test-vectors/afkplus-text.mjs
 * {@code checkPersona}, {@code checkPrefix}, {@code contactProblem}, {@code anyLink}): the persona texts ("Tanıtım",
 * the status) and the tag before the bot's lines. Cleaned as chat text first ({@link ChatText#clean}); the service and
 * the agent have the last word (the service alone knows its word list: a listed word only shows as its refusal).
 * Pure Java; the source stays ASCII (Turkish letters as code points).
 */
public final class PlusText {
    private PlusText() {}

    public static final int INTRO_MAX = 60;
    public static final int STATUS_MAX = 80;
    public static final int TAG_MAX = 16;

    private static final Pattern MARKER = Pattern.compile("\\[\\s*afk\\s*\\+\\s*\\]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COMMAND_START = Pattern.compile("^[/\\\\&" + (char) 0x00A7 + "]");
    private static final Pattern COLOUR_CODE = Pattern.compile("(?:" + (char) 0x00A7 + "|&[0-9a-fk-or])", Pattern.CASE_INSENSITIVE);
    private static final Pattern VARIABLE = Pattern.compile("%[\\p{L}\\p{N}_]+%");
    private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]@[\\p{L}\\p{N}-]{2,}");
    private static final Pattern IBAN_START = Pattern.compile("[a-z]{2}\\d{2}");
    private static final Pattern ELEVEN = Pattern.compile("\\d{11}");
    private static final Pattern CARD = Pattern.compile("\\d(?:[ -]?\\d){12,18}");
    private static final String TAG_MARKS = " []()+-_.!?*#|~^=:'";

    /** Other platforms and payment apps (folded, whole words; the long ones also with an ending glued on). */
    public static final List<String> PLATFORM_WORDS = Arrays.asList("discord", "dc", "instagram", "insta", "snapchat", "snap",
            "telegram", "whatsapp", "watsap", "wp", "tiktok", "twitch", "youtube", "steam", "papara", "ininal", "skype",
            "facebook", "messenger", "kik");
    /** Staff, system and server words no tag may carry: anywhere in its letters. */
    public static final List<String> STAFF_WORDS = Arrays.asList("yetkili", "admin", "moderator", "sistem", "system", "server",
            "sunucu", "konsol", "console", "staff", "kurucu", "founder", "owner", "destek", "support", "rehber", "helper",
            "duyuru", "bilgi", "info", "projects", "operator", "yonetic", "yonetim", "guvenlik", "security", "resmi", "official",
            "gelistirici", "developer");
    /** The short ones only as whole words. */
    public static final List<String> STAFF_SHORT = Arrays.asList("mod", "adm", "op", "gm", "sys", "dev");

    private static String clean(@Nullable String raw) {
        return ChatText.clean(raw == null ? "" : raw);
    }

    private static int cp(String text) {
        return text.codePointCount(0, text.length());
    }

    private static boolean formProblem(String cleaned) {
        return cleaned.indexOf('\n') >= 0 || COMMAND_START.matcher(cleaned).find() || COLOUR_CODE.matcher(cleaned).find()
                || VARIABLE.matcher(cleaned).find();
    }

    /** Whether the text shows anything (a letter, a digit, a mark, an emoji: not only spaces). */
    private static boolean showsSomething(String cleaned) {
        for (int i = 0; i < cleaned.length(); ) {
            int c = cleaned.codePointAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return true;
            i += Character.charCount(c);
        }
        return false;
    }

    /** The friends service's refusal of a chat text ({@code link}, {@code phone}, {@code words}), or null. */
    @Nullable
    private static String chatRejection(String cleaned) {
        switch (ChatText.check(cleaned)) {
            case LINK: return "link";
            case PHONE: return "phone";
            case WORDS: return "words";
            default: return null;
        }
    }

    /** Anything that reads as a link, our own domain included (AFK+ sends no link at all). */
    public static boolean anyLink(String cleaned) {
        return ChatText.looksLikeLink(ChatText.checkForm(cleaned).replace("projects", "projectz"));
    }

    /** {@code email}, {@code iban}, {@code tckn}, {@code card}, or null. */
    @Nullable
    public static String contactProblem(String cleaned) {
        String form = ChatText.checkForm(cleaned);
        if (EMAIL.matcher(form).find()) return "email";
        if (hasIban(form)) return "iban";
        if (hasTckn(form)) return "tckn";
        if (hasCard(form)) return "card";
        return null;
    }

    private static boolean ibanValid(String candidate) {
        String moved = (candidate.substring(4) + candidate.substring(0, 4)).toUpperCase(Locale.ROOT);
        long rest = 0;
        for (int i = 0; i < moved.length(); i++) {
            char ch = moved.charAt(i);
            int value = ch >= '0' && ch <= '9' ? ch - '0' : ch - 55;
            rest = Long.parseLong(rest + Integer.toString(value)) % 97;
        }
        return rest == 1;
    }

    private static boolean hasIban(String form) {
        Matcher m = IBAN_START.matcher(form);
        while (m.find()) {
            int at = m.start();
            if (at > 0 && Character.isLetterOrDigit(form.charAt(at - 1)) && form.charAt(at - 1) < 128) continue;
            StringBuilder compact = new StringBuilder();
            for (int i = at, taken = 0; i < form.length() && taken < 60; i++, taken++) {
                char ch = form.charAt(i);
                boolean alnum = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
                if (alnum) compact.append(ch);
                else if (ch == ' ' && i + 1 < form.length() && isAsciiAlnum(form.charAt(i + 1))) continue;
                else break;
            }
            String c = compact.length() > 34 ? compact.substring(0, 34) : compact.toString();
            for (int length = 15; length <= c.length(); length++) {
                if (ibanValid(c.substring(0, length))) return true;
            }
        }
        return false;
    }

    private static boolean isAsciiAlnum(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
    }

    private static boolean hasTckn(String form) {
        Matcher m = ELEVEN.matcher(form);
        while (m.find()) {
            if (m.start() > 0 && Character.isDigit(form.charAt(m.start() - 1))) continue;
            if (m.end() < form.length() && Character.isDigit(form.charAt(m.end()))) continue;
            String s = m.group();
            int[] d = new int[11];
            for (int i = 0; i < 11; i++) d[i] = s.charAt(i) - '0';
            if (d[0] == 0) continue;
            int odd = d[0] + d[2] + d[4] + d[6] + d[8];
            int even = d[1] + d[3] + d[5] + d[7];
            int tenth = (((odd * 7 - even) % 10) + 10) % 10;
            int sum = 0;
            for (int i = 0; i < 10; i++) sum += d[i];
            if (d[9] == tenth && d[10] == sum % 10) return true;
        }
        return false;
    }

    private static boolean hasCard(String form) {
        Matcher m = CARD.matcher(form);
        int from = 0;
        while (from < form.length() && m.find(from)) {
            int start = m.start();
            int end = m.end();
            from = start + 1;
            if (start > 0 && Character.isDigit(form.charAt(start - 1))) continue;
            if (end < form.length() && Character.isDigit(form.charAt(end))) continue;
            String digits = m.group().replace(" ", "").replace("-", "");
            int sum = 0;
            for (int i = 0; i < digits.length(); i++) {
                int digit = digits.charAt(digits.length() - 1 - i) - '0';
                if (i % 2 == 1) {
                    digit *= 2;
                    if (digit > 9) digit -= 9;
                }
                sum += digit;
            }
            if (sum % 10 == 0) return true;
        }
        return false;
    }

    /** Folds the Turkish letters to ASCII for word lists. */
    private static String fold(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case (char) 0x00E7: out.append('c'); break;
                case (char) 0x011F: out.append('g'); break;
                case (char) 0x0131: out.append('i'); break;
                case (char) 0x00F6: out.append('o'); break;
                case (char) 0x015F: out.append('s'); break;
                case (char) 0x00FC: out.append('u'); break;
                default: out.append(ch);
            }
        }
        return out.toString();
    }

    /** {@code platform} when the text names another platform or payment app, else null. */
    @Nullable
    public static String platformProblem(String cleaned) {
        String folded = fold(ChatText.checkForm(cleaned));
        for (String word : folded.split("[^a-z0-9]+")) {
            if (word.isEmpty()) continue;
            for (String name : PLATFORM_WORDS) {
                if (name.length() >= 5 ? word.startsWith(name) : word.equals(name)) return "platform";
            }
        }
        return null;
    }

    /**
     * A persona text ({@code intro} or {@code status}): null when it may be saved (empty is fine), else its reason:
     * {@code form}, {@code length}, {@code marker}, {@code link}, {@code phone}, {@code words}, {@code email},
     * {@code iban}, {@code tckn}, {@code card}, {@code platform}.
     */
    @Nullable
    public static String checkPersona(String field, @Nullable String raw) {
        String cleaned = clean(raw);
        if (cleaned.isEmpty()) return null;
        if (formProblem(cleaned) || !showsSomething(cleaned)) return "form";
        if (cp(cleaned) > ("status".equals(field) ? STATUS_MAX : INTRO_MAX)) return "length";
        String nfkc = java.text.Normalizer.normalize(cleaned, java.text.Normalizer.Form.NFKC);
        if (MARKER.matcher(nfkc).find()) return "marker";
        String rejection = chatRejection(cleaned);
        if (rejection != null) return rejection;
        if (anyLink(cleaned)) return "link";
        String contact = contactProblem(cleaned);
        if (contact != null) return contact;
        return platformProblem(cleaned);
    }

    /** Whether {@code cp} is a letter of the Latin script (ASCII, Latin-1, Latin Extended A/B and Additional). */
    private static boolean latinLetter(int cp) {
        if (!Character.isLetter(cp)) return false;
        return cp < 0x0250 || (cp >= 0x1E00 && cp <= 0x1EFF) || (cp >= 0x2C60 && cp <= 0x2C7F) || (cp >= 0xA720 && cp <= 0xA7FF);
    }

    private static boolean tagChars(String cleaned) {
        for (int i = 0; i < cleaned.length(); ) {
            int c = cleaned.codePointAt(i);
            boolean ok = (c >= '0' && c <= '9') || TAG_MARKS.indexOf(c) >= 0 || latinLetter(c);
            if (!ok) return false;
            i += Character.charCount(c);
        }
        return true;
    }

    /** The staff check's form: the chat filter's (lower case, marks dropped, look-alikes), dotless i and digits read as letters. */
    private static String staffForm(String text) {
        StringBuilder out = new StringBuilder();
        String form = ChatText.checkForm(text);
        for (int i = 0; i < form.length(); i++) {
            char ch = form.charAt(i);
            if (ch == (char) 0x0131) ch = 'i';
            switch (ch) {
                case '0': out.append('o'); break;
                case '1': out.append('i'); break;
                case '3': out.append('e'); break;
                case '4': out.append('a'); break;
                case '5': out.append('s'); break;
                case '7': out.append('t'); break;
                case '!': out.append('i'); break;
                case '|': out.append('i'); break;
                default: out.append(ch);
            }
        }
        return out.toString();
    }

    private static boolean staffProblem(String cleaned) {
        // Camel case split first ("ModBot" reads as "mod bot").
        StringBuilder split = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (i > 0 && Character.isUpperCase(ch) && Character.isLowerCase(cleaned.charAt(i - 1))) split.append(' ');
            split.append(ch);
        }
        String form = staffForm(split.toString());
        for (String word : form.split("[^a-z]+")) if (!word.isEmpty() && STAFF_SHORT.contains(word)) return true;
        String letters = form.replaceAll("[^a-z]+", "");
        for (String word : STAFF_WORDS) if (letters.contains(word)) return true;
        return false;
    }

    /**
     * The tag before the bot's lines: null when it may be saved, else its problem, the first that fails in this order:
     * {@code length} (empty), {@code form}, {@code length} (over 16), {@code link}, {@code phone}, {@code words}, {@code email}, {@code iban},
     * {@code tckn}, {@code card}, {@code platform}, {@code staff}, {@code name}. {@code names}: the friends and blocked
     * players the document knows (a tag naming one is refused).
     */
    @Nullable
    public static String checkPrefix(@Nullable String raw, List<String> names) {
        String cleaned = clean(raw);
        if (cleaned.isEmpty()) return "length";
        if (formProblem(cleaned) || !tagChars(cleaned) || !showsSomething(cleaned)) return "form";
        if (cp(cleaned) > TAG_MAX) return "length";
        String rejection = chatRejection(cleaned);
        if (rejection != null) return rejection;
        if (anyLink(cleaned)) return "link";
        String contact = contactProblem(cleaned);
        if (contact == null) contact = platformProblem(cleaned);
        if (contact != null) return contact;
        if (staffProblem(cleaned)) return "staff";
        Set<String> known = new HashSet<>();
        for (String n : names) known.add(n.toLowerCase(Locale.ROOT));
        for (String word : cleaned.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) if (!word.isEmpty() && known.contains(word)) return "name";
        return null;
    }
}
