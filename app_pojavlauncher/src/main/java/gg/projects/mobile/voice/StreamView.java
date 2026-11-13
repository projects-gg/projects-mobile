package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

/**
 * MOB-WATCH: the stream screen's rules apart from Android, so they are tested (the desktop's
 * {@code features/share/stream-view.ts}, the lead's {@code sqol.md} §1.1): which layer the screen asks for ("Yayın
 * Kalitesi"), how long a weak connection holds the small layer, and how pinch-zoom and drag move the picture.
 *
 * <p>The phone's "Otomatik" (share.md §7, "{@code q} mobil veride, {@code f} Wi-Fi'da"): the small layer on mobile data
 * (any network Android calls metered: a phone's own data, or a hotspot), in a small view (picture-in-picture) or while
 * the connection is weak; the full layer on Wi-Fi in a large view, and while zoomed in to read small text there.
 */
public final class StreamView {
    private StreamView() {}

    /** "Yayın Kalitesi" ({@code viewQuality}). */
    public static final String AUTO = "auto";
    public static final String HIGH = "high";
    public static final String SAVER = "saver";

    /** The share's layers (pl-share's RIDs): full, and the small one (640 × 360, 15 fps at most, 400 kbps). */
    public static final String FULL = "f";
    public static final String SMALL = "q";

    /**
     * Under "Otomatik", a view narrower than this (physical pixels) takes the small layer: its 640 pixels cover such a view
     * nearly one to one (the desktop's 480 CSS pixels at a common 1.5 scale). Picture-in-picture is about 700 wide.
     */
    public static final int SMALL_VIEW_PX = 720;

    /** A saved choice, read leniently (anything unknown is "Otomatik"). */
    public static String quality(@Nullable String saved) {
        if (HIGH.equals(saved)) return HIGH;
        if (SAVER.equals(saved)) return SAVER;
        return AUTO;
    }

    /** A layer as the room says it ({@code f} or {@code q}); anything else is the full one. */
    public static String layer(@Nullable String wire) {
        return SMALL.equals(wire) ? SMALL : FULL;
    }

    /**
     * The layer the screen asks for: {@code viewPx} the picture's width on screen (0: not measured yet), {@code metered}
     * the network costs (mobile data), {@code weakHeld} the weak connection's hold ({@link WeakHold}), {@code zoomed}
     * pinch-zoomed in.
     */
    public static String layerFor(String quality, int viewPx, boolean metered, boolean weakHeld, boolean zoomed) {
        if (HIGH.equals(quality)) return FULL;
        if (SAVER.equals(quality)) return SMALL;
        if (metered || weakHeld) return SMALL;
        if (zoomed) return FULL;
        return viewPx > 0 && viewPx < SMALL_VIEW_PX ? SMALL : FULL;
    }

    // ---- a weak connection holds the small layer ----

    /** A weak connection under "Otomatik": the small layer, and the full one back after this long good. */
    public static final long WEAK_BACK_MS = 30_000;
    /** Weak again within this long after the full layer came back: the next wait doubles… */
    public static final long WEAK_AGAIN_MS = 60_000;
    /** …up to this. */
    public static final long WEAK_BACK_MAX_MS = 120_000;

    /**
     * Whether a weak connection holds the small layer. Without it the screen would flip between the layers: the small one
     * carries less, the connection looks good again, the full one makes it weak again.
     */
    public static final class WeakHold {
        private boolean held;
        /** Since when the connection is good again while held (-1: not yet). */
        private long goodSince = -1;
        private long waitMs = WEAK_BACK_MS;
        /** When the full layer last came back (-1: never). */
        private long backAt = -1;

        public boolean held() {
            return held;
        }

        /** One quality reading ({@code now} in milliseconds); whether {@link #held} changed. */
        public boolean step(boolean weak, long now) {
            if (weak) {
                goodSince = -1;
                if (held) return false;
                boolean again = backAt >= 0 && now - backAt < WEAK_AGAIN_MS;
                waitMs = again ? Math.min(waitMs * 2, WEAK_BACK_MAX_MS) : WEAK_BACK_MS;
                held = true;
                return true;
            }
            if (!held) return false;
            if (goodSince < 0) goodSince = now;
            if (now - goodSince >= waitMs) {
                held = false;
                goodSince = -1;
                backAt = now;
                return true;
            }
            return false;
        }

        /** When the hold lets the full layer back if nothing changes meanwhile (-1: not counting). */
        public long backAt() {
            return held && goodSince >= 0 ? goodSince + waitMs : -1;
        }

        long waitMs() {
            return waitMs;
        }
    }

    // ---- zoom ----

    /** "%400" at most. */
    public static final float ZOOM_MAX = 4f;
    /** A zoom this close to 1 is none. */
    static final float ZOOM_SNAP = 1.02f;

    /**
     * The picture's place on screen: its scale over the fitted size and its top left corner in the view's pixels. A picture
     * zoomed in may use the whole view (the bars beside a fitted picture too), never shows an empty edge where it could
     * cover the view, and stays centred along a side it does not cover.
     */
    public static final class Zoom {
        public final float s;
        public final float x;
        public final float y;

        public Zoom(float s, float x, float y) {
            this.s = s;
            this.x = x;
            this.y = y;
        }

        public boolean zoomed() {
            return s > 1f;
        }

        /** The zoom as its chip says it (250 for "%250"). */
        public int percent() {
            return Math.round(s * 100f);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Zoom)) return false;
            Zoom z = (Zoom) other;
            return Math.abs(s - z.s) < 1e-4f && Math.abs(x - z.x) < 0.01f && Math.abs(y - z.y) < 0.01f;
        }

        @Override
        public int hashCode() {
            return Math.round(s * 1000f);
        }

        @Override
        public String toString() {
            return "Zoom(" + s + ", " + x + ", " + y + ")";
        }
    }

    /**
     * The picture's size fitted into the view (letterboxed): {@code {width, height}} for a picture of {@code w} × {@code
     * h} (16:9 while its size is not known yet).
     */
    public static float[] fit(int w, int h, float viewW, float viewH) {
        float pw = w > 0 && h > 0 ? w : 16f;
        float ph = w > 0 && h > 0 ? h : 9f;
        if (viewW <= 0 || viewH <= 0) return new float[] {0f, 0f};
        float scale = Math.min(viewW / pw, viewH / ph);
        return new float[] {pw * scale, ph * scale};
    }

    /** No zoom: the fitted picture in the middle of the view. */
    public static Zoom none(float fitW, float fitH, float viewW, float viewH) {
        return new Zoom(1f, (viewW - fitW) / 2f, (viewH - fitH) / 2f);
    }

    /** Keeps the picture where it may be (see {@link Zoom}); a zoom close to 1 is none. */
    public static Zoom clamp(Zoom zoom, float fitW, float fitH, float viewW, float viewH) {
        float s = Math.max(1f, Math.min(ZOOM_MAX, zoom.s));
        if (s < ZOOM_SNAP) return none(fitW, fitH, viewW, viewH);
        return new Zoom(s, clampAxis(zoom.x, fitW * s, viewW), clampAxis(zoom.y, fitH * s, viewH));
    }

    private static float clampAxis(float at, float size, float view) {
        if (size <= view) return (view - size) / 2f;
        return Math.max(view - size, Math.min(0f, at));
    }

    /** Zooms by {@code factor} keeping the point ({@code px}, {@code py}) of the view where it is (the pinch's focus). */
    public static Zoom zoomAt(Zoom zoom, float factor, float px, float py, float fitW, float fitH, float viewW, float viewH) {
        float s = Math.max(1f, Math.min(ZOOM_MAX, zoom.s * factor));
        // The picture's own point under (px, py), at its fitted size.
        float cx = (px - zoom.x) / zoom.s;
        float cy = (py - zoom.y) / zoom.s;
        return clamp(new Zoom(s, px - cx * s, py - cy * s), fitW, fitH, viewW, viewH);
    }

    /** Moves a zoomed picture (a drag); nothing moves at no zoom. */
    public static Zoom panBy(Zoom zoom, float dx, float dy, float fitW, float fitH, float viewW, float viewH) {
        if (!zoom.zoomed()) return zoom;
        return clamp(new Zoom(zoom.s, zoom.x + dx, zoom.y + dy), fitW, fitH, viewW, viewH);
    }

    /**
     * The double tap's zoom: back to none when zoomed, else 2× around the tapped point (YouTube's and Photos' habit; "sıfırla"
     * is the brief's word for the zoomed case).
     */
    public static Zoom doubleTap(Zoom zoom, float px, float py, float fitW, float fitH, float viewW, float viewH) {
        if (zoom.zoomed()) return none(fitW, fitH, viewW, viewH);
        return zoomAt(zoom, 2f, px, py, fitW, fitH, viewW, viewH);
    }
}
