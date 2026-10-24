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

    /** "37.500" → "37.500'e" (the number as written, its dative). */
    public static String dative(String written, long value) {
        return written + "'" + dativeOf(lastWord(value));
    }

    /** "14:35" → "14:35'te"; a time not in HH:MM form comes back as it is. */
    public static String locativeTime(String hhmm) {
        if (hhmm == null || !hhmm.matches("^\\d{1,2}[:.]\\d{2}$")) return hhmm;
        String[] parts = hhmm.split("[:.]");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        String word = minute != 0 ? lastWord(minute) : lastWord(hour);
        return hhmm + "'" + locativeOf(word);
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

    public static String monthName(int month) {
        return MONTHS[Math.max(0, Math.min(11, month - 1))];
    }
}
