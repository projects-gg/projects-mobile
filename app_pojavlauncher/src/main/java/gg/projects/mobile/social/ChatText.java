package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of a chat message (CHAT1, CHAT2; services/social src/chattext.ts, the launcher's
 * chat/text.ts): cleaned like the service cleans it, then checked for what the service turns away,
 * so the composer can say why before anything is sent: a link (while links are on, one the rules of
 * {@link ChatLinks} refuse, or an address that is no plain link), a phone number, the middle finger.
 * The service checks anyway, and alone knows its word list; a difference only means it answers
 * {@code chat_rejected} instead. Symbols that spell letters, digits and dots read as what they
 * spell. The service's cases pass here too (ChatLinksTest). Pure Java.
 *
 * <p>Characters outside ASCII are written as code points: the source stays ASCII (the English
 * interface's test reads every literal for Turkish letters).
 */
public final class ChatText {
    private ChatText() {}

    public static final int MAX = 300;
    public static final int MAX_LINES = 5;
    /** The counter shows from here on. */
    public static final int COUNTER_FROM = 250;

    /** Why a text cannot go as it is. WORDS is only the middle finger: the word list stays the service's. */
    public enum Problem { NONE, EMPTY, TOO_LONG, LINK, PHONE, WORDS }

    /** Why a text cannot go, with the link rule that refuses it. */
    public static final class Verdict {
        public final Problem problem;
        /** Only with LINK: OFF while links are off, FORM for an address that is no plain link. */
        @Nullable public final ChatLinks.Rule rule;

        /** Also for a refusal the service sends ({@code chat_rejected} with its reason and rule). */
        public Verdict(Problem problem, @Nullable ChatLinks.Rule rule) {
            this.problem = problem;
            this.rule = rule;
        }

        @Override
        public String toString() {
            return rule == null ? problem.name() : problem.name() + " " + rule.wire();
        }
    }

    private static final int ZWJ = 0x200D;
    private static final char DOTLESS_SMALL_I = (char) 0x0131;
    private static final char DOTTED_CAPITAL_I = (char) 0x0130;
    /** The word list's picture, the one listed word the app knows. */
    private static final String MIDDLE_FINGER = new String(Character.toChars(0x1F595));

    /**
     * The text as the service keeps it: NFC; invisible characters (control, format, private use and
     * blank-looking letters) dropped, the zero width joiner only where it joins two emoji; variation
     * selectors other than VS15 and VS16 dropped and those two at most one in a row; at most two
     * marks in a row; spaces collapsed; empty lines dropped; at most 5 lines (the rest joined into the
     * fifth); trimmed.
     */
    public static String clean(@Nullable String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(value, Normalizer.Form.NFC);
        int[] cps = new int[text.length()];
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') {
                if (i < text.length() && text.charAt(i) == '\n') i++;
                cp = '\n';
            } else if (cp == 0x0085 || cp == 0x2028 || cp == 0x2029) {
                cp = '\n';
            } else if (cp == '\t' || cp == 0x000B || cp == '\f') {
                cp = ' ';
            }
            if (cp != '\n' && cp != ZWJ && invisible(cp)) continue;
            // VS1 to VS14 and VS17 to VS256 are invisible data nobody needs in chat.
            if ((cp >= 0xFE00 && cp <= 0xFE0D) || (cp >= 0xE0100 && cp <= 0xE01EF)) continue;
            if (styleSelector(cp) && n > 0 && styleSelector(cps[n - 1])) continue;
            cps[n++] = cp;
        }
        StringBuilder kept = new StringBuilder(text.length());
        int marks = 0;
        for (int i = 0; i < n; i++) {
            int cp = cps[i];
            if (cp == ZWJ && !joinsEmoji(cps, n, i)) continue;
            if (mark(cp)) {
                if (++marks > 2) continue;
            } else {
                marks = 0;
            }
            kept.appendCodePoint(cp);
        }
        List<String> lines = new ArrayList<>();
        for (String raw : kept.toString().split("\n", -1)) {
            String line = collapsed(raw);
            if (!line.isEmpty()) lines.add(line);
        }
        StringBuilder out = new StringBuilder(kept.length());
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(i < MAX_LINES ? '\n' : ' ');
            out.append(lines.get(i));
        }
        return out.toString();
    }

    /** VS15 or VS16: text or emoji style, which stays. */
    private static boolean styleSelector(int cp) {
        return cp == 0xFE0E || cp == 0xFE0F;
    }

    /** Whether the joiner at {@code at} joins two emoji (VS16 and skin tones may sit between the first one and it). */
    private static boolean joinsEmoji(int[] cps, int n, int at) {
        int before = at - 1;
        while (before >= 0 && (cps[before] == 0xFE0F || (cps[before] >= 0x1F3FB && cps[before] <= 0x1F3FF))) before--;
        return before >= 0 && at + 1 < n && EmojiRanges.pictographic(cps[before])
                && EmojiRanges.pictographic(cps[at + 1]);
    }

    /** A line with its white space collapsed to single spaces and trimmed. */
    private static String collapsed(String line) {
        StringBuilder out = new StringBuilder(line.length());
        boolean gap = false;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            i += Character.charCount(cp);
            if (space(cp)) {
                gap = true;
                continue;
            }
            if (gap && out.length() > 0) out.append(' ');
            gap = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** Control, format, private-use and surrogate characters, and the letters that show as blanks. */
    private static boolean invisible(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                || type == Character.SURROGATE || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0
                || cp == 0x2800;
    }

    /** JavaScript's white space (its \s), the one the service collapses: the JVM's \s is ASCII only. */
    private static boolean space(int cp) {
        return cp == ' ' || (cp >= 0x09 && cp <= 0x0D) || cp == 0x00A0 || cp == 0x1680
                || (cp >= 0x2000 && cp <= 0x200A) || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F
                || cp == 0x3000 || cp == 0xFEFF;
    }

    /** A combining mark (accents, variation selectors, the keycap). */
    static boolean mark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /** Whether a cleaned text shows something: not only joiners, spaces and marks. */
    private static boolean shows(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp != ZWJ && !space(cp) && !mark(cp)) return true;
        }
        return false;
    }

    /** Unicode code points, as the service counts the 300. */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * Why a text the player is about to send cannot go (cleaned first), as the service answers it
     * (its word list aside). {@code lists}: Studio's lists while links are on ({@code hello.chat.links});
     * null while they are off or the service is older, and then anything that reads as a link is LINK
     * with {@link ChatLinks.Rule#OFF}, as it is without the rule table. With links on, the plain links
     * go by the rules first, the rest of the text (links blanked out) by the CHAT1 checks.
     */
    public static Verdict verdict(@Nullable String typed, @Nullable ChatLinks.Lists lists) {
        String text = clean(typed);
        if (!shows(text)) return new Verdict(Problem.EMPTY, null);
        if (length(text) > MAX) return new Verdict(Problem.TOO_LONG, null);
        ChatLinks.Check links = lists == null ? null : ChatLinks.check(text, lists);
        if (links == null) {
            String form = checkForm(text);
            if (looksLikeLink(form)) return new Verdict(Problem.LINK, ChatLinks.Rule.OFF);
            if (looksLikePhone(form)) return new Verdict(Problem.PHONE, null);
        } else {
            if (links.phone) return new Verdict(Problem.PHONE, null);
            if (links.rule != null) return new Verdict(Problem.LINK, links.rule);
            // Anything else that reads as an address (a bare domain, a hidden dot, an IP) still never goes.
            String rest = checkForm(links.rest);
            if (looksLikeLink(rest)) return new Verdict(Problem.LINK, ChatLinks.Rule.FORM);
            if (looksLikePhone(rest)) return new Verdict(Problem.PHONE, null);
        }
        return new Verdict(usesWords(text) ? Problem.WORDS : Problem.NONE, null);
    }

    /** What is wrong with a text the player is about to send while links are off, or {@link Problem#NONE}. */
    public static Problem check(@Nullable String typed) {
        return verdict(typed, null).problem;
    }

    /** Whether a text holds the middle finger, in any tone. */
    public static boolean usesWords(String text) {
        return checkForm(text).contains(MIDDLE_FINGER);
    }

    private static final Map<Integer, Character> LOOK_ALIKES = new HashMap<>();
    /** Small capitals NFKC leaves alone, as the letters they show (the small capital I is the dotted i). */
    private static final Map<Integer, String> SMALL_CAPITALS = new HashMap<>();

    static {
        int[] from = {0x0430, 0x0432, 0x0435, 0x043A, 0x043C, 0x043D, 0x043E, 0x0440, 0x0441, 0x0442, 0x0443, 0x0445,
                0x0455, 0x0456, 0x0458, 0x0501, 0x04CF, 0x0261, 0x03B1, 0x03B2, 0x03B5, 0x03B9, 0x03BA, 0x03BD, 0x03BF,
                0x03C1, 0x03C4, 0x03C5, 0x03C7, 0x03C9};
        String to = "abekmhopctyxsijdlgabeikvoptuxw";
        for (int i = 0; i < from.length; i++) LOOK_ALIKES.put(from[i], to.charAt(i));
        String[] block = {"a", "ae", "ae", "b", "c", "d", "d", "e", "e", "i", "j", "k", "l", "m", "n", "o", "o", "o",
                "o", "o", "oe", "ou", "o", "o", "p", "r", "r", "t", "u", "u", "u", "m", "v", "w", "z"};
        for (int i = 0; i < block.length; i++) SMALL_CAPITALS.put(0x1D00 + i, block[i]);
        int[] others = {0x0299, 0x0262, 0x029C, 0x026A, 0x029F, 0x0274, 0x0280, 0x028F, 0xA730, 0xA7AF, 0xA731};
        String letters = "bghilnryfqs";
        for (int i = 0; i < others.length; i++) SMALL_CAPITALS.put(others[i], String.valueOf(letters.charAt(i)));
    }

    /** Decimal digits of other scripts NFKC leaves alone: the first code point of each 0 to 9. */
    private static final int[] DIGIT_ZEROS = {0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0E50, 0x1040, 0x17E0};

    /** Flags whose letters alone spell a listed word (Antarctica "aq"): the word check reads them as flags. */
    private static final Set<String> FLAG_WORDS = new HashSet<>(Arrays.asList("aq", "shit", "cock"));

    /**
     * The letter or number a symbol stands for where NFKC does not say it: negative circled and
     * squared letters, small capitals, dingbat digits (the tenth is "10"), negative circled 11 to 20,
     * double circled digits, the circled zeros and the keycap ten; else null.
     */
    @Nullable
    private static String standIn(int code) {
        if (code >= 0x1F150 && code <= 0x1F169) return String.valueOf((char) ('a' + code - 0x1F150));
        if (code >= 0x1F170 && code <= 0x1F189) return String.valueOf((char) ('a' + code - 0x1F170));
        if (code >= 0x2776 && code <= 0x2793) return String.valueOf((code - 0x2776) % 10 + 1);
        if (code >= 0x24EB && code <= 0x24F4) return String.valueOf(code - 0x24EB + 11);
        if (code >= 0x24F5 && code <= 0x24FE) return String.valueOf(code - 0x24F5 + 1);
        if (code == 0x24FF || code == 0x1F10B || code == 0x1F10C) return "0";
        if (code == 0x1F51F) return "10";
        return SMALL_CAPITALS.get(code);
    }

    /** The form the checks read ({@link #checkForm(String, boolean)} for links and phone numbers). */
    public static String checkForm(String text) {
        return checkForm(text, false);
    }

    /**
     * The form the checks read: NFKC, lower case the Turkish way (capital I is dotless, the dotted
     * capital is i), marks and format characters out after NFKD, look-alike Cyrillic and Greek letters
     * as Latin, other scripts' digits as ASCII digits, symbols that stand for letters and numbers as
     * those, a run of regional indicators as its letters (the flags that spell "discord.gg"). With
     * {@code words} (the service's word check) a run that spells a listed word alone reads as a space.
     */
    public static String checkForm(String text, boolean words) {
        String lower = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(DOTTED_CAPITAL_I, 'i').replace('I', DOTLESS_SMALL_I).toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFKD);
        StringBuilder form = new StringBuilder(decomposed.length());
        StringBuilder flags = new StringBuilder();
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            if (mark(cp) || Character.getType(cp) == Character.FORMAT) continue;
            if (cp >= 0x1F1E6 && cp <= 0x1F1FF) {
                flags.append((char) ('a' + cp - 0x1F1E6));
                continue;
            }
            endFlags(form, flags, words);
            int digit = -1;
            for (int zero : DIGIT_ZEROS) {
                if (cp >= zero && cp < zero + 10) {
                    digit = cp - zero;
                    break;
                }
            }
            if (digit >= 0) {
                form.append((char) ('0' + digit));
                continue;
            }
            String stand = standIn(cp);
            Character latin = stand == null ? LOOK_ALIKES.get(cp) : null;
            if (stand != null) form.append(stand);
            else if (latin != null) form.append(latin.charValue());
            else form.appendCodePoint(cp);
        }
        endFlags(form, flags, words);
        return form.toString();
    }

    private static void endFlags(StringBuilder form, StringBuilder flags, boolean words) {
        if (flags.length() == 0) return;
        String letters = flags.toString();
        form.append(words && FLAG_WORDS.contains(letters) ? " " : letters);
        flags.setLength(0);
    }

    // ---- links ----

    private static final Set<String> TLDS = new HashSet<>(Arrays.asList((
            "ac ad ae af ag ai al am ao aq ar as at au aw ax az ba bb bd be bf bg bh bi bj bm bn bo br bs bt "
            + "bw by bz ca cc cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz de dj dk dm do dz ec ee eg er "
            + "es et eu fi fj fk fm fo fr ga gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy hk hm hn hr ht "
            + "hu id ie il im in io iq ir is it je jm jo jp ke kg kh ki km kn kp kr kw ky kz la lb lc li lk lr "
            + "ls lt lu lv ly ma mc md me mg mh mk ml mm mn mo mp mq mr ms mt mu mv mw mx my mz na nc ne nf ng "
            + "ni nl no np nr nu nz om pa pe pf pg ph pk pl pm pn pr ps pt pw py qa re ro rs ru rw sa sb sc sd "
            + "se sg sh si sk sl sm sn so sr ss st su sv sx sy sz tc td tf tg th tj tk tl tm tn to tr tt tv tw "
            + "tz ua ug uk us uy uz va vc ve vg vi vn vu wf ws ye yt za zm zw "
            + "com net org info biz xyz online site store shop app dev icu vip win bet pro top club live fun "
            + "link click space website tech cloud email world life today news blog art lol game games porn sex "
            + "xxx mobi name asia tel travel jobs cat edu gov mil int wiki fyi one plus best cool page rocks "
            + "host press digital network software solutions agency studio design media social chat zone team "
            + "center group global company services support systems tools city land red blue pink black green "
            + "gold rest bond cfd sbs cyou monster quest buzz fit run gift gifts moe ink kim lat ltd mom pub "
            + "uno vin wtf ninja guru town works zip mov stream tube video photo pics download free money "
            + "cash shopping market trade bid loan casino poker cam dating date love sexy adult tattoo").split(" ")));

    /** JavaScript's white space as a class: Java's \s is ASCII only on the JVM, ICU's leaves out the vertical tab. */
    private static final String WS =
            "[\\t\\n\\x0B\\f\\r\\x20\\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]";
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern DOMAIN = Pattern.compile("(?:^|[^a-z0-9-])((?:" + LABEL + "\\.)+([a-z]{2,24}))(?![a-z0-9-])");
    private static final Pattern IPV4 = Pattern.compile("(?:^|[^0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.]*[0-9])");
    private static final Pattern SCHEME = Pattern.compile("[a-z]{3,8}://|://|https?:|www\\.");
    private static final Pattern DISCORD = Pattern.compile("discord(?:app)?(?:gg|cominvite)|dscgg");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^a-z0-9]");
    private static final String OWN_DOMAIN = "projects.gg";
    /** The ideographic full stop, a dot always (NFKC has made the halfwidth and vertical ones into it). */
    private static final char FULL_STOP = (char) 0x3002;
    /** Round and small square emoji written for a dot. */
    private static final int[] DOT_EMOJI = {0x26AB, 0x26AA, 0x1F534, 0x1F535, 0x1F7E0, 0x1F7E1, 0x1F7E2, 0x1F7E3,
            0x1F7E4, 0x1F518, 0x23FA, 0x25CF, 0x25CB, 0x25C9, 0x25E6, 0x2981, 0x25AA, 0x25AB, 0x25FE, 0x25FD, 0x1F538,
            0x1F539};
    /**
     * A dot emoji is a dot only right after a letter or digit and before a common ending, spaces
     * allowed after it: "naber" red circle "ne zaman" is ordinary Turkish, "sunucu" green circle
     * "online" decoration.
     */
    private static final Pattern EMOJI_DOT = Pattern.compile("(?<=[a-z0-9])(?:" + alternatives(DOT_EMOJI) + ")" + WS
            + "*(?=(?:com|net|org|gg|io|co|me|tv|xyz|tk|ru|tr|app|dev|site|shop|store|online|live|link|info)(?![a-z0-9]))");
    /** Middle dot, bullet, bullet operator and dot operator: dots in disguise. */
    private static final Pattern DOT_LOOKS = Pattern.compile("[" + (char) 0x00B7 + (char) 0x2022 + (char) 0x2219
            + (char) 0x22C5 + "]");
    private static final Pattern BRACKET_DOT = Pattern.compile(WS + "*[(\\[\\{<]" + WS + "*(?:\\.|dot|nokta)" + WS
            + "*[)\\]\\}>]" + WS + "*");
    private static final Pattern WORD_DOT = Pattern.compile(WS + "+(?:dot|nokta)" + WS + "+(?=[a-z])");
    private static final Pattern SPACED_DOT = Pattern.compile(WS + "+\\." + WS + "*(?=[a-z0-9])");

    private static String alternatives(int[] codePoints) {
        StringBuilder out = new StringBuilder();
        for (int cp : codePoints) {
            if (out.length() > 0) out.append('|');
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** The dotless i read as "i", look-alike and written-out dots made dots, spaces that hide a dot taken out. */
    static String linkForm(String form) {
        String text = form.replace(DOTLESS_SMALL_I, 'i').replace(FULL_STOP, '.');
        text = EMOJI_DOT.matcher(text).replaceAll(".");
        text = DOT_LOOKS.matcher(text).replaceAll(".");
        text = BRACKET_DOT.matcher(text).replaceAll(".");
        text = WORD_DOT.matcher(text).replaceAll(".");
        return SPACED_DOT.matcher(text).replaceAll(".");
    }

    /** Whether a text looks like a web address, a domain name or a server's IPv4 address (projects.gg may be written). */
    public static boolean looksLikeLink(String text) {
        String form = linkForm(checkForm(text));
        if (SCHEME.matcher(form).find() || IPV4.matcher(form).find()) return true;
        String letters = NOT_LETTER_OR_DIGIT.matcher(form).replaceAll("");
        if (DISCORD.matcher(letters).find()) return true;
        Matcher m = DOMAIN.matcher(form);
        while (m.find()) {
            String domain = m.group(1);
            boolean own = domain.equals(OWN_DOMAIN) || domain.endsWith("." + OWN_DOMAIN);
            if (TLDS.contains(m.group(2)) && !own) return true;
        }
        return false;
    }

    // ---- phone numbers ----

    private static final Pattern MOBILE = Pattern.compile("5[0-6][0-9]{8}");
    private static final Pattern LANDLINE = Pattern.compile("0[2-4][0-9]{9}");
    private static final Pattern INTERNATIONAL = Pattern.compile("^00[0-9]{10,}");

    /** Turkish mobiles and landlines, and any number written with + or 00 and 10 or more digits. */
    public static boolean looksLikePhone(String text) {
        String form = checkForm(text);
        for (int i = 0; i < form.length(); ) {
            int end = numberEnd(form, i);
            if (end < 0) {
                i += Character.charCount(form.codePointAt(i));
                continue;
            }
            String run = form.substring(i, end);
            i = end;
            StringBuilder digits = new StringBuilder(run.length());
            for (int k = 0; k < run.length(); k++) {
                if (digit(run.charAt(k))) digits.append(run.charAt(k));
            }
            if (digits.length() < 10) continue;
            if (run.startsWith("+") && digits.length() <= 16) return true;
            if (INTERNATIONAL.matcher(digits).find() || MOBILE.matcher(digits).find() || LANDLINE.matcher(digits).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The end of the number that starts at {@code at}, or -1: digits with at most three separators
     * between them (white space, . - / ( ) _ , and emoji with their skin tones), maybe led by "+".
     * Written out because the separators take emoji, which the regex engines do not share.
     */
    private static int numberEnd(String form, int at) {
        int n = form.length();
        int i = form.charAt(at) == '+' ? at + 1 : at;
        if (i >= n || !digit(form.charAt(i))) return -1;
        i++;
        int end = -1;
        while (true) {
            int j = i;
            int separators = 0;
            while (j < n && separators <= 3) {
                int cp = form.codePointAt(j);
                if (!separator(cp)) break;
                separators++;
                j += Character.charCount(cp);
            }
            if (separators > 3 || j >= n || !digit(form.charAt(j))) return end;
            i = j + 1;
            end = i;
        }
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean separator(int cp) {
        return space(cp) || ".-/()_,".indexOf(cp) >= 0 || EmojiRanges.pictographic(cp) || (cp >= 0x1F3FB && cp <= 0x1F3FF);
    }
}
