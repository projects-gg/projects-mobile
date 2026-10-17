package gg.projects.mobile.accounts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline accounts need no Microsoft account and no copy of the game (the owner's decision of
 * 17 October 2026). Amethyst (2025) reserved adding a local account, downloading the game and
 * installing loaders, modpacks and mods for Microsoft accounts that own Minecraft, through three
 * helpers in Tools and the "needs a Microsoft account that owns Minecraft: Java Edition" message.
 * 0.1.3 removed the helpers and every gate; an upstream merge that brings one back fails here.
 */
public class AccountGatesTest {
    private static final String[] GATES = {
            "hasOnlineProfile(", "hasNoOnlineProfileDialog(", "isLocalProfile(",
            "R.string.feature_requires_java_account", "R.string.no_minecraft_account_found",
    };

    @Test
    public void noOwnershipGateIsLeftInTheSources() throws IOException {
        File sources = new File("src/main/java");
        assertTrue("run from the app module: " + sources.getAbsolutePath(), sources.isDirectory());
        List<String> found = new ArrayList<>();
        scan(sources, found);
        assertTrue("ownership gates are back: " + found, found.isEmpty());
    }

    @Test
    public void offlineAccountsAreNeverDemoAccounts() {
        // Demo mode (its own game folder, --demo) belongs to Microsoft accounts without the game,
        // which the sign-in names "Demo.Player"; an offline name cannot contain the dot.
        for (String name : new String[]{"Demo", "Demo_Player", "demo", "Steve"}) {
            assertTrue(name, OfflineAccounts.isValidName(name));
            MinecraftAccount account = new MinecraftAccount();
            account.username = name;
            assertTrue(name, account.isLocal());
            assertFalse(name, account.isDemo());
        }
        assertFalse(OfflineAccounts.isValidName("Demo.Player"));
        MinecraftAccount demo = new MinecraftAccount();
        demo.username = "Demo.Player";
        assertTrue(demo.isDemo());
        assertFalse(demo.isLocal());
    }

    private static void scan(File dir, List<String> found) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                scan(child, found);
            } else if (child.getName().endsWith(".java")) {
                String text = new String(Files.readAllBytes(child.toPath()), StandardCharsets.UTF_8);
                for (String gate : GATES) {
                    if (text.contains(gate)) found.add(child.getName() + ": " + gate);
                }
            }
        }
    }
}
