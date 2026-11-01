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

    /**
     * APK-REL: on a phone held sideways (2400 x 1080 px at 2.625, 914 dp) the default layout's drawer keys reach
     * 410 dp (1076 px) from the left in the bar's band; the centred bar (420 px wide) started at 990 px, under
     * "MİKROFON". It now starts right after the keys, as near to the centre as it can.
     */
    @Test
    public void theBarStaysCentredUnlessKeysAreThereThenTakesTheNearestFreePlace() {
        int[] none = new int[0];
        assertEquals(990, EditorRules.barLeft(2400, 420, none, none, 21, 21));
        // The default layout's keys on a phone: right after the last one (1076 + 21).
        assertEquals(1097, EditorRules.barLeft(2400, 420, new int[] {31, 540, 908}, new int[] {157, 709, 1076}, 21, 21));
        // On a tablet the keys end far from the centre: centred.
        assertEquals(1070, EditorRules.barLeft(2560, 420, new int[] {24, 412, 692}, new int[] {120, 552, 820}, 16, 16));
        // A key under the bar's right end: the free place before it is nearer than the one after it.
        assertEquals(1300 - 21 - 420, EditorRules.barLeft(2400, 420, new int[] {1300}, new int[] {1400}, 21, 21));
        // Keys across the whole band: no free place, the bar stays centred (the keys are drawn over it).
        assertEquals(990, EditorRules.barLeft(2400, 420, new int[] {0}, new int[] {2400}, 21, 21));
        // A place after the keys that would run off the screen is no place: centred again.
        assertEquals(990, EditorRules.barLeft(2400, 420, new int[] {0}, new int[] {1960}, 21, 21));
        // The place right after long keys, still on the screen.
        assertEquals(1921, EditorRules.barLeft(2400, 420, new int[] {0}, new int[] {1900}, 21, 21));
    }

    /**
     * APK-REL: after "Kaydet ve çık" the launcher comes back sideways and is relaunched upright, sometimes twice: the
     * save's line shows on each screen that comes up within 3 s of its first showing, never on a later return.
     */
    @Test
    public void theSavedLineShowsAgainOnARelaunchedScreenButNotOnALaterReturn() {
        assertTrue(EditorRules.showSavedLine(0, 5_000, 3_000));
        assertTrue(EditorRules.showSavedLine(5_000, 5_800, 3_000));
        assertTrue(EditorRules.showSavedLine(5_000, 8_000, 3_000));
        assertFalse(EditorRules.showSavedLine(5_000, 8_001, 3_000));
        assertFalse(EditorRules.showSavedLine(5_000, 65_000, 3_000));
    }
}
