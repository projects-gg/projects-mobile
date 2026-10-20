package gg.projects.mobile.controls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.content.AtomicFiles;

/**
 * M17: the one-tap fix for the key clashes of an options.txt saved before 0.1.6 (the owner's
 * decision on Decision 12), with the desktop's rule (KEYS2, pl-mc instance::keys): only the clashing
 * lines move to the launchers' table, only while both sides still have the mods' default key, and
 * every other byte stays. Push-to-talk without a key gets the table's Caps Lock in the same fix (the
 * lead's decision B, both launchers).
 */
public class KeyFixTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final int C = 67, X = 88, CAPS = 280;

    private static String table() throws IOException {
        // assets/key-bindings.json: a copy of ProLauncher2's crates/pl-mc/data/key-bindings.json (KEYS1, KEYS2, KEYS3).
        return new String(AtomicFiles.read(new File("src/main/assets/key-bindings.json"), 64 * 1024), StandardCharsets.UTF_8);
    }

    private static List<KeyFix.Pair> pairs() throws IOException {
        return KeyFix.pairs(table());
    }

    /** A Projects options.txt the game saved before 0.1.6: the mods' own defaults (KEYS1's inventory). */
    private static final String[] OLD = {
            "version:4790",
            "lang:tr_tr",
            "key_key.quickActions:key.keyboard.g",
            "key_key.saveToolbarActivator:key.keyboard.c",
            "key_key.loadToolbarActivator:key.keyboard.x",
            "key_key.debug.dumpVersion:key.keyboard.v",
            "key_key.gammautils.gammaToggle:key.keyboard.g",
            "key_key.gammautils.nightVisionToggle:key.keyboard.h",
            "key_iris.keybind.reload:key.keyboard.r",
            "key_iris.keybind.toggleShaders:key.keyboard.k",
            "key_key.mute_microphone:key.keyboard.m",
            "key_key.hide_icons:key.keyboard.h",
            "key_key.voice_chat:key.keyboard.v",
            "key_key.push_to_talk:key.keyboard.unknown",
            "key_key.wi_zoom.zoom:key.keyboard.v",
            "key_gui.xaero_quick_eat:key.keyboard.r",
            "key_gui.xaero_new_waypoint:key.keyboard.b",
            "soundCategory_master:1.0",
    };

    private static String file(String eol, String... lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append(eol);
        return out.toString();
    }

    private static List<KeyFix.Change> plan(String content) throws IOException {
        return KeyFix.plan(KeyFix.bindings(content), pairs());
    }

    private static List<String> moves(List<KeyFix.Change> changes) {
        List<String> out = new ArrayList<>();
        for (KeyFix.Change change : changes) out.add(change.mapping + "=" + change.after);
        return out;
    }

    private static String[] with(String[] lines, String mapping, String value) {
        String[] out = lines.clone();
        for (int i = 0; i < out.length; i++) if (out[i].startsWith("key_" + mapping + ":")) out[i] = "key_" + mapping + ":" + value;
        return out;
    }

    private static String[] without(String[] lines, String mapping) {
        List<String> out = new ArrayList<>();
        for (String line : lines) if (!line.startsWith("key_" + mapping + ":")) out.add(line);
        return out.toArray(new String[0]);
    }

    private static String[] plus(String[] lines, String line) {
        String[] out = Arrays.copyOf(lines, lines.length + 1);
        out[lines.length] = line;
        return out;
    }

    @Test
    public void theTableGivesFourClashesAndPushToTalksKey() throws IOException {
        List<String> found = new ArrayList<>();
        for (KeyFix.Pair pair : pairs()) {
            found.add(pair.mapping + " " + pair.modDefault + " -> " + pair.target + (pair.binds() ? " (while unbound)" : " (with " + pair.partner + ")"));
        }
        assertEquals(Arrays.asList(
                "key.wi_zoom.zoom key.keyboard.v -> key.keyboard.c (with key.voice_chat)",
                "key.gammautils.gammaToggle key.keyboard.g -> key.keyboard.x (with key.quickActions)",
                "key.push_to_talk key.keyboard.unknown -> key.keyboard.caps.lock (while unbound)",
                "key.hide_icons key.keyboard.h -> key.keyboard.unknown (with key.gammautils.nightVisionToggle)",
                "iris.keybind.reload key.keyboard.r -> key.keyboard.unknown (with gui.xaero_quick_eat)"), found);
        assertTrue(KeyFix.pairs("not json").isEmpty());
        assertTrue(KeyFix.pairs("{\"bindings\":{}}").isEmpty());
        assertTrue(KeyFix.pairs(null).isEmpty());
    }

    @Test
    public void theTablesOwnClashesWithWins() {
        // KEYS2 names the partner in the shared table; the built-in list only fills in for an older copy.
        String json = "{\"bindings\":[{\"name\":\"key.wi_zoom.zoom\",\"key\":\"key.keyboard.c\",\"default\":\"key.keyboard.v\","
                + "\"clashesWith\":\"key.other\"},{\"name\":\"key.new\",\"key\":\"key.keyboard.j\",\"default\":\"key.keyboard.l\","
                + "\"clashesWith\":\"key.drop\"},{\"name\":\"key.alone\",\"key\":\"key.keyboard.j\",\"default\":\"key.keyboard.l\"},"
                + "{\"name\":\"key.silent\",\"key\":\"key.keyboard.j\",\"default\":\"key.keyboard.unknown\",\"clashesWith\":\"key.mute\"}]}";
        List<KeyFix.Pair> pairs = KeyFix.pairs(json);
        assertEquals("a mapping without a key never clashes", 2, pairs.size());
        assertEquals("key.other", pairs.get(0).partner);
        assertEquals("key.drop", pairs.get(1).partner);
    }

    @Test
    public void theTablesOwnBindIfUnboundWins() {
        // KEYS3 marks push-to-talk in the shared table; the built-in list only fills in for an older copy.
        String json = "{\"bindings\":["
                + "{\"name\":\"key.push_to_talk\",\"key\":\"key.keyboard.caps.lock\",\"default\":\"key.keyboard.unknown\",\"bindIfUnbound\":false},"
                + "{\"name\":\"key.whisper\",\"key\":\"key.keyboard.j\",\"default\":\"key.keyboard.unknown\",\"bindIfUnbound\":true},"
                + "{\"name\":\"key.nothing\",\"key\":\"key.keyboard.unknown\",\"default\":\"key.keyboard.unknown\",\"bindIfUnbound\":true},"
                + "{\"name\":\"key.odd\",\"key\":\"key.keyboard.l\",\"default\":\"key.keyboard.unknown\",\"bindIfUnbound\":\"yes\"}]}";
        List<KeyFix.Pair> pairs = KeyFix.pairs(json);
        assertEquals(1, pairs.size());
        assertEquals("key.whisper", pairs.get(0).mapping);
        assertTrue(pairs.get(0).binds());
        assertEquals(ModKeys.UNBOUND_KEY, pairs.get(0).modDefault);
        // A copy without the field: push-to-talk still gets its key (the built-in list).
        List<KeyFix.Pair> older = KeyFix.pairs("{\"bindings\":[{\"name\":\"key.push_to_talk\",\"key\":\"key.keyboard.caps.lock\","
                + "\"default\":\"key.keyboard.unknown\"}]}");
        assertEquals(1, older.size());
        assertTrue(older.get(0).binds());
        assertEquals("key.keyboard.caps.lock", older.get(0).target);
    }

    @Test
    public void theBuiltInListsSayWhatTheTableSays() throws IOException {
        // The fallback for a copy of the table without the fields names the same clashes and binds.
        int clashes = 0, binds = 0;
        for (JsonElement entry : JsonParser.parseString(table()).getAsJsonObject().getAsJsonArray("bindings")) {
            JsonObject binding = entry.getAsJsonObject();
            String name = binding.get("name").getAsString();
            if (binding.has("clashesWith")) {
                assertEquals(name, binding.get("clashesWith").getAsString(), KeyFix.builtInPartner(name));
                clashes++;
            } else {
                assertNull(name, KeyFix.builtInPartner(name));
            }
            if (binding.has("bindIfUnbound")) assertEquals(name, binding.get("bindIfUnbound").getAsBoolean(), KeyFix.builtInBind(name));
            if (KeyFix.builtInBind(name)) binds++;
        }
        assertEquals(4, clashes);
        assertEquals("push-to-talk", 1, binds);
    }

    @Test
    public void aFileSavedBefore016GetsEveryClashFixedAndATalkKey() throws IOException {
        List<KeyFix.Change> changes = plan(file("\n", OLD));
        assertEquals(Arrays.asList("key.wi_zoom.zoom=key.keyboard.c", "key.gammautils.gammaToggle=key.keyboard.x",
                "key.push_to_talk=key.keyboard.caps.lock", "key.hide_icons=key.keyboard.unknown",
                "iris.keybind.reload=key.keyboard.unknown"), moves(changes));
        assertEquals("key.keyboard.v", changes.get(0).before);
        assertTrue(KeyFix.covers(changes, "key.wi_zoom.zoom"));
        assertTrue(KeyFix.covers(changes, "key.push_to_talk"));
        assertTrue(changes.get(0).clash);
        assertFalse("push-to-talk is no clash", changes.get(2).clash);
        assertTrue(KeyFix.hasClash(changes));
    }

    @Test
    public void onlyTheFixedLinesChangeEveryOtherByteStays() throws IOException {
        String before = file("\r\n", OLD);
        List<KeyFix.Change> changes = plan(before);
        String after = KeyFix.apply(before, changes);
        String[] was = before.split("\r\n", -1);
        String[] now = after.split("\r\n", -1);
        assertEquals("no line added or lost, CRLF kept", was.length, now.length);
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < was.length; i++) if (!was[i].equals(now[i])) changed.add(now[i]);
        assertEquals(Arrays.asList("key_key.gammautils.gammaToggle:key.keyboard.x", "key_iris.keybind.reload:key.keyboard.unknown",
                "key_key.hide_icons:key.keyboard.unknown", "key_key.push_to_talk:key.keyboard.caps.lock",
                "key_key.wi_zoom.zoom:key.keyboard.c"), changed);
        // The game's view afterwards: zoom, gamma and talk are free to press, nothing is left to fix.
        Map<String, String> fixed = ModKeys.keyBindings(Arrays.asList(after.split("\r?\n")));
        assertArrayEquals(new int[]{C}, ModKeys.resolve(ModKeys.Action.ZOOM, fixed).keys);
        assertArrayEquals(new int[]{X}, ModKeys.resolve(ModKeys.Action.BRIGHTNESS, fixed).keys);
        assertArrayEquals(new int[]{CAPS}, ModKeys.resolve(ModKeys.Action.TALK, fixed).keys);
        assertTrue("a second fix finds nothing", plan(after).isEmpty());
        // Undo gives the very same bytes back.
        assertEquals(before, KeyFix.undo(after, before, pairs()));
    }

    @Test
    public void inAnOldFileEveryModlarKeyTheFixSolvesOffersIt() throws IOException {
        // The game's MODLAR panel on a file saved before 0.1.6: zoom (V, the voice chat's menu) and
        // brightness (G, Quick Actions: the lead's decision A) clash, talk has no key (decision B), and
        // the one fix covers all three. Microphone, waypoint and map work as they are.
        Map<String, String> old = ModKeys.keyBindings(Arrays.asList(OLD));
        List<KeyFix.Change> changes = KeyFix.plan(old, pairs());
        Map<ModKeys.Action, String> seen = new EnumMap<>(ModKeys.Action.class);
        for (ModKeys.Action action : ModKeys.Action.values()) {
            seen.put(action, ModKeys.resolve(action, old).state + (KeyFix.covers(changes, action.mapping) ? " + fix" : ""));
        }
        assertEquals("CONFLICT + fix", seen.get(ModKeys.Action.ZOOM));
        assertEquals("CONFLICT + fix", seen.get(ModKeys.Action.BRIGHTNESS));
        assertEquals("UNBOUND + fix", seen.get(ModKeys.Action.TALK));
        assertEquals("READY", seen.get(ModKeys.Action.MICROPHONE));
        assertEquals("READY", seen.get(ModKeys.Action.WAYPOINT));
        assertEquals("READY", seen.get(ModKeys.Action.MAP));
    }

    @Test
    public void aKeyThePlayerChoseNeverChanges() throws IOException {
        // Zoom moved to Z by the player: it stays, even though nothing else of the pair changed.
        List<KeyFix.Change> changes = plan(file("\n", with(OLD, "key.wi_zoom.zoom", "key.keyboard.z")));
        assertFalse(KeyFix.covers(changes, "key.wi_zoom.zoom"));
        assertEquals("three clashes and the talk key", 4, changes.size());
        // A modifier or another spelling makes it another key: the player's too.
        assertFalse(KeyFix.covers(plan(file("\n", with(OLD, "key.wi_zoom.zoom", "key.keyboard.v:SHIFT"))), "key.wi_zoom.zoom"));
        assertFalse(KeyFix.covers(plan(file("\n", with(OLD, "key.wi_zoom.zoom", "key.keyboard.V"))), "key.wi_zoom.zoom"));
        // The voice menu moved off V: no clash on V any more, zoom stays on V.
        assertFalse(KeyFix.covers(plan(file("\n", with(OLD, "key.voice_chat", "key.keyboard.k"))), "key.wi_zoom.zoom"));
        // Quick Actions moved off G: gamma stays on G.
        assertFalse(KeyFix.covers(plan(file("\n", with(OLD, "key.quickActions", "key.keyboard.j"))), "key.gammautils.gammaToggle"));
        // A talk key the player chose stays.
        assertFalse(KeyFix.covers(plan(file("\n", with(OLD, "key.push_to_talk", "key.keyboard.k"))), "key.push_to_talk"));
    }

    @Test
    public void pushToTalkGetsCapsLockOnlyWhileItHasNoKey() throws IOException {
        // The clashes sorted out by the player in the game's Controls: only the talk key is left to fix.
        String[] sorted = with(with(with(with(OLD, "key.wi_zoom.zoom", "key.keyboard.z"), "key.gammautils.gammaToggle", "key.keyboard.y"),
                "key.hide_icons", "key.keyboard.j"), "iris.keybind.reload", "key.keyboard.unknown");
        List<KeyFix.Change> changes = plan(file("\n", sorted));
        assertEquals(Collections.singletonList("key.push_to_talk=key.keyboard.caps.lock"), moves(changes));
        assertFalse("the offer says push-to-talk, not clashes", KeyFix.hasClash(changes));
        // No line: Simple Voice Chat was not loaded when the game saved; nothing is added.
        assertTrue(plan(file("\n", without(sorted, "key.push_to_talk"))).isEmpty());
        // Exactly unbound: a modifier or another spelling is the player's.
        assertTrue(plan(file("\n", with(sorted, "key.push_to_talk", "key.keyboard.unknown:SHIFT"))).isEmpty());
        assertTrue(plan(file("\n", with(sorted, "key.push_to_talk", "key.keyboard.Unknown"))).isEmpty());
        // Caps Lock on an action that works in play: binding it would make a new clash.
        assertTrue(plan(file("\n", plus(sorted, "key_key.sprint:key.keyboard.caps.lock"))).isEmpty());
        // On an F3 combination it is still free in play.
        assertEquals(1, plan(file("\n", plus(sorted, "key_key.debug.reloadChunk:key.keyboard.caps.lock"))).size());
        // The game's view afterwards: KONUŞ sends Caps Lock.
        String after = KeyFix.apply(file("\n", sorted), changes);
        assertArrayEquals(new int[]{CAPS}, ModKeys.resolve(ModKeys.Action.TALK, ModKeys.keyBindings(Arrays.asList(after.split("\n")))).keys);
    }

    @Test
    public void aMappingWithoutALineClashesWithNothing() throws IOException {
        // Simple Voice Chat switched off when the game saved: its lines are gone, zoom on V is fine,
        // and its own "hide icons" and push-to-talk (no lines) are not touched either.
        List<KeyFix.Change> changes = plan(file("\n", without(without(without(without(OLD, "key.voice_chat"), "key.hide_icons"),
                "key.mute_microphone"), "key.push_to_talk")));
        assertFalse(KeyFix.covers(changes, "key.wi_zoom.zoom"));
        assertFalse(KeyFix.covers(changes, "key.hide_icons"));
        assertFalse(KeyFix.covers(changes, "key.push_to_talk"));
        // WI Zoom not loaded (no zoom line) while the voice menu is on V: nothing clashes in play.
        assertFalse(KeyFix.covers(plan(file("\n", without(OLD, "key.wi_zoom.zoom"))), "key.wi_zoom.zoom"));
        // The desktop's rule: both sides must be written, the game's own Quick Actions too.
        assertFalse(KeyFix.covers(plan(file("\n", without(OLD, "key.quickActions"))), "key.gammautils.gammaToggle"));
    }

    @Test
    public void aTakenNewKeyIsNotOffered() throws IOException {
        // The player put "drop" on C: moving zoom there would only make a new clash.
        assertFalse(KeyFix.covers(plan(file("\n", plus(OLD, "key_key.drop:key.keyboard.c"))), "key.wi_zoom.zoom"));
        // Creative's hotbar keys (C and X) and F3 combinations are no clash in play: the fix goes ahead.
        assertTrue(KeyFix.covers(plan(file("\n", OLD)), "key.wi_zoom.zoom"));
        // F3 itself acts in play (the desktop's acts_in_play): it would count.
        assertFalse(KeyFix.covers(plan(file("\n", plus(OLD, "key_key.debug.overlay:key.keyboard.c"))), "key.wi_zoom.zoom"));
        assertTrue(KeyFix.actsInPlay("key.replaymod.settings"));
        assertFalse(KeyFix.actsInPlay("key.replaymod.marker"));
        assertFalse(KeyFix.actsInPlay("key.spectatorOutlines"));
        assertTrue(KeyFix.actsInPlay("key.quickActions"));
    }

    @Test
    public void undoAfterTheGameSavedMovesBackOnlyTheFixedLines() throws IOException {
        String before = file("\n", OLD);
        String after = KeyFix.apply(before, plan(before));
        // The player set zoom to B in the game afterwards, and the game saved: undo keeps that.
        String played = after.replace("key_key.wi_zoom.zoom:key.keyboard.c", "key_key.wi_zoom.zoom:key.keyboard.b") + "narrator:0\n";
        String undone = KeyFix.undo(played, before, pairs());
        assertTrue(undone.contains("key_key.wi_zoom.zoom:key.keyboard.b\n"));
        assertTrue(undone.contains("key_key.gammautils.gammaToggle:key.keyboard.g\n"));
        assertTrue(undone.contains("key_key.hide_icons:key.keyboard.h\n"));
        assertTrue("push-to-talk goes back too", undone.contains("key_key.push_to_talk:key.keyboard.unknown\n"));
        assertTrue(undone.endsWith("narrator:0\n"));
        // A talk key the player chose after the fix stays.
        String rebound = played.replace("key_key.push_to_talk:key.keyboard.caps.lock", "key_key.push_to_talk:key.keyboard.k");
        assertTrue(KeyFix.undo(rebound, before, pairs()).contains("key_key.push_to_talk:key.keyboard.k\n"));
    }

    @Test
    public void everyCopyOfAClashingLineMovesAndOtherValuesStay() throws IOException {
        // The game reads the last line; a file with two copies gets both moved, another value stays.
        String before = "key_key.voice_chat:key.keyboard.v\nkey_key.wi_zoom.zoom:key.keyboard.v\nkey_key.wi_zoom.zoom:key.keyboard.v";
        String after = KeyFix.apply(before, plan(before));
        assertEquals("key_key.voice_chat:key.keyboard.v\nkey_key.wi_zoom.zoom:key.keyboard.c\nkey_key.wi_zoom.zoom:key.keyboard.c", after);
        String mixed = "key_key.wi_zoom.zoom:key.keyboard.n\nkey_key.voice_chat:key.keyboard.v\nkey_key.wi_zoom.zoom:key.keyboard.v\n";
        assertEquals("key_key.wi_zoom.zoom:key.keyboard.n\nkey_key.voice_chat:key.keyboard.v\nkey_key.wi_zoom.zoom:key.keyboard.c\n",
                KeyFix.apply(mixed, plan(mixed)));
    }

    @Test
    public void aNewFileOfTheProjectsProfileNeedsNoFix() throws IOException {
        // What 0.1.6 writes into a new options.txt, after the game saved it with every mapping.
        List<String> lines = new ArrayList<>(Arrays.asList(OLD));
        for (String line : gg.projects.mobile.launch.FirstOptions.keyLines(table())) {
            String key = line.substring(0, line.indexOf(':'));
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(key + ":")) {
                    lines.set(i, line);
                    replaced = true;
                }
            }
            if (!replaced) lines.add(line);
        }
        assertTrue(plan(file("\n", lines.toArray(new String[0]))).isEmpty());
        assertTrue(plan("").isEmpty());
    }

    @Test
    public void backupsAreNamedByTimeAndTheNewestFiveStay() throws IOException {
        File dir = temp.newFolder("options-backups");
        long at = 1792444500000L; // 2026-10-19 21:15:00 UTC
        assertEquals("options-20261019-211500.txt", KeyFixer.newBackupName(dir, at));
        new File(dir, "options-20261019-211500.txt").createNewFile();
        assertEquals("options-20261019-211500-2.txt", KeyFixer.newBackupName(dir, at));
        new File(dir, "options-20261019-211500-2.txt").createNewFile();
        assertEquals("options-20261019-211500-3.txt", KeyFixer.newBackupName(dir, at));
        for (String name : new String[]{"options-20261018-100000.txt", "options-20261018-090000.txt", "options-20261019-080000.txt",
                "options-20261017-235959.txt", "notes.txt"}) {
            new File(dir, name).createNewFile();
        }
        KeyFixer.prune(dir);
        List<String> left = new ArrayList<>(Arrays.asList(dir.list()));
        java.util.Collections.sort(left);
        assertEquals(Arrays.asList("notes.txt", "options-20261018-090000.txt", "options-20261018-100000.txt",
                "options-20261019-080000.txt", "options-20261019-211500-2.txt", "options-20261019-211500.txt"), left);
        assertEquals(new File(new File(temp.getRoot(), ".projects"), "options-backups"), KeyFixer.backupDir(temp.getRoot()));
    }
}
