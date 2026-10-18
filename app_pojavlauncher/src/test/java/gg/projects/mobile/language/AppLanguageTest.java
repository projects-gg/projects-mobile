package gg.projects.mobile.language;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The owner's language rule of 18 October 2026: choice, then Turkey, then the phone, then English. */
public class AppLanguageTest {
    private static final List<String> ENGLISH_PHONE = Collections.singletonList("en-US");
    private static final List<String> TURKISH_PHONE = Collections.singletonList("tr-TR");

    private static String auto(String timeZone, List<String> phone) {
        return AppLanguage.resolve(AppLanguage.AUTO, AppLanguage.inTurkey(timeZone, null, null), phone);
    }

    @Test
    public void theOwnersExamples() {
        assertEquals("an English phone in Istanbul", "tr", auto("Europe/Istanbul", ENGLISH_PHONE));
        assertEquals("an English phone in Berlin", "en", auto("Europe/Berlin", ENGLISH_PHONE));
        assertEquals("a Turkish phone in Berlin", "tr", auto("Europe/Berlin", TURKISH_PHONE));
        assertEquals("a Turkish phone in Istanbul", "tr", auto("Europe/Istanbul", TURKISH_PHONE));
    }

    @Test
    public void theChoiceWinsOverEverything() {
        assertEquals("en", AppLanguage.resolve("en", true, TURKISH_PHONE));
        assertEquals("tr", AppLanguage.resolve("tr", false, ENGLISH_PHONE));
        assertEquals("unknown values mean automatic", "tr", AppLanguage.resolve("de", true, ENGLISH_PHONE));
        assertEquals("auto", AppLanguage.normalizeChoice(null));
        assertEquals("auto", AppLanguage.normalizeChoice("system"));
    }

    @Test
    public void turkeyWithoutAskingAnyone() {
        assertTrue(AppLanguage.inTurkey("Europe/Istanbul", null, null));
        assertTrue("the old alias", AppLanguage.inTurkey("Asia/Istanbul", null, null));
        assertTrue(AppLanguage.inTurkey("Turkey", null, null));
        assertTrue("Northern Cyprus speaks Turkish", AppLanguage.inTurkey("Asia/Famagusta", null, null));
        assertFalse("the Republic of Cyprus", AppLanguage.inTurkey("Asia/Nicosia", null, null));
        assertFalse(AppLanguage.inTurkey("Europe/Berlin", "de", "de"));
        // A Turkish network or SIM counts in another time zone (a Turkish SIM abroad, a phone set to UTC).
        assertTrue(AppLanguage.inTurkey("UTC", "tr", null));
        assertTrue(AppLanguage.inTurkey("Europe/Berlin", "de", "TR"));
        assertFalse(AppLanguage.inTurkey(null, "", ""));
        assertEquals("an English phone in Berlin with a Turkish SIM", "tr",
                AppLanguage.resolve(AppLanguage.AUTO, AppLanguage.inTurkey("Europe/Berlin", "de", "tr"), ENGLISH_PHONE));
    }

    @Test
    public void thePhonesLanguagesInOrder() {
        // The first of the app's two languages in the phone's list, as Android itself would pick.
        assertTrue(AppLanguage.phonePrefersTurkish(Arrays.asList("de-DE", "tr-TR", "en-US")));
        assertFalse(AppLanguage.phonePrefersTurkish(Arrays.asList("en-GB", "tr-TR")));
        assertFalse("a German phone gets English", AppLanguage.phonePrefersTurkish(Collections.singletonList("de-DE")));
        assertTrue(AppLanguage.phonePrefersTurkish(Collections.singletonList("tr")));
        assertTrue(AppLanguage.phonePrefersTurkish(Collections.singletonList("tr_TR")));
        assertFalse(AppLanguage.phonePrefersTurkish(null));
        assertEquals("en", auto("Europe/Berlin", Collections.singletonList("de-DE")));
    }

    @Test
    public void theAppLocaleAndroidKeeps() {
        // Automatic and the phone already speaks it: the system default, so both pages agree.
        assertEquals("", AppLanguage.localeToApply("auto", "en", ENGLISH_PHONE));
        assertEquals("", AppLanguage.localeToApply("auto", "tr", TURKISH_PHONE));
        // Automatic in Turkey on an English phone: Turkish is set, the system page says Türkçe.
        assertEquals("tr", AppLanguage.localeToApply("auto", "tr", ENGLISH_PHONE));
        // A choice is always set, so it holds when the phone's language changes.
        assertEquals("en", AppLanguage.localeToApply("en", "en", ENGLISH_PHONE));
        assertEquals("tr", AppLanguage.localeToApply("tr", "tr", TURKISH_PHONE));

        // A change on the system's "App languages" page, read back as a choice.
        assertEquals("auto", AppLanguage.choiceFromSystem(""));
        assertEquals("auto", AppLanguage.choiceFromSystem(null));
        assertEquals("tr", AppLanguage.choiceFromSystem("tr"));
        assertEquals("tr", AppLanguage.choiceFromSystem("tr-TR,en-US"));
        assertEquals("en", AppLanguage.choiceFromSystem("en-GB"));
        assertEquals("the app has only two languages", "en", AppLanguage.choiceFromSystem("de-DE"));
    }
}
