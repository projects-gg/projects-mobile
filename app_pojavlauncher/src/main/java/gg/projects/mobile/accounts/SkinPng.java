package gg.projects.mobile.accounts;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * M053 (SKIN1 on the phone): a skin file made ready for the Projects skin service, the desktop's {@code pl_auth::skin_png}
 * (skin1.md §4.1) in Java: 64×64 or the old 64×32 (made 64×64 as Minecraft's {@code processLegacySkin} does), the base
 * layer checked (all see-through: empty; over 5 %: some parts may look black), then encoded again as the service takes it
 * (64×64 RGBA8, IHDR + one IDAT + IEND, no other chunks, at most {@link #CLEAN_MAX} bytes) with its SHA-256. The pixels
 * come in non-premultiplied ARGB ints (Android's {@code Bitmap.getPixels} of a non-premultiplied bitmap), so this stays
 * plain Java and JVM-tested.
 */
public final class SkinPng {
    private SkinPng() {}

    /** The largest file the picker accepts (the desktop's {@code MAX_SKIN_BYTES}). */
    public static final int MAX_FILE = 1024 * 1024;
    /** The service's limit for the clean PNG. */
    public static final int CLEAN_MAX = 24_576;
    private static final int SIZE = 64;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    public enum Problem { NOT_PNG, DIMENSIONS, TOO_BIG, EMPTY, UNREADABLE }

    public static final class Result {
        @Nullable public final Problem problem;
        /** The clean PNG the service gets. */
        @Nullable public final byte[] png;
        @Nullable public final String sha256;
        /** 64×64 non-premultiplied ARGB (the preview and the head). */
        @Nullable public final int[] argb;
        /** A 64×32 skin: no slim arms. */
        public final boolean legacy;
        /** Over 5 % of the base layer is see-through: those parts may look black in the game. */
        public final boolean blackParts;

        Result(@Nullable Problem problem, @Nullable byte[] png, @Nullable String sha256, @Nullable int[] argb, boolean legacy,
               boolean blackParts) {
            this.problem = problem;
            this.png = png;
            this.sha256 = sha256;
            this.argb = argb;
            this.legacy = legacy;
            this.blackParts = blackParts;
        }

        static Result failed(Problem problem) {
            return new Result(problem, null, null, null, false, false);
        }

        public boolean ok() {
            return problem == null;
        }
    }

    /** The file starts as a PNG does. */
    public static boolean looksLikePng(@Nullable byte[] head) {
        if (head == null || head.length < SIGNATURE.length) return false;
        for (int i = 0; i < SIGNATURE.length; i++) if (head[i] != SIGNATURE[i]) return false;
        return true;
    }

    /** Checks the size before decoding: 64 wide, 64 or 32 tall. */
    public static boolean sizeFits(int width, int height) {
        return width == SIZE && (height == SIZE || height == 32);
    }

    /** The decoded pixels ({@code width} × {@code height}, row by row) made into the service's PNG. */
    public static Result clean(int width, int height, int[] argb) {
        if (!sizeFits(width, height) || argb == null || argb.length < width * height) return Result.failed(Problem.DIMENSIONS);
        boolean legacy = height == 32;
        int[] pixels = legacy ? fromLegacy(argb) : argb.clone();
        int[] counts = baseSeeThrough(pixels);
        if (counts[0] == counts[1]) return Result.failed(Problem.EMPTY);
        boolean black = counts[0] * 20 > counts[1];
        byte[] png = encode(pixels);
        if (png.length > CLEAN_MAX) return Result.failed(Problem.TOO_BIG);
        return new Result(null, png, sha256Hex(png), pixels, legacy, black);
    }

    /** Guesses the arm model (the desktop's {@code inferVariant}): slim leaves the arms' fourth column see-through or black. */
    public static boolean looksSlim(int[] argb64, boolean legacy) {
        if (legacy || argb64 == null || argb64.length < SIZE * SIZE) return false;
        int[][] gaps = {{50, 16, 2, 4}, {54, 20, 2, 12}, {42, 48, 2, 4}, {46, 52, 2, 12}};
        boolean transparent = false;
        boolean allBlack = true;
        for (int[] g : gaps) {
            for (int y = g[1]; y < g[1] + g[3]; y++) {
                for (int x = g[0]; x < g[0] + g[2]; x++) {
                    int p = argb64[y * SIZE + x];
                    if ((p >>> 24) != 255) transparent = true;
                    if (p != 0xFF000000) allBlack = false;
                }
            }
        }
        return transparent || allBlack;
    }

    // ---- Minecraft's legacy conversion ----

    static int[] fromLegacy(int[] top) {
        int[] image = new int[SIZE * SIZE];
        System.arraycopy(top, 0, image, 0, SIZE * 32);
        int[][] copies = {
                {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
                {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
                {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};
        for (int[] c : copies) {
            for (int row = 0; row < c[5]; row++) {
                for (int col = 0; col < c[4]; col++) {
                    int from = (c[1] + row) * SIZE + c[0] + col;
                    int toX = c[0] + c[2] + (c[4] - 1 - col);
                    int toY = c[1] + c[3] + row;
                    image[toY * SIZE + toX] = image[from];
                }
            }
        }
        // "Notch transparency": a hat area without one see-through pixel (over x 32..64, y 0..32) is cleared.
        boolean solid = true;
        for (int y = 0; y < 32 && solid; y++) {
            for (int x = 32; x < 64; x++) {
                if ((image[y * SIZE + x] >>> 24) < 128) {
                    solid = false;
                    break;
                }
            }
        }
        if (solid) {
            for (int y = 0; y < 16; y++) for (int x = 32; x < 64; x++) image[y * SIZE + x] &= 0x00FFFFFF;
        }
        return image;
    }

    /** The base layer's faces the game draws, 3-pixel arms (both models draw them). */
    private static final int[][] BASE_FACES = {
            {8, 0, 8, 8}, {16, 0, 8, 8}, {0, 8, 32, 8},
            {4, 16, 4, 4}, {8, 16, 4, 4}, {0, 20, 16, 12},
            {20, 16, 8, 4}, {28, 16, 8, 4}, {16, 20, 24, 12},
            {44, 16, 3, 4}, {47, 16, 3, 4}, {40, 20, 14, 12},
            {20, 48, 4, 4}, {24, 48, 4, 4}, {16, 52, 16, 12},
            {36, 48, 3, 4}, {39, 48, 3, 4}, {32, 52, 14, 12}};

    /** {see-through, total} over the base layer (alpha under half). */
    static int[] baseSeeThrough(int[] pixels) {
        int seeThrough = 0;
        int total = 0;
        for (int[] f : BASE_FACES) {
            for (int y = f[1]; y < f[1] + f[3]; y++) {
                for (int x = f[0]; x < f[0] + f[2]; x++) {
                    total++;
                    if ((pixels[y * SIZE + x] >>> 24) < 128) seeThrough++;
                }
            }
        }
        return new int[]{seeThrough, total};
    }

    // ---- the service's PNG ----

    static byte[] encode(int[] pixels) {
        byte[] raw = new byte[SIZE * (1 + SIZE * 4)];
        int at = 0;
        for (int y = 0; y < SIZE; y++) {
            raw[at++] = 0; // filter: none
            for (int x = 0; x < SIZE; x++) {
                int p = pixels[y * SIZE + x];
                raw[at++] = (byte) (p >> 16);
                raw[at++] = (byte) (p >> 8);
                raw[at++] = (byte) p;
                raw[at++] = (byte) (p >>> 24);
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            compressed.write(buffer, 0, n);
        }
        deflater.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE, 0, SIGNATURE.length);
        byte[] ihdr = {0, 0, 0, SIZE, 0, 0, 0, SIZE, 8, 6, 0, 0, 0};
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        int n = data.length;
        out.write(n >>> 24);
        out.write(n >>> 16);
        out.write(n >>> 8);
        out.write(n);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.write(name, 0, 4);
        out.write(data, 0, n);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        long v = crc.getValue();
        out.write((int) (v >>> 24));
        out.write((int) (v >>> 16));
        out.write((int) (v >>> 8));
        out.write((int) v);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
