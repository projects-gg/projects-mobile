package gg.projects.mobile.controls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.content.AtomicFiles;

/** Capitals of the on-screen controls and the Turkish default layout. */
public class ControlLabelsTest {
    private static final File DEFAULT_LAYOUT = new File("src/main/assets/default.json");

    /** The English labels 0.1.0 to 0.1.3 shipped, in file order, as written in the JSON. */
    private static final String[][] ENGLISH = {
            {"ZIPLA", "Jump"},
            {"EL\\nDE\\u011E\\u0130\\u015E", "Off\\nHand"},
            {"MEN\\u00DC", "Menu"},
            {"E\\u011E\\u0130L", "Crouch"},
            {"SA\\u011E\\nTIK", "Right\\nClick"},
            {"SOL\\nTIK", "Left\\nClick"},
            {"ORTA\\nTIK", "Middle\\nClick"},
            {"ENV.", "Inv."},
            {"KO\\u015E", "Sprint"},
            {"TU\\u015ELARI\\nG\\u0130ZLE", "Hide\\nGUI"},
            {"DURAKLAT", "Pause"},
            {"ENVANTER", "Inventory"},
            {"D\\u00DCZENLEY\\u0130C\\u0130YE HO\\u015E GELD\\u0130N.\\nAYARLARI \\u0130\\u00C7\\u0130N B\\u0130R TU\\u015EA DOKUN.\\nDAHA FAZLASI \\u0130\\u00C7\\u0130N \\u00DCSTTEK\\u0130 D\\u0130\\u015EL\\u0130YE DOKUN.",
                    "Welcome to the Editor.\\nTap a button to see what you can do!\\nTap the gear at the top to see more options."},
            {"GER\\u0130", "Exit\\nMenu"},
            {"SHIFT", "Shift"},
            {"D\\u0130\\u011EER", "More"},
            {"G\\u00D6R\\u00DC\\u015E\\nA\\u00C7ISI", "Third\\nPerson"},
            {"F3", "Debug\\nMenu"},
            {"EL\\nDE\\u011E\\u0130\\u015E", "Offhand"},
            {"FARE", "Mouse\\nToggle"},
            {"SOHBET", "Chat"},
            {"KLAVYE", "Keyboard"},
            {"ARAY\\u00DCZ", "HUD\\nToggle"},
            {"BIRAK", "Drop\\nItem"},
            {"OYUNCU\\nL\\u0130STES\\u0130", "Player\\nList"},
    };

    @Test
    public void labelsUseRootCapitalsOnATurkishPhone() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            // The bug: the phone's language turned English labels into Turkish capitals.
            assertEquals("HİDE\nGUI", "Hide\nGUI".toUpperCase());
            assertEquals("MİDDLE\nCLİCK", "Middle\nClick".toUpperCase());
            assertEquals("HIDE\nGUI", ControlLabels.upper("Hide\nGUI"));
            assertEquals("MIDDLE\nCLICK", ControlLabels.upper("Middle\nClick"));
            assertEquals("SHIFT", ControlLabels.upper("Shift"));
            assertEquals("RIGHT\nCLICK", ControlLabels.upper("right\nclick"));
            // Labels already in Turkish capitals keep their dotted İ.
            assertEquals("EĞİL", ControlLabels.upper("EĞİL"));
            assertEquals("TUŞLARI\nGİZLE", ControlLabels.upper("TUŞLARI\nGİZLE"));
            assertEquals(null, ControlLabels.upper(null));
        } finally {
            Locale.setDefault(before);
        }
    }

    /** Every label of the default layout that is drawn (buttons, drawers and their buttons; joysticks show none). */
    private static List<JsonObject> drawnButtons() throws IOException {
        JsonObject layout = JsonParser.parseString(text(DEFAULT_LAYOUT)).getAsJsonObject();
        List<JsonObject> buttons = new ArrayList<>();
        for (JsonElement button : layout.getAsJsonArray("mControlDataList")) buttons.add(button.getAsJsonObject());
        for (JsonElement drawer : layout.getAsJsonArray("mDrawerDataList")) {
            buttons.add(drawer.getAsJsonObject().getAsJsonObject("properties"));
            JsonArray inside = drawer.getAsJsonObject().getAsJsonArray("buttonProperties");
            for (JsonElement button : inside) buttons.add(button.getAsJsonObject());
        }
        return buttons;
    }

    private static String text(File file) throws IOException {
        return new String(AtomicFiles.read(file, 4L << 20), StandardCharsets.UTF_8);
    }

    @Test
    public void theDefaultLayoutIsTurkishAndInCapitals() throws IOException {
        List<String> names = new ArrayList<>();
        for (JsonObject button : drawnButtons()) {
            String name = button.get("name").getAsString();
            names.add(name);
            // Written in capitals: the same with or without the "all caps" setting, and root
            // upper-casing cannot turn a Turkish i into I.
            assertEquals(name, ControlLabels.upper(name), name);
            // A single word per line has to fit its button (14 sp capitals, about 9 dp each).
            double width = button.get("width").getAsDouble();
            for (String line : name.split("\n")) {
                if (line.contains(" ")) continue; // sentences wrap
                assertTrue(name + " in " + width + " dp", line.length() * 9 <= width);
            }
        }
        assertTrue(names.contains("TUŞLARI\nGİZLE"));
        assertTrue(names.contains("EĞİL"));
        // Key names players know stay.
        assertTrue(names.contains("CTRL"));
        assertTrue(names.contains("SHIFT"));
        assertTrue(names.contains("F3"));
        assertFalse(names.contains("Hide\nGUI"));
    }

    @Test
    public void onlyAnUntouchedEarlierDefaultIsReplaced() throws IOException {
        String current = text(DEFAULT_LAYOUT);
        // The earlier English default: the same file with the old labels (only the names changed).
        String english = current;
        for (String[] label : ENGLISH) {
            String turkish = "\"name\":\"" + label[0] + "\"";
            int at = english.indexOf(turkish);
            assertTrue(label[1], at >= 0);
            english = english.substring(0, at) + "\"name\":\"" + label[1] + "\"" + english.substring(at + turkish.length());
        }
        String lf = english.replace("\r\n", "\n");
        assertTrue(ControlLabels.isOldDefault(lf.getBytes(StandardCharsets.UTF_8)));
        assertTrue("CRLF as our Windows builds had it", ControlLabels.isOldDefault(lf.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
        // The new default and anything the player saved stay.
        assertFalse(ControlLabels.isOldDefault(current.getBytes(StandardCharsets.UTF_8)));
        assertFalse(ControlLabels.isOldDefault(lf.replace("\"Jump\"", "\"Hop\"").getBytes(StandardCharsets.UTF_8)));
        assertFalse(ControlLabels.isOldDefault(new byte[0]));
        assertFalse(ControlLabels.isOldDefault(null));
        assertNotEquals(ControlLabels.sha256(lf), ControlLabels.sha256(current));
    }

    @Test
    public void lineEndings() {
        assertEquals("a\nb\n\r", new String(ControlLabels.normalizeLineEndings("a\r\nb\r\n\r".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }
}
