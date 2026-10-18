package gg.projects.mobile.accounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.UUID;

/** Heads drawn on the phone (the desktop's pl-auth avatar.rs tests). */
public class SkinFaceTest {
    private static final int FACE = 0xFF336699;
    private static final int HAT = 0xFFAA2211;
    private static final int CLEAR = 0x00000000;

    private interface Painter {
        int at(int x, int y);
    }

    private static int[] texture(int width, int height, Painter painter) {
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) argb[y * width + x] = painter.at(x, y);
        return argb;
    }

    private static boolean inFace(int x, int y) {
        return x >= 8 && x < 16 && y >= 8 && y < 16;
    }

    private static boolean inHat(int x, int y) {
        return x >= 40 && x < 48 && y >= 8 && y < 16;
    }

    @Test
    public void defaultSkinsMatchTheGame() {
        // Computed with Java's UUID.nameUUIDFromBytes / hashCode / Math.floorMod (the desktop's table).
        Object[][] cases = {
                {"Notch", "makena", true}, {"Steve", "efe", false}, {"Alex", "makena", true},
                {"EjderAvcisi", "steve", false}, {"KorAtesi_", "noor", true}, {"Madenci07", "steve", true},
                {"abc", "steve", false}, {"A_1234567890_bcd", "alex", true}, {"jeb_", "ari", true},
                {"Dinnerbone", "efe", true}};
        for (Object[] row : cases) {
            SkinFace.DefaultSkin skin = SkinFace.defaultSkin(OfflineAccounts.uuid((String) row[0]));
            assertEquals((String) row[0], row[1], skin.name);
            assertEquals((String) row[0], row[2], skin.slim);
        }
        assertEquals("alex", SkinFace.defaultSkin(new UUID(0, 0)).name);
        assertEquals("assets/minecraft/textures/entity/player/wide/efe.png",
                SkinFace.defaultSkin(OfflineAccounts.uuid("Steve")).jarEntry());
    }

    @Test
    public void theHeadIsTheFaceUnderTheHat() {
        int[] skin = texture(64, 64, (x, y) -> inFace(x, y) ? FACE : inHat(x, y) && y < 12 ? HAT : CLEAR);
        int[] head = SkinFace.face(skin, 64, 64);
        assertEquals(64, head.length);
        assertEquals(HAT, head[0]);
        assertEquals(HAT, head[3 * 8 + 7]);
        assertEquals(FACE, head[4 * 8]);
        assertEquals(FACE, head[63]);
    }

    @Test
    public void halfTransparentHatsBlendAndFacesAreOpaque() {
        int[] skin = texture(64, 64, (x, y) -> inFace(x, y) ? 0x00646464 : inHat(x, y) ? 0x80C80000 : CLEAR);
        int[] head = SkinFace.face(skin, 64, 64);
        assertEquals(0xFF963232, head[0]);
    }

    @Test
    public void legacySkinsWithASolidHatAreaHaveNoHat() {
        int[] solid = texture(64, 32, (x, y) -> inFace(x, y) ? FACE : x >= 32 && y < 16 ? HAT : CLEAR);
        for (int pixel : SkinFace.face(solid, 64, 32)) assertEquals(FACE, pixel);
        int[] withHat = texture(64, 32, (x, y) -> inFace(x, y) ? FACE : inHat(x, y) ? HAT : CLEAR);
        for (int pixel : SkinFace.face(withHat, 64, 32)) assertEquals(HAT, pixel);
    }

    @Test
    public void highResolutionTexturesKeepTheirDetail() {
        int[] skin = texture(128, 128, (x, y) -> x >= 16 && x < 32 && y >= 16 && y < 32 ? FACE : CLEAR);
        int[] head = SkinFace.face(skin, 128, 128);
        assertEquals(16, SkinFace.faceSize(128));
        assertEquals(256, head.length);
        for (int pixel : head) assertEquals(FACE, pixel);
    }

    @Test
    public void picturesThatAreNotSkins() {
        assertNull(SkinFace.face(new int[32 * 32], 32, 32));
        assertNull(SkinFace.face(new int[64 * 48], 64, 48));
        assertNull(SkinFace.face(new int[10], 64, 64));
        assertNull(SkinFace.face(null, 64, 64));
    }

    @Test
    public void texturesOnlyComeFromMojang() {
        assertEquals("https://textures.minecraft.net/texture/ABC123", SkinFace.mojangTextureUrl("http://textures.minecraft.net/texture/ABC123"));
        assertEquals("https://textures.minecraft.net/texture/abc", SkinFace.mojangTextureUrl("https://textures.minecraft.net/texture/abc"));
        for (String bad : new String[]{"https://evil.example/texture/abc", "https://textures.minecraft.net/texture/abc/def",
                "https://textures.minecraft.net/texture/not-hex", "https://textures.minecraft.net/skin/abc",
                "https://textures.minecraft.net/texture/", "https://textures.minecraft.net/texture/..%2F..",
                "https://mc-heads.net/head/abc/100", "ftp://textures.minecraft.net/texture/abc",
                "https://user@textures.minecraft.net/texture/abc", "https://textures.minecraft.net:8443/texture/abc", null}) {
            assertNull(String.valueOf(bad), SkinFace.mojangTextureUrl(bad));
        }
        assertTrue(SkinFace.DEFAULT_SKIN_NAMES.length == 9);
    }
}
