package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** MOB-WATCH: a watched stream's gain, the duck and how both move (the desktop's stream-audio.ts). */
public class StreamSoundTest {
    private static final double EPS = 1e-6;

    @Test
    public void theGainIsTheStreamersVolumeUpTo200Percent() {
        assertEquals(1.0, StreamSound.gain(100, false, false), EPS);
        assertEquals(1.5, StreamSound.gain(150, false, false), EPS);
        assertEquals(2.0, StreamSound.gain(200, false, false), EPS);
        assertEquals(2.0, StreamSound.gain(260, false, false), EPS);
        assertEquals(0.0, StreamSound.gain(-5, false, false), EPS);
        // "Yayını Sessize Al" and deafened: nothing.
        assertEquals(0.0, StreamSound.gain(150, true, false), EPS);
        assertEquals(0.0, StreamSound.gain(150, false, true), EPS);
    }

    @Test
    public void theDuckLowersOnlyWhileSomeoneTheyHearSpeaks() {
        // Off (the default): never.
        assertEquals(1.0, StreamSound.duck(false, 60, true, true, true), EPS);
        // On: someone else speaks, 60 % quieter.
        assertEquals(1.0, StreamSound.duck(true, 60, false, false, false), EPS);
        assertEquals(0.4, StreamSound.duck(true, 60, true, false, false), EPS);
        // "Kısma Miktarı" between 20 and 90 %.
        assertEquals(0.8, StreamSound.duck(true, 5, true, false, false), EPS);
        assertEquals(0.1, StreamSound.duck(true, 99, true, false, false), EPS);
        // The player's own voice only with "Ben Konuşunca da Kıs".
        assertEquals(1.0, StreamSound.duck(true, 60, false, false, true), EPS);
        assertEquals(0.4, StreamSound.duck(true, 60, false, true, true), EPS);
        // Whom the player silenced (muted for them, ignored, muted for everyone) is never a reason.
        assertFalse(StreamSound.othersSpeaking(Arrays.asList("me", "muted"), "me", Collections.singletonList("muted")));
        assertTrue(StreamSound.othersSpeaking(Arrays.asList("muted", "friend"), "me", Collections.singletonList("muted")));
        assertFalse(StreamSound.othersSpeaking(Collections.<String>emptyList(), "me", Collections.<String>emptyList()));
    }

    @Test
    public void theDuckGoesDownIn80MsAndBackUpIn700Ms() {
        StreamSound.Ramp ramp = new StreamSound.Ramp(1.0);
        ramp.advance(0);
        assertFalse(ramp.moving());
        ramp.set(1.0, 0.4, 0);
        assertTrue(ramp.moving());
        // In steps of 10 ms: about 95 % of the way down after 80 ms (three time constants).
        double gain = 1;
        for (long t = 10; t <= 80; t += StreamSound.STEP_MS) gain = ramp.advance(t);
        assertEquals(0.4 + 0.6 * Math.exp(-3), gain, 1e-3);
        for (long t = 90; t <= 400; t += StreamSound.STEP_MS) ramp.advance(t);
        assertFalse(ramp.moving());
        assertEquals(0.4, ramp.gain(), EPS);
        // Back up: about 95 % after 700 ms, never in a jump.
        ramp.set(1.0, 1.0, 400);
        double first = ramp.advance(410);
        assertTrue(first > 0.4 && first < 0.45);
        for (long t = 420; t <= 1_100; t += StreamSound.STEP_MS) gain = ramp.advance(t);
        assertEquals(1.0 - 0.6 * Math.exp(-3), gain, 2e-3);
        for (long t = 1_110; t <= 3_000; t += StreamSound.STEP_MS) ramp.advance(t);
        assertFalse(ramp.moving());
        assertEquals(1.0, ramp.gain(), EPS);
    }

    @Test
    public void aVolumeChangeGlidesAndBothPartsMultiply() {
        StreamSound.Ramp ramp = new StreamSound.Ramp(1.0);
        ramp.advance(0);
        ramp.set(2.0, 0.5, 0);
        // The volume glides (a 15 ms time constant): there within a tenth of a second, while the duck still moves.
        ramp.advance(100);
        assertEquals(2.0, ramp.gain() / ramp.duck(), EPS);
        for (long t = 110; t <= 500; t += StreamSound.STEP_MS) ramp.advance(t);
        assertEquals(1.0, ramp.gain(), EPS);
        // A stream that starts jumps to its level (no glide up from silence).
        ramp.set(0.0, 1.0, 1_000);
        ramp.settle(1_000);
        assertEquals(0.0, ramp.gain(), EPS);
        assertFalse(ramp.moving());
    }

    @Test
    public void aStepReachesItsTargetAndStops() {
        assertEquals(1.0, StreamSound.step(0.999, 1.0, 0.1, 10), EPS);
        assertEquals(0.5, StreamSound.step(0.0, 0.5, 0, 10), EPS);
        double next = StreamSound.step(0.0, 1.0, 0.1, 10);
        assertEquals(1 - Math.exp(-0.1), next, EPS);
        assertEquals(StreamSound.DUCK_ATTACK_S / 3, StreamSound.duckTau(1.0, 0.4), EPS);
        assertEquals(StreamSound.DUCK_RELEASE_S / 3, StreamSound.duckTau(0.4, 1.0), EPS);
        assertEquals(20, StreamSound.clampDuckLevel(0));
        assertEquals(90, StreamSound.clampDuckLevel(100));
        assertEquals(200, StreamSound.clampVolume(500));
    }
}
