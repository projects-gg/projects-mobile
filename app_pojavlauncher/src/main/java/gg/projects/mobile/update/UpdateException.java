package gg.projects.mobile.update;

/**
 * APK-UPD: why an update check, download or install stopped. {@link Kind} picks the one sentence the
 * player reads ({@link UpdateWords}); the message is for the log only.
 */
public final class UpdateException extends Exception {
    public enum Kind {
        /** No connection, a timeout, or the server had trouble: try again later. */
        NETWORK,
        /** The channel file breaks a rule of the format. */
        INVALID,
        /** The channel file's schema is newer than this build reads. */
        UNSUPPORTED,
        /** The signature is missing, malformed or does not verify. */
        BAD_SIGNATURE,
        /** Signed by a key this build does not trust. */
        UNTRUSTED_KEY,
        /** The APK is gone from every address (an older release after a newer one). */
        GONE,
        /** The downloaded bytes are not the announced ones. */
        CORRUPT,
        /** The APK is not this app's, or not signed like the installed app. */
        FOREIGN,
        /** The phone has no room for the download or the install. */
        SPACE,
        /** The game runs: an install would close it. */
        GAME_RUNNING,
        /** Another download runs: an install would cut it off. */
        BUSY,
        /** The player stopped the download (its part stays for the next try). */
        CANCELLED,
        /** Android did not install the update. */
        INSTALL_FAILED,
        /** A policy on the phone blocked the install. */
        BLOCKED,
        /** Android refused the update as incompatible with the phone. */
        INCOMPATIBLE
    }

    public final Kind kind;

    public UpdateException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public UpdateException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    @Override
    public String toString() {
        return kind + ": " + getMessage();
    }
}
