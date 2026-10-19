package gg.projects.mobile.controls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import gg.projects.mobile.content.AtomicFiles;

/** The mods a game loaded, from its start log (M16, the MODLAR keys). */
public class LoadedModsTest {
    /** The native logger's buffer (jni/stdio_is.c reads at most 2049 bytes at a time). */
    private static final int READ = 2049;

    /** The start of a real Projects pack start on the emulator (Fabric Loader 0.19.5, "Loading 122 mods"). */
    private static String fabricStart() throws IOException {
        String log = new String(AtomicFiles.read(new File("src/test/resources/controls/fabric-start.log"), 1 << 20), StandardCharsets.UTF_8);
        return log.replace("\r\n", "\n");
    }

    /**
     * The log as its writers wrote it: a log event (its continuation lines start with a tab or a
     * space, Fabric's list among them) or one of the launcher's lines per write.
     */
    private static List<String> writes(String log) {
        List<String> writes = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : log.split("\n")) {
            boolean continues = !line.isEmpty() && (line.charAt(0) == '\t' || line.charAt(0) == ' ');
            if (!continues && current.length() > 0) {
                writes.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) writes.add(current.toString());
        return writes;
    }

    /**
     * What the app's listener gets: a few writes reach the pipe, the logger reads at most 2049 bytes
     * of it at a time and drops the newline a read ends with.
     */
    private static LoadedMods feedAsTheLoggerDoes(List<String> writes, long seed) {
        LoadedMods mods = new LoadedMods();
        Random random = new Random(seed);
        StringBuilder pipe = new StringBuilder();
        int next = 0;
        while (next < writes.size() || pipe.length() > 0) {
            int arriving = random.nextInt(3);
            for (int i = 0; i < arriving && next < writes.size(); i++) pipe.append(writes.get(next++));
            if (pipe.length() == 0) continue;
            int size = Math.min(pipe.length(), READ);
            String piece = pipe.substring(0, size);
            pipe.delete(0, size);
            if (piece.endsWith("\n")) piece = piece.substring(0, piece.length() - 1);
            mods.feed(piece);
        }
        return mods;
    }

    private static void assertWholeList(Set<String> ids) {
        assertEquals("as many as Fabric counted", 122, ids.size());
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

    @Test
    public void readsFabricsListOfTheProjectsPackFromTheLoggersPieces() throws IOException {
        List<String> writes = writes(fabricStart());
        for (long seed = 0; seed < 60; seed++) {
            LoadedMods mods = feedAsTheLoggerDoes(writes, seed);
            assertTrue("seed " + seed, mods.finished());
            assertWholeList(mods.mods());
        }
        // Every write read on its own (a logger that keeps up), each without its last newline.
        LoadedMods mods = new LoadedMods();
        for (String write : writes) {
            for (int at = 0; at < write.length(); at += READ) {
                String piece = write.substring(at, Math.min(write.length(), at + READ));
                mods.feed(piece.endsWith("\n") ? piece.substring(0, piece.length() - 1) : piece);
            }
        }
        assertTrue(mods.finished());
        assertWholeList(mods.mods());
    }

    @Test
    public void theListSurvivesAnyCut() throws IOException {
        List<String> writes = writes(fabricStart());
        for (long seed = 0; seed < 30; seed++) {
            Random random = new Random(seed);
            LoadedMods mods = new LoadedMods();
            for (String write : writes) {
                if (!write.contains("Loading 122 mods:")) {
                    mods.feed(write.substring(0, write.length() - 1));
                    continue;
                }
                // The list event cut anywhere, its header too, a newline lost wherever a piece ended on one.
                int at = 0;
                while (at < write.length()) {
                    int size = Math.min(write.length() - at, 1 + random.nextInt(64));
                    String piece = write.substring(at, at + size);
                    at += size;
                    if (piece.endsWith("\n")) piece = piece.substring(0, piece.length() - 1);
                    mods.feed(piece);
                }
            }
            assertTrue("seed " + seed, mods.finished());
            assertWholeList(mods.mods());
        }
    }

    @Test
    public void gluedLinesKeepEveryEntry() {
        LoadedMods mods = new LoadedMods();
        mods.feed("[17:01:24] [main/INFO]: Loading Minecraft 26.1.2 with Fabric Loader 0.19.5");
        mods.feed("[17:01:26] [main/INFO]: Loading 4 mods:\n\t- fabricloader 0.19.5\t   \\-- mixinextras 0.5.5");
        mods.feed("\t- minecraft 26.1.2\r\n\t- wi_zoom 1.7-MC26.1.2");
        assertFalse(mods.finished());
        mods.feed("[17:01:27] [main/INFO]: SpongePowered MIXIN Subsystem Version=0.8.7");
        assertTrue(mods.finished());
        assertEquals(4, mods.mods().size());
        assertTrue(mods.mods().contains("wi_zoom"));
    }

    @Test
    public void onlyTheGamesNextEventEndsTheList() {
        LoadedMods mods = new LoadedMods();
        mods.feed("[17:01:26] [main/INFO]: Loading 4 mods:\n\t- fabricloader 0.19.5\n\t   \\-- mixinextras 0.5.5\n");
        // Other text in between (the launcher's own lines appended to the log) is not the end.
        assertFalse(mods.feed("I/ProjectsCurtain: stage 2\n"));
        assertFalse(mods.feed("\t- minecraft 26.1.2\r\n\t- wi_zoom 1.7-MC26.1.2\n"));
        assertNull(mods.mods());
        assertTrue(mods.feed("[17:01:27] [main/INFO]: SpongePowered MIXIN Subsystem Version=0.8.7\n"));
        assertEquals(4, mods.mods().size());
        // Later lines change nothing.
        assertTrue(mods.feed("\t- gammautils 3.0.0\n"));
        assertFalse(mods.mods().contains("gammautils"));
    }

    @Test
    public void vanillaHasNoMods() {
        LoadedMods mods = new LoadedMods();
        mods.feed("--------- Starting game with Launcher Debug!");
        mods.feed("Info: Selected Minecraft version: 26.1.2");
        assertFalse(mods.finished());
        mods.feed("[20:01:00] [Render thread/INFO]: Environment: Environment[sessionHost=https://sessionserver.mojang.com]");
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
            mods.feed(loader);
            mods.feed("[Render thread/INFO]: Setting user: Player");
            assertTrue(loader, mods.finished());
            assertNull(loader, mods.mods());
        }
    }

    @Test
    public void aListItCannotReadWholeIsUnknownNotEmpty() {
        String next = "[17:01:27] [main/INFO]: next\n";
        LoadedMods spaces = new LoadedMods();
        spaces.feed("[main/INFO]: Loading 2 mods:\n    - fabricloader 0.19.5\n    - minecraft 26.1.2\n" + next);
        assertTrue(spaces.finished());
        assertNull("nothing read: unknown, so no key turns unavailable", spaces.mods());

        LoadedMods foreign = new LoadedMods();
        foreign.feed("[main/INFO]: Loading 1 mod:\n\t- sodium 0.8.12\n" + next);
        assertNull("no Fabric Loader or Minecraft in it: not Fabric's list", foreign.mods());

        LoadedMods lost = new LoadedMods();
        lost.feed("[main/INFO]: Loading 3 mods:\n\t- fabricloader 0.19.5\n\t- minecraft 26.1.2\n" + next);
        assertTrue(lost.finished());
        assertNull("a mod of its count was lost: unknown", lost.mods());
    }

    @Test
    public void aLongLineWithoutAnEndIsBounded() {
        LoadedMods mods = new LoadedMods();
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 200_000; i++) noise.append('x');
        mods.feed(noise.toString());
        mods.feed("[Render thread/INFO]: Setting user: Player");
        assertTrue(mods.finished());
        assertTrue(mods.mods().isEmpty());
    }
}
