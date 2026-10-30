package gg.projects.mobile.update;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.bouncycastle.util.encoders.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * APK-UPD: the launcher's update signatures (minisign, as {@code tauri signer sign} writes them), so the
 * Android channel is signed with the launcher's updater key and scheme (docs/content-format.md, "Android
 * release channel"). A {@code .sig} file is the base64 of the minisign text:
 * <pre>
 * untrusted comment: …
 * base64("ED" + key id (8 bytes) + Ed25519 signature of BLAKE2b-512(data) (64 bytes))
 * trusted comment: timestamp:…\tfile:…\tversion:…
 * base64(Ed25519 signature of (the signature above + the trusted comment's bytes))
 * </pre>
 * Only the prehashed algorithm ("ED", the one Tauri writes) is accepted, and both signatures must verify, so
 * the trusted comment's fields can be relied on. Plain Java (BouncyCastle), JVM-tested.
 */
public final class Minisign {
    private Minisign() {}

    private static final Pattern BASE64 = Pattern.compile("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$");
    private static final int KEY_ID_SIZE = 8;
    /** "Ed" + key id + public key. */
    private static final int PUBLIC_KEY_BOX = 2 + KEY_ID_SIZE + Ed25519.PUBLIC_KEY_SIZE;
    /** "ED" + key id + signature. */
    private static final int SIGNATURE_BOX = 2 + KEY_ID_SIZE + Ed25519.SIGNATURE_SIZE;
    private static final String UNTRUSTED = "untrusted comment:";
    private static final String TRUSTED = "trusted comment: ";
    /** A signature file is a few hundred bytes. */
    public static final int MAX_SIGNATURE_FILE = 4096;

    /** A public key this build trusts: minisign's key id and the 32-byte Ed25519 key. */
    public static final class PublicKey {
        final byte[] keyId;
        final byte[] key;

        PublicKey(byte[] keyId, byte[] key) {
            this.keyId = keyId.clone();
            this.key = key.clone();
        }

        /** The key id as minisign prints it (upper-case hex of the little-endian number). */
        public String id() {
            StringBuilder out = new StringBuilder();
            for (int i = keyId.length - 1; i >= 0; i--) out.append(String.format(Locale.ROOT, "%02X", keyId[i] & 0xFF));
            return out.toString();
        }
    }

    /** A verified signature: the key that made it and its trusted comment. */
    public static final class Verified {
        public final PublicKey key;
        public final String trustedComment;

        Verified(PublicKey key, String trustedComment) {
            this.key = key;
            this.trustedComment = trustedComment;
        }

        /** The trusted comment's {@code name:value} fields (Tauri separates them with tabs). */
        public Map<String, String> fields() {
            Map<String, String> out = new LinkedHashMap<>();
            for (String part : trustedComment.split("\t")) {
                int colon = part.indexOf(':');
                if (colon > 0 && !out.containsKey(part.substring(0, colon))) out.put(part.substring(0, colon), part.substring(colon + 1));
            }
            return out;
        }
    }

    /**
     * A public key in any of minisign's spellings: the key line ({@code RWQ…}), the two-line text with its
     * untrusted comment, or the base64 of that text (Tauri's {@code plugins.updater.pubkey}).
     */
    public static PublicKey publicKey(String text) throws UpdateException {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith(UNTRUSTED)) {
            String[] lines = lines(trimmed);
            if (lines.length < 2) throw invalidKey();
            return publicKeyLine(lines[1].trim());
        }
        byte[] raw = decodeBase64(trimmed);
        if (raw == null) throw invalidKey();
        if (raw.length == PUBLIC_KEY_BOX) return publicKeyBox(raw);
        // The base64 of the whole text (tauri.conf.json): decode once more.
        String decoded = new String(raw, StandardCharsets.UTF_8).trim();
        if (!decoded.startsWith(UNTRUSTED)) throw invalidKey();
        return publicKey(decoded);
    }

    private static PublicKey publicKeyLine(String line) throws UpdateException {
        byte[] raw = decodeBase64(line);
        if (raw == null || raw.length != PUBLIC_KEY_BOX) throw invalidKey();
        return publicKeyBox(raw);
    }

    private static PublicKey publicKeyBox(byte[] raw) throws UpdateException {
        if (raw[0] != 'E' || raw[1] != 'd') throw invalidKey();
        return new PublicKey(Arrays.copyOfRange(raw, 2, 2 + KEY_ID_SIZE), Arrays.copyOfRange(raw, 2 + KEY_ID_SIZE, PUBLIC_KEY_BOX));
    }

    private static UpdateException invalidKey() {
        return new UpdateException(UpdateException.Kind.INVALID, "not a minisign Ed25519 public key");
    }

    /** The trusted keys of {@code texts}; fails on any that is not a key. */
    public static List<PublicKey> publicKeys(List<String> texts) throws UpdateException {
        List<PublicKey> keys = new ArrayList<>();
        for (String text : texts) keys.add(publicKey(text));
        return Collections.unmodifiableList(keys);
    }

    /**
     * Verifies {@code signatureFile} (a {@code .sig} as Tauri writes it, or the minisign text itself) over the
     * exact bytes of {@code data}. UNTRUSTED_KEY when no trusted key has its key id, BAD_SIGNATURE for anything
     * malformed or a signature that does not verify.
     */
    public static Verified verify(byte[] data, byte[] signatureFile, List<PublicKey> trusted) throws UpdateException {
        if (signatureFile == null || signatureFile.length == 0 || signatureFile.length > MAX_SIGNATURE_FILE) {
            throw bad("the signature file is empty or too large");
        }
        String text = new String(signatureFile, StandardCharsets.UTF_8).trim();
        if (!text.startsWith(UNTRUSTED)) {
            byte[] decoded = decodeBase64(text);
            if (decoded == null) throw bad("the signature file is neither minisign text nor its base64");
            text = new String(decoded, StandardCharsets.UTF_8);
        }
        String[] lines = lines(text);
        if (lines.length < 4 || !lines[0].startsWith(UNTRUSTED) || !lines[2].startsWith(TRUSTED)) {
            throw bad("the signature file does not have minisign's four lines");
        }
        byte[] box = decodeBase64(lines[1].trim());
        if (box == null || box.length != SIGNATURE_BOX) throw bad("the signature line is not base64 of 74 bytes");
        if (box[0] != 'E' || box[1] != 'D') throw bad("only prehashed (ED) signatures are accepted");
        byte[] keyId = Arrays.copyOfRange(box, 2, 2 + KEY_ID_SIZE);
        byte[] signature = Arrays.copyOfRange(box, 2 + KEY_ID_SIZE, SIGNATURE_BOX);
        PublicKey key = null;
        for (PublicKey candidate : trusted) {
            if (Arrays.equals(candidate.keyId, keyId)) {
                key = candidate;
                break;
            }
        }
        if (key == null) throw new UpdateException(UpdateException.Kind.UNTRUSTED_KEY, "signed by an untrusted key");
        String comment = lines[2].substring(TRUSTED.length());
        byte[] global = decodeBase64(lines[3].trim());
        if (global == null || global.length != Ed25519.SIGNATURE_SIZE) throw bad("the trusted comment's signature is not base64 of 64 bytes");
        if (!ed25519(signature, key.key, blake2b512(data))) throw bad("the signature does not verify");
        byte[] commentBytes = comment.getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[signature.length + commentBytes.length];
        System.arraycopy(signature, 0, signed, 0, signature.length);
        System.arraycopy(commentBytes, 0, signed, signature.length, commentBytes.length);
        if (!ed25519(global, key.key, signed)) throw bad("the trusted comment's signature does not verify");
        return new Verified(key, comment);
    }

    private static UpdateException bad(String message) {
        return new UpdateException(UpdateException.Kind.BAD_SIGNATURE, message);
    }

    private static boolean ed25519(byte[] signature, byte[] key, byte[] message) {
        try {
            return Ed25519.verify(signature, 0, key, 0, message, 0, message.length);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static byte[] blake2b512(byte[] data) {
        Blake2bDigest digest = new Blake2bDigest(512);
        digest.update(data, 0, data.length);
        byte[] out = new byte[64];
        digest.doFinal(out, 0);
        return out;
    }

    private static String[] lines(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].endsWith("\r")) lines[i] = lines[i].substring(0, lines[i].length() - 1);
        }
        return lines;
    }

    /** Strict standard base64 (padded, no whitespace inside); null when invalid. */
    static byte[] decodeBase64(String text) {
        if (text == null || text.isEmpty() || !BASE64.matcher(text).matches()) return null;
        try {
            return Base64.decode(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
