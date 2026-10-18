package gg.projects.mobile.ui;

import android.view.HapticFeedbackConstants;

/**
 * Which {@link HapticFeedbackConstants} value each kind of touch feedback uses on a given Android
 * version (M14; the owner's mobile rule of 18 October 2026: clear feedback on press and while
 * held). The constants are compile-time values, so this class is plain Java and JVM-tested.
 *
 * <p>From light to strong: a row press and a slider step tick, a button press clicks and its
 * release ticks back (Android's own navigation keys do the same), a switch toggles, the play
 * actions confirm or reject, a long press is the heaviest.
 */
public final class HapticMap {
    private HapticMap() {}

    /** No feedback for this kind on this version. */
    public static final int NONE = -1;

    public enum Kind {
        /** A button, chip or icon button went down. */
        PRESS,
        /** ...and came back up (Android 8.1+). */
        RELEASE,
        /** A full-width row or card went down: lighter than a button. */
        ROW_PRESS,
        /** A slider moved by a step. */
        TICK,
        TOGGLE_ON,
        TOGGLE_OFF,
        /** The action was accepted: the game starts, the join begins. */
        CONFIRM,
        /** The action was refused: something is still running, no account yet. */
        REJECT,
        LONG_PRESS
    }

    public static int constant(Kind kind, int sdk) {
        switch (kind) {
            case PRESS:
                return HapticFeedbackConstants.VIRTUAL_KEY;
            case RELEASE:
                return sdk >= 27 ? HapticFeedbackConstants.VIRTUAL_KEY_RELEASE : NONE;
            case ROW_PRESS:
                return sdk >= 23 ? HapticFeedbackConstants.CONTEXT_CLICK : HapticFeedbackConstants.KEYBOARD_TAP;
            case TICK:
                return sdk >= 34 ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK;
            case TOGGLE_ON:
                return sdk >= 34 ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.KEYBOARD_TAP;
            case TOGGLE_OFF:
                return sdk >= 34 ? HapticFeedbackConstants.TOGGLE_OFF : HapticFeedbackConstants.KEYBOARD_TAP;
            case CONFIRM:
                return sdk >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
            case REJECT:
                return sdk >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS;
            case LONG_PRESS:
            default:
                return HapticFeedbackConstants.LONG_PRESS;
        }
    }

    /**
     * Slider steps arrive faster than a motor can tick: at most one tick per {@code minGapMs}.
     * Returns true when a tick may play at {@code now}.
     */
    public static boolean tickDue(long lastTickAt, long now, long minGapMs) {
        return lastTickAt <= 0 || now - lastTickAt >= minGapMs || now < lastTickAt;
    }
}
