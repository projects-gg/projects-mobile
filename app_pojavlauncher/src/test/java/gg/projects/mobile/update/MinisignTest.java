package gg.projects.mobile.update;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * APK-UPD: minisign as the launcher's updater key signs (tauri signer): the real signer's output, the key's
 * spellings, and every way a signature file is refused.
 */
public class MinisignTest {
    private static final byte[] CHANNEL = UpdateFixtures.resource("android-stable.json");
    private static final byte[] CHANNEL_SIG = UpdateFixtures.resource("android-stable.json.sig");

    private static List<Minisign.PublicKey> keys(String... texts) throws UpdateException {
        return Minisign.publicKeys(Arrays.asList(texts));
    }

    private static void refused(UpdateException.Kind kind, byte[] data, byte[] sig, List<Minisign.PublicKey> keys) {
        try {
            Minisign.verify(data, sig, keys);
            fail("accepted a signature that should be refused");
        } catch (UpdateException e) {
            assertEquals(e.getMessage(), kind, e.kind);
        }
    }

    @Test
    public void blake2b512KnownAnswer() {
        // RFC 7693, appendix A.
        assertEquals("ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1"
                        + "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
                Hex.toHexString(Minisign.blake2b512("abc".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    public void theTauriSignersOutputVerifies() throws UpdateException {
        Minisign.Verified verified = Minisign.verify(CHANNEL, CHANNEL_SIG, keys(UpdateFixtures.FIXTURE_KEY));
        assertEquals("E9035FB7AD485BF1", verified.key.id());
        Map<String, String> fields = verified.fields();
        assertEquals("android-stable.json", fields.get("file"));
        assertEquals("0.4.6", fields.get("version"));
        assertEquals("1793362450", fields.get("timestamp"));
        // The minisign text itself (not its base64) is read the same way.
        byte[] text = Base64.decode(new String(CHANNEL_SIG, StandardCharsets.US_ASCII).trim());
        assertEquals("0.4.6", Minisign.verify(CHANNEL, text, keys(UpdateFixtures.FIXTURE_KEY)).fields().get("version"));
    }

    @Test
    public void aPublicKeyReadsInEverySpelling() throws UpdateException {
        Minisign.PublicKey line = Minisign.publicKey(UpdateFixtures.FIXTURE_KEY);
        String text = "untrusted comment: minisign public key: E9035FB7AD485BF1\n" + UpdateFixtures.FIXTURE_KEY + "\n";
        Minisign.PublicKey twoLines = Minisign.publicKey(text);
        Minisign.PublicKey tauri = Minisign.publicKey(Base64.toBase64String(text.getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(line.key, twoLines.key);
        assertArrayEquals(line.key, tauri.key);
        assertEquals("E9035FB7AD485BF1", tauri.id());
        // The launcher's key: the app trusts exactly the one in tauri.conf.json.
        Minisign.PublicKey launcher = Minisign.publicKey(UpdateFixtures.TAURI_CONF_PUBKEY);
        assertEquals("260676434E1A5359", launcher.id());
        Minisign.PublicKey embedded = Minisign.publicKey(UpdateConfig.TRUSTED_KEYS.get(0));
        assertArrayEquals(launcher.key, embedded.key);
        assertArrayEquals(launcher.keyId, embedded.keyId);
        assertEquals(1, UpdateConfig.TRUSTED_KEYS.size());
        for (String bad : new String[]{"", "RWQ", "not base64!", Base64.toBase64String(new byte[42]),
                "untrusted comment: only a comment", Base64.toBase64String("untrusted comment: x\nRWQ=\n".getBytes(StandardCharsets.UTF_8))}) {
            try {
                Minisign.publicKey(bad);
                fail("read a key from " + bad);
            } catch (UpdateException e) {
                assertEquals(UpdateException.Kind.INVALID, e.kind);
            }
        }
    }

    @Test
    public void changedDataOrCommentIsRefused() throws UpdateException {
        List<Minisign.PublicKey> trusted = keys(UpdateFixtures.FIXTURE_KEY);
        byte[] changed = CHANNEL.clone();
        changed[changed.length / 2] ^= 1;
        refused(UpdateException.Kind.BAD_SIGNATURE, changed, CHANNEL_SIG, trusted);
        byte[] longer = Arrays.copyOf(CHANNEL, CHANNEL.length + 1);
        longer[CHANNEL.length] = '\n';
        refused(UpdateException.Kind.BAD_SIGNATURE, longer, CHANNEL_SIG, trusted);
        // Another version in the trusted comment: its own signature no longer matches.
        String text = new String(Base64.decode(new String(CHANNEL_SIG, StandardCharsets.US_ASCII).trim()), StandardCharsets.UTF_8);
        String edited = text.replace("version:0.4.6", "version:0.4.9");
        refused(UpdateException.Kind.BAD_SIGNATURE, CHANNEL, edited.getBytes(StandardCharsets.UTF_8), trusted);
    }

    @Test
    public void onlyTrustedKeysAndRealKeys() throws UpdateException {
        UpdateFixtures.TestKey other = new UpdateFixtures.TestKey();
        refused(UpdateException.Kind.UNTRUSTED_KEY, CHANNEL, CHANNEL_SIG, Collections.singletonList(other.publicKey()));
        refused(UpdateException.Kind.UNTRUSTED_KEY, CHANNEL, CHANNEL_SIG, Collections.<Minisign.PublicKey>emptyList());
        // A key with the same id but other bytes: the id finds it, the signature does not verify.
        UpdateFixtures.TestKey signer = new UpdateFixtures.TestKey();
        UpdateFixtures.TestKey impostor = signer.sameIdOtherKey();
        byte[] data = UpdateFixtures.utf8("{}\n");
        byte[] sig = signer.sigFile(data, "file:android-stable.json");
        refused(UpdateException.Kind.BAD_SIGNATURE, data, sig, Collections.singletonList(impostor.publicKey()));
        // Several trusted keys (a rotation): the one with the id verifies.
        assertEquals(signer.publicKey().id(), Minisign.verify(data, sig, Arrays.asList(other.publicKey(), signer.publicKey())).key.id());
    }

    @Test
    public void onlyPrehashedSignaturesWithBothPartsValid() throws UpdateException {
        UpdateFixtures.TestKey key = new UpdateFixtures.TestKey();
        List<Minisign.PublicKey> trusted = Collections.singletonList(key.publicKey());
        byte[] data = UpdateFixtures.utf8("{\"schema\": 1}\n");
        assertEquals("file:x", Minisign.verify(data, key.sigFile(data, "file:x"), trusted).trustedComment);
        // minisign's legacy algorithm ("Ed", unhashed) is valid minisign but not what Tauri writes: refused.
        byte[] legacy = UpdateFixtures.utf8(key.signatureText(data, "file:x", false));
        refused(UpdateException.Kind.BAD_SIGNATURE, data, legacy, trusted);
        // A trusted comment's signature from another key.
        String good = key.signatureText(data, "file:x", true);
        String[] lines = good.split("\n");
        UpdateFixtures.TestKey other = new UpdateFixtures.TestKey();
        String foreign = other.signatureText(data, "file:x", true).split("\n")[3];
        String mixed = lines[0] + "\n" + lines[1] + "\n" + lines[2] + "\n" + foreign + "\n";
        refused(UpdateException.Kind.BAD_SIGNATURE, data, UpdateFixtures.utf8(mixed), trusted);
        // CRLF line ends are fine.
        assertEquals("file:x", Minisign.verify(data, UpdateFixtures.utf8(good.replace("\n", "\r\n")), trusted).trustedComment);
    }

    @Test
    public void malformedSignatureFilesAreRefused() throws UpdateException {
        UpdateFixtures.TestKey key = new UpdateFixtures.TestKey();
        List<Minisign.PublicKey> trusted = Collections.singletonList(key.publicKey());
        byte[] data = UpdateFixtures.utf8("{}");
        String[] lines = key.signatureText(data, "file:x", true).split("\n");
        String[] bad = {
                "",
                "not base64 at all",
                Base64.toBase64String(UpdateFixtures.utf8("hello")),
                lines[0] + "\n" + lines[1] + "\n" + lines[2] + "\n",
                lines[0] + "\n" + lines[1].substring(4) + "\n" + lines[2] + "\n" + lines[3] + "\n",
                lines[0] + "\n" + lines[1] + "\n" + "comment: no" + "\n" + lines[3] + "\n",
                "comment: x\n" + lines[1] + "\n" + lines[2] + "\n" + lines[3] + "\n",
                lines[0] + "\n" + lines[1] + "\n" + lines[2] + "\n" + lines[3].substring(0, 40) + "\n",
                lines[0] + "\n" + lines[1].substring(0, 50) + " " + lines[1].substring(50) + "\n" + lines[2] + "\n" + lines[3] + "\n",
        };
        for (String text : bad) {
            refused(UpdateException.Kind.BAD_SIGNATURE, data, UpdateFixtures.utf8(text), trusted);
        }
        refused(UpdateException.Kind.BAD_SIGNATURE, data, null, trusted);
        refused(UpdateException.Kind.BAD_SIGNATURE, data, new byte[Minisign.MAX_SIGNATURE_FILE + 1], trusted);
    }
}
