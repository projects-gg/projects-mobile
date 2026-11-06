package gg.projects.mobile.afk.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which lines the player hears about: the name in someone's line, private messages. */
public class AfkChatRulesTest {
    @Test
    public void theNameAsAWordOutsideTheSpeakersPlace() {
        assertTrue(AfkChatRules.mentions("<Ember_77> sleepyfox_42 neredesin?", "SleepyFox_42"));
        assertTrue(AfkChatRules.mentions("[VIP] Ember_77 » SleepyFox_42 bot musun", "SleepyFox_42"));
        assertTrue(AfkChatRules.mentions("Kontrol: SleepyFox_42, hareket et!", "SleepyFox_42"));
        assertFalse(AfkChatRules.mentions("SleepyFox_42 joined the game", "SleepyFox_42"));
        assertFalse(AfkChatRules.mentions("<SleepyFox_42> merhaba", "SleepyFox_42"));
        assertFalse(AfkChatRules.mentions("[Üye] SleepyFox_42 » merhaba", "SleepyFox_42"));
        assertFalse(AfkChatRules.mentions("SleepyFox_420 burada", "SleepyFox_42"));
        assertFalse(AfkChatRules.mentions("xSleepyFox_42 burada mı", "SleepyFox_42"));
        assertFalse(AfkChatRules.mentions("kimse yok", "SleepyFox_42"));
    }

    @Test
    public void privateMessageForms() {
        assertEquals("Ember_77", AfkChatRules.privateFrom("[Ember_77 -> ben] AFK misin?"));
        assertEquals("Ember_77", AfkChatRules.privateFrom("[VIP Ember_77 -> me] hi"));
        assertEquals("Night_Owl", AfkChatRules.privateFrom("Night_Owl -> You: selam"));
        assertEquals("Night_Owl", AfkChatRules.privateFrom("Night_Owl whispers to you: hey"));
        assertEquals("Night_Owl", AfkChatRules.privateFrom("Night_Owl sana fısıldıyor: selam"));
        assertNull(AfkChatRules.privateFrom("[ben -> Ember_77] selam"));
        assertNull(AfkChatRules.privateFrom("<Ember_77> ben geldim"));
    }
}
