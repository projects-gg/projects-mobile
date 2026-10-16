package gg.projects.mobile.content;

/**
 * The Minecraft version of the profiles the app makes for the player (the desktop's rule: new
 * custom instances start with the recommended version selected). Amethyst creates its default
 * profile ("Default") on 1.7.10; the app moves it to the recommended version and keeps it on newer
 * recommendations only while it still has the version the app gave it. A version the player
 * picked is never changed.
 */
public final class DefaultVersion {
    private DefaultVersion() {}

    /** Amethyst's default profile as upstream creates it. */
    public static final String UPSTREAM_NAME = "Default";
    public static final String UPSTREAM_VERSION = "1.7.10";

    /** The recommended version of {@code config}; the built-in default before any content. */
    public static String recommended(LauncherConfig config) {
        return config == null ? ContentConfig.DEFAULT_RECOMMENDED_MINECRAFT_VERSION : config.recommendedMinecraft();
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
        boolean untouched = applied != null
                ? applied.equals(current)
                : UPSTREAM_NAME.equals(name) && UPSTREAM_VERSION.equals(current);
        return untouched && recommended != null && !recommended.equals(current) ? recommended : null;
    }
}
