package gg.projects.mobile.afk.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A server's colour on the phone AFK console (CONSOLE-COLOR, the desktop's {@code groundColor} in
 * {@code packages/ui/src/components/console/ground.ts}): a colour that reads at 4.5:1 on its ground is drawn exactly as
 * sent; one that does not keeps its hue and saturation and takes the least lightness (in steps of 0.1 %) that reads.
 * The grounds are the desktop's, each at its lightest, so a colour that reads there reads on the phone's darker panels
 * too and both consoles draw the same colours. Plain Java, JVM-tested with the desktop's vectors.
 */
public final class ConsoleColors {
    private ConsoleColors() {}

    /** The console's own ground (the desktop's glass sheet at its lightest). */
    public static final int GROUND_CONSOLE = 0x1f1d23;
    /** A plate on it (a chosen row, a button). */
    public static final int GROUND_PLATE = 0x27222c;

    /** WCAG's floor for text (design.md §8). */
    private static final double FLOOR = 4.5;

    private static final Map<Long, Integer> SEEN = new LinkedHashMap<>();

    /** {@code rgb} ({@code 0xRRGGBB}) drawn on the console's ground: as sent when it reads, else lifted in its own hue. */
    public static int readable(int rgb) {
        return readable(rgb, GROUND_CONSOLE);
    }

    /** {@code rgb} ({@code 0xRRGGBB}) on {@code ground} ({@code 0xRRGGBB}). */
    public static synchronized int readable(int rgb, int ground) {
        rgb &= 0xffffff;
        ground &= 0xffffff;
        long key = ((long) ground << 24) | rgb;
        Integer hit = SEEN.get(key);
        if (hit != null) return hit;
        int out = rgb;
        if (contrast(rgb, ground) < FLOOR) {
            double[] hsl = hslOf(rgb);
            out = 0xffffff;
            for (int step = (int) Math.ceil(hsl[2] * 1000); step <= 1000; step++) {
                int next = rgbOfHsl(hsl[0], hsl[1], step / 1000.0);
                if (contrast(next, ground) >= FLOOR) {
                    out = next;
                    break;
                }
            }
        }
        if (SEEN.size() > 512) SEEN.clear();
        SEEN.put(key, out);
        return out;
    }

    /** The contrast of two colours ({@code 0xRRGGBB}), as WCAG measures it. */
    public static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG relative luminance of an sRGB colour ({@code 0xRRGGBB}). */
    public static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 255) + 0.7152 * channel((rgb >> 8) & 255) + 0.0722 * channel(rgb & 255);
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Hue (degrees), saturation and lightness (0–1) of {@code 0xRRGGBB}. */
    public static double[] hslOf(int rgb) {
        double r = ((rgb >> 16) & 255) / 255.0;
        double g = ((rgb >> 8) & 255) / 255.0;
        double b = (rgb & 255) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        double d = max - min;
        if (d == 0) return new double[]{0, 0, l};
        double s = d / (1 - Math.abs(2 * l - 1));
        double h;
        if (max == r) h = ((g - b) / d) % 6;
        else if (max == g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        return new double[]{(h * 60 + 360) % 360, s, l};
    }

    private static int rgbOfHsl(double h, double s, double l) {
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs(((h / 60) % 2) - 1));
        double m = l - c / 2;
        double r, g, b;
        if (h < 60) {
            r = c; g = x; b = 0;
        } else if (h < 120) {
            r = x; g = c; b = 0;
        } else if (h < 180) {
            r = 0; g = c; b = x;
        } else if (h < 240) {
            r = 0; g = x; b = c;
        } else if (h < 300) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        return (to255(r + m) << 16) | (to255(g + m) << 8) | to255(b + m);
    }

    private static int to255(double v) {
        // JavaScript's Math.round (half up), as the desktop rounds.
        return (int) Math.floor(Math.min(1, Math.max(0, v)) * 255 + 0.5);
    }
}
