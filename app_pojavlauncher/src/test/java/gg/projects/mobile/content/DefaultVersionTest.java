package gg.projects.mobile.content;

import static gg.projects.mobile.content.FeedFixtures.utf8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@code LauncherConfig.recommendedMinecraftVersion} (content-format.md "Launcher config"; the
 * desktop's {@code recommended_minecraft}), the default profile's version and the "Önerilen" mark.
 */
public class DefaultVersionTest {
    private static final String BUILT_IN = ContentConfig.DEFAULT_RECOMMENDED_MINECRAFT_VERSION;

    private static LauncherConfig parse(String json) throws ContentException {
        return LauncherConfig.parse(utf8(json));
    }

    private static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(s);
        return out.toString();
    }

    private static String recommendedOf(String version) {
        LauncherConfig config = new LauncherConfig();
        config.recommendedMinecraftVersion = version;
        return config.recommendedMinecraft();
    }

    @Test
    public void recommendedVersionFallsBackToTheBuiltInDefault() throws Exception {
        assertEquals("26.1.2", BUILT_IN);
        assertEquals(BUILT_IN, DefaultVersion.recommended(null));
        assertEquals(BUILT_IN, parse("{}").recommendedMinecraft());
        assertEquals("1.21.11", parse("{\"recommendedMinecraftVersion\":\"1.21.11\"}").recommendedMinecraft());
        assertEquals("1.21.11", DefaultVersion.recommended(parse("{\"recommendedMinecraftVersion\":\"1.21.11\"}")));
        // Snapshots and pre-releases are version ids too.
        // Sixteen two-byte characters (U+015F) are the longest id: 32 bytes in UTF-8.
        for (String good : new String[]{"26w41a", "1.21.4-pre1", "26.2-snapshot-3", repeat("9", 32), repeat("\u015f", 16)}) {
            assertEquals(good, recommendedOf(good));
        }
        // A broken value never reaches a profile: the built-in default stands in (the desktop's cases,
        // plus Unicode spaces, 33 bytes counted in UTF-8 and a lone surrogate).
        for (String bad : new String[]{"", " ", "1.21 4", "1.21.4\n", "1.21\u0007", repeat("9", 33), "1.21\u00a0",
                "1.21\u2003", repeat("\u015f", 16) + "a", "1.21\ud800", null}) {
            assertEquals(String.valueOf(bad), BUILT_IN, recommendedOf(bad));
        }
    }

    @Test
    public void aMalformedRecommendationDropsOnlyItself() throws Exception {
        for (String value : new String[]{"12", "null", "[\"1.21\"]", "{\"id\":\"1.21\"}", "true"}) {
            LauncherConfig config = parse("{\"recommendedMinecraftVersion\":" + value + ",\"recommendedPack\":\"projects\"}");
            assertEquals(value, BUILT_IN, config.recommendedMinecraft());
            assertEquals("projects", config.recommendedPack);
        }
    }

    @Test
    public void theUpstreamDefaultProfileMovesToTheRecommendation() {
        // A new install, or an update from 0.1.0: Amethyst's untouched default, as the bundled
        // launcher_profiles.json names it and as an empty profile list gets it.
        assertEquals("26.1.2", DefaultVersion.next("(Default)", "1.7.10", null, "26.1.2"));
        assertEquals("26.1.2", DefaultVersion.next("Default", "1.7.10", null, "26.1.2"));
        assertEquals("1.21.11", DefaultVersion.next("(Default)", "1.7.10", null, "1.21.11"));
        // Already there.
        assertNull(DefaultVersion.next("Default", "26.1.2", "26.1.2", "26.1.2"));
        // A newer recommendation is followed while the profile keeps the version the app gave it.
        assertEquals("26.2", DefaultVersion.next("Default", "26.1.2", "26.1.2", "26.2"));
        // A rename alone is not a version choice.
        assertEquals("26.2", DefaultVersion.next("Benim profilim", "26.1.2", "26.1.2", "26.2"));
    }

    @Test
    public void versionListsMarkOnlyTheRecommendedVersion() throws Exception {
        // The desktop's rule: the row whose id is the recommendation (in any group, an installed copy
        // of it too); a list without it marks nothing.
        String recommended = DefaultVersion.recommended(parse("{\"recommendedMinecraftVersion\":\"1.21.11\"}"));
        assertTrue(DefaultVersion.isRecommended("1.21.11", recommended));
        for (String other : new String[]{"1.21.1", "1.21.110", "1.21.11-rc1", " 1.21.11", "1.21.11 ", "1.21.11\n",
                "fabric-loader-0.17.2-1.21.11", "1.21.11-forge-61.0.2", "26.1.2", "", null}) {
            assertFalse(String.valueOf(other), DefaultVersion.isRecommended(other, recommended));
        }
        // A recommended snapshot is marked like any other id.
        assertTrue(DefaultVersion.isRecommended("26w41a", recommendedOf("26w41a")));
        // Before any content, and when the config's value is broken, the built-in default is marked.
        assertTrue(DefaultVersion.isRecommended("26.1.2", DefaultVersion.recommended(null)));
        assertFalse(DefaultVersion.isRecommended("1.21.11", DefaultVersion.recommended(null)));
        assertTrue(DefaultVersion.isRecommended("26.1.2",
                DefaultVersion.recommended(parse("{\"recommendedMinecraftVersion\":\"1.21 4\"}"))));
        // Nothing to recommend, nothing marked.
        assertFalse(DefaultVersion.isRecommended("26.1.2", null));
        assertFalse(DefaultVersion.isRecommended("", ""));
    }

    @Test
    public void aVersionThePlayerPickedIsNeverChanged() {
        assertNull(DefaultVersion.next("Default", "1.20.1", "26.1.2", "26.2"));
        // Even 1.7.10 on a profile the app manages, and "latest release": the player picked them.
        assertNull(DefaultVersion.next("Default", "1.7.10", "26.1.2", "26.2"));
        assertNull(DefaultVersion.next("Default", "latest-release", "26.1.2", "26.2"));
        // Profiles the app never managed: only Amethyst's untouched default moves.
        assertNull(DefaultVersion.next("Default", "1.20.1", null, "26.1.2"));
        assertNull(DefaultVersion.next("(Default)", "1.12.2", null, "26.1.2"));
        assertNull(DefaultVersion.next("Survival", "1.7.10", null, "26.1.2"));
        assertNull(DefaultVersion.next(null, null, null, "26.1.2"));
    }
}
