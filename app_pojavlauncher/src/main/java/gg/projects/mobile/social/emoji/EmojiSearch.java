package gg.projects.mobile.social.emoji;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Emoji search (CHAT2 section 3.1; the desktop's features/emoji/search.ts, ranked the same way).
 * In the interface's language; a Turkish search also finds English ("fire", "lol"). Folded as
 * every search in the apps ({@link #fold}): "I", dotted capital I and dotless i match "i", and
 * marks go, so "gulmek" finds the Turkish word for laughing. Each word of the query must start a
 * word of the emoji; a query of three letters or more also matches inside a name or a keyword.
 * Ranked: the name itself, a name that starts with the query, the name's words, its keywords,
 * then the same in English, then inside a word; Unicode's order within a rank. A pasted emoji
 * finds itself, in any skin tone.
 */
public final class EmojiSearch {
    private EmojiSearch() {}

    private static final Locale TR = Locale.forLanguageTag("tr");

    /**
     * The twenty emoji chats use most (the first of the desktop's POPULAR list: Unicode's emoji
     * frequency list, then a game server's own), most used first. They come first among the emoji
     * a query finds the same way (by name, by keyword, in English): "kalp" puts the red heart
     * before the anatomical one.
     */
    private static final int[][] BOOSTED = {
        {0x1F602}, {0x2764, 0xFE0F}, {0x1F923}, {0x1F44D}, {0x1F62D}, {0x1F64F}, {0x1F618},
        {0x1F970}, {0x1F60D}, {0x1F60A}, {0x1F389}, {0x1F601}, {0x1F495}, {0x1F97A},
        {0x1F605}, {0x1F525}, {0x263A, 0xFE0F}, {0x1F926}, {0x2665, 0xFE0F}, {0x1F937},
    };
    private static final int NOT_BOOSTED = BOOSTED.length;
    private static final Map<String, Integer> POPULARITY = new HashMap<>();

    static {
        for (int i = 0; i < BOOSTED.length; i++) {
            POPULARITY.put(EmojiTone.bareEmoji(new String(BOOSTED[i], 0, BOOSTED[i].length)), i);
        }
    }

    /**
     * Search folding for Turkish players (the apps' shared {@code fold}): lower case in Turkish,
     * "I", U+0130 and U+0131 all become "i", and marks go (s with cedilla gives s, g with breve
     * gives g, o with diaeresis gives o).
     */
    public static String fold(String text) {
        String nfd = Normalizer.normalize(text.toLowerCase(TR), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); ) {
            int cp = nfd.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                continue;
            }
            out.appendCodePoint(cp == 0x0131 ? 'i' : cp);
        }
        return out.toString();
    }

    /** A letter, a number, "+", "#" or "*" (the desktop splits a query on anything else). */
    private static boolean isWordChar(int cp) {
        if (cp == '+' || cp == '#' || cp == '*') return true;
        switch (Character.getType(cp)) {
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

    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            boolean word = isWordChar(cp);
            if (word && start < 0) start = i;
            if (!word && start >= 0) {
                out.add(text.substring(start, i));
                start = -1;
            }
            i += Character.charCount(cp);
        }
        if (start >= 0) out.add(text.substring(start));
        return out;
    }

    private static String join(List<String> tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (out.length() > 0) out.append(' ');
            out.append(token);
        }
        return out.toString();
    }

    /** A name, folded whole and as its words between spaces (" a b c "). */
    private static final class FoldedName {
        final String whole;
        final String words;

        FoldedName(String name) {
            whole = fold(name);
            words = " " + join(split(whole)) + " ";
        }
    }

    private static final class Folded {
        final FoldedName name;
        /** The English name of Turkish data. */
        final FoldedName english;

        Folded(EmojiData.Entry entry) {
            name = new FoldedName(entry.name);
            english = new FoldedName(entry.fallbackName);
        }
    }

    /** The folded names, made on the first search in a language and kept with its data. */
    private static final Map<EmojiData, Folded[]> CACHE = new WeakHashMap<>();

    /**
     * Folds the data's names now (the first search would): call it on the thread that parsed the
     * data, so the first letter typed does not wait for it on the main thread.
     */
    public static void prepare(EmojiData data) {
        foldedNames(data);
        data.byBare();
    }

    private static Folded[] foldedNames(EmojiData data) {
        synchronized (CACHE) {
            Folded[] names = CACHE.get(data);
            if (names == null) {
                List<EmojiData.Entry> entries = data.entries();
                names = new Folded[entries.size()];
                for (int i = 0; i < names.length; i++) names[i] = new Folded(entries.get(i));
                CACHE.put(data, names);
            }
            return names;
        }
    }

    /**
     * The query's words as the matches look for them, made once per search: {@code lead} is
     * " token" (a word of " a b c " starts with it), {@code whole} is " token " (a whole word).
     */
    private static final class Tokens {
        final String[] lead;
        final String[] whole;

        Tokens(List<String> tokens) {
            lead = new String[tokens.size()];
            whole = new String[tokens.size()];
            for (int i = 0; i < lead.length; i++) {
                lead[i] = " " + tokens.get(i);
                whole[i] = lead[i] + " ";
            }
        }

        boolean allWholeIn(String words) {
            for (String token : whole) {
                if (!words.contains(token)) return false;
            }
            return true;
        }

        boolean allLeadIn(String words) {
            for (String token : lead) {
                if (!words.contains(token)) return false;
            }
            return true;
        }
    }

    private static final int TIERS = 16;

    /**
     * How an entry matches, best first, as {@code tier * 16 + band}, or -1. Within the bands of
     * whole words (0), keywords (2) and English (3) the most used emoji come first; within the
     * name's beginnings (1) the order is the tier's; inside a word (4) last.
     */
    private static int rank(Folded folded, EmojiData.Entry entry, String query, Tokens tokens) {
        FoldedName name = folded.name;
        FoldedName english = folded.english;
        if (name.whole.equals(query)) return 0;
        if (tokens.allWholeIn(name.words)) return TIERS;
        if (name.whole.startsWith(query)) return 2 * TIERS + 1;
        if (tokens.allLeadIn(name.words)) return 3 * TIERS + 1;
        if (inAny(tokens, name.words, entry.words, null, null)) return 4 * TIERS + 2;
        if (!english.whole.isEmpty()) {
            if (english.whole.equals(query)) return 5 * TIERS + 3;
            if (tokens.allWholeIn(english.words)) return 6 * TIERS + 3;
            if (english.whole.startsWith(query)) return 7 * TIERS + 3;
            if (tokens.allLeadIn(english.words)) return 8 * TIERS + 3;
            if (inAny(tokens, name.words, entry.words, english.words, entry.fallback)) {
                return 9 * TIERS + 3;
            }
        }
        if (query.length() >= 3 && (name.whole.contains(query) || entry.words.contains(query))) {
            return 10 * TIERS + 4;
        }
        return -1;
    }

    /** Whether every token starts a word of one of the word lists (null ones left out). */
    private static boolean inAny(Tokens tokens, String a, String b, String c, String d) {
        for (String token : tokens.lead) {
            boolean found = a.contains(token) || b.contains(token)
                    || (c != null && c.contains(token)) || (d != null && d.contains(token));
            if (!found) return false;
        }
        return true;
    }

    private static boolean hasPictograph(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (EmojiChars.isExtPict(cp) || EmojiChars.isRegional(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static final class Found {
        final EmojiData.Entry entry;
        final int band;
        final int popular;
        final int tier;

        Found(EmojiData.Entry entry, int band, int popular, int tier) {
            this.entry = entry;
            this.band = band;
            this.popular = popular;
            this.tier = tier;
        }
    }

    private static final Comparator<Found> ORDER = new Comparator<Found>() {
        @Override
        public int compare(Found a, Found b) {
            if (a.band != b.band) return a.band - b.band;
            if (a.popular != b.popular) return a.popular - b.popular;
            if (a.tier != b.tier) return a.tier - b.tier;
            return a.entry.index - b.entry.index;
        }
    };

    /** The entries a query finds, best first; nothing for an empty query. */
    public static List<EmojiData.Entry> search(EmojiData data, String text) {
        String trimmed = EmojiChars.jsTrim(text);
        if (trimmed.isEmpty()) return Collections.emptyList();
        List<EmojiData.Entry> out = new ArrayList<>();
        if (hasPictograph(trimmed)) {
            String wanted = EmojiTone.bareEmoji(trimmed.substring(0, EmojiChars.graphemeEnd(trimmed, 0)));
            for (EmojiData.Entry entry : data.entries()) {
                if (entry.bare.equals(wanted)) out.add(entry);
            }
            return out;
        }
        List<String> tokens = split(fold(trimmed));
        if (tokens.isEmpty()) return out;
        String query = join(tokens);
        Tokens words = new Tokens(tokens);
        Folded[] names = foldedNames(data);
        List<Found> found = new ArrayList<>();
        for (EmojiData.Entry entry : data.entries()) {
            int match = rank(names[entry.index], entry, query, words);
            if (match < 0) continue;
            int tier = match / TIERS;
            int band = match % TIERS;
            Integer popularity = band == 1 ? null : POPULARITY.get(entry.bare);
            found.add(new Found(entry, band, popularity == null ? NOT_BOOSTED : popularity, tier));
        }
        Collections.sort(found, ORDER);
        for (Found f : found) out.add(f.entry);
        return out;
    }
}
