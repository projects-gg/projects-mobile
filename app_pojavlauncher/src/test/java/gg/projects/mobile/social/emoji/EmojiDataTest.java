package gg.projects.mobile.social.emoji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The emoji data on the phone (the desktop's features/emoji/data.test.ts): the copied TSVs read
 * the same way, in both languages, plus the lookups the chat screens use.
 */
public class EmojiDataTest {
    static final EmojiData TR = read("tr");
    static final EmojiData EN = read("en");

    static EmojiData read(String lang) {
        try (InputStream in = new FileInputStream("src/main/assets/" + EmojiData.assetPath(lang))) {
            return EmojiData.parse(in);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** "ate{15F}" gives the text with U+015F: the source stays ASCII. */
    static String t(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                int end = text.indexOf('}', i);
                out.appendCodePoint(Integer.parseInt(text.substring(i + 1, end), 16));
                i = end;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static EmojiData.Entry entry(EmojiData data, String emoji) {
        for (EmojiData.Entry e : data.entries()) {
            if (e.emoji.equals(emoji)) return e;
        }
        throw new AssertionError("No " + emoji);
    }

    @Test
    public void listsTheSameEmojiInBothLanguagesInUnicodesOrderAndGroups() {
        assertEquals("tr", TR.lang());
        assertEquals("en", EN.lang());
        assertTrue(TR.entries().size() > 1900);
        assertEquals(TR.entries().size(), EN.entries().size());
        for (int i = 0; i < TR.entries().size(); i++) {
            assertEquals(TR.entries().get(i).emoji, EN.entries().get(i).emoji);
            assertEquals(i, TR.entries().get(i).index);
        }
        assertEquals(t("{1F600}"), TR.entries().get(0).emoji);
        // Groups come one after another, each once, in the picker's order.
        List<String> order = new ArrayList<>();
        for (EmojiData.Entry e : TR.entries()) {
            if (order.isEmpty() || !order.get(order.size() - 1).equals(e.groupId())) order.add(e.groupId());
        }
        assertEquals(EmojiData.GROUPS, order);
        int total = 0;
        for (int g = 0; g < EmojiData.GROUPS.size(); g++) {
            for (EmojiData.Entry e : TR.group(g)) assertEquals(g, e.group);
            total += TR.group(g).size();
        }
        assertEquals(TR.entries().size(), total);
    }

    @Test
    public void leavesOutTheMiddleFingerSubdivisionFlagsComponentsAndTonedForms() {
        for (EmojiData.Entry e : TR.entries()) {
            int[] points = EmojiChars.codePoints(e.emoji);
            for (int cp : points) {
                assertTrue(e.emoji, cp != 0x1F595);
                assertTrue(e.emoji, cp < 0xE0000);
                assertFalse(e.emoji, cp >= 0x1F3FB && cp <= 0x1F3FF);
            }
            assertFalse(e.emoji, points.length == 1 && points[0] >= 0x1F9B0 && points[0] <= 0x1F9B3);
        }
        assertNotNull(TR.entryOf(t("{1F1F9}{1F1F7}")));
    }

    @Test
    public void namesEveryEmojiAndKeepsItsSearchWordsFolded() {
        for (EmojiData data : new EmojiData[] {TR, EN}) {
            for (EmojiData.Entry e : data.entries()) {
                assertFalse(e.emoji, e.name.trim().isEmpty());
                for (String word : (e.words + " " + e.fallback).split(" ")) {
                    if (!word.isEmpty()) assertEquals(e.emoji + " " + word, word, EmojiSearch.fold(word));
                }
            }
        }
        for (EmojiData.Entry e : EN.entries()) {
            assertEquals("", e.fallback);
            assertEquals("", e.fallbackName);
        }
        EmojiData.Entry joy = entry(TR, t("{1F602}"));
        assertEquals(t("sevin{E7} g{F6}zya{15F}lar{131}"), joy.name);
        assertTrue(joy.words.startsWith(" aglamak ") && joy.words.endsWith(" nese "));
        assertEquals("face with tears of joy", joy.fallbackName);
        assertTrue(joy.fallback.startsWith(" crying ") && joy.fallback.endsWith(" roflmao "));
    }

    @Test
    public void givesSkinTonesToThePeopleAndTheirHands() {
        int toned = 0;
        for (EmojiData.Entry e : TR.entries()) {
            if (!e.hasTones()) continue;
            toned++;
            if (!e.tonesByRule()) assertEquals(5, e.writtenTones().size());
        }
        assertTrue(toned > 300);
        assertTrue(entry(TR, t("{1F44D}")).tonesByRule());
        assertFalse(entry(TR, t("{1F600}")).hasTones());
        assertNull(entry(TR, t("{1F600}")).writtenTones());
        assertTrue(TR.isToneBase(0x1F44D));
        assertFalse(TR.isToneBase(0x1F600));
    }

    @Test
    public void refusesAFileOfAnotherKind() {
        try {
            EmojiData.parse("hello\tworld\n");
            fail();
        } catch (IllegalArgumentException expected) {
            // a file of another format
        }
        try {
            EmojiData.parse("PL-EMOJI\t1\ttr\t\t\t\t\nx\t9\t\tname\n");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("row 1"));
        }
    }

    @Test
    public void readsCrlfLineEndsAsTheDesktopDoes() {
        EmojiData data = EmojiData.parse("PL-EMOJI\t1\ten\t\t\t\t261D\r\n" + t("{261D}{FE0F}") + "\t1\t1\tindex pointing up\tpoint\r\n\r\n");
        assertEquals(1, data.entries().size());
        assertEquals(" point ", data.entries().get(0).words);
        assertTrue(data.isToneBase(0x261D));
    }

    @Test
    public void writesANameWithItsFirstLetterInUpperCaseTurkishIIncluded() {
        assertEquals(t("Sevin{E7} g{F6}zya{15F}lar{131}"),
                EmojiData.displayName(t("sevin{E7} g{F6}zya{15F}lar{131}"), "tr"));
        assertEquals(t("{130}{15F}aret"), EmojiData.displayName(t("i{15F}aret"), "tr"));
        assertEquals("Index pointing up", EmojiData.displayName("index pointing up", "en"));
        assertEquals("", EmojiData.displayName("", "tr"));
    }

    @Test
    public void findsAnEmojiInAnyOfItsForms() {
        EmojiData.Entry heart = entry(TR, t("{2764}{FE0F}"));
        assertSame(heart, TR.entryOf(t("{2764}")));
        assertSame(heart, TR.entryOf(t("{2764}{FE0F}")));
        EmojiData.Entry up = entry(TR, t("{1F44D}"));
        assertSame(up, TR.entryOf(t("{1F44D}{1F3FD}")));
        assertNull(TR.entryOf("not an emoji"));
        assertNull(TR.entryOf(t("{1F595}")));
    }

    @Test
    public void givesTheDatasOwnFormInTheSameTone() {
        assertEquals(t("{2764}{FE0F}"), TR.canonical(t("{2764}")));
        // A recent's bare rainbow flag lost the VS16 inside; the data's form has it.
        assertEquals(t("{1F3F3}{FE0F}{200D}{1F308}"), TR.canonical(t("{1F3F3}{200D}{1F308}")));
        assertEquals(t("{1F44D}{1F3FD}"), TR.canonical(t("{1F44D}{1F3FD}")));
        assertEquals(t("{261D}{1F3FC}"), TR.canonical(t("{261D}{FE0F}{1F3FC}")));
        assertEquals(t("{1F600}"), TR.canonical(t("{1F600}{1F3FD}")));
        assertNull(TR.canonical("x"));
    }

    @Test
    public void knowsTheEmojiTheServiceTakesAsReactions() {
        // Every emoji of the data and every tone the picker gives.
        for (EmojiData.Entry e : TR.entries()) {
            assertTrue(e.emoji, TR.isKnown(e.emoji));
            if (!e.hasTones()) continue;
            for (int tone = 1; tone <= 5; tone++) {
                String toned = EmojiTone.withTone(e, tone, TR);
                assertTrue(toned, TR.isKnown(toned));
            }
        }
        // One VS16 short at the end: the service adds it.
        assertTrue(TR.isKnown(t("{2764}")));
        assertTrue(TR.isKnown(t("{263A}")));
        // Not one emoji, not of the data, or a VS16 missing inside.
        assertFalse(TR.isKnown(t("{1F602}{1F602}")));
        assertFalse(TR.isKnown(t("{1F595}")));
        assertFalse(TR.isKnown(t("{1F595}{1F3FD}")));
        assertFalse(TR.isKnown(t("{1F3F3}{200D}{1F308}")));
        assertFalse(TR.isKnown("a"));
        assertFalse(TR.isKnown(""));
        assertFalse(TR.isKnown(null));
        // Mixed tones are not a form the picker gives.
        assertFalse(TR.isKnown(t("{1F9D1}{1F3FB}{200D}{1F91D}{200D}{1F9D1}{1F3FF}")));
    }

    @Test
    public void picksTheAssetOfTheLanguage() {
        assertEquals("emoji/emoji.tr.tsv", EmojiData.assetPath("tr"));
        assertEquals("emoji/emoji.en.tsv", EmojiData.assetPath("en"));
        assertEquals("emoji/emoji.tr.tsv", EmojiData.assetPath("de"));
    }

    @Test
    public void keepsTheToneBasesInStepWithTheBigEmojiTable() {
        // EmojiChars' tone bases are the data's and the middle finger, nothing else.
        for (int cp = 0x2600; cp <= 0x1FAFF; cp++) {
            boolean inData = TR.isToneBase(cp) || cp == 0x1F595;
            assertEquals(Integer.toHexString(cp), inData, EmojiChars.isModifierBase(cp));
        }
        // Its flag list is the data's flags.
        StringBuilder flags = new StringBuilder();
        for (EmojiData.Entry e : TR.group(EmojiData.GROUPS.indexOf("flags"))) {
            int[] cps = EmojiChars.codePoints(e.emoji);
            if (cps.length == 2 && EmojiChars.isRegional(cps[0]) && EmojiChars.isRegional(cps[1])) {
                flags.append((char) ('A' + cps[0] - 0x1F1E6)).append((char) ('A' + cps[1] - 0x1F1E6));
            }
        }
        assertEquals(EmojiChars.FLAGS, flags.toString());
    }
}
