package gg.projects.mobile.controls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.content.AtomicFiles;

/**
 * M17: the one-tap fix for the key clashes of an options.txt saved before 0.1.6 (the owner's
 * decision on Decision 12), with the desktop's rule (KEYS2, pl-mc instance::keys): only the clashing
 * lines move to the launchers' table, only while both sides still have the mods' default key, and
 * every other byte stays.
 */
public class KeyFixTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final int C = 67, X = 88;

    private static String table() throws IOException {
        // assets/key-bindings.json: a copy of ProLauncher2's crates/pl-mc/data/key-bindings.json (KEYS1, KEYS2).
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

    @Test
    public void theTableGivesFourClashesAndNotPushToTalk() throws IOException {
        List<String> found = new ArrayList<>();
        for (KeyFix.Pair pair : pairs()) found.add(pair.mapping + " " + pair.modDefault + " -> " + pair.target + " (with " + pair.partner + ")");
        assertEquals(Arrays.asList(
                "key.wi_zoom.zoom key.keyboard.v -> key.keyboard.c (with key.voice_chat)",
                "key.gammautils.gammaToggle key.keyboard.g -> key.keyboard.x (with key.quickActions)",
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
                + "\"clashesWith\":\"key.drop\"},{\"name\":\"key.alone\",\"key\":\"key.keyboard.j\",\"default\":\"key.keyboard.l\"}]}";
        List<KeyFix.Pair> pairs = KeyFix.pairs(json);
        assertEquals(2, pairs.size());
        assertEquals("key.other", pairs.get(0).partner);
        assertEquals("key.drop", pairs.get(1).partner);
    }

    @Test
    public void aFileSavedBefore016GetsEveryClashFixed() throws IOException {
        List<KeyFix.Change> changes = plan(file("\n", OLD));
        assertEquals(Arrays.asList("key.wi_zoom.zoom=key.keyboard.c", "key.gammautils.gammaToggle=key.keyboard.x",
                "key.hide_icons=key.keyboard.unknown", "iris.keybind.reload=key.keyboard.unknown"), moves(changes));
        assertEquals("key.keyboard.v", changes.get(0).before);
        assertTrue(KeyFix.covers(changes, "key.wi_zoom.zoom"));
        assertFalse(KeyFix.covers(changes, "key.push_to_talk"));
    }

    @Test
    public void onlyTheClashingLinesChangeEveryOtherByteStays() throws IOException {
        String before = file("\r\n", OLD);
        List<KeyFix.Change> changes = plan(before);
        String after = KeyFix.apply(before, changes);
        String[] was = before.split("\r\n", -1);
        String[] now = after.split("\r\n", -1);
        assertEquals("no line added or lost, CRLF kept", was.length, now.length);
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < was.length; i++) if (!was[i].equals(now[i])) changed.add(now[i]);
        assertEquals(Arrays.asList("key_key.gammautils.gammaToggle:key.keyboard.x", "key_iris.keybind.reload:key.keyboard.unknown",
                "key_key.hide_icons:key.keyboard.unknown", "key_key.wi_zoom.zoom:key.keyboard.c"), changed);
        // The game's view afterwards: zoom and gamma are free to press, nothing clashes any more.
        Map<String, String> fixed = ModKeys.keyBindings(Arrays.asList(after.split("\r?\n")));
        assertArrayEquals(new int[]{C}, ModKeys.resolve(ModKeys.Action.ZOOM, fixed).keys);
        assertArrayEquals(new int[]{X}, ModKeys.resolve(ModKeys.Action.BRIGHTNESS, fixed).keys);
        assertTrue("a second fix finds nothing", plan(after).isEmpty());
        // Push-to-talk keeps its line (no clash; the brief: only the clashing lines).
        assertEquals("key.keyboard.unknown", fixed.get("key.push_to_talk"));
        // Undo gives the very same bytes back.
        assertEquals(before, KeyFix.undo(after, before, pairs()));
    }

    @Test
    public void aKeyThePlayerChoseNeverChanges() throws IOException {
        // Zoom moved to Z by the player: it stays, even though nothing else of the pair changed.
        List<KeyFix.Change> changes = plan(file("\n", with(OLD, "key.wi_zoom.zoom", "key.keyboard.z")));
        assertFalse(KeyFix.covers(changes, "key.wi_zoom.zoom"));
        assertEquals(3, changes.size());
        // A modifier or another spelling makes it another key: the player's too.
        assertFalse(KeyFix.covers(plan(file("\n", with(OLD, "key.wi_zoom.zoom", "key.keyboard.v:SHIFT"))), "key.wi_zoom.zoom"));
        assertFalse(KeyFix.covers(plan(file("\n", with(OLD, "key.wi_zoom.zoom", "key.keyboard.V"))), "key.wi_zoom.zoom"));
        // The voice menu moved off V: no clash on V any more, zoom stays on V.
        assertFalse(KeyFix.covers(plan(file("\n", with(OLD, "key.voice_chat", "key.keyboard.k"))), "key.wi_zoom.zoom"));
        // Quick Actions moved off G: gamma stays on G.
        assertFalse(KeyFix.covers(plan(file("\n", with(OLD, "key.quickActions", "key.keyboard.j"))), "key.gammautils.gammaToggle"));
    }

    @Test
    public void aMappingWithoutALineClashesWithNothing() throws IOException {
        // Simple Voice Chat switched off when the game saved: its lines are gone, zoom on V is fine,
        // and its own "hide icons" (no line) is not touched either.
        List<KeyFix.Change> changes = plan(file("\n", without(without(without(OLD, "key.voice_chat"), "key.hide_icons"), "key.mute_microphone")));
        assertFalse(KeyFix.covers(changes, "key.wi_zoom.zoom"));
        assertFalse(KeyFix.covers(changes, "key.hide_icons"));
        // WI Zoom not loaded (no zoom line) while the voice menu is on V: nothing clashes in play.
        assertFalse(KeyFix.covers(plan(file("\n", without(OLD, "key.wi_zoom.zoom"))), "key.wi_zoom.zoom"));
        // The desktop's rule: both sides must be written, the game's own Quick Actions too.
        assertFalse(KeyFix.covers(plan(file("\n", without(OLD, "key.quickActions"))), "key.gammautils.gammaToggle"));
    }

    @Test
    public void aTakenNewKeyIsNotOffered() throws IOException {
        // The player put "drop" on C: moving zoom there would only make a new clash.
        String[] lines = Arrays.copyOf(OLD, OLD.length + 1);
        lines[OLD.length] = "key_key.drop:key.keyboard.c";
        assertFalse(KeyFix.covers(plan(file("\n", lines)), "key.wi_zoom.zoom"));
        // Creative's hotbar keys (C and X) and F3 combinations are no clash in play: the fix goes ahead.
        assertTrue(KeyFix.covers(plan(file("\n", OLD)), "key.wi_zoom.zoom"));
        // F3 itself acts in play (the desktop's acts_in_play): it would count.
        lines[OLD.length] = "key_key.debug.overlay:key.keyboard.c";
        assertFalse(KeyFix.covers(plan(file("\n", lines)), "key.wi_zoom.zoom"));
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
        assertTrue(undone.endsWith("narrator:0\n"));
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
