package gg.projects.mobile.launch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M17: the graphics a new game folder starts with on a phone (the desktop's W2A preset, made for
 * phones). Minecraft's own first options (render distance 12, simulation 12, fancy leaves, every
 * particle, 120 FPS) are a desktop's; the Projects pack takes about 2 GB in a world, and a phone that
 * runs out of memory closes the game, while one that renders too much gets hot and slows down.
 *
 * <p>Like the desktop, the preset goes only into an {@code options.txt} the app creates
 * ({@link FirstOptions}): a file the game, the player or a pack wrote is never touched, and the
 * player changes everything in the game's own video settings afterwards. Only vanilla options are
 * written: Sodium (on by default where the renderer runs it) reads the same render distance and
 * keeps its own file, which the app never writes.
 *
 * <p>Which preset: the phone's memory (what Android reports, a little under the box's figure),
 * its cores, Android's own low-RAM flag and the GPU's name. A weak GPU or a 3 to 4 GB phone gets
 * {@link Tier#LIGHT}; 6 GB gets {@link Tier#BALANCED}; {@link Tier#RICH} needs 8 GB and a GPU known
 * to be strong (an unknown GPU stays balanced). Plain Java, JVM-tested.
 */
public final class PhoneGraphics {
    private PhoneGraphics() {}

    /** How much the phone draws. */
    public enum Tier {
        /** 3 to 4 GB, a low-RAM phone, few cores or a weak GPU: the game first, distance second. */
        LIGHT,
        /** About 6 GB: farther, with shadows, still light on effects. */
        BALANCED,
        /** 8 GB and a strong GPU: about the desktop's lightest preset. */
        RICH,
    }

    private static final long GB = 1024L * 1024 * 1024;
    /** Android reports a 4 GB phone as about 3.6 GB, 6 GB as about 5.5, 8 GB as about 7.4. */
    static final long LIGHT_UP_TO = (long) (4.6 * GB);
    static final long BALANCED_UP_TO = (long) (6.8 * GB);
    static final int FEW_CORES = 4;

    /** What the GPU's name says ({@code GL_RENDERER}). */
    enum Gpu { WEAK, UNKNOWN, STRONG }

    private static final Pattern ADRENO = Pattern.compile("adreno[^0-9]*(\\d{3})");
    private static final Pattern MALI_G = Pattern.compile("mali-g(\\d{2,3})");

    /** A strong, a weak or an unknown GPU by its name; software renderers count as weak. */
    static Gpu gpu(String renderer) {
        if (renderer == null) return Gpu.UNKNOWN;
        String name = renderer.toLowerCase(Locale.ROOT);
        Matcher adreno = ADRENO.matcher(name);
        if (adreno.find()) {
            // 640 and up (Snapdragon 855 on, the 7xx and 8xx families) are capable; below 610, entry or old.
            int model = Integer.parseInt(adreno.group(1));
            if (model >= 640) return Gpu.STRONG;
            return model < 610 ? Gpu.WEAK : Gpu.UNKNOWN;
        }
        Matcher mali = MALI_G.matcher(name);
        if (mali.find()) {
            int model = Integer.parseInt(mali.group(1));
            // G31 to G57 are entry GPUs; G68, G7x, G610 and newer, G7xx and G9xx are capable.
            if (model < 60) return Gpu.WEAK;
            if (model >= 710 || (model >= 76 && model < 100)) return Gpu.STRONG;
            return Gpu.UNKNOWN;
        }
        if (name.contains("immortalis") || name.contains("xclipse")) return Gpu.STRONG;
        if (name.contains("mali-t") || name.contains("mali-4") || name.contains("mali-3") || name.contains("powervr")
                || name.contains("sgx") || name.contains("vivante") || name.contains("swiftshader")
                || name.contains("llvmpipe") || name.contains("softpipe")) {
            return Gpu.WEAK;
        }
        return Gpu.UNKNOWN;
    }

    /** The tier for a phone with {@code totalMemory} bytes, {@code cores} and its GPU's name (null: unknown). */
    public static Tier tier(long totalMemory, boolean lowRam, int cores, String renderer) {
        Gpu gpu = gpu(renderer);
        if (lowRam || gpu == Gpu.WEAK) return Tier.LIGHT;
        if (totalMemory > 0 && totalMemory <= LIGHT_UP_TO) return Tier.LIGHT;
        if (cores > 0 && cores <= FEW_CORES) return Tier.LIGHT;
        if (totalMemory > 0 && totalMemory <= BALANCED_UP_TO) return Tier.BALANCED;
        if (totalMemory <= 0) return Tier.BALANCED;
        return gpu == Gpu.STRONG ? Tier.RICH : Tier.BALANCED;
    }

    /** One option the preset writes, its value per tier and the versions that read it. */
    private static final class Setting {
        final String key;
        final String[] values;
        /** First version with the key: {release major, minor, patch, snapshot year, week}; null: every version presets cover. */
        final int[] since;
        /** First version that reads another key instead, or null. */
        final int[] until;
        /** An enum value: a JSON string from 1.19 on, bare before. */
        final boolean text;

        Setting(String key, boolean text, int[] since, int[] until, String light, String balanced, String rich) {
            this.key = key;
            this.text = text;
            this.since = since;
            this.until = until;
            this.values = new String[]{light, balanced, rich};
        }
    }

    // Version gates (ProLauncher2 crates/pl-mc instance/options.rs, the Minecraft Wiki's options.txt table).
    private static final int[] V1_7_2 = {1, 7, 2, 13, 38};
    private static final int[] V1_8 = {1, 8, 0, 14, 25};
    private static final int[] V1_8_1 = {1, 8, 1, 15, 31};
    private static final int[] V1_13 = {1, 13, 0, 18, 15};
    private static final int[] V1_16 = {1, 16, 0, 20, 27};
    private static final int[] V1_16_ENTITIES = {1, 16, 0, 20, 17};
    private static final int[] V1_18 = {1, 18, 0, 21, 38};
    private static final int[] ENUMS_QUOTED = {1, 19, 0, 22, 11};
    private static final int[] V1_21_6 = {1, 21, 6, 25, 17};
    private static final int[] V1_21_11 = {1, 21, 11, 25, 41};
    private static final int[] V1_21_11_LEAVES = {1, 21, 11, 25, 44};

    /**
     * The presets, option by option (light, balanced, rich). Lighter than the desktop's: the render
     * distance decides most of the memory a world takes; 60 FPS keeps a phone cool where the game
     * would draw 120; leaves, shadows and biome blending cost the most per frame on a phone GPU.
     * Smooth lighting, the GUI scale and everything else stay the game's.
     */
    private static final List<Setting> SETTINGS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Setting("renderDistance", false, null, null, "6", "8", "10"),
            new Setting("simulationDistance", false, V1_18, null, "5", "5", "6"),
            new Setting("maxFps", false, null, null, "60", "60", "60"),
            new Setting("fancyGraphics", false, null, V1_16, "false", "false", "true"),
            new Setting("graphicsMode", false, V1_16, V1_21_11, "0", "0", "1"),
            // Only the name the game's video settings show: the values are none of its own presets.
            new Setting("graphicsPreset", true, V1_21_11, null, "custom", "custom", "custom"),
            new Setting("cutoutLeaves", false, V1_21_11_LEAVES, null, "false", "false", "true"),
            new Setting("improvedTransparency", false, V1_21_11, null, "false", "false", "false"),
            new Setting("weatherRadius", false, V1_21_11, null, "5", "5", "10"),
            new Setting("particles", false, null, null, "1", "1", "0"),
            new Setting("clouds", false, null, V1_8, "false", "false", "true"),
            new Setting("renderClouds", true, V1_8, null, "false", "fast", "fast"),
            new Setting("cloudRange", false, V1_21_6, null, "32", "32", "64"),
            new Setting("entityShadows", false, V1_8_1, null, "false", "true", "true"),
            new Setting("entityDistanceScaling", false, V1_16_ENTITIES, null, "0.5", "0.75", "1.0"),
            new Setting("biomeBlendRadius", false, V1_13, null, "0", "1", "2"),
            new Setting("mipmapLevels", false, null, null, "2", "2", "4")));

    private static boolean reached(String version, int[] gate) {
        return FirstOptions.atLeast(version, gate[0], gate[1], gate[2], gate[3], gate[4]);
    }

    /** True when presets cover Minecraft {@code version} (1.7.2 and newer, as on the desktop). */
    public static boolean supports(String version) {
        return reached(version, V1_7_2);
    }

    /** The keys {@link #lines} writes for {@code version}. */
    public static Set<String> keys(String version) {
        Set<String> keys = new LinkedHashSet<>();
        if (!supports(version)) return keys;
        for (Setting setting : SETTINGS) if (current(setting, version)) keys.add(setting.key);
        return keys;
    }

    /** The preset's lines for a new options.txt of Minecraft {@code version} ({@code key:value}). */
    public static List<String> lines(String version, Tier tier) {
        List<String> lines = new ArrayList<>();
        if (!supports(version)) return lines;
        boolean quoted = reached(version, ENUMS_QUOTED);
        for (Setting setting : SETTINGS) {
            if (!current(setting, version)) continue;
            String value = setting.values[tier.ordinal()];
            lines.add(setting.key + ':' + (setting.text && quoted ? '"' + value + '"' : value));
        }
        return lines;
    }

    private static boolean current(Setting setting, String version) {
        if (setting.since != null && !reached(version, setting.since)) return false;
        return setting.until == null || !reached(version, setting.until);
    }
}
