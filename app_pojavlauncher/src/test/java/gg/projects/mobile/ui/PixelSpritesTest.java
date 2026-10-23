package gg.projects.mobile.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** ICON2: the desktop's content sprites, drawn at whole pixels (PixelSprites, PixelSpriteDrawable). */
public class PixelSpritesTest {
    @Test
    public void everySpriteIsSixteenRowsOfKnownTones() {
        for (PixelSprites.Sprite sprite : PixelSprites.Sprite.values()) {
            String[] rows = sprite.rows();
            assertEquals(sprite + " rows", 16, rows.length);
            int drawn = 0;
            for (String row : rows) {
                assertEquals(sprite + " row " + row, 16, row.length());
                for (char tone : row.toCharArray()) {
                    assertTrue(sprite + " tone " + tone, tone == '.' || PixelSprites.TONES.indexOf(tone) >= 0);
                    if (tone != '.') drawn++;
                }
            }
            assertTrue(sprite + " draws something", drawn > 20);
        }
    }

    @Test
    public void shadowsStayInsideTheGrid() {
        for (PixelSprites.Sprite sprite : PixelSprites.Sprite.values()) {
            if (!sprite.hasShadow()) continue;
            String[] rows = sprite.rows();
            assertEquals(sprite + " last row", "................", rows[15]);
            for (String row : rows) assertEquals(sprite + " last column", '.', row.charAt(15));
        }
    }

    @Test
    public void runsDrawEveryPixelOnce() {
        for (PixelSprites.Sprite sprite : PixelSprites.Sprite.values()) {
            int pixels = 0;
            for (String row : sprite.rows()) pixels += row.replace(".", "").length();
            assertEquals(sprite + " runs", pixels, area(sprite.runs()));
            if (sprite.hasShadow()) assertEquals(sprite + " shadow", pixels, area(sprite.shadowRuns()));
            for (int[] run : sprite.runs()) {
                String part = sprite.rows()[run[1]].substring(run[0], run[0] + run[2]);
                for (char tone : part.toCharArray()) assertEquals(sprite + " one tone per run", (char) run[3], tone);
            }
        }
    }

    @Test
    public void spritePixelsAreWholeScreenPixels() {
        assertEquals("28 dp at 2.625x (73 px)", 4, PixelSprites.scale(73));
        assertEquals("32 dp at 2.625x (84 px)", 5, PixelSprites.scale(84));
        assertEquals("32 dp at 3x", 6, PixelSprites.scale(96));
        assertEquals("24 dp at 2x", 3, PixelSprites.scale(48));
        assertEquals("never smaller than the sprite itself", 1, PixelSprites.scale(9));
    }

    @Test
    public void quietIsOneGoldStepDarker() {
        assertEquals(PixelSprites.color('m', false), PixelSprites.color('l', true));
        assertEquals(PixelSprites.color('k', false), PixelSprites.color('o', true));
        assertEquals("the deep dirt, as the desktop's CSS mixes it", 0xFF573016, PixelSprites.color('k', false));
        assertEquals("flare stays", PixelSprites.color('f', false), PixelSprites.color('f', true));
        assertEquals("jade stays", PixelSprites.color('j', false), PixelSprites.color('j', true));
    }

    private static int area(List<int[]> runs) {
        int sum = 0;
        for (int[] run : runs) sum += run[2];
        return sum;
    }
}
