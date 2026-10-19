package gg.projects.mobile.controls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.content.AtomicFiles;

/**
 * The default on-screen layout (M15, scripts/projects/make_control_layout.mjs): on the phones
 * players have, its keys stay on screen, apart from each other and off the Minecraft HUD (the
 * owner's screenshot: the menu keys under the toasts, the top row over the boss bar, the right
 * keys over the scoreboard).
 *
 * <p>HUD zones come from Minecraft's own layout (26.1) in GUI pixels; a GUI pixel is taken at its
 * largest, screen height / 240 (the Auto GUI scale never makes the HUD taller), so a smaller GUI
 * scale only makes room.
 */
public class ControlLayoutTest {
    private static final File TURKISH = new File("src/main/assets/default.json");
    private static final File ENGLISH = new File("src/main/assets/default_en.json");

    /** Landscape phones and tablets, physical pixels and density. */
    private static final Object[][] SCREENS = {
            {"20:9 1080p (emulator, Redmi Note, Galaxy A)", 2400, 1080, 2.625f},
            {"20:9 720p (Galaxy A0x, Redmi A)", 1600, 720, 2.0f},
            {"19.5:9 1080p (Galaxy A5x)", 2340, 1080, 2.625f},
            {"20:9 1080p dense", 2400, 1080, 2.75f},
            {"20:9 1440p", 3200, 1440, 3.5f},
            {"21:9 1080p (Xperia)", 2520, 1080, 2.625f},
            {"16:9 1080p", 1920, 1080, 2.625f},
            {"16:9 1080p dense", 1920, 1080, 3.0f},
            {"16:9 720p", 1280, 720, 2.0f},
            {"16:10 tablet", 2560, 1600, 2.0f},
            {"4:3 tablet", 2048, 1536, 2.0f},
    };

    /** A control resolved on a screen, in pixels. */
    static final class Box {
        final String name;
        final String icon;
        final String drawer; // the drawer a sub-button belongs to, else null
        final boolean game, menu, isDrawer, isStick;
        final double x, y, w, h;

        Box(String name, String icon, String drawer, boolean game, boolean menu, boolean isDrawer, boolean isStick,
            double x, double y, double w, double h) {
            this.name = name;
            this.icon = icon;
            this.drawer = drawer;
            this.game = game;
            this.menu = menu;
            this.isDrawer = isDrawer;
            this.isStick = isStick;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean overlaps(double ox, double oy, double ow, double oh) {
            return x < ox + ow && ox < x + w && y < oy + oh && oy < y + h;
        }

        boolean overlaps(Box o) {
            return overlaps(o.x, o.y, o.w, o.h);
        }

        @Override
        public String toString() {
            return String.format("%s [%.0f,%.0f %.0fx%.0f]", name.replace('\n', ' '), x, y, w, h);
        }
    }

    private static JsonObject layout(File file) throws IOException {
        return JsonParser.parseString(new String(AtomicFiles.read(file, 4L << 20), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Every control of a layout as ControlLayout places it (ControlData's variables and exp4j). */
    static List<Box> resolve(JsonObject layout, int screenW, int screenH, float density, float buttonScale, int inset) {
        List<Box> boxes = new ArrayList<>();
        for (JsonElement e : layout.getAsJsonArray("mControlDataList"))
            boxes.add(box(e.getAsJsonObject(), null, false, false, screenW, screenH, density, buttonScale, inset));
        for (JsonElement e : layout.getAsJsonArray("mDrawerDataList")) {
            JsonObject drawer = e.getAsJsonObject();
            Box d = box(drawer.getAsJsonObject("properties"), null, true, false, screenW, screenH, density, buttonScale, inset);
            boxes.add(d);
            assertEquals("panels are laid out by hand", "FREE", drawer.get("orientation").getAsString());
            for (JsonElement s : drawer.getAsJsonArray("buttonProperties"))
                boxes.add(box(s.getAsJsonObject(), d.name, false, false, screenW, screenH, density, buttonScale, inset));
        }
        for (JsonElement e : layout.getAsJsonArray("mJoystickDataList"))
            boxes.add(box(e.getAsJsonObject(), null, false, true, screenW, screenH, density, buttonScale, inset));
        return boxes;
    }

    private static Box box(JsonObject c, String drawer, boolean isDrawer, boolean isStick,
                           int screenW, int screenH, float density, float buttonScale, int inset) {
        double w = c.get("width").getAsDouble() * density * buttonScale / 100.0;
        double h = c.get("height").getAsDouble() * density * buttonScale / 100.0;
        double x = eval(c.get("dynamicX").getAsString(), w, h, screenW, screenH, density, buttonScale, inset);
        double y = eval(c.get("dynamicY").getAsString(), w, h, screenW, screenH, density, buttonScale, inset);
        String icon = c.has("icon") ? c.get("icon").getAsString() : null;
        return new Box(c.get("name").getAsString(), icon, drawer, c.get("displayInGame").getAsBoolean(),
                c.get("displayInMenu").getAsBoolean(), isDrawer, isStick, x, y, w, h);
    }

    static double eval(String expression, double w, double h, int screenW, int screenH, float density,
                       float buttonScale, int inset) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("top", "0");
        vars.put("left", "0");
        vars.put("right", Double.toString(screenW - w));
        vars.put("bottom", Double.toString(screenH - h));
        vars.put("width", Double.toString(w));
        vars.put("height", Double.toString(h));
        vars.put("screen_width", Integer.toString(screenW));
        vars.put("screen_height", Integer.toString(screenH));
        vars.put("margin", Integer.toString((int) (2 * density)));
        vars.put("preferred_scale", Float.toString(buttonScale));
        vars.put("inset", Integer.toString(inset));
        String filled = expression;
        for (Map.Entry<String, String> v : vars.entrySet()) filled = filled.replace("${" + v.getKey() + "}", v.getValue());
        assertFalse("every variable is known: " + filled, filled.contains("${"));
        return new ExpressionBuilder(filled)
                .function(new Function("px", 1) {
                    @Override
                    public double apply(double... args) {
                        return args[0] * density;
                    }
                })
                .function(new Function("dp", 1) {
                    @Override
                    public double apply(double... args) {
                        return args[0] / density;
                    }
                })
                .build().evaluate();
    }

    /** A HUD zone in GUI pixels. */
    static final class Zone {
        final String id;
        final double x, y, w, h;

        Zone(String id, double x, double y, double w, double h) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /**
     * The 26.1 HUD on a GUI screen of gw x gh: hotbar, offhand slot and vitals (hearts, armour,
     * food, air, two rows of absorption), two boss bars, two toasts, status effects, the pack's
     * Xaero minimap (top right with its coordinates), a 15-line scoreboard 130 GUI pixels wide
     * (bottom at gh / 2 + lines * 3), the crosshair and ten unfocused chat lines.
     */
    static List<Zone> hud(double gw, double gh) {
        double cx = gw / 2;
        int lines = 15;
        double j = 9 * lines;
        double sidebarBottom = gh / 2 + j / 3;
        double sidebarWidth = 130;
        List<Zone> zones = new ArrayList<>();
        zones.add(new Zone("hotbar", cx - 91, gh - 22, 182, 22));
        zones.add(new Zone("offhand", cx - 91 - 29, gh - 23, 29, 23));
        zones.add(new Zone("vitals", cx - 91, gh - 60, 182, 38));
        zones.add(new Zone("bossbars", cx - 91, 0, 182, 38));
        zones.add(new Zone("toasts", gw - 160, 0, 160, 64));
        zones.add(new Zone("effects", gw - 151, 0, 151, 52));
        zones.add(new Zone("minimap", gw - 64, 0, 64, 76));
        zones.add(new Zone("scoreboard", gw - 3 - sidebarWidth - 2, sidebarBottom - j - 10, sidebarWidth + 5, j + 10));
        zones.add(new Zone("crosshair", cx - 12, gh / 2 - 12, 24, 24));
        zones.add(new Zone("chat", 0, gh - 40 - 90, 328, 90));
        return zones;
    }

    @Test
    public void bothLanguagesShareOneGeometry() throws IOException {
        List<Box> tr = resolve(layout(TURKISH), 2400, 1080, 2.625f, 100f, 0);
        List<Box> en = resolve(layout(ENGLISH), 2400, 1080, 2.625f, 100f, 0);
        assertEquals(tr.size(), en.size());
        for (int i = 0; i < tr.size(); i++) {
            Box a = tr.get(i), b = en.get(i);
            assertEquals(a.toString(), a.x, b.x, 1e-6);
            assertEquals(a.toString(), a.y, b.y, 1e-6);
            assertEquals(a.toString(), a.w, b.w, 1e-6);
            assertEquals(a.toString(), a.h, b.h, 1e-6);
            assertEquals(a.icon, b.icon);
            String mapped = ControlLabels.ENGLISH_LABELS.containsKey(a.name) ? ControlLabels.ENGLISH_LABELS.get(a.name) : a.name;
            assertEquals(a.name, mapped, b.name);
        }
    }

    @Test
    public void everyKeyHasAGlyphAndALabelItCanFit() throws IOException {
        for (File file : new File[]{TURKISH, ENGLISH}) {
            int keys = 0;
            for (Box b : resolve(layout(file), 2400, 1080, 2.625f, 100f, 0)) {
                if (b.isStick) {
                    assertEquals(ControlStyle.STICK, b.icon);
                    continue;
                }
                if (!b.game && !b.menu && b.drawer == null) {
                    assertEquals("the editor's welcome text keeps upstream's look", null, b.icon);
                    continue;
                }
                keys++;
                assertTrue(b.name + ": " + b.icon, ControlStyle.knownGlyph(b.icon));
                assertEquals(b.name, ControlLabels.upper(b.name), b.name);
                assertFalse("one line: " + b.name, b.name.contains("\n"));
                // Figtree SemiBold capitals with 0.04 em tracking average under 0.66 em: the label
                // fits at the smallest size the skin uses, within 4 dp of each side.
                double widthDp = b.w / 2.625;
                assertTrue(b.name + " in " + widthDp + " dp",
                        b.name.length() * 0.66 * ControlStyle.MIN_LABEL_DP <= widthDp - 8);
            }
            assertTrue(keys >= 25);
        }
    }

    @Test
    public void keysStayOnScreenAndApartInGameAndInMenus() throws IOException {
        for (Object[] s : SCREENS) {
            // Larger keys (Settings, button size) still fit: 110 % on every screen, 125 % from 400 dp up.
            boolean tall = (int) s[2] / (float) s[3] >= 400;
            for (float scale : tall ? new float[]{100f, 110f, 125f} : new float[]{100f, 110f}) {
                List<Box> boxes = resolve(layout(TURKISH), (int) s[1], (int) s[2], (float) s[3], scale, 0);
                for (Box b : boxes) {
                    assertTrue(s[0] + " " + scale + "%: on screen " + b,
                            b.x >= 0 && b.y >= 0 && b.x + b.w <= (int) s[1] + 0.5 && b.y + b.h <= (int) s[2] + 0.5);
                }
                for (boolean game : new boolean[]{true, false}) {
                    List<Box> shown = new ArrayList<>();
                    for (Box b : boxes) if (b.drawer == null && (game ? b.game : b.menu)) shown.add(b);
                    apart(s[0] + " " + scale + "% " + (game ? "game" : "menus"), shown);
                }
                // An open drawer's panel: its keys apart, clear of every key shown in game.
                for (Box drawer : boxes) {
                    if (!drawer.isDrawer) continue;
                    List<Box> panel = new ArrayList<>();
                    for (Box b : boxes) if (drawer.name.equals(b.drawer)) panel.add(b);
                    assertFalse(panel.isEmpty());
                    apart(s[0] + " " + drawer.name, panel);
                    for (Box key : panel)
                        for (Box b : boxes)
                            if (b.drawer == null && b.game)
                                assertFalse(s[0] + " " + scale + "%: " + key + " over " + b, key.overlaps(b));
                }
            }
        }
    }

    private static void apart(String what, List<Box> shown) {
        for (int i = 0; i < shown.size(); i++)
            for (int k = i + 1; k < shown.size(); k++)
                if (shown.get(i).overlaps(shown.get(k)))
                    fail(what + ": " + shown.get(i) + " over " + shown.get(k));
    }

    @Test
    public void theHudStaysClear() throws IOException {
        for (Object[] s : SCREENS) {
            int w = (int) s[1], h = (int) s[2];
            float density = (float) s[3];
            double unit = h / 240.0; // pixels per GUI pixel at the largest HUD
            List<Zone> zones = hud(w / unit, 240);
            for (Box b : resolve(layout(TURKISH), w, h, density, 100f, 0)) {
                if (b.drawer != null || !b.game) continue; // panels are opened on purpose; menus have no HUD
                for (Zone z : zones) {
                    double zx = z.x * unit, zy = z.y * unit, zw = z.w * unit, zh = z.h * unit;
                    boolean over = b.isStick ? baseOverlaps(b, zx, zy, zw, zh) : b.overlaps(zx, zy, zw, zh);
                    if (!over) continue;
                    if (z.id.equals("chat")) {
                        // The joystick and the keys above it share the bottom-left with the chat
                        // (Bedrock's do too); its lines stay readable through the translucent base.
                        if (b.isStick || "sneak".equals(b.icon) || "sprint".equals(b.icon)) continue;
                        // Anything else may touch the chat's far corner by a few dp at most.
                        double dx = Math.min(b.x + b.w, zx + zw) - Math.max(b.x, zx);
                        double dy = Math.min(b.y + b.h, zy + zh) - Math.max(b.y, zy);
                        assertTrue(s[0] + ": " + b + " over the chat", Math.min(dx, dy) / density <= 8);
                        continue;
                    }
                    if (z.id.equals("offhand") && b.isStick && (double) w / h < 1.4) {
                        // A 4:3 tablet's HUD reaches the joystick: the offhand slot (shown only with
                        // something in the off hand) touches the edge of its base.
                        continue;
                    }
                    if (z.id.equals("scoreboard") && "use".equals(b.icon) && (double) w / h < 1.8) {
                        // On 16:9 phones "use" stands on "attack" (the row would cover the hotbar's
                        // last slot) and may meet the last lines of a 15-line scoreboard.
                        double overDp = (zy + zh - b.y) / density;
                        assertTrue(s[0] + ": use over the scoreboard by " + overDp + " dp", overDp <= 24);
                        continue;
                    }
                    fail(s[0] + ": " + b + " over the " + z.id);
                }
            }
        }
    }

    /** The joystick takes touches and draws only on its round base. */
    private static boolean baseOverlaps(Box stick, double zx, double zy, double zw, double zh) {
        double cx = stick.x + stick.w / 2, cy = stick.y + stick.h / 2;
        double r = Math.min(stick.w, stick.h) / 2 * ControlStyle.STICK_BASE_RATIO;
        double nx = Math.max(zx, Math.min(cx, zx + zw)), ny = Math.max(zy, Math.min(cy, zy + zh));
        return (nx - cx) * (nx - cx) + (ny - cy) * (ny - cy) < r * r;
    }

    @Test
    public void theRightKeysSitUnderTheTallestScoreboard() throws IOException {
        // The owner's screenshot: keys on the right middle met the scoreboard. Now nothing on the
        // right half of a 720p phone reaches above the bottom of a 15-line sidebar.
        List<Box> boxes = resolve(layout(TURKISH), 1600, 720, 2.0f, 100f, 0);
        double sidebarBottom = (240 / 2.0 + 45) * 3; // GUI scale 3
        for (Box b : boxes) {
            if (b.drawer != null || !b.game || b.x + b.w / 2 < 800) continue;
            assertTrue(b + " below " + sidebarBottom, b.y >= sidebarBottom);
        }
    }

    @Test
    public void useStandsOnAttackOnlyWhereTheRowIsTooWide() throws IOException {
        for (Object[] s : SCREENS) {
            Box attack = null, use = null;
            for (Box b : resolve(layout(TURKISH), (int) s[1], (int) s[2], (float) s[3], 100f, 0)) {
                if ("attack".equals(b.icon)) attack = b;
                if ("use".equals(b.icon)) use = b;
            }
            assertNotNull(attack);
            assertNotNull(use);
            int w = (int) s[1], h = (int) s[2];
            float d = (float) s[3];
            boolean stacked = Math.abs(use.x - attack.x) < 1;
            // Crowded: the row's left end comes within 6 dp of the hotbar at its largest.
            double rowLeft = w - (12 + 64 + 8 + 56 + 8 + 56) * d;
            boolean crowded = w / 2.0 + 91 * h / 240.0 + 6 * d > rowLeft;
            assertEquals(s[0] + ": " + use, crowded, stacked);
            double aspect = w / (double) h;
            if (aspect >= 2.0) assertFalse("phones from 19.5:9 keep the row: " + s[0], stacked);
            if (aspect < 1.8 && w / d < 760) assertTrue("16:9 phones stack: " + s[0], stacked);
            if (stacked) assertTrue(use.y + use.h <= attack.y);
            else assertEquals(attack.y, use.y, 1e-6);
        }
    }

    @Test
    public void aCutoutInsetMovesTheSideKeysIn() throws IOException {
        List<Box> plain = resolve(layout(TURKISH), 2400, 1080, 2.625f, 100f, 0);
        List<Box> inset = resolve(layout(TURKISH), 2400, 1080, 2.625f, 100f, 90);
        for (int i = 0; i < plain.size(); i++) {
            Box a = plain.get(i), b = inset.get(i);
            if (!a.game && !a.menu && a.drawer == null && !a.isStick) continue; // the centred welcome text
            double shift = b.x - a.x;
            assertEquals(a.toString(), 90, Math.abs(shift), 1e-6);
            assertTrue("towards the middle: " + a, a.x + a.w / 2 < 1200 ? shift > 0 : shift < 0);
            assertEquals(a.y, b.y, 1e-6);
        }
    }

    @Test
    public void earlierDefaultsAreReplacedOnlyWhenUntouched() throws IOException {
        File dir = new File("src/test/resources/controls");
        byte[] turkish015 = AtomicFiles.read(new File(dir, "default-0.1.5.json"), 4L << 20);
        byte[] english015 = AtomicFiles.read(new File(dir, "default_en-0.1.5.json"), 4L << 20);
        byte[] turkish = AtomicFiles.read(TURKISH, 4L << 20);
        byte[] english = AtomicFiles.read(ENGLISH, 4L << 20);
        assertNotNull(turkish015);
        // The 0.1.4 / 0.1.5 defaults (the layout of the owner's screenshot) give way to this one...
        assertTrue(ControlLabels.isOldDefault(turkish015));
        assertTrue(ControlLabels.isOldDefault(english015));
        assertTrue(ControlLabels.isUntouchedDefault(english015, turkish, english));
        assertFalse(ControlLabels.sameLayout(turkish015, turkish));
        // ...while this version's own defaults are not "old" and an edited copy of an old one stays.
        assertFalse(ControlLabels.isOldDefault(turkish));
        assertFalse(ControlLabels.isOldDefault(english));
        String edited = new String(turkish015, StandardCharsets.UTF_8).replace("\"ZIPLA\"", "\"HOP\"");
        assertFalse(ControlLabels.isUntouchedDefault(edited.getBytes(StandardCharsets.UTF_8), turkish, english));
        // An edited 0.1.5 layout still follows the language: its old default labels translate.
        JsonObject old = JsonParser.parseString(new String(turkish015, StandardCharsets.UTF_8)).getAsJsonObject();
        old.getAsJsonArray("mControlDataList").get(0).getAsJsonObject().addProperty("dynamicX", "0.5 * ${screen_width}");
        JsonObject en = JsonParser.parseString(ControlLabels.translate(old.toString(), false)).getAsJsonObject();
        JsonArray list = en.getAsJsonArray("mControlDataList");
        assertEquals("JUMP", list.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("0.5 * ${screen_width}", list.get(0).getAsJsonObject().get("dynamicX").getAsString());
        assertEquals("HIDE\nBUTTONS", list.get(9).getAsJsonObject().get("name").getAsString());
    }
}
