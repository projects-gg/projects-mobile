package gg.projects.mobile.join;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** M18: when the app asks for the notification permission (the 0.1.8 tester's report). */
public class NotifyAskTest {
    @Test
    public void theCardAsksOnceOnAndroid13AndLater() {
        // An update with everything downloaded: the card asks on the home screen, before any start.
        assertTrue(NotifyAsk.offerDue(33, false, false, false));
        assertTrue(NotifyAsk.offerDue(36, false, false, false));
        assertFalse("answered or set aside", NotifyAsk.offerDue(36, false, true, false));
        assertFalse("a start asked already", NotifyAsk.offerDue(36, false, false, true));
        assertFalse("allowed, also in Android's settings", NotifyAsk.offerDue(36, true, false, false));
        assertFalse("before Android 13 nothing needs asking", NotifyAsk.offerDue(32, false, false, false));
    }

    @Test
    public void theFirstStartAsksOnceAsAFallback() {
        // The card set aside ("Şimdi değil"): the first Projects start still asks, once.
        assertTrue(NotifyAsk.startAsks(33, false, false));
        assertFalse(NotifyAsk.startAsks(33, false, true));
        assertFalse(NotifyAsk.startAsks(33, true, false));
        assertFalse(NotifyAsk.startAsks(30, false, false));
    }
}
