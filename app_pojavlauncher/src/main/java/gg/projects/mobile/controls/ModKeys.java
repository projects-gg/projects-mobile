package gg.projects.mobile.controls;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The MODLAR panel follows the player's own key bindings (M16). Each mod key of the default layout
 * stands for one key mapping of a mod in the Projects pack, and the game folder's {@code options.txt}
 * says which key that mapping uses ({@code key_<mapping>:<key>}): the launchers' table in a new file
 * (zoom C, gamma X, push-to-talk Caps Lock), the mods' own defaults in one the game saved before
 * 0.1.6 (zoom V, gamma G, push-to-talk none), or whatever the player picked in the game's Controls.
 * The button sends that key. A mapping without a key, a key the screen cannot press, a key another
 * mapping uses in normal play (the game fires both) and a mod the game did not load make the button
 * unavailable, and it says why ({@link State}).
 *
 * <p>A button is a mod key only while it still has the glyph and the key the layout gave it: a key
 * the player changed in the controls editor is the player's and is sent as it is. Plain Java,
 * JVM-tested.
 */
public final class ModKeys {
    private ModKeys() {}

    // GLFW key codes (LwjglGlfwKeycode).
    static final int KEY_B = 66, KEY_C = 67, KEY_M = 77, KEY_X = 88, KEY_Z = 90, KEY_CAPS_LOCK = 280;
    static final int KEY_LEFT_SHIFT = 340, KEY_LEFT_CONTROL = 341, KEY_LEFT_ALT = 342;
    // Mouse buttons as upstream's special button codes (ControlData.SPECIALBTN_*; saved in layouts, never change).
    static final int MOUSE_LEFT = -3, MOUSE_RIGHT = -4, MOUSE_MIDDLE = -6, MOUSE_BACK = -10, MOUSE_FORWARD = -11;

    /** The options.txt value of a mapping without a key. */
    public static final String UNBOUND_KEY = "key.keyboard.unknown";

    /**
     * A mod key of the layout: its glyph, the key mapping it presses, the mod's own default key (what
     * the game uses while options.txt has no line for the mapping), the mod's id and name, and the key
     * the 0.1.6 layout stores for the button.
     */
    public enum Action {
        ZOOM("zoom", "key.wi_zoom.zoom", "key.keyboard.v", "wi_zoom", "WI Zoom", KEY_C),
        BRIGHTNESS("bright", "key.gammautils.gammaToggle", "key.keyboard.g", "gammautils", "Gamma Utils", KEY_X),
        MICROPHONE("mic", "key.mute_microphone", "key.keyboard.m", "voicechat", "Simple Voice Chat", KEY_M),
        WAYPOINT("waypoint", "gui.xaero_new_waypoint", "key.keyboard.b", "xaerobetterpvp", "Xaero's Better PvP", KEY_B),
        MAP("map", "gui.xaero_enlarge_map", "key.keyboard.z", "xaerobetterpvp", "Xaero's Better PvP", KEY_Z),
        TALK("talk", "key.push_to_talk", UNBOUND_KEY, "voicechat", "Simple Voice Chat", KEY_CAPS_LOCK);

        public final String glyph;
        public final String mapping;
        public final String modDefault;
        public final String modId;
        public final String modName;
        final int layoutKey;

        Action(String glyph, String mapping, String modDefault, String modId, String modName, int layoutKey) {
            this.glyph = glyph;
            this.mapping = mapping;
            this.modDefault = modDefault;
            this.modId = modId;
            this.modName = modName;
            this.layoutKey = layoutKey;
        }
    }

    /** What a mod key can do right now. */
    public enum State {
        /** It sends the key the mapping uses. */
        READY,
        /** The mapping has no key (the game's Controls can give it one). */
        UNBOUND,
        /** The mapping's key is one the screen cannot press (a scan code, a mouse button past 5). */
        UNSENDABLE,
        /**
         * Another mapping that acts in normal play has the same key, and the game fires every mapping
         * of a pressed key: in a file saved before 0.1.6 zoom shares V with Simple Voice Chat's menu,
         * so the button opened that menu instead of zooming.
         */
        CONFLICT,
        /** The game did not load the mod. */
        NO_MOD,
    }

    /**
     * Mappings that act only with F3 held, in creative's hotbar, as a spectator, in Replay Mod or on a
     * server's dialog (Quick Actions): sharing their key is no clash in normal play (KEYS1's
     * inventory, ProLauncher2 crates/pl-mc/testdata/key-bindings).
     */
    private static final java.util.Set<String> CONTEXT_ONLY = Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
            "key.saveToolbarActivator", "key.loadToolbarActivator", "key.spectatorHotbar", "key.spectatorOutlines",
            "key.toggleSpectatorShaderEffects", "key.quickActions")));

    static boolean contextOnly(String mapping) {
        return mapping.startsWith("key.debug.") || mapping.startsWith("key.replaymod.") || CONTEXT_ONLY.contains(mapping);
    }

    /** How a mod key is bound now. */
    public static final class Binding {
        public final Action action;
        public final State state;
        /** The keys to press, modifier first (GLFW key codes or upstream's mouse codes); empty unless READY. */
        public final int[] keys;
        /** The options.txt value it came from (the mod's default when the file has none). */
        public final String input;

        Binding(Action action, State state, int[] keys, String input) {
            this.action = action;
            this.state = state;
            this.keys = keys;
            this.input = input;
        }

        public boolean ready() {
            return state == State.READY;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder(action.name().toLowerCase(Locale.ROOT)).append(' ').append(state);
            out.append(" (").append(input).append(')');
            return out.toString();
        }
    }

    /**
     * The mod key a layout button stands for, or null: the button's glyph is a mod key's and it still
     * sends the one key the layout gave it (empty slots are 0).
     */
    public static Action actionOf(String glyph, int[] keycodes) {
        if (glyph == null || keycodes == null) return null;
        for (Action action : Action.values()) {
            if (!action.glyph.equals(glyph)) continue;
            int found = 0;
            for (int code : keycodes) {
                if (code == 0) continue;
                if (code != action.layoutKey) return null;
                found++;
            }
            return found == 1 ? action : null;
        }
        return null;
    }

    /**
     * The key bindings of an options.txt ({@code key_<mapping>:<value>} lines), mapping name to value.
     * The value is everything after the first colon (the game splits a line there); a later line for
     * the same mapping wins, as when the game reads the file.
     */
    public static Map<String, String> keyBindings(Iterable<String> lines) {
        Map<String, String> out = new HashMap<>();
        if (lines == null) return out;
        for (String line : lines) {
            if (line == null || !line.startsWith("key_")) continue;
            int colon = line.indexOf(':');
            if (colon <= 4) continue;
            out.put(line.substring(4, colon), line.substring(colon + 1).trim());
        }
        return out;
    }

    /** {@link #resolve(Action, Map, Set)} without knowing which mods the game loaded. */
    public static Binding resolve(Action action, Map<String, String> bindings) {
        return resolve(action, bindings, null);
    }

    /**
     * How {@code action} is bound: the options.txt line for its mapping, else the mod's own default.
     * {@code loadedMods} is the game's mod ids when known (null when not): a mod missing from it makes
     * the key {@link State#NO_MOD}.
     */
    public static Binding resolve(Action action, Map<String, String> bindings, Set<String> loadedMods) {
        String input = bindings == null ? null : bindings.get(action.mapping);
        if (input == null || input.isEmpty()) input = action.modDefault;
        if (loadedMods != null && !loadedMods.contains(action.modId)) {
            return new Binding(action, State.NO_MOD, new int[0], input);
        }
        String key = input;
        int modifier = 0;
        int colon = input.indexOf(':');
        if (colon >= 0) {
            // Forge and NeoForge write a key modifier after the key: "key.keyboard.x:SHIFT".
            key = input.substring(0, colon);
            String mod = input.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
            switch (mod) {
                case "SHIFT": modifier = KEY_LEFT_SHIFT; break;
                case "CONTROL": modifier = KEY_LEFT_CONTROL; break;
                case "ALT": modifier = KEY_LEFT_ALT; break;
                case "NONE": case "": modifier = 0; break;
                default: return new Binding(action, State.UNSENDABLE, new int[0], input);
            }
        }
        if (key.equals(UNBOUND_KEY)) return new Binding(action, State.UNBOUND, new int[0], input);
        Integer code = code(key);
        if (code == null) return new Binding(action, State.UNSENDABLE, new int[0], input);
        if (bindings != null && sharedInPlay(action, input, bindings)) {
            return new Binding(action, State.CONFLICT, new int[0], input);
        }
        int[] keys = modifier == 0 ? new int[]{code} : new int[]{modifier, code};
        return new Binding(action, State.READY, keys, input);
    }

    /** Another mapping of the file that acts in normal play has {@code input} (modifier included). */
    private static boolean sharedInPlay(Action action, String input, Map<String, String> bindings) {
        String mine = input.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            String mapping = entry.getKey();
            if (mapping.equals(action.mapping) || contextOnly(mapping) || entry.getValue() == null) continue;
            if (entry.getValue().trim().toLowerCase(Locale.ROOT).equals(mine)) return true;
        }
        return false;
    }

    /**
     * The code to send for one of the game's input names ({@code key.keyboard.*}, {@code key.mouse.*}):
     * a GLFW key code, or upstream's special code for a mouse button. Null for a name the screen cannot
     * press (scan codes, mouse buttons 6 to 8, anything unknown).
     */
    public static Integer code(String name) {
        if (name == null) return null;
        return CODES.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private static final Map<String, Integer> CODES;

    static {
        Map<String, Integer> codes = new HashMap<>();
        for (char c = 'a'; c <= 'z'; c++) codes.put("key.keyboard." + c, 65 + (c - 'a'));
        for (int d = 0; d <= 9; d++) {
            codes.put("key.keyboard." + d, 48 + d);
            codes.put("key.keyboard.keypad." + d, 320 + d);
        }
        for (int f = 1; f <= 25; f++) codes.put("key.keyboard.f" + f, 289 + f);
        String[][] named = {
                {"space", "32"}, {"apostrophe", "39"}, {"comma", "44"}, {"minus", "45"}, {"period", "46"},
                {"slash", "47"}, {"semicolon", "59"}, {"equal", "61"}, {"left.bracket", "91"}, {"backslash", "92"},
                {"right.bracket", "93"}, {"grave.accent", "96"}, {"world.1", "161"}, {"world.2", "162"},
                {"escape", "256"}, {"enter", "257"}, {"tab", "258"}, {"backspace", "259"}, {"insert", "260"},
                {"delete", "261"}, {"right", "262"}, {"left", "263"}, {"down", "264"}, {"up", "265"},
                {"page.up", "266"}, {"page.down", "267"}, {"home", "268"}, {"end", "269"},
                {"caps.lock", "280"}, {"scroll.lock", "281"}, {"num.lock", "282"}, {"print.screen", "283"},
                {"pause", "284"}, {"keypad.decimal", "330"}, {"keypad.divide", "331"},
                {"keypad.multiply", "332"}, {"keypad.subtract", "333"}, {"keypad.add", "334"},
                {"keypad.enter", "335"}, {"keypad.equal", "336"}, {"left.shift", "340"},
                {"left.control", "341"}, {"left.alt", "342"}, {"left.win", "343"}, {"right.shift", "344"},
                {"right.control", "345"}, {"right.alt", "346"}, {"right.win", "347"}, {"menu", "348"},
        };
        for (String[] pair : named) codes.put("key.keyboard." + pair[0], Integer.parseInt(pair[1]));
        codes.put("key.mouse.left", MOUSE_LEFT);
        codes.put("key.mouse.right", MOUSE_RIGHT);
        codes.put("key.mouse.middle", MOUSE_MIDDLE);
        codes.put("key.mouse.4", MOUSE_BACK);
        codes.put("key.mouse.5", MOUSE_FORWARD);
        CODES = Collections.unmodifiableMap(codes);
    }
}
