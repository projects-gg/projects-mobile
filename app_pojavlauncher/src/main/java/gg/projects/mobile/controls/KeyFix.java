package gg.projects.mobile.controls;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * M17: one tap fixes the key clashes an options.txt saved before 0.1.6 still has (the owner's
 * decision "tek dokunuşla düzelt" on docs/mobile.md Decision 12). The Projects pack's mods come
 * with keys that clash in normal play (KEYS1, ProLauncher2 docs/tasks/keys1.md "Çakışmalar"): zoom
 * and Simple Voice Chat's menu on V, Gamma Utils' toggle and the game's Quick Actions on G, Simple
 * Voice Chat's "hide icons" and Gamma Utils' night vision on H, Iris' shader reload and Xaero's
 * quick eat on R. Since 0.1.6 a new file gets the launchers' table ({@code assets/key-bindings.json},
 * a copy of ProLauncher2's {@code crates/pl-mc/data/key-bindings.json}); an older one still has the
 * mods' defaults.
 *
 * <p>The rule is the desktop's (KEYS2, pl-mc {@code instance::keys::find_key_clashes}), so both
 * launchers fix the same files the same way: a clash counts when the table's binding and the mapping
 * it shares its default key with ({@code clashesWith}) are both written in the file, both exactly on
 * that key. A mapping without a line does not count (the game drops the lines of mods it did not
 * load and writes them all once it ran with them), a key the player changed never counts, and a
 * clash is left alone when the table's new key already belongs to another mapping that acts in
 * normal play. The fix changes only those lines' values; every other byte (the order, unknown lines,
 * line endings) stays. Push-to-talk, unbound by default, is no clash. Plain Java, JVM-tested.
 */
public final class KeyFix {
    private KeyFix() {}

    /**
     * The clashes of the pack (KEYS1's inventory of 26.1.2), for a table that does not name them
     * yet: the binding the table moves and the mapping it shares its default key with. The table's
     * own {@code clashesWith} wins where it has one.
     */
    private static final String[][] CLASHES = {
            {"key.wi_zoom.zoom", "key.voice_chat"},
            {"key.gammautils.gammaToggle", "key.quickActions"},
            {"key.hide_icons", "key.gammautils.nightVisionToggle"},
            {"iris.keybind.reload", "gui.xaero_quick_eat"},
    };

    /** The table is a few kilobytes. */
    private static final int MAX_TABLE_CHARS = 64 * 1024;

    /** One clash the fix can solve. */
    public static final class Pair {
        /** The binding the table moves, e.g. {@code key.wi_zoom.zoom}. */
        public final String mapping;
        /** Its mod's own key, which it shares with {@link #partner}. */
        public final String modDefault;
        /** The table's key for it ({@link ModKeys#UNBOUND_KEY} leaves it without one). */
        public final String target;
        /** The mapping it clashes with on {@link #modDefault}. */
        public final String partner;

        Pair(String mapping, String modDefault, String target, String partner) {
            this.mapping = mapping;
            this.modDefault = modDefault;
            this.target = target;
            this.partner = partner;
        }
    }

    /** One line the fix writes: the binding's value from {@link #before} to {@link #after}. */
    public static final class Change {
        public final String mapping;
        public final String before;
        public final String after;

        Change(String mapping, String before, String after) {
            this.mapping = mapping;
            this.before = before;
            this.after = after;
        }

        @Override
        public String toString() {
            return mapping + " " + before + " -> " + after;
        }
    }

    /**
     * The clashes the shared key table solves ({@code bindings[]}: {@code name}, {@code key},
     * {@code default} and {@code clashesWith}; the built-in list where a binding has no
     * {@code clashesWith}). An unreadable table gives none.
     */
    public static List<Pair> pairs(String tableJson) {
        List<Pair> pairs = new ArrayList<>();
        if (tableJson == null || tableJson.length() > MAX_TABLE_CHARS) return pairs;
        try {
            JsonElement root = JsonParser.parseString(tableJson);
            if (!root.isJsonObject()) return pairs;
            JsonElement bindings = ((JsonObject) root).get("bindings");
            if (bindings == null || !bindings.isJsonArray()) return pairs;
            for (JsonElement entry : bindings.getAsJsonArray()) {
                if (!entry.isJsonObject()) continue;
                JsonObject binding = (JsonObject) entry;
                String name = string(binding, "name");
                String key = string(binding, "key");
                String modDefault = string(binding, "default");
                if (name == null || key == null || modDefault == null || key.equals(modDefault)) continue;
                String partner = string(binding, "clashesWith");
                if (partner == null) partner = builtInPartner(name);
                if (partner == null || partner.equals(name)) continue;
                pairs.add(new Pair(name, modDefault, key, partner));
            }
        } catch (RuntimeException e) {
            pairs.clear();
        }
        return pairs;
    }

    private static String builtInPartner(String name) {
        for (String[] clash : CLASHES) if (clash[0].equals(name)) return clash[1];
        return null;
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && ((JsonPrimitive) value).isString() ? value.getAsString() : null;
    }

    /**
     * False for mappings that act only outside normal play: F3 combinations, creative mode's hotbar
     * modifiers, spectator keys and Replay Mod (its viewer and recording). F3 itself and Replay Mod's
     * settings screen act in play. The desktop's {@code acts_in_play}, word for word.
     */
    static boolean actsInPlay(String name) {
        switch (name) {
            case "key.debug.overlay":
            case "key.replaymod.settings":
                return true;
            case "key.saveToolbarActivator":
            case "key.loadToolbarActivator":
            case "key.spectatorHotbar":
            case "key.spectatorOutlines":
            case "key.toggleSpectatorShaderEffects":
                return false;
            default:
                return !(name.startsWith("key.debug.") || name.startsWith("key.replaymod."));
        }
    }

    /**
     * What the fix changes in a file with these key bindings ({@link ModKeys#keyBindings}, the last
     * line of a mapping counting): empty when nothing clashes any more (a 0.1.6 file, or the player
     * moved one of the two).
     */
    public static List<Change> plan(Map<String, String> bindings, List<Pair> pairs) {
        List<Change> changes = new ArrayList<>();
        if (bindings == null || pairs == null) return changes;
        for (Pair pair : pairs) {
            if (!pair.modDefault.equals(bindings.get(pair.mapping)) || !pair.modDefault.equals(bindings.get(pair.partner))) continue;
            if (!pair.target.equals(ModKeys.UNBOUND_KEY) && taken(pair.target, pair.mapping, bindings)) continue;
            changes.add(new Change(pair.mapping, pair.modDefault, pair.target));
        }
        return changes;
    }

    /** True when {@code changes} moves {@code mapping}. */
    public static boolean covers(List<Change> changes, String mapping) {
        if (changes == null) return false;
        for (Change change : changes) if (change.mapping.equals(mapping)) return true;
        return false;
    }

    /** Another mapping that acts in normal play has {@code key}: moving there would make a new clash. */
    private static boolean taken(String key, String except, Map<String, String> bindings) {
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            if (!entry.getKey().equals(except) && key.equals(entry.getValue()) && actsInPlay(entry.getKey())) return true;
        }
        return false;
    }

    /**
     * {@code content} (an options.txt) with each change made: every {@code key_<mapping>:<before>}
     * line becomes {@code key_<mapping>:<after>}. Every other byte stays.
     */
    public static String apply(String content, List<Change> changes) {
        StringBuilder out = new StringBuilder(content.length() + 64);
        for (String[] line : lines(content)) {
            String text = line[0];
            Change change = changeOf(text, changes);
            if (change != null) out.append("key_").append(change.mapping).append(':').append(change.after);
            else out.append(text);
            out.append(line[1]);
        }
        return out.toString();
    }

    /**
     * Takes a fix back on {@code current}, given the file as it was before ({@code backup}): when
     * nothing else changed since, the old file comes back byte for byte; when the game saved the file
     * meanwhile, only the lines still on the table's keys go back to their old key (the desktop's
     * {@code undo_key_fix}).
     */
    public static String undo(String current, String backup, List<Pair> pairs) {
        List<Change> fixed = plan(bindings(backup), pairs);
        if (apply(backup, fixed).equals(current)) return backup;
        List<Change> back = new ArrayList<>();
        for (Change change : fixed) back.add(new Change(change.mapping, change.after, change.before));
        return apply(current, back);
    }

    /**
     * The key bindings of an options.txt's text, each exactly as written (the desktop compares bytes);
     * a mapping written twice counts with its last line, as in the game.
     */
    public static Map<String, String> bindings(String content) {
        Map<String, String> out = new java.util.HashMap<>();
        for (String[] line : lines(content)) {
            String text = line[0];
            if (!text.startsWith("key_")) continue;
            int colon = text.indexOf(':');
            if (colon <= 4) continue;
            out.put(text.substring(4, colon), text.substring(colon + 1));
        }
        return out;
    }

    /** The change for a line {@code key_<mapping>:<before>} (the game splits at the first colon), or null. */
    private static Change changeOf(String text, List<Change> changes) {
        if (!text.startsWith("key_")) return null;
        int colon = text.indexOf(':');
        if (colon <= 4) return null;
        String mapping = text.substring(4, colon);
        String value = text.substring(colon + 1);
        for (Change change : changes) {
            if (change.mapping.equals(mapping) && change.before.equals(value)) return change;
        }
        return null;
    }

    /** The lines of {@code content} as {text, ending}; the ending is empty on a last line without one. */
    private static List<String[]> lines(String content) {
        if (content.isEmpty()) return Collections.emptyList();
        List<String[]> lines = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int newline = content.indexOf('\n', start);
            if (newline < 0) {
                lines.add(new String[]{content.substring(start), ""});
                break;
            }
            int textEnd = newline > start && content.charAt(newline - 1) == '\r' ? newline - 1 : newline;
            lines.add(new String[]{content.substring(start, textEnd), content.substring(textEnd, newline + 1)});
            start = newline + 1;
        }
        return lines;
    }
}
