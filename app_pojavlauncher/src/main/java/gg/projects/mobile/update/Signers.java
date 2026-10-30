package gg.projects.mobile.update;

import java.util.Collection;
import java.util.Set;

import gg.projects.mobile.content.Hashes;

/**
 * APK-UPD: an update is installed only when its signing certificates are the installed app's and the ones
 * the signed channel names (Android refuses another signer anyway; this says why before it does). Plain
 * Java, JVM-tested.
 */
public final class Signers {
    private Signers() {}

    /** Lowercase hex SHA-256 of a certificate's DER bytes (as {@code apksigner} prints it, without colons). */
    public static String sha256(byte[] certificate) {
        return Hashes.sha256Hex(certificate);
    }

    /** The downloaded APK may replace the installed app: the same, non-empty set of signing certificates. */
    public static boolean sameAsInstalled(Set<String> apk, Set<String> installed) {
        return !apk.isEmpty() && apk.equals(installed);
    }

    /** Every certificate that signs the APK is one the channel file names. */
    public static boolean announced(Set<String> apk, Collection<String> channel) {
        return !apk.isEmpty() && channel.containsAll(apk);
    }
}
