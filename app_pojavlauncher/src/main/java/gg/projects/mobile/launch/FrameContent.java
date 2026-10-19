package gg.projects.mobile.launch;

/**
 * Whether a tiny sample of the game's surface shows a picture yet (M14, the launch screen). A
 * renderer may present black frames while it sets up; Minecraft's first real frame is its loading
 * screen (Mojang red, or black with a white logo in monochrome mode) or, where it presents nothing
 * while it loads, its title screen. Plain Java, JVM-tested.
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

    /** "mean 239,50,61 max 255": a sample in one short log line (the launch screen's trace). */
    public static String describe(int[] argb) {
        if (argb == null || argb.length == 0) return "empty";
        long r = 0, g = 0, b = 0;
        int max = 0;
        for (int p : argb) {
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            r += pr;
            g += pg;
            b += pb;
            max = Math.max(max, Math.max(pr, Math.max(pg, pb)));
        }
        int n = argb.length;
        return "mean " + Math.round((double) r / n) + "," + Math.round((double) g / n) + ","
                + Math.round((double) b / n) + " max " + max;
    }
}
