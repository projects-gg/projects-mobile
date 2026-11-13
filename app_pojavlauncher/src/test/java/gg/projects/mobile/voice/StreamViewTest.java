package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** MOB-WATCH: the stream screen's layer choice, the weak connection's hold and pinch-zoom (the desktop's stream-view.ts). */
public class StreamViewTest {
    private static final float EPS = 0.01f;

    @Test
    public void aSavedQualityReadsLeniently() {
        assertEquals(StreamView.AUTO, StreamView.quality(null));
        assertEquals(StreamView.AUTO, StreamView.quality("best"));
        assertEquals(StreamView.HIGH, StreamView.quality("high"));
        assertEquals(StreamView.SAVER, StreamView.quality("saver"));
        assertEquals(StreamView.SMALL, StreamView.layer("q"));
        assertEquals(StreamView.FULL, StreamView.layer("x"));
    }

    @Test
    public void otomatikSavesOnMobileDataInSmallViewsAndOnWeakConnections() {
        // "Yüksek" and "Veri Tasarrufu" always ask for their own layer.
        assertEquals(StreamView.FULL, StreamView.layerFor(StreamView.HIGH, 300, true, true, false));
        assertEquals(StreamView.SMALL, StreamView.layerFor(StreamView.SAVER, 2400, false, false, true));
        // "Otomatik": Wi-Fi in a large view is the full picture…
        assertEquals(StreamView.FULL, StreamView.layerFor(StreamView.AUTO, 1080, false, false, false));
        // …mobile data, a weak connection or picture-in-picture the small one.
        assertEquals(StreamView.SMALL, StreamView.layerFor(StreamView.AUTO, 2400, true, false, false));
        assertEquals(StreamView.SMALL, StreamView.layerFor(StreamView.AUTO, 2400, false, true, false));
        assertEquals(StreamView.SMALL, StreamView.layerFor(StreamView.AUTO, 700, false, false, false));
        // Zoomed in to read small text on Wi-Fi: the full picture even in a small view; mobile data still saves.
        assertEquals(StreamView.FULL, StreamView.layerFor(StreamView.AUTO, 700, false, false, true));
        assertEquals(StreamView.SMALL, StreamView.layerFor(StreamView.AUTO, 700, true, false, true));
        // Not measured yet: the full picture (as the desktop's tile).
        assertEquals(StreamView.FULL, StreamView.layerFor(StreamView.AUTO, 0, false, false, false));
        assertEquals(StreamView.FULL, StreamView.layerFor(StreamView.AUTO, StreamView.SMALL_VIEW_PX, false, false, false));
    }

    @Test
    public void aWeakConnectionHoldsTheSmallLayerAndWaitsLongerEachTime() {
        StreamView.WeakHold hold = new StreamView.WeakHold();
        assertFalse(hold.step(false, 0));
        assertTrue(hold.step(true, 1_000));
        assertTrue(hold.held());
        // Weak again while held: nothing changes; good again: counting starts.
        assertFalse(hold.step(true, 2_000));
        assertFalse(hold.step(false, 3_000));
        assertEquals(3_000 + StreamView.WEAK_BACK_MS, hold.backAt());
        assertFalse(hold.step(false, 3_000 + StreamView.WEAK_BACK_MS - 1));
        // A weak reading meanwhile starts the count over.
        assertFalse(hold.step(true, 20_000));
        assertEquals(-1, hold.backAt());
        assertFalse(hold.step(false, 21_000));
        assertTrue(hold.step(false, 21_000 + StreamView.WEAK_BACK_MS));
        assertFalse(hold.held());
        long back = 21_000 + StreamView.WEAK_BACK_MS;
        // Weak again within a minute of coming back: the wait doubles, up to two minutes.
        assertTrue(hold.step(true, back + 10_000));
        assertEquals(2 * StreamView.WEAK_BACK_MS, hold.waitMs());
        hold.step(false, back + 11_000);
        assertTrue(hold.step(false, back + 11_000 + 2 * StreamView.WEAK_BACK_MS));
        long again = back + 11_000 + 2 * StreamView.WEAK_BACK_MS;
        assertTrue(hold.step(true, again + 1_000));
        assertEquals(StreamView.WEAK_BACK_MAX_MS, hold.waitMs());
        hold.step(false, again + 2_000);
        assertTrue(hold.step(false, again + 2_000 + StreamView.WEAK_BACK_MAX_MS));
        // Weak long after: the first wait again.
        assertTrue(hold.step(true, again + 10 * 60_000L));
        assertEquals(StreamView.WEAK_BACK_MS, hold.waitMs());
    }

    @Test
    public void thePictureFitsTheViewAndZoomsAroundTheFingers() {
        // A 1280 × 720 picture on a 1080 × 2400 portrait phone: as wide as the screen, in its middle.
        float[] fit = StreamView.fit(1280, 720, 1080, 2400);
        assertEquals(1080f, fit[0], EPS);
        assertEquals(607.5f, fit[1], EPS);
        StreamView.Zoom none = StreamView.none(fit[0], fit[1], 1080, 2400);
        assertEquals(1f, none.s, EPS);
        assertEquals(0f, none.x, EPS);
        assertEquals(896.25f, none.y, EPS);
        assertFalse(none.zoomed());
        // Unknown size: 16:9.
        float[] unknown = StreamView.fit(0, 0, 1600, 900);
        assertEquals(1600f, unknown[0], EPS);
        assertEquals(900f, unknown[1], EPS);

        // Two fingers at the picture's middle, twice as big: it stays under them, wider than the screen, still centred
        // up and down where it does not fill the screen.
        StreamView.Zoom twice = StreamView.zoomAt(none, 2f, 540f, 1200f, fit[0], fit[1], 1080, 2400);
        assertEquals(2f, twice.s, EPS);
        assertEquals(-540f, twice.x, EPS);
        assertEquals((2400f - 1215f) / 2f, twice.y, EPS);
        assertEquals(200, twice.percent());
        // A drag moves it, never past its edge.
        StreamView.Zoom moved = StreamView.panBy(twice, 300f, 50f, fit[0], fit[1], 1080, 2400);
        assertEquals(-240f, moved.x, EPS);
        assertEquals(twice.y, moved.y, EPS);
        StreamView.Zoom edge = StreamView.panBy(twice, 5_000f, 0f, fit[0], fit[1], 1080, 2400);
        assertEquals(0f, edge.x, EPS);
        StreamView.Zoom other = StreamView.panBy(twice, -5_000f, 0f, fit[0], fit[1], 1080, 2400);
        assertEquals(1080f - 2160f, other.x, EPS);
        // Nothing moves at no zoom.
        assertEquals(none, StreamView.panBy(none, 100f, 100f, fit[0], fit[1], 1080, 2400));
        // %400 at most; a hair over 1 is none.
        StreamView.Zoom max = StreamView.zoomAt(twice, 10f, 540f, 1200f, fit[0], fit[1], 1080, 2400);
        assertEquals(StreamView.ZOOM_MAX, max.s, EPS);
        StreamView.Zoom back = StreamView.zoomAt(twice, 0.505f, 540f, 1200f, fit[0], fit[1], 1080, 2400);
        assertEquals(none, back);
    }

    @Test
    public void aDoubleTapZoomsInWhereItIsAndBackToNone() {
        float[] fit = StreamView.fit(1920, 1080, 2400, 1080);
        StreamView.Zoom none = StreamView.none(fit[0], fit[1], 2400, 1080);
        StreamView.Zoom in = StreamView.doubleTap(none, 600f, 300f, fit[0], fit[1], 2400, 1080);
        assertEquals(2f, in.s, EPS);
        assertTrue(in.zoomed());
        // A landscape picture zoomed in may use the bars beside it: it covers the whole screen.
        assertTrue(in.x <= 0f && in.x + fit[0] * in.s >= 2400f);
        assertEquals(none, StreamView.doubleTap(in, 10f, 10f, fit[0], fit[1], 2400, 1080));
    }
}
