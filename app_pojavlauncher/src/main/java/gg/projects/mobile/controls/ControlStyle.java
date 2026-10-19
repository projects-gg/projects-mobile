package gg.projects.mobile.controls;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The look of the Projects on-screen controls (M15), as plain Java: colours per state, the
 * chamfered key shape, label fitting and how state changes move. {@link ControlSkin} draws with it.
 *
 * <p>States, from the base look a button's own colours give: pressed (fill lifts, ring turns white,
 * the key sinks a little), held for longer than {@link #HOLD_AFTER_MS} (gold ring: a continuous
 * action such as mining or jumping), on (a toggle that is on, an open drawer: solid gold with
 * dark ink).
 */
public final class ControlStyle {
    private ControlStyle() {}

    /** Glyphs the skin knows ({@code res/drawable/projects_ctl_<name>.xml}, Phosphor bold). */
    public static final Set<String> GLYPHS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "pause", "back", "chat", "keyboard", "inventory", "more", "mods", "menu", "hide", "show",
            "close", "jump", "sneak", "sprint", "attack", "use", "rightclick", "leftclick", "shift",
            "view", "drop", "swap", "players", "hud", "debug", "mouse", "pick", "zoom", "bright", "mic",
            "waypoint", "map")));

    /** The joystick's marker: a joystick with it gets the Projects look. */
    public static final String STICK = "stick";
    /** The joystick's base and knob as parts of its half size (the library's size ratios). */
    public static final float STICK_BASE_RATIO = 0.82f;
    public static final float STICK_KNOB_RATIO = 0.27f;

    // Palette (docs/design.md): obsidian, bone, gold.
    static final int PRESSED_FILL = 0xD1464050;
    static final int HELD_FILL = 0xC7784E18;
    static final int ON_FILL = 0xEBE89A2C; // gold-500
    static final int ON_PRESSED_FILL = 0xF0C9741A; // gold-600
    static final int PRESSED_RING = 0xEBFFFFFF;
    static final int GOLD_RING = 0xFFF6D58E; // gold-300
    static final int INK = 0xFFF3EBDD; // bone-100
    static final int INK_PRESSED = 0xFFFFFFFF;
    static final int INK_HELD = 0xFFFFE7B0;
    static final int INK_ON = 0xFF1A0F05; // text on gold
    static final int LABEL = 0xDBF3EBDD; // bone at 86 %

    /** A press longer than this shows as held. */
    public static final long HOLD_AFTER_MS = 350;
    /** Time constants of the state changes (an exponential approach, so any change can be interrupted). */
    static final float PRESS_TAU_MS = 22f; // in about 70 ms
    static final float RELEASE_TAU_MS = 52f; // back in about 160 ms
    static final float HOLD_TAU_MS = 60f;
    static final float TOGGLE_TAU_MS = 45f;
    /** How far a pressed key sinks (drawn only: the touch area stays). */
    static final float PRESSED_SCALE = 0.94f;

    /** Channel-wise blend of two ARGB colours. */
    public static int mix(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int a = Math.round(((from >>> 24) & 0xff) + (((to >>> 24) & 0xff) - ((from >>> 24) & 0xff)) * t);
        int r = Math.round(((from >> 16) & 0xff) + (((to >> 16) & 0xff) - ((from >> 16) & 0xff)) * t);
        int g = Math.round(((from >> 8) & 0xff) + (((to >> 8) & 0xff) - ((from >> 8) & 0xff)) * t);
        int b = Math.round((from & 0xff) + ((to & 0xff) - (from & 0xff)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Fill for the states, from the button's own fill. */
    public static int fill(int base, float press, float hold, float on) {
        int off = mix(mix(base, PRESSED_FILL, press), HELD_FILL, hold);
        return mix(off, mix(ON_FILL, ON_PRESSED_FILL, press), on);
    }

    /** Ring colour for the states, from the button's own ring. */
    public static int ring(int base, float press, float hold, float on) {
        int off = mix(mix(base, PRESSED_RING, press), GOLD_RING, hold);
        return mix(off, GOLD_RING, on);
    }

    /** Glyph colour. */
    public static int ink(float press, float hold, float on) {
        return mix(mix(mix(INK, INK_PRESSED, press), INK_HELD, hold), INK_ON, on);
    }

    /** Label colour. */
    public static int label(float press, float hold, float on) {
        return mix(mix(mix(LABEL, INK_PRESSED, press), INK_HELD, hold), INK_ON, on);
    }

    /** Ring width: a hair heavier while pressed, held or on. */
    public static float ringWidth(float base, float dp, float press, float hold, float on) {
        float lift = Math.max(press, Math.max(hold, on));
        return base + 0.5f * dp * lift;
    }

    /** Scale the key is drawn at. */
    public static float scale(float press, boolean motion) {
        return motion ? 1f - (1f - PRESSED_SCALE) * press : 1f;
    }

    /**
     * Size of the cut corners: the editor's corner radius (0 to 100 %) of half the shorter side,
     * so the "corner radius" slider still shapes the key.
     */
    public static float chamfer(float width, float height, float cornerPercent) {
        float half = Math.min(width, height) / 2f;
        float pct = Math.max(0f, Math.min(100f, cornerPercent));
        return half * pct / 100f;
    }

    /**
     * The key's outline as 8 points (x, y pairs, clockwise from the top-left cut), inset by
     * {@code inset} on every side (half the ring, so the stroke stays inside the view).
     */
    public static float[] outline(float width, float height, float cut, float inset) {
        float l = inset, t = inset, r = width - inset, b = height - inset;
        float c = Math.max(0f, Math.min(cut - inset * 0.4f, Math.min(r - l, b - t) / 2f));
        return new float[]{
                l + c, t, r - c, t,
                r, t + c, r, b - c,
                r - c, b, l + c, b,
                l, b - c, l, t + c,
        };
    }

    /**
     * Moves {@code value} towards {@code target} for {@code dtMs}: an exponential approach with
     * the time constant {@code tauMs}, snapping when it is close. Interrupting it anywhere just
     * changes the target.
     */
    public static float approach(float value, float target, float dtMs, float tauMs) {
        if (dtMs <= 0f) return value;
        if (tauMs <= 0f) return target;
        float next = target + (value - target) * (float) Math.exp(-dtMs / tauMs);
        return Math.abs(next - target) < 0.01f ? target : next;
    }

    /** Glyph size (dp) for a key of {@code w} x {@code h} dp. */
    public static float glyphDp(float w, float h) {
        float m = Math.min(w, h);
        if (m <= 44f) return 18f;
        if (m <= 52f) return 20f;
        if (m <= 58f) return 22f;
        return 26f;
    }

    /** Label size (sp) a key starts from before it is fitted. */
    public static float labelSp(float w, float h) {
        return Math.min(w, h) <= 48f ? 8f : 9f;
    }

    /**
     * Smallest label size, in dp so a label fits its key at any font scale (the start size is in sp
     * and grows with it): below this a label would not be read.
     */
    public static final float MIN_LABEL_DP = 6.5f;

    /** Measures a label at a text size (in px or sp, as long as the width is in the same unit). */
    public interface Measure {
        float width(String text, float size);
    }

    /**
     * The largest size from {@code startSize} down to {@code minSize} (0.25 steps) at which
     * {@code text} fits {@code maxWidth}; {@code minSize} when nothing fits.
     */
    public static float fit(Measure measure, String text, float maxWidth, float startSize, float minSize) {
        float size = startSize;
        while (size > minSize && measure.width(text, size) > maxWidth) size -= 0.25f;
        return Math.max(size, minSize);
    }

    /**
     * The label a key shows: one line, upper-cased with {@link Locale#ROOT} when the "all caps"
     * setting is on, like upstream's labels ({@link ControlLabels#upper}).
     */
    public static String labelText(String name, boolean allCaps) {
        if (name == null) return "";
        String line = name.replace('\n', ' ').trim();
        return allCaps ? line.toUpperCase(Locale.ROOT) : line;
    }

    /**
     * Whether a key looks pressed: a swipeable key while its key is down (a finger can slide onto it
     * or off it), a toggle while the finger is on it (its key mirrors the latch, which shows as "on"),
     * any other key while the finger is on it or its key is down.
     */
    public static boolean shownPressed(boolean swipeable, boolean toggle, boolean fingerDown, boolean keyDown) {
        if (toggle) return fingerDown;
        if (swipeable) return keyDown;
        return fingerDown || keyDown;
    }

    /**
     * True when walking in {@code direction} (GamepadJoystick's 0..7: 0 east, counter-clockwise; -1
     * none) lights the joystick chevron of {@code quarter} (0 east, 1 north, 2 west, 3 south): its
     * own direction and the two diagonals beside it.
     */
    public static boolean chevronLit(int direction, int quarter) {
        if (direction < 0 || direction > 7) return false;
        int diff = Math.abs(direction - quarter * 2) % 8;
        return Math.min(diff, 8 - diff) <= 1;
    }

    /** True for a glyph name the skin can draw. */
    public static boolean knownGlyph(String name) {
        return name != null && GLYPHS.contains(name);
    }
}
