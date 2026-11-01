package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * BADGES: the phone's badge drawings ({@link MarkPaths}, generated from the launcher's @pl/ui drawings by
 * scripts/projects/build-profile-marks.mjs; {@code --check} compares them): every shape with every glyph parses at
 * every grid and stays inside it, and a look this version does not know draws nothing.
 */
public class MarkGeometryTest {
    private static final int[] GRIDS = {12, 14, 16};

    @Test
    public void everyShapeAndGlyphParsesAndStaysInItsGrid() {
        int drawings = 0;
        for (int grid : GRIDS) {
            for (String shape : MarkCatalog.SHAPES) {
                for (String glyph : MarkCatalog.GLYPHS) {
                    String what = shape + "/" + glyph + " at " + grid;
                    MarkGeometry.Drawing d = MarkGeometry.drawing(grid, shape, glyph);
                    assertNotNull(what, d);
                    assertEquals(what, grid, d.grid);
                    assertInside(what + " outline", d.outline, grid);
                    assertInside(what + " glyph", d.glyph, grid);
                    if ("check".equals(glyph)) assertTrue(what + " is a stroke", d.stroke > 0 && d.stroke < grid / 4f);
                    else assertEquals(what + " is filled", 0f, d.stroke, 0f);
                    // Read once, kept.
                    assertSame(d, MarkGeometry.drawing(grid, shape, glyph));
                    drawings++;
                }
            }
        }
        assertEquals(3 * 3 * 9, drawings);
    }

    @Test
    public void eachSizeHasItsOwnGrid() {
        assertEquals(12, MarkGeometry.gridFor(12));
        assertEquals(12, MarkGeometry.gridFor(13));
        assertEquals(14, MarkGeometry.gridFor(14));
        assertEquals(14, MarkGeometry.gridFor(15));
        assertEquals(16, MarkGeometry.gridFor(16));
        assertEquals(16, MarkGeometry.gridFor(24));
        assertEquals(12, MarkGeometry.gridFor(8));
    }

    @Test
    public void aLookThisVersionDoesNotKnowDrawsNothing() {
        assertNull(MarkGeometry.drawing(14, "hexagon", "check"));
        assertNull(MarkGeometry.drawing(14, "seal", "rocket"));
        assertNull(MarkGeometry.drawing(13, "seal", "check"));
        assertNull(MarkGeometry.drawing(14, null, "check"));
        assertNull(MarkGeometry.drawing(14, "seal", null));
    }

    @Test
    public void aBrokenTableIsCaughtHere() {
        // MOVE 1 2, LINE 3 4, CLOSE.
        assertEquals(7, MarkGeometry.parse("M 1 2 L 3 4 Z").length);
        for (String broken : new String[]{"M 1 2 X 3 4", "M 1", "C 1 2 3 4 5", "Q 1 2 3"}) {
            try {
                MarkGeometry.parse(broken);
                fail(broken);
            } catch (IllegalArgumentException expected) {
                // A test fails before a build ships.
            }
        }
    }

    /**
     * Every point of the commands lies within the grid, as the generator checks: points of lines a hair of rounding
     * past it at most, those of curves (their control points lean out) a pixel.
     */
    private static void assertInside(String what, float[] commands, int grid) {
        assertTrue(what + " is empty", commands.length > 0);
        int i = 0;
        while (i < commands.length) {
            int op = (int) commands[i++];
            int args;
            float slack = 0.01f;
            switch (op) {
                case MarkGeometry.MOVE:
                case MarkGeometry.LINE:
                    args = 2;
                    break;
                case MarkGeometry.QUAD:
                    args = 4;
                    slack = 1f;
                    break;
                case MarkGeometry.CUBIC:
                    args = 6;
                    slack = 1f;
                    break;
                case MarkGeometry.CLOSE:
                    args = 0;
                    break;
                default:
                    fail(what + ": command " + op);
                    return;
            }
            for (int a = 0; a < args; a++) {
                float v = commands[i++];
                assertTrue(what + ": " + v + " outside 0.." + grid, v >= -slack && v <= grid + slack);
            }
        }
    }
}
