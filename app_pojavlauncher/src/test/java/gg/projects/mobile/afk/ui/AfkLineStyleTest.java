package gg.projects.mobile.afk.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import gg.projects.mobile.afk.api.AfkLine;

public final class AfkLineStyleTest {
    private static AfkLine line(String text, AfkLine.Span... spans) {
        return new AfkLine("a", 1, 0, AfkLine.Kind.CHAT, text, Arrays.asList(spans), null, false);
    }

    @Test
    public void theGamesBlueIsLiftedToReadAndPlainStretchesAreLeftToTheLine() {
        // The desktop's console-looks vector: "[Parti]  " in the game's blue, bold; the name in aqua; the rest plain.
        String text = "[Parti]  FrostyOtter88: lobiye   geliyorum";
        List<AfkLineStyle.Run> runs = AfkLineStyle.runs(line(text,
                new AfkLine.Span(0, 9, 0x5555ff, AfkLine.BOLD),
                new AfkLine.Span(9, 22, 0x55ffff, 0),
                new AfkLine.Span(22, text.length(), AfkLine.NO_COLOR, 0)));
        assertEquals("[0-9#7474ffb, 9-22#55ffff]", runs.toString());
    }

    @Test
    public void runsStayInsideTheTextInOrderAndNeverOverlap() {
        List<AfkLineStyle.Run> runs = AfkLineStyle.runs(line("abcdef",
                new AfkLine.Span(-3, 2, 0xffffff, 0),
                new AfkLine.Span(1, 4, AfkLine.NO_COLOR, AfkLine.ITALIC),
                new AfkLine.Span(4, 99, 0xff5555, AfkLine.UNDERLINED | AfkLine.STRIKETHROUGH),
                new AfkLine.Span(5, 6, 0xffffff, AfkLine.BOLD)));
        assertEquals("[0-2#ffffff, 2-4i, 4-6#ff5555us]", runs.toString());
    }

    @Test
    public void obfuscatedTextStaysPlainAndAnEmojiIsNeverSplit() {
        assertEquals("[]", AfkLineStyle.runs(line("gizli", new AfkLine.Span(0, 5, AfkLine.NO_COLOR, AfkLine.OBFUSCATED))).toString());
        String text = "a😀b";
        List<AfkLineStyle.Run> runs = AfkLineStyle.runs(line(text, new AfkLine.Span(0, 2, 0xffffff, 0), new AfkLine.Span(2, 4, 0x55ff55, 0)));
        assertEquals("[0-3#ffffff, 3-4#55ff55]", runs.toString());
    }

    @Test
    public void aLineDrawsAtMostSixtyFourRuns() {
        StringBuilder text = new StringBuilder();
        List<AfkLine.Span> spans = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            spans.add(new AfkLine.Span(i, i + 1, 0xffffff, 0));
            text.append('x');
        }
        AfkLine l = new AfkLine("a", 1, 0, AfkLine.Kind.SYSTEM, text.toString(), spans, null, false);
        assertEquals(AfkLineStyle.MAX_RUNS, AfkLineStyle.runs(l).size());
        assertTrue(AfkLineStyle.runs(new AfkLine("a", 1, 0, AfkLine.Kind.SYSTEM, "", spans, null, false)).isEmpty());
    }
}
