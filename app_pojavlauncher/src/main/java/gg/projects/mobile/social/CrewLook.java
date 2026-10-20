package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A crew's look, the desktop's crew-look.ts as data (TEAM2 §4.1): 12 colours, 4 frames and 36
 * emblems. The crew colour is the crew's content, like an avatar: it fills the emblem's frame and
 * tints the tag; buttons, links and states stay gold. Crews from before looks (and a service
 * without them) show today's look: gold, the cut frame; an unknown emblem is drawn as a shield.
 */
public final class CrewLook {
    private CrewLook() {}

    /** Every emblem in the picker's order: heraldry, nature, creatures, things. */
    public static final List<String> EMBLEMS = Collections.unmodifiableList(Arrays.asList(
            "sword", "shield", "axe", "crown", "trophy", "flag", "target", "skull", "ghost",
            "flame", "bolt", "sun", "moon", "star", "planet", "snowflake", "waves", "leaf",
            "tree", "cactus", "paw", "cat", "horse", "bird", "fish", "butterfly", "heart",
            "diamond", "cube", "rocket", "potion", "wand", "castle", "controller", "alien", "robot"));

    /** The emblems every service takes; the other 16 came with crew looks ({@code teamFeatures: look}). */
    public static final List<String> FIRST_EMBLEMS = Collections.unmodifiableList(Arrays.asList(
            "sword", "shield", "crown", "flame", "bolt", "star", "skull", "diamond", "heart", "rocket",
            "tree", "paw", "ghost", "cube", "axe", "trophy", "moon", "flag", "target", "planet"));

    public static final List<String> COLORS = Collections.unmodifiableList(Arrays.asList(
            "gold", "copper", "crimson", "rose", "purple", "navy", "blue", "teal", "green", "silver", "ivory", "obsidian"));

    public static final List<String> FRAMES = Collections.unmodifiableList(Arrays.asList("cut", "shield", "banner", "hex"));

    public static final String DEFAULT_COLOR = "gold";
    public static final String DEFAULT_FRAME = "cut";

    private static final int GOLD_GLYPH = 0xFFF6D58E;
    private static final int BONE_GLYPH = 0xFFF3EBDD;

    /** Frame fill, frame edge and emblem colour (the desktop's table, contrast-checked there). */
    public static final class Colors {
        public final int fill;
        public final int edge;
        public final int glyph;

        Colors(int fill, int edge, int glyph) {
            this.fill = fill;
            this.edge = edge;
            this.glyph = glyph;
        }
    }

    public static Colors colors(@Nullable String color) {
        switch (color(color)) {
            case "copper": return new Colors(0xFF74361A, 0xFFD9824E, GOLD_GLYPH);
            case "crimson": return new Colors(0xFF6E1C22, 0xFFD9574D, GOLD_GLYPH);
            case "rose": return new Colors(0xFF6C2141, 0xFFDB6E99, GOLD_GLYPH);
            case "purple": return new Colors(0xFF452868, 0xFFA57BE0, GOLD_GLYPH);
            case "navy": return new Colors(0xFF1C2B57, 0xFF6F8EE0, GOLD_GLYPH);
            case "blue": return new Colors(0xFF17466E, 0xFF5AA6E0, BONE_GLYPH);
            case "teal": return new Colors(0xFF12514F, 0xFF46BDB4, BONE_GLYPH);
            case "green": return new Colors(0xFF1C4F31, 0xFF5DB47E, GOLD_GLYPH);
            case "silver": return new Colors(0xFF3A3D48, 0xFFB3B9C8, BONE_GLYPH);
            case "ivory": return new Colors(0xFFD6CAB4, 0xFFF3EBDD, 0xFF2A1D10);
            case "obsidian": return new Colors(0xFF141118, 0xFF6B6172, GOLD_GLYPH);
            case "gold":
            default: return new Colors(0xFF6E4A12, 0xFFE89A2C, GOLD_GLYPH);
        }
    }

    /** A known colour, else {@link #DEFAULT_COLOR}. */
    public static String color(@Nullable String value) {
        return value != null && COLORS.contains(value) ? value : DEFAULT_COLOR;
    }

    /** A known frame, else {@link #DEFAULT_FRAME}. */
    public static String frame(@Nullable String value) {
        return value != null && FRAMES.contains(value) ? value : DEFAULT_FRAME;
    }

    /** A known emblem, else the shield (what the desktop draws for one it does not know). */
    public static String emblem(@Nullable String value) {
        return value != null && EMBLEMS.contains(value) ? value : "shield";
    }

    /** The picker's emblems: all 36 where the service offers looks, else the first 20 (same order). */
    public static List<String> emblemsFor(boolean look) {
        if (look) return EMBLEMS;
        List<String> out = new ArrayList<>();
        for (String emblem : EMBLEMS) if (FIRST_EMBLEMS.contains(emblem)) out.add(emblem);
        return out;
    }

    /**
     * The frame's outline in a 40 × 40 box (absolute M, H, V, L, C and Z commands) and where the
     * emblem's centre sits (a banner's tail and a shield's point sit low).
     */
    public static final class FrameShape {
        public final String path;
        public final float glyphY;

        FrameShape(String path, float glyphY) {
            this.path = path;
            this.glyphY = glyphY;
        }
    }

    public static FrameShape shape(@Nullable String frame) {
        switch (frame(frame)) {
            case "shield":
                return new FrameShape("M20 1.5L36.5 6.5V19C36.5 28.6 29.6 35.2 20 38.5C10.4 35.2 3.5 28.6 3.5 19V6.5Z", 18.5f);
            case "banner":
                return new FrameShape("M4.5 1.5H35.5V38.5L20 31L4.5 38.5Z", 16.5f);
            case "hex":
                return new FrameShape("M20 1.5L36.5 10.75V29.25L20 38.5L3.5 29.25V10.75Z", 20f);
            case "cut":
            default:
                return new FrameShape("M9 1.5H38.5V31L31 38.5H1.5V9Z", 20f);
        }
    }

    /** The edge thins as the mark grows: 2.2 units at 22 px, 1.4 at 64 px (40-unit box). */
    public static float edgeWidth(float sizeDp) {
        float t = Math.min(1f, Math.max(0f, (sizeDp - 22f) / (64f - 22f)));
        return Math.round((2.2f - 0.8f * t) * 100f) / 100f;
    }

    /** The tag chip: the crew colour's edge at 24 %. */
    public static int tagTint(@Nullable String color) {
        int edge = colors(color).edge;
        return (Math.round(0.24f * 255) << 24) | (edge & 0x00FFFFFF);
    }
}
