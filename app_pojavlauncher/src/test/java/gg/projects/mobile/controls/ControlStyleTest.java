package gg.projects.mobile.controls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** The look of the Projects on-screen controls (M15): states, shape, motion, labels, glyphs. */
public class ControlStyleTest {
    private static final int BASE_FILL = 0xA3121016;
    private static final int BASE_RING = 0x4DF3EBDD;

    @Test
    public void coloursBlendChannelByChannel() {
        assertEquals(0xFF000000, ControlStyle.mix(0xFF000000, 0x00FFFFFF, 0f));
        assertEquals(0x00FFFFFF, ControlStyle.mix(0xFF000000, 0x00FFFFFF, 1f));
        assertEquals(0x80808080, ControlStyle.mix(0x00000000, 0xFFFFFFFF, 0.5f));
        assertEquals("out of range clamps", 0x11223344, ControlStyle.mix(0x11223344, 0xFFFFFFFF, -2f));
        assertEquals(0xFFFFFFFF, ControlStyle.mix(0x11223344, 0xFFFFFFFF, 3f));
    }

    @Test
    public void eachStateHasItsOwnLook() {
        // At rest a key is the layout's own colours.
        assertEquals(BASE_FILL, ControlStyle.fill(BASE_FILL, 0, 0, 0));
        assertEquals(BASE_RING, ControlStyle.ring(BASE_RING, 0, 0, 0));
        assertEquals(ControlStyle.INK, ControlStyle.ink(0, 0, 0));
        // Pressed: a lighter fill and a white ring; held: a gold ring; on: solid gold with dark ink.
        assertEquals(ControlStyle.PRESSED_FILL, ControlStyle.fill(BASE_FILL, 1, 0, 0));
        assertEquals(ControlStyle.PRESSED_RING, ControlStyle.ring(BASE_RING, 1, 0, 0));
        assertEquals(ControlStyle.HELD_FILL, ControlStyle.fill(BASE_FILL, 1, 1, 0));
        assertEquals(ControlStyle.GOLD_RING, ControlStyle.ring(BASE_RING, 1, 1, 0));
        assertEquals(ControlStyle.ON_FILL, ControlStyle.fill(BASE_FILL, 0, 0, 1));
        assertEquals(ControlStyle.ON_PRESSED_FILL, ControlStyle.fill(BASE_FILL, 1, 0, 1));
        assertEquals(ControlStyle.INK_ON, ControlStyle.ink(0, 0, 1));
        assertEquals(ControlStyle.INK_ON, ControlStyle.label(1, 0, 1));
        // Every state is opaque enough to read over snow: the fill keeps at least 60 % alpha.
        for (float p = 0; p <= 1; p += 0.25f)
            for (float o = 0; o <= 1; o += 0.25f)
                assertTrue(((ControlStyle.fill(BASE_FILL, p, 0, o) >>> 24) & 0xff) >= 0x99);
        // The ring is a hair heavier while pressed, held or on; the key sinks only with motion.
        assertEquals(1.5f * 2.625f, ControlStyle.ringWidth(1.5f * 2.625f, 2.625f, 0, 0, 0), 1e-4);
        assertEquals(2f * 2.625f, ControlStyle.ringWidth(1.5f * 2.625f, 2.625f, 0, 1, 0), 1e-4);
        assertEquals(0.94f, ControlStyle.scale(1f, true), 1e-6);
        assertEquals(1f, ControlStyle.scale(1f, false), 1e-6);
    }

    @Test
    public void anUnavailableKeyFadesItsInkAndRingButKeepsItsFill() {
        // M16: a MODLAR key without a key binding or without its mod.
        assertEquals(ControlStyle.INK, ControlStyle.faded(ControlStyle.INK, 0f, ControlStyle.UNAVAILABLE_INK));
        int ink = ControlStyle.faded(ControlStyle.INK, 1f, ControlStyle.UNAVAILABLE_INK);
        assertEquals(ControlStyle.INK & 0xFFFFFF, ink & 0xFFFFFF);
        assertEquals(Math.round(255 * ControlStyle.UNAVAILABLE_INK), (ink >>> 24) & 0xff);
        int ring = ControlStyle.faded(BASE_RING, 1f, ControlStyle.UNAVAILABLE_RING);
        assertEquals(Math.round(0x4D * ControlStyle.UNAVAILABLE_RING), (ring >>> 24) & 0xff);
        // Halfway and past the end.
        int half = ControlStyle.faded(0xFF000000, 0.5f, 0.5f);
        assertEquals(Math.round(255 * 0.75f), (half >>> 24) & 0xff);
        assertEquals(ControlStyle.faded(0xFF000000, 1f, 0.5f), ControlStyle.faded(0xFF000000, 3f, 0.5f));
        // Still readable as a key, clearly quieter than a ready one.
        assertTrue(ControlStyle.UNAVAILABLE_INK >= 0.3f && ControlStyle.UNAVAILABLE_INK <= 0.5f);
    }

    @Test
    public void theKeyIsChamfered() {
        assertEquals("40 % of half the shorter side", 9.6f, ControlStyle.chamfer(56, 48, 40), 1e-4);
        assertEquals(0f, ControlStyle.chamfer(48, 48, 0), 1e-6);
        assertEquals(24f, ControlStyle.chamfer(48, 64, 100), 1e-6);
        assertEquals("clamped", 24f, ControlStyle.chamfer(48, 64, 250), 1e-6);
        assertEquals("clamped", 0f, ControlStyle.chamfer(48, 64, -5), 1e-6);

        float[] p = ControlStyle.outline(100, 60, 12, 1);
        assertEquals(16, p.length);
        for (int i = 0; i < p.length; i += 2) {
            assertTrue(p[i] >= 1 && p[i] <= 99);
            assertTrue(p[i + 1] >= 1 && p[i + 1] <= 59);
        }
        // Symmetric: the top edge spans the same cut on both sides.
        assertEquals(p[0] - 1, 99 - p[2], 1e-4);
        // A cut larger than the key stays inside it (an octagon at most, never crossed edges).
        float[] q = ControlStyle.outline(20, 20, 50, 1);
        assertTrue(q[0] <= q[2]);
        assertTrue(q[5] <= q[7]);
    }

    @Test
    public void stateChangesEaseOutAndCanBeInterrupted() {
        // A press is 95 % there after about 70 ms and done in about 100, fastest at the start.
        float first = ControlStyle.approach(0f, 1f, 16f, ControlStyle.PRESS_TAU_MS);
        float second = ControlStyle.approach(first, 1f, 16f, ControlStyle.PRESS_TAU_MS) - first;
        assertTrue(first > second);
        assertTrue(ControlStyle.approach(0f, 1f, 66f, ControlStyle.PRESS_TAU_MS) >= 0.95f);
        assertEquals(1f, settle(0f, 1f, ControlStyle.PRESS_TAU_MS)[0], 0f);
        assertTrue("done within 112 ms", settle(0f, 1f, ControlStyle.PRESS_TAU_MS)[1] <= 112f);
        // The release takes longer: 95 % after about 160 ms, done within 260.
        assertTrue(ControlStyle.approach(1f, 0f, 156f, ControlStyle.RELEASE_TAU_MS) <= 0.05f);
        float[] release = settle(1f, 0f, ControlStyle.RELEASE_TAU_MS);
        assertEquals(0f, release[0], 0f);
        assertTrue(release[1] > 112f && release[1] <= 260f);
        // Released half way: it turns back from where it is, without a jump.
        float half = ControlStyle.approach(0f, 1f, 16f, ControlStyle.PRESS_TAU_MS);
        float back = ControlStyle.approach(half, 0f, 16f, ControlStyle.RELEASE_TAU_MS);
        assertTrue(back < half && back > 0f);
        assertEquals(0.3f, ControlStyle.approach(0.3f, 1f, 0f, 20f), 0f);
        assertEquals(1f, ControlStyle.approach(0.3f, 1f, 16f, 0f), 0f);
    }

    /** Frames of 16 ms until the value rests: {value, milliseconds}; it only moves towards the target. */
    private static float[] settle(float from, float to, float tau) {
        float v = from, t = 0f;
        while (v != to && t < 1000f) {
            float next = ControlStyle.approach(v, to, 16f, tau);
            assertTrue(Math.abs(to - next) <= Math.abs(to - v));
            v = next;
            t += 16f;
        }
        return new float[]{v, t};
    }

    @Test
    public void glyphsAndLabelsGrowWithTheKey() {
        assertEquals(18f, ControlStyle.glyphDp(44, 40), 0f);
        assertEquals(20f, ControlStyle.glyphDp(48, 48), 0f);
        assertEquals(22f, ControlStyle.glyphDp(56, 56), 0f);
        assertEquals(26f, ControlStyle.glyphDp(64, 64), 0f);
        assertEquals(8f, ControlStyle.labelSp(64, 48), 0f);
        assertEquals(9f, ControlStyle.labelSp(56, 56), 0f);
    }

    @Test
    public void labelsShrinkToFitButNotBelowReading() {
        ControlStyle.Measure measure = (text, size) -> text.length() * size * 0.6f;
        assertEquals("fits at once", 9f, ControlStyle.fit(measure, "ZIPLA", 48, 9f, 6.5f), 0f);
        float size = ControlStyle.fit(measure, "ENVANTER", 40, 9f, 6.5f);
        assertTrue(measure.width("ENVANTER", size) <= 40);
        assertTrue(measure.width("ENVANTER", size + 0.25f) > 40);
        assertEquals("never below the floor", 6.5f, ControlStyle.fit(measure, "A VERY LONG LABEL INDEED", 30, 9f, 6.5f), 0f);
    }

    @Test
    public void labelsAreOneLineInRootCapitals() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("HIDE GUI", ControlStyle.labelText("Hide\nGUI", true));
            assertEquals("EĞİL", ControlStyle.labelText("EĞİL", true));
            assertEquals("Hide GUI", ControlStyle.labelText(" Hide\nGUI ", false));
            assertEquals("", ControlStyle.labelText(null, true));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void thePressedLookFollowsTheFingerOrTheKey() {
        // An ordinary key: the finger or its key (a key pressed by sliding onto it counts).
        assertTrue(ControlStyle.shownPressed(false, false, true, false));
        assertTrue(ControlStyle.shownPressed(false, false, false, true));
        assertFalse(ControlStyle.shownPressed(false, false, false, false));
        // A swipeable key: only while its key is down (the finger slid off: not pressed).
        assertFalse(ControlStyle.shownPressed(true, false, true, false));
        assertTrue(ControlStyle.shownPressed(true, false, false, true));
        // A toggle, swipeable or not: the finger, never its latch (that shows as "on").
        assertFalse(ControlStyle.shownPressed(false, true, false, true));
        assertFalse(ControlStyle.shownPressed(true, true, false, true));
        assertTrue(ControlStyle.shownPressed(true, true, true, false));
    }

    @Test
    public void joystickChevronsFollowTheDirection() {
        // GamepadJoystick: 0 east, 1 north-east, 2 north ... 7 south-east, -1 none.
        assertTrue(ControlStyle.chevronLit(2, 1));
        assertFalse(ControlStyle.chevronLit(2, 0));
        assertTrue("north-east lights north and east", ControlStyle.chevronLit(1, 0) && ControlStyle.chevronLit(1, 1));
        assertTrue("south-east lights east", ControlStyle.chevronLit(7, 0));
        assertTrue(ControlStyle.chevronLit(7, 3));
        assertFalse(ControlStyle.chevronLit(7, 1));
        for (int q = 0; q < 4; q++) {
            assertFalse(ControlStyle.chevronLit(-1, q));
            assertFalse(ControlStyle.chevronLit(8, q));
        }
    }

    @Test
    public void everyGlyphHasItsDrawable() {
        File drawables = new File("src/main/res/drawable");
        Set<String> files = new TreeSet<>();
        String[] names = drawables.list((dir, name) -> name.startsWith("projects_ctl_") && name.endsWith(".xml"));
        for (String name : names) files.add(name.substring("projects_ctl_".length(), name.length() - 4));
        assertEquals(new TreeSet<>(ControlStyle.GLYPHS), files);
        assertTrue(ControlStyle.knownGlyph("jump"));
        assertFalse(ControlStyle.knownGlyph("stick"));
        assertFalse(ControlStyle.knownGlyph(null));
    }
}
