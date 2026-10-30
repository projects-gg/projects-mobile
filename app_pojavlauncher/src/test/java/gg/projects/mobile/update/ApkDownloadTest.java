package gg.projects.mobile.update;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import gg.projects.mobile.content.Hashes;

/**
 * APK-UPD: the APK download against a local HTTP server (a plain socket: the Android unit test classpath has
 * no JDK HTTP server): the whole file, going on from a part file, servers that cannot, wrong bytes, gone
 * addresses, a cut connection, a stop.
 */
public class ApkDownloadTest {
    @Rule
    public TemporaryFolder mTmp = new TemporaryFolder();

    private final List<Server> mServers = new ArrayList<>();
    private static final byte[] APK = new byte[700_000];
    private static final String SHA;

    static {
        new Random(46).nextBytes(APK);
        SHA = Hashes.sha256Hex(APK);
    }

    @After
    public void tearDown() {
        for (Server server : mServers) server.stop();
    }

    /** Records progress; stops after {@code stopAt} bytes when set. */
    private static final class Progress implements ApkDownload.Listener {
        final List<Long> seen = new CopyOnWriteArrayList<>();
        long stopAt = -1;

        @Override
        public void onProgress(long done, long total) {
            assertEquals(APK.length, total);
            seen.add(done);
        }

        @Override
        public boolean cancelled() {
            return stopAt >= 0 && !seen.isEmpty() && seen.get(seen.size() - 1) >= stopAt;
        }
    }

    private Server server() throws IOException {
        Server server = new Server();
        mServers.add(server);
        return server;
    }

    private File fetch(List<String> urls, File dir, Progress progress) throws UpdateException {
        return ApkDownload.fetch(urls, APK.length, SHA, dir, "projects-launcher-22.apk", "test", progress);
    }

    @Test
    public void theWholeFile() throws Exception {
        Server server = server();
        File dir = mTmp.newFolder();
        Progress progress = new Progress();
        File apk = fetch(Collections.singletonList(server.url("/a.apk")), dir, progress);
        assertEquals(new File(dir, "projects-launcher-22.apk"), apk);
        assertArrayEquals(APK, Files.readAllBytes(apk.toPath()));
        assertFalse(new File(dir, "projects-launcher-22.apk.part").exists());
        assertEquals(Long.valueOf(APK.length), progress.seen.get(progress.seen.size() - 1));
        assertTrue("about every 1%, not every chunk", progress.seen.size() < 20);
        assertEquals(Collections.singletonList("GET /a.apk "), server.asked);
        // A finished file is not downloaded again.
        assertEquals(apk, fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress()));
        assertEquals(1, server.asked.size());
    }

    @Test
    public void goesOnFromAPartFile() throws Exception {
        Server server = server();
        File dir = mTmp.newFolder();
        Files.write(new File(dir, "projects-launcher-22.apk.part").toPath(), Arrays.copyOf(APK, 300_000));
        File apk = fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress());
        assertArrayEquals(APK, Files.readAllBytes(apk.toPath()));
        assertEquals(Collections.singletonList("GET /a.apk bytes=300000-"), server.asked);
    }

    @Test
    public void aServerWithoutRangesStartsOver() throws Exception {
        Server server = server();
        server.ranges = false;
        File dir = mTmp.newFolder();
        Files.write(new File(dir, "projects-launcher-22.apk.part").toPath(), Arrays.copyOf(APK, 300_000));
        File apk = fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress());
        assertArrayEquals(APK, Files.readAllBytes(apk.toPath()));
    }

    @Test
    public void aPartThatDoesNotContinueStartsOver() throws Exception {
        Server server = server();
        server.rangeStartOffset = 10;
        File dir = mTmp.newFolder();
        Files.write(new File(dir, "projects-launcher-22.apk.part").toPath(), Arrays.copyOf(APK, 300_000));
        File apk = fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress());
        assertArrayEquals(APK, Files.readAllBytes(apk.toPath()));
        assertEquals(Arrays.asList("GET /a.apk bytes=300000-", "GET /a.apk "), server.asked);
    }

    @Test
    public void aWrongPartIsReplaced() throws Exception {
        Server server = server();
        File dir = mTmp.newFolder();
        byte[] wrong = Arrays.copyOf(APK, 300_000);
        wrong[5] ^= 1;
        Files.write(new File(dir, "projects-launcher-22.apk.part").toPath(), wrong);
        try {
            fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress());
            fail("kept a wrong part");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.CORRUPT, e.kind);
        }
        assertFalse(new File(dir, "projects-launcher-22.apk.part").exists());
        // The next try starts from nothing and succeeds.
        assertArrayEquals(APK, Files.readAllBytes(fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress()).toPath()));
    }

    @Test
    public void aCompletePartIsCheckedWhenTheServerHasNothingMore() throws Exception {
        Server server = server();
        File dir = mTmp.newFolder();
        Files.write(new File(dir, "projects-launcher-22.apk.part").toPath(), APK);
        File apk = fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress());
        assertArrayEquals(APK, Files.readAllBytes(apk.toPath()));
        assertEquals(Collections.singletonList("GET /a.apk bytes=700000-"), server.asked);
    }

    @Test
    public void otherBytesThanAnnounced() throws Exception {
        Server server = server();
        byte[] other = APK.clone();
        other[123] ^= 1;
        server.body = other;
        File dir = mTmp.newFolder();
        try {
            fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress());
            fail("accepted other bytes");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.CORRUPT, e.kind);
        }
        assertFalse(new File(dir, "projects-launcher-22.apk.part").exists());
        assertFalse(new File(dir, "projects-launcher-22.apk").exists());
        server.body = Arrays.copyOf(APK, APK.length + 5);
        try {
            fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress());
            fail("accepted a longer file");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.CORRUPT, e.kind);
        }
    }

    @Test
    public void aGoneAddressMovesToTheNextOne() throws Exception {
        Server gone = server();
        gone.status = 404;
        Server good = server();
        File apk = fetch(Arrays.asList(gone.url("/a.apk"), good.url("/b.apk")), mTmp.newFolder(), new Progress());
        assertArrayEquals(APK, Files.readAllBytes(apk.toPath()));
        try {
            fetch(Collections.singletonList(gone.url("/a.apk")), mTmp.newFolder(), new Progress());
            fail("downloaded from nowhere");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.GONE, e.kind);
        }
    }

    @Test
    public void serverTroubleIsAConnectionProblem() throws Exception {
        Server broken = server();
        broken.status = 503;
        Server good = server();
        try {
            fetch(Arrays.asList(broken.url("/a.apk"), good.url("/b.apk")), mTmp.newFolder(), new Progress());
            fail("went on after a server error");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.NETWORK, e.kind);
        }
        try {
            fetch(Collections.singletonList("http://127.0.0.1:1/a.apk"), mTmp.newFolder(), new Progress());
            fail("downloaded from a closed port");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.NETWORK, e.kind);
        }
    }

    @Test
    public void aCutConnectionKeepsThePartForTheNextTry() throws Exception {
        Server server = server();
        server.cutAfter = 250_000;
        File dir = mTmp.newFolder();
        try {
            fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress());
            fail("finished a cut download");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.NETWORK, e.kind);
        }
        assertEquals(250_000, new File(dir, "projects-launcher-22.apk.part").length());
        server.cutAfter = -1;
        File apk = fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress());
        assertArrayEquals(APK, Files.readAllBytes(apk.toPath()));
        assertEquals("GET /a.apk bytes=250000-", server.asked.get(1));
    }

    @Test
    public void durdurStopsAndKeepsThePart() throws Exception {
        Server server = server();
        File dir = mTmp.newFolder();
        Progress progress = new Progress();
        progress.stopAt = 1;
        try {
            fetch(Collections.singletonList(server.url("/a.apk")), dir, progress);
            fail("did not stop");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.CANCELLED, e.kind);
        }
        long kept = new File(dir, "projects-launcher-22.apk.part").length();
        assertTrue(kept > 0 && kept < APK.length);
        assertArrayEquals(APK, Files.readAllBytes(fetch(Collections.singletonList(server.url("/a.apk")), dir, new Progress()).toPath()));
    }

    @Test
    public void headerRules() {
        assertTrue(ApkDownload.continues("bytes 300-699/700", 300, 700));
        assertTrue(ApkDownload.continues(" Bytes 300-699/700 ", 300, 700));
        assertFalse(ApkDownload.continues("bytes 310-699/700", 300, 700));
        assertFalse(ApkDownload.continues("bytes 300-698/700", 300, 700));
        assertFalse(ApkDownload.continues("bytes 300-699/*", 300, 700));
        assertFalse(ApkDownload.continues("bytes 300-699/701", 300, 700));
        assertFalse(ApkDownload.continues(null, 300, 700));
        assertFalse(ApkDownload.continues("bytes 99999999999999999999-1/2", 300, 700));
        assertEquals(700, ApkDownload.contentLength(" 700 "));
        assertEquals(-1, ApkDownload.contentLength(null));
        assertEquals(-1, ApkDownload.contentLength("-5"));
        assertEquals(-1, ApkDownload.contentLength("lots"));
    }

    /** A tiny HTTP/1.1 server for one file, with Range support and switches for the cases above. */
    private static final class Server {
        final ServerSocket socket;
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile byte[] body = APK;
        volatile int status = 200;
        volatile boolean ranges = true;
        volatile long rangeStartOffset = 0;
        volatile long cutAfter = -1;

        Server() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            Thread accept = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        final Socket client = socket.accept();
                        Thread one = new Thread(() -> serve(client), "apk-request");
                        one.setDaemon(true);
                        one.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "apk-server");
            accept.setDaemon(true);
            accept.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + socket.getLocalPort() + path;
        }

        void stop() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        private void serve(Socket client) {
            try (Socket s = client) {
                String head = readHead(s.getInputStream());
                String[] lines = head.split("\r\n");
                String path = lines[0].split(" ")[1];
                String range = "";
                for (String line : lines) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("range:")) range = line.substring(6).trim();
                }
                asked.add("GET " + path + " " + range);
                OutputStream out = s.getOutputStream();
                byte[] data = body;
                if (status != 200) {
                    out.write(("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                long start = 0;
                if (ranges && range.startsWith("bytes=")) {
                    start = Long.parseLong(range.substring(6, range.indexOf('-')));
                    if (start >= data.length) {
                        out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + data.length
                                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        return;
                    }
                    long shown = start + rangeStartOffset;
                    out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + shown + "-" + (data.length - 1) + "/" + data.length
                            + "\r\nContent-Length: " + (data.length - start) + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                } else {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                }
                long end = cutAfter >= 0 ? Math.min(data.length, cutAfter) : data.length;
                for (long at = start; at < end; ) {
                    int n = (int) Math.min(16_384, end - at);
                    out.write(data, (int) at, n);
                    at += n;
                }
                out.flush();
            } catch (IOException ignored) {
                // The client went away (a stop, a refused part): nothing to do.
            }
        }

        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int last4 = 0;
            int b;
            while ((b = in.read()) != -1) {
                head.write(b);
                last4 = (last4 << 8) | b;
                if (last4 == 0x0D0A0D0A) break;
            }
            return head.toString("US-ASCII");
        }
    }
}
