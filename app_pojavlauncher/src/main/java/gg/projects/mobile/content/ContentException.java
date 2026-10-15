package gg.projects.mobile.content;

/**
 * Why Projects content could not be used. Messages are technical: they go to logs and the
 * "copy details" action, never to visible UI text.
 */
public class ContentException extends Exception {
    public enum Kind {
        /** The mirror could not be reached or answered with an error. */
        NETWORK,
        /** {@code index.json.sig} names a key this build does not trust. */
        UNTRUSTED_KEY,
        /** The Ed25519 signature does not verify. */
        BAD_SIGNATURE,
        /** A document uses a newer schema than this build understands. */
        UNSUPPORTED_SCHEMA,
        /** A document is malformed or breaks the format rules. */
        INVALID,
        /** A downloaded file does not match the size or hash its reference pins. */
        HASH_MISMATCH,
        /** The mirror serves an older index than the one already accepted. */
        ROLLBACK
    }

    public final Kind kind;

    public ContentException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ContentException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    @Override
    public String toString() {
        return kind + ": " + getMessage();
    }
}
