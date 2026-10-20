package gg.projects.mobile.social;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The friends service's HTTP API (services/social README "HTTP API"), called on a worker thread.
 * JSON only; the session token goes in the {@code Authorization} header, never in a URL or a log.
 * Retries follow the README: a GET or DELETE is tried once more when the connection failed or a
 * gateway gave up (502/504); everything else fails at once with a {@link SocialProblem}.
 */
public final class SocialApi {
    private static final String TAG = "ProjectsSocial";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_BODY = 512 * 1024;

    private final OkHttpClient mClient;
    private final String mBase;
    private final String mUserAgent;
    @Nullable
    private volatile String mToken;
    /** The session a worker thread's requests belong to (set around each task), when not the current one. */
    private final ThreadLocal<String> mBoundToken = new ThreadLocal<>();

    public SocialApi(String base, String userAgent) {
        mBase = base.endsWith("/") ? base : base + "/";
        mUserAgent = userAgent;
        mClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                // The presence socket's keep-alive: a protocol ping every ~45 s, answered by Cloudflare
                // without waking the hub; a missing pong closes the socket and the app reconnects.
                .pingInterval(45, TimeUnit.SECONDS)
                // Few, short requests: one idle connection is enough and it goes after a minute.
                .connectionPool(new ConnectionPool(1, 1, TimeUnit.MINUTES))
                .retryOnConnectionFailure(false)
                .build();
    }

    public String base() {
        return mBase;
    }

    OkHttpClient client() {
        return mClient;
    }

    String userAgent() {
        return mUserAgent;
    }

    public void setToken(@Nullable String token) {
        mToken = token;
    }

    @Nullable
    String token() {
        return mToken;
    }

    /**
     * Binds this worker thread's requests to one session's token until {@code null} is bound again: a
     * request queued before a sign-out or a new sign-in still goes as the session that asked for it.
     */
    void bind(@Nullable String token) {
        if (token == null) mBoundToken.remove();
        else mBoundToken.set(token);
    }

    // ---- account and sign-in ----

    @WorkerThread
    public void health() throws SocialProblem {
        JsonObject o = send("GET", "v1/health", null, false, false);
        if (o == null || !Json.bool(o, "ok", false)) throw SocialProblem.unavailable("health");
    }

    /** {@code POST /v1/auth/claim}: {@code {token, me}}. */
    @WorkerThread
    public JsonObject claim(String verifier, String code, String device) throws SocialProblem {
        JsonObject body = new JsonObject();
        body.addProperty("verifier", verifier);
        body.addProperty("code", code);
        body.addProperty("device", device);
        JsonObject o = send("POST", "v1/auth/claim", body, false, false);
        if (o == null || Json.str(o, "token") == null || Json.obj(o, "me") == null) throw SocialProblem.unavailable("claim");
        return o;
    }

    @WorkerThread
    public void logout() throws SocialProblem {
        send("POST", "v1/auth/logout", null, true, false);
    }

    @WorkerThread
    public JsonObject me() throws SocialProblem {
        return require(send("GET", "v1/me", null, true, false));
    }

    @WorkerThread
    public JsonObject patchMe(JsonObject changes) throws SocialProblem {
        return require(send("PATCH", "v1/me", changes, true, false));
    }

    @WorkerThread
    public String rotateFriendCode() throws SocialProblem {
        String code = Json.str(require(send("POST", "v1/me/friend-code", null, true, false)), "friendCode");
        if (code == null) throw SocialProblem.unavailable("friend code");
        return code;
    }

    @WorkerThread
    public void deleteAccount() throws SocialProblem {
        send("DELETE", "v1/me", null, true, false);
    }

    // ---- friends ----

    @WorkerThread
    public JsonObject friends() throws SocialProblem {
        return require(send("GET", "v1/friends", null, true, false));
    }

    @WorkerThread
    public JsonObject lookup(String code) throws SocialProblem {
        return require(send("POST", "v1/friends/lookup", codeBody(code), true, false));
    }

    /** {@code 201 {status: "requested", user, sentAt}} or {@code 200 {status: "friends", friend}}. */
    @WorkerThread
    public JsonObject request(String code) throws SocialProblem {
        return require(send("POST", "v1/friends/requests", codeBody(code), true, false));
    }

    @WorkerThread
    public JsonObject accept(String from) throws SocialProblem {
        return require(send("POST", "v1/friends/requests/" + id(from) + "/accept", null, true, false));
    }

    @WorkerThread
    public void decline(String from) throws SocialProblem {
        send("POST", "v1/friends/requests/" + id(from) + "/decline", null, true, false);
    }

    @WorkerThread
    public void cancelRequest(String to) throws SocialProblem {
        send("DELETE", "v1/friends/requests/" + id(to), null, true, false);
    }

    @WorkerThread
    public void removeFriend(String friend) throws SocialProblem {
        send("DELETE", "v1/friends/" + id(friend), null, true, false);
    }

    @WorkerThread
    public void block(String user) throws SocialProblem {
        send("PUT", "v1/blocks/" + id(user), null, true, false);
    }

    @WorkerThread
    public void unblock(String user) throws SocialProblem {
        send("DELETE", "v1/blocks/" + id(user), null, true, false);
    }

    // ---- phone notifications ----

    @WorkerThread
    public JsonObject putPushDevice(String token, String app, List<String> kinds) throws SocialProblem {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        body.addProperty("platform", "android");
        body.addProperty("app", app);
        JsonArray list = new JsonArray();
        for (String kind : kinds) list.add(kind);
        body.add("kinds", list);
        return require(send("PUT", "v1/push/device", body, true, false));
    }

    @WorkerThread
    public void deletePushDevice() throws SocialProblem {
        send("DELETE", "v1/push/device", null, true, false);
    }

    // ---- crews ----

    @WorkerThread
    public JsonObject teams() throws SocialProblem {
        return require(send("GET", "v1/teams", null, true, false));
    }

    @WorkerThread
    public JsonObject teamNotifications(JsonObject changes) throws SocialProblem {
        return require(send("PATCH", "v1/teams/notifications", changes, true, false));
    }

    /** {@code POST /v1/teams}: {@code 201 {team, invited}}. */
    @WorkerThread
    public JsonObject createTeam(JsonObject team) throws SocialProblem {
        return require(send("POST", "v1/teams", team, true, true));
    }

    @WorkerThread
    public JsonObject lookupTeam(String code) throws SocialProblem {
        return require(send("POST", "v1/teams/lookup", codeBody(code), true, false));
    }

    @WorkerThread
    public JsonObject joinTeam(String code) throws SocialProblem {
        return require(send("POST", "v1/teams/join", codeBody(code), true, false));
    }

    @WorkerThread
    public JsonObject acceptTeamInvite(String team) throws SocialProblem {
        return require(send("POST", "v1/teams/invites/" + segment(team) + "/accept", null, true, false));
    }

    @WorkerThread
    public void declineTeamInvite(String team) throws SocialProblem {
        send("POST", "v1/teams/invites/" + segment(team) + "/decline", null, true, false);
    }

    @WorkerThread
    public void leaveTeam(String team) throws SocialProblem {
        send("POST", "v1/teams/" + segment(team) + "/leave", null, true, false);
    }

    @WorkerThread
    public void disbandTeam(String team) throws SocialProblem {
        send("DELETE", "v1/teams/" + segment(team), null, true, false);
    }

    @WorkerThread
    public void inviteToTeam(String team, String friend) throws SocialProblem {
        JsonObject body = new JsonObject();
        body.addProperty("to", id(friend));
        send("POST", "v1/teams/" + segment(team) + "/invites", body, true, false);
    }

    @WorkerThread
    public void removeMember(String team, String user) throws SocialProblem {
        send("DELETE", "v1/teams/" + segment(team) + "/members/" + id(user), null, true, false);
    }

    @WorkerThread
    public JsonObject handOver(String team, String to) throws SocialProblem {
        JsonObject body = new JsonObject();
        body.addProperty("to", id(to));
        return require(send("POST", "v1/teams/" + segment(team) + "/founder", body, true, false));
    }

    // ---- plumbing ----

    private static JsonObject codeBody(String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        return body;
    }

    /** A user id in a path: digits only (ids come from the service; anything else is refused). */
    private static String id(String userId) throws SocialProblem {
        if (!SocialModel.isUserId(userId)) throw new SocialProblem(SocialProblem.Kind.FAILED, 0, "bad id", 0);
        return userId;
    }

    /** A crew id in a path: 16 hex characters today; letters, digits, _ and - at most. */
    private static String segment(String value) throws SocialProblem {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw new SocialProblem(SocialProblem.Kind.FAILED, 0, "bad id", 0);
        return value;
    }

    private static JsonObject require(@Nullable JsonObject o) throws SocialProblem {
        if (o == null) throw SocialProblem.unavailable("empty answer");
        return o;
    }

    /**
     * Sends one request. Returns the answer's JSON (null for 204); throws for anything else.
     * {@code teamText}: the request changes a crew's name, tag or motto (a 429 means the day's ten).
     */
    @Nullable
    @WorkerThread
    JsonObject send(String method, String path, @Nullable JsonObject body, boolean authorized, boolean teamText)
            throws SocialProblem {
        boolean idempotent = method.equals("GET") || method.equals("DELETE");
        int tries = idempotent ? 2 : 1;
        SocialProblem last = null;
        for (int attempt = 0; attempt < tries; attempt++) {
            Request.Builder request = new Request.Builder()
                    .url(mBase + path)
                    .header("User-Agent", mUserAgent)
                    .header("Accept", "application/json");
            String bound = mBoundToken.get();
            String token = bound != null ? bound : mToken;
            if (authorized) {
                if (token == null) throw new SocialProblem(SocialProblem.Kind.SIGNED_OUT, 401, "no session", 0);
                request.header("Authorization", "Bearer " + token);
            }
            RequestBody requestBody = body != null ? RequestBody.create(body.toString(), JSON)
                    : (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) ? RequestBody.create(new byte[0], null) : null;
            request.method(method, requestBody);
            try (Response response = mClient.newCall(request.build()).execute()) {
                int status = response.code();
                String text = readBody(response.body());
                if (status == 204) return null;
                if (status >= 200 && status < 300) {
                    JsonObject o = Json.parseObject(text);
                    if (o == null) throw SocialProblem.unavailable("not JSON");
                    return o;
                }
                SocialProblem problem = SocialProblem.of(status, text, response.header("Retry-After"), teamText);
                Log.i(TAG, method + " " + route(path) + " -> " + status + (problem.code == null ? "" : " " + problem.code));
                if (idempotent && (status == 502 || status == 504) && attempt + 1 < tries) {
                    last = problem;
                    continue;
                }
                throw problem;
            } catch (IOException e) {
                Log.i(TAG, method + " " + route(path) + " failed: " + e.getClass().getSimpleName());
                last = SocialProblem.unavailable(e.getClass().getSimpleName());
                if (attempt + 1 >= tries) throw last;
            }
        }
        throw last == null ? SocialProblem.unavailable("no answer") : last;
    }

    /** The path with ids taken out, for logs (no ids or codes in the log). */
    static String route(String path) {
        return path.replaceAll("\\d{17,20}", ":id").replaceAll("/teams/(invites/)?[0-9a-f]{16}", "/teams/$1:team");
    }

    private static String readBody(@Nullable ResponseBody body) throws IOException {
        if (body == null) return "";
        long length = body.contentLength();
        if (length > MAX_BODY) throw new IOException("answer too large");
        byte[] bytes = body.bytes();
        if (bytes.length > MAX_BODY) throw new IOException("answer too large");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
