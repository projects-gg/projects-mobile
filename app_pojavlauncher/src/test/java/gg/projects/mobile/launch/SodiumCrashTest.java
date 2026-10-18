package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Telling a start Sodium broke from other failed starts. */
public class SodiumCrashTest {
    /** A normal start: harmless exceptions (no network, natives for another platform), Sodium loading. */
    private static final String NORMAL = String.join("\n",
            "[15:59:02] [main/INFO]: Loading Minecraft 26.1.2 with Fabric Loader 0.19.5",
            "[15:59:03] [main/INFO]: Loading 47 mods:",
            "\t- sodium 0.8.12+mc26.1.2",
            "\t- iris 1.10.9+mc26.1.1",
            "[15:59:05] [main/WARN]: Error loading class: net/irisshaders/iris/layer/OuterWrappedRenderType (java.lang.ClassNotFoundException)",
            "com.mojang.authlib.exceptions.MinecraftClientException: Failed to read from https://sessionserver.mojang.com/session",
            "\tat knot//com.mojang.authlib.exceptions.MinecraftClientException.toAuthenticationException(MinecraftClientException.java:29)",
            "Caused by: java.net.UnknownHostException: sessionserver.mojang.com",
            "\tat java.base/java.net.InetAddress.getAllByName0(InetAddress.java:1)",
            "[15:59:27] [Render thread/INFO]: OpenGL Vendor: Google (NVIDIA Corporation)",
            "[15:59:32] [Render thread/INFO]: Backend library: LWJGL version 3.4.1-snapshot",
            "de.maxhenkel.opus4j.UnknownPlatformException: Could not load libopus4j natives for linux-x64",
            "\tat knot//de.maxhenkel.opus4j.Opus.load(Opus.java:40)");

    @Test
    public void nothingWithoutSodium() {
        String crash = NORMAL + "\n" + "Mixin [sodium-common.mixins.json:core.MinecraftMixin] from mod sodium failed injection check";
        assertEquals(SodiumCrash.Verdict.NONE, SodiumCrash.diagnose(crash, null, null, false));
    }

    @Test
    public void crashReportsNamingTheFamily() {
        String suspected = "---- Minecraft Crash Report ----\n// Oops.\n\nDescription: Rendering overlay\n\n"
                + "java.lang.RuntimeException: Shader compilation failed\n"
                + "\tat knot//com.example.SomeMod.render(SomeMod.java:10)\n"
                + "Suspected Mod: Sodium (sodium), Version: 0.8.12+mc26.1.2\n\n"
                + "-- System Details --\nFabric Mods:\n\tsodium: Sodium 0.8.12\n";
        assertEquals(SodiumCrash.Verdict.SODIUM, SodiumCrash.diagnose(NORMAL, suspected, null, true));

        String frames = "---- Minecraft Crash Report ----\nDescription: Unexpected error\n\n"
                + "java.lang.IllegalStateException: Failed to create buffer\n"
                + "\tat knot//net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice.createBuffer(GLRenderDevice.java:80)\n"
                + "\n-- System Details --\n";
        assertEquals(SodiumCrash.Verdict.SODIUM, SodiumCrash.diagnose(NORMAL, frames, null, true));

        // The same report printed into the log counts as well.
        assertEquals(SodiumCrash.Verdict.SODIUM, SodiumCrash.diagnose(NORMAL + "\n" + frames, null, null, true));
    }

    @Test
    public void crashReportsAboutAnotherModAreNotSodiums() {
        // The system details list every mod, Sodium included: they do not count.
        String other = "---- Minecraft Crash Report ----\nDescription: Ticking entity\n\n"
                + "java.lang.NullPointerException: Cannot invoke \"Object.toString()\"\n"
                + "\tat knot//dev.example.zoom.ZoomMod.tick(ZoomMod.java:42)\n"
                + "Suspected Mod: WI Zoom (wi_zoom), Version: 1.7\n\n"
                + "-- System Details --\nFabric Mods:\n\tsodium: Sodium 0.8.12\n\tiris: Iris 1.10.9\n"
                + "\tat net.caffeinemc.mods.sodium.Whatever\n";
        assertEquals(SodiumCrash.Verdict.NONE, SodiumCrash.diagnose(NORMAL, other, null, true));
    }

    @Test
    public void mixinAndLoaderFailures() {
        String mixin = NORMAL + "\n[main/ERROR]: Mixin apply for mod iris failed mixins.iris.json:MixinGameRenderer from mod iris"
                + " -> net.minecraft.client.renderer.GameRenderer: InvalidInjectionException\n"
                + "org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure\n"
                + "\tat org.spongepowered.asm.mixin.injection.struct.InjectionInfo.validateTargets(InjectionInfo.java:1)\n"
                + "\tat org.spongepowered.asm.mixin.transformer.MixinTargetContext.applyInjections(MixinTargetContext.java:1)\n";
        assertEquals(SodiumCrash.Verdict.SODIUM, SodiumCrash.diagnose(mixin, null, null, true));

        String uncaught = NORMAL + "\nException in thread \"Render thread\" java.lang.UnsupportedOperationException\n"
                + "\tat knot//me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod.onInitializeClient(SodiumExtraClientMod.java:20)\n"
                + "\tat net.fabricmc.loader.impl.FabricLoaderImpl.invokeEntrypoints(FabricLoaderImpl.java:1)\n";
        assertEquals(SodiumCrash.Verdict.SODIUM, SodiumCrash.diagnose(uncaught, null, null, true));

        // The last error is another mod's: Sodium's earlier warnings do not count.
        String elsewhere = uncaught + "[16:00:01] [Render thread/INFO]: Reloading ResourceManager: vanilla\n"
                + "java.lang.IllegalArgumentException: bad recipe\n"
                + "\tat knot//dev.example.recipes.Loader.load(Loader.java:5)\n";
        assertEquals(SodiumCrash.Verdict.NONE, SodiumCrash.diagnose(elsewhere, null, null, true));
    }

    @Test
    public void sodiumsOwnChecks() {
        assertEquals(SodiumCrash.Verdict.SODIUM, SodiumCrash.diagnose(NORMAL
                + "\nThe game failed to start because the currently active LWJGL version is not compatible.", null, null, true));
        assertEquals(SodiumCrash.Verdict.SODIUM, SodiumCrash.diagnose(NORMAL
                + "\njava.lang.RuntimeException: It appears that you are using PojavLauncher, which is not supported when using Sodium.",
                null, null, true));
        assertEquals(SodiumCrash.Verdict.SODIUM, SodiumCrash.diagnose(NORMAL + "\nSodium Renderer - Unsupported Driver", null, null, true));
    }

    @Test
    public void nativeCrashes() {
        String header = "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n"
                + "#  SIGSEGV (0xb) at pc=0x0000007a1b2c3d4e, pid=4242, tid=4290\n#\n"
                + "# Problematic frame:\n# C  [libmobileglues.so+0x5a1c4]  glMultiDrawElementsBaseVertex+0x44\n#\n";
        // Inside the renderer while Sodium was installed: worth a start without it.
        assertEquals(SodiumCrash.Verdict.RENDERER_WITH_SODIUM, SodiumCrash.diagnose(NORMAL + "\n" + header, null, null, true));

        // The report names Sodium's code among the Java frames: sure.
        String report = header + "\nJava frames: (J=compiled Java code, j=interpreted, Vv=VM code)\n"
                + "j  org.lwjgl.opengl.GL32C.nglMultiDrawElementsBaseVertex(IJIJIJ)V+0\n"
                + "j  net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice$ImmediateDrawCommandList.multiDrawElementsBaseVertex()V+30\n"
                + "\n---------------  P R O C E S S  ---------------\n";
        assertEquals(SodiumCrash.Verdict.SODIUM, SodiumCrash.diagnose(NORMAL, null, report, true));

        // A crash outside the graphics code is not blamed on Sodium.
        String elsewhere = "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n"
                + "# Problematic frame:\n# C  [libopenal.so+0x1234]  alSourcePlay+0x10\n#\n";
        assertEquals(SodiumCrash.Verdict.NONE, SodiumCrash.diagnose(NORMAL + "\n" + elsewhere, null, null, true));
    }

    @Test
    public void aNormalLogAloneIsNotACrash() {
        assertEquals(SodiumCrash.Verdict.NONE, SodiumCrash.diagnose(NORMAL, null, null, true));
        assertEquals(SodiumCrash.Verdict.NONE, SodiumCrash.diagnose(null, null, null, true));
        assertEquals(SodiumCrash.Verdict.NONE, SodiumCrash.diagnose("", null, null, true));
    }

    /** The end of a real 26.1.2 start with the Projects pack (M13, session 1), up to its sounds. */
    private static final String STARTED = NORMAL + "\n" + String.join("\n",
            "[16:14:21] [Render thread/INFO]: Reloading ResourceManager: vanilla, appleskin, badoptimizations",
            "[16:14:23] [Render thread/INFO]: Created: 1024x1024x0 minecraft:textures/atlas/gui.png-atlas",
            "[16:14:29] [Render thread/INFO]: OpenAL initialized on device OpenSL",
            "[16:14:29] [Render thread/INFO]: Sound engine started",
            "[16:14:30] [Render thread/INFO]: Created: 2048x2048x4 minecraft:textures/atlas/blocks.png-atlas");

    private static final String DRIVER_CRASH = "\n#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n"
            + "# Problematic frame:\n# C  [libGLESv2_adreno.so+0x3f10c]\n#\n";

    @Test
    public void aCrashDuringTheStartCounts() {
        // Before the first resource load reached its sounds: the start the fallback is for.
        String early = NORMAL + "\n[15:59:40] [Render thread/INFO]: Reloading ResourceManager: vanilla" + DRIVER_CRASH;
        assertEquals(true, SodiumCrash.duringStartup(early, false, 3 * 60_000));
        assertEquals(SodiumCrash.Verdict.RENDERER_WITH_SODIUM, SodiumCrash.diagnose(early, null, null, true));
        // The rest of that load (the block atlas comes after the sounds) still belongs to the start.
        assertEquals(true, SodiumCrash.duringStartup(STARTED + "\n[16:14:31] [Render thread/WARN]: x" + DRIVER_CRASH, false, 4 * 60_000));
        // A crash so early that no log was written.
        assertEquals(true, SodiumCrash.duringStartup(null, false, 20_000));
    }

    @Test
    public void aLateCrashDoesNotCount() {
        // REVIEW1 A1: a driver crash long after the menu (here 41 minutes of play by the log's clock).
        String late = STARTED + "\n[16:55:02] [Render thread/INFO]: [CHAT] <Steve> hi" + DRIVER_CRASH;
        assertEquals(false, SodiumCrash.duringStartup(late, false, 9 * 60_000));
        // Past midnight the clock wraps; still long after the sounds came up.
        String overnight = NORMAL + "\n[23:58:00] [Render thread/INFO]: Sound engine started\n[00:20:00] [Render thread/INFO]: x" + DRIVER_CRASH;
        assertEquals(false, SodiumCrash.duringStartup(overnight, false, 10 * 60_000));
        // More than 15 minutes after the launch, a log longer than what was read, or a game that was
        // stopping: it had started, whatever the log shows.
        assertEquals(false, SodiumCrash.duringStartup(NORMAL + DRIVER_CRASH, false, 16 * 60_000));
        assertEquals(false, SodiumCrash.duringStartup(NORMAL + DRIVER_CRASH, true, 60_000));
        assertEquals(false, SodiumCrash.duringStartup(NORMAL + "\n[16:20:00] [Render thread/INFO]: Stopping!" + DRIVER_CRASH, false, 60_000));
        // Sounds that could not start mark the end of the start as well.
        String noSound = NORMAL + "\n[16:14:29] [Render thread/ERROR]: Error starting SoundSystem. Turning off sounds & music\n"
                + "[16:30:00] [Render thread/INFO]: x" + DRIVER_CRASH;
        assertEquals(false, SodiumCrash.duringStartup(noSound, false, 14 * 60_000));
    }
}
