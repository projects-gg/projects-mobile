package gg.projects.mobile.cloud.plus;

import androidx.annotation.Nullable;

import gg.projects.mobile.social.ChatText;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AFK+ typed orders: the free rule check of the player's words, run as an instant hint while they type (projects-studio
 * services/cloud-afk/README.md, "AFK+ trade orders", "Typed orders"; the Worker runs the same rules exactly and the agent
 * again: their word is final). A port of the launcher's order-rules.ts, which ports the contract's reference
 * (test-vectors/afkplus-intake.mjs {@code checkText}, afkplus-money.mjs's amount reading, afkplus-text.mjs's contact
 * rules); the vectors' {@code rules} (and the digit part of {@code readers}) run in PlusDraftTest. The friends service's
 * word list is the one part left out: it never ships with the app (ChatText knows only the middle finger), so a text only
 * it refuses gets its answer from the service ("Kontrol et").
 *
 * <p>Pure Java, written to run on Android's ICU regex engine too (explicit [0-9] classes, no bare braces, bounded
 * look-behinds). The source stays ASCII: the folded phrase lists are ASCII already, the few other characters are code points.
 */
public final class OrderRules {
    private OrderRules() {}

    /** The typed text's least length, in code points after cleaning. */
    public static final int TEXT_MIN = 3;
    /** The typed text's most length, in code points after cleaning. */
    public static final int TEXT_MAX = 200;
    /** A generic profile's {@code money.maxDeal}; a Studio profile has its own. */
    public static final long GENERIC_MAX_DEAL = 100_000_000L;

    private static final char DOTTED_CAPITAL_I = (char) 0x0130;
    private static final char DOTLESS_SMALL_I = (char) 0x0131;
    private static final char LIRA_SIGN = (char) 0x20BA;
    private static final char EURO_SIGN = (char) 0x20AC;

    /** What the check counts against: the account's profile and its counters. Every part is optional (the defaults). */
    public static final class RuleContext {
        private long maxDeal = GENERIC_MAX_DEAL;
        private int active;
        private int orderMax = 10;
        private int minute;
        private int today;
        private int perDay = 20;

        /** The account profile's {@code money.maxDeal} (default: the generic profile's). */
        public RuleContext maxDeal(long value) {
            maxDeal = value;
            return this;
        }

        /** The account's active orders (default 0). */
        public RuleContext active(int value) {
            active = value;
            return this;
        }

        /** {@code limits.orderMax}: the most active orders (default 10). */
        public RuleContext orderMax(int value) {
            orderMax = value;
            return this;
        }

        /** Checks in the last minute (the Worker's rate binding allows 3; default 0). */
        public RuleContext minute(int value) {
            minute = value;
            return this;
        }

        /** Checks today (default 0). */
        public RuleContext today(int value) {
            today = value;
            return this;
        }

        /** {@code limits.orderChecksPerDay} (default 20). */
        public RuleContext perDay(int value) {
            perDay = value;
            return this;
        }
    }

    /** A check's answer: {@code result} and the card's {@code notes}. */
    public static final class RuleCheck {
        /** {@code ok}, {@code question:limit} (stored only with a limit), {@code reject:<reason>} or {@code reject:loosen:<rule>}. */
        public final String result;
        /** {@code approval_pref}: the authority decides, not the text. */
        public final List<String> notes;

        RuleCheck(String result, List<String> notes) {
            this.result = result;
            this.notes = Collections.unmodifiableList(notes);
        }

        /** Whether the text is refused ({@code reject:...}). */
        public boolean rejected() {
            return result.startsWith("reject:");
        }

        @Override
        public String toString() {
            return notes.isEmpty() ? result : result + " " + notes;
        }
    }

    /**
     * A refusal as the hint under the field reads it: {@code reason} is one of length, link, command, personal, unsafe,
     * loosen, too_big, orders_full, rate, daily; {@code rule} (only with loosen) one of prepay, pay_cmd, disclose, hide_ai,
     * rmt, off_platform, third_party.
     */
    public static final class Hint {
        public final String reason;
        @Nullable public final String rule;

        Hint(String reason, @Nullable String rule) {
            this.reason = reason;
            this.rule = rule;
        }

        @Override
        public String toString() {
            return rule == null ? reason : reason + ":" + rule;
        }
    }

    // ---- the money reading (afkplus-money.mjs) ----

    /** A multiplier word of the money reader, with the Turkish case endings it may carry (written without an apostrophe). */
    private static final class Unit {
        final String word;
        final long value;
        final String[] endings;

        Unit(String word, long value, String... endings) {
            this.word = word;
            this.value = value;
            this.endings = endings;
        }
    }

    /** Multipliers, longest word first. */
    private static final Unit[] UNITS = {
        new Unit("milyon", 1_000_000L, "dan", "luk", "da", "la", "un", "a", "u"),
        new Unit("million", 1_000_000L),
        new Unit("thousand", 1_000L),
        new Unit("bin", 1_000L, "den", "lik", "de", "le", "in", "e", "i"),
        new Unit("kk", 1_000_000L),
        new Unit("mn", 1_000_000L),
        new Unit("k", 1_000L),
        new Unit("m", 1_000_000L),
    };

    /** Real-money words: an amount written with one of them is real money talk (RMT). */
    private static final String[] CURRENCY_WORDS = {"tl", "lira", "try", "usd", "dolar", "dollar", "euro", "eur"};

    /** The value of digits with separators and a multiplier, or why it cannot be told. */
    public static final class Digits {
        /** The value; meaningful only without a {@link #problem}. */
        public final double value;
        /** {@code plain}, {@code unit}, {@code grouped}, {@code grouped_unit}, {@code decimal_unit} or {@code mixed}. */
        @Nullable public final String shape;
        /** {@code leading_zero}, {@code grouping_with_unit}, {@code decimal_without_unit}, {@code malformed}, {@code not_integer}. */
        @Nullable public final String problem;

        Digits(double value, @Nullable String shape, @Nullable String problem) {
            this.value = value;
            this.shape = shape;
            this.problem = problem;
        }

        static Digits of(double value, String shape) {
            return new Digits(value, shape, null);
        }

        static Digits problem(String problem) {
            return new Digits(0, null, problem);
        }
    }

    /** One number-like token of a text. */
    public static final class MoneyToken {
        public final int start;
        public final int end;
        public final String raw;
        public final String digits;
        /** 1, 1,000 or 1,000,000. */
        public final long multiplier;
        /** The multiplier word ({@code bin}, {@code k}, ...), or null. */
        @Nullable public final String unit;
        /** Written with a real-money word or sign. */
        public final boolean rmt;
        /** The amount; meaningful only without a {@link #problem}. */
        public final double value;
        @Nullable public final String shape;
        @Nullable public final String problem;

        MoneyToken(int start, int end, String raw, String digits, long multiplier, @Nullable String unit, boolean rmt,
                double value, @Nullable String shape, @Nullable String problem) {
            this.start = start;
            this.end = end;
            this.raw = raw;
            this.digits = digits;
            this.multiplier = multiplier;
            this.unit = unit;
            this.rmt = rmt;
            this.value = value;
            this.shape = shape;
            this.problem = problem;
        }
    }

    /** What {@link #tokenize(String)} found: the form read, its tokens and the digit runs glued to letters (no amounts). */
    public static final class Tokens {
        public final String form;
        public final List<MoneyToken> tokens;
        public final int stray;

        Tokens(String form, List<MoneyToken> tokens, int stray) {
            this.form = form;
            this.tokens = Collections.unmodifiableList(tokens);
            this.stray = stray;
        }
    }

    /** JavaScript's white space (what its trim and \s know) as a code unit: the JVM's \s is ASCII only. */
    private static boolean space(int c) {
        return c == ' ' || (c >= 0x09 && c <= 0x0D) || c == 0x00A0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    /** Turkish letters folded to ASCII (the lower case ones: the text is lower case already). */
    private static char fold(char c) {
        switch (c) {
            case 0x00E7: return 'c';
            case 0x011F: return 'g';
            case 0x0131: return 'i';
            case 0x00F6: return 'o';
            case 0x015F: return 's';
            case 0x00FC: return 'u';
            case 0x00E2: return 'a';
            case 0x00EE: return 'i';
            case 0x00FB: return 'u';
            default: return c;
        }
    }

    /** The typographic apostrophes (and the grave accent) people type for the case ending's apostrophe. */
    private static boolean apostrophe(char c) {
        return c == 0x2018 || c == 0x2019 || c == 0x02BC || c == 0x00B4 || c == '`';
    }

    /**
     * The form money is read in: NFKC, lower case the Turkish way ("I" is the dotless i, the dotted capital I is i), Turkish
     * letters folded to ASCII, typographic apostrophes as the apostrophe, white space runs as one space, trimmed.
     */
    public static String moneyForm(String text) {
        String lower = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(DOTTED_CAPITAL_I, 'i').replace('I', DOTLESS_SMALL_I).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean gap = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (space(c)) {
                gap = true;
                continue;
            }
            if (gap && out.length() > 0) out.append(' ');
            gap = false;
            out.append(apostrophe(c) ? '\'' : fold(c));
        }
        return out.toString();
    }

    /** The code unit at {@code i}, or -1 outside the text (a JavaScript index past the end reads undefined). */
    private static int charAt(String text, int i) {
        return i >= 0 && i < text.length() ? text.charAt(i) : -1;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    /** A letter, a number or an underscore (JavaScript's [\p{L}\p{N}_] on one code unit: a lone surrogate is none). */
    private static boolean isWordChar(int c) {
        if (c < 0) return false;
        if (c == '_') return true;
        switch (Character.getType((char) c)) {
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.DECIMAL_DIGIT_NUMBER:
            case Character.LETTER_NUMBER:
            case Character.OTHER_NUMBER:
                return true;
            default:
                return false;
        }
    }

    private static boolean isSign(int c) {
        return c == '$' || c == LIRA_SIGN || c == EURO_SIGN;
    }

    /** The length of an apostrophe and the letters after it at {@code at} (a Turkish case ending), else 0. */
    private static int caseEnding(String form, int at) {
        if (charAt(form, at) != '\'') return 0;
        int i = at + 1;
        while (i < form.length()) {
            int cp = form.codePointAt(i);
            if (!Character.isLetter(cp)) break;
            i += Character.charCount(cp);
        }
        return i == at + 1 ? 0 : i - at;
    }

    private static boolean boundaryAt(String form, int at) {
        return at >= form.length() || !isWordChar(form.charAt(at));
    }

    private static final class UnitHit {
        final Unit unit;
        final int end;

        UnitHit(Unit unit, int end) {
            this.unit = unit;
            this.end = end;
        }
    }

    @Nullable
    private static UnitHit unitAt(String form, int at) {
        int start = at;
        if (charAt(form, start) == ' ') start++;
        for (Unit unit : UNITS) {
            if (!form.startsWith(unit.word, start)) continue;
            int end = start + unit.word.length();
            for (String ending : unit.endings) {
                if (form.startsWith(ending, end)) {
                    end += ending.length();
                    break;
                }
            }
            end += caseEnding(form, end);
            if (boundaryAt(form, end)) return new UnitHit(unit, end);
        }
        return null;
    }

    /** The end of a real-money sign or word (with its ending) at {@code at}, or -1. */
    private static int currencyAt(String form, int at) {
        int start = at;
        if (charAt(form, start) == ' ') start++;
        if (isSign(charAt(form, start))) return start + 1;
        for (String word : CURRENCY_WORDS) {
            if (!form.startsWith(word, start)) continue;
            int end = start + word.length();
            if (word.length() >= 4) {
                while (end < form.length() && Character.isLetter(form.charAt(end))) end++;
            }
            end += caseEnding(form, end);
            if (boundaryAt(form, end)) return end;
        }
        return -1;
    }

    /** JavaScript's Number() of a string of digits (empty reads 0). */
    private static double number(String digits) {
        return digits.isEmpty() ? 0 : Double.parseDouble(digits);
    }

    private static boolean groupedTail(String[] groups, int from, int to) {
        for (int i = from; i < to; i++) {
            if (groups[i].length() != 3) return false;
        }
        return true;
    }

    private static String join(String[] groups, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < to; i++) out.append(groups[i]);
        return out.toString();
    }

    /** The groups of digits between "." and "," separators (empty groups kept). */
    private static String[] groupsOf(String digits) {
        List<String> groups = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c == '.' || c == ',') {
                groups.add(digits.substring(from, i));
                from = i + 1;
            }
        }
        groups.add(digits.substring(from));
        return groups.toArray(new String[0]);
    }

    /** The value of digits with separators and a multiplier, or why it cannot be told. */
    public static Digits readDigits(String digits, long multiplier) {
        String[] groups = groupsOf(digits);
        StringBuilder sepBuilder = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (!isDigit(digits.charAt(i))) sepBuilder.append(digits.charAt(i));
        }
        String seps = sepBuilder.toString();
        String first = groups[0];
        if (seps.isEmpty()) {
            if (first.length() > 1 && first.startsWith("0")) return Digits.problem("leading_zero");
            return Digits.of(number(first) * multiplier, multiplier == 1 ? "plain" : "unit");
        }
        boolean leadOk = first.length() >= 1 && first.length() <= 3 && !first.startsWith("0");
        boolean oneKind = true;
        for (int i = 1; i < seps.length(); i++) {
            if (seps.charAt(i) != seps.charAt(0)) oneKind = false;
        }
        if (oneKind) {
            if (leadOk && groupedTail(groups, 1, groups.length)) {
                if (multiplier == 1) return Digits.of(number(join(groups, groups.length)), "grouped");
                if (seps.length() == 1) return Digits.problem("grouping_with_unit");
                return Digits.of(number(join(groups, groups.length)) * multiplier, "grouped_unit");
            }
            String decimals = groups.length > 1 ? groups[1] : "";
            if (seps.length() == 1 && decimals.length() <= 2) {
                if (multiplier == 1) return Digits.problem("decimal_without_unit");
                if (first.length() > 1 && first.startsWith("0")) return Digits.problem("leading_zero");
                double scale = decimals.length() == 2 ? 100 : decimals.length() == 1 ? 10 : 1;
                double whole = number(first) * scale + number(decimals);
                return Digits.of((whole * multiplier) / scale, "decimal_unit");
            }
            return Digits.problem("malformed");
        }
        char last = seps.charAt(seps.length() - 1);
        String head = seps.substring(0, seps.length() - 1);
        String tail = groups[groups.length - 1];
        boolean headDiffers = !head.isEmpty();
        for (int i = 0; i < head.length(); i++) {
            if (head.charAt(i) == last) headDiffers = false;
        }
        if (multiplier == 1 && leadOk && headDiffers && groupedTail(groups, 1, groups.length - 1) && tail.length() >= 1
                && tail.length() <= 2) {
            boolean zeros = true;
            for (int i = 0; i < tail.length(); i++) {
                if (tail.charAt(i) != '0') zeros = false;
            }
            if (zeros) return Digits.of(number(join(groups, groups.length - 1)), "mixed");
            return Digits.problem("not_integer");
        }
        return Digits.problem("malformed");
    }

    /** The digits with their separators that start at {@code start}: digits, then any number of ("." or ",") and digits. */
    private static String digitsAt(String form, int start) {
        int i = start;
        while (i < form.length() && isDigit(form.charAt(i))) i++;
        while (i + 1 < form.length() && (form.charAt(i) == '.' || form.charAt(i) == ',') && isDigit(form.charAt(i + 1))) {
            i++;
            while (i < form.length() && isDigit(form.charAt(i))) i++;
        }
        return form.substring(start, i);
    }

    /** The number-like tokens of a text, left to right; digits glued to letters are no amount. */
    public static Tokens tokenize(String text) {
        String form = moneyForm(text);
        List<MoneyToken> tokens = new ArrayList<>();
        int stray = 0;
        int i = 0;
        while (i < form.length()) {
            if (!isDigit(form.charAt(i))) {
                i++;
                continue;
            }
            String digits = digitsAt(form, i);
            int end = i + digits.length();
            int before = charAt(form, i - 1);
            if (isWordChar(before)) {
                stray++;
                i = end;
                while (i < form.length() && isWordChar(form.charAt(i))) i++;
                continue;
            }
            boolean loneSeparator = (before == '.' || before == ',') && !isDigit(charAt(form, i - 2));
            boolean rmt = isSign(before) || (before == ' ' && isSign(charAt(form, i - 2)));
            Unit unit = null;
            UnitHit found = unitAt(form, end);
            if (found != null) {
                unit = found.unit;
                end = found.end;
            } else if (charAt(form, end) == '\'') {
                end += caseEnding(form, end);
            }
            int currencyEnd = currencyAt(form, end);
            if (currencyEnd != -1) {
                rmt = true;
                end = currencyEnd;
            }
            if (!boundaryAt(form, end)) {
                stray++;
                i = end;
                while (i < form.length() && isWordChar(form.charAt(i))) i++;
                continue;
            }
            long multiplier = unit == null ? 1 : unit.value;
            Digits read = loneSeparator ? Digits.problem("malformed") : readDigits(digits, multiplier);
            tokens.add(new MoneyToken(i, end, form.substring(i, end), digits, multiplier, unit == null ? null : unit.word, rmt,
                    read.value, read.shape, read.problem));
            i = end;
        }
        return new Tokens(form, tokens, stray);
    }

    /**
     * One amount the player types in a field ("40 bin", "40k", "40.000"): the number, or null when it cannot be told for
     * sure (the field then asks again): none, several, a decimal or grouping that does not add up, a real-money word, not
     * a whole number, zero or above {@code maxDeal}. The same reading as the agent's for digits; the agent alone reads
     * number words ("kirk bin").
     */
    @Nullable
    public static Long readAmount(String text, long maxDeal) {
        List<MoneyToken> tokens = tokenize(text).tokens;
        if (tokens.size() != 1) return null;
        MoneyToken token = tokens.get(0);
        if (token.problem != null) return null;
        double value = token.value;
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.floor(value) || value <= 0 || value > maxDeal
                || token.rmt) {
            return null;
        }
        return (long) value;
    }

    /** {@link #readAmount(String, long)} against the generic profile's {@code maxDeal}. */
    @Nullable
    public static Long readAmount(String text) {
        return readAmount(text, GENERIC_MAX_DEAL);
    }

    // ---- contact data (afkplus-text.mjs) ----

    private static boolean ibanValid(String candidate) {
        String moved = (candidate.substring(4) + candidate.substring(0, 4)).toUpperCase(Locale.ROOT);
        int rest = 0;
        for (int i = 0; i < moved.length(); i++) {
            char c = moved.charAt(i);
            int value = isDigit(c) ? c - '0' : c - 55;
            rest = (rest * (value < 10 ? 10 : 100) + value) % 97;
        }
        return rest == 1;
    }

    private static final Pattern IBAN_START = Pattern.compile("(?<![a-z0-9])[a-z]{2}[0-9]{2}");

    private static boolean alnum(int c) {
        return (c >= 'a' && c <= 'z') || isDigit(c);
    }

    /** Up to 60 units of letters, digits and single spaces before one of them, from {@code start} on. */
    private static String ibanTail(String form, int start) {
        StringBuilder tail = new StringBuilder();
        int i = start;
        while (tail.length() < 60 && i < form.length()) {
            char c = form.charAt(i);
            if (alnum(c) || (c == ' ' && alnum(charAt(form, i + 1)))) {
                tail.append(c);
                i++;
            } else {
                break;
            }
        }
        return tail.toString();
    }

    private static boolean hasIban(String form) {
        Matcher match = IBAN_START.matcher(form);
        while (match.find()) {
            String compact = ibanTail(form, match.start()).replace(" ", "");
            if (compact.length() > 34) compact = compact.substring(0, 34);
            for (int length = 15; length <= compact.length(); length++) {
                if (ibanValid(compact.substring(0, length))) return true;
            }
        }
        return false;
    }

    private static final Pattern ELEVEN_DIGITS = Pattern.compile("(?<![0-9])[0-9]{11}(?![0-9])");

    private static boolean hasTckn(String form) {
        Matcher match = ELEVEN_DIGITS.matcher(form);
        while (match.find()) {
            String run = match.group();
            int[] d = new int[11];
            for (int i = 0; i < 11; i++) d[i] = run.charAt(i) - '0';
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

    private static final Pattern CARD_NUMBER = Pattern.compile("(?<![0-9])[0-9](?:[ \\-]?[0-9]){12,18}(?![0-9])");

    private static boolean hasCard(String form) {
        Matcher match = CARD_NUMBER.matcher(form);
        while (match.find()) {
            String run = match.group();
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < run.length(); i++) {
                if (isDigit(run.charAt(i))) digits.append(run.charAt(i));
            }
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

    private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+\\-]@[\\p{L}\\p{N}\\-]{2,}");

    /** Contact details an order never carries: {@code email}, {@code iban}, {@code tckn}, {@code card}, or null. */
    @Nullable
    public static String contactProblem(String cleaned) {
        String form = ChatText.checkForm(cleaned);
        if (EMAIL.matcher(form).find()) return "email";
        if (hasIban(form)) return "iban";
        if (hasTckn(form)) return "tckn";
        if (hasCard(form)) return "card";
        return null;
    }

    /** AFK+ sends no link at all: {@code projects.gg} counts like any other domain here. */
    public static boolean anyLink(String cleaned) {
        return ChatText.looksLikeLink(ChatText.checkForm(cleaned).replace("projects", "projectz"));
    }

    // ---- the phrase lists (afkplus-intake.mjs; patterns.rs where they share its name) ----

    private static List<String> list(String... phrases) {
        return Collections.unmodifiableList(Arrays.asList(phrases));
    }

    private static List<String> listPlus(List<String> base, String... more) {
        List<String> out = new ArrayList<>(base);
        out.addAll(Arrays.asList(more));
        return Collections.unmodifiableList(out);
    }

    /** patterns.rs {@code SELF_HARM}. */
    private static final List<String> SELF_HARM = list("intihar", "kendimi oldur", "kendimi oldurecegim", "olmek istiyorum",
            "kendime zarar", "yasamak istemiyorum", "kill myself", "suicide", "want to die", "end my life", "hurt myself");

    /** patterns.rs {@code MINOR_SAFETY}. */
    private static final List<String> MINOR_SAFETY = list("kac yasindasin", "yasin kac", "kac yasinda", "foto at",
            "fotograf at", "fotografini at", "resim at", "resmini at", "kimseye soyleme", "kimseye deme", "sevgilin var mi",
            "nerede oturuyorsun", "hangi okul", "how old are you", "send a pic", "send pic", "send a photo",
            "dont tell anyone", "do not tell anyone", "where do you live", "are you single");

    /** patterns.rs {@code IMPERSONATION}. */
    private static final List<String> IMPERSONATION = list("ben adminim", "ben admin", "yetkiliyim", "moderatorum",
            "projects ekibi", "projects ekibinden", "im an admin", "i am an admin", "staff here", "im staff");

    /** patterns.rs {@code ACCOUNT}. */
    private static final List<String> ACCOUNT = list("sifre", "sifreni", "hesabini ver", "hesabina gir", "hesabini paylas",
            "password", "your account", "login code");

    /** patterns.rs {@code SCAM}: in a typed order, an attempt at the {@code prepay} rule. */
    private static final List<String> SCAM = list("once sen", "once at", "once gonder", "ilk sen", "pay first", "send first",
            "you first", "pay me first");

    /** patterns.rs {@code RMT}: in a typed order, an attempt at the {@code rmt} rule. */
    private static final List<String> RMT = list("papara", "iban", "steam kart", "steam karti", "hediye kart", "hediye karti",
            "gercek para", "gercek parayla", "real money", "paypal", "gift card", "ininal");

    /** One hard rule and the phrases that attempt to loosen it. */
    public static final class Loosen {
        /** {@code prepay}, {@code pay_cmd}, {@code disclose}, {@code hide_ai}, {@code rmt}, {@code off_platform}, {@code third_party}. */
        public final String rule;
        public final List<String> phrases;

        Loosen(String rule, List<String> phrases) {
            this.rule = rule;
            this.phrases = phrases;
        }
    }

    /**
     * Attempts to loosen a hard rule ({@code loosen:<rule>}), checked in this order. Phrases are folded words (patterns.rs's
     * form); a phrase's last word of 4 letters or more may carry an ending. {@code rmt} comes before {@code off_platform}:
     * payment apps (papara, ininal, a steam card) are real money first. {@code rmt} also holds for an amount written with a
     * real-money word or sign, {@code off_platform} for every name of {@link #PLATFORM_WORDS} as a whole word.
     */
    public static final List<Loosen> LOOSEN = Collections.unmodifiableList(Arrays.asList(
            new Loosen("prepay", listPlus(SCAM, "once ben oder", "once ben odey", "once ben veri", "once ben gonder",
                    "once ben yolla", "once ben atar", "once ode", "onden ode", "onden oder", "onden odey", "onden at",
                    "onden gonder", "onden veri", "onden yolla", "guvenip ver", "guvenip gonder", "guvenip yolla",
                    "i pay first", "ill pay first", "trust me", "pay upfront")),
            new Loosen("pay_cmd", list("parayi gonder", "parayi yolla", "parayi at", "para gonder", "para yolla", "para at",
                    "pay komut", "send money", "send the money", "send me money", "transfer money", "transfer the money")),
            new Loosen("disclose", list("butcemi soyle", "butcemi yaz", "butcemi paylas", "bakiyemi soyle", "bakiyemi yaz",
                    "bakiyemi paylas", "limitimi soyle", "limitimi yaz", "sinirimi soyle", "sinirlarimi soyle",
                    "en fazla ne verecegimi soyle", "tell my budget", "tell them my budget", "share my budget",
                    "say my budget", "tell my limit", "tell them my limit", "tell my balance")),
            new Loosen("hide_ai", list("bot oldugunu soyleme", "bot oldugunu sakla", "bot oldugunu belli etme",
                    "yapay zeka oldugunu soyleme", "yapay zeka oldugunu sakla", "insan gibi davran", "insanmis gibi",
                    "gercek oyuncu gibi", "ben gibi davran", "dont say you are a bot", "dont say youre a bot",
                    "do not say you are a bot", "pretend to be human", "pretend you are human", "act human",
                    "act like a human")),
            new Loosen("rmt", listPlus(RMT, "tl ile", "tl ye", "tlye", "lira ile", "liraya", "dolarla", "euro ile",
                    "gercek parali", "irl money", "for real money", "for cash", "nakit")),
            new Loosen("off_platform", list("baska uygulama", "ozelden konusalim", "dmden yaz", "other app", "move to dm",
                    "add me on")),
            new Loosen("third_party", list("herkese yaz", "herkese mesaj", "herkese pm", "herkese ozel", "mesaj at",
                    "mesaj gonder", "mesaj yolla", "pm at", "pm gonder", "spam yap", "spam at", "spamla", "message everyone",
                    "dm everyone", "pm everyone", "spam"))));

    /** "Any price": the order cannot be stored without a limit, so the limit question is asked. */
    public static final List<String> NO_LIMIT = list("her fiyata", "her fiyat", "kac olursa", "ne kadar olursa", "kaca olursa",
            "fiyat fark etmez", "fiyati fark etmez", "fiyat farketmez", "fiyati farketmez", "limit yok", "limitsiz",
            "sinirsiz", "sinir yok", "any price", "whatever price", "whatever it costs", "no limit", "at any cost");

    /** "Don't ask me": a note on the card (the authority setting decides, not the text). */
    public static final List<String> APPROVAL_PREF = list("bana sorma", "bana sormadan", "sormadan al", "sormadan sat",
            "sormadan tamamla", "onaysiz", "onay isteme", "onay bekleme", "dont ask me", "do not ask me", "without asking",
            "no approval");

    /**
     * Names of other platforms and payment apps (folded, whole words): AFK+ text never sends anyone elsewhere. The bot's own
     * templates may name them; the model's text and persona texts may not.
     */
    public static final List<String> PLATFORM_WORDS = list("discord", "dc", "instagram", "insta", "snapchat", "snap",
            "telegram", "whatsapp", "watsap", "wp", "tiktok", "twitch", "youtube", "steam", "papara", "ininal", "skype",
            "facebook", "messenger", "kik");

    // ---- the check ----

    /** A text's words in the patterns' form (patterns.rs {@code words}): lower case ASCII letters and digits, folded. */
    public static List<String> words(String text) {
        String form = moneyForm(ChatText.checkForm(text).replace(DOTLESS_SMALL_I, 'i'));
        List<String> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= form.length(); i++) {
            boolean word = i < form.length() && alnum(form.charAt(i));
            if (word) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                out.add(form.substring(start, i));
                start = -1;
            }
        }
        return out;
    }

    /** Whether the words hold the phrase as consecutive words; its last may carry an ending when it has 4+ letters. */
    public static boolean phraseIn(List<String> list, String phrase) {
        String[] parts = phrase.split(" ");
        int k = parts.length;
        if (k == 0 || list.size() < k) return false;
        String last = parts[k - 1];
        for (int i = 0; i + k <= list.size(); i++) {
            boolean ok = true;
            for (int j = 0; j < k - 1; j++) {
                if (!list.get(i + j).equals(parts[j])) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            String word = list.get(i + k - 1);
            if (word.equals(last) || (last.length() >= 4 && word.startsWith(last))) return true;
        }
        return false;
    }

    private static boolean anyPhrase(List<String> words, List<String> phrases) {
        for (String phrase : phrases) {
            if (phraseIn(words, phrase)) return true;
        }
        return false;
    }

    /** The text as it is checked, stored as the order's {@code note} and sent on: cleaned, on one line. */
    public static String cleanOrderText(@Nullable String text) {
        return ChatText.clean(text).replace('\n', ' ');
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** JavaScript's white space as a class (the JVM's \s is ASCII only; ICU's leaves out the vertical tab). */
    private static final String WS =
            "[\\t\\n\\x0B\\f\\r\\x20\\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]";
    /** A slash or backslash command (at the start or after a space). */
    private static final Pattern COMMAND = Pattern.compile("(?:^|" + WS + ")[/\\\\][a-z]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A section sign or a colour code. */
    private static final Pattern COLOUR = Pattern.compile("\\x{A7}|&[0-9a-fk-or]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static boolean platformNamed(List<String> words) {
        for (String word : words) {
            for (String name : PLATFORM_WORDS) {
                if (name.length() >= 5 ? word.startsWith(name) : word.equals(name)) return true;
            }
        }
        return false;
    }

    /** Whether a cleaned text shows something (not only joiners, spaces and marks). */
    private static boolean hasText(String cleaned) {
        return ChatText.check(cleaned) != ChatText.Problem.EMPTY;
    }

    /**
     * The free rule check: {@code result} and the card's notes. The first rule that holds answers, in this order: length,
     * link, command, personal, unsafe, loosen, too_big, orders_full, rate, daily.
     */
    public static RuleCheck checkOrderText(@Nullable String text, RuleContext ctx) {
        String cleaned = cleanOrderText(text);
        List<String> notes = new ArrayList<>();
        int size = codePoints(cleaned);
        if (size < TEXT_MIN || size > TEXT_MAX || !hasText(cleaned)) return new RuleCheck("reject:length", notes);
        if (anyLink(cleaned)) return new RuleCheck("reject:link", notes);
        if (COMMAND.matcher(cleaned).find() || COLOUR.matcher(cleaned).find()) return new RuleCheck("reject:command", notes);
        if (ChatText.looksLikePhone(cleaned) || contactProblem(cleaned) != null) return new RuleCheck("reject:personal", notes);
        List<String> list = words(cleaned);
        if (anyPhrase(list, SELF_HARM) || anyPhrase(list, MINOR_SAFETY) || anyPhrase(list, ACCOUNT)
                || anyPhrase(list, IMPERSONATION)) {
            return new RuleCheck("reject:unsafe", notes);
        }
        List<MoneyToken> tokens = tokenize(cleaned).tokens;
        for (Loosen loosen : LOOSEN) {
            boolean hit = anyPhrase(list, loosen.phrases);
            if (loosen.rule.equals("off_platform")) hit = hit || platformNamed(list);
            if (loosen.rule.equals("rmt")) {
                for (MoneyToken token : tokens) hit = hit || token.rmt;
            }
            if (hit) return new RuleCheck("reject:loosen:" + loosen.rule, notes);
        }
        for (MoneyToken token : tokens) {
            if (token.problem == null && token.value > ctx.maxDeal) return new RuleCheck("reject:too_big", notes);
        }
        if (ctx.active >= ctx.orderMax) return new RuleCheck("reject:orders_full", notes);
        if (ctx.minute >= 3) return new RuleCheck("reject:rate", notes);
        if (ctx.today >= ctx.perDay) return new RuleCheck("reject:daily", notes);
        if (anyPhrase(list, APPROVAL_PREF)) notes.add("approval_pref");
        if (anyPhrase(list, NO_LIMIT)) return new RuleCheck("question:limit", notes);
        return new RuleCheck("ok", notes);
    }

    /** {@link #checkOrderText(String, RuleContext)} with the defaults (the generic profile, no counters). */
    public static RuleCheck checkOrderText(@Nullable String text) {
        return checkOrderText(text, new RuleContext());
    }

    /** A check's result as the hint under the field reads it: the reason and the rule, or null when the text is not refused. */
    @Nullable
    public static Hint ruleHint(RuleCheck check) {
        if (!check.rejected()) return null;
        String[] parts = check.result.split(":");
        return new Hint(parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : null);
    }
}
