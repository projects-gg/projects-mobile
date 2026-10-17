package gg.projects.mobile.controls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The labels of the on-screen game controls.
 *
 * <p>Upstream upper-cases them with the phone's language, and Turkish turns the English labels
 * players bring into "HİDE GUI", "SHİFT" and "MİDDLE CLİCK". They are upper-cased with
 * {@link Locale#ROOT} instead. The Turkish labels of our default layout are written in capitals
 * ("EĞİL", "GİZLE"), so root upper-casing keeps them right too (it would turn a lower-case i into
 * I, not İ).
 */
public final class ControlLabels {
    private ControlLabels() {}

    /** A control label in capitals: "Hide\nGUI" reads "HIDE\nGUI" on a Turkish phone. */
    public static String upper(String label) {
        return label == null ? null : label.toUpperCase(Locale.ROOT);
    }

    /**
     * SHA-256 of upstream's English default layout ({@code assets/default.json}) as 0.1.0 to 0.1.3
     * installed it, line endings normalized to LF (the APKs built on Windows carry CRLF, upstream's
     * CI builds LF).
     */
    private static final Set<String> OLD_DEFAULTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "d92a182bde65a19c1c9edb1291f8dde2ff9efcbb95b9a7745c3e591b85f7516a")));

    /**
     * True when {@code installed} is an earlier version's default layout, byte for byte apart from
     * line endings: the player never edited it, so the new default (Turkish labels) may replace it.
     * A layout the player saved from the editor has other bytes and stays.
     */
    public static boolean isOldDefault(byte[] installed) {
        return installed != null && OLD_DEFAULTS.contains(sha256(normalizeLineEndings(installed)));
    }

    static byte[] normalizeLineEndings(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        int n = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') continue;
            out[n++] = bytes[i];
        }
        return Arrays.copyOf(out, n);
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #sha256} of UTF-8 text, for tests. */
    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
