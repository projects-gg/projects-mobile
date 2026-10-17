package gg.projects.mobile.content;

/**
 * The Minecraft version of the profiles the app makes for the player (the desktop's rule: new
 * custom instances start with the recommended version selected). Amethyst's default profile starts
 * on 1.7.10; the app moves it to the recommended version and keeps it on newer recommendations only
 * while it still has the version the app gave it. A version the player picked is never changed.
 */
public final class DefaultVersion {
    private DefaultVersion() {}

    /**
     * Names of Amethyst's default profile: "(Default)" from the bundled launcher_profiles.json,
     * "Default" when MinecraftProfile.getDefaultProfile() fills an empty profile list.
     */
    private static final String[] UPSTREAM_NAMES = {"(Default)", "Default"};
    /** The version Amethyst's default profile is created with. */
    public static final String UPSTREAM_VERSION = "1.7.10";

    /** The recommended version of {@code config}; the built-in default before any content. */
    public static String recommended(LauncherConfig config) {
        return config == null ? ContentConfig.DEFAULT_RECOMMENDED_MINECRAFT_VERSION : config.recommendedMinecraft();
    }

    /**
     * Whether a version list marks {@code versionId} "Önerilen": exactly the recommended version,
     * as the desktop's lists do. A recommendation the list lacks marks nothing, and versions built
     * on it (an installed "fabric-loader-0.17.2-26.1.2", a "26.1.2-rc1") are other versions.
     */
    public static boolean isRecommended(String versionId, String recommended) {
        return recommended != null && !recommended.isEmpty() && recommended.equals(versionId);
    }

    /** Amethyst's default profile as upstream creates it, untouched by the player. */
    public static boolean isUpstreamDefault(String name, String version) {
        if (!UPSTREAM_VERSION.equals(version)) return false;
        for (String upstream : UPSTREAM_NAMES) if (upstream.equals(name)) return true;
        return false;
    }

    /**
     * The version a profile should move to, or null to leave it as it is.
     *
     * @param name        the profile's name
     * @param current     its version now ({@code lastVersionId})
     * @param applied     the version the app last gave this profile, or null when it never did
     * @param recommended the version to follow
     */
    public static String next(String name, String current, String applied, String recommended) {
        boolean untouched = applied != null ? applied.equals(current) : isUpstreamDefault(name, current);
        return untouched && recommended != null && !recommended.equals(current) ? recommended : null;
    }
}
