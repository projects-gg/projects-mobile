package gg.projects.mobile.content;

import static gg.projects.mobile.content.TestPacks.file;
import static gg.projects.mobile.content.TestPacks.item;
import static gg.projects.mobile.content.TestPacks.library;
import static gg.projects.mobile.content.TestPacks.select;
import static gg.projects.mobile.content.TestPacks.sha1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The mod center rules (mirror of the desktop's items.rs tests). */
public class ItemResolverTest {
    /** Items with one file each ({@code mods/<id>.jar}) plus options.txt. */
    private static PackManifest manifest(PackManifest.PackItem... items) {
        List<PackManifest.PackFile> files = new ArrayList<>();
        for (PackManifest.PackItem item : items) files.add(file(item.path, item.path));
        files.add(file("options.txt", "options.txt"));
        PackManifest.Projects ext = TestPacks.ext();
        ext.items = new ArrayList<>(Arrays.asList(items));
        return TestPacks.manifest(files, ext);
    }

    /** sodium (on) needs fabric-api (library); iris (off) needs sodium; shaders (off) needs iris. */
    private static PackManifest catalog() {
        return manifest(
                item("sodium", "mods/sodium.jar", true, "fabric-api"),
                item("iris", "mods/iris.jar", false, "sodium"),
                item("shaders", "mods/shaders.jar", false, "iris"),
                item("minimap", "mods/minimap.jar", true),
                library("fabric-api", "mods/fabric-api.jar"));
    }

    private static List<String> enabled(ItemResolver.Resolution resolution) {
        List<String> out = new ArrayList<>();
        for (ItemResolver.ItemStatus status : resolution.items) if (status.effectivelyEnabled) out.add(status.id);
        return out;
    }

    private static PackState stateWith(ItemResolver.Resolution resolution) {
        PackState state = new PackState();
        state.items.putAll(resolution.choices);
        state.seenItems.addAll(resolution.seen);
        return state;
    }

    private static PackState.InstalledFile record(String sha1, String item) {
        return new PackState.InstalledFile(sha1, PackManifest.POLICY_OVERWRITE, item);
    }

    @Test
    public void firstInstallUsesDefaults() {
        PackManifest m = catalog();
        ItemResolver.Resolution first = ItemResolver.resolve(m, null, null);
        assertEquals(Arrays.asList("sodium", "minimap", "fabric-api"), enabled(first));
        Map<String, Boolean> expected = new TreeMap<>();
        expected.put("iris", false);
        expected.put("minimap", true);
        expected.put("shaders", false);
        expected.put("sodium", true);
        assertEquals("defaults are recorded, libraries are not choices", expected, first.choices);
        assertEquals("everything counts as seen", 5, first.seen.size());
        assertTrue(first.newItems().isEmpty());
        ItemResolver.ItemStatus fabric = first.status("fabric-api");
        assertTrue(fabric.library && !fabric.selected && fabric.effectivelyEnabled);
        assertEquals(Collections.singletonList("sodium"), fabric.requiredBy);
        assertFalse(fabric.installed || fabric.updateAvailable);
        assertEquals("mods/fabric-api.jar".length(), fabric.size);

        // Recorded choices survive a changed default.
        PackManifest changed = catalog();
        changed.projects.items.get(3).defaultEnabled = false;
        ItemResolver.Resolution again = ItemResolver.resolve(changed, stateWith(first), null);
        assertTrue(again.isEnabled("minimap"));
        assertEquals(first.choices, again.choices);
    }

    @Test
    public void newItemsStayOffForExistingPlayers() {
        PackState state = stateWith(ItemResolver.resolve(catalog(), null, null));
        PackManifest updated = catalog();
        updated.projects.items.add(item("zoom", "mods/zoom.jar", true));
        updated.files.add(file("mods/zoom.jar", "mods/zoom.jar"));

        ItemResolver.Resolution after = ItemResolver.resolve(updated, state, null);
        ItemResolver.ItemStatus zoom = after.status("zoom");
        assertTrue(zoom.isNew && !zoom.selected && !zoom.effectivelyEnabled);
        assertFalse("no choice is invented", after.choices.containsKey("zoom"));
        assertFalse("a background sync shows nothing", after.seen.contains("zoom"));
        assertEquals(1, after.newItems().size());
        assertTrue(after.isEnabled("sodium") && after.isEnabled("minimap"));

        // Confirming the mod screen (even without changes) marks it seen; it stays off.
        ItemResolver.Resolution confirmed = ItemResolver.resolve(updated, state, new PackSelection());
        assertTrue(confirmed.seen.contains("zoom"));
        ItemResolver.Resolution later = ItemResolver.resolve(updated, stateWith(confirmed), null);
        zoom = later.status("zoom");
        assertTrue(!zoom.isNew && !zoom.effectivelyEnabled);

        // Picking it records the choice.
        ItemResolver.Resolution picked = ItemResolver.resolve(updated, state, select("zoom", true));
        assertTrue(picked.isEnabled("zoom"));
        assertEquals(Boolean.TRUE, picked.choices.get("zoom"));
    }

    @Test
    public void requiresClosureAndLibraries() {
        PackManifest m = catalog();
        PackState state = stateWith(ItemResolver.resolve(m, null, null));

        // Shaders pull in Iris, which pulls in Sodium and the library.
        ItemResolver.Resolution shaders = ItemResolver.resolve(m, state, select("shaders", true));
        assertEquals(Arrays.asList("sodium", "iris", "shaders", "minimap", "fabric-api"), enabled(shaders));
        ItemResolver.ItemStatus iris = shaders.status("iris");
        assertTrue(!iris.selected && iris.effectivelyEnabled);
        assertEquals(Collections.singletonList("shaders"), iris.requiredBy);

        // A deselected item that something enabled needs stays on (and says why).
        ItemResolver.Resolution forced = ItemResolver.resolve(m, state, select("shaders", true, "sodium", false));
        ItemResolver.ItemStatus sodium = forced.status("sodium");
        assertTrue(!sodium.selected && sodium.effectivelyEnabled);
        assertEquals(Collections.singletonList("iris"), sodium.requiredBy);

        // Nothing enabled needs the library any more: it goes too.
        ItemResolver.Resolution off = ItemResolver.resolve(m, state, select("sodium", false));
        assertEquals(Collections.singletonList("minimap"), enabled(off));
        assertTrue(off.status("fabric-api").requiredBy.isEmpty());

        // Libraries and unknown ids are not choices.
        ItemResolver.Resolution ignored = ItemResolver.resolve(m, state, select("fabric-api", false, "unknown", true));
        assertTrue(ignored.isEnabled("fabric-api"));
        assertFalse(ignored.choices.containsKey("fabric-api"));
        assertFalse(ignored.choices.containsKey("unknown"));
    }

    @Test
    public void setsFromBeforeItemsKeepTheirFiles() {
        // The pack installed sodium and iris before it had items; nothing was chosen yet.
        PackState legacy = new PackState();
        for (String path : new String[]{"mods/sodium.jar", "mods/iris.jar"}) legacy.files.put(path, record(sha1(path), null));
        ItemResolver.Resolution resolution = ItemResolver.resolve(catalog(), legacy, null);
        assertEquals("installed files stay, defaults fill the rest",
                Arrays.asList("sodium", "iris", "minimap", "fabric-api"), enabled(resolution));
        assertTrue(resolution.newItems().isEmpty());
        ItemResolver.ItemStatus iris = resolution.status("iris");
        assertTrue(iris.selected && iris.installed && !iris.updateAvailable);
    }

    @Test
    public void renamedFilesKeepTheChoice() {
        PackManifest m = catalog();
        PackState state = stateWith(ItemResolver.resolve(m, null, null));
        // Version 1 installed sodium under another file name.
        state.files.put("mods/sodium-0.5.jar", record(sha1("old"), "sodium"));
        ItemResolver.ItemStatus sodium = ItemResolver.resolve(m, state, null).status("sodium");
        assertTrue(sodium.selected && sodium.installed && sodium.updateAvailable);

        // The same file, same hash (any case): installed and current.
        state.files.clear();
        state.files.put("mods/sodium.jar", record(sha1("mods/sodium.jar").toUpperCase(java.util.Locale.ROOT), "sodium"));
        sodium = ItemResolver.resolve(m, state, null).status("sodium");
        assertTrue(sodium.installed && !sodium.updateAvailable);
    }

    @Test
    public void adoptedChoicesRankBetweenTheSelectionAndTheRecords() {
        PackManifest m = catalog();
        PackState state = stateWith(ItemResolver.resolve(m, null, null));
        Map<String, Boolean> adopted = new TreeMap<>();
        adopted.put("minimap", false);
        adopted.put("iris", true);
        ItemResolver.Resolution background = ItemResolver.resolve(m, state, null, adopted, ItemResolver.MANIFEST_DEFAULTS);
        assertFalse(background.isEnabled("minimap"));
        assertTrue(background.isEnabled("iris") && background.isEnabled("sodium"));
        assertEquals(Boolean.FALSE, background.choices.get("minimap"));
        assertEquals("adopting shows nothing new", state.seenItems, background.seen);
        assertTrue(background.newItems().isEmpty());

        // An explicit selection wins over what the disk shows.
        ItemResolver.Resolution explicit = ItemResolver.resolve(m, state, select("minimap", true), adopted, ItemResolver.MANIFEST_DEFAULTS);
        assertTrue(explicit.isEnabled("minimap"));
        assertTrue("unlisted items still follow the disk", explicit.isEnabled("iris"));
    }

    @Test
    public void manifestsWithoutItemsResolveToNothing() {
        ItemResolver.Resolution resolution = ItemResolver.resolve(manifest(), null, new PackSelection());
        assertTrue(resolution.items.isEmpty());
        assertTrue(resolution.choices.isEmpty() && resolution.seen.isEmpty());
    }

    /** Real ids: Sodium (AANobbMI) and Iris (YL57xq9U); an add-on and what it brings; Lithium is not in the family. */
    private static PackManifest sodiumPack() {
        return manifest(
                item("AANobbMI", "mods/sodium-fabric-0.8.12.jar", true, "P7dR8mSH"),
                item("YL57xq9U", "mods/iris-fabric-1.10.9.jar", true, "AANobbMI"),
                item("extras", "mods/extras.jar", true, "AANobbMI", "anvil"),
                item("lithium", "mods/lithium.jar", true),
                library("anvil", "mods/anvianslib.jar"),
                library("P7dR8mSH", "mods/fabric-api.jar"));
    }

    @Test
    public void heldItemsAreNotInstalledButKeepTheirChoices() {
        PackManifest m = sodiumPack();
        java.util.Set<String> family = SodiumSupport.family(m);
        assertEquals(new java.util.HashSet<>(Arrays.asList("AANobbMI", "YL57xq9U", "extras")), family);

        // A new set on a phone that cannot run Sodium: the pack's defaults are recorded, nothing of
        // the family is installed (nor the library only it needs), Lithium is.
        ItemResolver.Resolution fresh = ItemResolver.resolve(m, null, null, Collections.<String, Boolean>emptyMap(),
                ItemResolver.MANIFEST_DEFAULTS, family);
        assertEquals(Collections.singletonList("lithium"), enabled(fresh));
        assertEquals("the recorded choice stays the pack's default", Boolean.TRUE, fresh.choices.get("AANobbMI"));
        ItemResolver.ItemStatus sodium = fresh.status("AANobbMI");
        assertTrue(sodium.selected && sodium.heldOff && !sodium.effectivelyEnabled);
        assertFalse("libraries are never held, only not needed", fresh.status("anvil").heldOff);
        assertFalse(fresh.status("lithium").heldOff);

        // Once nothing holds it (another renderer, the player's override), the same state installs it.
        ItemResolver.Resolution back = ItemResolver.resolve(m, stateWith(fresh), null, Collections.<String, Boolean>emptyMap(),
                ItemResolver.MANIFEST_DEFAULTS, Collections.<String>emptySet());
        assertTrue(back.isEnabled("AANobbMI") && back.isEnabled("YL57xq9U") && back.isEnabled("extras") && back.isEnabled("anvil"));
        assertFalse(back.status("AANobbMI").heldOff);

        // An explicit selection switching a held item on wins, and brings what it needs.
        ItemResolver.Resolution chosen = ItemResolver.resolve(m, stateWith(fresh), select("YL57xq9U", true),
                Collections.<String, Boolean>emptyMap(), ItemResolver.MANIFEST_DEFAULTS, family);
        assertTrue(chosen.isEnabled("YL57xq9U") && chosen.isEnabled("AANobbMI") && chosen.isEnabled("P7dR8mSH"));
        assertFalse("not selected, still held", chosen.isEnabled("extras"));
    }

    @Test
    public void sodiumFamilyIsRecognisedByIdOrFileName() {
        PackManifest.PackItem byModrinth = item("x", "mods/x.jar", true);
        byModrinth.modrinth = new PackManifest.ModrinthRef();
        byModrinth.modrinth.projectId = "AANobbMI";
        assertTrue(SodiumSupport.isKnown(byModrinth));
        assertTrue(SodiumSupport.isKnown(item("y", "mods/Embeddium-1.0.jar", true)));
        assertTrue(SodiumSupport.isKnown(item("reeses", "mods/reeses-sodium-options.jar", true)));
        assertTrue(SodiumSupport.isKnown(item("YL57xq9U", "mods/shaders.jar", true)));
        assertFalse(SodiumSupport.isKnown(item("lithium", "mods/lithium-fabric.jar", true)));
        assertNull(item("z", "mods/z.jar", true).modrinth);
        assertNotNull(SodiumSupport.family(catalog()));
    }
}
