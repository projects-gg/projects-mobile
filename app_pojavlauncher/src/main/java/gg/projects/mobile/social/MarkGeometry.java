package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * BADGES (the phone; docs: badges.md "Görsel sistem"): how one badge is drawn at one pixel grid. Every shape and glyph
 * has its own drawing for the 12, 14 and 16 grids ({@link MarkPaths}, generated from the launcher's @pl/ui drawings):
 * the outline is filled with the badge's colour; the glyph is filled white, or for the check stroked white with round
 * caps and joins. The paths are read here into plain commands ({@link #MOVE} x y, {@link #LINE} x y, {@link #QUAD} x1 y1
 * x y, {@link #CUBIC} x1 y1 x2 y2 x y, {@link #CLOSE}) that the drawable turns into Android's {@code Path}. Pure Java.
 */
public final class MarkGeometry {
    private MarkGeometry() {}

    public static final int MOVE = 0;
    public static final int LINE = 1;
    public static final int QUAD = 2;
    public static final int CUBIC = 3;
    public static final int CLOSE = 4;

    /** One badge at one grid: its outline, its glyph and, for the check, the glyph's stroke width (else 0). */
    public static final class Drawing {
        /** The grid (12, 14 or 16): the drawing's coordinates run from 0 to it. */
        public final int grid;
        public final float[] outline;
        public final float[] glyph;
        /** The check's stroke width in grid pixels; 0 for a filled glyph. */
        public final float stroke;

        Drawing(int grid, float[] outline, float[] glyph, float stroke) {
            this.grid = grid;
            this.outline = outline;
            this.glyph = glyph;
            this.stroke = stroke;
        }
    }

    private static final Map<String, Drawing> CACHE = new HashMap<>();

    /**
     * The grid drawn for a badge shown {@code dp} tall: 12 beside 12–12.5 sp text (chat names), 14 beside 13–15 sp
     * names (rows), 16 by titles; the nearest one otherwise.
     */
    public static int gridFor(int dp) {
        if (dp <= 13) return 12;
        if (dp <= 15) return 14;
        return 16;
    }

    /** The drawing of {@code shape} with {@code glyph} at {@code grid}; null for anything this version does not draw. */
    @Nullable
    public static Drawing drawing(int grid, @Nullable String shape, @Nullable String glyph) {
        int size = indexOf(MarkPaths.SIZES, grid);
        int outline = indexOf(MarkPaths.SHAPES, shape);
        boolean check = "check".equals(glyph);
        int filled = indexOf(MarkPaths.FILLED, glyph);
        if (size < 0 || outline < 0 || (!check && filled < 0)) return null;
        String key = grid + "/" + shape + "/" + glyph;
        synchronized (CACHE) {
            Drawing cached = CACHE.get(key);
            if (cached != null) return cached;
            // The shield leaves less room below: its glyphs sit a little smaller and higher.
            int room = "shield".equals(shape) ? 1 : 0;
            Drawing drawing = new Drawing(grid, parse(MarkPaths.OUTLINES[size][outline]),
                    parse(check ? MarkPaths.CHECKS[size][room] : MarkPaths.GLYPHS[size][room][filled]),
                    check ? MarkPaths.CHECK_WIDTHS[size] : 0f);
            CACHE.put(key, drawing);
            return drawing;
        }
    }

    /**
     * A path as {@link MarkPaths} writes it ("M x y C x1 y1 x2 y2 x y … Z": absolute commands, every number and letter
     * apart) as commands. Anything else is a broken table: it throws, so a test catches it before a build ships.
     */
    static float[] parse(String path) {
        String[] parts = path.trim().split(" +");
        float[] out = new float[parts.length];
        int n = 0;
        int i = 0;
        while (i < parts.length) {
            String command = parts[i++];
            int op;
            int args;
            switch (command) {
                case "M": op = MOVE; args = 2; break;
                case "L": op = LINE; args = 2; break;
                case "Q": op = QUAD; args = 4; break;
                case "C": op = CUBIC; args = 6; break;
                case "Z": op = CLOSE; args = 0; break;
                default: throw new IllegalArgumentException("not a path command: " + command);
            }
            out[n++] = op;
            for (int a = 0; a < args; a++) {
                if (i >= parts.length) throw new IllegalArgumentException("a path ends inside " + command);
                out[n++] = Float.parseFloat(parts[i++]);
            }
        }
        float[] exact = new float[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private static int indexOf(String[] values, @Nullable String value) {
        if (value == null) return -1;
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }
}
