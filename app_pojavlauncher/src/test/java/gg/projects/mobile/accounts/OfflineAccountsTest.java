package gg.projects.mobile.accounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Offline names with the desktop's rules (name-rules.test.ts) and the identity the game gets. */
public class OfflineAccountsTest {
    /** Account files on the device: offline ones and a Microsoft one (one file per name on Android). */
    private static final List<String> ACCOUNTS = Arrays.asList("EjderAvcisi", "KorAtesi_", "Cagri");

    private static OfflineAccounts.Check typing(String raw) {
        return OfflineAccounts.check(raw, ACCOUNTS, false);
    }

    private static OfflineAccounts.Check submitted(String raw) {
        return OfflineAccounts.check(raw, ACCOUNTS, true);
    }

    @Test
    public void acceptsMinecraftNames() {
        for (String name : new String[]{"abc", "Notch", "A_1234567890_bcd", "  Steve  ", "___", "\tAli_34\n"}) {
            OfflineAccounts.Check check = submitted(name);
            assertNull(name, check.problem);
            assertNull(name, check.existing);
            assertEquals(name.trim(), check.name);
        }
    }

    @Test
    public void waitsForTheSubmitBeforeCallingANameEmptyOrTooShort() {
        assertNull(typing("").problem);
        assertNull(typing("ab").problem);
        assertEquals(OfflineAccounts.Problem.EMPTY, submitted("").problem);
        assertEquals(OfflineAccounts.Problem.EMPTY, submitted("   ").problem);
        assertEquals(OfflineAccounts.Problem.EMPTY, submitted(null).problem);
        assertEquals(OfflineAccounts.Problem.TOO_SHORT, submitted("ab").problem);
    }

    @Test
    public void flagsLongNamesAtOnce() {
        assertEquals(OfflineAccounts.Problem.TOO_LONG, typing("abcdefghijklmnopq").problem);
        assertNull(typing("abcdefghijklmnop").problem);
    }

    @Test
    public void explainsTurkishLettersAndSpacesWithAFix() {
        OfflineAccounts.Check turkish = typing("Gölge Avcı");
        assertEquals(OfflineAccounts.Problem.TURKISH, turkish.problem);
        assertEquals("Golge_Avci", turkish.suggestion);

        OfflineAccounts.Check spaces = typing("Ali Veli");
        assertEquals(OfflineAccounts.Problem.SPACES, spaces.problem);
        assertEquals("Ali_Veli", spaces.suggestion);
        // A no-break space is a space too, as for the desktop's \s.
        assertEquals(OfflineAccounts.Problem.SPACES, typing("Ali" + (char) 0xA0 + "Veli").problem);

        OfflineAccounts.Check symbols = typing("nick?!");
        assertEquals(OfflineAccounts.Problem.CHARACTERS, symbols.problem);
        assertEquals("nick", symbols.suggestion);
        assertEquals(OfflineAccounts.Problem.CHARACTERS, typing("a-b").problem);
        assertEquals(OfflineAccounts.Problem.CHARACTERS, typing("Demo.Player").problem);
    }

    @Test
    public void doesNotSuggestANameThatIsTakenOrUnusable() {
        // "Çağrı" spells out to "Cagri", which another account has.
        OfflineAccounts.Check taken = typing("Çağrı");
        assertEquals(OfflineAccounts.Problem.TURKISH, taken.problem);
        assertNull(taken.suggestion);
        assertNull(typing("ş!").suggestion);
        assertEquals("Istanbul_Sehri_3", OfflineAccounts.suggest("İstanbul Şehri 34 Çok Uzun"));
        assertEquals("a__b", OfflineAccounts.suggest("a ! b"));
        assertNull(OfflineAccounts.suggest(null));
    }

    @Test
    public void aNameInTheListSwitchesToThatAccountIgnoringCase() {
        OfflineAccounts.Check check = submitted("ejderavcisi");
        assertNull(check.problem);
        assertEquals("EjderAvcisi", check.existing);
        // Every account counts, a Microsoft one too: Android keeps one file per name, never overwritten.
        assertEquals("KorAtesi_", submitted("KORATESI_").existing);
        assertNull(submitted("EjderAvcisi2").existing);
        // While typing as well, so the screen can say so before the tap.
        assertEquals("Cagri", typing("cagri").existing);
        // The exact name wins over another case of it.
        assertEquals("Steve", OfflineAccounts.findExisting("Steve", Arrays.asList("steve", "Steve")));
        assertEquals("steve", OfflineAccounts.findExisting("STEVE", Arrays.asList("steve", "Steve")));
        assertNull(OfflineAccounts.findExisting("Steve", Collections.<String>emptyList()));
        assertNull(OfflineAccounts.findExisting("Steve", null));
        // Only ASCII letters fold: the Turkish dotless i is another letter.
        assertNull(OfflineAccounts.findExisting("cagrı", ACCOUNTS));
    }

    @Test
    public void validNames() {
        assertTrue(OfflineAccounts.isValidName("Steve_123"));
        assertTrue(OfflineAccounts.isValidName("abc"));
        assertTrue(OfflineAccounts.isValidName("abcdefghijklmnop"));
        assertFalse(OfflineAccounts.isValidName("ab"));
        assertFalse(OfflineAccounts.isValidName("this_name_is_too_long"));
        assertFalse(OfflineAccounts.isValidName("bad name"));
        assertFalse(OfflineAccounts.isValidName("Çağrı"));
        assertFalse(OfflineAccounts.isValidName("Demo.Player"));
        assertFalse(OfflineAccounts.isValidName(""));
        assertFalse(OfflineAccounts.isValidName(null));
    }

    @Test
    public void offlineUuidIsTheOneServersCompute() {
        // The desktop's test vector (pl-mc identity.rs), a version 3 UUID.
        UUID notch = OfflineAccounts.uuid("Notch");
        assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", notch.toString());
        assertEquals(3, notch.version());
        // Case matters to servers: another name, another player.
        assertFalse(notch.equals(OfflineAccounts.uuid("notch")));
    }

    @Test
    public void launchUuidOfOfflineAndMicrosoftAccounts() {
        assertEquals("b50ad385829d3141a2167e7d7539ba7f",
                OfflineAccounts.launchUuid(true, "Notch", "00000000-0000-0000-0000-000000000000"));
        // Microsoft accounts keep their own profile id, a demo one (all zero) as well.
        assertEquals("069a79f444e94726a5befca90e38aaf5",
                OfflineAccounts.launchUuid(false, "Notch", "069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertEquals("00000000000000000000000000000000",
                OfflineAccounts.launchUuid(false, "Demo.Player", "00000000-0000-0000-0000-000000000000"));
    }
}
