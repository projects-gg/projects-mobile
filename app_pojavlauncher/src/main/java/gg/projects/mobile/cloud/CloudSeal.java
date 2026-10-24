package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.hpke.HPKEContext;
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * Secret rows and password console lines of 7/24 Bulut, sealed on the phone to the machines' vault key
 * (services/cloud-afk README "Seal format pl1"), and "Göster"'s answers opened with a one-time key ("Reveal
 * format rv1"). HPKE base mode, DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, single shot, from
 * BouncyCastle (already in the app for the content signatures). The Worker, the database and staff never see a
 * sealed text; the AAD ties a seal to its owner, account, row and server. Checked byte for byte against the
 * contract's {@code seal-v1.json} and {@code reveal-v1.json} (the launcher's and the site's vectors).
 */
public final class CloudSeal {
    private CloudSeal() {}

    public static final String ALG = "HPKE-P256-SHA256-AES128GCM";
    /** The row id of a line typed into the console. */
    public static final String CONSOLE_ROW = "console";

    static final String SEAL_PREFIX = "pl1";
    static final String REVEAL_PREFIX = "rv1";
    static final byte[] SEAL_INFO = "projects-cloud-afk seal v1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] REVEAL_INFO = "projects-cloud-afk reveal v1".getBytes(StandardCharsets.US_ASCII);
    static final int PAD_BLOCK = 64;
    static final int MAX_PADDED = 768;
    static final int POINT_BYTES = 65;
    static final int TAG_BYTES = 16;

    /** Why a value could not be sealed or opened (the vectors' error classes for opening). */
    public enum Error { KEY, LENGTH, FORMAT, UNKNOWN_KID, OPEN_FAILED, PLAINTEXT }

    public static final class SealException extends Exception {
        public final Error error;

        SealException(Error error) {
            super(error.name());
            this.error = error;
        }
    }

    /** The vault key as {@code GET /vault-key} gives it. */
    public static final class VaultKey {
        public final String kid;
        public final String alg;
        public final String publicKey;

        public VaultKey(String kid, String alg, String publicKey) {
            this.kid = kid;
            this.alg = alg;
            this.publicKey = publicKey;
        }
    }

    /** Who and where a seal is for: {@code <discordId>|<accountId>|<rowId>|<host>:<port>}. */
    public static String sealAad(String discordId, String accountId, String rowId, CloudServer server) {
        return discordId + "|" + accountId + "|" + rowId + "|" + server.aadPart();
    }

    static HPKE suite() {
        return new HPKE(HPKE.mode_base, HPKE.kem_P256_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM128);
    }

    static boolean isKid(@Nullable String kid) {
        if (kid == null || kid.isEmpty() || kid.length() > 12) return false;
        for (int i = 0; i < kid.length(); i++) {
            char c = kid.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** The text's UTF-8 bytes, zero-padded to the next multiple of 64 (at least 64, at most 768). */
    static byte[] pad(String text) throws SealException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int size = Math.max(1, (bytes.length + PAD_BLOCK - 1) / PAD_BLOCK) * PAD_BLOCK;
        if (bytes.length == 0 || size > MAX_PADDED) throw new SealException(Error.LENGTH);
        for (byte b : bytes) if (b == 0) throw new SealException(Error.LENGTH);
        return Arrays.copyOf(bytes, size);
    }

    /** Seals {@code text} (it must already pass {@link CloudText#check}) to the vault key for {@code aad}, with a fresh ephemeral key. */
    public static String seal(VaultKey key, String text, String aad) throws SealException {
        return sealWith(key, text, aad, null);
    }

    /** The same, with the ephemeral key pair given (the vectors replay {@code DeriveKeyPair(ikmE)}). */
    static String sealWith(VaultKey key, String text, String aad, @Nullable AsymmetricCipherKeyPair ephemeral) throws SealException {
        if (!ALG.equals(key.alg) || !isKid(key.kid)) throw new SealException(Error.KEY);
        byte[] raw = Base64Url.decode(key.publicKey);
        if (raw == null || raw.length != POINT_BYTES || raw[0] != 0x04) throw new SealException(Error.KEY);
        HPKE hpke = suite();
        AsymmetricKeyParameter recipient;
        try {
            recipient = hpke.deserializePublicKey(raw);
        } catch (RuntimeException e) {
            throw new SealException(Error.KEY);
        }
        byte[] padded = pad(text);
        try {
            HPKEContextWithEncapsulation context = ephemeral == null
                    ? hpke.setupBaseS(recipient, SEAL_INFO)
                    : hpke.setupBaseS(recipient, SEAL_INFO, ephemeral);
            byte[] ct = context.seal(aad.getBytes(StandardCharsets.UTF_8), padded);
            byte[] enc = context.getEncapsulation();
            byte[] body = new byte[enc.length + ct.length];
            System.arraycopy(enc, 0, body, 0, enc.length);
            System.arraycopy(ct, 0, body, enc.length, ct.length);
            return SEAL_PREFIX + "." + key.kid + "." + Base64Url.encode(body);
        } catch (InvalidCipherTextException | RuntimeException e) {
            throw new SealException(Error.KEY);
        } finally {
            Arrays.fill(padded, (byte) 0);
        }
    }

    /** The stored seal's fingerprint as the account view gives it ({@code fp}): 16 characters of base64url(SHA-256(value)). */
    public static String fingerprint(String sealed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sealed.getBytes(StandardCharsets.US_ASCII));
            return Base64Url.encode(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code enc || ct} of a canonical base64url payload of {@code 65 + 16 + 64·k} bytes, {@code 1 <= k <= 12}. */
    static byte[][] splitPayload(String payload) throws SealException {
        byte[] body = Base64Url.decode(payload);
        if (body == null || !Base64Url.encode(body).equals(payload)) throw new SealException(Error.FORMAT);
        int blocks = body.length - POINT_BYTES - TAG_BYTES;
        if (blocks <= 0 || blocks % PAD_BLOCK != 0 || blocks > MAX_PADDED) throw new SealException(Error.FORMAT);
        return new byte[][]{Arrays.copyOfRange(body, 0, POINT_BYTES), Arrays.copyOfRange(body, POINT_BYTES, body.length)};
    }

    /** Opens {@code enc || ct} with {@code recipient} and strips the padding. */
    static byte[] openBody(AsymmetricCipherKeyPair recipient, byte[] enc, byte[] ct, byte[] info, byte[] aad) throws SealException {
        byte[] padded;
        try {
            HPKEContext context = suite().setupBaseR(enc, recipient, info);
            padded = context.open(aad, ct);
        } catch (InvalidCipherTextException | RuntimeException e) {
            throw new SealException(Error.OPEN_FAILED);
        }
        int end = padded.length;
        while (end > 0 && padded[end - 1] == 0) end--;
        byte[] out = Arrays.copyOf(padded, end);
        Arrays.fill(padded, (byte) 0);
        return out;
    }

    /** Strict UTF-8 (a malformed sequence is not a text). */
    @Nullable
    static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    /** "Göster": a key pair made for one request; its public half goes with it, the private half opens the answer once. */
    public static final class RevealKey {
        private final AsymmetricCipherKeyPair mPair;
        private final String mPublic;

        private RevealKey(AsymmetricCipherKeyPair pair) {
            mPair = pair;
            mPublic = Base64Url.encode(suite().serializePublicKey(pair.getPublic()));
        }

        public static RevealKey generate() {
            return new RevealKey(suite().generatePrivateKey());
        }

        /** DeriveKeyPair(ikm): the vectors' client keys. */
        static RevealKey fromIkm(byte[] ikm) {
            return new RevealKey(suite().deriveKeyPair(ikm));
        }

        /** The uncompressed point in base64url (87 characters). */
        public String publicKey() {
            return mPublic;
        }

        /** Opens the machine's answer for {@code <discordId>|<accountId>|<rowId>|<nonce>}. */
        public String open(String revealed, String aad) throws SealException {
            if (revealed == null || !revealed.startsWith(REVEAL_PREFIX + ".")) throw new SealException(Error.FORMAT);
            byte[][] parts = splitPayload(revealed.substring(REVEAL_PREFIX.length() + 1));
            byte[] bytes = openBody(mPair, parts[0], parts[1], REVEAL_INFO, aad.getBytes(StandardCharsets.UTF_8));
            try {
                if (bytes.length == 0) throw new SealException(Error.PLAINTEXT);
                for (byte b : bytes) if (b == 0) throw new SealException(Error.PLAINTEXT);
                String text = utf8(bytes);
                if (text == null) throw new SealException(Error.PLAINTEXT);
                return text;
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    /** The AAD of a reveal's answer. */
    public static String revealAad(String discordId, String accountId, String rowId, String nonce) {
        return discordId + "|" + accountId + "|" + rowId + "|" + nonce;
    }

    /**
     * Opens a {@code pl1} value as the machine does (opening steps 1-5): the vectors' openers' half, so the sealer is
     * checked against every value an opener must refuse. Tests only: the phone holds no vault key.
     */
    static String openSealed(List<Object[]> keys, String sealed, String aad) throws SealException {
        String[] parts = sealed.split("\\.", 3);
        if (parts.length != 3 || !SEAL_PREFIX.equals(parts[0]) || !isKid(parts[1]) || parts[2].contains(".")) {
            throw new SealException(Error.FORMAT);
        }
        byte[][] body = splitPayload(parts[2]);
        AsymmetricCipherKeyPair secret = null;
        for (Object[] key : keys) if (parts[1].equals(key[0])) secret = (AsymmetricCipherKeyPair) key[1];
        if (secret == null) throw new SealException(Error.UNKNOWN_KID);
        byte[] bytes = openBody(secret, body[0], body[1], SEAL_INFO, aad.getBytes(StandardCharsets.UTF_8));
        String text = utf8(bytes);
        if (text == null || text.isEmpty() || CloudText.check(text) != null) throw new SealException(Error.PLAINTEXT);
        return text;
    }

    /** Base64url without padding (RFC 4648 §5), strict: anything else is null. Android 5 has no java.util.Base64. */
    static final class Base64Url {
        private Base64Url() {}

        private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
        private static final int[] INDEX = new int[128];

        static {
            Arrays.fill(INDEX, -1);
            for (int i = 0; i < ALPHABET.length; i++) INDEX[ALPHABET[i]] = i;
        }

        static String encode(byte[] data) {
            StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
            int i = 0;
            for (; i + 3 <= data.length; i += 3) {
                int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
                out.append(ALPHABET[(n >> 18) & 63]).append(ALPHABET[(n >> 12) & 63]).append(ALPHABET[(n >> 6) & 63]).append(ALPHABET[n & 63]);
            }
            int rest = data.length - i;
            if (rest == 1) {
                int n = (data[i] & 0xFF) << 16;
                out.append(ALPHABET[(n >> 18) & 63]).append(ALPHABET[(n >> 12) & 63]);
            } else if (rest == 2) {
                int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
                out.append(ALPHABET[(n >> 18) & 63]).append(ALPHABET[(n >> 12) & 63]).append(ALPHABET[(n >> 6) & 63]);
            }
            return out.toString();
        }

        /** The bytes, or null for a character outside the alphabet or a length no encoder makes. */
        @Nullable
        static byte[] decode(@Nullable String text) {
            if (text == null || text.length() % 4 == 1) return null;
            byte[] out = new byte[text.length() * 3 / 4];
            int bits = 0;
            int buffer = 0;
            int at = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int v = c < 128 ? INDEX[c] : -1;
                if (v < 0) return null;
                buffer = (buffer << 6) | v;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out[at++] = (byte) ((buffer >> bits) & 0xFF);
                }
            }
            return at == out.length ? out : Arrays.copyOf(out, at);
        }
    }
}
