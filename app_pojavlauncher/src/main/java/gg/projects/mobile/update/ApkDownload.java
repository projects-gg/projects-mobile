package gg.projects.mobile.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gg.projects.mobile.content.Hashes;
import gg.projects.mobile.join.FailureKind;

/**
 * APK-UPD: downloads the announced APK into the app's own folder, as {@code <name>.part} until every byte
 * is there and its SHA-256 and size match the signed channel file, then renames it. A cut-off download
 * goes on from its part file ({@code Range}) the next time; a part that cannot be continued starts over.
 * Addresses are tried in order (one that is gone moves to the next). Plain Java, JVM-tested.
 */
public final class ApkDownload {
    private ApkDownload() {}

    /** Room kept free next to the download (Android's own staging copy needs about as much again). */
    static final long SPARE_BYTES = 32L * 1024 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes (\\d+)-(\\d+)/(\\d+)$");

    public interface Listener {
        /** Called on the downloading thread, about every 1% and at the end. */
        void onProgress(long done, long total);

        /** Polled between chunks: true stops the download (its part file stays). */
        boolean cancelled();
    }

    /** The finished file of {@code name} in {@code dir} when it is there and matches, else null. */
    public static File existing(File dir, String name, long size, String sha256) {
        File done = new File(dir, name);
        if (!done.isFile() || done.length() != size) return null;
        try {
            return sha256.equals(Hashes.fileHex(done, "SHA-256")) ? done : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Downloads {@code size} bytes with SHA-256 {@code sha256} from the first of {@code urls} that has them into
     * {@code dir/name} and returns that file.
     */
    public static File fetch(List<String> urls, long size, String sha256, File dir, String name, String userAgent,
                             Listener listener) throws UpdateException {
        File ready = existing(dir, name, size, sha256);
        if (ready != null) {
            listener.onProgress(size, size);
            return ready;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new UpdateException(UpdateException.Kind.SPACE, "cannot create " + dir);
        File part = new File(dir, name + ".part");
        if (part.length() > size) delete(part);
        if (dir.getUsableSpace() < size - part.length() + SPARE_BYTES) {
            throw new UpdateException(UpdateException.Kind.SPACE, "not enough room for " + size + " bytes");
        }
        UpdateException last = null;
        for (String url : urls) {
            try {
                fetchOne(url, size, sha256, part, userAgent, listener);
                File done = new File(dir, name);
                delete(done);
                if (!part.renameTo(done)) throw new UpdateException(UpdateException.Kind.SPACE, "cannot rename " + part);
                return done;
            } catch (UpdateException e) {
                // Only an address that does not have the file moves on to the next one.
                if (e.kind != UpdateException.Kind.GONE) throw e;
                last = e;
            }
        }
        throw last != null ? last : new UpdateException(UpdateException.Kind.GONE, "no address");
    }

    private static void fetchOne(String url, long size, String sha256, File part, String userAgent, Listener listener)
            throws UpdateException {
        // Two rounds at most: a part the server cannot continue starts over once.
        for (int round = 0; round < 2; round++) {
            long have = part.isFile() ? part.length() : 0;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
                int status = connection.getResponseCode();
                boolean append;
                if (status == HttpURLConnection.HTTP_PARTIAL && have > 0) {
                    if (!continues(connection.getHeaderField("Content-Range"), have, size)) {
                        delete(part);
                        continue;
                    }
                    append = true;
                } else if (status == HttpURLConnection.HTTP_OK) {
                    append = false;
                } else if (status == 416 && have > 0) {
                    // "Range not satisfiable": the part is complete or wrong; check it as it is, else start over.
                    if (have == size && sha256.equals(hashOf(part))) return;
                    delete(part);
                    continue;
                } else if (status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_GONE) {
                    throw new UpdateException(UpdateException.Kind.GONE, "HTTP " + status + " for " + url);
                } else {
                    throw new UpdateException(UpdateException.Kind.NETWORK, "HTTP " + status + " for " + url);
                }
                // getContentLengthLong() is Android 7+; the header is read by hand for Android 5 and 6.
                long declared = contentLength(connection.getHeaderField("Content-Length"));
                long expected = size - (append ? have : 0);
                if (declared >= 0 && declared != expected) {
                    if (!append) throw new UpdateException(UpdateException.Kind.CORRUPT, "the server has " + declared + " bytes, not " + size);
                    delete(part);
                    continue;
                }
                MessageDigest digest = Hashes.digest("SHA-256");
                long done = 0;
                if (append) done = hashInto(part, digest);
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                    byte[] buffer = new byte[64 * 1024];
                    long step = Math.max(256 * 1024, size / 100);
                    long nextReport = done;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (listener.cancelled()) throw new UpdateException(UpdateException.Kind.CANCELLED, "stopped");
                        if (done + read > size) {
                            delete(part);
                            throw new UpdateException(UpdateException.Kind.CORRUPT, "more bytes than announced");
                        }
                        out.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        done += read;
                        if (done >= nextReport) {
                            listener.onProgress(done, size);
                            nextReport = done + step;
                        }
                    }
                }
                if (done != size) throw new UpdateException(UpdateException.Kind.NETWORK, "the download stopped at " + done + " of " + size);
                listener.onProgress(size, size);
                if (!sha256.equals(Hashes.hex(digest.digest()))) {
                    delete(part);
                    throw new UpdateException(UpdateException.Kind.CORRUPT, "SHA-256 does not match");
                }
                return;
            } catch (IOException e) {
                if (FailureKind.isNetwork(e, false)) throw new UpdateException(UpdateException.Kind.NETWORK, String.valueOf(e), e);
                if (isFull(e)) throw new UpdateException(UpdateException.Kind.SPACE, String.valueOf(e), e);
                throw new UpdateException(UpdateException.Kind.NETWORK, String.valueOf(e), e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new UpdateException(UpdateException.Kind.NETWORK, "the server could not continue the download");
    }

    /** Whether a 206's {@code Content-Range} continues exactly where the part file ends. */
    static boolean continues(String contentRange, long have, long size) {
        if (contentRange == null) return false;
        Matcher m = CONTENT_RANGE.matcher(contentRange.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return false;
        try {
            return Long.parseLong(m.group(1)) == have && Long.parseLong(m.group(2)) == size - 1 && Long.parseLong(m.group(3)) == size;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The header's byte count, or -1 when it is missing or not a number. */
    static long contentLength(String header) {
        if (header == null) return -1;
        try {
            long value = Long.parseLong(header.trim());
            return value >= 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long hashInto(File file, MessageDigest digest) throws IOException {
        long total = 0;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private static String hashOf(File file) {
        try {
            return Hashes.fileHex(file, "SHA-256");
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isFull(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String text = t.getMessage();
            if (text != null && (text.contains("ENOSPC") || text.contains("No space left"))) return true;
        }
        return false;
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }
}
