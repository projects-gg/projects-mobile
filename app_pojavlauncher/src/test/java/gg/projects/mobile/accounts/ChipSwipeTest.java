package gg.projects.mobile.accounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The account chip's swipe (SWIPE-M): taps, holds and vertical drags stay the spinner's (its list), sideways swipes switch
 * accounts by distance or by a flick, and the motion's numbers never jump.
 */
public class ChipSwipeTest {
    /** A 440 dpi phone: 2.75 px per dp, Android's 8 dp touch slop, the list's hold at 250 ms (tap 100 + long press 400). */
    private static final float DP = 2.75f;
    private static final float SLOP = 8 * DP;
    private static final long HOLD = ChipSwipe.holdFor(250);

    private static ChipSwipe swipe() {
        return new ChipSwipe(DP, SLOP, HOLD);
    }

    /** A finger from (x, 100) moving {@code dxDp} sideways in {@code steps} even steps of {@code stepMs}, then lifted. */
    private static ChipSwipe.Result drag(ChipSwipe s, boolean rtl, float dxDp, int steps, long stepMs) {
        float x = 300;
        s.down(x, 100, 1000, rtl);
        long t = 1000;
        for (int i = 1; i <= steps; i++) {
            t += stepMs;
            s.move(x + dxDp * DP * i / steps, 100 + i * 0.2f, t);
        }
        return s.up(x + dxDp * DP, 100, t + 8);
    }

    @Test
    public void theListHoldsAFewMomentsLaterThanTheSwipeDecides() {
        assertEquals(210, HOLD);
        assertEquals(460, ChipSwipe.holdFor(500));
        assertEquals(60, ChipSwipe.holdFor(70));
    }

    @Test
    public void aTapIsNotASwipe() {
        ChipSwipe s = swipe();
        s.down(300, 100, 0, false);
        assertEquals(ChipSwipe.Touch.UNDECIDED, s.move(305, 102, 40));
        assertEquals(ChipSwipe.Touch.UNDECIDED, s.move(300 + SLOP, 103, 60));
        assertEquals(ChipSwipe.Result.NONE, s.up(300 + SLOP, 103, 90));
        assertEquals(0f, s.followOf(SLOP), 0f);
    }

    @Test
    public void aHoldStaysTheListsEvenWhenTheFingerMovesSidewaysAfterIt() {
        ChipSwipe s = swipe();
        s.down(300, 100, 0, false);
        assertEquals(ChipSwipe.Touch.UNDECIDED, s.move(303, 100, HOLD - 1));
        assertEquals(ChipSwipe.Touch.OTHER, s.move(304, 100, HOLD));
        // The list opened by its hold: dragging across it picks a row, it never switches the account.
        assertEquals(ChipSwipe.Touch.OTHER, s.move(300 - 40 * DP, 100, HOLD + 80));
        assertEquals(ChipSwipe.Result.NONE, s.up(300 - 40 * DP, 100, HOLD + 120));
    }

    @Test
    public void aSwipeThatStartsAfterTheHoldTimeIsAHold() {
        ChipSwipe s = swipe();
        s.down(300, 100, 0, false);
        assertEquals(ChipSwipe.Touch.OTHER, s.move(300 - 30 * DP, 100, HOLD + 5));
        assertEquals(ChipSwipe.Result.NONE, s.up(300 - 30 * DP, 100, HOLD + 20));
    }

    @Test
    public void aVerticalDragStaysTheListsDragToOpen() {
        ChipSwipe s = swipe();
        s.down(300, 100, 0, false);
        assertEquals(ChipSwipe.Touch.OTHER, s.move(300 + 3 * DP, 100 + 12 * DP, 50));
        assertEquals(ChipSwipe.Touch.OTHER, s.move(300 - 60 * DP, 100 + 14 * DP, 120));
        assertEquals(ChipSwipe.Result.NONE, s.up(300 - 60 * DP, 100, 160));
    }

    @Test
    public void aDiagonalIsNotASwipeUntilItIsClearlySideways() {
        ChipSwipe s = swipe();
        s.down(300, 100, 0, false);
        // 9 dp across and 8 dp down: more across, but not enough to tell.
        assertEquals(ChipSwipe.Touch.UNDECIDED, s.move(300 - 9 * DP, 100 + 8 * DP, 30));
        // At 45 degrees past the slop the drag goes down: the list's.
        assertEquals(ChipSwipe.Touch.OTHER, s.move(300 - 20 * DP, 100 + 20 * DP, 60));

        ChipSwipe t = swipe();
        t.down(300, 100, 0, false);
        assertEquals(ChipSwipe.Touch.UNDECIDED, t.move(300 - 9 * DP, 100 + 8 * DP, 30));
        assertEquals(ChipSwipe.Touch.SWIPE, t.move(300 - 20 * DP, 100 + 10 * DP, 60));
    }

    @Test
    public void aTouchLeavingTheChipUndecidedIsDecidedByItsLargerWay() {
        // 9.5 dp across and 8.5 dp down: still undecided, but the finger is about to leave the chip.
        ChipSwipe across = swipe();
        across.down(300, 100, 0, false);
        assertEquals(ChipSwipe.Touch.UNDECIDED, across.move(300 + 9.5f * DP, 100 + 8.5f * DP, 40));
        assertEquals(ChipSwipe.Touch.SWIPE, across.decideNow());
        assertEquals(ChipSwipe.Touch.SWIPE, across.decideNow());

        ChipSwipe down = swipe();
        down.down(300, 100, 0, false);
        assertEquals(ChipSwipe.Touch.UNDECIDED, down.move(300 + 7.5f * DP, 100 + 7 * DP, 40));
        // Not past the slop sideways: the list's.
        assertEquals(ChipSwipe.Touch.OTHER, down.decideNow());

        ChipSwipe decided = swipe();
        decided.down(300, 100, 0, false);
        assertEquals(ChipSwipe.Touch.OTHER, decided.move(300, 100 + 20 * DP, 40));
        assertEquals(ChipSwipe.Touch.OTHER, decided.decideNow());
    }

    @Test
    public void swipingLeftIsTheNextAccountAndRightThePreviousOne() {
        assertEquals(ChipSwipe.Result.NEXT, drag(swipe(), false, -30, 6, 16));
        assertEquals(ChipSwipe.Result.PREVIOUS, drag(swipe(), false, 30, 6, 16));
    }

    @Test
    public void rightToLeftMirrorsTheDirections() {
        assertEquals(ChipSwipe.Result.PREVIOUS, drag(swipe(), true, -30, 6, 16));
        assertEquals(ChipSwipe.Result.NEXT, drag(swipe(), true, 30, 6, 16));
        assertEquals(-1, ChipSwipe.sign(ChipSwipe.Result.NEXT, false));
        assertEquals(1, ChipSwipe.sign(ChipSwipe.Result.PREVIOUS, false));
        assertEquals(1, ChipSwipe.sign(ChipSwipe.Result.NEXT, true));
        assertEquals(-1, ChipSwipe.sign(ChipSwipe.Result.PREVIOUS, true));
        assertEquals(0, ChipSwipe.sign(ChipSwipe.Result.BACK, false));
        assertEquals(1, ChipSwipe.step(ChipSwipe.Result.NEXT));
        assertEquals(-1, ChipSwipe.step(ChipSwipe.Result.PREVIOUS));
        assertEquals(0, ChipSwipe.step(ChipSwipe.Result.BACK));
        assertEquals(0, ChipSwipe.step(ChipSwipe.Result.NONE));
    }

    @Test
    public void aSlowSwipeSwitchesFromTwentyFourDp() {
        // 25 dp over 400 ms, then the finger rests 150 ms before it lifts: no flick, the distance decides.
        ChipSwipe s = swipe();
        s.down(300, 100, 0, false);
        long t = 0;
        for (int i = 1; i <= 20; i++) {
            t += 20;
            s.move(300 - 25 * DP * i / 20, 100, t);
        }
        assertEquals(ChipSwipe.Result.NEXT, s.up(300 - 25 * DP, 100, t + 150));

        ChipSwipe short_ = swipe();
        short_.down(300, 100, 0, false);
        t = 0;
        for (int i = 1; i <= 20; i++) {
            t += 20;
            short_.move(300 - 20 * DP * i / 20, 100, t);
        }
        assertEquals(ChipSwipe.Result.BACK, short_.up(300 - 20 * DP, 100, t + 150));
    }

    @Test
    public void aQuickFlickSwitchesHoweverShortItIs() {
        // 14 dp in 32 ms: about 440 dp/s.
        assertEquals(ChipSwipe.Result.NEXT, drag(swipe(), false, -14, 4, 8));
        assertEquals(ChipSwipe.Result.PREVIOUS, drag(swipe(), false, 14, 4, 8));
    }

    @Test
    public void aFlickBackCancelsTheSwipe() {
        ChipSwipe s = swipe();
        s.down(300, 100, 0, false);
        s.move(300 - 30 * DP, 100, 60);
        s.move(300 - 60 * DP, 100, 120);
        // Back towards the start, fast (30 dp in 50 ms), and let go still past the switching distance.
        s.move(300 - 45 * DP, 100, 150);
        s.move(300 - 30 * DP, 100, 170);
        assertTrue(Math.abs(s.travel()) >= ChipSwipe.COMMIT_DP * DP);
        assertEquals(ChipSwipe.Result.BACK, s.up(300 - 30 * DP, 100, 174));
    }

    @Test
    public void theReleaseSpeedIsTheLastHundredMilliseconds() {
        ChipSwipe s = swipe();
        s.down(0, 0, 0, false);
        s.move(-100, 0, 100);
        s.move(-100, 0, 300);
        s.move(-130, 0, 340);
        s.move(-160, 0, 380);
        // Samples within 100 ms of the newest (380): 300, 340, 380.
        assertEquals(-60f / 80f, s.velocity(), 1e-4f);
        ChipSwipe still = swipe();
        still.down(0, 0, 0, false);
        assertEquals(0f, still.velocity(), 0f);
        still.move(-50, 0, 40);
        still.move(-50, 0, 400);
        // The finger stopped: the only step is long ago and flat.
        assertEquals(0f, still.velocity(), 0f);
    }

    @Test
    public void theFaceFollowsWithoutAJumpAndStretchesLikeARubberBand() {
        ChipSwipe s = swipe();
        float shift = ChipSwipe.SHIFT_DP * DP;
        assertEquals(0f, s.followOf(0), 0f);
        assertEquals(0f, s.followOf(-SLOP), 0f);
        assertEquals(-shift, s.followOf(-ChipSwipe.COMMIT_DP * DP), 1e-3f);
        assertEquals(shift, s.followOf(ChipSwipe.COMMIT_DP * DP), 1e-3f);
        // Continuous at the commit distance and slower past it.
        float at = s.followOf(ChipSwipe.COMMIT_DP * DP);
        float past = s.followOf(ChipSwipe.COMMIT_DP * DP + 10 * DP);
        assertEquals(at + 10 * DP * ChipSwipe.RUBBER, past, 1e-3f);
        assertEquals(shift * ChipSwipe.FOLLOW_MAX, s.followOf(400 * DP), 1e-3f);
        // Never decreasing along the way.
        float last = 0;
        for (int px = 0; px < 600; px++) {
            float now = s.followOf(px);
            assertTrue(px + " px", now >= last);
            last = now;
        }
        assertEquals(1f, ChipSwipe.followAlpha(0, DP), 0f);
        assertEquals(0.6f, ChipSwipe.followAlpha(-shift, DP), 1e-4f);
        assertEquals(0.6f, ChipSwipe.followAlpha(shift * 1.5f, DP), 1e-4f);
    }

    @Test
    public void theFaceLeavesOnFromWhereItIsAndTheNextComesFromTheOtherSide() {
        float shift = ChipSwipe.SHIFT_DP * DP;
        // From rest: a full shift.
        assertEquals(-shift, ChipSwipe.exitShift(0, -1, DP), 1e-3f);
        assertEquals(shift, ChipSwipe.exitShift(0, 1, DP), 1e-3f);
        // Dragged a full shift: half a shift more.
        assertEquals(-1.5f * shift, ChipSwipe.exitShift(-shift, -1, DP), 1e-3f);
        // Coming in from the right and swiped left before it settled: it turns and leaves left a full shift.
        assertEquals(-shift, ChipSwipe.exitShift(shift * 0.5f, -1, DP), 1e-3f);
        assertEquals(shift, ChipSwipe.enterShift(-1, DP), 1e-3f);
        assertEquals(-shift, ChipSwipe.enterShift(1, DP), 1e-3f);
        assertEquals(120, ChipSwipe.outMs(1f));
        assertEquals(72, ChipSwipe.outMs(0.6f));
        assertEquals(60, ChipSwipe.outMs(0.1f));
        assertEquals(120, ChipSwipe.outMs(1.4f));
        assertTrue(ChipSwipe.OUT_MS < ChipSwipe.IN_MS);
    }

    @Test
    public void aCancelledTouchDecidesNothing() {
        ChipSwipe s = swipe();
        s.down(300, 100, 0, false);
        assertEquals(ChipSwipe.Touch.SWIPE, s.move(300 - 20 * DP, 100, 30));
        s.cancel();
        assertEquals(ChipSwipe.Touch.OTHER, s.touch());
        assertEquals(ChipSwipe.Result.NONE, s.up(300 - 40 * DP, 100, 60));
        assertNotEquals(0f, s.travel());
    }

    @Test
    public void theHintIsDueOnceForTwoOrMoreAccounts() {
        assertFalse(ChipSwipe.hintDue(0, false, 0));
        assertFalse(ChipSwipe.hintDue(1, false, 0));
        assertTrue(ChipSwipe.hintDue(2, false, 0));
        assertTrue(ChipSwipe.hintDue(5, false, ChipSwipe.HINT_SEEN_MS - 1));
        assertFalse(ChipSwipe.hintDue(2, false, ChipSwipe.HINT_SEEN_MS));
        assertFalse(ChipSwipe.hintDue(2, true, 0));
        assertTrue(ChipSwipe.HINT_SEEN_MS < ChipSwipe.HINT_SHOW_MS);
    }
}
