package gg.projects.mobile.content;

import static gg.projects.mobile.content.FeedFixtures.utf8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HOME1 on the phone: the home card's community row follows pl-content's {@code Links::community}
 * (docs/content-format.md "Community platforms"), the same cases as its Rust tests.
 */
public class CommunityLinksTest {
    private static LauncherConfig.Links links(String discord, LauncherConfig.CustomLink... custom) {
        LauncherConfig.Links links = new LauncherConfig.Links();
        links.discord = discord;
        links.custom = Arrays.asList(custom);
        return links;
    }

    private static LauncherConfig.CustomLink link(String id, String url) {
        return new LauncherConfig.CustomLink(id, id, url, null);
    }

    private static List<String> shown(List<CommunityLinks.Link> links) {
        List<String> out = new ArrayList<>();
        for (CommunityLinks.Link link : links) out.add(link.platform.key + "=" + link.url);
        return out;
    }

    @Test
    public void withoutPlatformsTheBuiltInAddressesShow() {
        List<String> expected = Arrays.asList(
                "wiki=https://wiki.projects.gg",
                "discord=https://projects.gg/discord",
                "forum=https://forum.projects.gg",
                "instagram=https://www.instagram.com/projects.oyun/",
                "tiktok=https://www.tiktok.com/@projects.oyun",
                "youtube=https://www.youtube.com/@projectsoyun");
        assertEquals(expected, shown(CommunityLinks.of(null)));
        // A custom link that is no platform (and one named "discord") changes nothing.
        assertEquals(expected, shown(CommunityLinks.of(links(null, link("rules", "https://projects.gg/kurallar"),
                link("discord", "https://discord.gg/other")))));
    }

    @Test
    public void discordComesFromItsOwnFieldAndIsNeverHidden() {
        assertEquals("discord=https://discord.gg/projects", shown(CommunityLinks.of(links("https://discord.gg/projects"))).get(1));
        assertEquals("discord=https://projects.gg/discord", shown(CommunityLinks.of(links("http://discord.gg/projects"))).get(1));
        assertEquals("discord=https://projects.gg/discord", shown(CommunityLinks.of(links(""))).get(1));
    }

    @Test
    public void onceStudioNamesOneTheConfigDecides() {
        List<String> got = shown(CommunityLinks.of(links(null, link("instagram", "https://www.instagram.com/new/"),
                new LauncherConfig.CustomLink("yt", "YouTube", "https://www.youtube.com/@new", " YouTube "),
                link("twitter", "https://x.com/projects"),
                link("instagram", "https://www.instagram.com/second/"),
                link("forum", ""),
                link("twitch", "http://twitch.tv/projects"),
                link("kick", "https://user:pw@kick.com/projects"))));
        assertEquals(Arrays.asList(
                "discord=https://projects.gg/discord",
                "instagram=https://www.instagram.com/new/",
                "youtube=https://www.youtube.com/@new",
                "x=https://x.com/projects"), got);
    }

    @Test
    public void groupsAndNames() {
        assertFalse(CommunityLinks.Platform.WIKI.social);
        assertFalse(CommunityLinks.Platform.FORUM.social);
        assertTrue(CommunityLinks.Platform.TIKTOK.social);
        assertEquals("TikTok", CommunityLinks.Platform.TIKTOK.label);
    }

    @Test
    public void theConfigReadsCustomEntryByEntry() throws Exception {
        LauncherConfig config = LauncherConfig.parse(utf8("{\"schema\":1,\"links\":{\"discord\":\"https://discord.gg/x\","
                + "\"custom\":[{\"id\":\"instagram\",\"label\":\"Instagram\",\"url\":\"https://www.instagram.com/p/\"},5,"
                + "{\"id\":\"wiki\",\"label\":\"Wiki\"}]}}"));
        assertEquals("the broken entries drop themselves", 1, config.links.custom.size());
        assertEquals(Arrays.asList("discord=https://discord.gg/x", "instagram=https://www.instagram.com/p/"),
                shown(CommunityLinks.of(config.links)));
        assertEquals(6, CommunityLinks.of(LauncherConfig.parse(utf8("{\"schema\":1}")).links).size());
    }
}
