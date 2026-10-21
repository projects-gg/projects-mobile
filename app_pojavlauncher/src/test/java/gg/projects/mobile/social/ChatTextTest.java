package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * M20: the phone's chat text checks against the service's own cases (services/social
 * test/chattext.test.ts): what it cleans, and the links and phone numbers it turns away.
 */
public class ChatTextTest {
    private static final String ZWJ = String.valueOf((char) 0x200D);
    private static final String ZWSP = String.valueOf((char) 0x200B);
    private static final String RLO = String.valueOf((char) 0x202E);
    private static final String LS = String.valueOf((char) 0x2028);
    private static final String CGJ = String.valueOf((char) 0x034F);
    private static final String VS16 = String.valueOf((char) 0xFE0F);
    private static final String CYRILLIC_O = String.valueOf((char) 0x043E);
    private static final String CYRILLIC_ES = String.valueOf((char) 0x0441);

    private static String arabic(String digits) {
        StringBuilder out = new StringBuilder();
        for (char d : digits.toCharArray()) out.append((char) (0x0660 + (d - '0')));
        return out.toString();
    }

    @Test
    public void itCleansLikeTheService() {
        assertEquals("Selam dünya", ChatText.clean("  Selam" + ZWSP + "   dünya" + RLO + "  "));
        assertEquals("a\nb\nc\nd\ne f g", ChatText.clean("a\r\n\r\nb" + LS + "c\n\n\nd\ne\nf\tg"));
        String family = "👨" + ZWJ + "👩" + ZWJ + "👧";
        assertEquals(family, ChatText.clean(family));
        assertEquals(ChatText.Problem.EMPTY, ChatText.check(ZWJ + ZWSP));
        assertEquals(ChatText.Problem.NONE, ChatText.check(repeat("x", 300)));
        assertEquals(ChatText.Problem.TOO_LONG, ChatText.check(repeat("x", 301)));
        // 300 emoji are 300 characters (code points), not 600.
        assertEquals(ChatText.Problem.NONE, ChatText.check(repeat("😀", 300)));
        assertEquals(ChatText.Problem.EMPTY, ChatText.check(null));
        // At most two marks on a letter.
        String zalgo = "a" + "́̂̃̄̅" + "b";
        String cleaned = ChatText.clean(zalgo);
        assertEquals(2 + 2, cleaned.length());
        // A non-breaking space is a space too.
        assertEquals("a b", ChatText.clean("a" + (char) 0x00A0 + (char) 0x3000 + "b"));
    }

    @Test
    public void itTurnsAwayLinksWrittenInTheUsualWays() {
        for (String text : new String[]{"discord.gg/abcd", "DISCORD.GG/abcd", "discord . gg/abcd", "discord gg/abcd",
                "discordgg abcd", "dsc.gg/abc", "https://example.org", "http:/bozuk", "www.ornek.com.tr",
                "siteme gel oyun(.)com", "oyun[dot]net", "oyun nokta com", "örnek.com", "bit.ly/x", "evil-site.xyz",
                "projects.gg.evil.com", "mcprojects.gg", "youtube" + ZWJ + ".com", "discord" + ZWJ + ".gg/abc",
                "site." + CYRILLIC_ES + CYRILLIC_O + "m", "185.12.34.56:25565"}) {
            assertTrue(text, ChatText.looksLikeLink(text));
            assertEquals(text, ChatText.Problem.LINK, ChatText.check(text));
        }
        for (String text : new String[]{"play.projects.gg'ye gel", "projects.gg", "Tamam.Gel", "Saat 5.30'da",
                "1.16.5 sürümü", "bir nokta koy", "Geldim. Bekle", "discord grubuna yaz", "Discord'dan yazarım"}) {
            assertFalse(text, ChatText.looksLikeLink(text));
        }
    }

    @Test
    public void itTurnsAwayPhoneNumbersNotCoordinatesOrScores() {
        for (String text : new String[]{"0532 123 45 67", "05321234567", "5321234567", "+90 532 123 4567",
                "+90-532-123-45-67", "(0532) 123 45 67", "0212 123 45 67", "+1 202 555 0123", "0049 30 1234567",
                "０５３２ １２３ ４５ ６７", "0532 - 123 - 45 - 67",
                "0532 123 45 67 8", "0532\n123 45 67", "0532,123,45,67", "0532_123_45_67", "0532" + ZWJ + "123 45 67",
                "0532" + CGJ + "123" + VS16 + " 45 67", "numara: " + arabic("05321234567")}) {
            assertTrue(text, ChatText.looksLikePhone(text));
            assertEquals(text, ChatText.Problem.PHONE, ChatText.check(text));
        }
        for (String text : new String[]{"1234 64 -567", "x: 120 y: 64 z: -3000", "skor 15-12", "2026 yılında",
                "100 elmas 200 demir", "1.16.5", "SIK SIK 1234 64 -567"}) {
            assertFalse(text, ChatText.looksLikePhone(text));
        }
        assertEquals(ChatText.Problem.NONE, ChatText.check("SIK SIK oluyor"));
        assertEquals(ChatText.Problem.NONE, ChatText.check("Geliyorum, 5 dk"));
    }

    private static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(s);
        return out.toString();
    }
}
