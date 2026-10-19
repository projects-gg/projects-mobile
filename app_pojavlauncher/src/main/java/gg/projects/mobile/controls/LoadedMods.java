package gg.projects.mobile.controls;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which mods the game loaded, read from its log while it starts (M16), for the MODLAR keys: a key
 * of a mod the game did not load says so instead of sending a key nobody listens to.
 *
 * <p>Fabric Loader lists them before Minecraft starts ("Loading 122 mods:", then one
 * {@code \t- <id> <version>} line each, nested ones as {@code \t   |-- <id> <version>}). A game that
 * reaches Minecraft's own start with no mod loader in its log is vanilla: no mods. Quilt, Forge and
 * NeoForge list their mods in other ways, so with them the answer stays unknown and the keys work
 * as bound. The log arrives in chunks that may cut a line in two; lines are put together first.
 * Plain Java, JVM-tested.
 */
public final class LoadedMods {
    private static final Pattern HEADER = Pattern.compile("\\bLoading \\d+ mods?:\\s*$");
    private static final Pattern ENTRY = Pattern.compile("^\\t\\s*(?:-|[|\\\\]--)\\s+([A-Za-z0-9_.\\-]+)");
    /** A mod loader in the log (LaunchStages' markers). */
    private static final String[] LOADER_MARKERS = {
            "with Fabric Loader", "Fabric Loader ", "Quilt Loader", "ModLauncher", "Forge Mod Loader", "/FML",
            "fml.", "NeoForge",
    };
    /** Minecraft itself starting: a mod loader would have shown up before this. */
    private static final String[] MINECRAFT_MARKERS = {
            "[Render thread/", "[Client thread/", "Setting user:", "Backend library:", "LWJGL Version",
    };
    private static final int MAX_LINE = 8 * 1024;

    private final StringBuilder partial = new StringBuilder();
    private final Set<String> ids = new LinkedHashSet<>();
    private boolean listing;
    private boolean sawLoader;
    private boolean finished;
    private Set<String> result;

    /** Reads one chunk of the log; true once the answer is final (known or no longer coming). */
    public boolean feed(String chunk) {
        if (finished || chunk == null || chunk.isEmpty()) return finished;
        int start = 0;
        for (int i = 0; i < chunk.length() && !finished; i++) {
            if (chunk.charAt(i) != '\n') continue;
            append(chunk, start, i);
            line(partial.toString());
            partial.setLength(0);
            start = i + 1;
        }
        if (!finished) append(chunk, start, chunk.length());
        return finished;
    }

    private void append(String chunk, int from, int to) {
        int room = MAX_LINE - partial.length();
        if (room <= 0) return;
        partial.append(chunk, from, Math.min(to, from + room));
    }

    private void line(String raw) {
        String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        if (listing) {
            Matcher entry = ENTRY.matcher(line);
            if (entry.find()) {
                ids.add(entry.group(1).toLowerCase(java.util.Locale.ROOT));
                return;
            }
            // Fabric always lists itself and Minecraft: a list without them was not read right.
            finish(ids.contains("fabricloader") && ids.contains("minecraft") ? ids : null);
            return;
        }
        if (HEADER.matcher(line).find()) {
            listing = true;
            sawLoader = true;
            return;
        }
        if (containsAny(line, LOADER_MARKERS)) sawLoader = true;
        else if (containsAny(line, MINECRAFT_MARKERS)) {
            // Minecraft starts: vanilla when no loader came first; another loader's list we cannot read.
            if (sawLoader) finish(null);
            else finish(Collections.<String>emptySet());
        }
    }

    private void finish(Set<String> mods) {
        finished = true;
        result = mods == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(mods));
        partial.setLength(0);
    }

    /** True once {@link #mods()} will not change. */
    public boolean finished() {
        return finished;
    }

    /** The loaded mod ids (lower case, nested ones too); empty for vanilla; null while unknown. */
    public Set<String> mods() {
        return result;
    }

    private static boolean containsAny(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }
}
