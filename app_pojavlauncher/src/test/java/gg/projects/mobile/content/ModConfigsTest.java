package gg.projects.mobile.content;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * M054 (LAUNCH-SPEED on the phone): the desktop's first settings of two mods (pl-mc mod_configs.rs and its tests),
 * written only where no file is, and the plan's placed mods (sync.rs {@code placed_mods}).
 */
public class ModConfigsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SODIUM_EXTRA_INFO = "696WgnvT";
    private static final String BETTER_PVP = "better-pvp";

    @Test
    public void theTableHoldsTheDesktopsBytes() {
        List<ModConfigs.Entry> sodium = ModConfigs.configsFor(Collections.singletonList(SODIUM_EXTRA_INFO));
        assertEquals(1, sodium.size());
        assertEquals("config/sodiumextrainformation/telemetry-config.json", sodium.get(0).path);
        assertEquals("{\n  \"enable_telemetry\": false\n}\n", new String(sodium.get(0).contents(), StandardCharsets.UTF_8));
        List<ModConfigs.Entry> xaero = ModConfigs.configsFor(Collections.singletonList(BETTER_PVP));
        assertEquals(1, xaero.size());
        assertEquals("config/xaero/lib/common.cfg", xaero.get(0).path);
        assertEquals("allow_internet_access = false\n", new String(xaero.get(0).contents(), StandardCharsets.UTF_8));
        assertTrue(ModConfigs.configsFor(Collections.singletonList("sodium")).isEmpty());
        for (ModConfigs.Entry entry : ModConfigs.table()) assertTrue(entry.path, ModConfigs.settingsPath(entry.path));
    }

    @Test
    public void onlyPlainPathsUnderConfig() {
        assertTrue(ModConfigs.settingsPath("config/a/b.json"));
        assertFalse(ModConfigs.settingsPath("config/"));
        assertFalse(ModConfigs.settingsPath("config/../options.txt"));
        assertFalse(ModConfigs.settingsPath("config/./a"));
        assertFalse(ModConfigs.settingsPath("config//a"));
        assertFalse(ModConfigs.settingsPath("mods/a.jar"));
        assertFalse(ModConfigs.settingsPath("config\\a"));
        assertFalse(ModConfigs.settingsPath("C:/config/a"));
    }

    @Test
    public void writesAMissingFileAndNeverTouchesOneThatIsThere() throws Exception {
        File game = tmp.newFolder("game");
        List<String> written = ModConfigs.seed(game, Arrays.asList("sodium", SODIUM_EXTRA_INFO, BETTER_PVP));
        assertEquals(Arrays.asList("config/sodiumextrainformation/telemetry-config.json", "config/xaero/lib/common.cfg"), written);
        File xaero = new File(game, "config/xaero/lib/common.cfg");
        assertEquals("allow_internet_access = false\n", new String(Files.readAllBytes(xaero.toPath()), StandardCharsets.UTF_8));

        // The player's (or the mod's own) file wins, every time after.
        byte[] own = "allow_internet_access = true\nother = 1\n".getBytes(StandardCharsets.UTF_8);
        Files.write(xaero.toPath(), own);
        File telemetry = new File(game, "config/sodiumextrainformation/telemetry-config.json");
        assertTrue(telemetry.delete());
        assertTrue(telemetry.mkdirs());
        assertTrue(ModConfigs.seed(game, Arrays.asList(SODIUM_EXTRA_INFO, BETTER_PVP)).isEmpty());
        assertArrayEquals(own, Files.readAllBytes(xaero.toPath()));
        assertTrue(telemetry.isDirectory());
        assertTrue(ModConfigs.seed(game, Collections.<String>emptyList()).isEmpty());
    }

    @Test
    public void placedModsAreNewFilesAndRenamedBackCopiesNotUpdates() {
        PackManifest manifest = new PackManifest();
        manifest.projects = TestPacks.ext();
        PackManifest.PackItem sodium = TestPacks.item("sodium-extra-info", "mods/sei.jar", true);
        sodium.modrinth = new PackManifest.ModrinthRef();
        sodium.modrinth.projectId = SODIUM_EXTRA_INFO;
        PackManifest.PackItem pvp = TestPacks.item(BETTER_PVP, "mods/better-pvp.jar", true);
        PackManifest.PackItem zoom = TestPacks.item("zoom", "mods/zoom.jar", true);
        manifest.projects.items = new ArrayList<>(Arrays.asList(sodium, pvp, zoom));

        PackSyncPlanner.Plan plan = new PackSyncPlanner.Plan();
        plan.downloads.add(new PackSyncPlanner.Download("mods/sei.jar", Collections.<String>emptyList(), "a", null, 1,
                PackSyncPlanner.Action.ADD, FileCategory.MOD, "sodium-extra-info"));
        plan.downloads.add(new PackSyncPlanner.Download("mods/zoom.jar", Collections.<String>emptyList(), "b", null, 1,
                PackSyncPlanner.Action.UPDATE, FileCategory.MOD, "zoom"));
        plan.downloads.add(new PackSyncPlanner.Download("config/x.json", Collections.<String>emptyList(), "c", null, 1,
                PackSyncPlanner.Action.ADD, FileCategory.CONFIG, "zoom"));
        plan.downloads.add(new PackSyncPlanner.Download("mods/gone.jar", Collections.<String>emptyList(), "d", null, 1,
                PackSyncPlanner.Action.ADD, FileCategory.MOD, "not-in-the-pack"));
        plan.restores.add(new PackSyncPlanner.Restore("mods/better-pvp.jar", 1, FileCategory.MOD, BETTER_PVP));
        plan.restores.add(new PackSyncPlanner.Restore("mods/sei.jar", 1, FileCategory.MOD, "sodium-extra-info"));
        assertEquals(Arrays.asList(SODIUM_EXTRA_INFO, BETTER_PVP), plan.placedMods(manifest));
        assertTrue(plan.placedMods(null).isEmpty());
    }
}
