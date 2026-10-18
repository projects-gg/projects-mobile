package gg.projects.mobile.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.HapticFeedbackConstants;

import org.junit.Test;

/** M14: which haptic effect each touch plays on each Android version (HapticMap). */
public class HapticMapTest {
    private static int c(HapticMap.Kind kind, int sdk) {
        return HapticMap.constant(kind, sdk);
    }

    @Test
    public void buttonsClickDownAndTickUpLikeTheNavigationKeys() {
        for (int sdk : new int[]{21, 26, 27, 30, 34, 37}) {
            assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, c(HapticMap.Kind.PRESS, sdk));
        }
        assertEquals("no release effect before 8.1", HapticMap.NONE, c(HapticMap.Kind.RELEASE, 26));
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE, c(HapticMap.Kind.RELEASE, 27));
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE, c(HapticMap.Kind.RELEASE, 37));
    }

    @Test
    public void rowsAndSlidersAreLighterThanButtons() {
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, c(HapticMap.Kind.ROW_PRESS, 22));
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, c(HapticMap.Kind.ROW_PRESS, 23));
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, c(HapticMap.Kind.TICK, 21));
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, c(HapticMap.Kind.TICK, 33));
        assertEquals(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, c(HapticMap.Kind.TICK, 34));
    }

    @Test
    public void switchesToggleAndPlayActionsConfirmOrRejectWhereAndroidHasThem() {
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, c(HapticMap.Kind.TOGGLE_ON, 33));
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, c(HapticMap.Kind.TOGGLE_ON, 34));
        assertEquals(HapticFeedbackConstants.TOGGLE_OFF, c(HapticMap.Kind.TOGGLE_OFF, 34));
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, c(HapticMap.Kind.CONFIRM, 29));
        assertEquals(HapticFeedbackConstants.CONFIRM, c(HapticMap.Kind.CONFIRM, 30));
        assertEquals("a refusal is felt as a heavier buzz before Android 11",
                HapticFeedbackConstants.LONG_PRESS, c(HapticMap.Kind.REJECT, 29));
        assertEquals(HapticFeedbackConstants.REJECT, c(HapticMap.Kind.REJECT, 30));
        assertEquals(HapticFeedbackConstants.LONG_PRESS, c(HapticMap.Kind.LONG_PRESS, 21));
    }

    @Test
    public void aReleaseTickDoesNotCutAConfirmOrRejectShort() {
        assertTrue("no confirm yet", HapticMap.releaseDue(0, 1000));
        assertFalse("the release right after the click's confirm", HapticMap.releaseDue(1000, 1030));
        assertTrue(HapticMap.releaseDue(1000, 1000 + HapticMap.STRONG_HOLD_MS));
        assertTrue("a clock that went back does not silence the release", HapticMap.releaseDue(5000, 1000));
    }

    @Test
    public void sliderTicksAreSpacedOut() {
        assertTrue("the first tick plays", HapticMap.tickDue(0, 1000, 35));
        assertFalse(HapticMap.tickDue(1000, 1020, 35));
        assertTrue(HapticMap.tickDue(1000, 1035, 35));
        assertTrue("a clock that went back does not silence the slider", HapticMap.tickDue(5000, 1000, 35));
    }
}
