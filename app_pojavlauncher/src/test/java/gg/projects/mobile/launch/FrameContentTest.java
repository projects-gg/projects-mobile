package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/** M14: the launch screen lifts at the first real frame of the game (FrameContent). */
public class FrameContentTest {
    private static int[] filled(int argb) {
        int[] pixels = new int[64];
        Arrays.fill(pixels, argb);
        return pixels;
    }

    @Test
    public void blackFramesAreNotAPictureYet() {
        assertFalse(FrameContent.hasPicture(filled(0xFF000000)));
        assertFalse("noise of a black clear", FrameContent.hasPicture(filled(0xFF0A0A0A)));
        assertFalse(FrameContent.hasPicture(null));
    }

    @Test
    public void theLoadingScreensOfMinecraftAre() {
        assertTrue("Mojang red", FrameContent.hasPicture(filled(0xFFEF323D)));
        assertTrue("a dark loading screen mod", FrameContent.hasPicture(filled(0xFF14181C)));
        int[] monochrome = filled(0xFF000000);
        monochrome[27] = 0xFFFFFFFF;
        assertTrue("a white logo on black where the sample caught it", FrameContent.hasPicture(monochrome));
    }

    @Test
    public void aWhiteLogoOnBlackShowsInTheSixteenBySixteenSample() {
        // Monochrome mode: the logo is a quarter of the screen's height across its middle.
        int[] sample = new int[256];
        Arrays.fill(sample, 0xFF000000);
        assertFalse(FrameContent.hasPicture(sample));
        sample[7 * 16 + 9] = 0xFFFFFFFF;
        assertTrue(FrameContent.hasPicture(sample));
    }

    @Test
    public void aSampleFitsOneLogLine() {
        assertEquals("mean 239,50,61 max 239", FrameContent.describe(filled(0xFFEF323D)));
        int[] half = filled(0xFF000000);
        Arrays.fill(half, 0, 32, 0xFFFFFFFF);
        assertEquals("mean 128,128,128 max 255", FrameContent.describe(half));
        assertEquals("empty", FrameContent.describe(new int[0]));
        assertEquals("empty", FrameContent.describe(null));
    }

    @Test
    public void steadyBlackFramesCountOnceMinecraftIsStarting() {
        assertFalse(FrameContent.steadyFrames(0, 10_000, LaunchStages.MINECRAFT, 2500));
        assertFalse("not while Java or the mods are still loading",
                FrameContent.steadyFrames(1000, 10_000, LaunchStages.MODS, 2500));
        assertFalse(FrameContent.steadyFrames(1000, 3000, LaunchStages.MINECRAFT, 2500));
        assertTrue(FrameContent.steadyFrames(1000, 3500, LaunchStages.MINECRAFT, 2500));
    }
}
