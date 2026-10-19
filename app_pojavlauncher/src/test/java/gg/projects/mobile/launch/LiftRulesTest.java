package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** M14: when the launch screen lifts without a picture (LiftRules). Times in ms of uptime. */
public class LiftRulesTest {
    private static final long START = 1_000;

    private static LiftRules.State state(boolean seesFrames) {
        LiftRules.State s = new LiftRules.State();
        s.startedAt = START;
        s.seesFrames = seesFrames;
        return s;
    }

    @Test
    public void withFramesInSightItWaitsForOneAfterTheResourcesStartLoading() {
        // The first emulator run: resources at 115 s, the title screen (the first frame) 10 s later.
        LiftRules.State s = state(true);
        s.stage = LaunchStages.MINECRAFT;
        s.minecraftAt = 80_000;
        s.resourcesAt = 115_000;
        s.lastLogAt = 120_000;
        assertNull(LiftRules.reason(s, 118_000));
        assertNull(LiftRules.reason(s, 125_000));
    }

    @Test
    public void withoutFramesInSightTheResourcesLiftIt() {
        // SurfaceView before Android 7, or PixelCopy that keeps failing.
        LiftRules.State s = state(false);
        s.stage = LaunchStages.MINECRAFT;
        s.minecraftAt = 80_000;
        s.resourcesAt = 115_000;
        assertNull(LiftRules.reason(s, 117_999));
        assertEquals("resources", LiftRules.reason(s, 118_000));
    }

    @Test
    public void blackFramesLiftItOnlyOnceMinecraftStartedAndTheGameWentQuiet() {
        LiftRules.State s = state(true);
        s.stage = LaunchStages.MODS;
        s.firstFrameAt = 50_000;
        s.lastLogAt = 50_000;
        long later = 50_000 + LiftRules.STEADY_MS;
        assertNull("not while the mods load", LiftRules.reason(s, later));
        s.stage = LaunchStages.MINECRAFT;
        s.minecraftAt = 49_000;
        assertEquals("steady frames", LiftRules.reason(s, later));
        s.lastLogAt = later - 1_000;
        assertNull("the pack's black loading screen: still writing", LiftRules.reason(s, later));
        assertNull(LiftRules.reason(s, s.lastLogAt + LiftRules.STEADY_MS - 1));
        assertEquals("steady frames", LiftRules.reason(s, s.lastLogAt + LiftRules.STEADY_MS));
        s.firstFrameAt = later;
        s.lastLogAt = 0;
        assertNull("frames for too short a time", LiftRules.reason(s, later + LiftRules.STEADY_MS - 1));
    }

    @Test
    public void theMinecraftTimeoutWaitsWhileTheGameKeepsWriting() {
        LiftRules.State s = state(true);
        s.stage = LaunchStages.MINECRAFT;
        s.minecraftAt = 80_000;
        long late = 80_000 + LiftRules.MINECRAFT_FALLBACK_MS + 1;
        s.lastLogAt = late - 89_000;
        assertNull("a slow phone, 89 s without a line", LiftRules.reason(s, late));
        assertEquals("minecraft timeout", LiftRules.reason(s, s.lastLogAt + LiftRules.QUIET_MS + 1));
        s.lastLogAt = 0;
        assertEquals("a game that never wrote after Minecraft started", "minecraft timeout", LiftRules.reason(s, late));
    }

    @Test
    public void withoutFramesInSightTheTimeLimitsDoNotWaitForQuiet() {
        LiftRules.State s = state(false);
        s.stage = LaunchStages.MINECRAFT;
        s.minecraftAt = 80_000;
        long late = 80_000 + LiftRules.MINECRAFT_FALLBACK_MS + 1;
        s.lastLogAt = late;
        assertEquals("minecraft timeout", LiftRules.reason(s, late));

        LiftRules.State before = state(false);
        before.stage = LaunchStages.MODS;
        before.lastLogAt = START + LiftRules.BEFORE_MINECRAFT_LIMIT_MS;
        assertEquals("timeout before Minecraft", LiftRules.reason(before, START + LiftRules.BEFORE_MINECRAFT_LIMIT_MS + 1));
    }

    @Test
    public void aSlowStartThatKeepsWritingIsNotLeftButAQuietOneIs() {
        // The busy emulator (19 October): the mods still loading five minutes in.
        LiftRules.State s = state(true);
        s.stage = LaunchStages.MODS;
        long fiveMinutes = START + LiftRules.BEFORE_MINECRAFT_LIMIT_MS + 1;
        s.lastLogAt = fiveMinutes - 30_000;
        assertNull(LiftRules.reason(s, fiveMinutes));
        assertEquals("timeout before Minecraft", LiftRules.reason(s, s.lastLogAt + LiftRules.QUIET_MS + 1));
        long eightMinutes = START + LiftRules.ABSOLUTE_FALLBACK_MS + 1;
        s.lastLogAt = eightMinutes - 10_000;
        assertNull("still writing at eight minutes", LiftRules.reason(s, eightMinutes));

        s.stage = LaunchStages.MINECRAFT;
        s.minecraftAt = START + 60_000;
        s.lastLogAt = START + LiftRules.ABSOLUTE_FALLBACK_MS;
        assertNull("still writing", LiftRules.reason(s, START + LiftRules.ABSOLUTE_FALLBACK_MS + 1));
        assertEquals("minecraft timeout", LiftRules.reason(s, s.lastLogAt + LiftRules.QUIET_MS + 1));
    }

    @Test
    public void nothingOutlastsTheHardLimit() {
        LiftRules.State s = state(true);
        s.stage = LaunchStages.MINECRAFT;
        s.minecraftAt = START + 60_000;
        s.lastLogAt = START + LiftRules.HARD_LIMIT_MS;
        assertNull(LiftRules.reason(s, START + LiftRules.HARD_LIMIT_MS));
        assertEquals("hard timeout", LiftRules.reason(s, START + LiftRules.HARD_LIMIT_MS + 1));
    }

    @Test
    public void nothingLiftsItEarly() {
        LiftRules.State s = state(true);
        assertNull(LiftRules.reason(s, START));
        assertNull(LiftRules.reason(s, 200_000));
        assertNull("a quiet start, but young", LiftRules.reason(s, START + LiftRules.QUIET_MS + 1));
    }
}
