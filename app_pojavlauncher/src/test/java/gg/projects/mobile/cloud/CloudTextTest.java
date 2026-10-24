package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Test;

/** The text rule, password commands and previews, against the contract's shared cases (text-rule-v1.json). */
public class CloudTextTest {
    private static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(s);
        return out.toString();
    }

    @Test
    public void theTextRule() {
        assertNull(CloudText.check("/is home"));
        assertNull(CloudText.check("50% indirim"));
        assertNull(CloudText.check("%%"));
        assertNull(CloudText.check("Selam %username%!"));
        assertNull(CloudText.check("%DateTime% saatinde"));
        assertEquals(CloudText.Problem.EMPTY, CloudText.check(""));
        assertNull(CloudText.check(repeat("a", 256)));
        assertEquals(CloudText.Problem.LENGTH, CloudText.check(repeat("a", 257)));
        assertNull(CloudText.check(repeat("🙂", 128)));
        assertEquals(CloudText.Problem.LENGTH, CloudText.check(repeat("🙂", 129)));
        assertEquals(CloudText.Problem.SPACE, CloudText.check(" /spawn"));
        assertEquals(CloudText.Problem.SPACE, CloudText.check("/spawn "));
        assertEquals(CloudText.Problem.CONTROL, CloudText.check("/a\tb"));
        assertEquals(CloudText.Problem.CONTROL, CloudText.check("/a\nb"));
        assertEquals(CloudText.Problem.CONTROL, CloudText.check("a b"));
        assertEquals(CloudText.Problem.CONTROL, CloudText.check("a\u0085b"));
        assertEquals(CloudText.Problem.BACKSLASH, CloudText.check("\\quit"));
        assertEquals(CloudText.Problem.BACKSLASH, CloudText.check(" \\quit"));
        assertEquals(CloudText.Problem.BACKSLASH, CloudText.check("​\\quit"));
        assertEquals(CloudText.Problem.BACKSLASH, CloudText.check(new String(Character.toChars(0xE0020)) + "\\quit"));
        assertNull(CloudText.check("a\\quit"));
        assertEquals(CloudText.Problem.UNICODE, CloudText.check("a\uD800b"));
        assertNull(CloudText.check("a🙂"));
        assertEquals(CloudText.Problem.VARIABLE, CloudText.check("%login%"));
        assertEquals(CloudText.Problem.VARIABLE, CloudText.check("selam %players% kişi"));
        assertEquals(CloudText.Problem.VARIABLE, CloudText.check("%şifre%"));
        assertNull(CloudText.check("%50 %"));
        assertNull(CloudText.check("100%"));
    }

    @Test
    public void passwordCommands() {
        for (String line : new String[]{"/login hunter2", "/LOGIN hunter2", "/l pw", "/giriş 1234", "/GIRIŞ 1234", "/register a a",
                "/şifre x", "/2fa 123456", "  /login  pw"}) {
            assertTrue(line, CloudText.isSensitive(line));
            assertTrue(line, CloudText.looksSensitive(line));
        }
        for (String line : new String[]{"/list", "/is home", "/logins", "selam", "", "/"}) {
            assertFalse(line, CloudText.isSensitive(line));
            assertFalse(line, CloudText.looksSensitive(line));
        }
        // A Turkish keyboard's capitals: the contract's rule misses them, the phone seals them anyway.
        assertFalse(CloudText.isSensitive("/GİRİŞ 1234"));
        assertTrue(CloudText.looksSensitive("/GİRİŞ 1234"));
        assertTrue(CloudText.looksSensitive("/KAYIT a a"));
    }

    @Test
    public void previews() {
        assertEquals("/login ******", CloudText.preview("/login hunter2"));
        assertEquals("/spawn ******", CloudText.preview("/spawn"));
        assertEquals("******", CloudText.preview("gizli söz"));
        assertEquals("******", CloudText.preview("/" + repeat("x", 40)));
    }

    private static int codePoint(String label) {
        return Integer.parseInt(label.substring(2), 16);
    }

    @Test
    public void everySharedTextCase() {
        JsonObject vectors = Vectors.load("cloud/text-rule-v1.json");
        assertEquals(CloudText.MAX_UNITS, vectors.get("maxTextUnits").getAsInt());
        JsonArray cases = vectors.getAsJsonArray("cases");
        assertEquals(60, cases.size());
        for (JsonElement element : cases) {
            JsonObject c = element.getAsJsonObject();
            String name = c.get("name").getAsString();
            String text;
            if (c.get("text").isJsonNull()) {
                StringBuilder units = new StringBuilder();
                for (JsonElement unit : c.getAsJsonArray("utf16")) units.append((char) Integer.parseInt(unit.getAsString(), 16));
                text = units.toString();
            } else {
                text = c.get("text").getAsString();
            }
            assertEquals(name, c.get("units").getAsInt(), text.length());
            String expected = c.get("problem").isJsonNull() ? null : c.get("problem").getAsString();
            CloudText.Problem got = CloudText.check(text);
            assertEquals(name, expected, got == null ? null : got.code);
            // The long texts come without their code points.
            if (c.has("codePoints") && c.get("codePoints").isJsonArray() && !c.get("text").isJsonNull()) {
                JsonArray listed = c.getAsJsonArray("codePoints");
                int i = 0;
                for (int at = 0; at < text.length(); i++) {
                    int cp = text.codePointAt(at);
                    assertEquals(name, codePoint(listed.get(i).getAsString()), cp);
                    at += Character.charCount(cp);
                }
                assertEquals(name, listed.size(), i);
            }
            if (c.has("sensitive") && !c.get("sensitive").isJsonNull()) {
                boolean sensitive = c.get("sensitive").getAsBoolean();
                assertEquals(name, sensitive, CloudText.isSensitive(text));
                assertEquals(name, sensitive, CloudText.looksSensitive(text));
            }
        }
        for (JsonElement example : vectors.getAsJsonArray("ignorableExamples")) {
            assertTrue(example.getAsString(), CloudText.isLeadingIgnorable(codePoint(example.getAsString())));
        }
    }

    @Test
    public void theIgnorableRanges() {
        assertTrue(CloudText.isDefaultIgnorable(0xAD));
        assertTrue(CloudText.isDefaultIgnorable(0xE0FFF));
        assertFalse(CloudText.isDefaultIgnorable(0xE1000));
        assertFalse(CloudText.isDefaultIgnorable('a'));
        assertFalse(CloudText.isLeadingIgnorable('/'));
        assertEquals("/a b", CloudText.withoutLeadingIgnorable("﻿  /a b"));
    }
}
