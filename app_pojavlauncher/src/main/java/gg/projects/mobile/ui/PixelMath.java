package gg.projects.mobile.ui;

/** Pixel rules of the interface (M14). Plain Java, JVM-tested. */
public final class PixelMath {
    private PixelMath() {}

    /**
     * The size a player head is drawn at: the nearest multiple of 8 pixels to {@code px} (at least
     * 8), so each of the face's 8 × 8 skin pixels becomes the same whole number of screen pixels.
     */
    public static int crispHeadSize(int px) {
        return Math.max(8, Math.round(px / 8f) * 8);
    }
}
