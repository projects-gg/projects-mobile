package gg.projects.mobile.social.emoji;

import java.util.List;

/**
 * Skin tones (CHAT2 section 3.1; the desktop's features/emoji/tone.ts): one choice for the whole
 * picker, kept on the phone (EmojiRecents), the same tone on every person of an emoji (as Slack
 * and Discord do). Tone 0 is the default yellow; 1 to 5 are the Fitzpatrick modifiers U+1F3FB to
 * U+1F3FF, light to dark.
 */
public final class EmojiTone {
    private EmojiTone() {}

    public static final int DEFAULT = 0;
    /** The six choices, 0 to 5. */
    public static final int COUNT = 6;

    /** The raised hand (U+270B) the tone button shows in the chosen tone. */
    private static final int HAND = 0x270B;

    public static boolean isSkinTone(int value) {
        return value >= 0 && value < COUNT;
    }

    /** The modifier code point of a tone 1 to 5. */
    public static int modifier(int tone) {
        if (tone < 1 || tone >= COUNT) throw new IllegalArgumentException("No skin tone " + tone);
        return EmojiChars.MODIFIER_FIRST + tone - 1;
    }

    /** The emoji in the tone, or as it is when it has no tones (or the tone is the default). */
    public static String withTone(EmojiData.Entry entry, int tone, EmojiData data) {
        if (!isSkinTone(tone)) throw new IllegalArgumentException("No skin tone " + tone);
        if (tone == DEFAULT || !entry.hasTones()) return entry.emoji;
        List<String> written = entry.writtenTones();
        if (written != null) return tone - 1 < written.size() ? written.get(tone - 1) : entry.emoji;
        int modifier = modifier(tone);
        String emoji = entry.emoji;
        StringBuilder out = new StringBuilder(emoji.length() + 4);
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            i += Character.charCount(cp);
            out.appendCodePoint(cp);
            if (data.isToneBase(cp)) {
                out.appendCodePoint(modifier);
                // The tone takes the place of a presentation selector right after the base.
                if (i < emoji.length() && emoji.codePointAt(i) == EmojiChars.VS16) i++;
            }
        }
        return out.toString();
    }

    /** The raised hand in a tone (the tone button and its six choices, as the desktop's). */
    public static String hand(int tone) {
        if (!isSkinTone(tone)) throw new IllegalArgumentException("No skin tone " + tone);
        StringBuilder out = new StringBuilder().appendCodePoint(HAND);
        if (tone != DEFAULT) out.appendCodePoint(modifier(tone));
        return out.toString();
    }

    /** An emoji without its skin tones and VS16 selectors (to find it in the list). */
    public static String bareEmoji(String emoji) {
        StringBuilder out = null;
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            int next = i + Character.charCount(cp);
            boolean drop = cp == EmojiChars.VS16 || EmojiChars.isModifier(cp);
            if (drop && out == null) out = new StringBuilder(emoji.substring(0, i));
            else if (!drop && out != null) out.appendCodePoint(cp);
            i = next;
        }
        return out == null ? emoji : out.toString();
    }

    /** The first skin tone in an emoji (1 to 5), or 0 when it has none. */
    public static int toneOf(String emoji) {
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            if (EmojiChars.isModifier(cp)) return cp - EmojiChars.MODIFIER_FIRST + 1;
            i += Character.charCount(cp);
        }
        return DEFAULT;
    }
}
