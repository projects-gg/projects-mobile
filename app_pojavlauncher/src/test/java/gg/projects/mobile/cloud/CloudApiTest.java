package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The API client against a local HTTP server: the session in the header only, If-Match on writes, the error
 * envelope's codes and extra fields, a platform page read as the service being away, and the retry rules (a read
 * once more after a 502, a write never). The server is a few lines over a socket: unit tests compile against
 * android.jar, which has no com.sun.net.httpserver.
 */
public class CloudApiTest {
    private TinyServer mServer;
    private CloudApi mApi;
    private final List<String> mSeen = Collections.synchronizedList(new ArrayList<String>());
    private final Map<String, String> mHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> mBodies = new ConcurrentHashMap<>();
    private final AtomicInteger mFlaky = new AtomicInteger();

    /** One answer of the test server. */
    private static final class Answer {
        final int status;
        final String body;
        final String type;

        Answer(int status, String body, String type) {
            this.status = status;
            this.body = body;
            this.type = type;
        }
    }

    private static Answer reply(int status, String body, String type) {
        return new Answer(status, body, type);
    }

    private interface Handler {
        Answer handle(String method, String path, String query, Map<String, String> headers, String body) throws IOException;
    }

    /** HTTP/1.1 over a server socket, one request per connection ("Connection: close"). */
    private static final class TinyServer {
        private final ServerSocket mSocket;
        private final Thread mThread;

        TinyServer(final Handler handler) throws IOException {
            mSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            mThread = new Thread(() -> {
                while (!mSocket.isClosed()) {
                    try (Socket client = mSocket.accept()) {
                        serve(client, handler);
                    } catch (IOException ignored) {
                        // Closed at the end of the test, or a client that went away.
                    }
                }
            }, "cloud-api-test-server");
            mThread.setDaemon(true);
            mThread.start();
        }

        int port() {
            return mSocket.getLocalPort();
        }

        void stop() throws IOException {
            mSocket.close();
        }

        private static String line(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') out.write(c);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }

        private static void serve(Socket client, Handler handler) throws IOException {
            InputStream in = client.getInputStream();
            String request = line(in);
            if (request.isEmpty()) return;
            String[] parts = request.split(" ");
            String target = parts[1];
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            String query = q < 0 ? null : target.substring(q + 1);
            Map<String, String> headers = new HashMap<>();
            String header;
            while (!(header = line(in)).isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
            }
            int length = headers.containsKey("content-length") ? Integer.parseInt(headers.get("content-length")) : 0;
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            Answer answer = handler.handle(parts[0], path, query, headers, new String(body, 0, read, StandardCharsets.UTF_8));
            byte[] bytes = answer.body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(answer.status).append(" X\r\n")
                    .append("Content-Type: ").append(answer.type).append("\r\n")
                    .append("Content-Length: ").append(answer.status == 204 ? 0 : bytes.length).append("\r\n")
                    .append("Connection: close\r\n");
            if (answer.status == 429) head.append("Retry-After: 17\r\n");
            head.append("\r\n");
            OutputStream out = client.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (answer.status != 204) out.write(bytes);
            out.flush();
        }
    }

    @Before
    public void start() throws IOException {
        mServer = new TinyServer((method, fullPath, query, headers, body) -> {
            if (!fullPath.startsWith("/api/v1/")) return reply(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"x\"}}", "application/json");
            String path = fullPath.substring("/api/v1/".length());
            mSeen.add(method + " " + path + (query == null ? "" : "?" + query));
            String auth = headers.get("authorization");
            mHeaders.put(method + " " + path + " auth", auth == null ? "" : auth);
            String ifMatch = headers.get("if-match");
            if (ifMatch != null) mHeaders.put(method + " " + path + " if-match", ifMatch);
            mBodies.put(method + " " + path, body);
            switch (method + " " + path) {
                case "GET offer":
                    return reply(200, Vectors.resource("cloud/fixtures/offer.json"), "application/json");
                case "GET accounts":
                    if (auth == null) {
                        return reply(401, "{\"error\":{\"code\":\"unauthorized\",\"message\":\"no\"}}", "application/json");
                    }
                    if (mFlaky.getAndIncrement() == 0) {
                        return reply(502, "<html>Bad gateway</html>", "text/html");
                    }
                    return reply(200, Vectors.resource("cloud/fixtures/accounts.json"), "application/json");
                case "PATCH accounts/a_7KQ2MZ3P9TW4XB1C":
                    return reply(412, "{\"error\":{\"code\":\"rev_conflict\",\"message\":\"stale\",\"rev\":8}}", "application/json");
                case "POST accounts/a_7KQ2MZ3P9TW4XB1C/restart":
                    return reply(502, "<html>Bad gateway</html>", "text/html");
                case "POST accounts/a_7KQ2MZ3P9TW4XB1C/send":
                    return reply(400, "{\"error\":{\"code\":\"invalid_command\",\"message\":\"x\",\"reason\":\"secret\"}}", "application/json");
                case "GET accounts/a_7KQ2MZ3P9TW4XB1C/plus/feed":
                    return reply(503, "{\"error\":{\"code\":\"host_offline\",\"message\":\"x\",\"hostId\":\"kz1\",\"retryAfter\":30}}", "application/json");
                case "GET accounts/a_7KQ2MZ3P9TW4XB1C/plus":
                    return reply(200, Vectors.resource("cloud/fixtures/plus.json"), "application/json");
                case "PUT accounts/a_7KQ2MZ3P9TW4XB1C/plus":
                    return reply(200, Vectors.resource("cloud/fixtures/plus.json"), "application/json");
                case "POST accounts/a_7KQ2MZ3P9TW4XB1C/plus/approvals/01JBXK3D2E5F6G7H8J9K0M1N2P":
                    return reply(409, "{\"error\":{\"code\":\"approval_closed\",\"message\":\"x\",\"state\":\"expired\"}}", "application/json");
                case "GET me/notifications":
                    return reply(429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"x\"}}", "application/json");
                case "POST accounts/a_7KQ2MZ3P9TW4XB1C/stop":
                    return reply(503, "<html>Cloudflare</html>", "text/html");
                default:
                    return reply(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"x\"}}", "application/json");
            }
        });
        mApi = new CloudApi("http://127.0.0.1:" + mServer.port() + "/api/v1/", "ProjectsTest/1", line -> { });
    }

    @After
    public void stop() throws IOException {
        mServer.stop();
    }

    @Test
    public void readsTheOfferWithoutASession() throws Exception {
        CloudModel.Offer offer = mApi.offer();
        assertTrue(offer.available());
        assertEquals("", mHeaders.get("GET offer auth"));
    }

    @Test
    public void aReadIsTriedOnceMoreAfterAGatewayFailure() throws Exception {
        CloudApi.AccountList list = mApi.accounts("pls_token");
        assertEquals(3, list.accounts.size());
        assertEquals(1792846800L, list.now);
        assertEquals("Bearer pls_token", mHeaders.get("GET accounts auth"));
        assertEquals(2, Collections.frequency(mSeen, "GET accounts"));
    }

    @Test
    public void aWriteIsNeverRepeated() {
        try {
            mApi.restart("pls_token", "a_7KQ2MZ3P9TW4XB1C");
            fail("restarted");
        } catch (CloudProblem problem) {
            assertEquals(CloudProblem.Kind.UNAVAILABLE, problem.kind);
            assertNull(problem.code);
        }
        assertEquals(1, Collections.frequency(mSeen, "POST accounts/a_7KQ2MZ3P9TW4XB1C/restart"));
    }

    @Test
    public void aStaleChangeIsAConflictWithTheNewRevision() {
        JsonObject patch = new JsonObject();
        patch.addProperty("name", "Yeni");
        try {
            mApi.patchAccount("pls_token", "a_7KQ2MZ3P9TW4XB1C", 7, patch);
            fail("patched");
        } catch (CloudProblem problem) {
            assertEquals(CloudProblem.Kind.CONFLICT, problem.kind);
            assertEquals(8, problem.number("rev", 0));
        }
        assertEquals("\"7\"", mHeaders.get("PATCH accounts/a_7KQ2MZ3P9TW4XB1C if-match"));
        assertEquals("{\"name\":\"Yeni\"}", mBodies.get("PATCH accounts/a_7KQ2MZ3P9TW4XB1C"));
    }

    @Test
    public void refusalsCarryTheirCodeAndExtras() throws Exception {
        try {
            mApi.sendLine("pls_token", "a_7KQ2MZ3P9TW4XB1C", "/login x");
            fail("sent");
        } catch (CloudProblem problem) {
            assertEquals(CloudProblem.Kind.REFUSED, problem.kind);
            assertTrue(problem.is("invalid_command"));
            assertEquals("secret", problem.reason());
        }
        try {
            mApi.feed("pls_token", "a_7KQ2MZ3P9TW4XB1C", null);
            fail("fed");
        } catch (CloudProblem problem) {
            assertEquals(CloudProblem.Kind.HOST_OFFLINE, problem.kind);
            assertEquals(30, problem.retryAfter);
        }
        try {
            mApi.decide("pls_token", "a_7KQ2MZ3P9TW4XB1C", "01JBXK3D2E5F6G7H8J9K0M1N2P", true, 35000L);
            fail("decided");
        } catch (CloudProblem problem) {
            assertTrue(problem.is("approval_closed"));
            assertEquals("expired", problem.string("state"));
        }
        assertEquals("{\"decision\":\"approve\",\"maxPrice\":35000}",
                mBodies.get("POST accounts/a_7KQ2MZ3P9TW4XB1C/plus/approvals/01JBXK3D2E5F6G7H8J9K0M1N2P"));
        try {
            mApi.notifications("pls_token");
            fail("read");
        } catch (CloudProblem problem) {
            assertEquals(CloudProblem.Kind.RATE_LIMITED, problem.kind);
            assertEquals(17, problem.retryAfter);
        }
    }

    @Test
    public void aPlatformPageIsTheServiceBeingAway() {
        try {
            mApi.stop("pls_token", "a_7KQ2MZ3P9TW4XB1C");
            fail("stopped");
        } catch (CloudProblem problem) {
            assertEquals(CloudProblem.Kind.UNAVAILABLE, problem.kind);
        }
        // A 503 is not a gateway giving up: not tried again at once (the screens back off).
        assertEquals(1, Collections.frequency(mSeen, "POST accounts/a_7KQ2MZ3P9TW4XB1C/stop"));
    }

    @Test
    public void afkPlusSettingsGoBackWholeOnTheirRevision() throws Exception {
        CloudModel.PlusDoc doc = mApi.plus("pls_token", "a_7KQ2MZ3P9TW4XB1C");
        PlusConfig config = new PlusConfig(doc.config);
        config.setScheduleOn(false);
        mApi.putPlus("pls_token", "a_7KQ2MZ3P9TW4XB1C", doc.aiRev, config.toJson());
        assertEquals("\"14\"", mHeaders.get("PUT accounts/a_7KQ2MZ3P9TW4XB1C/plus if-match"));
        JsonObject sent = Vectors.json(mBodies.get("PUT accounts/a_7KQ2MZ3P9TW4XB1C/plus"));
        assertEquals(false, sent.getAsJsonObject("schedule").get("on").getAsBoolean());
        // Everything else exactly as stored.
        sent.getAsJsonObject("schedule").addProperty("on", true);
        assertEquals(doc.config, sent);
    }

    @Test
    public void idsThatCouldLeaveTheRouteAreRefusedBeforeSending() {
        try {
            mApi.start("pls_token", "../me");
            fail("sent");
        } catch (CloudProblem problem) {
            assertEquals(CloudProblem.Kind.FAILED, problem.kind);
        }
        try {
            mApi.thread("pls_token", "a_7KQ2MZ3P9TW4XB1C", "x/../y", null);
            fail("sent");
        } catch (CloudProblem problem) {
            assertEquals(CloudProblem.Kind.FAILED, problem.kind);
        }
        assertTrue(mSeen.isEmpty());
    }

    @Test
    public void urlsAndLogs() {
        CloudApi prod = new CloudApi(CloudApi.PRODUCTION, "x", line -> { });
        assertEquals("wss://afk.projects.gg/api/v1/live?host=kz1&client=phone", prod.liveUrl("kz1"));
        assertEquals("https://afk.projects.gg/satin-al?hesap=a_7KQ2MZ3P9TW4XB1C", prod.siteUrl("/satin-al?hesap=a_7KQ2MZ3P9TW4XB1C"));
        // English: the public pages under /en, an account's pages at their one address.
        assertEquals("/en/satin-al?hesap=a_7KQ2MZ3P9TW4XB1C&tier=plus", CloudApi.sitePath("/satin-al?hesap=a_7KQ2MZ3P9TW4XB1C&tier=plus", "en"));
        assertEquals("/satin-al?hesap=a_7KQ2MZ3P9TW4XB1C&tier=plus", CloudApi.sitePath("/satin-al?hesap=a_7KQ2MZ3P9TW4XB1C&tier=plus", "tr"));
        assertEquals("/en/", CloudApi.sitePath("/", "en"));
        assertEquals("/", CloudApi.sitePath("/", "tr"));
        assertEquals("/hesap/a_7KQ2MZ3P9TW4XB1C/itiraz", CloudApi.sitePath("/hesap/a_7KQ2MZ3P9TW4XB1C/itiraz", "en"));
        assertEquals("/satin-alma", CloudApi.sitePath("/satin-alma", "en"));
        assertEquals("ws://127.0.0.1:" + mServer.port() + "/api/v1/live?host=kz1&client=phone", mApi.liveUrl("kz1"));
        assertEquals("accounts/:id/plus/inbox/:name", CloudApi.route("accounts/a_7KQ2MZ3P9TW4XB1C/plus/inbox/Kaan_07?limit=100"));
        assertEquals("accounts/:id/plus/approvals/:aid", CloudApi.route("accounts/a_7KQ2MZ3P9TW4XB1C/plus/approvals/01JBXK3D2E5F6G7H8J9K0M1N2P"));
    }
}
