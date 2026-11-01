package gg.projects.mobile.controls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/**
 * CTRL-SAVE: what leaving the controls editor does, when a finger moves a key, and when a loaded layout is a change.
 * A player who resized keys and left lost them: upstream asked "Çıkılsın mı?" with no save, because only an added key
 * counted as a change.
 */
public class EditorRulesTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void leavingWithAChangeAsksOnceAndWithoutOneLeavesAtOnce() {
        assertEquals(EditorRules.Leave.ASK, EditorRules.leave(true));
        assertEquals(EditorRules.Leave.NOW, EditorRules.leave(false));
    }

    @Test
    public void aKeyMovesOnlyOnceTheFingerPassedTheSlop() {
        assertFalse(EditorRules.dragged(0f, 0f, 8f));
        assertFalse(EditorRules.dragged(3f, -2f, 8f));
        assertFalse(EditorRules.dragged(8f, -8f, 8f));
        assertTrue(EditorRules.dragged(8.5f, 0f, 8f));
        assertTrue(EditorRules.dragged(0f, -12f, 8f));
    }

    @Test
    public void loadingTheLayoutInUseIsNoChangeButAnotherFileIs() throws IOException {
        File controlmap = temp.newFolder("controlmap");
        File active = new File(controlmap, "default.json");
        File other = new File(controlmap, "new_default.json");
        String dotted = controlmap.getPath() + File.separator + "." + File.separator + "default.json";

        assertFalse(EditorRules.loadedAnother(active.getPath(), active.getPath()));
        assertFalse(EditorRules.loadedAnother(dotted, active.getPath()));
        assertTrue(EditorRules.loadedAnother(other.getPath(), active.getPath()));
        assertTrue(EditorRules.sameFile(null, null));
        assertFalse(EditorRules.sameFile(active.getPath(), null));
    }
}
