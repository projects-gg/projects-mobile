package gg.projects.mobile.social.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * BADGES: where a badge goes inside a line that starts from someone (the home's friends row): after the word holding
 * their name, so a suffix stays with it, and never inside another word.
 */
public class ProfileMarksTest {
    @Test
    public void theBadgeFollowsTheWordHoldingTheName() {
        assertEquals(10, ProfileMarks.nameEnd("Pixel_Ezgi seni oyuna çağırıyor", "Pixel_Ezgi"));
        assertEquals(8, ProfileMarks.nameEnd("KorAtesi, Gece Kartallari ekibine katılmak istiyor", "KorAtesi"));
        // Turkish and English suffixes stay with the name.
        assertEquals(12, ProfileMarks.nameEnd("KorAtesi'nin daveti", "KorAtesi"));
        assertEquals(10, ProfileMarks.nameEnd("KorAtesi's invite", "KorAtesi"));
        assertEquals(10, ProfileMarks.nameEnd("KorAtesi’s invite", "KorAtesi"));
        // A name later in the line, as a word of its own.
        assertEquals(23, ProfileMarks.nameEnd("Ekibe katıldı: Can_Blok", "Can_Blok"));
    }

    @Test
    public void aNameInsideAnotherWordOrMissingTakesNoBadge() {
        assertEquals(-1, ProfileMarks.nameEnd("SuperCan seni çağırıyor", "Can"));
        assertEquals(-1, ProfileMarks.nameEnd("Biri seni oyuna çağırıyor", "KorAtesi"));
        assertEquals(-1, ProfileMarks.nameEnd("KorAtesi seni çağırıyor", ""));
        assertEquals(-1, ProfileMarks.nameEnd("KorAtesi seni çağırıyor", null));
        // The first word of its own wins.
        assertEquals(3, ProfileMarks.nameEnd("Can ve Can_Blok", "Can"));
    }
}
