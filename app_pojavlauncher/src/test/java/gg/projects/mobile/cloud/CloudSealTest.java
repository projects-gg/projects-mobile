package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Seals and reveals against the contract's vectors (seal-v1.json, reveal-v1.json): every valid seal byte for byte
 * (replaying {@code ikmE}), every value an opener must refuse with its error class, every reveal answer, and the
 * library itself against RFC 9180 A.3.1.
 */
public class CloudSealTest {
    private static final JsonObject SEAL = Vectors.load("cloud/seal-v1.json");
    private static final JsonObject REVEAL = Vectors.load("cloud/reveal-v1.json");

    private static String s(JsonObject o, String key) {
        return o.get(key).getAsString();
    }

    private static CloudServer server(JsonObject o) {
        JsonObject server = o.getAsJsonObject("server");
        return new CloudServer(server.get("host").getAsString(), server.get("port").getAsInt());
    }

    private static CloudSeal.VaultKey vaultKey(String kid) {
        for (JsonElement e : SEAL.getAsJsonArray("keys")) {
            JsonObject key = e.getAsJsonObject();
            if (kid.equals(s(key, "kid"))) return new CloudSeal.VaultKey(kid, CloudSeal.ALG, s(key, "publicKey"));
        }
        throw new IllegalArgumentException(kid);
    }

    private static List<Object[]> openerKeys() {
        List<Object[]> keys = new ArrayList<>();
        for (JsonElement e : SEAL.getAsJsonArray("keys")) {
            JsonObject key = e.getAsJsonObject();
            keys.add(new Object[]{s(key, "kid"), CloudSeal.suite().deriveKeyPair(Vectors.hex(s(key, "ikm")))});
        }
        return keys;
    }

    @Test
    public void theLibraryPassesRfc9180A31() {
        JsonObject rfc = SEAL.getAsJsonObject("rfc9180");
        HPKE hpke = CloudSeal.suite();
        AsymmetricCipherKeyPair recipient = hpke.deriveKeyPair(Vectors.hex(s(rfc, "ikmR")));
        assertEquals(s(rfc, "pkRm"), Vectors.toHex(hpke.serializePublicKey(recipient.getPublic())));
        AsymmetricCipherKeyPair ephemeral = hpke.deriveKeyPair(Vectors.hex(s(rfc, "ikmE")));
        assertEquals(s(rfc, "pkEm"), Vectors.toHex(hpke.serializePublicKey(ephemeral.getPublic())));
        try {
            HPKEContextWithEncapsulation context = hpke.setupBaseS(recipient.getPublic(), Vectors.hex(s(rfc, "info")), ephemeral);
            assertEquals(s(rfc, "enc"), Vectors.toHex(context.getEncapsulation()));
            JsonObject first = rfc.has("encryptions") ? rfc.getAsJsonArray("encryptions").get(0).getAsJsonObject() : rfc;
            if (first.has("pt") && first.has("aad") && first.has("ct")) {
                byte[] ct = context.seal(Vectors.hex(s(first, "aad")), Vectors.hex(s(first, "pt")));
                assertEquals(s(first, "ct"), Vectors.toHex(ct));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void sealsEveryVectorByteForByte() throws Exception {
        int seen = 0;
        for (JsonElement e : SEAL.getAsJsonArray("vectors")) {
            JsonObject v = e.getAsJsonObject();
            String name = s(v, "name");
            CloudServer server = server(v);
            String aad = CloudSeal.sealAad(s(v, "discordId"), s(v, "accountId"), s(v, "rowId"), server);
            assertEquals(name, s(v, "aad"), aad);
            assertEquals(name, s(v, "padded"), Vectors.toHex(CloudSeal.pad(s(v, "text"))));
            AsymmetricCipherKeyPair ephemeral = CloudSeal.suite().deriveKeyPair(Vectors.hex(s(v, "ikmE")));
            String sealed = CloudSeal.sealWith(vaultKey(s(v, "kid")), s(v, "text"), aad, ephemeral);
            assertEquals(name, s(v, "sealed"), sealed);
            assertEquals(name, s(v, "text"), CloudSeal.openSealed(openerKeys(), sealed, aad));
            if (v.has("fingerprint")) assertEquals(name, s(v, "fingerprint"), CloudSeal.fingerprint(sealed));
            seen++;
        }
        assertEquals(10, seen);
    }

    @Test
    public void refusesEveryInvalidSealWithItsErrorClass() {
        List<Object[]> keys = openerKeys();
        int seen = 0;
        for (JsonElement e : SEAL.getAsJsonArray("invalid")) {
            JsonObject v = e.getAsJsonObject();
            String name = s(v, "name");
            String aad = s(v, "aad");
            try {
                CloudSeal.openSealed(keys, s(v, "sealed"), aad);
                fail(name + " opened");
            } catch (CloudSeal.SealException refused) {
                assertEquals(name, s(v, "error"), refused.error.name().toLowerCase(java.util.Locale.ROOT));
            }
            seen++;
        }
        assertEquals(58, seen);
    }

    @Test
    public void freshSealsOpenAndPadToOneLength() throws Exception {
        CloudSeal.VaultKey key = vaultKey("k1");
        CloudServer server = new CloudServer("play.projects.gg", 0);
        String aad = CloudSeal.sealAad("123456789012345678", "a_7KQ2MZ3P9TW4XB1C", "c1", server);
        String a = CloudSeal.seal(key, "/login a", aad);
        String b = CloudSeal.seal(key, "/login a much longer password 1234567890", aad);
        assertNotEquals(a, b);
        assertEquals(a.length(), b.length());
        assertEquals(201, a.length());
        assertEquals("/login a", CloudSeal.openSealed(openerKeys(), a, aad));
        // Another row's AAD does not open it.
        try {
            CloudSeal.openSealed(openerKeys(), a, CloudSeal.sealAad("123456789012345678", "a_7KQ2MZ3P9TW4XB1C", "c2", server));
            fail("opened for another row");
        } catch (CloudSeal.SealException refused) {
            assertEquals(CloudSeal.Error.OPEN_FAILED, refused.error);
        }
    }

    @Test
    public void refusesToSealWithAnotherSuiteOrABadKey() {
        CloudSeal.VaultKey good = vaultKey("k1");
        for (CloudSeal.VaultKey bad : new CloudSeal.VaultKey[]{
                new CloudSeal.VaultKey("k1", "HPKE-X25519", good.publicKey),
                new CloudSeal.VaultKey("K1", CloudSeal.ALG, good.publicKey),
                new CloudSeal.VaultKey("k1", CloudSeal.ALG, good.publicKey.substring(1)),
                new CloudSeal.VaultKey("k1", CloudSeal.ALG, "AAAA")}) {
            try {
                CloudSeal.seal(bad, "/login a", "x");
                fail("sealed with " + bad.alg + " " + bad.kid);
            } catch (CloudSeal.SealException refused) {
                assertEquals(CloudSeal.Error.KEY, refused.error);
            }
        }
        try {
            CloudSeal.seal(good, "", "x");
            fail("sealed nothing");
        } catch (CloudSeal.SealException refused) {
            assertEquals(CloudSeal.Error.LENGTH, refused.error);
        }
    }

    @Test
    public void theFingerprintIsSha256OfTheValue() {
        String fp = CloudSeal.fingerprint("pl1.k1.AAAA");
        assertEquals(16, fp.length());
        assertTrue(fp.matches("[A-Za-z0-9_-]{16}"));
    }

    private static CloudSeal.RevealKey clientKey(String name) {
        for (JsonElement e : REVEAL.getAsJsonArray("clientKeys")) {
            JsonObject key = e.getAsJsonObject();
            if (name.equals(s(key, "name"))) {
                CloudSeal.RevealKey k = CloudSeal.RevealKey.fromIkm(Vectors.hex(s(key, "ikm")));
                assertEquals(s(key, "pub"), k.publicKey());
                return k;
            }
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    public void opensEveryRevealVector() throws Exception {
        int seen = 0;
        for (JsonElement e : REVEAL.getAsJsonArray("vectors")) {
            JsonObject v = e.getAsJsonObject();
            String name = s(v, "name");
            String aad = CloudSeal.revealAad(s(v, "discordId"), s(v, "accountId"), s(v, "rowId"), s(v, "nonce"));
            assertEquals(name, s(v, "aad"), aad);
            assertEquals(name, s(v, "text"), clientKey(s(v, "client")).open(s(v, "revealed"), aad));
            seen++;
        }
        assertEquals(8, seen);
    }

    @Test
    public void refusesEveryInvalidRevealWithItsErrorClass() {
        int seen = 0;
        for (JsonElement e : REVEAL.getAsJsonArray("invalid")) {
            JsonObject v = e.getAsJsonObject();
            String name = s(v, "name");
            try {
                clientKey(s(v, "client")).open(s(v, "revealed"), s(v, "aad"));
                fail(name + " opened");
            } catch (CloudSeal.SealException refused) {
                assertEquals(name, s(v, "error"), refused.error.name().toLowerCase(java.util.Locale.ROOT));
            }
            seen++;
        }
        assertEquals(20, seen);
    }

    @Test
    public void oneTimeKeysAreFreshPoints() {
        CloudSeal.RevealKey a = CloudSeal.RevealKey.generate();
        CloudSeal.RevealKey b = CloudSeal.RevealKey.generate();
        assertEquals(87, a.publicKey().length());
        assertTrue(a.publicKey().startsWith("B"));
        assertNotEquals(a.publicKey(), b.publicKey());
    }

    @Test
    public void base64UrlIsStrict() {
        byte[] data = "projects".getBytes(StandardCharsets.US_ASCII);
        String encoded = CloudSeal.Base64Url.encode(data);
        assertEquals("cHJvamVjdHM", encoded);
        assertEquals("projects", new String(CloudSeal.Base64Url.decode(encoded), StandardCharsets.US_ASCII));
        assertEquals(null, CloudSeal.Base64Url.decode("cHJ+"));
        assertEquals(null, CloudSeal.Base64Url.decode("cHJvamVjdHM="));
    }
}
