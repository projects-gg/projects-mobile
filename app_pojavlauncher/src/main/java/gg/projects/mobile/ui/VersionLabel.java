package gg.projects.mobile.ui;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a profile's version id means to a player (M14): "Minecraft 26.1.2", or with the mod loader
 * "Minecraft 26.1.2, Fabric" instead of "fabric-loader-0.19.5-26.1.2". Ids it does not know stay as
 * they are. Plain Java, JVM-tested.
 */
public final class VersionLabel {
    private VersionLabel() {}

    private static final String MC = "(\\d+(?:\\.\\d+)+(?:-(?:pre|rc)\\d+)?|\\d{2}w\\d{2}[a-z])";
    private static final Pattern FABRIC = Pattern.compile("fabric-loader-[^-]+-" + MC);
    private static final Pattern QUILT = Pattern.compile("quilt-loader-[^-]+-" + MC);
    private static final Pattern FORGE = Pattern.compile(MC + "-forge.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTIFINE = Pattern.compile(MC + "-OptiFine.*", Pattern.CASE_INSENSITIVE);
    /** NeoForge 20.2–25.x: "neoforge-21.1.77" is for Minecraft 1.21.1 ("21.0.x" for 1.21). */
    private static final Pattern NEOFORGE = Pattern.compile("neoforge-(\\d+)\\.(\\d+)\\.[^-]+.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern VANILLA = Pattern.compile(MC);

    /**
     * The player's line for {@code versionId} ("Minecraft 1.21.1, NeoForge"); the id itself when it
     * is not one of the known shapes, or "" for none.
     */
    public static String describe(String versionId) {
        if (versionId == null) return "";
        String id = versionId.trim();
        if (id.isEmpty()) return "";
        Matcher m;
        if ((m = FABRIC.matcher(id)).matches()) return minecraft(m.group(1)) + ", Fabric";
        if ((m = QUILT.matcher(id)).matches()) return minecraft(m.group(1)) + ", Quilt";
        if ((m = OPTIFINE.matcher(id)).matches()) return minecraft(m.group(1)) + ", OptiFine";
        if ((m = FORGE.matcher(id)).matches()) return minecraft(m.group(1)) + ", Forge";
        if ((m = NEOFORGE.matcher(id)).matches()) {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            // From Minecraft 26 on NeoForge numbers follow the game's own years; not guessed here.
            if (major >= 20 && major < 26) {
                return minecraft("1." + major + (minor == 0 ? "" : "." + minor)) + ", NeoForge";
            }
            return "NeoForge " + id.substring("neoforge-".length());
        }
        if (VANILLA.matcher(id).matches()) return minecraft(id);
        return id;
    }

    private static String minecraft(String version) {
        return "Minecraft " + version.toLowerCase(Locale.ROOT);
    }

    /**
     * M18: true when {@code versionId} runs a mod loader that reads the mods folder (Fabric, Quilt,
     * Forge, NeoForge); OptiFine and plain versions do not.
     */
    public static boolean isModLoader(String versionId) {
        if (versionId == null) return false;
        String id = versionId.trim();
        if (id.isEmpty() || OPTIFINE.matcher(id).matches()) return false;
        return FABRIC.matcher(id).matches() || QUILT.matcher(id).matches() || FORGE.matcher(id).matches()
                || id.toLowerCase(Locale.ROOT).startsWith("neoforge-");
    }

    /**
     * The default profile Amethyst ships ("(Default)", or "Default" when the list was empty) gets a
     * name in the player's language; any other name stays as the player wrote it.
     */
    public static boolean isDefaultName(String name) {
        if (name == null) return false;
        String n = name.trim();
        return n.equals("(Default)") || n.equals("Default");
    }
}
