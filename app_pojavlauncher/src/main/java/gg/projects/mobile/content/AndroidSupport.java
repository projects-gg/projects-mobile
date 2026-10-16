package gg.projects.mobile.content;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pack items known not to work on Android. Upstream (Amethyst) does not support Sodium and its
 * forks and warns before every launch when one is installed; Iris needs Sodium. The content
 * format has no field for this, so the app recognises them by Modrinth project id, item id or
 * file name, and also marks every item that requires one of them (installing it would install
 * the unsupported mod too).
 *
 * <p>On Android such items start switched off on a new install; the player can still switch
 * them on.
 */
public final class AndroidSupport {
    private AndroidSupport() {}

    /** Modrinth project ids and the slugs Studio would use: Sodium, Iris. */
    static final Set<String> KNOWN_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "AANobbMI", "YL57xq9U", "sodium", "iris", "embeddium", "rubidium")));
    /** File name parts upstream treats as Sodium or one of its forks. */
    static final List<String> KNOWN_FILE_NAMES = Collections.unmodifiableList(Arrays.asList(
            "sodium", "embeddium", "rubidium"));

    /** True for an item that is itself one of the known unsupported mods. */
    public static boolean isKnownUnsupported(PackManifest.PackItem item) {
        if (item.id != null && KNOWN_IDS.contains(item.id)) return true;
        if (item.modrinth != null && item.modrinth.projectId != null && KNOWN_IDS.contains(item.modrinth.projectId)) return true;
        String name = item.fileName();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String part : KNOWN_FILE_NAMES) if (lower.contains(part)) return true;
        }
        return false;
    }

    /** Ids of the items that cannot work on Android: known ones and every item requiring one. */
    public static Set<String> unsupportedItems(PackManifest manifest) {
        Set<String> unsupported = new HashSet<>();
        List<PackManifest.PackItem> items = manifest.projects == null || manifest.projects.items == null
                ? Collections.<PackManifest.PackItem>emptyList() : manifest.projects.items;
        for (PackManifest.PackItem item : items) if (isKnownUnsupported(item)) unsupported.add(item.id);
        boolean grew = !unsupported.isEmpty();
        while (grew) {
            grew = false;
            for (PackManifest.PackItem item : items) {
                if (unsupported.contains(item.id) || item.requires == null) continue;
                for (String required : item.requires) {
                    if (unsupported.contains(required)) {
                        unsupported.add(item.id);
                        grew = true;
                        break;
                    }
                }
            }
        }
        return unsupported;
    }

    /** Fresh-set defaults on Android: the manifest's, except that unsupported items start off. */
    public static ItemResolver.Defaults defaults(PackManifest manifest) {
        final Set<String> unsupported = unsupportedItems(manifest);
        return new ItemResolver.Defaults() {
            @Override
            public boolean enabledByDefault(PackManifest.PackItem item) {
                return item.defaultEnabled && !unsupported.contains(item.id);
            }
        };
    }
}
