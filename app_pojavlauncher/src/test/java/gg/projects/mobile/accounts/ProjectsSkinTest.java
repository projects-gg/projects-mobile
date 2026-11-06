package gg.projects.mobile.accounts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.codec.binary.Base64;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

/**
 * M053 (SKIN1 on the phone): the clean PNG the service takes (its own checks, skinpng.ts), Minecraft's legacy
 * conversion, the panel's rules over {@code GET /v1/mc} and the {@code mc} frame, and the premium lookup's reading.
 */
public class ProjectsSkinTest {
    private static final String TEX = "ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12";

    private static int[] solidSkin(int height, int color) {
        int[] argb = new int[64 * height];
        java.util.Arrays.fill(argb, color);
        return argb;
    }

    /** The service's structural check (services/social/src/skinpng.ts), in Java. */
    private static void assertServiceTakes(byte[] png, String sha256) throws Exception {
        assertTrue(png.length <= SkinPng.CLEAN_MAX);
        ByteBuffer b = ByteBuffer.wrap(png);
        byte[] sig = new byte[8];
        b.get(sig);
        assertTrue(SkinPng.looksLikePng(sig));
        List<String> types = new ArrayList<>();
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        while (b.hasRemaining()) {
            int length = b.getInt();
            byte[] type = new byte[4];
            b.get(type);
            byte[] data = new byte[length];
            b.get(data);
            int crc = b.getInt();
            CRC32 check = new CRC32();
            check.update(type);
            check.update(data);
            assertEquals((int) check.getValue(), crc);
            String name = new String(type, StandardCharsets.US_ASCII);
            types.add(name);
            if (name.equals("IHDR")) {
                assertArrayEquals(new byte[]{0, 0, 0, 64, 0, 0, 0, 64, 8, 6, 0, 0, 0}, data);
            }
            if (name.equals("IDAT")) idat.write(data);
        }
        assertEquals("IHDR", types.get(0));
        assertEquals("IEND", types.get(types.size() - 1));
        for (String t : types.subList(1, types.size() - 1)) assertEquals("IDAT", t);
        Inflater inflater = new Inflater();
        inflater.setInput(idat.toByteArray());
        byte[] raw = new byte[20000];
        int n = inflater.inflate(raw);
        assertTrue(inflater.finished());
        assertEquals(16448, n);
        for (int row = 0; row < 64; row++) assertTrue(raw[row * 257] >= 0 && raw[row * 257] <= 4);
        assertEquals(SkinPng.sha256Hex(png), sha256);
    }

    @Test
    public void aModernSkinBecomesTheServicesPng() throws Exception {
        SkinPng.Result r = SkinPng.clean(64, 64, solidSkin(64, 0xFF336699));
        assertTrue(r.ok());
        assertFalse(r.legacy);
        assertFalse(r.blackParts);
        assertNotNull(r.png);
        assertServiceTakes(r.png, r.sha256);
        assertEquals(64, r.sha256.length());
    }

    @Test
    public void aLegacySkinIsConvertedAsTheGameDoes() {
        int[] top = solidSkin(32, 0xFF000000);
        // A marked pixel of the right leg's front (x 4..8, y 20..32) lands mirrored on the left leg (x 20..24, y 52..64).
        top[20 * 64 + 4] = 0xFFFF0000;
        // The hat area is solid: "Notch transparency" clears it.
        SkinPng.Result r = SkinPng.clean(64, 32, top);
        assertTrue(r.ok());
        assertTrue(r.legacy);
        assertEquals(0xFFFF0000, r.argb[52 * 64 + 23]);
        assertEquals(0, r.argb[0 * 64 + 40] >>> 24);
        assertFalse(SkinPng.looksSlim(r.argb, true));
    }

    @Test
    public void emptyAndSeeThroughSkins() {
        assertEquals(SkinPng.Problem.EMPTY, SkinPng.clean(64, 64, solidSkin(64, 0x00000000)).problem);
        int[] holes = solidSkin(64, 0xFF808080);
        for (int x = 0; x < 32; x++) for (int y = 8; y < 16; y++) holes[y * 64 + x] = 0;
        SkinPng.Result r = SkinPng.clean(64, 64, holes);
        assertTrue(r.ok());
        assertTrue(r.blackParts);
        assertEquals(SkinPng.Problem.DIMENSIONS, SkinPng.clean(128, 128, new int[128 * 128]).problem);
        assertFalse(SkinPng.sizeFits(64, 48));
    }

    @Test
    public void slimArmsAreGuessed() {
        int[] skin = solidSkin(64, 0xFF808080);
        assertFalse(SkinPng.looksSlim(skin, false));
        skin[20 * 64 + 54] = 0;
        assertTrue(SkinPng.looksSlim(skin, false));
    }

    private static final String STATE = "{\"features\":[\"mcLink\",\"skins\"],\"health\":{\"bot\":true,\"server\":true},"
            + "\"link\":{\"state\":\"linked\",\"name\":\"RuzgarOkcu\",\"at\":1792500000,\"checkedAt\":1792600000},"
            + "\"skin\":{\"kind\":\"png\",\"texture\":\"abc123\",\"variant\":\"slim\",\"premium\":null,\"at\":1792600100,\"by\":\"launcher\"},"
            + "\"jobs\":[{\"id\":\"jq3TzK0vY8dMw2aLp5bXcQw\",\"kind\":\"skin\",\"state\":\"working\",\"name\":\"RuzgarOkcu\",\"source\":\"png\","
            + "\"createdAt\":1,\"updatedAt\":2}],\"limits\":{\"skinsPerDay\":3,\"skinsLeft\":2,\"resetAt\":1792627200,\"linkTriesLeft\":5},"
            + "\"blocked\":null,\"limited\":null}";

    private static ProjectsSkinState.State state(String json) {
        return ProjectsSkinState.State.read(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    public void theServicesStateReads() {
        ProjectsSkinState.State s = state(STATE);
        assertTrue(s.skinsOn());
        assertTrue(s.linkOn());
        assertTrue(s.botHealthy);
        assertEquals(ProjectsSkinState.LinkState.LINKED, s.link.state);
        assertEquals("abc123", s.skin.texture);
        assertTrue(s.skin.slim);
        assertEquals(2, s.skinsLeft);
        assertFalse(s.blocked);
        ProjectsSkinState.Job job = s.openJob("skin");
        assertNotNull(job);
        assertTrue(job.open());
        // The link area: this account, another, none, unknown.
        assertEquals(ProjectsSkinState.LinkArea.LINKED, ProjectsSkinState.linkArea(s, "ruzgarokcu"));
        assertEquals(ProjectsSkinState.LinkArea.OTHER, ProjectsSkinState.linkArea(s, "KarTilkisi42"));
        assertEquals(ProjectsSkinState.LinkArea.CODE,
                ProjectsSkinState.linkArea(state("{\"features\":[],\"link\":{\"state\":\"none\"}}"), "x"));
        assertEquals(ProjectsSkinState.LinkArea.CHECKING, ProjectsSkinState.linkArea(state("{\"features\":[]}"), "x"));
        // A change runs: the next waits.
        assertEquals(ProjectsSkinState.Hold.PENDING, ProjectsSkinState.hold(s, 1792600200L));
        // Checked over a day ago: once more.
        assertTrue(ProjectsSkinState.needsCheck(s, 1792600000L + 86_401));
        assertFalse(ProjectsSkinState.needsCheck(s, 1792600000L + 100));
    }

    @Test
    public void theFrameReplacesTheLinkTheSkinAndTheBlock() {
        ProjectsSkinState.State s = state(STATE);
        JsonObject frame = JsonParser.parseString("{\"t\":\"mc\",\"job\":{\"id\":\"jq3TzK0vY8dMw2aLp5bXcQw\",\"kind\":\"skin\","
                + "\"state\":\"done\",\"result\":{\"code\":\"applied\",\"texture\":\"def\",\"variant\":\"classic\"}},"
                + "\"link\":{\"state\":\"linked\",\"name\":\"RuzgarOkcu\"},\"skin\":{\"kind\":\"default\"},\"blocked\":{\"until\":null}}")
                .getAsJsonObject();
        ProjectsSkinState.State after = s.withFrame(frame);
        assertNull(after.openJob("skin"));
        assertTrue(after.skin.isDefault());
        assertTrue(after.blocked);
        assertEquals(0, after.blockedUntil);
        assertEquals(ProjectsSkinState.Hold.BLOCKED, ProjectsSkinState.hold(after, 1L));
        ProjectsSkinState.Job job = ProjectsSkinState.Job.read(frame.getAsJsonObject("job"));
        assertTrue(job.succeeded());
        assertNull(ProjectsSkinState.Job.read(JsonParser.parseString("{\"id\":\"bad\"}").getAsJsonObject()));
    }

    @Test
    public void refusalsAsTheDesktopReadsThem() {
        assertEquals(ProjectsSkinState.Refusal.SESSION, ProjectsSkinState.refusal(401, null, null, null));
        assertEquals(ProjectsSkinState.Refusal.OFF, ProjectsSkinState.refusal(503, "unavailable", null, "off"));
        assertEquals(ProjectsSkinState.Refusal.OFF, ProjectsSkinState.refusal(404, "not_found", null, null));
        assertEquals(ProjectsSkinState.Refusal.UNREACHABLE, ProjectsSkinState.refusal(503, "unavailable", null, "bot"));
        assertEquals(ProjectsSkinState.Refusal.QUOTA, ProjectsSkinState.refusal(429, "rate_limited", "skin_changes", null));
        assertEquals(ProjectsSkinState.Refusal.TRIES, ProjectsSkinState.refusal(429, "rate_limited", "link_tries", null));
        assertEquals(ProjectsSkinState.Refusal.INVALID_PIN, ProjectsSkinState.refusal(400, "invalid_pin", null, null));
        assertEquals(ProjectsSkinState.Refusal.INVALID_SKIN, ProjectsSkinState.refusal(413, "payload_too_large", null, null));
        assertEquals(ProjectsSkinState.Refusal.UNREACHABLE, ProjectsSkinState.refusal(409, "discord_required", null, null));
        assertEquals("482913", ProjectsSkinState.pinDigits("48 29-13x9"));
        assertTrue(ProjectsSkinState.premiumName("Dream"));
        assertFalse(ProjectsSkinState.premiumName("ab"));
        assertFalse(ProjectsSkinState.premiumName("Rüzgar"));
    }

    @Test
    public void thePremiumLookupReadsTheSignedTextures() {
        String id = "0123456789abcdef0123456789abcdef";
        String value = Base64.encodeBase64String(("{\"profileId\":\"" + id + "\",\"textures\":{\"SKIN\":{\"url\":"
                + "\"http://textures.minecraft.net/texture/" + TEX + "\",\"metadata\":{\"model\":\"slim\"}}}}")
                .getBytes(StandardCharsets.UTF_8));
        JsonObject session = JsonParser.parseString("{\"id\":\"" + id + "\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + value + "\",\"signature\":\"c2ln\"}]}").getAsJsonObject();
        MojangSkinLookup.Result r = MojangSkinLookup.fromSession(session, id, "Dream");
        assertEquals(MojangSkinLookup.Kind.FOUND, r.kind);
        assertTrue(r.slim);
        assertEquals(TEX, r.texture);
        assertEquals("c2ln", r.signature);
        assertEquals(value, r.value);
        // Another host, or no skin: never sent.
        assertNull(MojangSkinLookup.textureOf("https://example.com/texture/" + TEX));
        assertEquals(MojangSkinLookup.Kind.NO_SKIN, MojangSkinLookup.fromSession(
                JsonParser.parseString("{\"properties\":[]}").getAsJsonObject(), id, "Dream").kind);
        String other = Base64.encodeBase64String("{\"profileId\":\"ffff\",\"textures\":{}}".getBytes(StandardCharsets.UTF_8));
        assertEquals(MojangSkinLookup.Kind.UNREACHABLE, MojangSkinLookup.fromSession(JsonParser.parseString(
                "{\"properties\":[{\"name\":\"textures\",\"value\":\"" + other + "\",\"signature\":\"x\"}]}").getAsJsonObject(), id, "Dream").kind);
    }
}
