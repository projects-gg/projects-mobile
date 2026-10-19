package gg.projects.mobile.controls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Set;

import gg.projects.mobile.content.AtomicFiles;

/** The mods a game loaded, from its start log (M16, the MODLAR keys). */
public class LoadedModsTest {
    /** The start of a real Projects pack start on the emulator (Fabric Loader 0.19.5, 122 mods). */
    private static String fabricStart() throws IOException {
        return new String(AtomicFiles.read(new File("src/test/resources/controls/fabric-start.log"), 1 << 20), StandardCharsets.UTF_8);
    }

    private static LoadedMods feedInChunks(String log, long seed) {
        LoadedMods mods = new LoadedMods();
        Random random = new Random(seed);
        int at = 0;
        while (at < log.length()) {
            // The game's log arrives in chunks of up to 2 KB that cut lines anywhere.
            int size = 1 + random.nextInt(2048);
            mods.feed(log.substring(at, Math.min(log.length(), at + size)));
            at += size;
        }
        return mods;
    }

    @Test
    public void readsFabricsListOfTheProjectsPack() throws IOException {
        String log = fabricStart();
        for (long seed = 0; seed < 20; seed++) {
            LoadedMods mods = feedInChunks(log, seed);
            assertTrue(mods.finished());
            Set<String> ids = mods.mods();
            assertTrue(ids.contains("wi_zoom"));
            assertTrue(ids.contains("gammautils"));
            assertTrue(ids.contains("voicechat"));
            assertTrue(ids.contains("xaerobetterpvp"));
            assertTrue("nested mods count", ids.contains("mixinextras"));
            assertTrue(ids.contains("cloth-basic-math"));
            assertTrue(ids.contains("fabric-api-base"));
            // The warnings before the list are not mods.
            assertFalse(ids.contains("you"));
            assertFalse(ids.contains("mod"));
        }
    }

    @Test
    public void linesCutAtEveryCharacterStillCount() throws IOException {
        String log = fabricStart();
        LoadedMods mods = new LoadedMods();
        for (int i = 0; i < log.length(); i++) mods.feed(log.substring(i, i + 1));
        assertTrue(mods.finished());
        assertTrue(mods.mods().contains("xaerobetterpvp"));
        assertEquals(feedInChunks(log, 7).mods(), mods.mods());
    }

    @Test
    public void theListEndsAtTheFirstLineThatIsNotPartOfIt() {
        LoadedMods mods = new LoadedMods();
        assertFalse(mods.feed("[17:01:24] [main/INFO]: Loading Minecraft 26.1.2 with Fabric Loader 0.19.5\n"));
        assertFalse(mods.feed("[17:01:26] [main/INFO]: Loading 4 mods:\n\t- fabricloader 0.19.5\n\t   \\-- mixinextras 0.5.5\n"));
        assertFalse(mods.feed("\t- minecraft 26.1.2\r\n\t- wi_zoom 1.7-MC26.1.2\n"));
        assertNull(mods.mods());
        assertTrue(mods.feed("[17:01:27] [main/INFO]: SpongePowered MIXIN Subsystem Version=0.8.7\n"));
        assertEquals(4, mods.mods().size());
        assertTrue(mods.mods().contains("wi_zoom"));
        // Later lines change nothing.
        assertTrue(mods.feed("\t- gammautils 3.0.0\n"));
        assertFalse(mods.mods().contains("gammautils"));
    }

    @Test
    public void vanillaHasNoMods() {
        LoadedMods mods = new LoadedMods();
        mods.feed("--------- Starting game with Launcher Debug!\nInfo: Selected Minecraft version: 26.1.2\n");
        assertFalse(mods.finished());
        mods.feed("[20:01:00] [Render thread/INFO]: Environment: Environment[sessionHost=https://sessionserver.mojang.com]\n");
        assertTrue(mods.finished());
        assertTrue(mods.mods().isEmpty());
    }

    @Test
    public void otherLoadersStayUnknown() {
        for (String loader : new String[]{
                "[main/INFO]: ModLauncher running: args [--launchTarget, forgeclient]",
                "[main/INFO]: Loading Minecraft 1.20.1 with Quilt Loader 0.26.0",
                "[main/INFO]: NeoForge mod loading started"}) {
            LoadedMods mods = new LoadedMods();
            mods.feed(loader + "\n");
            mods.feed("[Render thread/INFO]: Setting user: Player\n");
            assertTrue(loader, mods.finished());
            assertNull(loader, mods.mods());
        }
    }

    @Test
    public void aListItCannotReadIsUnknownNotEmpty() {
        LoadedMods mods = new LoadedMods();
        mods.feed("[main/INFO]: Loading 2 mods:\n    - fabricloader 0.19.5\n");
        assertTrue(mods.finished());
        assertNull("nothing read: unknown, so no key turns unavailable", mods.mods());

        LoadedMods partial = new LoadedMods();
        partial.feed("[main/INFO]: Loading 2 mods:\n\t- sodium 0.8.12\n[main/INFO]: next\n");
        assertNull("no Fabric Loader or Minecraft in it: not Fabric's list", partial.mods());
    }

    @Test
    public void aLongLineWithoutAnEndIsBounded() {
        LoadedMods mods = new LoadedMods();
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 100_000; i++) noise.append('x');
        mods.feed(noise.toString());
        mods.feed("\n[Render thread/INFO]: Setting user: Player\n");
        assertTrue(mods.finished());
        assertTrue(mods.mods().isEmpty());
    }
}
