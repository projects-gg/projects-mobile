package gg.projects.mobile.update;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.bouncycastle.util.encoders.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * APK-UPD test helpers: the channel file the real {@code tauri signer sign} signed with a throwaway key
 * ({@code src/test/resources/update}, its private half was never kept), and minisign keys made for each run
 * (BouncyCastle) to sign variants the same way Tauri does.
 */
final class UpdateFixtures {
    private UpdateFixtures() {}

    /** The throwaway key that signed {@code android-stable.json} (resources/update/throwaway.pub). */
    static final String FIXTURE_KEY = "RWTxW0itt18D6d4Gh7zdmwUlGzxrxqHeF60E5E9A8dzjaWdI5ElFRnuB";
    /** The launcher's {@code tauri.conf.json} {@code plugins.updater.pubkey}, as it is there. */
    static final String TAURI_CONF_PUBKEY = "dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IDI2MDY3NjQzNEUxQTUzNTkKUldSWlV4cE9RM1lHSnJBUHhhaDJhS2o2NmdlS2lydU9YcHppTmNZZnA4aS9GcjFDemIrWEphMk4K";

    static byte[] resource(String name) {
        try (InputStream in = UpdateFixtures.class.getResourceAsStream("/update/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A minisign key made for one test run. */
    static final class TestKey {
        final byte[] secret = new byte[Ed25519.SECRET_KEY_SIZE];
        final byte[] key = new byte[Ed25519.PUBLIC_KEY_SIZE];
        final byte[] keyId = new byte[8];

        TestKey() {
            SecureRandom random = new SecureRandom();
            Ed25519.generatePrivateKey(random, secret);
            Ed25519.generatePublicKey(secret, 0, key, 0);
            random.nextBytes(keyId);
        }

        /** The same key id with other key bytes (a key id is no proof). */
        TestKey sameIdOtherKey() {
            TestKey other = new TestKey();
            System.arraycopy(keyId, 0, other.keyId, 0, keyId.length);
            return other;
        }

        /** minisign's key line ("RWQ..."). */
        String publicLine() {
            byte[] box = new byte[2 + 8 + 32];
            box[0] = 'E';
            box[1] = 'd';
            System.arraycopy(keyId, 0, box, 2, 8);
            System.arraycopy(key, 0, box, 10, 32);
            return Base64.toBase64String(box);
        }

        Minisign.PublicKey publicKey() throws UpdateException {
            return Minisign.publicKey(publicLine());
        }

        byte[] sign(byte[] message) {
            byte[] signature = new byte[Ed25519.SIGNATURE_SIZE];
            Ed25519.sign(secret, 0, message, 0, message.length, signature, 0);
            return signature;
        }

        /** The minisign text for {@code data}: prehashed ("ED") as Tauri writes it, or minisign's legacy "Ed". */
        String signatureText(byte[] data, String trustedComment, boolean prehashed) {
            byte[] signature = sign(prehashed ? Minisign.blake2b512(data) : data);
            byte[] box = new byte[2 + 8 + 64];
            box[0] = 'E';
            box[1] = (byte) (prehashed ? 'D' : 'd');
            System.arraycopy(keyId, 0, box, 2, 8);
            System.arraycopy(signature, 0, box, 10, 64);
            byte[] comment = utf8(trustedComment);
            byte[] signed = new byte[64 + comment.length];
            System.arraycopy(signature, 0, signed, 0, 64);
            System.arraycopy(comment, 0, signed, 64, comment.length);
            return "untrusted comment: signature from tauri secret key\n" + Base64.toBase64String(box) + "\ntrusted comment: "
                    + trustedComment + "\n" + Base64.toBase64String(sign(signed)) + "\n";
        }

        /** A {@code .sig} file as Tauri writes it: the base64 of the minisign text. */
        byte[] sigFile(byte[] data, String trustedComment) {
            return utf8(Base64.toBase64String(utf8(signatureText(data, trustedComment, true))));
        }

        /** The {@code .sig} of a channel file, with the comment {@code tauri signer sign --app-version} writes. */
        byte[] channelSig(byte[] json, String channel, String version) {
            return sigFile(json, "timestamp:1793362450\tfile:android-" + channel + ".json\tversion:" + version);
        }
    }
}
