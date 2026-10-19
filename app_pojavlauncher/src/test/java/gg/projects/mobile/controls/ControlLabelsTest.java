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
    private static final File ENGLISH_LAYOUT = new File("src/main/assets/default_en.json");
    /** The Turkish default of 0.1.4 and 0.1.5 (before M15), kept as it was installed. */
    private static final File LAYOUT_015 = new File("src/test/resources/controls/default-0.1.5.json");

    /** The English labels 0.1.0 to 0.1.3 shipped, in the 0.1.5 file's order, as written in the JSON. */
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
        return drawnButtons(DEFAULT_LAYOUT);
    }

    private static List<JsonObject> drawnButtons(File file) throws IOException {
        JsonObject layout = JsonParser.parseString(text(file)).getAsJsonObject();
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
        List<String> names = labelsInCapitalsThatFit(DEFAULT_LAYOUT);
        assertTrue(names.contains("GİZLE"));
        assertTrue(names.contains("EĞİL"));
        assertTrue(names.contains("YOL NOKTASI"));
        // Key names players know stay.
        assertTrue(names.contains("SHIFT"));
        assertTrue(names.contains("F3"));
        assertFalse(names.contains("Hide\nGUI"));
        assertFalse(names.contains("HIDE"));
    }

    @Test
    public void theEnglishLayoutIsTheSameLayoutWithEnglishLabels() throws IOException {
        List<String> names = labelsInCapitalsThatFit(ENGLISH_LAYOUT);
        assertTrue(names.contains("HIDE"));
        assertTrue(names.contains("SNEAK"));
        assertTrue(names.contains("SHIFT") && names.contains("F3"));
        for (String name : names) {
            assertFalse("no Turkish left: " + name, name.matches("(?s).*[ÇĞİÖŞÜçğıöşü].*"));
        }
        // Button for button, the Turkish label maps to the English one (ControlLabels' table) and
        // nothing else differs: positions, sizes and keys are the Turkish file's.
        JsonObject turkish = JsonParser.parseString(text(DEFAULT_LAYOUT)).getAsJsonObject();
        JsonObject english = JsonParser.parseString(text(ENGLISH_LAYOUT)).getAsJsonObject();
        List<JsonObject> turkishButtons = drawnButtons(DEFAULT_LAYOUT);
        List<JsonObject> englishButtons = drawnButtons(ENGLISH_LAYOUT);
        assertEquals(turkishButtons.size(), englishButtons.size());
        for (int i = 0; i < turkishButtons.size(); i++) {
            String from = turkishButtons.get(i).get("name").getAsString();
            String to = englishButtons.get(i).get("name").getAsString();
            String mapped = ControlLabels.ENGLISH_LABELS.containsKey(from) ? ControlLabels.ENGLISH_LABELS.get(from) : from;
            assertEquals(from, mapped, to);
        }
        assertEquals("the Turkish file translated is the English file",
                english, JsonParser.parseString(ControlLabels.translate(text(DEFAULT_LAYOUT), false)));
        assertEquals("and back", turkish, JsonParser.parseString(ControlLabels.translate(text(ENGLISH_LAYOUT), true)));
        assertEquals(ControlLabels.ENGLISH_ASSET, ControlLabels.assetFor(false));
        assertEquals(ControlLabels.TURKISH_ASSET, ControlLabels.assetFor(true));
    }

    @Test
    public void untouchedDefaultsFollowTheLanguageEditedOnesKeepTheirButtons() throws IOException {
        byte[] turkish = text(DEFAULT_LAYOUT).getBytes(StandardCharsets.UTF_8);
        byte[] english = text(ENGLISH_LAYOUT).getBytes(StandardCharsets.UTF_8);
        assertTrue(ControlLabels.isUntouchedDefault(turkish, turkish, english));
        assertTrue(ControlLabels.isUntouchedDefault(english, turkish, english));
        String lf = text(ENGLISH_LAYOUT).replace("\r\n", "\n");
        assertTrue("line endings aside", ControlLabels.isUntouchedDefault(lf.getBytes(StandardCharsets.UTF_8), turkish, english));
        assertTrue("line endings aside", ControlLabels.isUntouchedDefault(
                lf.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), turkish, english));
        assertFalse(ControlLabels.isUntouchedDefault(null, turkish, english));

        // An edited layout: a moved button, a label of the player's own and a default label.
        JsonObject layout = JsonParser.parseString(text(DEFAULT_LAYOUT)).getAsJsonObject();
        JsonObject first = layout.getAsJsonArray("mControlDataList").get(0).getAsJsonObject();
        first.addProperty("dynamicX", "0.5 * ${screen_width}");
        JsonObject second = layout.getAsJsonArray("mControlDataList").get(1).getAsJsonObject();
        second.addProperty("name", "BENİM TUŞUM");
        byte[] edited = layout.toString().getBytes(StandardCharsets.UTF_8);
        assertFalse(ControlLabels.isUntouchedDefault(edited, turkish, english));
        JsonObject translated = JsonParser.parseString(ControlLabels.translate(layout.toString(), false)).getAsJsonObject();
        JsonObject movedPause = translated.getAsJsonArray("mControlDataList").get(0).getAsJsonObject();
        assertEquals("PAUSE", movedPause.get("name").getAsString());
        assertEquals("0.5 * ${screen_width}", movedPause.get("dynamicX").getAsString());
        assertEquals("the glyph stays with the button", "pause", movedPause.get("icon").getAsString());
        assertEquals("the player's own label stays", "BENİM TUŞUM",
                translated.getAsJsonArray("mControlDataList").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("already in that language: nothing to do", null, ControlLabels.translate(text(DEFAULT_LAYOUT), true));
        // An edited layout of 0.1.0 to 0.1.3 with upstream's English labels, on a Turkish app.
        JsonObject old = JsonParser.parseString(text(DEFAULT_LAYOUT)).getAsJsonObject();
        old.getAsJsonArray("mControlDataList").get(0).getAsJsonObject().addProperty("name", "Jump");
        old.getAsJsonArray("mControlDataList").get(1).getAsJsonObject().addProperty("name", "Hide\nGUI");
        JsonObject turkishAgain = JsonParser.parseString(ControlLabels.translate(old.toString(), true)).getAsJsonObject();
        assertEquals("ZIPLA", turkishAgain.getAsJsonArray("mControlDataList").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("TUŞLARI\nGİZLE", turkishAgain.getAsJsonArray("mControlDataList").get(1).getAsJsonObject().get("name").getAsString());
        // On an English app the Turkish default labels become English and upstream's stay as they are.
        JsonObject englishAgain = JsonParser.parseString(ControlLabels.translate(old.toString(), false)).getAsJsonObject();
        assertEquals("Hide\nGUI", englishAgain.getAsJsonArray("mControlDataList").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("CHAT", englishAgain.getAsJsonArray("mControlDataList").get(2).getAsJsonObject().get("name").getAsString());
        assertEquals("not a layout", null, ControlLabels.translate("[1, 2]", false));
        assertEquals(null, ControlLabels.translate("{ broken", false));
    }

    /** The drawn labels of a layout, each checked: in capitals, and every single-word line fits its button. */
    private static List<String> labelsInCapitalsThatFit(File file) throws IOException {
        List<String> names = new ArrayList<>();
        for (JsonObject button : drawnButtons(file)) {
            String name = button.get("name").getAsString();
            names.add(name);
            // Written in capitals: the same with or without the "all caps" setting, and root
            // upper-casing cannot turn a Turkish i into I.
            assertEquals(name, ControlLabels.upper(name), name);
            // A key with a glyph fits its small label itself (ControlLayoutTest checks the room).
            if (button.has("icon")) continue;
            // A single word per line has to fit its button (14 sp capitals, about 9 dp each).
            double width = button.get("width").getAsDouble();
            for (String line : name.split("\n")) {
                if (line.contains(" ")) continue; // sentences wrap
                assertTrue(name + " in " + width + " dp", line.length() * 9 <= width);
            }
        }
        return names;
    }

    @Test
    public void onlyAnUntouchedEarlierDefaultIsReplaced() throws IOException {
        String current = text(DEFAULT_LAYOUT);
        // The earliest English default: the 0.1.5 file with the old labels (only the names changed).
        String english = text(LAYOUT_015);
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
