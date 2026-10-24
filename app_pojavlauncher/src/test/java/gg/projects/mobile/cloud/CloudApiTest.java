package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The API client against a local HTTP server: the session in the header only, If-Match on writes, the error
 * envelope's codes and extra fields, a platform page read as the service being away, and the retry rules (a read
 * once more after a 502, a write never).
 */
public class CloudApiTest {
    private HttpServer mServer;
    private CloudApi mApi;
    private final List<String> mSeen = Collections.synchronizedList(new ArrayList<String>());
    private final Map<String, String> mHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> mBodies = new ConcurrentHashMap<>();
    private final AtomicInteger mFlaky = new AtomicInteger();

    private static void reply(HttpExchange exchange, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        if (status == 429) exchange.getResponseHeaders().add("Retry-After", "17");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Before
    public void start() throws IOException {
        mServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mServer.createContext("/api/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/api/v1/".length());
            String query = exchange.getRequestURI().getRawQuery();
            String method = exchange.getRequestMethod();
            mSeen.add(method + " " + path + (query == null ? "" : "?" + query));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            mHeaders.put(method + " " + path + " auth", auth == null ? "" : auth);
            String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
            if (ifMatch != null) mHeaders.put(method + " " + path + " if-match", ifMatch);
            String body = read(exchange.getRequestBody());
            mBodies.put(method + " " + path, body);
            switch (method + " " + path) {
                case "GET offer":
                    reply(exchange, 200, Vectors.resource("cloud/fixtures/offer.json"), "application/json");
                    return;
                case "GET accounts":
                    if (auth == null) {
                        reply(exchange, 401, "{\"error\":{\"code\":\"unauthorized\",\"message\":\"no\"}}", "application/json");
                        return;
                    }
                    if (mFlaky.getAndIncrement() == 0) {
                        reply(exchange, 502, "<html>Bad gateway</html>", "text/html");
                        return;
                    }
                    reply(exchange, 200, Vectors.resource("cloud/fixtures/accounts.json"), "application/json");
                    return;
                case "PATCH accounts/a_7KQ2MZ3P9TW4XB1C":
                    reply(exchange, 412, "{\"error\":{\"code\":\"rev_conflict\",\"message\":\"stale\",\"rev\":8}}", "application/json");
                    return;
                case "POST accounts/a_7KQ2MZ3P9TW4XB1C/restart":
                    reply(exchange, 502, "<html>Bad gateway</html>", "text/html");
                    return;
                case "POST accounts/a_7KQ2MZ3P9TW4XB1C/send":
                    reply(exchange, 400, "{\"error\":{\"code\":\"invalid_command\",\"message\":\"x\",\"reason\":\"secret\"}}", "application/json");
                    return;
                case "GET accounts/a_7KQ2MZ3P9TW4XB1C/plus/feed":
                    reply(exchange, 503, "{\"error\":{\"code\":\"host_offline\",\"message\":\"x\",\"hostId\":\"kz1\",\"retryAfter\":30}}", "application/json");
                    return;
                case "GET accounts/a_7KQ2MZ3P9TW4XB1C/plus":
                    reply(exchange, 200, Vectors.resource("cloud/fixtures/plus.json"), "application/json");
                    return;
                case "PUT accounts/a_7KQ2MZ3P9TW4XB1C/plus":
                    reply(exchange, 200, Vectors.resource("cloud/fixtures/plus.json"), "application/json");
                    return;
                case "POST accounts/a_7KQ2MZ3P9TW4XB1C/plus/approvals/01JBXK3D2E5F6G7H8J9K0M1N2P":
                    reply(exchange, 409, "{\"error\":{\"code\":\"approval_closed\",\"message\":\"x\",\"state\":\"expired\"}}", "application/json");
                    return;
                case "GET me/notifications":
                    reply(exchange, 429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"x\"}}", "application/json");
                    return;
                case "POST accounts/a_7KQ2MZ3P9TW4XB1C/stop":
                    reply(exchange, 503, "<html>Cloudflare</html>", "text/html");
                    return;
                default:
                    reply(exchange, 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"x\"}}", "application/json");
            }
        });
        mServer.start();
        mApi = new CloudApi("http://127.0.0.1:" + mServer.getAddress().getPort() + "/api/v1/", "ProjectsTest/1", line -> { });
    }

    @After
    public void stop() {
        mServer.stop(0);
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
        assertEquals("wss://afk.projects.gg/api/v1/live?host=kz1", prod.liveUrl("kz1"));
        assertEquals("https://afk.projects.gg/satin-al?hesap=a_7KQ2MZ3P9TW4XB1C", prod.siteUrl("/satin-al?hesap=a_7KQ2MZ3P9TW4XB1C"));
        assertEquals("ws://127.0.0.1:" + mServer.getAddress().getPort() + "/api/v1/live?host=kz1", mApi.liveUrl("kz1"));
        assertEquals("accounts/:id/plus/inbox/:name", CloudApi.route("accounts/a_7KQ2MZ3P9TW4XB1C/plus/inbox/Kaan_07?limit=100"));
        assertEquals("accounts/:id/plus/approvals/:aid", CloudApi.route("accounts/a_7KQ2MZ3P9TW4XB1C/plus/approvals/01JBXK3D2E5F6G7H8J9K0M1N2P"));
    }
}
