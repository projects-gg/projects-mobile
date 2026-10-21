package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** M21 (TEAM5): crew invite links read as the service reads them, and never as crew codes. */
public class InviteLinkTest {
    @Test
    public void aLinkIsTwelveSymbolsInAnyShape() {
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.normalizeInviteLink("ABCD-EFGH-JKMN"));
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.normalizeInviteLink("abcd efgh jkmn"));
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.normalizeInviteLink("ABCDEFGHJKMN"));
        assertEquals("O is 0, I and L are 1", "0BCD-1FGH-1KMN", SocialCodes.normalizeInviteLink("OBCD-IFGH-LKMN"));
        assertNull("U is not in the alphabet", SocialCodes.normalizeInviteLink("ABCD-EFGH-JKMU"));
        assertNull("eight symbols are a code", SocialCodes.normalizeInviteLink("ABCD-EFGH"));
        assertNull(SocialCodes.normalizeInviteLink(null));
        assertNull("longer than the service reads", SocialCodes.normalizeInviteLink("ABCD-EFGH-JKMN                              x"));
    }

    @Test
    public void theLinkIsFoundInWhatWasPasted() {
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.inviteLinkIn("https://social.projects.gg/e/ABCD-EFGH-JKMN"));
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.inviteLinkIn("Ekibe gel: social.projects.gg/e/abcd-efgh-jkmn !"));
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.inviteLinkIn("projects://crew/ABCD-EFGH-JKMN"));
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.inviteLinkIn("  ABCD EFGH JKMN  "));
        assertNull("a crew code is no link", SocialCodes.inviteLinkIn("EKP-ABCD-EFGH"));
        assertNull("a friend code is no link", SocialCodes.inviteLinkIn("PRJ-ABCD-EFGH"));
        assertNull(SocialCodes.inviteLinkIn("https://social.projects.gg/f/PRJ-ABCD-EFGH"));
    }

    @Test
    public void aLinkIsNeverTakenForACrewCode() {
        // Its last eight symbols look like a code.
        assertNull(SocialCodes.crewCodeIn("https://social.projects.gg/e/ABCD-EFGH-JKMN"));
        assertNull(SocialCodes.crewCodeIn("ABCD-EFGH-JKMN"));
        assertEquals("ABCD-EFGH", SocialCodes.crewCodeIn("EKP-ABCD-EFGH"));
    }

    @Test
    public void theDeepLinkOfTheLinksPage() {
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.inviteLinkFromDeepLink("projects://crew/ABCD-EFGH-JKMN"));
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.inviteLinkFromDeepLink("PROJECTS://crew/abcd-efgh-jkmn/"));
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.inviteLinkFromDeepLink("projects://ekip/ABCD-EFGH-JKMN?x=1"));
        assertEquals("ABCD-EFGH-JKMN", SocialCodes.inviteLinkFromDeepLink("projects://crew/ABCD%20EFGH%20JKMN"));
        assertNull(SocialCodes.inviteLinkFromDeepLink("projects://crew/"));
        assertNull(SocialCodes.inviteLinkFromDeepLink("projects://friend/PRJ-ABCD-EFGH"));
        assertNull(SocialCodes.inviteLinkFromDeepLink("projects://crew/ABCD-EFGH"));
    }

    @Test
    public void theAddressIsTheServices() {
        assertEquals("https://social.projects.gg/e/ABCD-EFGH-JKMN", SocialCodes.inviteLinkUrl("https://social.projects.gg/", "ABCD-EFGH-JKMN"));
        assertEquals("http://10.0.2.2:8837/e/ABCD-EFGH-JKMN", SocialCodes.inviteLinkUrl("http://10.0.2.2:8837", "ABCD-EFGH-JKMN"));
        assertEquals("social.projects.gg/e/ABCD-EFGH-JKMN", SocialCodes.inviteLinkShort("https://social.projects.gg/", "ABCD-EFGH-JKMN"));
    }
}
