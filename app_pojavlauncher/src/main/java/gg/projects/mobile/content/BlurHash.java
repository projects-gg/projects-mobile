package gg.projects.mobile.content;

/**
 * BlurHash decoder (the algorithm of woltapp/blurhash), for cover placeholders: the feed gives a
 * short string per cover, decoded to a few dozen pixels and scaled up while the image loads.
 */
public final class BlurHash {
    private BlurHash() {}

    private static final String CHARS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    /** Opaque ARGB pixels ({@code width} x {@code height}, row by row), or null for an invalid hash. */
    public static int[] decode(String hash, int width, int height, double punch) {
        if (hash == null || hash.length() < 6 || width <= 0 || height <= 0) return null;
        int sizeFlag = decode83(hash, 0, 1);
        if (sizeFlag < 0) return null;
        int numY = sizeFlag / 9 + 1;
        int numX = sizeFlag % 9 + 1;
        if (hash.length() != 4 + 2 * numX * numY) return null;
        int quantisedMaximum = decode83(hash, 1, 2);
        if (quantisedMaximum < 0) return null;
        double maximum = (quantisedMaximum + 1) / 166.0;
        double[][] colors = new double[numX * numY][];
        int dc = decode83(hash, 2, 6);
        if (dc < 0) return null;
        colors[0] = new double[]{srgbToLinear(dc >> 16), srgbToLinear((dc >> 8) & 255), srgbToLinear(dc & 255)};
        for (int i = 1; i < colors.length; i++) {
            int ac = decode83(hash, 4 + i * 2, 6 + i * 2);
            if (ac < 0) return null;
            double scale = maximum * (punch > 0 ? punch : 1);
            colors[i] = new double[]{
                    signPow((ac / (19 * 19) - 9) / 9.0) * scale,
                    signPow(((ac / 19) % 19 - 9) / 9.0) * scale,
                    signPow((ac % 19 - 9) / 9.0) * scale};
        }
        double[] cosX = new double[width * numX];
        for (int x = 0; x < width; x++) {
            for (int i = 0; i < numX; i++) cosX[x * numX + i] = Math.cos(Math.PI * x * i / width);
        }
        double[] cosY = new double[height * numY];
        for (int y = 0; y < height; y++) {
            for (int j = 0; j < numY; j++) cosY[y * numY + j] = Math.cos(Math.PI * y * j / height);
        }
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double r = 0;
                double g = 0;
                double b = 0;
                for (int j = 0; j < numY; j++) {
                    for (int i = 0; i < numX; i++) {
                        double basis = cosX[x * numX + i] * cosY[y * numY + j];
                        double[] color = colors[i + j * numX];
                        r += color[0] * basis;
                        g += color[1] * basis;
                        b += color[2] * basis;
                    }
                }
                pixels[y * width + x] = 0xFF000000 | (linearToSrgb(r) << 16) | (linearToSrgb(g) << 8) | linearToSrgb(b);
            }
        }
        return pixels;
    }

    private static int decode83(String text, int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            int digit = CHARS.indexOf(text.charAt(i));
            if (digit < 0) return -1;
            value = value * 83 + digit;
        }
        return value;
    }

    private static double srgbToLinear(int value) {
        double v = value / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int linearToSrgb(double value) {
        double v = Math.max(0, Math.min(1, value));
        if (v <= 0.0031308) return (int) (v * 12.92 * 255 + 0.5);
        return (int) ((1.055 * Math.pow(v, 1 / 2.4) - 0.055) * 255 + 0.5);
    }

    private static double signPow(double value) {
        return Math.copySign(value * value, value);
    }
}
