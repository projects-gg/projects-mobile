package gg.projects.mobile.launch;

/**
 * Where a game start is, read from the game's log as it is written (M14, the launch screen's
 * status line). Plain Java, JVM-tested. The log arrives in chunks of up to 2 KB that may hold
 * several lines or part of one, so every check is a substring search; a marker cut in two by a
 * chunk boundary is caught by the next marker of the same stage.
 *
 * <ul>
 * <li>{@link #PREPARING}: the game activity opened (options, the renderer, the runtime);</li>
 * <li>{@link #JAVA}: the launcher started the Java runtime;</li>
 * <li>{@link #MODS}: a mod loader (Fabric, Quilt, Forge, NeoForge) is finding and loading mods;</li>
 * <li>{@link #MINECRAFT}: Minecraft itself is starting (its render thread, the session).</li>
 * </ul>
 * Stages only move forward.
 */
public final class LaunchStages {
    private LaunchStages() {}

    public static final int PREPARING = 0;
    public static final int JAVA = 1;
    public static final int MODS = 2;
    public static final int MINECRAFT = 3;

    private static final String[] JAVA_MARKERS = {
            "Starting game with Launcher Debug",
            "Info: Selected Minecraft version",
            "Info: Launcher version",
    };
    private static final String[] MODS_MARKERS = {
            "with Fabric Loader",
            "Fabric Loader ",
            "Quilt Loader",
            "ModLauncher",
            "Forge Mod Loader",
            "/FML",
            "fml.",
            "NeoForge",
    };
    private static final String[] MINECRAFT_MARKERS = {
            "[Render thread/",
            "[Client thread/",
            "Setting user:",
            "Backend library:",
            "LWJGL Version",
    };
    /** Minecraft is loading its resources: its loading screen is up by now (a fallback signal). */
    private static final String[] RESOURCES_MARKERS = {
            "Reloading ResourceManager",
    };

    /** The stage {@code chunk} shows, never behind {@code current}. */
    public static int advance(int current, String chunk) {
        if (chunk == null || chunk.isEmpty()) return current;
        if (current < MINECRAFT && containsAny(chunk, MINECRAFT_MARKERS)) return MINECRAFT;
        if (current < MODS && containsAny(chunk, MODS_MARKERS)) return MODS;
        if (current < JAVA && containsAny(chunk, JAVA_MARKERS)) return JAVA;
        return current;
    }

    /** True when {@code chunk} says Minecraft is loading resources (its own loading screen shows). */
    public static boolean loadingResources(String chunk) {
        return chunk != null && containsAny(chunk, RESOURCES_MARKERS);
    }

    private static boolean containsAny(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }
}
