package gg.projects.mobile.launch;

/**
 * Whether a tiny sample of the game's surface shows a picture yet (M14, the launch screen). A
 * renderer may present black frames while it sets up; Minecraft's first real frame is its loading
 * screen (Mojang red, or black with a white logo in monochrome mode). Plain Java, JVM-tested.
 */
public final class FrameContent {
    private FrameContent() {}

    /** Brighter than this in any channel counts as drawn (black clears stay at 0). */
    static final int THRESHOLD = 12;

    /** True when any pixel of {@code argb} (as from Bitmap#getPixels) is not black. */
    public static boolean hasPicture(int[] argb) {
        if (argb == null) return false;
        for (int p : argb) {
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (r > THRESHOLD || g > THRESHOLD || b > THRESHOLD) return true;
        }
        return false;
    }

    /**
     * Frames keep coming but stay black (a dark loading screen, a monochrome logo the sample missed):
     * once Minecraft itself is starting and frames have come for {@code steadyMs}, the game is
     * drawing its own screen.
     */
    public static boolean steadyFrames(long firstFrameAt, long now, int stage, long steadyMs) {
        return firstFrameAt > 0 && stage >= LaunchStages.MINECRAFT && now - firstFrameAt >= steadyMs;
    }
}
