package gg.projects.mobile.content;

import androidx.annotation.Keep;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.bouncycastle.util.encoders.Base64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Verifies {@code index.json} against {@code index.json.sig} (mirror of pl-content's
 * signing.rs): Ed25519 over the exact bytes, {@code keyId} = lowercase hex of the first 8 bytes
 * of SHA-256(public key), keys and signatures as standard padded base64.
 */
public final class IndexVerifier {
    private static final Pattern BASE64 = Pattern.compile("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$");

    /** Contents of {@code index.json.sig}. */
    @Keep
    public static final class SignatureFile {
        public String keyId;
        public String signature;
    }

    public static final class TrustedKey {
        public final byte[] publicKey;
        public final String keyId;

        TrustedKey(byte[] publicKey) {
            this.publicKey = publicKey.clone();
            this.keyId = keyId(publicKey);
        }
    }

    private final List<TrustedKey> trusted;

    public IndexVerifier(List<String> base64Keys) throws ContentException {
        List<TrustedKey> keys = new ArrayList<>();
        for (String key : base64Keys) keys.add(new TrustedKey(decodePublicKey(key)));
        this.trusted = Collections.unmodifiableList(keys);
    }

    /** The verifier for this build's embedded keys. */
    public static IndexVerifier production() {
        try {
            return new IndexVerifier(ContentConfig.TRUSTED_KEYS);
        } catch (ContentException e) {
            throw new IllegalStateException("embedded content key is invalid", e);
        }
    }

    public List<TrustedKey> trustedKeys() {
        return trusted;
    }

    /** Lowercase hex of the first 8 bytes of SHA-256(public key). */
    public static String keyId(byte[] publicKey) {
        return Hashes.sha256Hex(publicKey).substring(0, 16);
    }

    public static byte[] decodePublicKey(String text) throws ContentException {
        byte[] raw = decodeBase64(text);
        if (raw == null || raw.length != Ed25519.PUBLIC_KEY_SIZE) {
            throw new ContentException(ContentException.Kind.INVALID, "the public key must be base64 of 32 bytes");
        }
        return raw;
    }

    public static SignatureFile parseSignatureFile(byte[] bytes) throws ContentException {
        SignatureFile file = ContentJson.parse(bytes, SignatureFile.class, "index.json.sig");
        if (file.keyId == null || file.signature == null) {
            throw new ContentException(ContentException.Kind.INVALID, "index.json.sig lacks keyId or signature");
        }
        return file;
    }

    /** Verifies the raw signature file; see {@link #verify(byte[], SignatureFile)}. */
    public TrustedKey verify(byte[] indexBytes, byte[] signatureFileBytes) throws ContentException {
        SignatureFile file;
        try {
            file = parseSignatureFile(signatureFileBytes);
        } catch (ContentException e) {
            throw new ContentException(ContentException.Kind.BAD_SIGNATURE, "unreadable signature file: " + e.getMessage(), e);
        }
        return verify(indexBytes, file);
    }

    /**
     * Verifies the exact bytes of {@code index.json}. Fails with UNTRUSTED_KEY when no trusted
     * key has the signature's {@code keyId} and with BAD_SIGNATURE when it does not verify.
     * Returns the key that verified the index.
     */
    public TrustedKey verify(byte[] indexBytes, SignatureFile signature) throws ContentException {
        String wanted = signature.keyId == null ? "" : signature.keyId.trim().toLowerCase(Locale.ROOT);
        TrustedKey key = null;
        for (TrustedKey candidate : trusted) {
            if (candidate.keyId.equals(wanted)) {
                key = candidate;
                break;
            }
        }
        if (key == null) {
            throw new ContentException(ContentException.Kind.UNTRUSTED_KEY, "index signed by untrusted key " + signature.keyId);
        }
        byte[] raw = decodeBase64(signature.signature);
        if (raw == null || raw.length != Ed25519.SIGNATURE_SIZE) {
            throw new ContentException(ContentException.Kind.BAD_SIGNATURE, "signature is not base64 of 64 bytes");
        }
        boolean valid;
        try {
            valid = Ed25519.verify(raw, 0, key.publicKey, 0, indexBytes, 0, indexBytes.length);
        } catch (RuntimeException e) {
            valid = false;
        }
        if (!valid) throw new ContentException(ContentException.Kind.BAD_SIGNATURE, "index signature does not verify");
        return key;
    }

    /** Strict standard base64 (padded, no whitespace inside); null when invalid. */
    static byte[] decodeBase64(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty() || !BASE64.matcher(trimmed).matches()) return null;
        try {
            return Base64.decode(trimmed);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
