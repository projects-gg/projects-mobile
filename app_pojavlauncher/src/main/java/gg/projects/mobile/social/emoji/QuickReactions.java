package gg.projects.mobile.social.emoji;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The quick reactions row (CHAT2 section 7; the desktop's features/social/chat/reactions.ts): six
 * emoji, the player's recent ones first, then the defaults (thumbs up, red heart, tears of joy,
 * loudly crying, fire, open mouth), each once. The middle finger is never a reaction, in any tone
 * (the service refuses it).
 */
public final class QuickReactions {
    private QuickReactions() {}

    /** How many the row offers. */
    public static final int COUNT = 6;

    /** The row's six when the player has no recent emoji. */
    public static final List<String> DEFAULTS = Collections.unmodifiableList(Arrays.asList(
            text(0x1F44D), text(0x2764, EmojiChars.VS16), text(0x1F602), text(0x1F62D),
            text(0x1F525), text(0x1F62E)));

    private static String text(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static String withoutVs16(String emoji) {
        int at = emoji.indexOf(EmojiChars.VS16);
        if (at < 0) return emoji;
        StringBuilder out = new StringBuilder(emoji.length());
        for (int i = 0; i < emoji.length(); i++) {
            char c = emoji.charAt(i);
            if (c != EmojiChars.VS16) out.append(c);
        }
        return out.toString();
    }

    /** The heart with and without VS16 is the same reaction (the service adds it); a tone is another. */
    public static boolean sameEmoji(String a, String b) {
        return withoutVs16(a).equals(withoutVs16(b));
    }

    /** The middle finger, in any tone: never a reaction (the picker says it cannot be one). */
    public static boolean isRefused(String emoji) {
        return emoji.codePointCount(0, emoji.length()) > 0
                && emoji.codePointAt(0) == EmojiChars.MIDDLE_FINGER;
    }

    /** The row: the recent emoji first (EmojiRecents.emoji()), then the defaults; six, each once. */
    public static List<String> of(List<String> recent) {
        return of(recent, null, COUNT);
    }

    /**
     * The row, each recent emoji in the data's own form when the data is there
     * ({@link EmojiData#canonical}): recents are kept bare, and a bare emoji that lost a VS16
     * inside (a rainbow flag, a heart on fire) is one the service would refuse. Without the data
     * the row is the desktop's.
     */
    public static List<String> of(List<String> recent, @Nullable EmojiData data) {
        return of(recent, data, COUNT);
    }

    public static List<String> of(List<String> recent, @Nullable EmojiData data, int count) {
        List<String> candidates = new ArrayList<>(recent.size() + DEFAULTS.size());
        for (String emoji : recent) {
            if (emoji == null || emoji.isEmpty()) continue;
            String form = data == null ? null : data.canonical(emoji);
            candidates.add(form == null ? emoji : form);
        }
        candidates.addAll(DEFAULTS);
        List<String> out = new ArrayList<>(count);
        for (String emoji : candidates) {
            if (out.size() >= count) break;
            boolean taken = false;
            for (String other : out) taken |= sameEmoji(other, emoji);
            if (taken || isRefused(emoji)) continue;
            out.add(emoji);
        }
        return Collections.unmodifiableList(out);
    }
}
