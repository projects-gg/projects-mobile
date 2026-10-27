package gg.projects.mobile.join;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * MOB2: the editor calls Amethyst's default profile what the profile list calls it ("Varsayılan"),
 * and keeps the stored "(Default)" while the player leaves the name alone.
 */
public class ProfileNamesTest {
    private static final String DEFAULT = "Varsayılan";
    private static final String UNNAMED = "Profil";

    @Test
    public void theDefaultProfileReadsInThePlayersLanguage() {
        assertEquals(DEFAULT, ProfileNames.editable("(Default)", DEFAULT));
        assertEquals(DEFAULT, ProfileNames.editable("Default", DEFAULT));
        assertEquals(DEFAULT, ProfileNames.editable(" (Default) ", DEFAULT));
        assertEquals("Fabric", ProfileNames.editable("Fabric", DEFAULT));
        assertEquals("", ProfileNames.editable(null, DEFAULT));
        assertEquals("Default", ProfileNames.editable("Default", "Default"));
    }

    @Test
    public void theStoredDefaultNameStaysWhileLeftAlone() {
        assertEquals("(Default)", ProfileNames.saved("(Default)", DEFAULT, DEFAULT));
        assertEquals("(Default)", ProfileNames.saved("(Default)", " Varsayılan ", DEFAULT));
        assertEquals("Default", ProfileNames.saved("Default", DEFAULT, DEFAULT));
        assertEquals("a name the player typed is the new name", "Benim dünyam",
                ProfileNames.saved("(Default)", "Benim dünyam", DEFAULT));
        // A profile of the player's own that they name like the default keeps what they typed.
        assertEquals(DEFAULT, ProfileNames.saved("Eski", DEFAULT, DEFAULT));
        assertEquals("Yeni", ProfileNames.saved("", "Yeni", DEFAULT));
        assertEquals("Yeni", ProfileNames.saved(null, "Yeni", DEFAULT));
    }

    @Test
    public void aSentenceAlwaysHasAName() {
        assertEquals(DEFAULT, ProfileNames.shown("(Default)", DEFAULT, UNNAMED));
        assertEquals("Projects", ProfileNames.shown(" Projects ", DEFAULT, UNNAMED));
        assertEquals(UNNAMED, ProfileNames.shown("", DEFAULT, UNNAMED));
        assertEquals(UNNAMED, ProfileNames.shown(null, DEFAULT, UNNAMED));
        assertEquals(UNNAMED, ProfileNames.shown("New", DEFAULT, UNNAMED));
    }

    @Test
    public void namesWithoutOneOfTheirOwn() {
        assertTrue(ProfileNames.isUnnamed(null));
        assertTrue(ProfileNames.isUnnamed("  "));
        assertTrue(ProfileNames.isUnnamed("new"));
        assertFalse(ProfileNames.isUnnamed("Newton"));
        assertTrue(ProfileNames.isDefault("(Default)"));
        assertFalse(ProfileNames.isDefault("Varsayılan"));
    }
}
