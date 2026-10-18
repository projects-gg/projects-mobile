package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import gg.projects.mobile.content.AtomicFiles;

/** The first options of a new game folder (the desktop's pl-mc defaults.rs seed_options tests). */
public class FirstOptionsTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** A client jar holding {@code version.json} with {@code json}, or no {@code version.json} at all. */
    private File clientJar(String name, String json) throws IOException {
        File jar = new File(temp.getRoot(), name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("net/minecraft/client/Main.class"));
            zip.write("class".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (json != null) {
                zip.putNextEntry(new ZipEntry("version.json"));
                zip.write(json.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static String read(File game) throws IOException {
        return new String(AtomicFiles.read(new File(game, FirstOptions.FILE_NAME), 1 << 20), StandardCharsets.UTF_8);
    }

    private static void write(File game, String content) throws IOException {
        AtomicFiles.write(new File(game, FirstOptions.FILE_NAME), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void versionsGateTheLanguageCodeAndTheOnboarding() {
        Object[][] table = {
                {"1.21.4", "tr_tr", true},
                {"1.19.3", "tr_tr", true},
                {"1.19.3-pre1", "tr_tr", true},
                {"22w42a", "tr_tr", true},
                {"1.19.2", "tr_tr", false},
                {"22w24a", "tr_tr", false},
                {"1.11", "tr_tr", false},
                {"1.11-pre1", "tr_tr", false},
                {"16w32a", "tr_tr", false},
                {"26.1", "tr_tr", true},
                {"26.1.2", "tr_tr", true},
                {"26.1-snapshot-1", "tr_tr", true},
                {"1.10.2", "tr_TR", false},
                {"1.8.9", "tr_TR", false},
                {"1.7.10", "tr_TR", false},
                {"16w21a", "tr_TR", false},
                {"b1.7.3", "tr_TR", false},
                {"a1.2.6", "tr_TR", false},
                {"", "tr_TR", false},
                {null, "tr_TR", false},
        };
        for (Object[] row : table) {
            String version = (String) row[0];
            assertEquals(String.valueOf(version), row[1], FirstOptions.turkishLanguage(version));
            assertEquals(String.valueOf(version), row[2], FirstOptions.hasAccessibilityOnboarding(version));
        }
    }

    @Test
    public void aNewFolderStartsInTurkishWithTheDataVersion() throws Exception {
        File jar = clientJar("26.1.2.jar", "{\"id\":\"26.1.2\",\"world_version\":4790,\"java_version\":25}");
        assertEquals(Integer.valueOf(4790), FirstOptions.clientDataVersion(jar));
        File game = new File(temp.getRoot(), "custom_instances/projects");
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(game, "26.1.2", Collections.singletonList(jar), true));
        // No GUI scale: the game's "Auto" keeps buttons finger-sized on a phone.
        assertEquals("version:4790\nlang:tr_tr\n", read(game));
        // Only once: from then on the file is the game's and the player's.
        write(game, "version:4790\nlang:en_us\nguiScale:3\nrenderDistance:6\n");
        assertEquals(FirstOptions.Result.EXISTS, FirstOptions.seed(game, "26.1.2", Collections.singletonList(jar), true));
        assertEquals("version:4790\nlang:en_us\nguiScale:3\nrenderDistance:6\n", read(game));
    }

    @Test
    public void aPacksOrThePlayersOptionsAreNeverTouched() throws Exception {
        File jar = clientJar("1.21.1.jar", "{\"world_version\":3955}");
        File game = temp.newFolder("pack");
        String pack = "version:3955\r\nlang:en_us\r\nkey_key.jump:key.keyboard.space\r\n";
        write(game, pack);
        assertEquals(FirstOptions.Result.EXISTS, FirstOptions.seed(game, "1.21.1", Collections.singletonList(jar), true));
        assertEquals(pack, read(game));
        // A line the game would not write is not ours either.
        write(game, "fullscreen:off\nsomething odd\n");
        assertEquals(FirstOptions.Result.EXISTS, FirstOptions.seed(game, "1.21.1", Collections.singletonList(jar), true));
    }

    @Test
    public void upstreamsWindowLinesDoNotCountAsTheGamesOptions() throws Exception {
        // Upstream creates an empty file and writes the window size before the game starts; an
        // earlier launch that stopped before the game saved its options leaves exactly that.
        File jar = clientJar("1.21.1.jar", "{\"world_version\":3955}");
        File game = temp.newFolder("stopped");
        write(game, "");
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(game, "1.21.1", Collections.singletonList(jar), true));
        assertEquals("version:3955\nlang:tr_tr\n", read(game));

        write(game, "overrideHeight:1080\nfullscreen:off\r\noverrideWidth:2400\n\n");
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(game, "1.21.1", Collections.singletonList(jar), true));
        assertEquals("version:3955\nlang:tr_tr\noverrideHeight:1080\nfullscreen:off\noverrideWidth:2400\n", read(game));

        // Its own lines from a launch that stopped early are written again, the window kept.
        write(game, "lang:tr_tr\noverrideWidth:2400\nversion:3955\n");
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(game, "1.21.1", Collections.singletonList(jar), true));
        assertEquals("version:3955\nlang:tr_tr\noverrideWidth:2400\n", read(game));
    }

    @Test
    public void aLoaderVersionFallsBackToTheVanillaJar() throws Exception {
        File missing = new File(temp.getRoot(), "fabric-loader-0.19.5-26.1.2.jar");
        File vanilla = clientJar("26.1.2.jar", "{\"world_version\":4790}");
        File game = temp.newFolder("fabric");
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(game, "26.1.2", Arrays.asList(missing, vanilla), true));
        assertEquals("version:4790\nlang:tr_tr\n", read(game));
    }

    @Test
    public void anUnknownDataVersion() throws Exception {
        File noJson = clientJar("1.12.2.jar", null);
        assertNull(FirstOptions.clientDataVersion(noJson));
        // Older versions have no onboarding: the language alone is safe.
        File old = temp.newFolder("old");
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(old, "1.12.2", Collections.singletonList(noJson), true));
        assertEquals("lang:tr_tr\n", read(old));
        File older = temp.newFolder("older");
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(older, "1.8.9", Collections.singletonList(noJson), true));
        assertEquals("lang:tr_TR\n", read(older));
        // From 1.19.3 on, the game would switch the onboarding off: better nothing than that.
        File modern = temp.newFolder("modern");
        assertEquals(FirstOptions.Result.SKIPPED, FirstOptions.seed(modern, "1.21.4", Collections.singletonList(noJson), true));
        assertFalse(new File(modern, FirstOptions.FILE_NAME).exists());
        assertEquals(FirstOptions.Result.SKIPPED, FirstOptions.seed(modern, "1.21.4", new ArrayList<File>(), true));
    }

    @Test
    public void onlyAnIntegerWorldVersionCounts() throws Exception {
        assertNull(FirstOptions.clientDataVersion(clientJar("a.jar", "{\"world_version\":\"4790\"}")));
        assertNull(FirstOptions.clientDataVersion(clientJar("b.jar", "{\"world_version\":47.5}")));
        assertNull(FirstOptions.clientDataVersion(clientJar("c.jar", "{\"world_version\":-1}")));
        assertNull(FirstOptions.clientDataVersion(clientJar("d.jar", "[4790]")));
        assertNull(FirstOptions.clientDataVersion(clientJar("e.jar", "not json")));
        assertNull(FirstOptions.clientDataVersion(new File(temp.getRoot(), "missing.jar")));
        File notAZip = temp.newFile("broken.jar");
        AtomicFiles.write(notAZip, "PK? no".getBytes(StandardCharsets.UTF_8));
        assertNull(FirstOptions.clientDataVersion(notAZip));
    }

    @Test
    public void launcherOnlyLines() {
        List<String> kept = new ArrayList<>();
        assertTrue(FirstOptions.launcherOnly("", kept));
        assertTrue(FirstOptions.launcherOnly("fullscreen:off\noverrideWidth:1\nversion:1\nlang:tr_tr", kept));
        assertEquals(Arrays.asList("fullscreen:off", "overrideWidth:1"), kept);
        assertFalse(FirstOptions.launcherOnly("guiScale:2\n", new ArrayList<String>()));
        assertFalse(FirstOptions.launcherOnly(":value\n", new ArrayList<String>()));
        assertFalse(FirstOptions.launcherOnly("fullscreen\n", new ArrayList<String>()));
    }

    @Test
    public void theGameStartsInTheAppsLanguage() throws Exception {
        File jar = clientJar("26.1.2-en.jar", "{\"world_version\":4790}");
        File game = new File(temp.getRoot(), "english");
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(game, "26.1.2", Collections.singletonList(jar), false));
        assertEquals("version:4790\nlang:en_us\n", read(game));
        // A folder the game never saved into follows the app's language at the next start.
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(game, "26.1.2", Collections.singletonList(jar), true));
        assertEquals("version:4790\nlang:tr_tr\n", read(game));

        assertEquals("en_us", FirstOptions.gameLanguage("1.21.1", false));
        assertEquals("en_US", FirstOptions.gameLanguage("1.10.2", false));
        assertEquals("en_us", FirstOptions.gameLanguage("26.1.2", false));
        assertEquals("tr_TR", FirstOptions.gameLanguage("16w31a", true));
        assertEquals("tr_tr", FirstOptions.gameLanguage("16w32a", true));
    }

    @Test
    public void upstreamsDefaultFolderOptionsSpeakTheAppsLanguage() {
        String asset = "invertYMouse:false\r\nlang:tr_TR\r\nrenderDistance:2\r\n";
        assertEquals("invertYMouse:false\r\nlang:en_US\r\nrenderDistance:2\r\n", FirstOptions.withLanguage(asset, false));
        assertEquals(asset, FirstOptions.withLanguage(asset, true));
        assertEquals("renderDistance:2\nlang:en_US\n", FirstOptions.withLanguage("renderDistance:2", false));
        assertEquals("renderDistance:2\nlang:tr_TR\n", FirstOptions.withLanguage("renderDistance:2\n", true));
    }
}
