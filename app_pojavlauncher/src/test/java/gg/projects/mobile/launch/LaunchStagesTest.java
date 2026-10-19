package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** M14: the launch screen's status line follows the game's log (LaunchStages). */
public class LaunchStagesTest {
    @Test
    public void aFabricStartFromTheRealLog() {
        int stage = LaunchStages.PREPARING;
        stage = LaunchStages.advance(stage, "Writing MG configs to /data/user/0/gg.projects.mobile.debug/MobileGlues/config.json");
        assertEquals(LaunchStages.PREPARING, stage);
        stage = LaunchStages.advance(stage, "--------- Starting game with Launcher Debug!\nInfo: Launcher version: 0.1.6-debug");
        assertEquals(LaunchStages.JAVA, stage);
        stage = LaunchStages.advance(stage, "D/jrelog  ( 8019): dlopen /data/user/0/gg.projects.mobile.debug/runtimes/External-25/lib/server/libjvm.so success");
        assertEquals(LaunchStages.JAVA, stage);
        stage = LaunchStages.advance(stage, "[16:12:51] [main/INFO]: Loading Minecraft 26.1.2 with Fabric Loader 0.19.5");
        assertEquals(LaunchStages.MODS, stage);
        stage = LaunchStages.advance(stage, "[16:13:04] [main/INFO]: Loading 113 mods:\n\t- betterstats 5.3.0+fabric-26.1");
        assertEquals(LaunchStages.MODS, stage);
        stage = LaunchStages.advance(stage, "[16:14:00] [Render thread/INFO]: Setting user: Oyuncu_M13");
        assertEquals(LaunchStages.MINECRAFT, stage);
        assertTrue(LaunchStages.loadingResources("[16:14:21] [Render thread/INFO]: Reloading ResourceManager: vanilla, appleskin"));
    }

    @Test
    public void vanillaAndOlderLoaders() {
        assertEquals("vanilla goes from Java to Minecraft", LaunchStages.MINECRAFT,
                LaunchStages.advance(LaunchStages.JAVA, "[12:00:01] [Render thread/INFO]: Backend library: LWJGL version 3.3.3"));
        assertEquals("1.12 and older", LaunchStages.MINECRAFT,
                LaunchStages.advance(LaunchStages.JAVA, "[12:00:01] [Client thread/INFO]: Setting user: Steve"));
        assertEquals("Forge and NeoForge", LaunchStages.MODS,
                LaunchStages.advance(LaunchStages.JAVA, "[main/INFO] [cpw.mods.modlauncher.Launcher/MODLAUNCHER]: ModLauncher running: args [--username"));
        assertEquals("Forge 1.12", LaunchStages.MODS,
                LaunchStages.advance(LaunchStages.JAVA, "[main/INFO] [FML]: Forge Mod Loader version 14.23.5.2860 for Minecraft 1.12.2 loading"));
        assertEquals("LaunchWrapper era", LaunchStages.MODS,
                LaunchStages.advance(LaunchStages.JAVA, "[main/INFO] [LaunchWrapper]: Loading tweak class name cpw.mods.fml.common.launcher.FMLTweaker"));
        assertEquals("Quilt", LaunchStages.MODS,
                LaunchStages.advance(LaunchStages.JAVA, "[main/INFO]: Loading Minecraft 1.21.1 with Quilt Loader 0.26.4"));
    }

    @Test
    public void stagesNeverGoBackAndOneChunkMaySkipAhead() {
        assertEquals(LaunchStages.MINECRAFT, LaunchStages.advance(LaunchStages.MINECRAFT, "Loading Minecraft 26.1.2 with Fabric Loader 0.19.5"));
        assertEquals(LaunchStages.MODS, LaunchStages.advance(LaunchStages.MODS, "--------- Starting game with Launcher Debug!"));
        assertEquals("a big chunk holding several lines", LaunchStages.MINECRAFT, LaunchStages.advance(LaunchStages.PREPARING,
                "--------- Starting game with Launcher Debug!\n[main/INFO]: Loading Minecraft 26.1.2 with Fabric Loader 0.19.5\n[Render thread/INFO]: Setting user: X"));
        assertEquals(LaunchStages.JAVA, LaunchStages.advance(LaunchStages.JAVA, ""));
        assertEquals(LaunchStages.JAVA, LaunchStages.advance(LaunchStages.JAVA, null));
        assertFalse(LaunchStages.loadingResources("[Render thread/INFO]: Creating new resource manager!"));
        assertFalse(LaunchStages.loadingResources(null));
    }

    @Test
    public void removeReloadingScreenSaysWhenItShowsTheTitleEarlyAndWhenItIsDone() {
        // 80-trace's log (Projects pack, Remove Reloading Screen 5.2.6).
        assertTrue(LaunchStages.earlyTitle("[17:02:33] [Render thread/INFO]: Reloading ResourceManager: vanilla\n[17:02:34] [Render thread/INFO]: Trying to complete load early..."));
        assertFalse(LaunchStages.earlyTitle("[17:02:33] [Render thread/INFO]: Reloading ResourceManager: vanilla"));
        assertFalse(LaunchStages.earlyTitle(null));
        assertTrue(LaunchStages.loadDone("[17:03:02] [Render thread/INFO]: Overlay is removed!"));
        assertFalse(LaunchStages.loadDone("[17:02:53] [Render thread/INFO]: 29877 cache(s) were cleared!"));
        assertFalse(LaunchStages.loadDone(null));
    }

    @Test
    public void theResourcesStageComesOnlyFromTheLaunchScreenAndStays() {
        // LaunchCurtain shows "Neredeyse hazır" a moment after the resources marker; the log never
        // moves a start there by itself, and nothing in it moves a start back.
        assertTrue(LaunchStages.RESOURCES > LaunchStages.MINECRAFT);
        assertEquals(LaunchStages.MINECRAFT, LaunchStages.advance(LaunchStages.MINECRAFT,
                "[16:14:21] [Render thread/INFO]: Reloading ResourceManager: vanilla, appleskin"));
        assertEquals(LaunchStages.RESOURCES, LaunchStages.advance(LaunchStages.RESOURCES,
                "--------- Starting game with Launcher Debug!\n[Render thread/INFO]: Setting user: X"));
    }
}
