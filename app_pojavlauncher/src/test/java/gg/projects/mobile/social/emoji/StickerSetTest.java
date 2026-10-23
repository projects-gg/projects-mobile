package gg.projects.mobile.social.emoji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The sticker set on the phone (the desktop's packages/stickers/src/manifest.test.ts): the copied
 * manifest, its 32 stickers, and a drawable for each, the very file the manifest names.
 */
public class StickerSetTest {
    private static final File MANIFEST = new File("src/main/assets/" + StickerSet.PL1_ASSET);
    private static final File DRAWABLES = new File("src/main/res/drawable-nodpi");
    static final StickerSet PL1 = read();

    private static StickerSet read() {
        try (InputStream in = new FileInputStream(MANIFEST)) {
            return StickerSet.parse(in);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonObject manifest() throws IOException {
        byte[] bytes = Files.readAllBytes(MANIFEST.toPath());
        return JsonParser.parseString(new String(bytes, Charset.forName("UTF-8"))).getAsJsonObject();
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b & 0xFF));
        return out.toString();
    }

    @Test
    public void holds32StickersEightInEachOfTheFourThemesInOrder() {
        assertEquals("pl1", PL1.id());
        assertEquals(1, PL1.version());
        assertEquals(32, PL1.stickers().size());
        List<String> themes = new ArrayList<>();
        for (StickerSet.Theme theme : PL1.themes()) themes.add(theme.id);
        assertEquals(Arrays.asList("reactions", "game", "server", "greetings"), themes);
        for (StickerSet.Theme theme : PL1.themes()) assertEquals(8, PL1.inTheme(theme.id).size());
        for (int i = 0; i < 32; i++) assertEquals(i + 1, PL1.stickers().get(i).order);
        assertEquals("Tepkiler", PL1.themes().get(0).name("tr"));
        assertEquals("Reactions", PL1.themes().get(0).name("en"));
    }

    @Test
    public void givesEveryStickerItsNamesWordsAndOneKnownMotionUsingEveryMotion() {
        Set<String> used = new HashSet<>();
        String turkish = EmojiDataTest.t("{E7}{11F}{131}{F6}{15F}{FC}{C7}{11E}{130}{D6}{15E}{DC}");
        for (StickerSet.Sticker sticker : PL1.stickers()) {
            assertFalse(sticker.nameTr.isEmpty());
            for (int i = 0; i < sticker.nameEn.length(); i++) {
                assertTrue(sticker.id, turkish.indexOf(sticker.nameEn.charAt(i)) < 0);
            }
            assertFalse(sticker.keywordsTr.isEmpty());
            assertFalse(sticker.keywordsEn.isEmpty());
            assertTrue(sticker.id, StickerMotion.isKnown(sticker.motion));
            used.add(sticker.motion);
        }
        assertEquals(new HashSet<>(StickerMotion.NAMES), used);
        assertEquals(StickerMotion.NAMES, new ArrayList<>(PL1.motions().keySet()));
    }

    @Test
    public void hasADrawableForEverySticker_theFileTheManifestNames() throws Exception {
        JsonArray stickers = manifest().getAsJsonArray("stickers");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < stickers.size(); i++) {
            JsonObject raw = stickers.get(i).getAsJsonObject();
            String id = raw.get("id").getAsString();
            JsonObject android = raw.getAsJsonObject("files").getAsJsonObject("android");
            String drawable = PL1.drawableName(id);
            assertEquals("pl1_" + id, drawable);
            assertEquals(drawable, PL1.byRef("pl1/" + id).drawable);
            File file = new File(DRAWABLES, drawable + ".webp");
            assertTrue(file.getPath(), file.isFile());
            assertEquals(file.getPath(), android.get("bytes").getAsLong(), file.length());
            assertEquals(file.getPath(), android.get("sha256").getAsString(), sha256(file));
            names.add(file.getName());
        }
        // And no drawable of the set the manifest does not name.
        for (String name : DRAWABLES.list()) {
            if (name.startsWith("pl1_")) assertTrue(name, names.contains(name));
        }
    }

    @Test
    public void refusesABrokenManifest() throws IOException {
        String[][] cases = {
            {"two stickers share an id", "dup"},
            {"ref", "ref"},
            {"unknown motion", "motion"},
            {"unknown theme", "theme"},
            {"atlas cell taken", "cell"},
            {"outside the atlas", "outside"},
            {"order", "order"},
            {"launcher image path", "launcher"},
            {"android image path", "android"},
            {"version", "version"},
            {"motion glitch", "motions"},
        };
        for (String[] c : cases) {
            JsonObject broken = manifest();
            JsonArray stickers = broken.getAsJsonArray("stickers");
            JsonObject first = stickers.get(0).getAsJsonObject();
            JsonObject second = stickers.get(1).getAsJsonObject();
            switch (c[1]) {
                case "dup": {
                    JsonObject copy = first.deepCopy();
                    copy.addProperty("order", 2);
                    copy.add("atlas", second.get("atlas"));
                    stickers.set(1, copy);
                    break;
                }
                case "ref": first.addProperty("ref", "pl2/lol"); break;
                case "motion": first.addProperty("motion", "spin"); break;
                case "theme": first.addProperty("theme", "music"); break;
                case "cell": second.add("atlas", first.get("atlas")); break;
                case "outside": {
                    JsonObject cell = new JsonObject();
                    cell.addProperty("column", 8);
                    cell.addProperty("row", 0);
                    first.add("atlas", cell);
                    break;
                }
                case "order": first.addProperty("order", 40); break;
                case "launcher":
                    first.getAsJsonObject("files").getAsJsonObject("launcher").addProperty("path", "launcher/x.webp");
                    break;
                case "android":
                    first.getAsJsonObject("files").getAsJsonObject("android").addProperty("path", "android/x.webp");
                    break;
                case "version": broken.addProperty("version", 2); break;
                default: broken.getAsJsonObject("motions").remove("glitch"); break;
            }
            try {
                StickerSet.parse(broken);
                fail(c[0]);
            } catch (IllegalArgumentException expected) {
                assertTrue(c[0] + ": " + expected.getMessage(), expected.getMessage().contains(c[0]));
            }
        }
        try {
            StickerSet.parse("[]");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().startsWith("Sticker manifest: "));
        }
    }

    @Test
    public void findsTheStickersOfThisBuildAndNothingElse() {
        StickerSet.Sticker gg = PL1.byRef("pl1/gg");
        assertEquals("GG", gg.name("tr"));
        assertEquals("GG", gg.name("en"));
        assertEquals("pop", gg.motion);
        assertEquals("GG", gg.caption);
        assertNull(PL1.byRef("pl1/lol").caption);
        assertNull(PL1.byRef("pl1/nope"));
        assertNull(PL1.byRef(null));
        assertTrue(PL1.isKnown("pl1/hi"));
        assertFalse(PL1.isKnown("pl2/hi"));
        assertFalse(PL1.isKnown("PL1/HI"));
        assertFalse(PL1.isKnown("pl1/../hi"));
        assertFalse(PL1.isKnown(null));
        assertTrue(StickerSet.isRef("pl1/a-b"));
        assertFalse(StickerSet.isRef("pl1/a/b"));
        assertFalse(StickerSet.isRef("/gg"));
        assertFalse(StickerSet.isRef("pl1/"));
        assertFalse(StickerSet.isRef("pl123456789/gg"));
        assertFalse(StickerSet.isRef("p-1/gg"));
    }

    @Test
    public void listsTheRecentStickersItKnowsInTheirOrder() {
        List<StickerSet.Sticker> recent = PL1.recent(Arrays.asList("pl1/hi", "pl9/new", "pl1/gg"));
        assertEquals(2, recent.size());
        assertEquals("pl1/hi", recent.get(0).ref);
        assertEquals("pl1/gg", recent.get(1).ref);
    }
}
