package gg.projects.mobile.social.emoji;

import static gg.projects.mobile.social.emoji.EmojiDataTest.EN;
import static gg.projects.mobile.social.emoji.EmojiDataTest.TR;
import static gg.projects.mobile.social.emoji.EmojiDataTest.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Emoji search on the phone (the desktop's features/emoji/search.test.ts, case for case). */
public class EmojiSearchTest {
    private static List<String> top(EmojiData data, String query, int count) {
        List<String> out = new ArrayList<>();
        for (EmojiData.Entry entry : EmojiSearch.search(data, query)) {
            if (out.size() == count) break;
            out.add(entry.emoji);
        }
        return out;
    }

    private static List<String> top(EmojiData data, String query) {
        return top(data, query, 5);
    }

    private static List<String> all(EmojiData data, String query) {
        return top(data, query, Integer.MAX_VALUE);
    }

    private static final String FIRE = t("{1F525}");
    private static final String RED_HEART = t("{2764}{FE0F}");
    private static final String JOY = t("{1F602}");

    @Test
    public void findsNothingForAnEmptyQuery() {
        assertEquals(Collections.emptyList(), EmojiSearch.search(TR, ""));
        assertEquals(Collections.emptyList(), EmojiSearch.search(TR, "   "));
        assertEquals(Collections.emptyList(), EmojiSearch.search(TR, " -- "));
    }

    @Test
    public void putsTheEmojiWhoseNameIsTheQueryFirst() {
        assertEquals(FIRE, top(TR, t("ate{15F}")).get(0));
        assertEquals(RED_HEART, top(TR, t("k{131}rm{131}z{131} kalp")).get(0));
        assertEquals(FIRE, top(EN, "fire").get(0));
        assertEquals(t("{1F408}"), top(TR, "kedi").get(0));
    }

    @Test
    public void putsTheEmojiChatsUseMostFirstAmongEqualFinds() {
        assertEquals(RED_HEART, top(TR, "kalp").get(0));
        assertTrue(top(TR, "kalp").contains(t("{1FAC0}")));
        assertEquals(FIRE, top(TR, "fire").get(0));
        assertEquals(JOY, top(TR, "kahkaha").get(0));
    }

    @Test
    public void putsNamesWithTheWordItselfBeforeNamesWithLongerWords() {
        List<String> eye = top(TR, t("g{F6}z"), 10);
        assertEquals(t("{1F441}{FE0F}"), eye.get(0));
        assertTrue(eye.contains(t("{1F440}")));
        assertTrue(eye.indexOf(t("{1F609}")) < eye.indexOf(t("{1F440}")));
    }

    @Test
    public void foldsTurkishCaseDottedAndDotlessIAndMarks() {
        assertTrue(top(TR, "gulmek").contains(JOY));
        assertEquals(top(TR, "gulmek"), top(TR, t("G{DC}LMEK")));
        assertEquals(RED_HEART, top(TR, "KIRMIZI KALP").get(0));
        assertEquals(FIRE, top(TR, "ates").get(0));
        assertTrue(top(TR, "sirit").contains(t("{1F600}")));
        assertEquals(top(TR, "sirit"), top(TR, "SIRIT"));
        assertEquals("iris", EmojiSearch.fold("Iris"));
        assertEquals("istanbul", EmojiSearch.fold(t("{130}stanbul")));
        assertEquals("gulmek sirit", EmojiSearch.fold(t("g{FC}lmek S{131}r{131}t")));
    }

    @Test
    public void findsEnglishWordsInATurkishSearchAfterTheTurkishOnes() {
        assertTrue(top(TR, "fire").contains(FIRE));
        assertTrue(top(TR, "skull").contains(t("{1F480}")));
        List<EmojiData.Entry> kalp = EmojiSearch.search(TR, "kalp");
        assertTrue(kalp.size() > 5);
        assertTrue(kalp.get(0).name.contains("kalp"));
    }

    @Test
    public void findsFlagsByTheirCountry() {
        String turkey = t("{1F1F9}{1F1F7}");
        assertTrue(top(TR, t("t{FC}rkiye")).contains(turkey));
        assertTrue(top(TR, "turk").contains(turkey));
        assertTrue(top(EN, "turkey").contains(turkey));
    }

    @Test
    public void needsEveryWordOfTheQuery() {
        List<String> both = all(TR, t("k{131}rm{131}z{131} kalp"));
        assertTrue(both.contains(RED_HEART));
        assertFalse(both.contains(t("{1F499}")));
    }

    @Test
    public void findsAPastedEmojiWithOrWithoutItsSkinTone() {
        assertEquals(Arrays.asList(JOY), top(TR, JOY));
        assertEquals(Arrays.asList(t("{1F44D}")), top(TR, t("{1F44D}{1F3FD}")));
        assertEquals(Arrays.asList(RED_HEART), top(TR, RED_HEART));
        assertEquals(Arrays.asList(RED_HEART), top(TR, t("{2764}")));
        // The first emoji of what was pasted; text before it finds nothing.
        assertEquals(Arrays.asList(FIRE), top(TR, " " + FIRE + JOY + " "));
        assertEquals(Collections.emptyList(), top(TR, "a" + FIRE));
        assertEquals(Arrays.asList(t("{1F1F9}{1F1F7}")), top(TR, t("{1F1F9}{1F1F7}{1F1F9}{1F1F7}")));
    }

    @Test
    public void keepsUnicodesOrderWithinARankAndNeverRepeats() {
        List<EmojiData.Entry> found = EmojiSearch.search(TR, t("y{FC}z"));
        assertEquals(found.size(), new HashSet<>(found).size());
        assertTrue(found.size() > 50);
    }

    @Test
    public void foldsTheNamesAheadWhenAsked() {
        EmojiData fresh = EmojiDataTest.read("tr");
        EmojiSearch.prepare(fresh);
        assertEquals(top(TR, "kalp", 20), top(fresh, "kalp", 20));
    }

    @Test
    public void matchesInsideAWordFromThreeLettersOnLast() {
        List<EmojiData.Entry> found = EmojiSearch.search(TR, "ahkah");
        assertTrue(found.size() > 0);
        for (EmojiData.Entry e : found) assertTrue(e.emoji, e.name.contains("ahkah") || e.words.contains("ahkah"));
        assertEquals(Collections.emptyList(), EmojiSearch.search(TR, "hk"));
    }
}
