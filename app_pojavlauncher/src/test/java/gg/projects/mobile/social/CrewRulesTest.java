package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import gg.projects.mobile.social.SocialModel.Profile;

/** M19: a crew's name, tag and motto checked like the service (src/names.ts), and pictures' sources. */
public class CrewRulesTest {
    @Test
    public void names() {
        assertEquals(CrewRules.Problem.NONE, CrewRules.name("Gece Kurtları"));
        assertEquals(CrewRules.Problem.NONE, CrewRules.name("  A&B  Takımı! "));
        assertEquals(CrewRules.Problem.NAME_SHORT, CrewRules.name("ab"));
        assertEquals("two letters or digits at least", CrewRules.Problem.NAME_SHORT, CrewRules.name("a&!"));
        assertEquals(CrewRules.Problem.NAME_LONG, CrewRules.name("abcdefghijklmnopqrstuvwxy"));
        assertEquals("no links", CrewRules.Problem.NAME_CHARS, CrewRules.name("ekip.gg"));
        assertEquals("no emoji", CrewRules.Problem.NAME_CHARS, CrewRules.name("Ekip 🔥"));
        assertEquals("invisible characters go", CrewRules.Problem.NONE, CrewRules.name("Ek​ip"));
    }

    @Test
    public void tagsTheTurkishWay() {
        assertEquals("GKR", CrewRules.tagOf("gkr"));
        assertEquals("İÇİ", CrewRules.tagOf("içi"));
        assertEquals("IŞI", CrewRules.tagOf("ışı"));
        assertEquals(CrewRules.Problem.NONE, CrewRules.tag("ab12"));
        assertEquals(CrewRules.Problem.TAG, CrewRules.tag("a"));
        assertEquals(CrewRules.Problem.TAG, CrewRules.tag("abcde"));
        assertEquals(CrewRules.Problem.TAG, CrewRules.tag("a-b"));
    }

    @Test
    public void mottos() {
        assertEquals(CrewRules.Problem.NONE, CrewRules.motto(""));
        assertEquals("only spaces: no motto", CrewRules.Problem.NONE, CrewRules.motto("   "));
        assertEquals(CrewRules.Problem.NONE, CrewRules.motto("Hazır mısın, kardeşim?"));
        assertEquals(CrewRules.Problem.MOTTO_LONG, CrewRules.motto("12345678901234567890123456789012345678901"));
        assertEquals(CrewRules.Problem.MOTTO_CHARS, CrewRules.motto("discord.gg/abc"));
        assertEquals(CrewRules.Problem.MOTTO_LETTERS, CrewRules.motto("?!"));
    }

    @Test
    public void picturesComeFromTheThreeSourcesOnly() {
        Profile preset = new Profile("100000000000000002", "ayse", "Ayşe", "preset:owl", null);
        assertEquals("owl", preset.presetSlug());
        assertNull(preset.discordAvatarUrl(64));
        String hash = "0123456789abcdef0123456789abcdef01234567";
        Profile head = new Profile("100000000000000002", "ayse", null, "minecraft:" + hash, null);
        assertEquals(hash, head.textureHash());
        assertEquals("ayse", head.name());
        Profile discord = new Profile("100000000000000002", "ayse", "Ayşe", "discord", "a_0123456789abcdef0123456789abcdef");
        assertEquals("https://cdn.discordapp.com/avatars/100000000000000002/a_0123456789abcdef0123456789abcdef.png?size=64",
                discord.discordAvatarUrl(64));
        // No picture of their own: no Discord stand-in either, the face is the name's letter (the desktop's SMALLS-L).
        Profile plain = new Profile("100000000000000002", "ayse", "Ayşe", "discord", null);
        assertNull(plain.discordAvatarUrl(64));
        assertNull(new Profile("100000000000000002", "ayse", "Ayşe", "discord", "not-a-hash").discordAvatarUrl(64));
        assertEquals("https://discord.com/users/100000000000000002", plain.discordProfileUrl());
    }

    @Test
    public void looksFallBackToTodaysLook() {
        assertEquals("gold", CrewLook.color("magenta"));
        assertEquals("cut", CrewLook.frame(null));
        assertEquals("shield", CrewLook.emblem("dragon"));
        assertEquals(20, CrewLook.emblemsFor(false).size());
        assertEquals(36, CrewLook.emblemsFor(true).size());
        assertEquals(0x3D46BDB4, CrewLook.tagTint("teal"));
    }
}
