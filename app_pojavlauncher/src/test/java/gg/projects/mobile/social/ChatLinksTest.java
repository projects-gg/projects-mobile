package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * CHAT2: the phone's chat checks against the friends service's shared cases (services/social
 * test/chat-text-vectors.json and test/chat-link-vectors.json, as the launcher's text-vectors.json
 * holds them), with the rule table the app carries (assets social/chat-links.json), run the way the
 * launcher's text.test.ts runs them; then the parts of the rules that test names.
 */
public class ChatLinksTest {
    private static final File RULES = new File("src/main/assets/social/chat-links.json");
    private static final File CASES = new File("src/test/resources/social/text-vectors.json");
    private static final String MIDDLE_FINGER = cps(0x1F595);
    private static final String ZWJ = cps(0x200D);

    @Before
    public void readTheAppsTable() {
        ChatLinks.setSource(() -> new FileInputStream(RULES));
    }

    @Test
    public void theCasesAreTheServicesWithTheTableTheyWereMadeFor() throws IOException {
        JsonObject cases = read(CASES);
        assertTrue(Json.objects(Json.obj(cases, "text"), "vectors").size() >= 45);
        assertTrue(Json.objects(Json.obj(cases, "links"), "vectors").size() > 100);
        assertEquals(Json.num(read(RULES), "version", -1), ChatLinks.rulesVersion());
        assertTrue(Json.str(Json.obj(cases, "links"), "about").contains("rule table version " + ChatLinks.rulesVersion()));
    }

    @Test
    public void textCasesTurnTricksAwayAndLetOrdinaryTextThroughWhileLinksAreOff() throws IOException {
        List<String> failures = new ArrayList<>();
        for (JsonObject vector : Json.objects(Json.obj(read(CASES), "text"), "vectors")) {
            String text = Json.str(vector, "text");
            String reason = Json.str(vector, "reason");
            String want;
            if ("link".equals(reason)) want = "link off";
            else if ("phone".equals(reason)) want = "phone";
            // The word list is the service's: the app knows only the middle finger and says nothing else.
            else if ("words".equals(reason)) want = text.contains(MIDDLE_FINGER) ? "words" : null;
            else want = null;
            String got = answer(ChatText.verdict(text, null));
            if (!Objects.equals(got, want)) failures.add(Json.str(vector, "name") + ": got " + got + ", want " + want);
        }
        assertEquals(new ArrayList<String>(), failures);
    }

    @Test
    public void linkCasesWithStudiosListsAsGiven() throws IOException {
        JsonObject links = Json.obj(read(CASES), "links");
        JsonObject lists = Json.obj(links, "lists");
        List<String> failures = new ArrayList<>();
        for (JsonObject vector : Json.objects(links, "vectors")) {
            boolean invites = Json.bool(vector, "invites", Json.bool(lists, "invites", true));
            ChatLinks.Lists on = Json.bool(vector, "off", false) ? null : new ChatLinks.Lists(ChatLinks.rulesVersion(),
                    Json.strings(lists, "trusted"), Json.strings(lists, "blocked"), invites);
            String text = Json.str(vector, "text");
            String reason = Json.str(vector, "reason");
            String rule = Json.str(vector, "rule");
            String want = reason == null || rule == null ? reason : reason + " " + rule;
            String got = answer(ChatText.verdict(text, on));
            if (reason == null) {
                List<String> wanted = new ArrayList<>();
                for (JsonObject link : Json.objects(vector, "links")) {
                    wanted.add(Json.str(link, "url") + (Json.bool(link, "trusted", false) ? " trusted" : ""));
                }
                List<String> drawn = new ArrayList<>();
                if (on != null) {
                    for (ChatLinks.MessageLink link : ChatLinks.messageLinks(ChatText.clean(text), on)) {
                        drawn.add(link.link.url + (link.trusted ? " trusted" : ""));
                    }
                }
                want = want + " " + wanted;
                got = got + " " + drawn;
            }
            if (!Objects.equals(got, want)) failures.add(Json.str(vector, "name") + ": got " + got + ", want " + want);
        }
        assertEquals(new ArrayList<String>(), failures);
    }

    @Test
    public void aTextGoesOrNotAsTheLauncherSays() {
        ChatLinks.Lists studio = ChatLinks.Lists.defaults();
        assertEquals(ChatText.Problem.EMPTY, ChatText.verdict("", studio).problem);
        assertEquals(ChatText.Problem.EMPTY, ChatText.verdict("   \n ", studio).problem);
        assertEquals("NONE", ChatText.verdict("selam", studio).toString());
        assertEquals("LINK off", ChatText.verdict("discord.gg/x", null).toString());
        assertEquals("PHONE", ChatText.verdict("0532 123 45 67", null).toString());
        assertEquals("NONE", ChatText.verdict(repeat("a", 300), studio).toString());
        assertEquals("TOO_LONG", ChatText.verdict(repeat("a", 301), studio).toString());
        // Spaces the service collapses do not count against the limit.
        assertEquals("NONE", ChatText.verdict(repeat("a ", 150) + "    ", studio).toString());
        // With Studio's lists a plain https link that passes the rules goes; while links are off none does.
        assertEquals("LINK off", ChatText.verdict("bak https://youtu.be/dQw4w9WgXcQ", null).toString());
        assertEquals("NONE", ChatText.verdict("bak https://youtu.be/dQw4w9WgXcQ", studio).toString());
        assertEquals("LINK shortener", ChatText.verdict("https://bit.ly/x", studio).toString());
        assertEquals("LINK form", ChatText.verdict("discord.gg/x", studio).toString());
        // The long s is an s to JavaScript's case-insensitive match: a link, not an https one.
        assertEquals("LINK http", ChatText.verdict("http" + cps(0x017F) + "://example.com", studio).toString());
        // The middle finger is the one listed word the app knows; the rest of the list is the service's.
        assertEquals(ChatText.Problem.WORDS, ChatText.check(MIDDLE_FINGER));
        assertEquals(ChatText.Problem.WORDS, ChatText.check(MIDDLE_FINGER + cps(0x1F3FD)));
        assertTrue(ChatText.usesWords(MIDDLE_FINGER + cps(0x1F3FD)));
        assertFalse(ChatText.usesWords("selam"));
    }

    @Test
    public void joinersAndSelectorsAreCleanedAsTheServiceKeepsThem() {
        assertEquals("selam naber", ChatText.clean("  selam    naber  "));
        assertEquals("a\nb\nc", ChatText.clean("a\r\n\r\nb\n\n\nc"));
        assertEquals("1\n2\n3\n4\n5 6 7", ChatText.clean("1\n2\n3\n4\n5\n6\n7"));
        // A zero width joiner inside a word goes; between two emoji it stays, also after a skin tone.
        assertEquals("abcd", ChatText.clean("ab" + ZWJ + "cd"));
        String coder = cps(0x1F469) + ZWJ + cps(0x1F4BB);
        assertEquals(coder, ChatText.clean(coder));
        String toned = cps(0x1F469, 0x1F3FD) + ZWJ + cps(0x1F4BB);
        assertEquals(toned, ChatText.clean(toned));
        assertEquals(cps(0x1F469, 0x1F4BB), ChatText.clean(cps(0x1F469) + ZWJ + ZWJ + cps(0x1F4BB)));
        // Variation selectors nobody needs go; VS16 stays, one at a time.
        assertEquals("ab", ChatText.clean("a" + cps(0xFE00) + "b"));
        assertEquals("ab", ChatText.clean("a" + cps(0xE0100) + "b"));
        assertEquals(cps(0x2764, 0xFE0F), ChatText.clean(cps(0x2764, 0xFE0F, 0xFE0F, 0xFE0F)));
        assertEquals(cps(0x2764, 0xFE0E), ChatText.clean(cps(0x2764, 0xFE0E, 0x200B, 0xFE0F)));
        // A lone surrogate is invisible data too.
        assertEquals("ab", ChatText.clean("a" + (char) 0xD83D + "b"));
        assertEquals(2, ChatText.length(ChatText.clean(cps(0x1F44D, 0x1F44D))));
    }

    @Test
    public void addressesAreFoundHoweverTheyAreWrittenButProjectsGgGoes() {
        for (String text : new String[]{"discord.gg/abc", "gel discord gg/abc", "https://example.com",
                "www.bedava-elmas.net", "site(.)com'a gir", "youtube . com", "kanal nokta com", "185.12.34.56:25565",
                cps(0x0455) + "ite.com", "youtube" + cps(0x1F534) + "com", "discord" + cps(0x3002) + "gg/abc",
                "site" + cps(0xFE12) + "com"}) {
            assertTrue(text, ChatText.looksLikeLink(text));
        }
        for (String text : new String[]{"play.projects.gg'ye gel", "projects.gg", "saat 12.30'da",
                "3.5 kalp kald" + cps(0x0131), "Geliyorum", "sa as", "naber" + cps(0x1F534) + "ne zaman",
                "sunucu " + cps(0x1F7E2) + " online"}) {
            assertFalse(text, ChatText.looksLikeLink(text));
        }
        for (String text : new String[]{"0532 123 45 67", "+90 532 123 45 67", "5321234567", "0212 345 67 89",
                "0532" + cps(0x1F525) + "123" + cps(0x1F525) + "45" + cps(0x1F525) + "67",
                "0532" + cps(0x1F44D, 0x1F3FD) + "123" + cps(0x1F44D, 0x1F3FD) + "4567"}) {
            assertTrue(text, ChatText.looksLikePhone(text));
        }
        for (String text : new String[]{"x 1234 y 64 z -5678", "skor 120 - 98", "2026", "Skor 3" + cps(0x1F525) + "2",
                "0532" + cps(0x1F525, 0x1F525, 0x1F525, 0x1F525) + "1234567"}) {
            assertFalse(text, ChatText.looksLikePhone(text));
        }
    }

    @Test
    public void symbolsReadAsWhatTheySpell() {
        assertEquals("discord.gg", ChatText.checkForm(flags("discord") + "." + flags("gg")));
        assertEquals("discord", ChatText.checkForm(squared("discord")));
        assertEquals("discord", ChatText.checkForm(cps(0x1D05, 0x026A, 0xA731, 0x1D04, 0x1D0F, 0x0280, 0x1D05)));
        assertEquals("121110", ChatText.checkForm(cps(0x2776, 0x2777, 0x24EB, 0x1F51F)));
        // A flag whose letters spell a listed word reads as a flag in the word check only.
        assertEquals(" ", ChatText.checkForm(flags("aq"), true));
        assertEquals("aq", ChatText.checkForm(flags("aq")));
        assertEquals("sik", ChatText.checkForm(flags("sik"), true));
    }

    @Test
    public void linksAreFoundAsTheServiceFindsThem() {
        List<ChatLinks.Found> found = ChatLinks.find("(https://youtu.be/x'i izle)");
        assertEquals(1, found.size());
        assertEquals("https://youtu.be/x", found.get(0).url);
        assertEquals(1, found.get(0).start);
        assertEquals(19, found.get(0).end);
        assertTrue(ChatLinks.find("xhttps://a.com").isEmpty());
        ChatLinks.Found fire = ChatLinks.find("HTTPS://Modrinth.com/mod/sodium" + cps(0x1F525)).get(0);
        assertEquals("HTTPS://Modrinth.com/mod/sodium", fire.url);
        assertEquals("https", fire.scheme);
        assertEquals("Modrinth.com", fire.authority);
        assertEquals("/mod/sodium", fire.rest);
        assertEquals("modrinth.com", ChatLinks.hostOf(fire));
        // Not after a letter or digit of any plane; after a mark, yes.
        assertTrue(ChatLinks.find(cps(0x1D400) + "https://a.com").isEmpty());
        assertTrue(ChatLinks.find(cps(0x2160) + "https://a.com").isEmpty());
        assertEquals(2, ChatLinks.find("x" + cps(0x0301) + "https://a.com").get(0).start);
        assertEquals("https://a.com", ChatLinks.find("https://a.com).").get(0).url);
        assertEquals("https://a.com/(x)", ChatLinks.find("https://a.com/(x)").get(0).url);
        assertEquals("https://a.com/x", ChatLinks.find("[https://a.com/x]").get(0).url);
        String text = "a https://b.co c";
        assertEquals("a " + repeat(" ", 12) + " c", ChatLinks.withoutLinks(text, ChatLinks.find(text)));
    }

    @Test
    public void linksMayBePressedOnlyWhileTheyPassTheRulesAsTheyAreNow() {
        String text = "https://youtu.be/a https://www.planetminecraft.com/x https://bit.ly/y";
        ChatLinks.Lists studio = ChatLinks.Lists.defaults();
        List<ChatLinks.MessageLink> links = ChatLinks.messageLinks(text, studio);
        assertEquals("[true true, true false, false false]", drawn(links));
        assertEquals(ChatLinks.Rule.SHORTENER, links.get(2).rule);
        assertEquals("www.planetminecraft.com", links.get(1).host);
        // A site Studio blocks later turns back into plain text in older messages.
        ChatLinks.Lists blocked = new ChatLinks.Lists(studio.rules, studio.trusted, Collections.singletonList("youtu.be"), true);
        assertFalse(ChatLinks.messageLinks(text, blocked).get(0).ok);
        assertEquals(ChatLinks.Rule.BLOCKED, ChatLinks.messageLinks(text, blocked).get(0).rule);
        // Links off: nothing is pressed.
        for (ChatLinks.MessageLink link : ChatLinks.messageLinks(text, null)) {
            assertFalse(link.ok);
            assertFalse(link.trusted);
            assertEquals(ChatLinks.Rule.OFF, link.rule);
        }
        // A fourth link is plain text; one with a phone number is no link either.
        String four = "https://a.com/1 https://a.com/2 https://a.com/3 https://a.com/4";
        assertEquals(ChatLinks.Rule.COUNT, ChatLinks.messageLinks(four, studio).get(3).rule);
        assertEquals(ChatLinks.Rule.FORM, ChatLinks.messageLinks("https://wa.me/905321234567", studio).get(0).rule);
    }

    @Test
    public void brandNamesReadAsTheEyeReadsThem() {
        assertEquals(ChatLinks.skeleton("discord"), ChatLinks.skeleton("d1scord"));
        assertEquals("steamcommunlty", ChatLinks.skeleton("steam-comrnunity"));
        assertEquals(1, ChatLinks.typoDistance("dslcord", "dlscord"));
        assertEquals(2, ChatLinks.typoDistance("abc", "cab"));
        assertTrue(ChatLinks.lookalike("discordapp.xyz"));
        assertFalse(ChatLinks.lookalike("cdn.discordapp.com"));
        assertEquals(" free nitro ", ChatLinks.scamForm("FR33 N" + cps(0x0130) + "TR0."));
        assertEquals(" hesabin kapatilacak ", ChatLinks.scamForm("HESABIN kapat" + cps(0x0131) + "lacak"));
        assertEquals("  ", ChatLinks.scamForm("..."));
    }

    @Test
    public void percentEscapesReadAsJavaScriptReadsThem() {
        assertEquals("/setup.exe", ChatLinks.decoded("/setup%2Eexe"));
        assertEquals("a+b c", ChatLinks.decoded("a+b%20c"));
        assertEquals(cps(0xFC), ChatLinks.decoded("%C3%BC"));
        assertEquals(cps(0xFC), ChatLinks.decoded("%c3%bc"));
        assertEquals(cps(0x1F525), ChatLinks.decoded("%F0%9F%94%A5"));
        // One escape that does not read leaves the whole text as it is.
        for (String text : new String[]{"%E0%A4%A", "100%", "%C0%AF", "%ED%A0%80", "%zz", "%F4%90%80%80",
                "%E0%80%80", "%C3", "%C3%28", "%%41", "%41%", "/a%2Eb/%FF"}) {
            assertEquals(text, ChatLinks.decoded(text));
        }
    }

    @Test
    public void withoutTheRuleTableEveryLinkIsOff() {
        ChatLinks.Lists lists = new ChatLinks.Lists(2, Arrays.asList("youtu.be"), Collections.<String>emptyList(), true);
        String link = "bak https://youtu.be/dQw4w9WgXcQ";
        ChatLinks.Source[] broken = {null, () -> new ByteArrayInputStream("{\"version\":".getBytes(StandardCharsets.UTF_8)),
                () -> new ByteArrayInputStream("{\"version\":2}".getBytes(StandardCharsets.UTF_8)), () -> {
                    throw new IOException("gone");
                }};
        try {
            for (ChatLinks.Source source : broken) {
                ChatLinks.setSource(source);
                assertEquals("LINK off", ChatText.verdict(link, lists).toString());
                assertEquals("NONE", ChatText.verdict("selam", lists).toString());
                assertEquals("PHONE", ChatText.verdict("0532 123 45 67", lists).toString());
                List<ChatLinks.MessageLink> drawn = ChatLinks.messageLinks(link, lists);
                assertEquals(1, drawn.size());
                assertFalse(drawn.get(0).ok);
                assertEquals(ChatLinks.Rule.OFF, drawn.get(0).rule);
                assertEquals(0, ChatLinks.rulesVersion());
                assertTrue(ChatLinks.Lists.defaults().trusted.isEmpty());
            }
        } finally {
            ChatLinks.setSource(() -> new FileInputStream(RULES));
        }
        assertEquals("NONE", ChatText.verdict(link, lists).toString());
    }

    @Test
    public void studiosListsReadFromHello() {
        ChatLinks.Lists lists = ChatLinks.Lists.read(Json.parseObject(
                "{\"rules\":2,\"trusted\":[\"projects.gg\"],\"blocked\":[\"example.org\"],\"invites\":false,\"newer\":1}"));
        assertEquals(2, lists.rules);
        assertEquals(Collections.singletonList("projects.gg"), lists.trusted);
        assertEquals(Collections.singletonList("example.org"), lists.blocked);
        assertFalse(lists.invites);
        // Missing fields read as the launcher reads them; another type is no list at all.
        ChatLinks.Lists bare = ChatLinks.Lists.read(new JsonObject());
        assertEquals(0, bare.rules);
        assertTrue(bare.trusted.isEmpty() && bare.blocked.isEmpty() && bare.invites);
        assertNull(ChatLinks.Lists.read(null));
        assertNull(ChatLinks.Lists.read(Json.parseObject("{\"trusted\":\"projects.gg\"}")));
        assertNull(ChatLinks.Lists.read(Json.parseObject("{\"blocked\":[1]}")));
        assertNull(ChatLinks.Lists.read(Json.parseObject("{\"invites\":\"yes\"}")));
        assertNull(ChatLinks.Lists.read(Json.parseObject("{\"rules\":\"2\"}")));
        // Before Studio sets them: the table's.
        ChatLinks.Lists defaults = ChatLinks.Lists.defaults();
        assertEquals(ChatLinks.rulesVersion(), defaults.rules);
        assertTrue(defaults.trusted.contains("projects.gg") && defaults.trusted.contains("youtu.be"));
        assertTrue(defaults.blocked.isEmpty() && defaults.invites);
        assertEquals("shortener", ChatLinks.Rule.SHORTENER.wire());
        assertEquals(ChatLinks.Rule.LOOKALIKE, ChatLinks.Rule.of("lookalike"));
        assertNull(ChatLinks.Rule.of("newer"));
        assertNull(ChatLinks.Rule.of(null));
    }

    // ---- helpers ----

    private static JsonObject read(File file) throws IOException {
        try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        }
    }

    /** A verdict as text.test.ts compares the launcher's answer: null when the text may go (or is empty). */
    @Nullable
    private static String answer(ChatText.Verdict verdict) {
        switch (verdict.problem) {
            case LINK: return "link " + verdict.rule.wire();
            case PHONE: return "phone";
            case WORDS: return "words";
            case TOO_LONG: return "long";
            default: return null;
        }
    }

    private static String drawn(List<ChatLinks.MessageLink> links) {
        List<String> out = new ArrayList<>();
        for (ChatLinks.MessageLink link : links) out.add(link.ok + " " + link.trusted);
        return out.toString();
    }

    private static String cps(int... codePoints) {
        StringBuilder out = new StringBuilder();
        for (int cp : codePoints) out.appendCodePoint(cp);
        return out.toString();
    }

    /** Letters as regional indicators (the letters of flags). */
    private static String flags(String letters) {
        StringBuilder out = new StringBuilder();
        for (char c : letters.toCharArray()) out.appendCodePoint(0x1F1E6 + c - 'a');
        return out.toString();
    }

    /** Letters as negative squared capitals. */
    private static String squared(String letters) {
        StringBuilder out = new StringBuilder();
        for (char c : letters.toCharArray()) out.appendCodePoint(0x1F170 + c - 'a');
        return out.toString();
    }

    private static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(s);
        return out.toString();
    }
}
