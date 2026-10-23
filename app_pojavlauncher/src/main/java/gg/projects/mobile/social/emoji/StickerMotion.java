package gg.projects.mobile.social.emoji;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a sticker moves (owner, 22 Oct 2026; chat2.md section 8; the desktop's
 * features/emoji/sticker-motion.ts, the same 14 patterns): the image is still and moves once in
 * code, when it arrives and when it is pressed. One pattern per sticker (the manifest's
 * {@code motion}), transform and opacity only, never a loop. Distances are fractions of the
 * sticker's own size, so 120 dp in chat and 72 dp in the picker move alike. Pure data;
 * {@link StickerAnimator} plays it on a View.
 *
 * <p>Each frame's easing runs from it to the next frame (Web Animations' rule; Android eases an
 * interval with the keyframe that ends it, so the player shifts them by one).
 */
public final class StickerMotion {
    /** The patterns' names, in the desktop's order (the manifest's {@code motions}). */
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            "bounce", "wobble", "beat", "shake", "shiver", "wave", "nod", "sway", "float", "pop",
            "tilt", "swing", "sink", "glitch"));

    /** The reduced form's name: the arrival only fades in, nothing moves. */
    public static final String FADE = "fade";

    /** One animated property of a View, with its value at rest. */
    public enum Channel {
        /** A fraction of the sticker's width. */
        TRANSLATION_X(0f),
        /** A fraction of the sticker's height. */
        TRANSLATION_Y(0f),
        /** Degrees, clockwise. */
        ROTATION(0f),
        SCALE_X(1f),
        SCALE_Y(1f),
        ALPHA(1f);

        public final float rest;

        Channel(float rest) {
            this.rest = rest;
        }
    }

    /** A timing curve: CSS cubic-bezier control points, or {@code steps(1, end)}. */
    public static final class Easing {
        public static final Easing LINEAR = new Easing(0f, 0f, 1f, 1f, false);
        /** The desktop's "out": cubic-bezier(0.22, 1, 0.36, 1). */
        public static final Easing OUT = new Easing(0.22f, 1f, 0.36f, 1f, false);
        /** The desktop's "inOut": cubic-bezier(0.45, 0, 0.55, 1). */
        public static final Easing IN_OUT = new Easing(0.45f, 0f, 0.55f, 1f, false);
        /** The desktop's "down": cubic-bezier(0.55, 0, 1, 0.45). */
        public static final Easing DOWN = new Easing(0.55f, 0f, 1f, 0.45f, false);
        /** steps(1, end): holds the frame's value, then jumps to the next one at its offset. */
        public static final Easing STEP_END = new Easing(0f, 0f, 1f, 1f, true);

        public final float x1;
        public final float y1;
        public final float x2;
        public final float y2;
        /** A jump at the end instead of a curve (the control points are then unused). */
        public final boolean stepEnd;

        private Easing(float x1, float y1, float x2, float y2, boolean stepEnd) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.stepEnd = stepEnd;
        }

        public boolean isLinear() {
            return this == LINEAR;
        }
    }

    /** One keyframe. */
    public static final class Frame {
        /** 0 to 1 within the duration. */
        public final float offset;
        /** Fractions of the sticker's width and height. */
        public final float translationX;
        public final float translationY;
        /** Degrees, clockwise. */
        public final float rotation;
        public final float scaleX;
        public final float scaleY;
        public final float alpha;
        /** From this frame to the next one (unused on the last frame). */
        public final Easing easing;

        Frame(float offset, float translationX, float translationY, float rotation, float scaleX,
                float scaleY, float alpha, Easing easing) {
            this.offset = offset;
            this.translationX = translationX;
            this.translationY = translationY;
            this.rotation = rotation;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.alpha = alpha;
            this.easing = easing;
        }

        public float value(Channel channel) {
            switch (channel) {
                case TRANSLATION_X: return translationX;
                case TRANSLATION_Y: return translationY;
                case ROTATION: return rotation;
                case SCALE_X: return scaleX;
                case SCALE_Y: return scaleY;
                default: return alpha;
            }
        }

        /** Whether this frame leaves the sticker where it was. */
        public boolean atRest() {
            for (Channel channel : Channel.values()) {
                if (value(channel) != channel.rest) return false;
            }
            return true;
        }
    }

    /** The pattern's name ("bounce"), or {@link #FADE} for the reduced form. */
    public final String name;
    /** Milliseconds. */
    public final long duration;
    /** The point it turns and squashes around, as fractions of the width and height. */
    public final float pivotX;
    public final float pivotY;
    /** Offsets from 0 to 1, rising; the first and the last at rest. */
    public final List<Frame> frames;

    private StickerMotion(String name, long duration, float pivotX, float pivotY, Frame[] frames) {
        this.name = name;
        this.duration = duration;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.frames = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(frames)));
    }

    /** Whether any frame changes the channel (a player animates only those). */
    public boolean animates(Channel channel) {
        for (Frame frame : frames) {
            if (frame.value(channel) != channel.rest) return true;
        }
        return false;
    }

    public boolean isReduced() {
        return FADE.equals(name);
    }

    private static final Easing LINEAR = Easing.LINEAR;
    private static final Easing OUT = Easing.OUT;
    private static final Easing IN_OUT = Easing.IN_OUT;
    private static final Easing DOWN = Easing.DOWN;
    private static final Easing STEP = Easing.STEP_END;

    private static Frame f(float offset, float translationX, float translationY, float rotation,
            float scaleX, float scaleY, float alpha, Easing easing) {
        return new Frame(offset, translationX, translationY, rotation, scaleX, scaleY, alpha, easing);
    }

    private static final Map<String, StickerMotion> PATTERNS = new LinkedHashMap<>();

    private static void put(String name, long duration, float pivotX, float pivotY, Frame... frames) {
        PATTERNS.put(name, new StickerMotion(name, duration, pivotX, pivotY, frames));
    }

    /**
     * The arrival under reduced motion or with animations off: a fade, nothing moves (the
     * desktop's fadeInSticker: opacity 0 to 1 in 200 ms, easing out).
     */
    private static final StickerMotion REDUCED = new StickerMotion(FADE, 200, 0.5f, 0.5f,
            new Frame[] {f(0f, 0f, 0f, 0f, 1f, 1f, 0f, OUT), f(1f, 0f, 0f, 0f, 1f, 1f, 1f, LINEAR)});

    // Generated from the desktop's STICKER_PATTERNS: translate percentages as fractions, then
    // rotate (degrees), scale (x, y) and opacity; easing as each frame gives it (the desktop's
    // frames() helper defaults to inOut; glitch's last two frames have none, so linear).
    static {
        put("bounce", 820, 0.5f, 1f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, OUT),
                f(0.12f, 0f, 0f, 0f, 1.06f, 0.92f, 1f, OUT),
                f(0.32f, 0f, -0.14f, 0f, 0.96f, 1.05f, 1f, DOWN),
                f(0.5f, 0f, 0f, 0f, 1.05f, 0.94f, 1f, OUT),
                f(0.66f, 0f, -0.06f, 0f, 0.99f, 1.02f, 1f, DOWN),
                f(0.82f, 0f, 0f, 0f, 1.02f, 0.98f, 1f, OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("wobble", 760, 0.5f, 0.9f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.16f, 0f, 0f, -9f, 1f, 1f, 1f, IN_OUT),
                f(0.34f, 0f, 0f, 8f, 1f, 1f, 1f, IN_OUT),
                f(0.52f, 0f, 0f, -6f, 1f, 1f, 1f, IN_OUT),
                f(0.7f, 0f, 0f, 4f, 1f, 1f, 1f, IN_OUT),
                f(0.86f, 0f, 0f, -1.5f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("beat", 760, 0.5f, 0.55f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, OUT),
                f(0.14f, 0f, 0f, 0f, 1.14f, 1.14f, 1f, IN_OUT),
                f(0.3f, 0f, 0f, 0f, 0.98f, 0.98f, 1f, OUT),
                f(0.44f, 0f, 0f, 0f, 1.09f, 1.09f, 1f, IN_OUT),
                f(0.7f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("shake", 560, 0.5f, 0.6f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.12f, -0.07f, 0f, -2f, 1f, 1f, 1f, IN_OUT),
                f(0.28f, 0.06f, 0f, 2f, 1f, 1f, 1f, IN_OUT),
                f(0.44f, -0.05f, 0f, -1.5f, 1f, 1f, 1f, IN_OUT),
                f(0.6f, 0.035f, 0f, 1f, 1f, 1f, 1f, IN_OUT),
                f(0.78f, -0.015f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("shiver", 560, 0.5f, 0.6f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.1f, -0.025f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.2f, 0.025f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.3f, -0.025f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.4f, 0.02f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.5f, -0.02f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.62f, 0.015f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.74f, -0.01f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.86f, 0.005f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("wave", 900, 0.5f, 1f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.18f, 0f, 0f, 12f, 1f, 1f, 1f, IN_OUT),
                f(0.38f, 0f, 0f, -9f, 1f, 1f, 1f, IN_OUT),
                f(0.58f, 0f, 0f, 7f, 1f, 1f, 1f, IN_OUT),
                f(0.78f, 0f, 0f, -3f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("nod", 720, 0.5f, 1f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.22f, 0f, 0.05f, 0f, 1.02f, 0.96f, 1f, IN_OUT),
                f(0.44f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.64f, 0f, 0.04f, 0f, 1.015f, 0.97f, 1f, IN_OUT),
                f(0.86f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("sway", 1100, 0.5f, 1f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.42f, 0f, 0f, -7f, 1f, 1f, 1f, IN_OUT),
                f(0.76f, 0f, 0f, 3f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("float", 900, 0.5f, 0.7f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, OUT),
                f(0.42f, 0f, -0.09f, 0f, 1.04f, 1.04f, 1f, IN_OUT),
                f(0.76f, 0f, 0.01f, 0f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("pop", 560, 0.5f, 0.6f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, OUT),
                f(0.24f, 0f, 0f, 0f, 1.16f, 1.16f, 1f, IN_OUT),
                f(0.52f, 0f, 0f, 0f, 0.97f, 0.97f, 1f, IN_OUT),
                f(0.76f, 0f, 0f, 0f, 1.02f, 1.02f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("tilt", 900, 0.5f, 0.8f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, OUT),
                f(0.3f, 0f, 0f, -10f, 1f, 1f, 1f, IN_OUT),
                f(0.56f, 0f, 0f, -10f, 1f, 1f, 1f, IN_OUT),
                f(0.82f, 0f, 0f, 2f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("swing", 760, 0.35f, 0.9f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.3f, 0f, 0f, -12f, 1f, 1f, 1f, DOWN),
                f(0.48f, 0f, 0f, 10f, 1f, 1f, 1f, OUT),
                f(0.74f, 0f, 0f, -3f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("sink", 900, 0.5f, 1f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.36f, 0f, 0.08f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.66f, 0f, -0.03f, 0f, 1f, 1f, 1f, IN_OUT),
                f(0.86f, 0f, 0.01f, 0f, 1f, 1f, 1f, IN_OUT),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, IN_OUT));
        put("glitch", 520, 0.5f, 0.5f,
                f(0f, 0f, 0f, 0f, 1f, 1f, 1f, STEP),
                f(0.12f, -0.05f, 0f, 0f, 1f, 1f, 0.82f, STEP),
                f(0.24f, 0.04f, 0f, 0f, 1f, 1f, 1f, STEP),
                f(0.36f, -0.03f, 0f, 0f, 1f, 1f, 0.86f, STEP),
                f(0.5f, 0.02f, 0f, 0f, 1f, 1f, 1f, STEP),
                f(0.64f, 0f, 0f, 0f, 1f, 1f, 1f, LINEAR),
                f(1f, 0f, 0f, 0f, 1f, 1f, 1f, LINEAR));
    }

    /** The pattern a manifest's {@code motion} names, or null for one this build does not know. */
    @Nullable
    public static StickerMotion of(@Nullable String name) {
        return name == null ? null : PATTERNS.get(name);
    }

    public static boolean isKnown(@Nullable String name) {
        return of(name) != null;
    }

    /** The arrival under reduced motion: a fade from nothing, 200 ms. */
    public static StickerMotion reduced() {
        return REDUCED;
    }
}
