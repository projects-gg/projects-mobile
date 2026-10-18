package gg.projects.mobile.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** M14: player heads are drawn at whole pixels per skin pixel (PixelMath). */
public class PixelMathTest {
    @Test
    public void headsSnapToMultiplesOfEight() {
        assertEquals("24 dp at 2.625x (63 px)", 64, PixelMath.crispHeadSize(63));
        assertEquals("24 dp at 3x", 72, PixelMath.crispHeadSize(72));
        assertEquals("32 dp at 2.625x (84 px)", 88, PixelMath.crispHeadSize(84));
        assertEquals("24 dp at 2x", 48, PixelMath.crispHeadSize(48));
        assertEquals("never smaller than the face itself", 8, PixelMath.crispHeadSize(3));
        assertEquals(0, PixelMath.crispHeadSize(100) % 8);
    }
}
