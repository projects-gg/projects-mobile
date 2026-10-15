package gg.projects.mobile.content;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads planned pack files: every URL of a file is tried in order, the result is verified
 * (size, SHA-1 and SHA-512 when the manifest has it) and moved into place atomically. Either all
 * downloads succeed or the call fails; deletions and the new pack state are the caller's next step.
 */
public final class PackDownloader {
    private PackDownloader() {}

    public interface Progress {
        void onProgress(long doneBytes, long totalBytes);
    }

    public interface Transport {
        /** Downloads {@code url} to {@code destination}, reporting bytes written so far. */
        void download(String url, File destination, long maxBytes, Progress progress) throws IOException;
    }

    public static void downloadAll(final File gameDir, List<PackSyncPlanner.Download> downloads, final Transport transport,
                                   final Progress progress, int threads) throws IOException {
        long total = 0;
        for (PackSyncPlanner.Download download : downloads) total += Math.max(0, download.file.fileSize);
        final long totalBytes = total;
        final AtomicLong done = new AtomicLong();
        final AtomicBoolean failed = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (final PackSyncPlanner.Download download : downloads) {
                futures.add(pool.submit(() -> {
                    if (failed.get()) return null;
                    try {
                        fetch(gameDir, download.file, transport, new Progress() {
                            long reported = 0;

                            @Override
                            public void onProgress(long fileBytes, long ignored) {
                                done.addAndGet(fileBytes - reported);
                                reported = fileBytes;
                                if (progress != null) progress.onProgress(done.get(), totalBytes);
                            }
                        });
                    } catch (IOException e) {
                        failed.set(true);
                        throw e;
                    }
                    return null;
                }));
            }
            IOException first = null;
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (first == null) {
                        first = e.getCause() instanceof IOException ? (IOException) e.getCause()
                                : new IOException(String.valueOf(e.getCause()), e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            if (first != null) throw first;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void fetch(File gameDir, PackManifest.PackFile file, Transport transport, Progress progress) throws IOException {
        File target = new File(gameDir, file.path);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(parent, target.getName() + ".projects-download");
        long maxBytes = file.fileSize > 0 ? file.fileSize : 1L << 31;
        IOException last = null;
        for (String url : file.downloads) {
            try {
                transport.download(url, tmp, maxBytes, progress);
                verify(tmp, file);
                if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
                if (!tmp.renameTo(target)) throw new IOException("cannot move " + tmp + " to " + target);
                return;
            } catch (IOException e) {
                last = e;
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                progress.onProgress(0, 0);
            }
        }
        throw new IOException("could not download " + file.path + (last == null ? "" : ": " + last.getMessage()), last);
    }

    static void verify(File downloaded, PackManifest.PackFile file) throws IOException {
        if (file.fileSize > 0 && downloaded.length() != file.fileSize) {
            throw new IOException(file.path + ": expected " + file.fileSize + " bytes, got " + downloaded.length());
        }
        String sha512 = file.sha512();
        if (sha512 != null) {
            if (!sha512.equals(Hashes.fileHex(downloaded, "SHA-512"))) throw new IOException(file.path + ": SHA-512 mismatch");
        }
        if (!file.sha1().equals(Hashes.fileHex(downloaded, "SHA-1"))) throw new IOException(file.path + ": SHA-1 mismatch");
    }

    /** Streams over HttpURLConnection (redirects followed, size capped). */
    public static Transport httpTransport(final String userAgent) {
        return new Transport() {
            @Override
            public void download(String url, File destination, long maxBytes, Progress progress) throws IOException {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                try {
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", userAgent);
                    int status = connection.getResponseCode();
                    if (status != HttpURLConnection.HTTP_OK) throw new HttpStatusException(status, url);
                    try (InputStream in = connection.getInputStream();
                         OutputStream out = new FileOutputStream(destination)) {
                        byte[] buffer = new byte[64 * 1024];
                        long written = 0;
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            written += read;
                            if (written > maxBytes) throw new IOException(url + " is larger than expected");
                            out.write(buffer, 0, read);
                            progress.onProgress(written, maxBytes);
                        }
                    }
                } finally {
                    connection.disconnect();
                }
            }
        };
    }
}
