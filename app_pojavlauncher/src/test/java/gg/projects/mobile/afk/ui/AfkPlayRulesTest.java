package gg.projects.mobile.afk.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AfkPlayRulesTest {
    @Test
    public void theGamesHeapNeedsRoomBesideAfk() {
        assertTrue(AfkPlayRules.memoryFits(2048 + AfkPlayRules.MARGIN_MB, 2048));
        assertFalse(AfkPlayRules.memoryFits(2048 + AfkPlayRules.MARGIN_MB - 1, 2048));
        // Unknown figures never block a game.
        assertTrue(AfkPlayRules.memoryFits(0, 2048));
        assertTrue(AfkPlayRules.memoryFits(1500, 0));
    }

    @Test
    public void theSameNameAsksFirstAnotherNamePlaysWithoutAQuestion() {
        assertEquals(AfkPlayRules.Step.SAME_NAME, AfkPlayRules.first(true, 0, true));
        assertEquals(AfkPlayRules.Step.SAME_NAME, AfkPlayRules.first(true, 2, false));
        assertEquals(AfkPlayRules.Step.PLAY, AfkPlayRules.first(false, 2, true));
        assertEquals(AfkPlayRules.Step.MEMORY, AfkPlayRules.first(false, 1, false));
        assertEquals(AfkPlayRules.Step.PLAY, AfkPlayRules.first(false, 0, false));
    }

    @Test
    public void memoryAsksOnlyWhileSomeAccountStaysAfk() {
        // "AFK'yı durdur ve oyna" with no other account: nothing stays, no memory question.
        assertEquals(AfkPlayRules.Step.PLAY, AfkPlayRules.afterSameName(false, 0, false));
        assertEquals(AfkPlayRules.Step.MEMORY, AfkPlayRules.afterSameName(false, 1, false));
        // "AFK açık kalsın": it stays.
        assertEquals(AfkPlayRules.Step.MEMORY, AfkPlayRules.afterSameName(true, 0, false));
        assertEquals(AfkPlayRules.Step.PLAY, AfkPlayRules.afterSameName(true, 0, true));
    }
}
