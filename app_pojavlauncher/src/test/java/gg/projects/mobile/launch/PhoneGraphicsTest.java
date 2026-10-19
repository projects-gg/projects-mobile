package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

/** M17: the phone's graphics preset in a new options.txt (the desktop's W2A rule, made for phones). */
public class PhoneGraphicsTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final long GB = 1024L * 1024 * 1024;

    private static long gb(double value) {
        return (long) (value * GB);
    }

    @Test
    public void theTierFollowsMemoryCoresAndTheGpu() {
        // What Android reports for 4, 6 and 8 GB phones.
        assertEquals(PhoneGraphics.Tier.LIGHT, PhoneGraphics.tier(gb(3.6), false, 8, "Adreno (TM) 650"));
        assertEquals(PhoneGraphics.Tier.BALANCED, PhoneGraphics.tier(gb(5.5), false, 8, "Adreno (TM) 650"));
        assertEquals(PhoneGraphics.Tier.RICH, PhoneGraphics.tier(gb(7.4), false, 8, "Adreno (TM) 650"));
        assertEquals(PhoneGraphics.Tier.RICH, PhoneGraphics.tier(gb(11.2), false, 8, "Adreno (TM) 830"));
        assertEquals(PhoneGraphics.Tier.RICH, PhoneGraphics.tier(gb(7.5), false, 8, "Mali-G78 MP14"));
        assertEquals(PhoneGraphics.Tier.RICH, PhoneGraphics.tier(gb(7.5), false, 8, "Mali-G715-Immortalis MC11"));
        assertEquals(PhoneGraphics.Tier.RICH, PhoneGraphics.tier(gb(7.5), false, 8, "Samsung Xclipse 940"));
        // A strong phone with a GPU it does not know stays balanced.
        assertEquals(PhoneGraphics.Tier.BALANCED, PhoneGraphics.tier(gb(7.5), false, 8, "Android Emulator OpenGL ES Translator"));
        assertEquals(PhoneGraphics.Tier.BALANCED, PhoneGraphics.tier(gb(7.5), false, 8, null));
        assertEquals(PhoneGraphics.Tier.BALANCED, PhoneGraphics.tier(gb(7.5), false, 8, "Mali-G68 MC4"));
        assertEquals(PhoneGraphics.Tier.BALANCED, PhoneGraphics.tier(gb(7.5), false, 8, "Adreno (TM) 619"));
        // A weak GPU, Android's low-RAM flag or few cores: light, however much memory.
        assertEquals(PhoneGraphics.Tier.LIGHT, PhoneGraphics.tier(gb(11), false, 8, "Mali-G52 MC2"));
        assertEquals(PhoneGraphics.Tier.LIGHT, PhoneGraphics.tier(gb(7.4), false, 8, "Adreno (TM) 506"));
        assertEquals(PhoneGraphics.Tier.LIGHT, PhoneGraphics.tier(gb(7.4), false, 8, "PowerVR Rogue GE8320"));
        assertEquals(PhoneGraphics.Tier.LIGHT, PhoneGraphics.tier(gb(7.4), false, 8, "Mali-T830"));
        assertEquals(PhoneGraphics.Tier.LIGHT, PhoneGraphics.tier(gb(7.4), false, 8, "Google SwiftShader"));
        assertEquals(PhoneGraphics.Tier.LIGHT, PhoneGraphics.tier(gb(7.4), true, 8, "Adreno (TM) 650"));
        assertEquals(PhoneGraphics.Tier.LIGHT, PhoneGraphics.tier(gb(7.4), false, 4, "Adreno (TM) 650"));
        // Unknown memory and cores do not count against the phone.
        assertEquals(PhoneGraphics.Tier.BALANCED, PhoneGraphics.tier(0, false, 0, "Adreno (TM) 650"));
    }

    private static List<String> keys(List<String> lines) {
        List<String> keys = new ArrayList<>();
        for (String line : lines) keys.add(line.substring(0, line.indexOf(':')));
        return keys;
    }

    @Test
    public void theLinesFollowTheVersion() {
        // 26.1.2 (the Projects pack): 1.21.11's options, enum values as JSON strings.
        List<String> light = PhoneGraphics.lines("26.1.2", PhoneGraphics.Tier.LIGHT);
        assertEquals(Arrays.asList("renderDistance:6", "simulationDistance:5", "maxFps:60", "graphicsPreset:\"custom\"",
                "cutoutLeaves:false", "improvedTransparency:false", "weatherRadius:5", "particles:1", "renderClouds:\"false\"",
                "cloudRange:32", "entityShadows:false", "entityDistanceScaling:0.5", "biomeBlendRadius:0", "mipmapLevels:2"), light);
        assertEquals(new ArrayList<>(PhoneGraphics.keys("26.1.2")), keys(light));
        List<String> rich = PhoneGraphics.lines("26.1.2", PhoneGraphics.Tier.RICH);
        assertTrue(rich.contains("renderDistance:10"));
        assertTrue(rich.contains("cutoutLeaves:true"));
        assertTrue(rich.contains("renderClouds:\"fast\""));
        assertTrue(PhoneGraphics.lines("26.1.2", PhoneGraphics.Tier.BALANCED).contains("renderDistance:8"));
        // 1.20.1: graphicsMode, no 1.21.11 options, no cloud range.
        List<String> older = PhoneGraphics.lines("1.20.1", PhoneGraphics.Tier.BALANCED);
        assertTrue(older.contains("graphicsMode:0"));
        assertTrue(older.contains("renderClouds:\"fast\""));
        assertFalse(keys(older).contains("graphicsPreset"));
        assertFalse(keys(older).contains("cutoutLeaves"));
        assertFalse(keys(older).contains("cloudRange"));
        // 1.16.5: enum values bare (before 1.19), no simulation distance (1.18).
        List<String> v1165 = PhoneGraphics.lines("1.16.5", PhoneGraphics.Tier.LIGHT);
        assertTrue(v1165.contains("renderClouds:false"));
        assertFalse(keys(v1165).contains("simulationDistance"));
        assertTrue(keys(v1165).contains("entityDistanceScaling"));
        // 1.12.2: fancyGraphics, no biome blend (1.13) or entity distance (1.16).
        List<String> v1122 = PhoneGraphics.lines("1.12.2", PhoneGraphics.Tier.RICH);
        assertTrue(v1122.contains("fancyGraphics:true"));
        assertFalse(keys(v1122).contains("biomeBlendRadius"));
        assertFalse(keys(v1122).contains("graphicsMode"));
        // 1.7.10: clouds as a switch; before 1.7.2 nothing at all.
        assertTrue(PhoneGraphics.lines("1.7.10", PhoneGraphics.Tier.LIGHT).contains("clouds:false"));
        assertTrue(PhoneGraphics.lines("1.6.4", PhoneGraphics.Tier.LIGHT).isEmpty());
        assertTrue(PhoneGraphics.keys("1.6.4").isEmpty());
    }

    private File clientJar(String json) throws IOException {
        File jar = new File(temp.getRoot(), "client.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    private static String read(File game) throws IOException {
        return new String(AtomicFiles.read(new File(game, FirstOptions.FILE_NAME), 1 << 20), StandardCharsets.UTF_8);
    }

    @Test
    public void aNewFileGetsThePresetAnExistingOneNever() throws Exception {
        File jar = clientJar("{\"world_version\":4790}");
        File game = new File(temp.getRoot(), "custom_instances/projects");
        final List<String> asked = new ArrayList<>();
        FirstOptions.Preset preset = minecraft -> {
            asked.add(minecraft);
            return PhoneGraphics.lines(minecraft, PhoneGraphics.Tier.LIGHT);
        };
        List<String> keyLines = Collections.singletonList("key_key.wi_zoom.zoom:key.keyboard.c");
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(game, "26.1.2", Collections.singletonList(jar), true,
                keyLines, PhoneGraphics.keys("26.1.2"), preset));
        String written = read(game);
        assertTrue(written, written.startsWith("version:4790\nlang:tr_tr\ntutorialStep:none\nrenderDistance:6\n"));
        assertTrue("the key table after the preset", written.endsWith("mipmapLevels:2\nkey_key.wi_zoom.zoom:key.keyboard.c\n"));
        assertEquals(Collections.singletonList("26.1.2"), asked);
        // A file holding only what this wrote (the game never saved it) is still the app's: written again.
        assertEquals(FirstOptions.Result.WRITTEN, FirstOptions.seed(game, "26.1.2", Collections.singletonList(jar), true,
                keyLines, PhoneGraphics.keys("26.1.2"), preset));
        // Once the game saved it, the preset is never even asked for.
        AtomicFiles.write(new File(game, FirstOptions.FILE_NAME), (written + "soundCategory_master:1.0\n").getBytes(StandardCharsets.UTF_8));
        asked.clear();
        FirstOptions.Preset never = minecraft -> {
            throw new AssertionError("asked for an existing file");
        };
        assertEquals(FirstOptions.Result.EXISTS, FirstOptions.seed(game, "26.1.2", Collections.singletonList(jar), true,
                keyLines, PhoneGraphics.keys("26.1.2"), never));
        assertTrue(read(game).endsWith("soundCategory_master:1.0\n"));
    }
}
