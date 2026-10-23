package gg.projects.mobile.social.emoji;

import androidx.annotation.Nullable;

/**
 * Emoji in the composer and in a bubble (CHAT2 sections 3.1 and 3.3; the desktop's
 * features/emoji/insert.ts): an emoji goes in at the caret only when the whole text still fits
 * the limit, counted in code points as the service counts; a message of only one to three emoji
 * is drawn large and without a bubble. Needs no emoji data, so a bubble can ask before the
 * picker's data is loaded.
 */
public final class EmojiText {
    private EmojiText() {}

    /** The longest text (UTF-16 units) still looked at for large emoji. */
    private static final int BIG_MAX_LENGTH = 64;
    private static final int BIG_MAX_COUNT = 3;

    /** Code points of a text (what the chat's limit counts). */
    public static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** The field's text after an insertion and the caret after what went in. */
    public static final class Insertion {
        public final String value;
        /** A UTF-16 offset, as EditText takes it. */
        public final int caret;

        Insertion(String value, int caret) {
            this.value = value;
            this.caret = caret;
        }
    }

    /**
     * The field's text with {@code insert} in place of the selection {@code [start, end)} (UTF-16
     * offsets, either way round, clamped to the text) and the caret after it; null when the
     * result would pass {@code max} code points.
     */
    @Nullable
    public static Insertion insert(String value, int start, int end, String insert, int max) {
        int from = Math.max(0, Math.min(Math.min(start, end), value.length()));
        int to = Math.min(value.length(), Math.max(Math.max(start, end), from));
        String next = value.substring(0, from) + insert + value.substring(to);
        if (codePoints(next) > max) return null;
        return new Insertion(next, from + insert.length());
    }

    /**
     * How many emoji a message is made of when it is only one to three emoji (white space between
     * them allowed): such a message is drawn large and without a bubble. 0 for anything else.
     */
    public static int bigEmojiCount(@Nullable String text) {
        if (text == null) return 0;
        String trimmed = EmojiChars.jsTrim(text);
        if (trimmed.isEmpty() || trimmed.length() > BIG_MAX_LENGTH) return 0;
        int count = 0;
        for (int i = 0; i < trimmed.length(); ) {
            int end = EmojiChars.graphemeEnd(trimmed, i);
            String segment = trimmed.substring(i, end);
            i = end;
            if (EmojiChars.isAllSpace(segment)) continue;
            count++;
            if (count > BIG_MAX_COUNT || !EmojiChars.isEmoji(segment)) return 0;
        }
        return count;
    }
}
