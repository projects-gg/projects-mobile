package gg.projects.mobile.social.emoji;

import static gg.projects.mobile.social.emoji.EmojiDataTest.TR;
import static gg.projects.mobile.social.emoji.EmojiDataTest.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** The quick reactions row (the desktop's features/social/chat/reactions.test.ts, its row part). */
public class QuickReactionsTest {
    private static final String UP = t("{1F44D}");
    private static final String RED_HEART = t("{2764}{FE0F}");
    private static final String HEART = t("{2764}");
    private static final String JOY = t("{1F602}");
    private static final String SOB = t("{1F62D}");
    private static final String FIRE = t("{1F525}");
    private static final String OPEN_MOUTH = t("{1F62E}");
    private static final String GAME = t("{1F3AE}");
    private static final String SKULL = t("{1F480}");
    private static final String MIDDLE_FINGER = t("{1F595}");

    @Test
    public void startsWithThePlayersRecentEmojiThenTheDefaultsSixOfThem() {
        assertEquals(Arrays.asList(UP, RED_HEART, JOY, SOB, FIRE, OPEN_MOUTH), QuickReactions.DEFAULTS);
        assertEquals(QuickReactions.DEFAULTS, QuickReactions.of(Collections.<String>emptyList()));
        assertEquals(Arrays.asList(GAME, UP, SKULL, RED_HEART, JOY, SOB),
                QuickReactions.of(Arrays.asList(GAME, UP, SKULL)));
    }

    @Test
    public void neverOffersTheMiddleFingerAndCountsTheHeartOnce() {
        assertEquals(Arrays.asList(HEART, FIRE, UP, JOY, SOB, OPEN_MOUTH),
                QuickReactions.of(Arrays.asList(MIDDLE_FINGER, HEART, FIRE)));
        assertTrue(QuickReactions.isRefused(MIDDLE_FINGER));
        assertTrue(QuickReactions.isRefused(MIDDLE_FINGER + t("{1F3FD}")));
        assertFalse(QuickReactions.isRefused(UP));
        assertFalse(QuickReactions.isRefused(""));
    }

    @Test
    public void readsAHeartWithOrWithoutItsSelectorAsOneAndASkinToneAsAnother() {
        assertTrue(QuickReactions.sameEmoji(HEART, RED_HEART));
        assertFalse(QuickReactions.sameEmoji(UP, UP + t("{1F3FD}")));
    }

    @Test
    public void givesTheRecentsInTheFormTheServiceTakesWhenTheDataIsThere() {
        String bareRainbow = t("{1F3F3}{200D}{1F308}");
        String rainbow = t("{1F3F3}{FE0F}{200D}{1F308}");
        assertEquals(Arrays.asList(rainbow, RED_HEART, UP, JOY, SOB, FIRE),
                QuickReactions.of(Arrays.asList(bareRainbow, HEART), TR));
        for (String emoji : QuickReactions.of(Arrays.asList(bareRainbow, HEART, "x"), TR)) {
            if (!emoji.equals("x")) assertTrue(emoji, TR.isKnown(emoji));
        }
        // Without the data, the desktop's row.
        assertEquals(Arrays.asList(bareRainbow, HEART, UP, JOY, SOB, FIRE),
                QuickReactions.of(Arrays.asList(bareRainbow, HEART), null));
        assertEquals(3, QuickReactions.of(Collections.<String>emptyList(), null, 3).size());
    }
}
