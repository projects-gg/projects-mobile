package gg.projects.mobile.cloud;

/**
 * Turkish case endings after numbers and times as they are read aloud, so a notice can say "37.500'e alındı" and
 * "14:35'te yenilenir" (the ending follows the last spoken word: "beş yüz" → "'e", "otuz" → "'da"): vowel harmony,
 * a buffer "y" after a vowel, "d" turning "t" after a voiceless consonant. Pure, with tests.
 */
public final class TrGrammar {
    private TrGrammar() {}

    private static final String[] DIGITS = {"sıfır", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz"};
    private static final String[] TENS = {"", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan"};

    /** The last word of {@code n} read aloud ({@code 37500} → "yüz", {@code 40} → "kırk", {@code 1000} → "bin"). */
    static String lastWord(long n) {
        n = Math.abs(n);
        if (n == 0) return "sıfır";
        if (n % 10 != 0) return DIGITS[(int) (n % 10)];
        if (n % 100 != 0) return TENS[(int) ((n % 100) / 10)];
        if (n % 1000 != 0) return "yüz";
        if (n % 1_000_000 != 0) return "bin";
        if (n % 1_000_000_000L != 0) return "milyon";
        return "milyar";
    }

    private static boolean isVowel(char c) {
        return "aeıioöuü".indexOf(c) >= 0;
    }

    private static boolean backVowel(String word) {
        for (int i = word.length() - 1; i >= 0; i--) {
            char c = word.charAt(i);
            if (isVowel(c)) return "aıou".indexOf(c) >= 0;
        }
        return false;
    }

    /** The dative ending a word takes ("'a", "'e", "'ya", "'ye"), without the apostrophe. */
    static String dativeOf(String word) {
        char last = word.charAt(word.length() - 1);
        String vowel = backVowel(word) ? "a" : "e";
        return isVowel(last) ? "y" + vowel : vowel;
    }

    /** The locative ending ("'da", "'de", "'ta", "'te"), without the apostrophe. */
    static String locativeOf(String word) {
        char last = word.charAt(word.length() - 1);
        String vowel = backVowel(word) ? "a" : "e";
        String consonant = "çfhkpsşt".indexOf(last) >= 0 ? "t" : "d";
        return consonant + vowel;
    }

    /** The ablative ending ("'dan", "'den", "'tan", "'ten"), without the apostrophe. */
    static String ablativeOf(String word) {
        return locativeOf(word) + "n";
    }

    /** "37.500" → "37.500'e" (the number as written, its dative). */
    public static String dative(String written, long value) {
        return written + "'" + dativeOf(lastWord(value));
    }

    /** "14:35" → "14:35'te"; a time not in HH:MM form comes back as it is. */
    public static String locativeTime(String hhmm) {
        String word = timeWord(hhmm);
        return word == null ? hhmm : hhmm + "'" + locativeOf(word);
    }

    /** "14:35" → "14:35'e" ("14:35'e kadar"); a time not in HH:MM form comes back as it is. */
    public static String dativeTime(String hhmm) {
        String word = timeWord(hhmm);
        return word == null ? hhmm : hhmm + "'" + dativeOf(word);
    }

    /** The last word of a time read aloud: its minutes, or its hour when they are zero. */
    private static String timeWord(String hhmm) {
        if (hhmm == null || !hhmm.matches("^\\d{1,2}[:.]\\d{2}$")) return null;
        String[] parts = hhmm.split("[:.]");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        return minute != 0 ? lastWord(minute) : lastWord(hour);
    }

    private static final String[] EN_ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] EN_TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    /**
     * An amount in words, so a typed price is read twice ("kırk bin" under "40.000", afkplus.md §1.4): Turkish
     * ("otuz yedi bin beş yüz") or British English ("thirty-seven thousand five hundred"). Up to 999 billion.
     */
    public static String words(long n, String lang) {
        if ("en".equals(lang)) return englishWords(n);
        if (n == 0) return "sıfır";
        StringBuilder out = new StringBuilder();
        long[] scales = {1_000_000_000L, 1_000_000L, 1000L, 1L};
        String[] names = {"milyar", "milyon", "bin", ""};
        long rest = Math.abs(n);
        for (int i = 0; i < scales.length; i++) {
            int group = (int) (rest / scales[i]);
            rest %= scales[i];
            if (group == 0) continue;
            // "bin", never "bir bin"; "bir milyon" keeps its "bir".
            if (!(names[i].equals("bin") && group == 1)) append(out, turkishHundreds(group));
            append(out, names[i]);
        }
        return out.toString().trim();
    }

    private static String turkishHundreds(int n) {
        StringBuilder out = new StringBuilder();
        int hundreds = n / 100;
        int tens = (n % 100) / 10;
        int ones = n % 10;
        if (hundreds > 0) {
            if (hundreds > 1) append(out, DIGITS[hundreds]);
            append(out, "yüz");
        }
        if (tens > 0) append(out, TENS[tens]);
        if (ones > 0) append(out, DIGITS[ones]);
        return out.toString();
    }

    private static void append(StringBuilder out, String word) {
        if (word.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(word);
    }

    private static String englishWords(long n) {
        if (n == 0) return "zero";
        StringBuilder out = new StringBuilder();
        long[] scales = {1_000_000_000L, 1_000_000L, 1000L, 1L};
        String[] names = {"billion", "million", "thousand", ""};
        long rest = Math.abs(n);
        boolean before = false;
        for (int i = 0; i < scales.length; i++) {
            int group = (int) (rest / scales[i]);
            rest %= scales[i];
            if (group == 0) continue;
            append(out, englishHundreds(group, before));
            append(out, names[i]);
            before = true;
        }
        return out.toString().trim();
    }

    /** British English: "and" before the last two digits after a hundred or a larger group. */
    private static String englishHundreds(int n, boolean largerBefore) {
        StringBuilder out = new StringBuilder();
        int hundreds = n / 100;
        int rest = n % 100;
        if (hundreds > 0) {
            append(out, EN_ONES[hundreds]);
            append(out, "hundred");
        }
        if (rest > 0) {
            if (hundreds > 0 || (largerBefore && n < 100)) append(out, "and");
            if (rest < 20) append(out, EN_ONES[rest]);
            else append(out, EN_TENS[rest / 10] + (rest % 10 > 0 ? "-" + EN_ONES[rest % 10] : ""));
        }
        return out.toString();
    }

    private static final String[] MONTHS = {"Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül",
            "Ekim", "Kasım", "Aralık"};

    /** "26 Ekim" → "26 Ekim'de" (a day and month name, its locative after the month). */
    public static String locativeDate(int day, int month) {
        String name = MONTHS[Math.max(0, Math.min(11, month - 1))];
        return day + " " + name + "'" + locativeOf(name.toLowerCase(java.util.Locale.forLanguageTag("tr")));
    }

    /** "30 Ekim'e kadar" needs the dative after a month: "Ekim'e". */
    public static String dativeDate(int day, int month) {
        String name = MONTHS[Math.max(0, Math.min(11, month - 1))];
        return day + " " + name + "'" + dativeOf(name.toLowerCase(java.util.Locale.forLanguageTag("tr")));
    }

    /** "12 Kasım" → "12 Kasım'dan" ("… itibaren"). */
    public static String ablativeDate(int day, int month) {
        String name = MONTHS[Math.max(0, Math.min(11, month - 1))];
        return day + " " + name + "'" + ablativeOf(name.toLowerCase(java.util.Locale.forLanguageTag("tr")));
    }

    /** "3 Ocak 2027'de": a day of another year, the ending after the year as it is read ("yedi" → 'de). */
    public static String locativeDate(int day, int month, int year) {
        return day + " " + MONTHS[Math.max(0, Math.min(11, month - 1))] + " " + year + "'" + locativeOf(lastWord(year));
    }

    /** "3 Ocak 2027'den". */
    public static String ablativeDate(int day, int month, int year) {
        return day + " " + MONTHS[Math.max(0, Math.min(11, month - 1))] + " " + year + "'" + ablativeOf(lastWord(year));
    }

    public static String monthName(int month) {
        return MONTHS[Math.max(0, Math.min(11, month - 1))];
    }
}
