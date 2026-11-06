package gg.projects.mobile.afk.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The desktop's vectors (apps/launcher/src/shell/console-ground.test.ts): both consoles draw the same colours. */
public final class ConsoleColorsTest {
    /** The game's 16 text colours, §0–§f. */
    private static final int[] LEGACY = {
            0x000000, 0x0000aa, 0x00aa00, 0x00aaaa, 0xaa0000, 0xaa00aa, 0xffaa00, 0xaaaaaa,
            0x555555, 0x5555ff, 0x55ff55, 0x55ffff, 0xff5555, 0xff55ff, 0xffff55, 0xffffff,
    };

    @Test
    public void drawsEachOfTheGamesColoursAsTheDesktopDoes() {
        int[] console = {
                0x858585, 0x7474ff, 0x00aa00, 0x00aaaa, 0xff2d2d, 0xea00ea, 0xffaa00, 0xaaaaaa,
                0x858585, 0x7474ff, 0x55ff55, 0x55ffff, 0xff5555, 0xff55ff, 0xffff55, 0xffffff,
        };
        int[] plate = {
                0x8a8a8a, 0x7b7bff, 0x00aa00, 0x00aaaa, 0xff4141, 0xf300f3, 0xffaa00, 0xaaaaaa,
                0x8a8a8a, 0x7b7bff, 0x55ff55, 0x55ffff, 0xff5555, 0xff55ff, 0xffff55, 0xffffff,
        };
        for (int i = 0; i < LEGACY.length; i++) {
            assertEquals(hex(LEGACY[i]) + " on the console", hex(console[i]), hex(ConsoleColors.readable(LEGACY[i])));
            assertEquals(hex(LEGACY[i]) + " on a plate", hex(plate[i]), hex(ConsoleColors.readable(LEGACY[i], ConsoleColors.GROUND_PLATE)));
        }
    }

    @Test
    public void keepsAColourThatReadsAndLiftsTheRestInTheirOwnHue() {
        int[] extra = {0x120000, 0x2a0a4a, 0x3355aa, 0x808000};
        for (int ground : new int[]{ConsoleColors.GROUND_CONSOLE, ConsoleColors.GROUND_PLATE}) {
            for (int[] set : new int[][]{LEGACY, extra}) {
                for (int in : set) {
                    int out = ConsoleColors.readable(in, ground);
                    assertTrue(hex(in) + " reads", ConsoleColors.contrast(out, ground) >= 4.5);
                    if (ConsoleColors.contrast(in, ground) >= 4.5) assertEquals(hex(in), hex(out));
                    double[] before = ConsoleColors.hslOf(in);
                    double[] after = ConsoleColors.hslOf(out);
                    assertTrue(after[2] >= before[2]);
                    if (before[1] > 0) {
                        assertTrue(hex(in) + " keeps its hue", Math.abs(after[0] - before[0]) < 2);
                        assertTrue(hex(in) + " keeps its saturation", Math.abs(after[1] - before[1]) < 0.03);
                    }
                }
            }
        }
    }

    @Test
    public void ignoresAnAlphaByteAndKeepsTheBlueOfTheShellTest() {
        assertEquals(hex(0x7474ff), hex(ConsoleColors.readable(0xff5555ff)));
    }

    private static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xffffff);
    }
}
