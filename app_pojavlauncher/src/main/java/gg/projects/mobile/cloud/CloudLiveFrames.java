package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.List;

import gg.projects.mobile.cloud.console.Bars;
import gg.projects.mobile.cloud.inventory.InvFrames;
import gg.projects.mobile.social.Json;

/**
 * The live console socket's messages (services/cloud-afk README "Live console socket", "AFK+ live socket") turned
 * into calls, and its close codes into what to do next. Pure, so the socket ({@link CloudLive}) stays thin and the
 * tests read the same frames the service sends.
 */
public final class CloudLiveFrames {
    private CloudLiveFrames() {}

    public interface Listener {
        void hello(boolean hostOnline, List<String> accounts, long leaseSeconds, long now);

        void state(String acct, JsonObject live);

        void console(String acct, List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot);

        void host(boolean online);

        void error(String code, @Nullable String acct);

        void ai(String acct, JsonObject ai);

        void feed(String acct, List<CloudModel.FeedItem> items, boolean snapshot);

        void approval(String acct, CloudModel.Approval approval);

        void bye(@Nullable String reason);

        /** CAFK-FEAT: whether the inventory shows now ({@code invst}). */
        default void invState(String acct, InvFrames.Status status) {}

        /** The player inventory ({@code inv}), whole. */
        default void inventory(String acct, InvFrames.Inv frame) {}

        /** The window the server opened or closed ({@code win}). */
        default void window(String acct, InvFrames.Win frame) {}

        /** The answer to a move ({@code invres}); {@code error} null: the client sent it to the server. */
        default void invResult(String id, @Nullable InvFrames.Error error) {}

        /** The status strip ({@code bars}). */
        default void bars(String acct, Bars bars) {}
    }

    /** Hands one message to {@code listener}; false for one it does not know (ignored, the contract's rule). */
    public static boolean dispatch(@Nullable JsonObject message, Listener listener) {
        if (message == null) return false;
        String type = Json.str(message, "t", "");
        String acct = Json.str(message, "acct");
        switch (type) {
            case "hello": {
                JsonObject host = Json.obj(message, "host");
                listener.hello(Json.bool(host, "online", false), Json.strings(message, "accts"), Json.num(message, "leaseSeconds", 600),
                        Json.num(message, "now", 0));
                return true;
            }
            case "st": {
                JsonObject live = Json.obj(message, "live");
                if (acct == null || live == null) return false;
                listener.state(acct, live);
                return true;
            }
            case "con":
                if (acct == null) return false;
                listener.console(acct, CloudModel.ConsoleLine.list(message, "lines"), (int) Json.num(message, "skipped", 0),
                        Json.bool(message, "snapshot", false));
                return true;
            case "host":
                listener.host(Json.bool(message, "online", false));
                return true;
            case "error":
                listener.error(Json.str(message, "code", "bad_request"), acct);
                return true;
            case "aist": {
                JsonObject ai = Json.obj(message, "ai");
                if (acct == null || ai == null) return false;
                listener.ai(acct, ai);
                return true;
            }
            case "aifeed":
                if (acct == null) return false;
                listener.feed(acct, CloudModel.FeedItem.list(message, "items"), Json.bool(message, "snapshot", false));
                return true;
            case "aiapproval": {
                CloudModel.Approval approval = CloudModel.Approval.read(Json.obj(message, "approval"));
                if (acct == null || approval == null) return false;
                listener.approval(acct, approval);
                return true;
            }
            case "bye":
                listener.bye(Json.str(message, "reason"));
                return true;
            case "pong":
                return true;
            case "invst":
                if (acct == null) return false;
                listener.invState(acct, InvFrames.Status.read(message));
                return true;
            case "inv":
                if (acct == null) return false;
                listener.inventory(acct, InvFrames.Inv.read(message));
                return true;
            case "win":
                if (acct == null) return false;
                listener.window(acct, InvFrames.Win.read(message));
                return true;
            case "invres": {
                String id = Json.str(message, "id");
                if (id == null) return false;
                JsonObject error = Json.obj(message, "error");
                listener.invResult(id, error == null && Json.bool(message, "ok", false) ? null
                        : InvFrames.Error.read(error == null ? new JsonObject() : error));
                return true;
            }
            case "bars":
                if (acct == null) return false;
                listener.bars(acct, Bars.read(message));
                return true;
            default:
                return false;
        }
    }

    /** What a close code asks for. */
    public enum After {
        /** 4000: replaced by a newer socket of the player: no automatic reconnect ("Burada aç"). */
        REPLACED,
        /** 4001: the session ended: sign in again. */
        SIGNED_OUT,
        /** 4004: the player's accounts on this machine changed: reconnect now. */
        RECONNECT_NOW,
        /** 4008: too many messages: wait a minute. */
        WAIT_A_MINUTE,
        /** Anything else: reconnect with backoff. */
        BACKOFF
    }

    public static After after(int code) {
        switch (code) {
            case 4000: return After.REPLACED;
            case 4001: return After.SIGNED_OUT;
            case 4004: return After.RECONNECT_NOW;
            case 4008: return After.WAIT_A_MINUTE;
            default: return After.BACKOFF;
        }
    }

    public static JsonObject watch(String acct, boolean ai) {
        return watch(acct, ai, null);
    }

    /**
     * {@code watch}; {@code inv} true streams the inventory too, false switches it off on an account the socket already
     * watches (no new console snapshot), null leaves it out (an older service never sees the field).
     */
    public static JsonObject watch(String acct, boolean ai, @Nullable Boolean inv) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "watch");
        o.addProperty("acct", acct);
        if (ai) o.addProperty("ai", true);
        if (inv != null) o.addProperty("inv", inv);
        return o;
    }

    public static JsonObject unwatch(String acct) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "unwatch");
        o.addProperty("acct", acct);
        return o;
    }

    /** {@code {"t":"keep"}}: renews the watches' leases every 5 minutes while the screen is up. */
    public static final String KEEP = "{\"t\":\"keep\"}";
    /** Exactly these bytes, every 30-45 s: the Durable Object's auto-response answers without waking it. */
    public static final String PING = "{\"t\":\"ping\"}";
    public static final long KEEP_MS = 5 * 60_000;
    public static final long PING_MIN_MS = 30_000;
    public static final long PING_MAX_MS = 45_000;
    /** Nothing within this long of a ping: the socket is gone. */
    public static final long PONG_WAIT_MS = 20_000;
}
