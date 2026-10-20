package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** M19: friend and crew codes, links and the sign-in link, as the desktop's codes.rs reads them. */
public class SocialCodesTest {
    @Test
    public void friendCodesNormalizeLikeTheService() {
        assertEquals("PRJ-ABCD-EFGH", SocialCodes.normalizeFriendCode("PRJ-ABCD-EFGH"));
        assertEquals("PRJ-ABCD-EFGH", SocialCodes.normalizeFriendCode("prj abcd efgh"));
        assertEquals("PRJ-ABCD-EFGH", SocialCodes.normalizeFriendCode("abcdefgh"));
        assertEquals("PRJ-0011-1234", SocialCodes.normalizeFriendCode("O0IL-1234"));
        assertNull("U is not Crockford", SocialCodes.normalizeFriendCode("ABCD-EFGU"));
        assertNull(SocialCodes.normalizeFriendCode("ABC"));
        assertNull(SocialCodes.normalizeFriendCode("ABCD-EFGŞ"));
        assertNull(SocialCodes.normalizeFriendCode(new String(new char[40]).replace('\0', 'x')));
        assertNull(SocialCodes.normalizeFriendCode(null));
    }

    @Test
    public void crewCodesTakeBothPrefixesAndGoOutWithoutOne() {
        assertEquals("ABCD-EFGH", SocialCodes.normalizeCrewCode("tkm abcd efgh"));
        assertEquals("ABCD-EFGH", SocialCodes.normalizeCrewCode("EKP-ABCD-EFGH"));
        assertEquals("ABCD-EFGH", SocialCodes.normalizeCrewCode("ekp_abcd_efgh"));
        assertEquals("ABCD-EFGH", SocialCodes.normalizeCrewCode("ABCDEFGH"));
        assertNull("a friend code is not a crew code", SocialCodes.normalizeCrewCode("PRJ-ABCD-EFGH"));
        assertEquals("EKP-ABCD-EFGH", SocialCodes.crewCode("TKM-ABCD-EFGH"));
        assertEquals("not a crew code", SocialCodes.crewCode("not a crew code"));
    }

    @Test
    public void pastedTextGivesTheLastFullCode() {
        assertEquals("PRJ-ABCD-EFGH", SocialCodes.friendCodeIn("kodum PRJ-ABCD-EFGH"));
        assertEquals("PRJ-1234-5678", SocialCodes.friendCodeIn("PRJ-ABCD-EFGH ya da PRJ-1234-5678"));
        assertEquals("PRJ-ABCD-EFGH", SocialCodes.friendCodeIn("  prj-abcd-efgh  "));
        assertNull(SocialCodes.friendCodeIn("merhaba"));
        assertEquals("ABCD-EFGH", SocialCodes.crewCodeIn("Ekibime katıl: EKP-ABCD-EFGH"));
        assertEquals("ABCD-EFGH", SocialCodes.crewCodeIn("TKM-ABCD-EFGH"));
        assertNull("a friend code is never taken for a crew code", SocialCodes.crewCodeIn("PRJ-ABCD-EFGH"));
        assertNull(SocialCodes.crewCodeIn("kodum PRJ-ABCD-EFGH"));
    }

    @Test
    public void deepLinks() {
        assertEquals("PRJ-ABCD-EFGH", SocialCodes.friendCodeFromDeepLink("projects://friend/PRJ-ABCD-EFGH"));
        assertEquals("PRJ-ABCD-EFGH", SocialCodes.friendCodeFromDeepLink("projects://friend/abcd-efgh/"));
        assertNull(SocialCodes.friendCodeFromDeepLink("projects://friend/nope"));
        assertNull(SocialCodes.friendCodeFromDeepLink("https://example.com/f/PRJ-ABCD-EFGH"));
        String code = "Qm9fT2sXyZ-0123456789_abcdefghijklmnopqrstu";
        assertEquals(43, code.length());
        assertEquals(code, SocialCodes.authCodeFromDeepLink("projects://auth/done?c=" + code));
        assertEquals(code, SocialCodes.authCodeFromDeepLink("projects://auth/done/?x=1&c=" + code));
        assertNull("too short", SocialCodes.authCodeFromDeepLink("projects://auth/done?c=abc"));
        assertNull("another link", SocialCodes.authCodeFromDeepLink("projects://friend/PRJ-ABCD-EFGH"));
        assertNull(SocialCodes.authCodeFromDeepLink(null));
    }

    @Test
    public void shareLink() {
        assertEquals("https://social.projects.gg/f/PRJ-ABCD-EFGH",
                SocialCodes.friendShareLink("https://social.projects.gg/", "abcdefgh"));
        assertEquals("http://10.0.2.2:8897/f/PRJ-ABCD-EFGH", SocialCodes.friendShareLink("http://10.0.2.2:8897", "PRJ-ABCD-EFGH"));
        assertNull(SocialCodes.friendShareLink("https://social.projects.gg/", "bad"));
    }
}
