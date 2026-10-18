package gg.projects.mobile.content;

import static gg.projects.mobile.content.TestPacks.file;
import static gg.projects.mobile.content.TestPacks.item;
import static gg.projects.mobile.content.TestPacks.library;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sodium on Android: which renderers run it, what holds it off, the offer to old sets. */
public class SodiumSupportTest {
    /** The Projects pack's family with its real ids: Sodium, Iris, add-ons; Lithium is not in it. */
    private static PackManifest pack() {
        PackManifest.PackItem[] items = {
                item("AANobbMI", "mods/sodium-fabric-0.8.12+mc26.1.2.jar", true),
                item("PtjYWJkn", "mods/sodium-extra-fabric-0.8.7.jar", true, "AANobbMI"),
                item("Bh37bMuy", "mods/reeses-sodium-options-fabric-2.0.5.jar", true, "AANobbMI"),
                item("696WgnvT", "mods/SodiumExtraInformation-fabric-2.9.0.jar", true, "PtjYWJkn", "AANobbMI", "PuTjrq4T"),
                item("YL57xq9U", "mods/iris-fabric-1.10.9.jar", true, "AANobbMI"),
                item("gvQqBUqZ", "mods/lithium-fabric-0.24.6.jar", true),
                item("optional", "mods/optional-sodium-addon.jar", false, "AANobbMI"),
                library("PuTjrq4T", "mods/anvianslib.jar")};
        List<PackManifest.PackFile> files = new ArrayList<>();
        for (PackManifest.PackItem item : items) files.add(file(item.path, item.path));
        PackManifest.Projects ext = TestPacks.ext();
        ext.items = new ArrayList<>(Arrays.asList(items));
        return TestPacks.manifest(files, ext);
    }

    @Test
    public void aCrashGoesIntoANewSet() {
        // The stored set is the immutable empty set before the first crash.
        Set<String> none = Collections.emptySet();
        Set<String> crashed = SodiumSupport.withCrash(none, null);
        assertEquals(Collections.singleton(SodiumSupport.DEFAULT_RENDERER), crashed);
        assertTrue(none.isEmpty());
        Set<String> both = SodiumSupport.withCrash(crashed, "vulkan_zink");
        assertEquals(new HashSet<>(Arrays.asList(SodiumSupport.DEFAULT_RENDERER, "opengles3_desktopgl_zink_kopper")), both);
        assertEquals(1, crashed.size());
        assertEquals(crashed, SodiumSupport.withCrash(crashed, SodiumSupport.DEFAULT_RENDERER));
    }

    @Test
    public void renderersThatRunSodium() {
        // MobileGlues (upstream's default, also what a profile without its own renderer gets), Zink, LTW.
        assertTrue(SodiumSupport.rendererRunsSodium(null));
        assertTrue(SodiumSupport.rendererRunsSodium(""));
        assertTrue(SodiumSupport.rendererRunsSodium("opengles_mobileglues"));
        assertTrue(SodiumSupport.rendererRunsSodium("opengles3_desktopgl_zink_kopper"));
        assertTrue(SodiumSupport.rendererRunsSodium("vulkan_zink"));
        assertTrue(SodiumSupport.rendererRunsSodium("opengles3_ltw"));
        // The GL4ES family (Krypton Wrapper) cannot.
        assertFalse(SodiumSupport.rendererRunsSodium("opengles2"));
        assertFalse(SodiumSupport.rendererRunsSodium("opengles2_5"));
        assertFalse(SodiumSupport.rendererRunsSodium("opengles3"));
        assertEquals("opengles_mobileglues", SodiumSupport.rendererOrDefault(null));
        assertEquals("upstream starts the old Zink as Kopper Zink", "opengles3_desktopgl_zink_kopper",
                SodiumSupport.rendererOrDefault("vulkan_zink"));
    }

    @Test
    public void whatHoldsTheFamilyOff() {
        assertEquals(SodiumSupport.Block.NONE, SodiumSupport.block(null, Collections.<String>emptySet(), false));
        assertEquals(SodiumSupport.Block.RENDERER, SodiumSupport.block("opengles2", Collections.<String>emptySet(), false));
        // A crash with the default renderer holds it off for a profile without its own renderer too.
        assertEquals(SodiumSupport.Block.CRASHED,
                SodiumSupport.block(null, Collections.singleton("opengles_mobileglues"), false));
        assertEquals("another renderer is another chance", SodiumSupport.Block.NONE,
                SodiumSupport.block("opengles3_ltw", Collections.singleton("opengles_mobileglues"), false));
        // The player switched it back on: nothing holds it.
        assertEquals(SodiumSupport.Block.NONE, SodiumSupport.block("opengles2", Collections.singleton("opengles2"), true));

        PackManifest m = pack();
        assertTrue(SodiumSupport.heldOff(m, SodiumSupport.Block.NONE).isEmpty());
        assertEquals(SodiumSupport.family(m), SodiumSupport.heldOff(m, SodiumSupport.Block.CRASHED));
    }

    @Test
    public void theFamilyOfTheProjectsPack() {
        PackManifest m = pack();
        assertEquals(new HashSet<>(Arrays.asList("AANobbMI", "PtjYWJkn", "Bh37bMuy", "696WgnvT", "YL57xq9U", "optional")),
                SodiumSupport.family(m));
        assertEquals("what the offer switches on: the recommended members",
                new HashSet<>(Arrays.asList("AANobbMI", "PtjYWJkn", "Bh37bMuy", "696WgnvT", "YL57xq9U")),
                SodiumSupport.recommendedFamily(m));
    }

    @Test
    public void setsFromBeforeTheChangeAreOfferedSodium() {
        PackManifest m = pack();
        // What 0.1.0 to 0.1.4 recorded on Android: the family off, the rest the pack's defaults.
        PackState old = new PackState();
        for (PackManifest.PackItem item : m.projects.items) {
            if (!item.library) old.items.put(item.id, item.defaultEnabled && !SodiumSupport.family(m).contains(item.id));
        }
        old.seenItems.addAll(old.items.keySet());
        ItemResolver.Resolution resolution = ItemResolver.resolve(m, old, null);
        assertTrue(SodiumSupport.missesRecommendedSodium(m, resolution.items));

        // A set that has Sodium (here without one add-on) is not offered anything.
        old.items.put("AANobbMI", true);
        assertFalse(SodiumSupport.missesRecommendedSodium(m, ItemResolver.resolve(m, old, null).items));
        // Neither is a pack that does not recommend Sodium or Iris.
        for (PackManifest.PackItem item : m.projects.items) if (SodiumSupport.isKnown(item)) item.defaultEnabled = false;
        old.items.put("AANobbMI", false);
        assertFalse(SodiumSupport.missesRecommendedSodium(m, ItemResolver.resolve(m, old, null).items));
    }
}
