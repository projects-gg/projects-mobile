package gg.projects.mobile.content;

import static gg.projects.mobile.content.TestPacks.file;
import static gg.projects.mobile.content.TestPacks.item;
import static gg.projects.mobile.content.TestPacks.library;
import static gg.projects.mobile.content.TestPacks.list;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The mod screen's pending-choice helpers (mirror of the desktop's modcenter/selection.ts). */
public class ModsSelectionTest {
    private static PackManifest catalog() {
        PackManifest.Projects ext = TestPacks.ext();
        ext.items = list(item("sodium", "mods/sodium.jar", true, "fabric-api"),
                item("iris", "mods/iris.jar", false, "sodium"),
                item("shaders", "shaderpacks/shaders.zip", false, "iris"),
                library("fabric-api", "mods/fabric-api.jar"));
        return TestPacks.manifest(list(file("mods/sodium.jar", "s"), file("mods/iris.jar", "i"),
                file("shaderpacks/shaders.zip", "z"), file("mods/fabric-api.jar", "f")), ext);
    }

    private static List<String> titles(List<PackManifest.PackItem> items) {
        List<String> out = new ArrayList<>();
        for (PackManifest.PackItem item : items) out.add(item.title);
        return out;
    }

    @Test
    public void pendingChoicesAndWhatTheyBring() {
        PackManifest m = catalog();
        ItemResolver.Resolution resolution = ItemResolver.resolve(m, null, null);
        Map<String, Boolean> choices = ModsSelection.choicesOf(resolution.items);
        assertEquals("libraries are never choices", 3, choices.size());
        assertNull("nothing changed yet", ModsSelection.pending(resolution.items, choices, null));

        Set<String> enabled = ModsSelection.enabledSet(m, choices);
        assertEquals(new HashSet<>(Arrays.asList("sodium", "fabric-api")), enabled);
        assertEquals(Collections.singletonList("SODIUM"), titles(ModsSelection.neededBy(m, enabled, "fabric-api")));
        // Shaders would bring Iris along; Sodium is on already.
        assertEquals(Collections.singletonList("IRIS"), titles(ModsSelection.bringsAlong(m, "shaders", enabled)));
        assertEquals(Arrays.asList("IRIS", "SODIUM", "FABRIC-API"), titles(ModsSelection.bringsAlong(m, "shaders", new HashSet<String>())));

        choices.put("shaders", true);
        enabled = ModsSelection.enabledSet(m, choices);
        assertTrue(enabled.containsAll(Arrays.asList("shaders", "iris", "sodium", "fabric-api")));
        assertEquals(Collections.singletonList("SHADERS"), titles(ModsSelection.neededBy(m, enabled, "iris")));
        PackSelection pending = ModsSelection.pending(resolution.items, choices, Collections.<String>emptySet());
        assertEquals("only what differs from the recorded choice", "{shaders=true}", pending.items.toString());

        // Switching it back is no change at all.
        choices.put("shaders", false);
        assertNull(ModsSelection.pending(resolution.items, choices, null));
        // A revert alone is a change.
        PackSelection revert = ModsSelection.pending(resolution.items, choices, Collections.singleton("sodium"));
        assertTrue(revert.items.isEmpty());
        assertEquals(Collections.singleton("sodium"), revert.revert);
    }

    @Test
    public void totalsCountEveryCategory() {
        PackSyncPlanner.Summary summary = new PackSyncPlanner.Summary();
        summary.added.mods = 2;
        summary.added.shaderPacks = 1;
        summary.removed.mods = 1;
        assertEquals(Arrays.asList(3, 0, 1), Arrays.asList(ModsSelection.totals(summary)[0], ModsSelection.totals(summary)[1],
                ModsSelection.totals(summary)[2]));

        // The bar counts mods (and packs) apart from configs, which it names only on their own.
        summary.updated.mods = 1;
        summary.updated.configs = 1;
        summary.removed.configs = 2;
        int[][] bar = ModsSelection.barCounts(summary);
        assertEquals(3, bar[0][0]);
        assertEquals(0, bar[0][1]);
        assertEquals(1, bar[1][0]);
        assertEquals(1, bar[1][1]);
        assertEquals(1, bar[2][0]);
        assertEquals(2, bar[2][1]);
    }
}
