package gg.projects.mobile.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Mirrors the path.rs and glob.rs tests of pl-content. */
public class PathsAndGlobTest {
    @Test
    public void acceptsNormalPackPaths() throws Exception {
        for (String path : new String[]{
                "mods/sodium-fabric-0.6.0+mc1.21.1.jar", "config/sodium-options.json", "config/sub dir/x.toml",
                "options.txt", "resourcepacks/Faithful 32x.zip", "shaderpacks/Complementary_r5.zip",
                "config/a.b.c/d", "mods/ünïcödé.jar"}) {
            ContentPaths.checkRelativePath(path);
        }
    }

    @Test
    public void rejectsUnsafePaths() {
        StringBuilder longPath = new StringBuilder();
        for (int i = 0; i < 520; i++) longPath.append('a');
        for (String path : new String[]{
                "", "/etc/passwd", "../x", "mods/../../x", "mods/./x", "mods//x", "mods\\x", "C:/x", "file:stream",
                "mods/x\u0000", "mods/x\n", "mods/x.", "mods/x ", "con", "CON.txt", "mods/nul.jar", "aux", "COM1",
                "lpt9.log", "mods/a<b", "mods/a?b", "mods/a*b", "mods/a|b", "mods/a\"b", ".projects/pack-state.json",
                ".PROJECTS/x", longPath.toString()}) {
            try {
                ContentPaths.checkRelativePath(path);
                fail("accepted " + path);
            } catch (ContentException e) {
                // expected
            }
        }
        // Not device names.
        assertTrue(ok("console.txt"));
        assertTrue(ok("com10"));
        assertTrue(ok("mods/.projects/x"));
    }

    @Test
    public void mirrorPathsUseARestrictedAlphabet() {
        assertTrue(ContentPaths.isMirrorPath("media/4c5ed0886b06a3f9.webm"));
        assertTrue(ContentPaths.isMirrorPath("packs/projects/2.3.0.json"));
        assertFalse(ContentPaths.isMirrorPath("media/a b.webp"));
        assertFalse(ContentPaths.isMirrorPath("media/a+b.webp"));
        assertFalse(ContentPaths.isMirrorPath("media/ü.webp"));
    }

    @Test
    public void urls() {
        assertTrue(ContentPaths.isHttpUrl("https://cdn.modrinth.com/data/x.jar"));
        assertTrue(ContentPaths.isHttpUrl("http://example.com/x"));
        assertFalse(ContentPaths.isHttpUrl("ftp://example.com/x"));
        assertFalse(ContentPaths.isHttpUrl("projects://pack/projects"));
        assertFalse(ContentPaths.isHttpUrl("not a url"));
        assertTrue(ContentPaths.isHttpsUrl("HTTPS://projects.gg"));
        assertFalse(ContentPaths.isHttpsUrl("http://projects.gg"));
        assertFalse(ContentPaths.isHttpsUrl("javascript:alert(1)"));
    }

    @Test
    public void onlyHttpsAddressesReachTheBrowser() {
        // What "Mod sayfası" and the news links hand to ACTION_VIEW: the address itself...
        assertEquals("https://modrinth.com/project/AANobbMI", ContentPaths.browserUrl("https://modrinth.com/project/AANobbMI"));
        assertEquals("https://www.wimods.net/wi-zoom/?a=1#top", ContentPaths.browserUrl("https://www.wimods.net/wi-zoom/?a=1#top"));
        // ...with the scheme in lower case: Android matches intent schemes case-sensitively.
        assertEquals("https://Modrinth.com/Project/X", ContentPaths.browserUrl("HTTPS://Modrinth.com/Project/X"));
        assertEquals("https://projects.gg", ContentPaths.browserUrl("hTtPs://projects.gg"));
        // Nothing else: other schemes, no host, relative or malformed addresses.
        for (String bad : new String[]{null, "", "http://modrinth.com/project/x", "javascript:alert(1)",
                "intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/x.html", "content://x/y", "projects://mods",
                "market://details?id=x", "data:text/html,x", "https://", "https:///path", "https:modrinth.com",
                "//modrinth.com/x", "modrinth.com/project/x", " https://modrinth.com", "https://modrinth.com/a b",
                "https://modrinth.com/\n"}) {
            assertNull(String.valueOf(bad), ContentPaths.browserUrl(bad));
        }
    }

    @Test
    public void singleStarStaysInOneFolder() {
        assertTrue(Glob.matches("*.txt", "options.txt"));
        assertFalse(Glob.matches("*.txt", "config/options.txt"));
        assertTrue(Glob.matches("config/*.json", "config/a.json"));
        assertFalse(Glob.matches("config/*.json", "config/sub/a.json"));
        assertTrue(Glob.matches("config/*", "config/a.json"));
        assertTrue(Glob.matches("mods/sodium-*.jar", "mods/sodium-0.6.0.jar"));
    }

    @Test
    public void doubleStarCrossesFolders() {
        assertTrue(Glob.matches("config/**", "config/a.json"));
        assertTrue(Glob.matches("config/**", "config/a/b/c.toml"));
        assertFalse(Glob.matches("config/**", "configs/a.json"));
        assertFalse(Glob.matches("config/**", "config"));
        assertTrue(Glob.matches("config/**/x.json", "config/x.json"));
        assertTrue(Glob.matches("config/**/x.json", "config/a/b/x.json"));
        assertTrue(Glob.matches("**/*.json", "a.json"));
        assertTrue(Glob.matches("**/*.json", "config/a/b.json"));
        assertTrue(Glob.matches("**", "anything/at/all"));
    }

    @Test
    public void questionMarkAndLiterals() {
        assertTrue(Glob.matches("option?.txt", "options.txt"));
        assertFalse(Glob.matches("a?b", "a/b"));
        assertTrue(Glob.matches("options.txt", "options.txt"));
        assertFalse(Glob.matches("options.txt", "Options.txt"));
        assertFalse(Glob.matches("", "x"));
        assertTrue(Glob.matches("", ""));
    }

    private static boolean ok(String path) {
        try {
            ContentPaths.checkRelativePath(path);
            return true;
        } catch (ContentException e) {
            return false;
        }
    }
}
