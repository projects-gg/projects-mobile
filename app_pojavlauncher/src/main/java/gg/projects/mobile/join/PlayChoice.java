package gg.projects.mobile.join;

import androidx.annotation.Nullable;

import gg.projects.mobile.ui.VersionLabel;

/**
 * M18: which profile OYNA should play when the player most likely means Projects (the 0.1.7 tester's
 * report: a "Fabric 26.1.2" profile chosen in the play bar, every mod ticked in "Modlar", and a game
 * with no mods). Plain Java, JVM-tested.
 *
 * <ul>
 * <li>{@link #selectProjectsAtFirstStart}: a new install plays Projects. Upstream's default profile
 * (plain vanilla, no mods, no server) was the only profile, so it was the selection, and OYNA started
 * it.</li>
 * <li>{@link #offerProjects}: OYNA on a profile of the player's own that runs a mod loader with no
 * mods in its folder (what upstream's "Fabric" profile from the profile list is) offers Projects
 * first: the pack's mods live only in the Projects profile, the "Modlar" screen shows them all the
 * same.</li>
 * </ul>
 */
public final class PlayChoice {
    private PlayChoice() {}

    /**
     * Whether the Projects profile, created at the app's first start, becomes the selection.
     *
     * @param projectsExisted the Projects profile was there already (an install that joined before
     *                        keeps whatever the player chose)
     * @param currentExists   the selected profile exists
     * @param currentName     its name
     */
    public static boolean selectProjectsAtFirstStart(boolean projectsExisted, boolean currentExists, @Nullable String currentName) {
        if (projectsExisted) return false;
        if (!currentExists) return true;
        // Upstream's own default profile ("(Default)", "Default", or historically "New" or no name) was
        // not the player's choice; a profile the player made and named was.
        if (currentName == null || currentName.trim().isEmpty() || "New".equalsIgnoreCase(currentName.trim())) return true;
        return VersionLabel.isDefaultName(currentName);
    }

    /**
     * Whether OYNA on a profile other than Projects asks first ("Bu profilde mod yok").
     *
     * @param versionId     the profile's version ({@code lastVersionId})
     * @param hasMods       its mods folder holds at least one mod file
     * @param packPublished the content publishes a Projects pack (there are Projects mods to offer)
     * @param confirmed     the player already chose to play this profile anyway
     */
    public static boolean offerProjects(@Nullable String versionId, boolean hasMods, boolean packPublished, boolean confirmed) {
        return packPublished && !confirmed && !hasMods && VersionLabel.isModLoader(versionId);
    }

    /** True for a file name a mod loader reads from the mods folder (a jar, any case). */
    public static boolean isModFile(@Nullable String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    /** True when a mods folder's listing ({@code File.list()}, null for no folder) holds a mod. */
    public static boolean hasModFiles(@Nullable String[] names) {
        if (names == null) return false;
        for (String name : names) if (isModFile(name)) return true;
        return false;
    }
}
