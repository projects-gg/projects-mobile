package gg.projects.mobile.update;

/**
 * APK-UPD: what Android's answer to the install session means for the card (PackageInstaller's
 * {@code EXTRA_STATUS}; the values are Android's, kept here so the rule is JVM-tested).
 */
public final class InstallStatus {
    private InstallStatus() {}

    static final int PENDING_USER_ACTION = -1;
    static final int SUCCESS = 0;
    static final int FAILURE = 1;
    static final int FAILURE_BLOCKED = 2;
    static final int FAILURE_ABORTED = 3;
    static final int FAILURE_INVALID = 4;
    static final int FAILURE_CONFLICT = 5;
    static final int FAILURE_STORAGE = 6;
    static final int FAILURE_INCOMPATIBLE = 7;
    static final int FAILURE_TIMEOUT = 8;

    public enum Outcome {
        /** Android's own confirmation must open. */
        CONFIRM,
        /** Installed (an app that updates itself rarely hears this: Android restarts it first). */
        DONE,
        /** The player said no (or left Android's question): the offer comes back, nothing to read. */
        DECLINED,
        /** Not installed: {@link #failure} says why. */
        FAILED
    }

    public static Outcome outcome(int status) {
        switch (status) {
            case PENDING_USER_ACTION:
                return Outcome.CONFIRM;
            case SUCCESS:
                return Outcome.DONE;
            case FAILURE_ABORTED:
                return Outcome.DECLINED;
            default:
                return Outcome.FAILED;
        }
    }

    /** The sentence for a failed install. */
    public static UpdateException.Kind failure(int status) {
        switch (status) {
            case FAILURE_BLOCKED:
                return UpdateException.Kind.BLOCKED;
            case FAILURE_INVALID:
                return UpdateException.Kind.CORRUPT;
            case FAILURE_CONFLICT:
                // Another signer or an older version: what the checks before the install rule out.
                return UpdateException.Kind.FOREIGN;
            case FAILURE_STORAGE:
                return UpdateException.Kind.SPACE;
            case FAILURE_INCOMPATIBLE:
                return UpdateException.Kind.INCOMPATIBLE;
            default:
                return UpdateException.Kind.INSTALL_FAILED;
        }
    }
}
