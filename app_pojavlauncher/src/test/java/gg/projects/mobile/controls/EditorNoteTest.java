package gg.projects.mobile.controls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import gg.projects.mobile.content.AtomicFiles;

/**
 * CTRL-SAVE: the default layout's editor-only welcome key sent the player to the gear at the top, which the editor's
 * own "Menü" replaced. This version's defaults say "Menü"; untouched 0.4.7 / 0.4.8 defaults give way to them, and an
 * edited layout's earlier welcome text becomes this version's, in either language.
 */
public class EditorNoteTest {
    private static final File TURKISH = new File("src/main/assets/default.json");
    private static final File ENGLISH = new File("src/main/assets/default_en.json");
    private static final File OLD_TURKISH = new File("src/test/resources/controls/default-0.4.8.json");
    private static final File OLD_ENGLISH = new File("src/test/resources/controls/default_en-0.4.8.json");

    private static byte[] bytes(File file) throws IOException {
        return AtomicFiles.read(file, 4L << 20);
    }

    private static String text(File file) throws IOException {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    /** The welcome key: the one key shown neither in the game nor in menus. */
    private static String note(JsonObject layout) {
        String found = null;
        for (JsonElement element : layout.getAsJsonArray("mControlDataList")) {
            JsonObject key = element.getAsJsonObject();
            if (key.get("displayInGame").getAsBoolean() || key.get("displayInMenu").getAsBoolean()) continue;
            assertEquals("one welcome key", null, found);
            found = key.get("name").getAsString();
        }
        assertNotNull(found);
        return found;
    }

    @Test
    public void thisVersionsWelcomeKeyPointsToTheMenuNotTheGear() throws IOException {
        String turkish = note(JsonParser.parseString(text(TURKISH)).getAsJsonObject());
        String english = note(JsonParser.parseString(text(ENGLISH)).getAsJsonObject());
        assertEquals(ControlLabels.EDITOR_NOTE_TURKISH, turkish);
        assertEquals(ControlLabels.EDITOR_NOTE_ENGLISH, english);
        assertTrue(turkish.contains("MENÜ") && english.contains("MENU"));
        assertFalse(turkish.contains("DİŞLİ"));
        assertFalse(english.contains("GEAR"));
        assertEquals(ControlLabels.EDITOR_NOTE_ENGLISH, ControlLabels.ENGLISH_LABELS.get(ControlLabels.EDITOR_NOTE_TURKISH));
        assertFalse(ControlLabels.OLD_EDITOR_NOTES.contains(turkish));
    }

    @Test
    public void untouchedDefaultsOf048GiveWayToThisVersions() throws IOException {
        byte[] turkish = bytes(TURKISH);
        byte[] english = bytes(ENGLISH);
        byte[] oldTurkish = bytes(OLD_TURKISH);
        byte[] oldEnglish = bytes(OLD_ENGLISH);
        assertTrue(ControlLabels.isOldDefault(oldTurkish));
        assertTrue(ControlLabels.isOldDefault(oldEnglish));
        assertTrue("CRLF as the Windows builds had it", ControlLabels.isOldDefault(
                new String(oldTurkish, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
        assertTrue(ControlLabels.isUntouchedDefault(oldEnglish, turkish, english));
        assertFalse(ControlLabels.sameLayout(oldTurkish, turkish));
        assertFalse(ControlLabels.isOldDefault(turkish));
        assertFalse(ControlLabels.isOldDefault(english));
        // Only the welcome key's words changed: everything else is 0.4.8's, key for key.
        JsonObject before = JsonParser.parseString(text(OLD_TURKISH)).getAsJsonObject();
        JsonObject now = JsonParser.parseString(text(TURKISH)).getAsJsonObject();
        assertTrue(ControlLabels.OLD_EDITOR_NOTES.contains(note(before)));
        JsonArray keys = before.getAsJsonArray("mControlDataList");
        for (JsonElement element : keys) {
            JsonObject key = element.getAsJsonObject();
            if (ControlLabels.OLD_EDITOR_NOTES.contains(key.get("name").getAsString())) {
                key.addProperty("name", ControlLabels.EDITOR_NOTE_TURKISH);
            }
        }
        assertEquals(now, before);
    }

    @Test
    public void anEditedLayoutsEarlierWelcomeTextBecomesThisVersionsInEitherLanguage() throws IOException {
        // A 0.4.8 layout the player saved: a moved key, a label of the player's own, the welcome key as it came.
        JsonObject layout = JsonParser.parseString(text(OLD_TURKISH)).getAsJsonObject();
        JsonObject first = layout.getAsJsonArray("mControlDataList").get(0).getAsJsonObject();
        first.addProperty("dynamicX", "0.5 * ${screen_width}");
        String firstName = first.get("name").getAsString();
        layout.getAsJsonArray("mControlDataList").get(1).getAsJsonObject().addProperty("name", "BENİM TUŞUM");

        JsonObject turkish = JsonParser.parseString(ControlLabels.translate(layout.toString(), true)).getAsJsonObject();
        assertEquals(ControlLabels.EDITOR_NOTE_TURKISH, note(turkish));
        assertEquals(firstName, turkish.getAsJsonArray("mControlDataList").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("0.5 * ${screen_width}",
                turkish.getAsJsonArray("mControlDataList").get(0).getAsJsonObject().get("dynamicX").getAsString());
        assertEquals("BENİM TUŞUM", turkish.getAsJsonArray("mControlDataList").get(1).getAsJsonObject().get("name").getAsString());

        JsonObject english = JsonParser.parseString(ControlLabels.translate(layout.toString(), false)).getAsJsonObject();
        assertEquals(ControlLabels.EDITOR_NOTE_ENGLISH, note(english));

        JsonObject savedInEnglish = JsonParser.parseString(text(OLD_ENGLISH)).getAsJsonObject();
        savedInEnglish.getAsJsonArray("mControlDataList").get(0).getAsJsonObject().addProperty("dynamicX", "0.5 * ${screen_width}");
        JsonObject stillEnglish = JsonParser.parseString(ControlLabels.translate(savedInEnglish.toString(), false)).getAsJsonObject();
        assertEquals(ControlLabels.EDITOR_NOTE_ENGLISH, note(stillEnglish));

        // Upstream's English welcome of 0.1.0 to 0.1.3 on a Turkish app.
        JsonObject upstream = JsonParser.parseString(text(TURKISH)).getAsJsonObject();
        for (JsonElement element : upstream.getAsJsonArray("mControlDataList")) {
            JsonObject key = element.getAsJsonObject();
            if (ControlLabels.EDITOR_NOTE_TURKISH.equals(key.get("name").getAsString())) {
                key.addProperty("name", "Welcome to the Editor.\nTap a button to see what you can do!\nTap the gear at the top to see more options.");
            }
        }
        assertEquals(ControlLabels.EDITOR_NOTE_TURKISH,
                note(JsonParser.parseString(ControlLabels.translate(upstream.toString(), true)).getAsJsonObject()));
        // This version's layouts have nothing to upgrade.
        assertEquals(null, ControlLabels.translate(text(TURKISH), true));
        assertEquals(null, ControlLabels.translate(text(ENGLISH), false));
    }
}
