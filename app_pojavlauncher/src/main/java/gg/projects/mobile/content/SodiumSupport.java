package gg.projects.mobile.content;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sodium and the mods built on it, on Android.
 *
 * <p>Sodium 0.8 needs a renderer that gives it a modern desktop OpenGL: MobileGlues (upstream's
 * default), Mesa's Zink and LTW do; the GL4ES family (Krypton Wrapper) does not. On a renderer that
 * runs it the pack's defaults apply, as on the desktop. On one that does not, or after Sodium kept
 * the game from starting on this phone, the family is held off ({@link Block}): its files are not
 * installed, but the player's recorded choices stay, so the mods come back by themselves when the
 * reason goes away. Switching one of them on in the mod screen overrides the hold.
 *
 * <p>The content format has no field for this, so the family is recognised here: the known mods by
 * Modrinth project id, item id or file name, plus every item that requires one of them (installing
 * it would install Sodium too).
 */
public final class SodiumSupport {
    private SodiumSupport() {}

    /** Modrinth project ids and the slugs Studio would use: Sodium, Iris and Sodium's Forge ports. */
    static final Set<String> KNOWN_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "AANobbMI", "YL57xq9U", "sodium", "iris", "embeddium", "rubidium")));
    /** File name parts of Sodium and its forks. */
    static final List<String> KNOWN_FILE_NAMES = Collections.unmodifiableList(Arrays.asList(
            "sodium", "embeddium", "rubidium"));

    /** The renderer a profile without its own uses (upstream's {@code MainActivity}). */
    public static final String DEFAULT_RENDERER = "opengles_mobileglues";
    /** Upstream's GL4ES family (Krypton Wrapper, {@code libng_gl4es}): an old OpenGL Sodium cannot use. */
    static final Set<String> GL4ES_RENDERERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "opengles2", "opengles2_5", "opengles3")));

    /** Why the Sodium family is held off. */
    public enum Block {
        /** It is not: the pack's defaults and the player's choices apply. */
        NONE,
        /** The profile's renderer cannot run Sodium. */
        RENDERER,
        /** Sodium kept the game from starting on this phone with this renderer. */
        CRASHED
    }

    /** The renderer id a profile runs with: its own, or the default when it has none. */
    public static String rendererOrDefault(String renderer) {
        if (renderer == null || renderer.trim().isEmpty()) return DEFAULT_RENDERER;
        // Upstream moved the old OSMesa Zink onto Kopper Zink at launch.
        if (renderer.equals("vulkan_zink")) return "opengles3_desktopgl_zink_kopper";
        return renderer;
    }

    /** True when {@code renderer} (a profile's {@code pojavRendererName}, null for the default) can run Sodium. */
    public static boolean rendererRunsSodium(String renderer) {
        return !GL4ES_RENDERERS.contains(rendererOrDefault(renderer));
    }

    /**
     * Why the family is held off for a profile on {@code renderer}, given the renderers Sodium
     * crashed with on this phone and whether the player switched it back on since.
     */
    public static Block block(String renderer, Collection<String> crashedRenderers, boolean override) {
        if (override) return Block.NONE;
        if (!rendererRunsSodium(renderer)) return Block.RENDERER;
        if (crashedRenderers != null && crashedRenderers.contains(rendererOrDefault(renderer))) return Block.CRASHED;
        return Block.NONE;
    }

    /** True for an item that is itself Sodium, one of its forks or Iris. */
    public static boolean isKnown(PackManifest.PackItem item) {
        if (item.id != null && KNOWN_IDS.contains(item.id)) return true;
        if (item.modrinth != null && item.modrinth.projectId != null && KNOWN_IDS.contains(item.modrinth.projectId)) return true;
        String name = item.fileName();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String part : KNOWN_FILE_NAMES) if (lower.contains(part)) return true;
        }
        return false;
    }

    /** Ids of the Sodium family in a pack: the known mods and every item requiring one of them. */
    public static Set<String> family(PackManifest manifest) {
        Set<String> family = new HashSet<>();
        List<PackManifest.PackItem> items = manifest.projects == null || manifest.projects.items == null
                ? Collections.<PackManifest.PackItem>emptyList() : manifest.projects.items;
        for (PackManifest.PackItem item : items) if (isKnown(item)) family.add(item.id);
        boolean grew = !family.isEmpty();
        while (grew) {
            grew = false;
            for (PackManifest.PackItem item : items) {
                if (family.contains(item.id) || item.requires == null) continue;
                for (String required : item.requires) {
                    if (family.contains(required)) {
                        family.add(item.id);
                        grew = true;
                        break;
                    }
                }
            }
        }
        return family;
    }

    /** The items {@link #block} holds off: the whole family while it is blocked, else none. */
    public static Set<String> heldOff(PackManifest manifest, Block block) {
        return block == Block.NONE ? Collections.<String>emptySet() : family(manifest);
    }

    /**
     * True when a set no longer has Sodium although the pack recommends it: what 0.1.0 to 0.1.4
     * left behind (they started the family switched off on Android). Only the known mods count, so
     * a set that keeps Sodium but drops an add-on is not offered anything.
     */
    public static boolean missesRecommendedSodium(PackManifest manifest, List<ItemResolver.ItemStatus> statuses) {
        boolean recommended = false;
        for (PackManifest.PackItem item : manifest.projects.items) {
            if (item.library || !item.defaultEnabled || !isKnown(item)) continue;
            recommended = true;
            for (ItemResolver.ItemStatus status : statuses) {
                if (status.id.equals(item.id) && status.effectivelyEnabled) return false;
            }
        }
        return recommended;
    }

    /** The recommended family members (default on, not libraries): what the "Aç" offer switches on. */
    public static Set<String> recommendedFamily(PackManifest manifest) {
        Set<String> family = family(manifest);
        Set<String> out = new HashSet<>();
        for (PackManifest.PackItem item : manifest.projects.items) {
            if (!item.library && item.defaultEnabled && family.contains(item.id)) out.add(item.id);
        }
        return out;
    }
}
