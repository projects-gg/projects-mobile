package gg.projects.mobile.launch;

import androidx.annotation.Nullable;

/**
 * M18: which surface the game draws on. Minecraft 26.2 and later carry Mojang's own Vulkan renderer
 * (LWJGL's Vulkan binding among their libraries) and use it when their OpenGL renderer cannot start on
 * the phone or the player picks it. Its swapchain takes the window's rotation hint as its transform, and
 * a SurfaceView passes the display's rotation, so a landscape game drew sideways (a player's 26.3 Fabric
 * profile: the title screen turned 90 degrees under the upright touch keys; 26.1.2 had no Vulkan, so the
 * same phone was fine). A TextureView's surface has no rotation, which is why upstream keeps Kopper Zink,
 * the other Vulkan path, on it. Plain Java, JVM-tested.
 */
public final class GameSurface {
    private GameSurface() {}

    private static final String VULKAN_LIBRARY = "org.lwjgl:lwjgl-vulkan:";

    /** True when a version's libraries (Maven names, its parents' too) include LWJGL's Vulkan binding. */
    public static boolean mayUseVulkan(@Nullable String[] libraryNames) {
        if (libraryNames == null) return false;
        for (String name : libraryNames) {
            if (name != null && name.startsWith(VULKAN_LIBRARY)) return true;
        }
        return false;
    }
}
