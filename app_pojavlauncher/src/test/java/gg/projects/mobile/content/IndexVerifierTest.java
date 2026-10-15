package gg.projects.mobile.content;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class IndexVerifierTest {

    @Test
    public void productionKeyHasTheDocumentedKeyId() throws Exception {
        assertEquals(1, ContentConfig.TRUSTED_KEYS.size());
        byte[] key = IndexVerifier.decodePublicKey(ContentConfig.TRUSTED_KEYS.get(0));
        assertEquals(32, key.length);
        assertEquals("5125c0d365b019ce", IndexVerifier.keyId(key));
        assertEquals("5125c0d365b019ce", IndexVerifier.production().trustedKeys().get(0).keyId);
    }

    @Test
    public void signedFixtureVerifies() throws Exception {
        IndexVerifier.TrustedKey key = Fixtures.verifier().verify(Fixtures.bytes("v1/index.json"), Fixtures.bytes("v1/index.json.sig"));
        assertEquals("c79706120435b544", key.keyId);
    }

    @Test
    public void productionKeyDoesNotTrustTheThrowawayKey() {
        expect(ContentException.Kind.UNTRUSTED_KEY, () ->
                IndexVerifier.production().verify(Fixtures.bytes("v1/index.json"), Fixtures.bytes("v1/index.json.sig")));
    }

    @Test
    public void everySingleByteChangeIsRejected() throws Exception {
        IndexVerifier verifier = Fixtures.verifier();
        byte[] index = Fixtures.bytes("v1/index.json");
        byte[] sig = Fixtures.bytes("v1/index.json.sig");
        for (int i = 0; i < index.length; i += 7) {
            byte[] tampered = index.clone();
            tampered[i] ^= 0x01;
            final byte[] bytes = tampered;
            expect(ContentException.Kind.BAD_SIGNATURE, () -> verifier.verify(bytes, sig));
        }
        byte[] longer = Arrays.copyOf(index, index.length + 1);
        longer[index.length] = '\n';
        expect(ContentException.Kind.BAD_SIGNATURE, () -> verifier.verify(longer, sig));
    }

    @Test
    public void unknownKeyIdIsRejected() throws Exception {
        expect(ContentException.Kind.UNTRUSTED_KEY, () ->
                Fixtures.verifier().verify(Fixtures.bytes("v1/index.json"), Fixtures.bytes("index.other-key.sig")));
    }

    @Test
    public void rotationAcceptsAnyTrustedKey() throws Exception {
        List<String> keys = new ArrayList<>();
        keys.add(Fixtures.publicKey());
        keys.add(Fixtures.otherPublicKey());
        IndexVerifier both = new IndexVerifier(keys);
        assertEquals("c79706120435b544", both.verify(Fixtures.bytes("v1/index.json"), Fixtures.bytes("v1/index.json.sig")).keyId);
        assertEquals("f9e8aaee83ff3315", both.verify(Fixtures.bytes("v1/index.json"), Fixtures.bytes("index.other-key.sig")).keyId);
    }

    @Test
    public void keyIdMustMatchTheSigningKey() throws Exception {
        // Signed by the other key but labelled with the main key's id: must not verify.
        List<String> keys = new ArrayList<>();
        keys.add(Fixtures.publicKey());
        keys.add(Fixtures.otherPublicKey());
        IndexVerifier both = new IndexVerifier(keys);
        expect(ContentException.Kind.BAD_SIGNATURE, () ->
                both.verify(Fixtures.bytes("v1/index.json"), Fixtures.bytes("index.mislabelled.sig")));
    }

    @Test
    public void malformedSignaturesAreRejected() throws Exception {
        IndexVerifier verifier = Fixtures.verifier();
        byte[] index = Fixtures.bytes("v1/index.json");
        for (String sig : new String[]{
                "{\"keyId\":\"c79706120435b544\",\"signature\":\"not base64!\"}",
                "{\"keyId\":\"c79706120435b544\",\"signature\":\"" + Base64.toBase64String(new byte[63]) + "\"}",
                "{\"keyId\":\"c79706120435b544\",\"signature\":\"" + Base64.toBase64String(new byte[64]) + "\"}",
                "{\"keyId\":\"c79706120435b544\"}",
                "not json",
                ""}) {
            expect(ContentException.Kind.BAD_SIGNATURE, () -> verifier.verify(index, sig.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void keyIdIsCaseInsensitiveAndUnknownFieldsAreIgnored() throws Exception {
        String sig = Fixtures.text("v1/index.json.sig").replace("c79706120435b544", "C79706120435B544")
                .replace("{", "{\"future\": 1,");
        Fixtures.verifier().verify(Fixtures.bytes("v1/index.json"), sig.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void nonCanonicalScalarIsRejected() throws Exception {
        // S + L is the same point equation but not canonical; strict verifiers refuse it.
        byte[] index = Fixtures.bytes("v1/index.json");
        String good = Fixtures.text("v1/index.json.sig");
        String b64 = good.substring(good.indexOf("\"signature\": \"") + 14, good.lastIndexOf('"'));
        byte[] sig = Base64.decode(b64);
        byte[] s = Arrays.copyOfRange(sig, 32, 64);
        byte[] l = Hex.decode("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010"); // little-endian L
        int carry = 0;
        for (int i = 0; i < 32; i++) {
            int sum = (s[i] & 0xFF) + (l[i] & 0xFF) + carry;
            s[i] = (byte) sum;
            carry = sum >> 8;
        }
        if (carry != 0) return; // S + L overflowed 256 bits; nothing to test for this signature
        System.arraycopy(s, 0, sig, 32, 32);
        String tampered = "{\"keyId\":\"c79706120435b544\",\"signature\":\"" + Base64.toBase64String(sig) + "\"}";
        expect(ContentException.Kind.BAD_SIGNATURE, () -> Fixtures.verifier().verify(index, tampered.getBytes(StandardCharsets.UTF_8)));
    }

    /** RFC 8032 section 7.1, tests 1 to 3. */
    @Test
    public void rfc8032Vectors() throws Exception {
        String[][] vectors = {
                {"d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
                        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"},
                {"3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
                        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"},
                {"fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
                        "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"},
        };
        for (String[] v : vectors) {
            byte[] pk = Hex.decode(v[0]);
            byte[] msg = Hex.decode(v[1]);
            IndexVerifier verifier = new IndexVerifier(Collections.singletonList(Base64.toBase64String(pk)));
            IndexVerifier.SignatureFile sig = new IndexVerifier.SignatureFile();
            sig.keyId = IndexVerifier.keyId(pk);
            sig.signature = Base64.toBase64String(Hex.decode(v[2]));
            verifier.verify(msg, sig);
            byte[] other = Arrays.copyOf(msg, msg.length + 1);
            expect(ContentException.Kind.BAD_SIGNATURE, () -> verifier.verify(other, sig));
        }
    }

    /** Cross-check against the JDK's own Ed25519 (an independent implementation). */
    @Test
    public void agreesWithTheJdkImplementation() throws Exception {
        SecureRandom random = new SecureRandom();
        for (int round = 0; round < 25; round++) {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] spki = pair.getPublic().getEncoded();
            byte[] raw = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
            byte[] message = new byte[random.nextInt(2048)];
            random.nextBytes(message);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(message);
            IndexVerifier.SignatureFile sig = new IndexVerifier.SignatureFile();
            sig.keyId = IndexVerifier.keyId(raw);
            sig.signature = Base64.toBase64String(signer.sign());
            IndexVerifier verifier = new IndexVerifier(Collections.singletonList(Base64.toBase64String(raw)));
            verifier.verify(message, sig);
        }
    }

    @Test
    public void publicKeysMustBe32Bytes() {
        for (String key : new String[]{"", "%%%", Base64.toBase64String(new byte[31]), Base64.toBase64String(new byte[33])}) {
            try {
                IndexVerifier.decodePublicKey(key);
                fail("accepted " + key);
            } catch (ContentException e) {
                assertEquals(ContentException.Kind.INVALID, e.kind);
            }
        }
        assertArrayEquals(new byte[32], IndexVerifier.decodeBase64(Base64.toBase64String(new byte[32])));
        assertTrue(IndexVerifier.decodeBase64("AAAA AAAA") == null);
    }

    interface Action {
        void run() throws Exception;
    }

    static void expect(ContentException.Kind kind, Action action) {
        try {
            action.run();
            fail("expected " + kind);
        } catch (ContentException e) {
            assertEquals(e.toString(), kind, e.kind);
        } catch (Exception e) {
            throw new AssertionError("unexpected " + e, e);
        }
    }
}
