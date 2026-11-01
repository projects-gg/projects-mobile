package gg.projects.mobile.afk.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import gg.projects.mobile.afk.api.AfkDraft;
import gg.projects.mobile.afk.api.AfkLoginMode;

public final class AfkFormRulesTest {
    @Test
    public void anEmptyAddressIsTheDesktopsDefault() {
        assertEquals("play.projects.gg", AfkFormRules.address(""));
        assertEquals("play.projects.gg", AfkFormRules.address("   "));
        assertEquals("play.projects.gg", AfkFormRules.address(null));
        assertEquals("mc.sunucu.net:25570", AfkFormRules.address(" mc.sunucu.net:25570 "));
    }

    @Test
    public void commandsAreKeptTrimmedWithoutBlankLines() {
        assertEquals(Arrays.asList("/login abc", "selam"), AfkFormRules.commands(Arrays.asList(" /login abc ", "", "  ", "selam", null)));
        assertTrue(AfkFormRules.commands(null).isEmpty());
    }

    @Test
    public void whatCountsAsAChange() {
        AfkDraft start = new AfkDraft();
        start.name = "Golge_Avci";
        start.address = "";
        start.loginCommands = Arrays.asList("/login abc");
        AfkDraft now = AfkFormRules.copy(start);
        assertFalse(AfkFormRules.changed(start, now));
        now.address = "PLAY.projects.gg";
        now.loginCommands = Arrays.asList("/login abc", " ");
        assertFalse("the default typed out and a blank line are no change", AfkFormRules.changed(start, now));
        now.loginMode = AfkLoginMode.FIRST_JOIN;
        assertTrue(AfkFormRules.changed(start, now));
        now = AfkFormRules.copy(start);
        now.mobileData = true;
        assertTrue(AfkFormRules.changed(start, now));
        now = AfkFormRules.copy(start);
        now.sneak = false;
        assertTrue(AfkFormRules.changed(start, now));
    }

    @Test
    public void aSecretLineShowsItsCommandAndBulletsForTheRest() {
        assertTrue(AfkFormRules.secret("/login gizli123"));
        assertTrue(AfkFormRules.secret("/L gizli"));
        assertFalse(AfkFormRules.secret("/list"));
        assertFalse(AfkFormRules.secret("herkese merhaba"));
        assertEquals("/login ••••••••", AfkFormRules.masked("/login gizli123"));
        assertEquals("/register ••• •••", AfkFormRules.masked("/register abc abc"));
        assertEquals("  /login •", AfkFormRules.masked("  /login x"));
        assertEquals("/kit", AfkFormRules.masked("/kit"));
        assertEquals("/login", AfkFormRules.masked("/login"));
    }

    @Test
    public void theMaskIsReadFromTheLineAsItIsNowCharacterByCharacter() {
        for (String line : new String[]{"/login gizli123", "/L x", "herkese merhaba", "  /register a b", "/login", "/login ", ""}) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < line.length(); i++) out.append(AfkFormRules.shown(line, i));
            assertEquals(AfkFormRules.masked(line), out.toString());
        }
        // The field's text grows under the mask: each character comes from the text as it is now.
        StringBuilder typed = new StringBuilder("/login");
        assertEquals('n', AfkFormRules.shown(typed, 5));
        typed.append(" ab");
        assertEquals('•', AfkFormRules.shown(typed, 7));
        assertEquals(' ', AfkFormRules.shown(typed, 6));
    }

    @Test
    public void thePhonesOwnNamesThatMayFillTheNameField() {
        assertEquals(Arrays.asList("Ali_K", "x7Ender"),
                AfkFormRules.phoneNames(Arrays.asList("Ali_K", "golge_avci", "x7Ender", "Demo.Player", "ab", "ALI_K"),
                        Collections.singletonList("Golge_Avci")));
        assertTrue(AfkFormRules.phoneNames(null, null).isEmpty());
    }
}
