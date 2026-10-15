package gg.projects.mobile.content;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Digests and lowercase hex, as the content format spells them. */
public final class Hashes {
    private Hashes() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    public static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1, SHA-256 and SHA-512 are mandatory on every Java and Android runtime.
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return hex(digest("SHA-256").digest(data));
    }

    public static String fileHex(File file, String algorithm) throws IOException {
        MessageDigest md = digest(algorithm);
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) md.update(buffer, 0, read);
        }
        return hex(md.digest());
    }

    /**
     * Trimmed lowercase form of a hex digest with exactly {@code length} hex digits, or null
     * when the text is not one.
     */
    public static String normalizeHex(String value, int length) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() != length) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            if (!isAsciiHex(trimmed.charAt(i))) return null;
        }
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isAsciiHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
