package gg.projects.mobile.social.emoji;

import static gg.projects.mobile.social.emoji.EmojiDataTest.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The composer's insertion and large emoji (the desktop's features/emoji/insert.test.ts). */
public class EmojiTextTest {
    private static final String ZWJ = t("{200D}");
    private static final String VS16 = t("{FE0F}");
    private static final String FAMILY = t("{1F468}") + ZWJ + t("{1F469}") + ZWJ + t("{1F467}") + ZWJ + t("{1F466}");
    private static final String WAVE = t("{1F44B}");
    private static final String FIRE = t("{1F525}");
    private static final String JOY = t("{1F602}");

    private static void assertInsertion(String value, int caret, EmojiText.Insertion insertion) {
        assertEquals(value, insertion.value);
        assertEquals(caret, insertion.caret);
    }

    private static String repeat(String text, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(text);
        return out.toString();
    }

    @Test
    public void putsTheEmojiAtTheCaretAndTheCaretAfterIt() {
        assertInsertion("selam" + WAVE, 7, EmojiText.insert("selam", 5, 5, WAVE, 300));
        assertInsertion(WAVE + "selam", 2, EmojiText.insert("selam", 0, 0, WAVE, 300));
    }

    @Test
    public void replacesASelectionWhicheverWayItWasMade() {
        String text = t("bu {E7}ok iyi");
        assertInsertion("bu " + FIRE + " iyi", 5, EmojiText.insert(text, 3, 6, FIRE, 300));
        assertInsertion("bu " + FIRE + " iyi", 5, EmojiText.insert(text, 6, 3, FIRE, 300));
    }

    @Test
    public void keepsACaretBeyondTheTextAtItsEnd() {
        assertInsertion("ab" + JOY, 4, EmojiText.insert("ab", 9, 9, JOY, 300));
        assertInsertion(JOY + "ab", 2, EmojiText.insert("ab", -3, -1, JOY, 300));
    }

    @Test
    public void refusesWhatWouldPassTheLimitCountedInCodePoints() {
        String full = repeat("a", 299);
        assertEquals(full + JOY, EmojiText.insert(full, 299, 299, JOY, 300).value);
        assertNull(EmojiText.insert(full, 299, 299, FAMILY, 300));
        assertEquals(7, EmojiText.codePoints(FAMILY));
    }

    @Test
    public void countsAMessageOfOneToThreeEmoji() {
        assertEquals(1, EmojiText.bigEmojiCount(JOY));
        assertEquals(3, EmojiText.bigEmojiCount(JOY + JOY + JOY));
        assertEquals(2, EmojiText.bigEmojiCount(" " + JOY + " " + FIRE + " "));
        assertEquals(1, EmojiText.bigEmojiCount(t("{2764}") + VS16));
        assertEquals(1, EmojiText.bigEmojiCount(t("{1F1F9}{1F1F7}")));
        assertEquals(1, EmojiText.bigEmojiCount(FAMILY));
        assertEquals(1, EmojiText.bigEmojiCount("1" + VS16 + t("{20E3}")));
    }

    @Test
    public void isZeroForTextForMoreThanThreeAndForNothing() {
        assertEquals(0, EmojiText.bigEmojiCount(JOY + JOY + JOY + JOY));
        assertEquals(0, EmojiText.bigEmojiCount("haha " + JOY));
        assertEquals(0, EmojiText.bigEmojiCount("1"));
        assertEquals(0, EmojiText.bigEmojiCount(""));
        assertEquals(0, EmojiText.bigEmojiCount("   "));
        assertEquals(0, EmojiText.bigEmojiCount(null));
    }

    @Test
    public void readsEmojiTheWayTheDesktopsRgiTestDoes() {
        // A tone after a person, mixed tones, the lone tone swatch, the middle finger.
        assertEquals(1, EmojiText.bigEmojiCount(t("{1F44D}{1F3FD}")));
        assertEquals(1, EmojiText.bigEmojiCount(t("{1F469}{1F3FB}{200D}{2764}{FE0F}{200D}{1F468}{1F3FF}")));
        assertEquals(1, EmojiText.bigEmojiCount(t("{1F3FD}")));
        assertEquals(2, EmojiText.bigEmojiCount(t("{1F595} {1F595}{1F3FD}")));
        // A pictograph alone, with or without VS16; text style is not an emoji.
        assertEquals(1, EmojiText.bigEmojiCount(t("{263A}")));
        assertEquals(1, EmojiText.bigEmojiCount(t("{A9}")));
        assertEquals(0, EmojiText.bigEmojiCount(t("{263A}{FE0E}")));
        // A keycap needs its VS16; a tone after a face is not one emoji.
        assertEquals(0, EmojiText.bigEmojiCount("1" + t("{20E3}")));
        assertEquals(0, EmojiText.bigEmojiCount(t("{1F600}{1F3FD}")));
        // In a ZWJ sequence a text-default pictograph needs its VS16 (the judge's scales).
        assertEquals(1, EmojiText.bigEmojiCount(t("{1F468}{200D}{2696}{FE0F}")));
        assertEquals(0, EmojiText.bigEmojiCount(t("{1F468}{200D}{2696}")));
        assertEquals(1, EmojiText.bigEmojiCount(t("{26F9}{1F3FD}{200D}{2640}{FE0F}")));
        // Flags of a region, and the three subdivision flags; not a lone letter or "AA".
        assertEquals(0, EmojiText.bigEmojiCount(t("{1F1E6}{1F1E6}")));
        assertEquals(0, EmojiText.bigEmojiCount(t("{1F1F9}")));
        assertEquals(1, EmojiText.bigEmojiCount(t("{1F3F4}{E0067}{E0062}{E0065}{E006E}{E0067}{E007F}")));
        assertEquals(0, EmojiText.bigEmojiCount(t("{1F3F4}{E0075}{E0073}{E0063}{E0061}{E007F}")));
        // Line breaks and other white space between them.
        assertEquals(3, EmojiText.bigEmojiCount(JOY + "\n" + FIRE + "\r\n" + t("{3000}") + WAVE));
        // Longer than 64 UTF-16 units is never large.
        assertEquals(0, EmojiText.bigEmojiCount(FAMILY + repeat(" ", 60) + JOY));
    }
}
