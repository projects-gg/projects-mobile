package gg.projects.mobile.controls;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which mods the game loaded, read from its log while it starts (M16), for the MODLAR keys: a key
 * of a mod the game did not load says so instead of sending a key nobody listens to.
 *
 * <p>Fabric Loader lists them before Minecraft starts ("Loading 122 mods:", then one
 * {@code \t- <id> <version>} line each, nested ones as {@code \t   |-- <id> <version>}; the count is
 * of distinct mods, nested ones included). A game that reaches Minecraft's own start with no mod
 * loader in its log is vanilla: no mods. Quilt, Forge and NeoForge list their mods in other ways, so
 * with them the answer stays unknown and the keys work as bound.
 *
 * <p>The log reaches the app in pieces (the native logger reads up to 2 KB at a time, other threads
 * append whole lines) and the logger drops the newline a piece ends with, so a piece may end at a
 * line's end or inside a line without saying which. Outside the list a piece's end is taken as a
 * line's end, but the end of the text since the last newline the log showed is kept, so a header cut
 * by a piece's end is still found. Fabric's list is one event of several KB, so inside it pieces are
 * joined as one stream: an entry cut by a piece's end is whole again, every entry on a line counts
 * (two lines may be glued), and only the game's next log event ("[17:01:27] [main/INFO]: …") ends
 * the list. A list short of its own count, or without Fabric Loader and Minecraft in it, was not read
 * whole: then the answer is unknown, never "no mods". Plain Java, JVM-tested.
 */
public final class LoadedMods {
    private static final Pattern HEADER = Pattern.compile("\\bLoading (\\d+) mods?:");
    /** One entry of the list, also inside glued lines: a tab, "-" or "|--"/"\--", the id, its version. */
    private static final Pattern ENTRY = Pattern.compile("\\t[ \\t]*(?:-|[|\\\\]--)[ \\t]+([A-Za-z0-9_.\\-]+)[ \\t]+\\S+");
    /** The start of the game's next log event (log4j's "[hh:mm:ss] [thread/LEVEL]"). */
    private static final Pattern NEXT_EVENT = Pattern.compile("\\[\\d{2}:\\d{2}:\\d{2}\\] \\[");
    /** A mod loader in the log (LaunchStages' markers). */
    private static final String[] LOADER_MARKERS = {
            "with Fabric Loader", "Fabric Loader ", "Quilt Loader", "ModLauncher", "Forge Mod Loader", "/FML",
            "fml.", "NeoForge",
    };
    /** Minecraft itself starting: a mod loader would have shown up before this. */
    private static final String[] MINECRAFT_MARKERS = {
            "[Render thread/", "[Client thread/", "Setting user:", "Backend library:", "LWJGL Version",
    };
    /** Longest line kept (Fabric's list of a few hundred mods, joined, fits). */
    private static final int MAX_LINE = 64 * 1024;
    /** How much of a line a piece's end may have cut is kept for the next piece (a header fits). */
    private static final int CARRY = 64;

    private final StringBuilder partial = new StringBuilder();
    private final Set<String> ids = new LinkedHashSet<>();
    /** Outside the list: the end of the line so far, when the last piece ended without a newline. */
    private String carry = "";
    private boolean listing;
    private int expected = -1;
    private boolean sawLoader;
    private boolean finished;
    private Set<String> result;

    /** Reads one piece of the log; true once the answer is final (known or no longer coming). */
    public synchronized boolean feed(String chunk) {
        if (finished || chunk == null || chunk.isEmpty()) return finished;
        int start = 0;
        for (int i = 0; i < chunk.length() && !finished; i++) {
            if (chunk.charAt(i) != '\n') continue;
            append(chunk, start, i);
            line(partial.toString(), true);
            partial.setLength(0);
            start = i + 1;
        }
        if (finished) return true;
        append(chunk, start, chunk.length());
        // Outside the list the piece's end is a line's end; inside it the next piece goes on with it,
        // until the game's next event has begun.
        if (!listing || NEXT_EVENT.matcher(partial).find()) {
            line(partial.toString(), false);
            partial.setLength(0);
        }
        return finished;
    }

    private void append(String chunk, int from, int to) {
        int room = MAX_LINE - partial.length();
        if (room <= 0) return;
        partial.append(chunk, from, Math.min(to, from + room));
    }

    /** A line, or the part of one up to a piece's end ({@code ended} false: it may go on). */
    private void line(String raw, boolean ended) {
        String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        if (listing) {
            list(line);
            return;
        }
        String before = carry;
        String seen = before.isEmpty() ? line : before + line;
        carry = ended ? "" : seen.substring(Math.max(0, seen.length() - CARRY));
        String text = line;
        Matcher header = HEADER.matcher(line);
        if (!header.find()) {
            // A header the last piece's end cut in two.
            text = seen;
            header = before.isEmpty() ? null : HEADER.matcher(seen);
            if (header != null && !header.find()) header = null;
        }
        if (header != null) {
            listing = true;
            sawLoader = true;
            carry = "";
            try {
                expected = Integer.parseInt(header.group(1));
            } catch (NumberFormatException e) {
                expected = -1;
            }
            list(text.substring(header.end()));
            return;
        }
        if (containsAny(seen, LOADER_MARKERS)) sawLoader = true;
        // Minecraft starts: vanilla when no loader came first; another loader's list we cannot read.
        if (containsAny(seen, MINECRAFT_MARKERS)) finish(sawLoader ? null : Collections.<String>emptySet());
    }

    /** Text inside the list: every entry in it counts; the game's next log event ends the list. */
    private void list(String text) {
        Matcher next = NEXT_EVENT.matcher(text);
        int end = next.find() ? next.start() : text.length();
        Matcher entry = ENTRY.matcher(text);
        entry.region(0, end);
        while (entry.find()) ids.add(entry.group(1).toLowerCase(Locale.ROOT));
        if (end < text.length()) finish(complete() ? ids : null);
    }

    /** The whole list: Fabric always lists itself and Minecraft, and as many mods as it counted. */
    private boolean complete() {
        return ids.contains("fabricloader") && ids.contains("minecraft") && (expected < 0 || ids.size() >= expected);
    }

    private void finish(Set<String> mods) {
        finished = true;
        result = mods == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(mods));
        partial.setLength(0);
        carry = "";
    }

    /** True once {@link #mods()} will not change. */
    public synchronized boolean finished() {
        return finished;
    }

    /** The loaded mod ids (lower case, nested ones too); empty for vanilla; null while unknown. */
    public synchronized Set<String> mods() {
        return result;
    }

    private static boolean containsAny(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }
}
