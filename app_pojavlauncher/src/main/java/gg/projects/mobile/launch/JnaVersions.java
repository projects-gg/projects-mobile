package gg.projects.mobile.launch;

import androidx.annotation.Nullable;

/**
 * M8 (mobile.md "16 KB page size"): the JNA a game version runs on the phone ({@code Tools.preProcessLibraries}). Its
 * Android natives come from the JNA AAR of the same version, which the downloader takes per game version and the game
 * loads through {@code jna.boot.library.path}. Those of JNA 5.12.1 to 5.16.0 are not 16 KB ready (x86_64: 4 KB aligned;
 * arm64: writable data in the last RELRO page, which a 16 KB phone makes read-only, so the first write faults even in
 * compat mode); 5.17.0 and later are. So JNA 5.14 to 5.16, whose native interface is 7.0.x like 5.17's, run as 5.17.0
 * (its jar and its natives). Upstream's rule stays for the old ones: below 5.13, 5.13.0 (LabyMod 1.8.9, Forge 1.12.2,
 * oshi); 5.12 and 5.13 natives keep their RELRO page until a jar update (native interface 6.x). Pure, with tests.
 */
public final class JnaVersions {
    private JnaVersions() {}

    /** The first JNA whose Android natives are 16 KB ready. */
    public static final String ALIGNED = "5.17.0";
    /** Upstream's JNA for game versions that ask for one older than 5.13. */
    public static final String LEGACY = "5.13.0";

    /** The jar's SHA-1 at Maven Central (checked against the jar on 29 October 2026). */
    private static final String ALIGNED_SHA1 = "33d12735bef894440780fce64f9758d420c7bae2";
    private static final String LEGACY_SHA1 = "1200e7ebeedbe0d10062093f32925a912020e747";

    /** The JNA to run instead of {@code version} ("5.15.0" → "5.17.0"), or null to keep it. */
    @Nullable
    public static String replacement(@Nullable String version) {
        int[] v = majorMinor(version);
        if (v == null) return null;
        if (v[0] < 5 || (v[0] == 5 && v[1] < 13)) return LEGACY;
        if (v[0] == 5 && v[1] >= 14 && v[1] <= 16) return ALIGNED;
        return null;
    }

    /** The SHA-1 of a replacement's jar ({@link #replacement} only gives these two). */
    public static String sha1(String version) {
        return ALIGNED.equals(version) ? ALIGNED_SHA1 : LEGACY.equals(version) ? LEGACY_SHA1 : "";
    }

    /** The jar's path in a Maven repository. */
    public static String path(String version) {
        return "net/java/dev/jna/jna/" + version + "/jna-" + version + ".jar";
    }

    public static String url(String version) {
        return "https://repo1.maven.org/maven2/" + path(version);
    }

    /** "5.15.0" → {5, 15}; null when the version does not start with two numbers. */
    @Nullable
    private static int[] majorMinor(@Nullable String version) {
        if (version == null) return null;
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1].replaceAll("[^0-9].*$", ""))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
