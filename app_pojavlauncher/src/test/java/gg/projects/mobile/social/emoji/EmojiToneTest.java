package gg.projects.mobile.social.emoji;

import static gg.projects.mobile.social.emoji.EmojiDataTest.TR;
import static gg.projects.mobile.social.emoji.EmojiDataTest.entry;
import static gg.projects.mobile.social.emoji.EmojiDataTest.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Skin tones on the phone (the desktop's features/emoji/tone.test.ts). */
public class EmojiToneTest {
    private static String tone(int n) {
        return t("{" + Integer.toHexString(0x1F3FA + n) + "}");
    }

    private static final String VS16 = t("{FE0F}");
    private static final String ZWJ = t("{200D}");

    @Test
    public void addsTheToneAfterThePerson() {
        assertEquals(t("{1F44D}") + tone(3), EmojiTone.withTone(entry(TR, t("{1F44D}")), 3, TR));
        assertEquals(t("{1F44B}") + tone(5), EmojiTone.withTone(entry(TR, t("{1F44B}")), 5, TR));
    }

    @Test
    public void dropsThePresentationSelectorTheToneReplaces() {
        EmojiData.Entry up = entry(TR, t("{261D}") + VS16);
        assertEquals(t("{261D}") + tone(1), EmojiTone.withTone(up, 1, TR));
    }

    @Test
    public void tonesEveryPersonOfASequenceAndOnlyThePeople() {
        String woman = t("{1F469}");
        String man = t("{1F468}");
        String heart = t("{2764}");
        EmojiData.Entry couple = entry(TR, woman + ZWJ + heart + VS16 + ZWJ + man);
        assertEquals(woman + tone(2) + ZWJ + heart + VS16 + ZWJ + man + tone(2),
                EmojiTone.withTone(couple, 2, TR));
        String person = t("{1F9D1}");
        EmojiData.Entry hands = entry(TR, person + ZWJ + t("{1F91D}") + ZWJ + person);
        assertEquals(person + tone(4) + ZWJ + t("{1F91D}") + ZWJ + person + tone(4),
                EmojiTone.withTone(hands, 4, TR));
    }

    @Test
    public void leavesEmojiWithoutTonesAndTheDefaultToneAsTheyAre() {
        assertEquals(t("{1F600}"), EmojiTone.withTone(entry(TR, t("{1F600}")), 3, TR));
        assertEquals(t("{1F44D}"), EmojiTone.withTone(entry(TR, t("{1F44D}")), 0, TR));
        try {
            EmojiTone.withTone(entry(TR, t("{1F44D}")), 6, TR);
            fail();
        } catch (IllegalArgumentException expected) {
            // six tones, 0 to 5
        }
    }

    @Test
    public void stripsTonesAndSelectors() {
        assertEquals(t("{1F44D}"), EmojiTone.bareEmoji(t("{1F44D}") + tone(3)));
        assertEquals(t("{2764}"), EmojiTone.bareEmoji(t("{2764}") + VS16));
        assertEquals("abc", EmojiTone.bareEmoji("abc"));
        assertEquals("", EmojiTone.bareEmoji(VS16 + tone(1)));
    }

    @Test
    public void knowsTheSixTones() {
        for (int n = 0; n <= 5; n++) assertTrue(EmojiTone.isSkinTone(n));
        assertFalse(EmojiTone.isSkinTone(6));
        assertFalse(EmojiTone.isSkinTone(-1));
        assertEquals(0x1F3FB, EmojiTone.modifier(1));
        assertEquals(0x1F3FF, EmojiTone.modifier(5));
    }

    @Test
    public void readsTheToneOfAnEmojiAndShowsTheHandInIt() {
        assertEquals(0, EmojiTone.toneOf(t("{1F44D}")));
        assertEquals(4, EmojiTone.toneOf(t("{1F44D}") + tone(4)));
        assertEquals(t("{270B}"), EmojiTone.hand(0));
        assertEquals(t("{270B}") + tone(2), EmojiTone.hand(2));
    }
}
