package gg.projects.mobile.controls;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.content.AtomicFiles;

/**
 * MOB-NEXT (TASTE-2's P1): the default layout in the controls editor. Upstream's editor showed every key at once, so the
 * twins the game never shows together (DURAKLAT and GERİ, SOHBET and KLAVYE, ZIPLA and SHIFT, SALDIR and SAĞ TIK) and
 * both drawers' grids lay over each other. The editor now shows one of the game's screens ({@link EditorRules#shownIn})
 * and one drawer's keys at a time, the welcome text stepping aside where they cover it ({@link EditorRules#noteGivesWay}):
 * on every screen of the game, with no drawer or any one drawer open, the keys it shows lie apart.
 */
public class EditorScenesTest {
    private static final File TURKISH = new File("src/main/assets/default.json");
    private static final File ENGLISH = new File("src/main/assets/default_en.json");

    /** Phones and tablets held sideways, and windows taller than wide (TABLET-M), physical pixels and density. */
    private static final Object[][] SCREENS = {
            {"20:9 1080p", 2400, 1080, 2.625f},
            {"20:9 720p", 1600, 720, 2.0f},
            {"21:9 1080p", 2520, 1080, 2.625f},
            {"16:9 1080p", 1920, 1080, 2.625f},
            {"16:9 720p", 1280, 720, 2.0f},
            {"16:10 tablet", 2560, 1600, 2.0f},
            {"4:3 tablet", 2048, 1536, 2.0f},
            {"phone upright 1080 x 2400", 1080, 2400, 2.625f},
            {"tablet upright 1600 x 2560", 1600, 2560, 2.0f},
            {"half a tablet 1280 x 1600", 1280, 1600, 2.0f},
    };

    private static JsonObject layout(File file) throws IOException {
        return JsonParser.parseString(new String(AtomicFiles.read(file, 4L << 20), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static boolean note(ControlLayoutTest.Box b) {
        return b.drawer == null && !b.game && !b.menu && !b.isDrawer && !b.isStick;
    }

    /** What the editor shows on {@code scene} with {@code open}'s keys out (null: none), as ControlLayout decides. */
    static List<ControlLayoutTest.Box> shown(List<ControlLayoutTest.Box> boxes, EditorRules.Scene scene,
                                             ControlLayoutTest.Box open) {
        List<ControlLayoutTest.Box> out = new ArrayList<>();
        boolean opened = open != null && EditorRules.shownIn(scene, open.game, open.menu, true);
        for (ControlLayoutTest.Box b : boxes) {
            if (b.drawer != null) {
                if (opened && open.name.equals(b.drawer)) out.add(b);
                continue;
            }
            if (!note(b) && EditorRules.shownIn(scene, b.game, b.menu, true)) out.add(b);
        }
        float[][] covering = new float[out.size()][];
        for (int i = 0; i < out.size(); i++) covering[i] = box(out.get(i));
        for (ControlLayoutTest.Box b : boxes) {
            if (note(b) && !EditorRules.noteGivesWay(box(b), covering)) out.add(b);
        }
        return out;
    }

    private static float[] box(ControlLayoutTest.Box b) {
        return new float[]{(float) b.x, (float) b.y, (float) (b.x + b.w), (float) (b.y + b.h)};
    }

    private static void apart(String what, List<ControlLayoutTest.Box> shown, boolean drawerKeysMayCover) {
        for (int i = 0; i < shown.size(); i++) {
            for (int k = i + 1; k < shown.size(); k++) {
                ControlLayoutTest.Box a = shown.get(i), b = shown.get(k);
                if (!a.overlaps(b)) continue;
                // TABLET-M: below 600 dp a window taller than wide slides the open panel left, over the top block.
                if (drawerKeysMayCover && (a.drawer == null) != (b.drawer == null)) continue;
                fail(what + ": " + a + " over " + b);
            }
        }
    }

    @Test
    public void upstreamsEditorDrewTheTwinsAndBothGridsOverEachOther() throws IOException {
        List<ControlLayoutTest.Box> all = ControlLayoutTest.resolve(layout(TURKISH), 2400, 1080, 2.625f, 100f, 0);
        int twins = 0, grids = 0;
        for (int i = 0; i < all.size(); i++) {
            for (int k = i + 1; k < all.size(); k++) {
                ControlLayoutTest.Box a = all.get(i), b = all.get(k);
                if (!a.overlaps(b)) continue;
                if (a.drawer == null && b.drawer == null && a.game != b.game) twins++;
                if (a.drawer != null && b.drawer != null && !a.drawer.equals(b.drawer)) grids++;
            }
        }
        assertTrue("the four twins", twins >= 4);
        assertTrue("the two grids", grids >= 6);
    }

    @Test
    public void eachScreenOfTheEditorShowsItsKeysApartWithAnyOneDrawerOpen() throws IOException {
        for (File file : new File[]{TURKISH, ENGLISH}) {
            JsonObject layout = layout(file);
            for (Object[] s : SCREENS) {
                int w = (int) s[1], h = (int) s[2];
                float density = (float) s[3];
                boolean narrowUpright = h > w && w / density < 600;
                for (float scale : new float[]{100f, 110f}) {
                    List<ControlLayoutTest.Box> boxes = ControlLayoutTest.resolve(layout, w, h, density, scale, 0);
                    List<ControlLayoutTest.Box> drawers = new ArrayList<>();
                    drawers.add(null);
                    for (ControlLayoutTest.Box b : boxes) if (b.isDrawer) drawers.add(b);
                    for (EditorRules.Scene scene : EditorRules.Scene.values()) {
                        boolean twinsSeen = false;
                        for (ControlLayoutTest.Box open : drawers) {
                            List<ControlLayoutTest.Box> shown = shown(boxes, scene, open);
                            String what = file.getName() + " " + s[0] + " " + scale + "% " + scene + " open "
                                    + (open == null ? "none" : open.name);
                            apart(what, shown, narrowUpright);
                            // One of each pair, never both: GERİ only in menus, DURAKLAT only in play.
                            for (ControlLayoutTest.Box b : shown) {
                                if (b.drawer == null && !note(b) && !b.isStick) {
                                    assertTrue(what + ": " + b, scene == EditorRules.Scene.GAME ? b.game : b.menu);
                                    twinsSeen |= b.game != b.menu;
                                }
                            }
                        }
                        assertTrue(file.getName() + " " + s[0] + " " + scene + ": its own keys", twinsSeen);
                    }
                }
            }
        }
    }

    @Test
    public void theWelcomeTextStaysUnlessADrawersKeysCoverIt() throws IOException {
        List<ControlLayoutTest.Box> boxes = ControlLayoutTest.resolve(layout(TURKISH), 2400, 1080, 2.625f, 100f, 0);
        ControlLayoutTest.Box more = null, mods = null;
        for (ControlLayoutTest.Box b : boxes) {
            if (b.isDrawer && "DİĞER".equals(b.name)) more = b;
            if (b.isDrawer && "MODLAR".equals(b.name)) mods = b;
        }
        assertTrue(more != null && mods != null);
        for (EditorRules.Scene scene : EditorRules.Scene.values()) {
            assertTrue(scene + ": no drawer open", hasNote(shown(boxes, scene, null)));
        }
        // On a phone held sideways DİĞER's third row reaches the centred text; MODLAR's two rows do not.
        assertFalse(hasNote(shown(boxes, EditorRules.Scene.GAME, more)));
        assertTrue(hasNote(shown(boxes, EditorRules.Scene.GAME, mods)));
        // In menus the drawers are not shown, so their keys never come out over it.
        assertTrue(hasNote(shown(boxes, EditorRules.Scene.MENU, more)));
    }

    private static boolean hasNote(List<ControlLayoutTest.Box> shown) {
        for (ControlLayoutTest.Box b : shown) if (note(b)) return true;
        return false;
    }
}
