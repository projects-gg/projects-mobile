package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of a chat message (CHAT1; services/social src/chattext.ts): cleaned like the service
 * cleans it, then checked for what the service turns away, so the composer can say why before
 * anything is sent (a link, a phone number). The service checks anyway (and alone knows its word
 * list); a difference only means it answers {@code chat_rejected} instead. Pure Java.
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

    public enum Problem { NONE, EMPTY, TOO_LONG, LINK, PHONE }

    /** Any white space, Unicode spaces too (JavaScript's \s; Java's own is ASCII only on the JVM). */
    private static final String SPACE = "[\\s\\p{Z}]";

    private static final char ZWJ = (char) 0x200D;
    private static final char DOTLESS_SMALL_I = (char) 0x0131;
    private static final char DOTTED_CAPITAL_I = (char) 0x0130;
    private static final String ZWJ_TEXT = String.valueOf(ZWJ);

    /**
     * NFC; control, format and private-use characters out except line breaks (the zero width joiner
     * stays: the service keeps it inside emoji); at most two marks on a letter; spaces collapsed; empty
     * lines dropped; at most 5 lines (the rest joined into the fifth); trimmed.
     */
    public static String clean(@Nullable String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n')
                .replace((char) 0x0085, '\n').replace((char) 0x2028, '\n').replace((char) 0x2029, '\n')
                .replace('\t', ' ').replace((char) 0x000B, ' ').replace('\f', ' ');
        StringBuilder out = new StringBuilder();
        int marks = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == ZWJ) {
                out.appendCodePoint(cp);
                marks = 0;
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                    || type == Character.SURROGATE || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0
                    || cp == 0x2800) {
                continue;
            }
            boolean mark = type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK;
            if (mark) {
                if (++marks > 2) continue;
            } else {
                marks = 0;
            }
            out.appendCodePoint(cp);
        }
        StringBuilder lines = new StringBuilder();
        int count = 0;
        for (String raw : out.toString().split("\n", -1)) {
            String line = raw.replaceAll(SPACE + "+", " ").trim();
            if (line.isEmpty()) continue;
            if (count >= MAX_LINES) {
                lines.append(' ').append(line);
                continue;
            }
            if (count > 0) lines.append('\n');
            lines.append(line);
            count++;
        }
        return lines.toString();
    }

    /** Unicode code points, as the service counts the 300. */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** What is wrong with a text the player is about to send (cleaned first), or {@link Problem#NONE}. */
    public static Problem check(@Nullable String value) {
        String text = clean(value);
        String shown = text.replace(ZWJ_TEXT, "").replaceAll("[\\s\\p{Z}\\p{M}]", "");
        if (text.isEmpty() || shown.isEmpty()) return Problem.EMPTY;
        if (length(text) > MAX) return Problem.TOO_LONG;
        String form = checkForm(text);
        if (looksLikeLink(form)) return Problem.LINK;
        if (looksLikePhone(form)) return Problem.PHONE;
        return Problem.NONE;
    }

    private static final Map<Integer, Character> LOOK_ALIKES = new HashMap<>();

    static {
        int[] from = {0x0430, 0x0432, 0x0435, 0x043A, 0x043C, 0x043D, 0x043E, 0x0440, 0x0441, 0x0442, 0x0443, 0x0445,
                0x0455, 0x0456, 0x0458, 0x0501, 0x04CF, 0x0261, 0x03B1, 0x03B2, 0x03B5, 0x03B9, 0x03BA, 0x03BD, 0x03BF,
                0x03C1, 0x03C4, 0x03C5, 0x03C7, 0x03C9};
        String to = "abekmhopctyxsijdlgabeikvoptuxw";
        for (int i = 0; i < from.length; i++) LOOK_ALIKES.put(from[i], to.charAt(i));
    }

    /** Decimal digits of other scripts NFKC leaves alone: the first code point of each 0 to 9. */
    private static final int[] DIGIT_ZEROS = {0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0E50, 0x1040, 0x17E0};

    /**
     * The form the checks read: NFKC, lower case the Turkish way (capital I is dotless, the dotted
     * capital is i), marks and format characters out after NFKD, look-alike Cyrillic and Greek letters
     * as Latin, other scripts' digits as ASCII digits.
     */
    public static String checkForm(String text) {
        String lower = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(DOTTED_CAPITAL_I, 'i').replace('I', DOTLESS_SMALL_I).toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFKD);
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK || type == Character.FORMAT) {
                continue;
            }
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
            Character latin = LOOK_ALIKES.get(cp);
            if (latin != null) form.append(latin.charValue());
            else form.appendCodePoint(cp);
        }
        return form.toString();
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

    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern DOMAIN = Pattern.compile("(?:^|[^a-z0-9-])((?:" + LABEL + "\\.)+([a-z]{2,24}))(?![a-z0-9-])");
    private static final Pattern IPV4 = Pattern.compile("(?:^|[^0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.]*[0-9])");
    private static final Pattern SCHEME = Pattern.compile("[a-z]{3,8}://|://|https?:|www\\.");
    private static final Pattern DISCORD = Pattern.compile("discord(?:app)?(?:gg|cominvite)|dscgg");
    /** Middle dot, bullet, bullet operator and dot operator: dots in disguise. */
    private static final String DOT_LOOKS = "[" + (char) 0x00B7 + (char) 0x2022 + (char) 0x2219 + (char) 0x22C5 + "]";
    private static final String OWN_DOMAIN = "projects.gg";

    /** The dotless i read as "i", look-alike and written-out dots made dots, spaces that hide a dot taken out. */
    static String linkForm(String form) {
        return form.replace(DOTLESS_SMALL_I, 'i')
                .replaceAll(DOT_LOOKS, ".")
                .replaceAll(SPACE + "*[(\\[{<]" + SPACE + "*(?:\\.|dot|nokta)" + SPACE + "*[)\\]}>]" + SPACE + "*", ".")
                .replaceAll(SPACE + "+(?:dot|nokta)" + SPACE + "+(?=[a-z])", ".")
                .replaceAll(SPACE + "+\\." + SPACE + "*(?=[a-z0-9])", ".");
    }

    /** Whether a text looks like a web address, a domain name or a server's IPv4 address. */
    public static boolean looksLikeLink(String text) {
        String form = linkForm(checkForm(text));
        if (SCHEME.matcher(form).find() || IPV4.matcher(form).find()) return true;
        String letters = form.replaceAll("[^a-z0-9]", "");
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

    private static final Pattern NUMBER = Pattern.compile("\\+?[0-9](?:[\\s\\p{Z}.\\-/()_,]{0,3}[0-9])+");
    private static final Pattern MOBILE = Pattern.compile("5[0-6][0-9]{8}");
    private static final Pattern LANDLINE = Pattern.compile("0[2-4][0-9]{9}");
    private static final Pattern INTERNATIONAL = Pattern.compile("^00[0-9]{10,}");

    /** Turkish mobiles and landlines, and any number written with + or 00 and 10 or more digits. */
    public static boolean looksLikePhone(String text) {
        String form = checkForm(text);
        Matcher m = NUMBER.matcher(form);
        while (m.find()) {
            String run = m.group();
            String digits = run.replaceAll("[^0-9]", "");
            if (digits.length() < 10) continue;
            if (run.startsWith("+") && digits.length() <= 16) return true;
            if (INTERNATIONAL.matcher(digits).find() || MOBILE.matcher(digits).find() || LANDLINE.matcher(digits).find()) {
                return true;
            }
        }
        return false;
    }
}
