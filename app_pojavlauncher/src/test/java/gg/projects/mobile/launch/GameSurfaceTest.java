package gg.projects.mobile.launch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** M18: the surface for a game with Mojang's Vulkan renderer (a player's 26.3 profile drawn sideways). */
public class GameSurfaceTest {
    @Test
    public void versionsWithMojangsVulkanRendererGetASurfaceWithoutRotation() {
        // 26.2 and 26.3 list LWJGL's Vulkan binding; 26.1.2 does not.
        assertTrue(GameSurface.mayUseVulkan(new String[]{"org.lwjgl:lwjgl:3.4.3", "org.lwjgl:lwjgl-vulkan:3.4.3",
                "net.fabricmc:fabric-loader:0.19.5"}));
        assertTrue(GameSurface.mayUseVulkan(new String[]{"org.lwjgl:lwjgl-vulkan:3.4.1:natives-linux"}));
        assertFalse(GameSurface.mayUseVulkan(new String[]{"org.lwjgl:lwjgl:3.4.1", "org.lwjgl:lwjgl-opengl:3.4.1",
                "org.lwjgl:lwjgl-glfw:3.4.1", null}));
        assertFalse(GameSurface.mayUseVulkan(null));
    }
}
