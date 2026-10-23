package gg.projects.mobile.social.emoji;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The picker's emoji (CHAT2, chat2.md section 3.1; the desktop's features/emoji/data.ts): one TSV
 * per language ({@code assets/emoji/emoji.tr.tsv}, {@code emoji.en.tsv}, copies of the desktop's,
 * made from Unicode Emoji 17.0 and CLDR 48.2.0). Unicode's order and its nine groups; each row has
 * the emoji without a skin tone, its tones, its name and the folded words a search finds it by.
 * The Turkish file also carries the English name and words, for a Turkish search in English.
 *
 * <p>Parse it once, off the main thread; the result is immutable and safe to share. Characters
 * outside ASCII are named by code point here: the source stays ASCII.
 */
public final class EmojiData {
    /** The groups in the picker's order (their names are the screens' strings). */
    public static final List<String> GROUPS = Collections.unmodifiableList(Arrays.asList(
            "smileys", "people", "nature", "food", "travel", "activities", "objects", "symbols",
            "flags"));

    public static final String TURKISH = "tr";
    public static final String ENGLISH = "en";

    private static final String MAGIC = "PL-EMOJI";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Locale TR = Locale.forLanguageTag("tr");

    private final String lang;
    private final List<Entry> entries;
    private final List<List<Entry>> groups;
    /** Code points a skin tone follows (for tones by rule), sorted. */
    private final int[] toneBases;
    private Map<String, Entry> byBare;

    private EmojiData(String lang, List<Entry> entries, int[] toneBases) {
        this.lang = lang;
        this.entries = Collections.unmodifiableList(entries);
        this.toneBases = toneBases;
        List<List<Entry>> byGroup = new ArrayList<>();
        for (int g = 0; g < GROUPS.size(); g++) byGroup.add(new ArrayList<Entry>());
        for (Entry entry : entries) byGroup.get(entry.group).add(entry);
        List<List<Entry>> fixed = new ArrayList<>();
        for (List<Entry> list : byGroup) fixed.add(Collections.unmodifiableList(list));
        this.groups = Collections.unmodifiableList(fixed);
    }

    /** The asset of a language ("tr" or "en"; anything else is Turkish, as on the desktop). */
    public static String assetPath(String lang) {
        return ENGLISH.equals(lang) ? "emoji/emoji.en.tsv" : "emoji/emoji.tr.tsv";
    }

    /** Reads one data file (UTF-8; the caller closes the stream). See {@link #parse(Reader)}. */
    public static EmojiData parse(InputStream in) throws IOException {
        return parse(new InputStreamReader(in, UTF_8));
    }

    /** Reads one data file from its text. See {@link #parse(Reader)}. */
    public static EmojiData parse(String text) {
        try {
            return parse(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads one data file; a file of another format or a broken row throws
     * {@link IllegalArgumentException}. Line ends may be LF or CRLF; empty lines are skipped.
     */
    public static EmojiData parse(Reader reader) throws IOException {
        BufferedReader lines = reader instanceof BufferedReader
                ? (BufferedReader) reader : new BufferedReader(reader, 16 * 1024);
        String headerLine = nextLine(lines);
        String[] header = fields(headerLine == null ? "" : headerLine, 7);
        if (!MAGIC.equals(header[0]) || !"1".equals(header[1])) {
            throw new IllegalArgumentException("Not an emoji data file");
        }
        String lang = ENGLISH.equals(header[2]) ? ENGLISH : TURKISH;
        int[] bases = parseBases(header[6]);
        List<Entry> entries = new ArrayList<>(2048);
        String line;
        while ((line = nextLine(lines)) != null) {
            int index = entries.size();
            String[] f = fields(line, 7);
            int group = groupOf(f[1]);
            if (f[0].isEmpty() || group < 0 || f[3].isEmpty()) {
                throw new IllegalArgumentException("Broken emoji row " + (index + 1));
            }
            String tones = f[2];
            String[] written = tones.isEmpty() || "1".equals(tones) ? null : tones.split(" ", -1);
            entries.add(new Entry(f[0], group, "1".equals(tones), written, f[3],
                    f[4].isEmpty() ? "" : " " + f[4] + " ", f[5],
                    f[6].isEmpty() ? "" : " " + f[6] + " ", index));
        }
        return new EmojiData(lang, entries, bases);
    }

    /** The next line that is not empty, or null at the end. */
    @Nullable
    private static String nextLine(BufferedReader lines) throws IOException {
        String line;
        while ((line = lines.readLine()) != null) {
            if (!line.isEmpty()) return line;
        }
        return null;
    }

    /** The first {@code count} tab-separated fields, missing ones empty (no regex: a hot path). */
    private static String[] fields(String line, int count) {
        String[] out = new String[count];
        int start = 0;
        for (int i = 0; i < count; i++) {
            if (start > line.length()) {
                out[i] = "";
                continue;
            }
            int tab = line.indexOf('\t', start);
            int end = tab < 0 ? line.length() : tab;
            out[i] = line.substring(start, end);
            start = tab < 0 ? line.length() + 1 : tab + 1;
        }
        return out;
    }

    /** The group's index, or -1 (as JavaScript's Number() reads it, an empty column is 0). */
    private static int groupOf(String column) {
        String value = column.trim();
        if (value.isEmpty()) return 0;
        if (value.length() > 2) return -1;
        int group = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return -1;
            group = group * 10 + (c - '0');
        }
        return group < GROUPS.size() ? group : -1;
    }

    private static int[] parseBases(String column) {
        List<Integer> list = new ArrayList<>();
        for (String hex : column.split(" ")) {
            if (!hex.isEmpty()) list.add(Integer.parseInt(hex, 16));
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        Arrays.sort(out);
        return out;
    }

    /** "tr" or "en". */
    public String lang() {
        return lang;
    }

    /** Every emoji in the picker's order. */
    public List<Entry> entries() {
        return entries;
    }

    /** The emoji of one group (an index into {@link #GROUPS}), in order. */
    public List<Entry> group(int group) {
        return groups.get(group);
    }

    /** Whether a skin tone follows this code point (tones by rule). */
    public boolean isToneBase(int codePoint) {
        return Arrays.binarySearch(toneBases, codePoint) >= 0;
    }

    synchronized Map<String, Entry> byBare() {
        if (byBare == null) {
            // As the desktop's Map: a later entry with the same bare form wins (there is none).
            Map<String, Entry> map = new HashMap<>(entries.size() * 2);
            for (Entry entry : entries) map.put(entry.bare, entry);
            byBare = map;
        }
        return byBare;
    }

    /**
     * The entry of an emoji in any of its forms: any skin tone, with or without its VS16 (the red
     * heart U+2764 alone or with U+FE0F; a thumbs up with a tone); null when the data lacks it.
     */
    @Nullable
    public Entry entryOf(String emoji) {
        return byBare().get(EmojiTone.bareEmoji(emoji));
    }

    /**
     * The data's own form of an emoji given in any form, in its skin tone (the first tone in it):
     * the heart without VS16 gives the heart with it; a recent's bare rainbow flag (VS16 missing
     * inside) gives the fully qualified one. Null when the data does not have it.
     */
    @Nullable
    public String canonical(String emoji) {
        Entry entry = entryOf(emoji);
        if (entry == null) return null;
        int tone = EmojiTone.toneOf(emoji);
        return tone == 0 || !entry.hasTones() ? entry.emoji : EmojiTone.withTone(entry, tone, this);
    }

    /**
     * Whether the text is one complete emoji of the data, skin tones included, the way the friends
     * service takes a reaction: the emoji itself, or one VS16 short at its end (the service adds
     * it). A bare form missing a VS16 inside (a recent's rainbow flag) is not: see
     * {@link #canonical}.
     */
    public boolean isKnown(@Nullable String emoji) {
        if (emoji == null || emoji.isEmpty()) return false;
        String form = canonical(emoji);
        return form != null && (form.equals(emoji) || form.equals(emoji + (char) EmojiChars.VS16));
    }

    /** The emoji's name for the screen, its first letter in upper case (Turkish i gives U+0130). */
    public static String displayName(String name, String lang) {
        if (name.isEmpty()) return name;
        int first = name.codePointAt(0);
        String head = new String(Character.toChars(first));
        return head.toUpperCase(TURKISH.equals(lang) ? TR : Locale.ENGLISH)
                + name.substring(head.length());
    }

    /** One emoji of the picker. */
    public static final class Entry {
        /** The emoji as the picker inserts it without a skin tone (fully qualified). */
        public final String emoji;
        /** Its group, an index into {@link #GROUPS}. */
        public final int group;
        /** CLDR's name, in lower case as CLDR writes it. */
        public final String name;
        /** Folded search words, each between spaces (" kahkaha komik "), or "". */
        public final String words;
        /** Turkish data: the English name ("fire" finds the fire first); "" in the English data. */
        public final String fallbackName;
        /** Turkish data: English words the Turkish ones do not find, like {@link #words}. */
        public final String fallback;
        /** Place in the list. */
        public final int index;
        private final boolean toneRule;
        @Nullable
        private final List<String> writtenTones;
        /** Without skin tones and VS16 (EmojiTone.bareEmoji), to find it in any form. */
        final String bare;

        Entry(String emoji, int group, boolean toneRule, @Nullable String[] writtenTones,
                String name, String words, String fallbackName, String fallback, int index) {
            this.emoji = emoji;
            this.group = group;
            this.toneRule = toneRule;
            this.writtenTones = writtenTones == null
                    ? null : Collections.unmodifiableList(Arrays.asList(writtenTones));
            this.name = name;
            this.words = words;
            this.fallbackName = fallbackName;
            this.fallback = fallback;
            this.index = index;
            this.bare = EmojiTone.bareEmoji(emoji);
        }

        /** Its group's id ("smileys" and so on). */
        public String groupId() {
            return GROUPS.get(group);
        }

        /** Whether it takes a skin tone (the picker's tone applies to it). */
        public boolean hasTones() {
            return toneRule || writtenTones != null;
        }

        /** Its tones come by rule: a tone after each tone base, a VS16 right after one dropped. */
        public boolean tonesByRule() {
            return toneRule;
        }

        /** Its five tones written out (people holding hands keep the hands without one), or null. */
        @Nullable
        public List<String> writtenTones() {
            return writtenTones;
        }

        @Override
        public String toString() {
            return emoji;
        }
    }
}
