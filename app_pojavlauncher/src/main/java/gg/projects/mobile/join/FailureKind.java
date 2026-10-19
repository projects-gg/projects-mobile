package gg.projects.mobile.join;

/**
 * M17: whether a failed start is the connection's (its text says "check your internet and try
 * again") or something else. A full disk never is: the pack takes about 2 GB in a world, so a phone
 * can fill up, and telling that player to check their internet sends them the wrong way. A
 * connection problem is known by its type anywhere in the cause chain; anything else is the
 * caller's {@code fallback} (the pack's download keeps its old answer, the game files' check says
 * the generic sentence). Plain Java, JVM-tested.
 */
public final class FailureKind {
    private FailureKind() {}

    public static boolean isNetwork(Throwable failure, boolean fallback) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String text = t.getMessage();
            if (text != null && (text.contains("ENOSPC") || text.contains("No space left"))) return false;
        }
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.SocketException
                    || t instanceof java.net.SocketTimeoutException || t instanceof javax.net.ssl.SSLException
                    || t instanceof java.net.ProtocolException) {
                return true;
            }
        }
        return fallback;
    }
}
