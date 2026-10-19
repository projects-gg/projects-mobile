package gg.projects.mobile.controls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.launch.FirstOptions;

/** The MODLAR panel's keys follow options.txt (M16). */
public class ModKeysTest {
    private static final int V = 86, G = 71, C = 67, X = 88, M = 77, B = 66, Z = 90, CAPS = 280;

    private static Map<String, String> options(String... lines) {
        return ModKeys.keyBindings(Arrays.asList(lines));
    }

    private static ModKeys.Binding zoom(String value) {
        return ModKeys.resolve(ModKeys.Action.ZOOM, options("key_key.wi_zoom.zoom:" + value));
    }

    @Test
    public void aButtonIsAModKeyWhileItHasItsGlyphAndTheLayoutsKey() {
        assertSame(ModKeys.Action.ZOOM, ModKeys.actionOf("zoom", new int[]{C, 0, 0, 0}));
        assertSame(ModKeys.Action.BRIGHTNESS, ModKeys.actionOf("bright", new int[]{X, 0, 0, 0}));
        assertSame(ModKeys.Action.MICROPHONE, ModKeys.actionOf("mic", new int[]{M, 0, 0, 0}));
        assertSame(ModKeys.Action.WAYPOINT, ModKeys.actionOf("waypoint", new int[]{B, 0, 0, 0}));
        assertSame(ModKeys.Action.MAP, ModKeys.actionOf("map", new int[]{Z, 0, 0, 0}));
        assertSame(ModKeys.Action.TALK, ModKeys.actionOf("talk", new int[]{CAPS, 0, 0, 0}));
        assertSame(ModKeys.Action.ZOOM, ModKeys.actionOf("zoom", new int[]{0, 0, C, 0}));
        // Keys the player set in the controls editor are the player's.
        assertNull(ModKeys.actionOf("zoom", new int[]{V, 0, 0, 0}));
        assertNull(ModKeys.actionOf("zoom", new int[]{C, 340, 0, 0}));
        assertNull(ModKeys.actionOf("zoom", new int[]{0, 0, 0, 0}));
        // Other glyphs, no glyph.
        assertNull(ModKeys.actionOf("chat", new int[]{84, -1, 0, 0}));
        assertNull(ModKeys.actionOf(null, new int[]{C, 0, 0, 0}));
        assertNull(ModKeys.actionOf("zoom", null));
    }

    private static JsonObject layout(String name) throws IOException {
        File file = new File("src/main/assets/" + name);
        return JsonParser.parseString(new String(AtomicFiles.read(file, 4L << 20), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    public void theDefaultLayoutsModsPanelHoldsEveryModKey() throws IOException {
        for (String name : new String[]{"default.json", "default_en.json"}) {
            Set<ModKeys.Action> found = EnumSet.noneOf(ModKeys.Action.class);
            for (JsonElement drawer : layout(name).getAsJsonArray("mDrawerDataList")) {
                JsonObject properties = drawer.getAsJsonObject().getAsJsonObject("properties");
                if (!"mods".equals(properties.get("icon").getAsString())) continue;
                for (JsonElement sub : drawer.getAsJsonObject().getAsJsonArray("buttonProperties")) {
                    JsonObject button = sub.getAsJsonObject();
                    int[] keys = new int[4];
                    for (int i = 0; i < 4; i++) keys[i] = button.getAsJsonArray("keycodes").get(i).getAsInt();
                    ModKeys.Action action = ModKeys.actionOf(button.get("icon").getAsString(), keys);
                    assertTrue(name + ": " + button.get("name").getAsString(), action != null);
                    found.add(action);
                }
            }
            assertEquals(name, EnumSet.allOf(ModKeys.Action.class), found);
        }
    }

    @Test
    public void keyLinesAreReadLikeTheGameReadsThem() {
        Map<String, String> keys = options(
                "version:4790",
                "lang:tr_tr",
                "key_key.attack:key.mouse.left",
                "key_key.wi_zoom.zoom:key.keyboard.v",
                "key_gui.xaero_new_waypoint:key.keyboard.b\r",
                "key_key.custom:key.keyboard.x:SHIFT",
                "key_:nothing",
                "keyboard:nope",
                "key_key.wi_zoom.zoom:key.keyboard.c");
        assertEquals("key.mouse.left", keys.get("key.attack"));
        assertEquals("a later line wins", "key.keyboard.c", keys.get("key.wi_zoom.zoom"));
        assertEquals("key.keyboard.b", keys.get("gui.xaero_new_waypoint"));
        assertEquals("key.keyboard.x:SHIFT", keys.get("key.custom"));
        assertEquals(4, keys.size());
        assertTrue(ModKeys.keyBindings(null).isEmpty());
    }

    @Test
    public void aNewFileOfTheProjectsProfileHasTheLaunchersTable() throws IOException {
        // What FirstOptions writes from assets/key-bindings.json (ProLauncher2 crates/pl-mc/data).
        String json = new String(AtomicFiles.read(new File("src/main/assets/key-bindings.json"), 64 * 1024), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(Arrays.asList("version:4790", "lang:tr_tr", "tutorialStep:none"));
        lines.addAll(FirstOptions.keyLines(json));
        Map<String, String> keys = ModKeys.keyBindings(lines);
        assertArrayEquals(new int[]{C}, ModKeys.resolve(ModKeys.Action.ZOOM, keys).keys);
        assertArrayEquals(new int[]{X}, ModKeys.resolve(ModKeys.Action.BRIGHTNESS, keys).keys);
        assertArrayEquals(new int[]{CAPS}, ModKeys.resolve(ModKeys.Action.TALK, keys).keys);
        // Not in the table: the mods' own keys.
        assertArrayEquals(new int[]{M}, ModKeys.resolve(ModKeys.Action.MICROPHONE, keys).keys);
        assertArrayEquals(new int[]{B}, ModKeys.resolve(ModKeys.Action.WAYPOINT, keys).keys);
        assertArrayEquals(new int[]{Z}, ModKeys.resolve(ModKeys.Action.MAP, keys).keys);
    }

    @Test
    public void theTablesModDefaultsAreTheOnesTheButtonsFallBackTo() throws IOException {
        String json = new String(AtomicFiles.read(new File("src/main/assets/key-bindings.json"), 64 * 1024), StandardCharsets.UTF_8);
        Map<String, ModKeys.Action> byMapping = new HashMap<>();
        for (ModKeys.Action action : ModKeys.Action.values()) byMapping.put(action.mapping, action);
        int checked = 0;
        for (JsonElement entry : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("bindings")) {
            JsonObject binding = entry.getAsJsonObject();
            ModKeys.Action action = byMapping.get(binding.get("name").getAsString());
            if (action == null) continue;
            assertEquals(action.mapping, action.modDefault, binding.get("default").getAsString());
            // The layout stores the table's key, so the 0.1.6 button and a new file agree.
            assertEquals(action.mapping, (Integer) action.layoutKey, ModKeys.code(binding.get("key").getAsString()));
            checked++;
        }
        assertEquals("zoom, gamma and push-to-talk are in the table", 3, checked);
    }

    @Test
    public void aFileSavedBefore016KeepsTheModsKeys() {
        // 0.1.5 and older: the game saved the mods' defaults (zoom V, gamma G, no push-to-talk).
        Map<String, String> keys = options(
                "key_key.wi_zoom.zoom:key.keyboard.v",
                "key_key.gammautils.gammaToggle:key.keyboard.g",
                "key_key.mute_microphone:key.keyboard.m",
                "key_key.push_to_talk:key.keyboard.unknown",
                "key_gui.xaero_new_waypoint:key.keyboard.b",
                "key_gui.xaero_enlarge_map:key.keyboard.z");
        assertArrayEquals(new int[]{V}, ModKeys.resolve(ModKeys.Action.ZOOM, keys).keys);
        assertArrayEquals(new int[]{G}, ModKeys.resolve(ModKeys.Action.BRIGHTNESS, keys).keys);
        ModKeys.Binding talk = ModKeys.resolve(ModKeys.Action.TALK, keys);
        assertEquals(ModKeys.State.UNBOUND, talk.state);
        assertEquals(0, talk.keys.length);
    }

    @Test
    public void aKeyAnotherActionUsesInPlayIsAClash() {
        // A file the game saved before 0.1.6: zoom and Simple Voice Chat's menu both on V (pressing the
        // button opened the voice chat's set-up instead of zooming, seen on the emulator).
        Map<String, String> old = options(
                "key_key.wi_zoom.zoom:key.keyboard.v",
                "key_key.voice_chat:key.keyboard.v",
                "key_key.gammautils.gammaToggle:key.keyboard.g",
                "key_key.quickActions:key.keyboard.g",
                "key_key.debug.showChunkBorders:key.keyboard.g",
                "key_key.debug.dumpVersion:key.keyboard.v");
        ModKeys.Binding zoom = ModKeys.resolve(ModKeys.Action.ZOOM, old);
        assertEquals(ModKeys.State.CONFLICT, zoom.state);
        assertEquals(0, zoom.keys.length);
        // G is also Quick Actions (only on a server's dialog) and an F3 combination: no clash in play.
        assertArrayEquals(new int[]{G}, ModKeys.resolve(ModKeys.Action.BRIGHTNESS, old).keys);
        // Without its own line zoom is on its default V, which still clashes.
        assertEquals(ModKeys.State.CONFLICT, ModKeys.resolve(ModKeys.Action.ZOOM, options("key_key.voice_chat:key.keyboard.v")).state);
        // A modifier makes another key.
        assertEquals(ModKeys.State.READY, ModKeys.resolve(ModKeys.Action.ZOOM,
                options("key_key.wi_zoom.zoom:key.keyboard.v:SHIFT", "key_key.voice_chat:key.keyboard.v")).state);
    }

    @Test
    public void theLaunchersTableClashesWithNothingOnceTheGameSavedTheFile() throws IOException {
        // The table's keys beside every mapping of the Projects pack that shares them (KEYS1's
        // inventory): creative's hotbar, F3 combinations, Replay Mod's viewer, Quick Actions.
        String json = new String(AtomicFiles.read(new File("src/main/assets/key-bindings.json"), 64 * 1024), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(FirstOptions.keyLines(json));
        lines.addAll(Arrays.asList(
                "key_key.saveToolbarActivator:key.keyboard.c", "key_key.loadToolbarActivator:key.keyboard.x",
                "key_key.debug.crash:key.keyboard.c", "key_key.debug.copyLocation:key.keyboard.c",
                "key_key.debug.showHitboxes:key.keyboard.b", "key_key.debug.showChunkBorders:key.keyboard.g",
                "key_key.replaymod.marker:key.keyboard.m", "key_key.replaymod.lighting:key.keyboard.z",
                "key_key.replaymod.playeroverview:key.keyboard.b", "key_key.replaymod.clearkeyframes:key.keyboard.c",
                "key_key.replaymod.keyframerepository:key.keyboard.x", "key_key.quickActions:key.keyboard.g",
                "key_key.voice_chat:key.keyboard.v", "key_key.mute_microphone:key.keyboard.m",
                "key_gui.xaero_new_waypoint:key.keyboard.b", "key_gui.xaero_enlarge_map:key.keyboard.z",
                "key_key.hide_icons:key.keyboard.unknown", "key_key.whisper:key.keyboard.unknown"));
        Map<String, String> keys = ModKeys.keyBindings(lines);
        for (ModKeys.Action action : ModKeys.Action.values()) {
            assertEquals(action.name(), ModKeys.State.READY, ModKeys.resolve(action, keys).state);
        }
    }

    @Test
    public void withoutALineTheModsOwnKeyApplies() {
        Map<String, String> none = Collections.emptyMap();
        assertArrayEquals(new int[]{V}, ModKeys.resolve(ModKeys.Action.ZOOM, none).keys);
        assertArrayEquals(new int[]{G}, ModKeys.resolve(ModKeys.Action.BRIGHTNESS, none).keys);
        assertArrayEquals(new int[]{M}, ModKeys.resolve(ModKeys.Action.MICROPHONE, none).keys);
        assertArrayEquals(new int[]{B}, ModKeys.resolve(ModKeys.Action.WAYPOINT, none).keys);
        assertArrayEquals(new int[]{Z}, ModKeys.resolve(ModKeys.Action.MAP, none).keys);
        assertEquals(ModKeys.State.UNBOUND, ModKeys.resolve(ModKeys.Action.TALK, none).state);
        assertEquals(ModKeys.State.UNBOUND, ModKeys.resolve(ModKeys.Action.TALK, null).state);
        // An empty value is no value.
        assertArrayEquals(new int[]{V}, zoom("").keys);
    }

    @Test
    public void thePlayersOwnKeysAreSent() {
        assertArrayEquals(new int[]{342}, zoom("key.keyboard.left.alt").keys);
        assertArrayEquals(new int[]{ModKeys.MOUSE_BACK}, zoom("key.mouse.4").keys);
        assertArrayEquals(new int[]{ModKeys.MOUSE_FORWARD}, zoom("key.mouse.5").keys);
        assertArrayEquals(new int[]{ModKeys.MOUSE_MIDDLE}, zoom("key.mouse.middle").keys);
        assertArrayEquals(new int[]{ModKeys.MOUSE_LEFT}, zoom("key.mouse.left").keys);
        assertArrayEquals(new int[]{ModKeys.MOUSE_RIGHT}, zoom("key.mouse.right").keys);
        assertArrayEquals(new int[]{334}, zoom("key.keyboard.keypad.add").keys);
        assertArrayEquals(new int[]{C}, zoom(" key.keyboard.C ").keys);
        // Forge and NeoForge modifiers: the modifier goes down first.
        assertArrayEquals(new int[]{340, X}, zoom("key.keyboard.x:SHIFT").keys);
        assertArrayEquals(new int[]{341, X}, zoom("key.keyboard.x:CONTROL").keys);
        assertArrayEquals(new int[]{342, X}, zoom("key.keyboard.x:ALT").keys);
        assertArrayEquals(new int[]{X}, zoom("key.keyboard.x:NONE").keys);
        assertEquals(ModKeys.State.UNBOUND, zoom("key.keyboard.unknown:SHIFT").state);
    }

    @Test
    public void keysTheScreenCannotPressAreSaidSo() {
        for (String value : new String[]{"scancode.140", "key.mouse.6", "key.mouse.8", "47", "key.keyboard.hyper",
                "key.keyboard.x:META"}) {
            ModKeys.Binding binding = zoom(value);
            assertEquals(value, ModKeys.State.UNSENDABLE, binding.state);
            assertEquals(value, 0, binding.keys.length);
            assertEquals(value, value, binding.input);
        }
    }

    @Test
    public void aModTheGameDidNotLoadMakesItsKeysUnavailable() {
        Set<String> loaded = new HashSet<>(Arrays.asList("fabricloader", "minecraft", "wi_zoom", "voicechat"));
        Map<String, String> keys = options("key_key.push_to_talk:key.keyboard.caps.lock");
        assertEquals(ModKeys.State.READY, ModKeys.resolve(ModKeys.Action.ZOOM, keys, loaded).state);
        assertEquals(ModKeys.State.NO_MOD, ModKeys.resolve(ModKeys.Action.BRIGHTNESS, keys, loaded).state);
        assertEquals(ModKeys.State.READY, ModKeys.resolve(ModKeys.Action.TALK, keys, loaded).state);
        assertEquals(ModKeys.State.NO_MOD, ModKeys.resolve(ModKeys.Action.MAP, keys, loaded).state);
        // Vanilla: no mods at all.
        Set<String> vanilla = Collections.emptySet();
        for (ModKeys.Action action : ModKeys.Action.values()) {
            assertEquals(ModKeys.State.NO_MOD, ModKeys.resolve(action, keys, vanilla).state);
        }
        // Not known: the keys work as bound.
        assertEquals(ModKeys.State.READY, ModKeys.resolve(ModKeys.Action.BRIGHTNESS, keys, null).state);
    }

    @Test
    public void everyKeyName() {
        Object[][] table = {
                {"key.keyboard.a", 65}, {"key.keyboard.z", 90}, {"key.keyboard.0", 48}, {"key.keyboard.9", 57},
                {"key.keyboard.f1", 290}, {"key.keyboard.f12", 301}, {"key.keyboard.f25", 314},
                {"key.keyboard.keypad.0", 320}, {"key.keyboard.keypad.9", 329}, {"key.keyboard.keypad.decimal", 330},
                {"key.keyboard.keypad.enter", 335}, {"key.keyboard.keypad.equal", 336},
                {"key.keyboard.space", 32}, {"key.keyboard.apostrophe", 39}, {"key.keyboard.grave.accent", 96},
                {"key.keyboard.left.bracket", 91}, {"key.keyboard.right.bracket", 93}, {"key.keyboard.world.1", 161},
                {"key.keyboard.escape", 256}, {"key.keyboard.enter", 257}, {"key.keyboard.tab", 258},
                {"key.keyboard.page.down", 267}, {"key.keyboard.end", 269}, {"key.keyboard.caps.lock", 280},
                {"key.keyboard.print.screen", 283}, {"key.keyboard.pause", 284}, {"key.keyboard.left.shift", 340},
                {"key.keyboard.left.win", 343}, {"key.keyboard.right.alt", 346}, {"key.keyboard.right.win", 347},
        };
        for (Object[] row : table) assertEquals((String) row[0], (Integer) row[1], ModKeys.code((String) row[0]));
        assertNull(ModKeys.code(null));
        assertNull(ModKeys.code(ModKeys.UNBOUND_KEY));
        assertNull(ModKeys.code("scancode.12"));
        // Upstream's key table has no room for the menu key: it is not sent.
        assertNull(ModKeys.code("key.keyboard.menu"));
    }
}
