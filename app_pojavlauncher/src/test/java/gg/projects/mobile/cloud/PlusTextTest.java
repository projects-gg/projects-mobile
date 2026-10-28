package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.plus.PlusText;
import gg.projects.mobile.social.Json;

/**
 * AFK+'s persona and tag rules on the phone against the contract's vectors (afkplus-text-v1.json {@code persona};
 * afkplus-config-v2.json cases whose answer is about {@code persona.prefix.text}). A listed word of the service's own
 * list is the service's to find (the phone knows only the middle finger), as in the friends chat's tests.
 */
public class PlusTextTest {
    @Test
    public void personaTextsAsTheServiceChecksThem() {
        JsonObject vectors = Vectors.load("cloud/afkplus-text-v1.json");
        int checked = 0;
        List<String> wrong = new ArrayList<>();
        for (JsonObject c : Json.objects(vectors, "persona")) {
            JsonObject result = Json.obj(c, "result");
            String expected = Json.bool(result, "ok", false) ? null : Json.str(result, "reason");
            if ("words".equals(expected) && !Json.str(c, "text", "").contains(new String(Character.toChars(0x1F595)))) continue;
            String got = PlusText.checkPersona(Json.str(c, "field", "intro"), Json.str(c, "text", ""));
            checked++;
            if (expected == null ? got != null : !expected.equals(got)) wrong.add(Json.str(c, "name") + ": " + expected + " != " + got);
        }
        assertEquals(new ArrayList<String>(), wrong);
        assertTrue("persona cases run: " + checked, checked >= 50);
    }

    @Test
    public void tagsAsTheConfigCasesCheckThem() {
        JsonObject vectors = Vectors.load("cloud/afkplus-config-v2.json");
        int checked = 0;
        List<String> wrong = new ArrayList<>();
        for (JsonObject c : Json.objects(vectors, "cases")) {
            JsonObject input = Json.obj(c, "input");
            JsonObject prefix = Json.obj(Json.obj(input, "persona"), "prefix");
            JsonObject result = Json.obj(c, "result");
            if (prefix == null || !prefix.has("text") || !prefix.has("on") || prefix.size() != 2) continue;
            boolean aboutTag = "persona.prefix.text".equals(Json.str(result, "field"));
            if (!Json.bool(result, "ok", false) && !aboutTag) continue;
            List<String> names = new ArrayList<>(Json.strings(Json.obj(input, "who"), "friends"));
            names.addAll(Json.strings(input, "blocked"));
            String got = PlusText.checkPrefix(Json.str(prefix, "text", ""), names);
            String expected = aboutTag ? Json.str(result, "problem") : null;
            checked++;
            if (expected == null ? got != null : !expected.equals(got)) wrong.add(Json.str(c, "name") + ": " + expected + " != " + got);
        }
        assertEquals(new ArrayList<String>(), wrong);
        assertTrue("tag cases run: " + checked, checked >= 20);
    }

    /** The money's name ({@code trade.currency}, 28 Oct 2026): the config cases whose answer is about it, or that keep it. */
    @Test
    public void moneyNamesAsTheConfigCasesCheckThem() {
        JsonObject vectors = Vectors.load("cloud/afkplus-config-v2.json");
        int checked = 0;
        List<String> wrong = new ArrayList<>();
        for (JsonObject c : Json.objects(vectors, "cases")) {
            JsonObject trade = Json.obj(Json.obj(c, "input"), "trade");
            if (trade == null || !trade.has("currency") || !trade.get("currency").isJsonPrimitive()
                    || !trade.get("currency").getAsJsonPrimitive().isString()) {
                continue;
            }
            JsonObject result = Json.obj(c, "result");
            boolean about = "trade.currency".equals(Json.str(result, "field"));
            // Studio's switch is the service's to apply; the text rule alone is the phone's.
            if (about && !"text".equals(Json.str(result, "reason"))) continue;
            if (!about && !Json.bool(result, "ok", false)) continue;
            String got = PlusText.checkCurrency(trade.get("currency").getAsString());
            String expected = about ? Json.str(result, "problem") : null;
            checked++;
            if (expected == null ? got != null : !expected.equals(got)) wrong.add(Json.str(c, "name") + ": " + expected + " != " + got);
        }
        assertEquals(new ArrayList<String>(), wrong);
        assertTrue("money name cases run: " + checked, checked >= 12);
        assertEquals("Altın Para", PlusText.cleanCurrency("  Altın   Para "));
    }

    @Test
    public void contactDetails() {
        assertEquals("email", PlusText.contactProblem("ali@gmail"));
        assertEquals("iban", PlusText.contactProblem("TR33 0006 1005 1978 6457 8413 26"));
        assertEquals("card", PlusText.contactProblem("4111 1111 1111 1111"));
        assertEquals(null, PlusText.contactProblem("64 elmas 40 bin"));
        assertEquals("platform", PlusText.platformProblem("discorddan yaz"));
        assertEquals(null, PlusText.platformProblem("elmas satilik"));
    }
}
