package gg.projects.mobile.afk.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * MOB-NEXT: where the console finds the game's own words on the phone: English in the newest installed version's
 * client jar, Turkish among that version's assets (a Fabric version through the one it extends); nothing without the
 * game, and no path out of the game's folder.
 */
public class AfkGameLangTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File file, String text) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void jar(File file, String english) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("net/minecraft/client/Main.class"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
            if (english != null) {
                zip.putNextEntry(new ZipEntry("assets/minecraft/lang/en_us.json"));
                zip.write(english.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return null;
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** A game folder with 26.1.2 (jar, asset index 29 with Turkish) and the Projects profile's Fabric on top. */
    private File game() throws IOException {
        File game = tmp.newFolder(".minecraft");
        File versions = new File(game, "versions");
        write(new File(versions, "26.1.2/26.1.2.json"), "{\"id\":\"26.1.2\",\"assetIndex\":{\"id\":\"29\",\"url\":\"x\"}}");
        jar(new File(versions, "26.1.2/26.1.2.jar"), "{\"entity.minecraft.slime\":\"Slime\"}");
        write(new File(versions, "fabric-loader-0.19.5-26.1.2/fabric-loader-0.19.5-26.1.2.json"),
                "{\"id\":\"fabric-loader-0.19.5-26.1.2\",\"inheritsFrom\":\"26.1.2\"}");
        String hash = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12";
        write(new File(game, "assets/indexes/29.json"), "{\"objects\":{\"minecraft/sounds/a.ogg\":{\"hash\":\"00\",\"size\":1},"
                + "\"minecraft/lang/tr_tr.json\":{\"hash\":\"" + hash + "\",\"size\":30},"
                + "\"minecraft/lang/de_de.json\":{\"hash\":\"cd\",\"size\":2}}}");
        write(new File(game, "assets/objects/ab/" + hash), "{\"entity.minecraft.slime\":\"Balçık\"}");
        return game;
    }

    @Test
    public void englishFromTheJarTurkishFromTheAssets() throws Exception {
        AfkGameLang lang = new AfkGameLang(game());
        assertEquals("{\"entity.minecraft.slime\":\"Slime\"}", read(lang.open("en_us")));
        assertEquals("{\"entity.minecraft.slime\":\"Balçık\"}", read(lang.open("tr_tr")));
        assertNull("a language the game did not download", read(lang.open("ja_jp")));
        // The Fabric version names the base's jar and index: one installed version, not two.
        assertEquals(1, lang.installed().size());
    }

    @Test
    public void theMostRecentlyInstalledVersionFirst() throws Exception {
        File game = game();
        File versions = new File(game, "versions");
        write(new File(versions, "26.3/26.3.json"), "{\"id\":\"26.3\",\"assets\":\"30\"}");
        File newer = new File(versions, "26.3/26.3.jar");
        jar(newer, "{\"command.context.here\":\"<--[HERE]\"}");
        assertTrue(newer.setLastModified(new File(versions, "26.1.2/26.1.2.jar").lastModified() + 60_000));
        AfkGameLang lang = new AfkGameLang(game);
        assertEquals("{\"command.context.here\":\"<--[HERE]\"}", read(lang.open("en_us")));
        // 26.3's index was never downloaded: Turkish comes from the version whose assets are there.
        assertEquals("{\"entity.minecraft.slime\":\"Balçık\"}", read(lang.open("tr_tr")));
    }

    @Test
    public void noGameNoWordsAndNoPathOut() throws Exception {
        assertNull(read(new AfkGameLang(tmp.newFolder("none")).open("en_us")));
        assertNull(read(new AfkGameLang(new File(tmp.getRoot(), "missing")).open("tr_tr")));
        // A version without its jar (a download that never finished) and one naming a parent outside the folder.
        File game = tmp.newFolder("half");
        write(new File(game, "versions/26.2/26.2.json"), "{\"id\":\"26.2\",\"assetIndex\":{\"id\":\"29\"}}");
        write(new File(game, "versions/evil/evil.json"), "{\"id\":\"evil\",\"inheritsFrom\":\"../../outside\"}");
        write(new File(game, "assets/indexes/29.json"), "{\"objects\":{\"minecraft/lang/tr_tr.json\":{\"hash\":\"../../x\"}}}");
        AfkGameLang lang = new AfkGameLang(game);
        assertTrue(lang.installed().isEmpty());
        assertNull(read(lang.open("en_us")));
        assertNull(read(lang.open("tr_tr")));
    }
}
