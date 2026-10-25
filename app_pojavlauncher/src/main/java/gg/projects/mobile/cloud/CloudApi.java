package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import gg.projects.mobile.social.Json;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.EventListener;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 7/24 Bulut's player API ({@code https://afk.projects.gg/api/v1}, services/cloud-afk README "Player API" and "AFK+
 * player API"), called on a worker thread with the player's friends session ({@code Authorization: Bearer pls_…},
 * the launcher's way: a phone's session is a launcher session; no separate sign-in). The token goes only in that
 * header, never in a URL or a log. Retries follow the README: a read, a start or a stop is tried once more when the
 * connection failed or a gateway gave up (502/504); a request that failed on an idle connection kept from an earlier
 * one goes once more on a new one (the service never saw it); restart, send and every other write never repeat.
 */
public final class CloudApi {
    public static final String PRODUCTION = "https://afk.projects.gg/api/v1/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_BODY = 512 * 1024;
    private static final Pattern ACCOUNT_ID = Pattern.compile("^a_[0-9A-HJKMNP-TV-Z]{16}$");
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern GAME_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    public interface Log {
        void line(String text);
    }

    private final OkHttpClient mClient;
    private final String mBase;
    private final String mUserAgent;
    private final Log mLog;

    /** {@code base}: the API's base ending in {@code /api/v1/} (production, or a debug build's local service). */
    public CloudApi(String base, String userAgent, Log log) {
        mBase = base.endsWith("/") ? base : base + "/";
        mUserAgent = userAgent;
        mLog = log;
        mClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                // The live socket's keep-alive is the contract's own text "ping" (CloudLive), never a timer here.
                .connectionPool(new ConnectionPool(1, 1, TimeUnit.MINUTES))
                .retryOnConnectionFailure(false)
                .eventListenerFactory(call -> {
                    final Attempt attempt = call.request().tag(Attempt.class);
                    if (attempt == null) return EventListener.NONE;
                    return new EventListener() {
                        @Override
                        public void connectStart(Call c, InetSocketAddress address, Proxy proxy) {
                            attempt.newConnection = true;
                        }
                    };
                })
                .build();
    }

    private static final class Attempt {
        volatile boolean newConnection;
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

    /** The live console socket of one machine: {@code wss://…/api/v1/live?host=<host>}. */
    public String liveUrl(String host) {
        String base = mBase.startsWith("https://") ? "wss://" + mBase.substring(8) : mBase.startsWith("http://") ? "ws://" + mBase.substring(7) : mBase;
        return base + "live?host=" + encode(host);
    }

    /** A page of the site for the browser ({@code /satin-al?hesap=…}, {@code /hesap/…}). */
    public String siteUrl(String path) {
        String origin = mBase.endsWith("/api/v1/") ? mBase.substring(0, mBase.length() - "/api/v1/".length()) : mBase;
        return origin + path;
    }

    /** The site's public pages: their English copies live under {@code /en} (the site's router, PUBLIC_PAGES). */
    private static final String[] PUBLIC_PAGES = {"/satin-al", "/afk-plus", "/kosullar", "/girilemeyen-sunucular", "/sikayet"};

    /**
     * A page's path in the app's language: in English a public page (the home page, buying, the terms) is under
     * {@code /en}; the rest of the site (accounts) has one address and follows the player's own choice there.
     */
    public static String sitePath(String path, String lang) {
        if (!"en".equals(lang)) return path;
        if (path.equals("/") || path.startsWith("/?") || path.startsWith("/#")) return "/en" + path;
        for (String page : PUBLIC_PAGES) {
            if (path.equals(page) || path.startsWith(page + "?") || path.startsWith(page + "#")) return "/en" + path;
        }
        return path;
    }

    // ---- public reads ----

    @WorkerThread
    public CloudModel.Offer offer() throws CloudProblem {
        CloudModel.Offer offer = CloudModel.Offer.read(send("GET", "offer", null, null, null, Repeat.SAFE));
        if (offer == null) throw CloudProblem.unavailable("offer");
        return offer;
    }

    @WorkerThread
    public CloudModel.Versions versions() throws CloudProblem {
        return CloudModel.Versions.read(send("GET", "versions", null, null, null, Repeat.SAFE));
    }

    @WorkerThread
    public CloudSeal.VaultKey vaultKey() throws CloudProblem {
        JsonObject o = send("GET", "vault-key", null, null, null, Repeat.SAFE);
        String kid = Json.str(o, "kid");
        String alg = Json.str(o, "alg");
        String key = Json.str(o, "publicKey");
        if (kid == null || alg == null || key == null) throw CloudProblem.unavailable("vault key");
        return new CloudSeal.VaultKey(kid, alg, key);
    }

    @WorkerThread
    public CloudModel.PlusCatalog catalog(String mode) throws CloudProblem {
        CloudModel.PlusCatalog catalog = CloudModel.PlusCatalog.read(send("GET", "plus/catalog?mode=" + encode(mode), null, null, null, Repeat.SAFE));
        if (catalog == null) throw CloudProblem.unavailable("catalog");
        return catalog;
    }

    // ---- the player and accounts ----

    @WorkerThread
    public CloudModel.Me me(String token) throws CloudProblem {
        CloudModel.Me me = CloudModel.Me.read(send("GET", "me", token, null, null, Repeat.SAFE));
        if (me == null) throw CloudProblem.unavailable("me");
        return me;
    }

    /** {@code GET /accounts}: the accounts and the service's clock. */
    public static final class AccountList {
        public final List<CloudModel.Account> accounts;
        public final long now;

        AccountList(List<CloudModel.Account> accounts, long now) {
            this.accounts = accounts;
            this.now = now;
        }
    }

    @WorkerThread
    public AccountList accounts(String token) throws CloudProblem {
        JsonObject o = require(send("GET", "accounts", token, null, null, Repeat.SAFE));
        return new AccountList(CloudModel.Account.list(o, "accounts"), Json.num(o, "now", 0));
    }

    @WorkerThread
    public CloudModel.Account account(String token, String id) throws CloudProblem {
        return accountOf(send("GET", accountPath(id, ""), token, null, null, Repeat.SAFE));
    }

    /** Changes an account; {@code rev} is the revision the change was made on ({@code If-Match}). */
    @WorkerThread
    public CloudModel.Account patchAccount(String token, String id, long rev, JsonObject patch) throws CloudProblem {
        return accountOf(send("PATCH", accountPath(id, ""), token, patch, "\"" + rev + "\"", Repeat.NEVER));
    }

    @WorkerThread
    public CloudModel.Account start(String token, String id) throws CloudProblem {
        return accountOf(send("POST", accountPath(id, "/start"), token, null, null, Repeat.SAFE));
    }

    @WorkerThread
    public CloudModel.Account stop(String token, String id) throws CloudProblem {
        return accountOf(send("POST", accountPath(id, "/stop"), token, null, null, Repeat.SAFE));
    }

    /** "Yeniden bağlan" (never repeated by itself). */
    @WorkerThread
    public CloudModel.Account restart(String token, String id) throws CloudProblem {
        return accountOf(send("POST", accountPath(id, "/restart"), token, null, null, Repeat.NEVER));
    }

    @WorkerThread
    public void sendLine(String token, String id, String line) throws CloudProblem {
        JsonObject body = new JsonObject();
        body.addProperty("line", line);
        send("POST", accountPath(id, "/send"), token, body, null, Repeat.NEVER);
    }

    @WorkerThread
    public void sendSealed(String token, String id, String sealed) throws CloudProblem {
        JsonObject body = new JsonObject();
        body.addProperty("sealed", sealed);
        send("POST", accountPath(id, "/send"), token, body, null, Repeat.NEVER);
    }

    /** The machine's masked log between {@code from} and {@code to} (Unix seconds), oldest first; {@code after} continues a page. */
    @WorkerThread
    public CloudModel.LogPage logs(String token, String id, long from, long to, int limit, @Nullable String after) throws CloudProblem {
        String path = accountPath(id, "/logs") + "?from=" + Math.max(0, from) + "&to=" + Math.max(0, to) + "&limit=" + limit + afterParam(after);
        return CloudModel.LogPage.read(send("GET", path, token, null, null, Repeat.SAFE));
    }

    /** "Göster"'s answer: the row, the service's nonce and the machine's sealed answer. */
    public static final class Revealed {
        public final String rowId;
        public final String nonce;
        public final String revealed;

        Revealed(String rowId, String nonce, String revealed) {
            this.rowId = rowId;
            this.nonce = nonce;
            this.revealed = revealed;
        }
    }

    @WorkerThread
    public Revealed reveal(String token, String id, String rowId, String publicKey) throws CloudProblem {
        JsonObject body = new JsonObject();
        body.addProperty("rowId", rowId);
        body.addProperty("pub", publicKey);
        JsonObject o = require(send("POST", accountPath(id, "/reveal"), token, body, null, Repeat.NEVER));
        String row = Json.str(o, "rowId");
        String nonce = Json.str(o, "nonce");
        String revealed = Json.str(o, "revealed");
        if (!rowId.equals(row) || nonce == null || nonce.isEmpty() || revealed == null || revealed.isEmpty()) {
            throw CloudProblem.failed("reveal answer for another row");
        }
        return new Revealed(row, nonce, revealed);
    }

    // ---- notifications ----

    @WorkerThread
    public CloudModel.NotifyPrefs notifications(String token) throws CloudProblem {
        return CloudModel.NotifyPrefs.read(send("GET", "me/notifications", token, null, null, Repeat.SAFE));
    }

    @WorkerThread
    public CloudModel.NotifyPrefs putNotifications(String token, CloudModel.NotifyPrefs prefs) throws CloudProblem {
        return CloudModel.NotifyPrefs.read(send("PUT", "me/notifications", token, prefs.toJson(), null, Repeat.NEVER));
    }

    // ---- AFK+ ----

    @WorkerThread
    public CloudModel.PlusDoc plus(String token, String id) throws CloudProblem {
        CloudModel.PlusDoc doc = CloudModel.PlusDoc.read(send("GET", accountPath(id, "/plus"), token, null, null, Repeat.SAFE));
        if (doc == null) throw CloudProblem.unavailable("plus settings");
        return doc;
    }

    /** Writes the whole AFK+ settings document on the revision it was read at ({@code If-Match}). */
    @WorkerThread
    public CloudModel.PlusDoc putPlus(String token, String id, long aiRev, JsonObject config) throws CloudProblem {
        CloudModel.PlusDoc doc = CloudModel.PlusDoc.read(send("PUT", accountPath(id, "/plus"), token, config, "\"" + aiRev + "\"", Repeat.NEVER));
        if (doc == null) throw CloudProblem.unavailable("plus settings");
        return doc;
    }

    /** {@code pause}, {@code resume}, {@code block}, {@code unblock}, {@code forget} or {@code reset_spent}: the AI view after it. */
    @WorkerThread
    public JsonObject plusControl(String token, String id, String action, @Nullable String name) throws CloudProblem {
        JsonObject body = new JsonObject();
        body.addProperty("action", action);
        if (name != null) body.addProperty("name", name);
        return require(send("POST", accountPath(id, "/plus/control"), token, body, null, Repeat.NEVER));
    }

    public static final class FeedPage {
        public final List<CloudModel.FeedItem> items;
        @Nullable
        public final String next;

        FeedPage(List<CloudModel.FeedItem> items, @Nullable String next) {
            this.items = items;
            this.next = next;
        }
    }

    @WorkerThread
    public FeedPage feed(String token, String id, @Nullable String after) throws CloudProblem {
        JsonObject o = require(send("GET", accountPath(id, "/plus/feed") + "?limit=50" + afterParam(after), token, null, null, Repeat.SAFE));
        return new FeedPage(CloudModel.FeedItem.list(o, "items"), Json.str(o, "next"));
    }

    public static final class DealPage {
        public final List<CloudModel.Deal> deals;
        @Nullable
        public final String next;

        DealPage(List<CloudModel.Deal> deals, @Nullable String next) {
            this.deals = deals;
            this.next = next;
        }
    }

    @WorkerThread
    public DealPage deals(String token, String id, @Nullable String after) throws CloudProblem {
        String query = after == null ? "" : "?after=" + encode(after);
        JsonObject o = require(send("GET", accountPath(id, "/plus/deals") + query, token, null, null, Repeat.SAFE));
        return new DealPage(CloudModel.Deal.list(o, "deals"), Json.str(o, "next"));
    }

    @WorkerThread
    public List<CloudModel.Approval> approvals(String token, String id) throws CloudProblem {
        return CloudModel.Approval.list(send("GET", accountPath(id, "/plus/approvals"), token, null, null, Repeat.SAFE), "approvals");
    }

    /** "Onayla" / "Reddet" / "Fiyatı düşür" ({@code maxPrice} with approve). */
    @WorkerThread
    public CloudModel.Approval decide(String token, String id, String approval, boolean approve, @Nullable Long maxPrice) throws CloudProblem {
        if (!ULID.matcher(approval).matches()) throw CloudProblem.failed("bad approval id");
        JsonObject body = new JsonObject();
        body.addProperty("decision", approve ? "approve" : "reject");
        if (approve && maxPrice != null) body.addProperty("maxPrice", maxPrice);
        JsonObject o = require(send("POST", accountPath(id, "/plus/approvals/" + approval), token, body, null, Repeat.NEVER));
        CloudModel.Approval answer = CloudModel.Approval.read(Json.obj(o, "approval"));
        if (answer == null) throw CloudProblem.unavailable("approval");
        return answer;
    }

    @WorkerThread
    public CloudModel.QuotaView quota(String token, String id) throws CloudProblem {
        CloudModel.QuotaView quota = CloudModel.QuotaView.read(send("GET", accountPath(id, "/plus/quota"), token, null, null, Repeat.SAFE));
        if (quota == null) throw CloudProblem.unavailable("quota");
        return quota;
    }

    @WorkerThread
    public CloudModel.DowngradePreview downgradePreview(String token, String id) throws CloudProblem {
        CloudModel.DowngradePreview preview = CloudModel.DowngradePreview.read(send("GET", accountPath(id, "/plus/downgrade"), token, null, null, Repeat.SAFE));
        if (preview == null) throw CloudProblem.unavailable("downgrade preview");
        return preview;
    }

    @WorkerThread
    public CloudModel.Account downgrade(String token, String id) throws CloudProblem {
        JsonObject body = new JsonObject();
        body.addProperty("confirm", true);
        return accountOf(send("POST", accountPath(id, "/plus/downgrade"), token, body, null, Repeat.NEVER));
    }

    // ---- AFK+ QoL: Mesajlar, typed rules, history, report ----

    public static final class InboxList {
        public final List<CloudModel.InboxThread> threads;
        @Nullable
        public final String next;

        InboxList(List<CloudModel.InboxThread> threads, @Nullable String next) {
            this.threads = threads;
            this.next = next;
        }
    }

    @WorkerThread
    public InboxList inbox(String token, String id, @Nullable String after) throws CloudProblem {
        JsonObject o = require(send("GET", accountPath(id, "/plus/inbox") + "?limit=30" + afterParam(after), token, null, null, Repeat.SAFE));
        return new InboxList(CloudModel.InboxThread.list(o, "threads"), Json.str(o, "next"));
    }

    @WorkerThread
    public CloudModel.InboxPage thread(String token, String id, String name, @Nullable String after) throws CloudProblem {
        return CloudModel.InboxPage.read(send("GET", threadPath(id, name, "") + "?limit=100" + afterParam(after), token, null, null, Repeat.SAFE));
    }

    @WorkerThread
    public CloudModel.InboxLine reply(String token, String id, String name, String text) throws CloudProblem {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        CloudModel.InboxLine line = CloudModel.InboxLine.read(Json.obj(require(send("POST", threadPath(id, name, ""), token, body, null, Repeat.NEVER)), "line"));
        if (line == null) throw CloudProblem.unavailable("reply");
        return line;
    }

    @WorkerThread
    public void markRead(String token, String id, String name, long upTo) throws CloudProblem {
        JsonObject body = new JsonObject();
        body.addProperty("upTo", upTo);
        send("POST", threadPath(id, name, "/read"), token, body, null, Repeat.NEVER);
    }

    /** "Şimdi devret": the assistant answers in that conversation again. */
    @WorkerThread
    public void handBack(String token, String id, String name) throws CloudProblem {
        JsonObject body = new JsonObject();
        body.addProperty("action", "end");
        send("POST", threadPath(id, name, "/handoff"), token, body, null, Repeat.NEVER);
    }

    @WorkerThread
    public CloudModel.ParseDraft parse(String token, String id, String text, String lang) throws CloudProblem {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("lang", "en".equals(lang) ? "en" : "tr");
        CloudModel.ParseDraft draft = CloudModel.ParseDraft.read(send("POST", accountPath(id, "/plus/parse"), token, body, null, Repeat.NEVER));
        if (draft == null) throw CloudProblem.unavailable("parse");
        return draft;
    }

    @WorkerThread
    public List<CloudModel.HistoryEntry> history(String token, String id) throws CloudProblem {
        return CloudModel.HistoryEntry.list(send("GET", accountPath(id, "/plus/history"), token, null, null, Repeat.SAFE), "entries");
    }

    @WorkerThread
    public CloudModel.PlusDoc restore(String token, String id, long rev, long aiRev) throws CloudProblem {
        CloudModel.PlusDoc doc = CloudModel.PlusDoc.read(send("POST", accountPath(id, "/plus/history/" + rev + "/restore"), token, null,
                "\"" + aiRev + "\"", Repeat.NEVER));
        if (doc == null) throw CloudProblem.unavailable("restore");
        return doc;
    }

    public static final class DealSummary {
        public final long from;
        public final long to;
        public final List<CloudModel.ItemSummary> items;
        public final JsonArray totals;

        DealSummary(long from, long to, List<CloudModel.ItemSummary> items, JsonArray totals) {
            this.from = from;
            this.to = to;
            this.items = items;
            this.totals = totals;
        }
    }

    @WorkerThread
    public DealSummary dealSummary(String token, String id) throws CloudProblem {
        JsonObject o = require(send("GET", accountPath(id, "/plus/deals/summary"), token, null, null, Repeat.SAFE));
        return new DealSummary(Json.num(o, "from", 0), Json.num(o, "to", 0), CloudModel.ItemSummary.list(o, "items"), Json.arr(o, "totals"));
    }

    @WorkerThread
    public List<CloudModel.WeeklyAccount> weekly(String token) throws CloudProblem {
        return CloudModel.WeeklyAccount.list(send("GET", "me/plus/report", token, null, null, Repeat.SAFE));
    }

    // ---- plumbing ----

    private enum Repeat { SAFE, NEVER }

    private static String afterParam(@Nullable String after) {
        return after == null ? "" : "&after=" + encode(after);
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An account's path: its id must be the contract's form (ids come from the service). */
    static String accountPath(String id, String rest) throws CloudProblem {
        if (id == null || !ACCOUNT_ID.matcher(id).matches()) throw CloudProblem.failed("bad account id");
        return "accounts/" + id + rest;
    }

    private static String threadPath(String id, String name, String rest) throws CloudProblem {
        if (name == null || !GAME_NAME.matcher(name).matches()) throw CloudProblem.failed("bad name");
        return accountPath(id, "/plus/inbox/" + name + rest);
    }

    private static JsonObject require(@Nullable JsonObject o) throws CloudProblem {
        if (o == null) throw CloudProblem.unavailable("empty answer");
        return o;
    }

    private static CloudModel.Account accountOf(@Nullable JsonObject o) throws CloudProblem {
        CloudModel.Account account = CloudModel.Account.read(Json.obj(o, "account"));
        if (account == null) throw CloudProblem.unavailable("no account in the answer");
        return account;
    }

    /** The path with ids taken out, for logs (no ids, names or cursors in the log). */
    static String route(String path) {
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        return bare.replaceAll("a_[0-9A-HJKMNP-TV-Z]{16}", ":id").replaceAll("/approvals/[0-9A-HJKMNP-TV-Z]{26}", "/approvals/:aid")
                .replaceAll("/inbox/[A-Za-z0-9_]{3,16}", "/inbox/:name").replaceAll("/history/\\d+", "/history/:rev");
    }

    /** Sends one request; the answer's JSON (null for 204), or the problem. */
    @Nullable
    @WorkerThread
    JsonObject send(String method, String path, @Nullable String token, @Nullable JsonObject body, @Nullable String ifMatch,
                    Repeat repeat) throws CloudProblem {
        int tries = repeat == Repeat.SAFE ? 2 : 1;
        CloudProblem last = null;
        for (int attempt = 0; attempt < tries; attempt++) {
            Attempt tried = new Attempt();
            Request.Builder request = new Request.Builder()
                    .url(mBase + path)
                    .tag(Attempt.class, tried)
                    .header("User-Agent", mUserAgent)
                    .header("Accept", "application/json");
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (ifMatch != null) request.header("If-Match", ifMatch);
            RequestBody requestBody = body != null ? RequestBody.create(body.toString(), JSON)
                    : (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) ? RequestBody.create(new byte[0], null) : null;
            request.method(method, requestBody);
            try (Response response = mClient.newCall(request.build()).execute()) {
                int status = response.code();
                String text = readBody(response.body());
                if (status == 204) return null;
                if (status >= 200 && status < 300) {
                    JsonObject o = Json.parseObject(text);
                    if (o == null) throw CloudProblem.unavailable("not JSON");
                    return o;
                }
                CloudProblem problem = CloudProblem.of(status, text, response.header("Retry-After"));
                mLog.line(method + " " + route(path) + " -> " + status + (problem.code == null ? "" : " " + problem.code));
                if (repeat == Repeat.SAFE && (status == 502 || status == 504) && attempt + 1 < tries) {
                    last = problem;
                    continue;
                }
                throw problem;
            } catch (IOException e) {
                mLog.line(method + " " + route(path) + " failed: " + e.getClass().getSimpleName() + (tried.newConnection ? "" : " (idle connection)"));
                last = CloudProblem.unavailable(e.getClass().getSimpleName());
                // The idle connection the server had already closed: the request never reached it, so a write
                // goes once more on a new connection. A timeout is not that: the service may have it.
                if (repeat == Repeat.NEVER && attempt == 0 && !tried.newConnection && !(e instanceof InterruptedIOException)) {
                    mClient.connectionPool().evictAll();
                    tries = 2;
                }
                if (attempt + 1 >= tries) throw last;
            }
        }
        throw last == null ? CloudProblem.unavailable("no answer") : last;
    }

    private static String readBody(@Nullable ResponseBody body) throws IOException {
        if (body == null) return "";
        long length = body.contentLength();
        if (length > MAX_BODY) throw new IOException("answer too large");
        byte[] bytes = body.bytes();
        if (bytes.length > MAX_BODY) throw new IOException("answer too large");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A new account row id: {@code ^[a-z][a-z0-9]{0,15}$}, not {@code console}, not in {@code taken}. */
    public static String newRowId(java.util.Random random, java.util.Collection<String> taken) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int tries = 0; tries < 1000; tries++) {
            StringBuilder id = new StringBuilder();
            id.append((char) ('a' + random.nextInt(26)));
            for (int i = 0; i < 5; i++) id.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String value = id.toString();
            if (!value.equals(CloudSeal.CONSOLE_ROW) && !taken.contains(value)) return value;
        }
        throw new IllegalStateException("no free row id");
    }

    /** {@code en} or {@code tr} for the service's {@code lang} fields. */
    public static String langOf(Locale locale) {
        return "en".equals(locale.getLanguage()) ? "en" : "tr";
    }

    /** The ids of a list, for logs and tests. */
    static List<String> ids(List<CloudModel.Account> accounts) {
        List<String> out = new ArrayList<>();
        for (CloudModel.Account a : accounts) out.add(a.id);
        return out;
    }
}
