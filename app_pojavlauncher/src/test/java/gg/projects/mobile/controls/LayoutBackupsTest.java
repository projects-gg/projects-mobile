package gg.projects.mobile.controls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import gg.projects.mobile.content.AtomicFiles;

/**
 * CTRL-SAVE: the controls editor's "Kaydet" writes the layout in use in place and keeps the version it replaced in
 * controlmap/.backups (the newest 5 of each layout), so "Geri al" can bring it back. Before, "Kaydet" asked for a file
 * name and wrote it without a copy; a moved or resized key was not even counted as a change.
 */
public class LayoutBackupsTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** 1 November 2026 21:30:00 UTC. */
    private static final long NOW = 1793568600000L;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(File file) throws IOException {
        return new String(AtomicFiles.read(file, 1 << 20), StandardCharsets.UTF_8);
    }

    @Test
    public void aSaveWritesTheLayoutInPlaceAndKeepsTheVersionBefore() throws IOException {
        File controlmap = temp.newFolder("controlmap");
        File layout = new File(controlmap, "default.json");
        AtomicFiles.write(layout, bytes("{\"old\":1}"));

        LayoutBackups.Saved saved = LayoutBackups.save(layout, bytes("{\"new\":2}"), NOW);

        assertEquals("{\"new\":2}", read(layout));
        assertNotNull(saved.backup);
        assertEquals(new File(controlmap, LayoutBackups.DIR), saved.backup.getParentFile());
        assertEquals("default-20261101-213000.json", saved.backup.getName());
        assertEquals("{\"old\":1}", read(saved.backup));
        assertTrue(saved.undoable());
        assertFalse(saved.created);
        assertFalse(saved.unchanged);
    }

    @Test
    public void undoBringsTheVersionBeforeBackAndUsesUpItsCopy() throws IOException {
        File layout = new File(temp.newFolder("controlmap"), "default.json");
        AtomicFiles.write(layout, bytes("{\"old\":1}"));
        LayoutBackups.Saved saved = LayoutBackups.save(layout, bytes("{\"new\":2}"), NOW);

        assertTrue(LayoutBackups.undo(saved));
        assertEquals("{\"old\":1}", read(layout));
        assertFalse(saved.backup.exists());
        // A second "Geri al" has nothing left to bring back and changes nothing.
        assertFalse(LayoutBackups.undo(saved));
        assertEquals("{\"old\":1}", read(layout));
    }

    @Test
    public void theSameBytesWriteNothingAndOfferNoUndo() throws IOException {
        File controlmap = temp.newFolder("controlmap");
        File layout = new File(controlmap, "default.json");
        AtomicFiles.write(layout, bytes("{\"same\":1}"));
        long modified = layout.lastModified();

        LayoutBackups.Saved saved = LayoutBackups.save(layout, bytes("{\"same\":1}"), NOW);

        assertTrue(saved.unchanged);
        assertNull(saved.backup);
        assertFalse(saved.undoable());
        assertFalse(new File(controlmap, LayoutBackups.DIR).exists());
        assertEquals(modified, layout.lastModified());
        assertFalse(LayoutBackups.undo(saved));
    }

    @Test
    public void aLayoutTheSaveMadeGoesAgainOnUndo() throws IOException {
        // A profile's own control file that was missing: the save makes it, "Geri al" removes it again.
        File layout = new File(temp.newFolder("controlmap"), "pvp.json");

        LayoutBackups.Saved saved = LayoutBackups.save(layout, bytes("{\"new\":2}"), NOW);

        assertTrue(saved.created);
        assertNull(saved.backup);
        assertTrue(saved.undoable());
        assertEquals("{\"new\":2}", read(layout));
        assertTrue(LayoutBackups.undo(saved));
        assertFalse(layout.exists());
    }

    @Test
    public void copiesOfTheSameSecondCountUp() throws IOException {
        File controlmap = temp.newFolder("controlmap");
        File layout = new File(controlmap, "default.json");
        AtomicFiles.write(layout, bytes("v1"));

        File first = LayoutBackups.save(layout, bytes("v2"), NOW).backup;
        File second = LayoutBackups.save(layout, bytes("v3"), NOW + 400).backup;
        File third = LayoutBackups.save(layout, bytes("v4"), NOW + 900).backup;

        assertEquals("default-20261101-213000.json", first.getName());
        assertEquals("default-20261101-213000-2.json", second.getName());
        assertEquals("default-20261101-213000-3.json", third.getName());
        assertEquals("v1", read(first));
        assertEquals("v2", read(second));
        assertEquals("v3", read(third));
        // Each "Geri al" brings back the version its own save replaced.
        assertTrue(LayoutBackups.undo(new LayoutBackups.Saved(layout, third, false, false)));
        assertEquals("v3", read(layout));
    }

    @Test
    public void theNewestFiveCopiesOfALayoutStayAndOtherLayoutsKeepTheirs() throws IOException {
        File controlmap = temp.newFolder("controlmap");
        File layout = new File(controlmap, "default.json");
        File other = new File(controlmap, "default_pvp.json");
        AtomicFiles.write(layout, bytes("v0"));
        AtomicFiles.write(other, bytes("p0"));
        LayoutBackups.save(other, bytes("p1"), NOW);

        for (int i = 1; i <= 8; i++) LayoutBackups.save(layout, bytes("v" + i), NOW + i * 60_000L);

        File dir = new File(controlmap, LayoutBackups.DIR);
        String[] names = dir.list();
        assertNotNull(names);
        Arrays.sort(names);
        assertArrayEquals(new String[]{
                "default-20261101-213400.json", "default-20261101-213500.json", "default-20261101-213600.json",
                "default-20261101-213700.json", "default-20261101-213800.json", "default_pvp-20261101-213000.json"}, names);
        // The newest copy is the version right before the last save.
        assertEquals("v7", read(new File(dir, "default-20261101-213800.json")));
        assertEquals("v8", read(layout));
        assertEquals("p0", read(new File(dir, "default_pvp-20261101-213000.json")));
    }

    @Test
    public void copiesHideFromTheEditorsFileLists() throws IOException {
        // The editor's "Yükle" and "Varsayılan kontrolleri seç" list .json files and skip dot folders (FileListView).
        File layout = new File(temp.newFolder("controlmap"), "default.json");
        AtomicFiles.write(layout, bytes("v1"));

        File backup = LayoutBackups.save(layout, bytes("v2"), NOW).backup;

        assertTrue(backup.getParentFile().getName().startsWith("."));
        assertEquals(layout.getParentFile().getAbsoluteFile(), backup.getParentFile().getParentFile().getAbsoluteFile());
        String[] listed = layout.getParentFile().list((dir, name) -> name.endsWith(".json"));
        assertNotNull(listed);
        assertArrayEquals(new String[]{"default.json"}, listed);
    }

    @Test
    public void namesWithoutJsonAndRegexCharactersAreSafe() throws IOException {
        File controlmap = temp.newFolder("controlmap");
        File layout = new File(controlmap, "my (new) layout+1.json");
        AtomicFiles.write(layout, bytes("a"));
        for (int i = 1; i <= 7; i++) LayoutBackups.save(layout, bytes("a" + i), NOW + i * 1000L);
        String[] names = new File(controlmap, LayoutBackups.DIR).list();
        assertNotNull(names);
        assertEquals(LayoutBackups.KEPT, names.length);
        assertEquals("layout", LayoutBackups.baseName(new File("layout")));
        assertEquals("default", LayoutBackups.baseName(new File("default.JSON")));
    }
}
