package gg.projects.mobile.content;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * M053: the phone's version picker: the desktop's state split (sync.rs {@code version_switch_splits_the_state}), the
 * per-version sets on disk (sets.rs {@code switching_parks_starts_and_restores_sets},
 * {@code an_interrupted_switch_is_finished_on_resume}), the offered versions and the pack a first visit follows.
 */
public class VersionSwitchTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DOC = "\"path\": \"config/x.json\", \"sha256\": \"" + ModelTest.repeat("0", 64) + "\", \"size\": 1";

    private static String pack(String id, String family, String minecraft, boolean isDefault) {
        return "{\"id\": \"" + id + "\", \"name\": \"Projects\", \"latest\": \"1.0.0\","
                + " \"manifest\": {\"path\": \"packs/" + id + "/1.0.0.json\", \"sha256\": \"" + ModelTest.repeat("1", 64) + "\", \"size\": 10}"
                + ", \"family\": \"" + family + "\", \"minecraft\": \"" + minecraft + "\"" + (isDefault ? ", \"default\": true" : "") + "}";
    }

    private static RootIndex index(String... packs) throws ContentException {
        String json = "{\"schema\": 1, \"sequence\": 1, \"generatedAt\": \"2026-10-15T12:00:00Z\", \"config\": {" + DOC + "},"
                + " \"news\": {" + DOC + "}, \"packs\": [" + String.join(",", packs) + "]}";
        return RootIndex.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static RootIndex family() throws ContentException {
        return index(pack("projects", "projects", "1.21.1", true), pack("projects-1.21.4", "projects", "1.21.4", false),
                pack("projects-snap", "projects", "25w14a", false));
    }

    private static PackState installed() {
        PackState state = new PackState();
        state.packId = "projects";
        state.versionId = "2.3.0";
        state.family = "projects";
        state.minecraft = "1.21.1";
        state.files.put("mods/sodium.jar", new PackState.InstalledFile("aa", "overwrite", "sodium"));
        state.files.put("config/sodium.json", new PackState.InstalledFile("bb", "keep"));
        state.files.put("resourcepacks/projects.zip", new PackState.InstalledFile("cc", "overwrite"));
        state.items.put("sodium", true);
        state.items.put("minimap", false);
        state.seenItems.add("sodium");
        state.groups.put("hud", true);
        state.manual.put("mods/extra.jar", PackState.MANUAL_REMOVED);
        return state;
    }

    private static void write(File dir, String rel, String content) throws IOException {
        File file = new File(dir, rel);
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File dir, String rel) throws IOException {
        return new String(Files.readAllBytes(new File(dir, rel).toPath()), StandardCharsets.UTF_8);
    }

    private static String[] names(File dir) {
        String[] names = dir.list();
        if (names == null) return new String[0];
        Arrays.sort(names);
        return names;
    }

    @Test
    public void theStateSplitsAsOnTheDesktop() {
        PackState.VersionSwitch first = installed().switchVersion("1.21.1", "1.21.4", null);
        assertEquals("1.21.1", first.parked.minecraft);
        assertEquals("projects", first.parked.packId);
        assertEquals(Arrays.asList("config/sodium.json", "mods/sodium.jar"), new ArrayList<>(first.parked.files.keySet()));
        assertEquals(PackState.MANUAL_REMOVED, first.parked.manual.get("mods/extra.jar"));
        // First visit: no pack yet, config/ and shared records, the choices; manual changes stay with the parked set.
        assertEquals("", first.active.packId);
        assertEquals("", first.active.versionId);
        assertEquals("projects", first.active.family);
        assertEquals("1.21.4", first.active.minecraft);
        assertEquals(Arrays.asList("config/sodium.json", "resourcepacks/projects.zip"), new ArrayList<>(first.active.files.keySet()));
        assertEquals(Boolean.TRUE, first.active.items.get("sodium"));
        assertEquals(Boolean.FALSE, first.active.items.get("minimap"));
        assertTrue(first.active.seenItems.contains("sodium"));
        assertTrue(first.active.manual.isEmpty());

        // Returning: the target's own records and choices, this set's shared records.
        PackState back = first.parked;
        PackState now = first.active.copy();
        now.packId = "projects-1.21.4";
        now.items.put("minimap", true);
        now.files.put("mods/minimap.jar", new PackState.InstalledFile("dd", "overwrite", "minimap"));
        PackState.VersionSwitch again = now.switchVersion("1.21.4", "1.21.1", back);
        assertEquals("projects", again.active.packId);
        assertEquals("2.3.0", again.active.versionId);
        assertEquals("1.21.1", again.active.minecraft);
        assertEquals(Boolean.FALSE, again.active.items.get("minimap"));
        assertEquals(Arrays.asList("config/sodium.json", "mods/sodium.jar", "resourcepacks/projects.zip"),
                new ArrayList<>(again.active.files.keySet()));
        assertEquals(Arrays.asList("config/sodium.json", "mods/minimap.jar"), new ArrayList<>(again.parked.files.keySet()));
    }

    @Test
    public void switchingParksStartsAndRestoresSets() throws IOException {
        File game = tmp.newFolder("game");
        write(game, "mods/sodium.jar", "sodium-1.21.1");
        write(game, "mods/own.jar", "mine");
        write(game, "config/sodium.json", "{\"a\":1}");
        write(game, "options.txt", "fov:90");
        installed().save(game);
        VersionSets sets = new VersionSets(game);

        VersionSets.Journal first = sets.switchTo("1.21.1", "1.21.4", "projects-1.21.4", 1_000_000L);
        assertTrue(first.firstVisit);
        // The 1.21.1 set is parked whole; mods/ starts empty, config/ is a copy, shared files stay.
        File parked = new File(game, ".projects/versions/1.21.1");
        assertEquals("sodium-1.21.1", read(parked, "mods/sodium.jar"));
        assertEquals("mine", read(parked, "mods/own.jar"));
        assertArrayEquals(new String[0], names(new File(game, "mods")));
        assertEquals("{\"a\":1}", read(game, "config/sodium.json"));
        assertEquals("fov:90", read(game, "options.txt"));
        PackState active = PackState.load(game);
        assertNotNull(active);
        assertEquals("projects-1.21.4", active.packId);
        assertEquals("1.21.4", active.minecraft);
        assertFalse(active.files.containsKey("mods/sodium.jar"));
        assertNull(sets.pending());
        List<VersionSets.Parked> list = sets.parked();
        assertEquals(1, list.size());
        assertEquals("1.21.1", list.get(0).minecraft);
        assertEquals("projects", list.get(0).packId);
        assertTrue(list.get(0).sizeBytes > 0);

        // The new version's mods arrive (the sync), then the player goes back: the old set returns as it was.
        write(game, "mods/sodium-new.jar", "sodium-1.21.4");
        write(game, "config/sodium.json", "{\"a\":2}");
        VersionSets.Journal back = sets.switchTo("1.21.4", "1.21.1", "projects", 2_000_000L);
        assertFalse(back.firstVisit);
        assertArrayEquals(new String[]{"own.jar", "sodium.jar"}, names(new File(game, "mods")));
        assertEquals("{\"a\":1}", read(game, "config/sodium.json"));
        assertEquals("sodium-1.21.4", read(new File(game, ".projects/versions/1.21.4"), "mods/sodium-new.jar"));
        PackState restored = PackState.load(game);
        assertNotNull(restored);
        assertEquals("projects", restored.packId);
        assertEquals("2.3.0", restored.versionId);
        assertTrue(restored.files.containsKey("mods/sodium.jar"));
        // The returned set's folder is gone; only 1.21.4 is parked now.
        assertFalse(new File(game, ".projects/versions/1.21.1").exists());
        assertEquals("1.21.4", sets.parked().get(0).minecraft);
    }

    @Test
    public void anInterruptedSwitchIsFinished() throws IOException {
        File game = tmp.newFolder("game2");
        write(game, "mods/sodium.jar", "x");
        write(game, "config/a.json", "{}");
        installed().save(game);
        VersionSets sets = new VersionSets(game);
        // A journal left at the restore step: the park step ran, nothing after it.
        PackState[] split = VersionSets.split(PackState.load(game), "1.21.1", "1.21.4", false, null, "projects-1.21.4");
        File parked = new File(game, ".projects/versions/1.21.1");
        assertTrue(parked.mkdirs());
        assertTrue(new File(game, "mods").renameTo(new File(parked, "mods")));
        assertTrue(new File(game, "config").renameTo(new File(parked, "config")));
        VersionSets.Journal journal = new VersionSets.Journal("1.21.1", "1.21.4", VersionSets.Step.RESTORE, true,
                split[0], split[1], "projects", "projects-1.21.4", 5_000L);
        Files.write(new File(game, ".projects/" + VersionSets.JOURNAL).toPath(), journal.toJson().toString().getBytes(StandardCharsets.UTF_8));

        assertNotNull(sets.pending());
        assertNotNull(sets.resume());
        assertNull(sets.pending());
        assertEquals("{}", read(game, "config/a.json"));
        assertTrue(new File(game, "mods").isDirectory());
        assertEquals("1.21.4", PackState.load(game).minecraft);
        assertEquals("x", read(parked, "mods/sodium.jar"));
    }

    @Test
    public void anUnreadableJournalIsSetAside() throws IOException {
        File game = tmp.newFolder("game3");
        write(game, ".projects/" + VersionSets.JOURNAL, "{not json");
        VersionSets sets = new VersionSets(game);
        assertNull(sets.pending());
        assertFalse(new File(game, ".projects/" + VersionSets.JOURNAL).exists());
    }

    @Test
    public void folderNamesAreSafe() {
        assertEquals("1.21.4", VersionSets.folderName("1.21.4"));
        assertEquals("1.21_4_pre", VersionSets.folderName("1.21 4/pre"));
        assertEquals("_2e2e", VersionSets.folderName(".."));
    }

    @Test
    public void theOfferedVersions() throws ContentException {
        RootIndex index = family();
        List<VersionSets.Parked> none = Collections.emptyList();
        List<PackVersions.Option> options = PackVersions.options(index, installed(), "1.21.4", none);
        // The snapshot member is left out (neither recommended nor current), newest first.
        assertEquals(2, options.size());
        assertEquals("1.21.4", options.get(0).minecraft);
        assertTrue(options.get(0).recommended);
        assertTrue(options.get(0).available);
        assertEquals("projects-1.21.4", options.get(0).packId);
        assertEquals("1.21.1", options.get(1).minecraft);
        assertTrue(options.get(1).current);
        assertFalse(options.get(1).available);
        // Without a published recommendation the family's default member is recommended.
        assertTrue(PackVersions.options(index, installed(), "1.20.6", none).get(1).recommended);
    }

    @Test
    public void aDroppedCurrentVersionAndParkedSetsAreListed() throws ContentException, IOException {
        RootIndex index = family();
        PackState state = installed();
        state.packId = "projects-old";
        state.minecraft = "1.20.6";
        File game = tmp.newFolder("game4");
        write(game, ".projects/versions/1.19.2/set.json", "{\"minecraft\":\"1.19.2\",\"sizeBytes\":5}");
        List<PackVersions.Option> options = PackVersions.options(index, state, null, new VersionSets(game).parked());
        assertEquals(Arrays.asList("1.21.4", "1.21.1", "1.20.6", "1.19.2"), Arrays.asList(
                options.get(0).minecraft, options.get(1).minecraft, options.get(2).minecraft, options.get(3).minecraft));
        assertTrue(options.get(2).current);
        assertFalse(options.get(2).available);
        assertTrue(options.get(3).hasSet);
        assertFalse(options.get(3).available);
        assertTrue(PackVersions.options(index, null, null, Collections.<VersionSets.Parked>emptyList()).isEmpty());
    }

    @Test
    public void aFirstVisitFollowsTheTargetMember() throws ContentException {
        RootIndex index = family();
        PackState desktopFirstVisit = new PackState();
        desktopFirstVisit.family = "projects";
        desktopFirstVisit.minecraft = "1.21.4";
        PackChoice choice = PackChoice.choose(index, "projects", desktopFirstVisit);
        assertEquals(PackChoice.Kind.MEMBER, choice.kind);
        assertEquals("projects-1.21.4", choice.pack.id);
        PackState phone = installed().switchVersion("1.21.1", "1.21.4", null).active;
        phone.packId = "projects-1.21.4";
        assertEquals("projects-1.21.4", PackChoice.choose(index, "projects", phone).pack.id);
    }
}
